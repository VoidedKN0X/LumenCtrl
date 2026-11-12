package com.vknxworks.lumenctrl

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.hardware.usb.UsbDevice
import com.vknxworks.lumenctrl.data.CamPhoto
import com.vknxworks.lumenctrl.data.CameraImport
import com.vknxworks.lumenctrl.data.Album
import com.vknxworks.lumenctrl.data.Catalog
import com.vknxworks.lumenctrl.data.ImageLoader
import com.vknxworks.lumenctrl.data.Settings
import com.vknxworks.lumenctrl.data.Version
import com.vknxworks.lumenctrl.edit.AiAtlas
import com.vknxworks.lumenctrl.edit.AutoTools
import com.vknxworks.lumenctrl.edit.HdrMerge
import com.vknxworks.lumenctrl.render.Hdr
import com.vknxworks.lumenctrl.render.VideoExporter
import com.vknxworks.lumenctrl.render.SourceGain
import com.vknxworks.lumenctrl.edit.Denoiser
import com.vknxworks.lumenctrl.edit.Segmenter
import com.vknxworks.lumenctrl.model.AiKind
import com.vknxworks.lumenctrl.model.EditParams
import com.vknxworks.lumenctrl.model.LocalMask
import com.vknxworks.lumenctrl.model.MAX_MASKS
import com.vknxworks.lumenctrl.model.MAX_SPOTS
import com.vknxworks.lumenctrl.model.MaskType
import com.vknxworks.lumenctrl.model.Param
import com.vknxworks.lumenctrl.model.Spot
import com.vknxworks.lumenctrl.render.ExportOptions
import com.vknxworks.lumenctrl.render.Exporter
import com.vknxworks.lumenctrl.render.Histogram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class Screen { LIBRARY, EDITOR, SLIDESHOW, COLLAGE, SETTINGS, CAMERA, VIDEO }

enum class LibFilter(val label: String) {
    ALL("All"), PICKS("Picks"), REJECTED("Rejected"), EDITED("Edited"), UNEDITED("Unedited"), RATED("★ 3+"),
}

enum class SortOrder(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), IMPORTED("Recently added") }

class LumenViewModel(app: Application) : AndroidViewModel(app) {
    private val catalog = Catalog(app)
    val settings = Settings(app)

    var screen by mutableStateOf(Screen.LIBRARY)

    // ---- App lock ----
    var unlocked by mutableStateOf(!settings.appLock)
    private var stoppedAt = 0L
    fun onAppStop() { stoppedAt = System.currentTimeMillis() }
    fun onAppStart() {
        // Short grace period so the photo picker, camera and share sheet don't re-lock the app.
        if (settings.appLock && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000) unlocked = false
    }

    // ---- Library ----
    var allPhotos by mutableStateOf<List<Catalog.Photo>>(emptyList()); private set
    var albums by mutableStateOf<List<Album>>(emptyList()); private set
    var query by mutableStateOf("")
    var filter by mutableStateOf(LibFilter.ALL)
    var albumFilter by mutableStateOf<String?>(null)
    var sort by mutableStateOf(SortOrder.NEWEST)
    var selection by mutableStateOf<Set<String>>(emptySet()); private set
    var thumbVersion by mutableIntStateOf(0); private set

    val photos by derivedStateOf {
        val q = query.trim().lowercase()
        allPhotos.asSequence()
            .filter { albumFilter == null || albumFilter in it.meta.albums }
            .filter {
                when (filter) {
                    LibFilter.ALL -> it.meta.flag >= 0
                    LibFilter.PICKS -> it.meta.flag == 1
                    LibFilter.REJECTED -> it.meta.flag == -1
                    LibFilter.EDITED -> it.edited
                    LibFilter.UNEDITED -> !it.edited
                    LibFilter.RATED -> it.meta.rating >= 3
                }
            }
            .filter { q.isEmpty() || q in it.meta.searchText }
            .sortedWith(
                when (sort) {
                    SortOrder.NEWEST -> compareByDescending { it.sortTime }
                    SortOrder.OLDEST -> compareBy { it.sortTime }
                    SortOrder.IMPORTED -> compareByDescending { it.meta.importedAt }
                }
            ).toList()
    }

    // ---- Editor ----
    var openPhoto by mutableStateOf<Catalog.Photo?>(null); private set
    var preview by mutableStateOf<Bitmap?>(null); private set
    var params by mutableStateOf(EditParams()); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var clipboard by mutableStateOf<EditParams?>(null); private set
    var histogram by mutableStateOf<Histogram?>(null)
    var versions by mutableStateOf<List<Version>>(emptyList()); private set
    var history by mutableStateOf<List<String>>(emptyList()); private set
    /** AI mask images for the open photo, by mask id. */
    var aiMasks by mutableStateOf<Map<String, Bitmap>>(emptyMap()); private set
    /** The open photo's own Ultra HDR gain map (null for SDR photos). */
    var sourceGain by mutableStateOf<SourceGain?>(null); private set
    /** Rendered Ultra HDR preview (SDR + gain map) for HDR view. */
    var hdrPreview by mutableStateOf<Bitmap?>(null); private set

    fun renderHdrPreview(maxDim: Int) {
        val photo = openPhoto ?: return
        val p = params
        viewModelScope.launch {
            hdrPreview = runCatching {
                withContext(Dispatchers.Default) {
                    val src = ImageLoader.decode(photo.original, maxDim)
                    Exporter.render(src, p, maxDim, atlasFor(photo, p), sourceGain, withGainmap = true, denoised = denoised)
                        .also { src.recycle() }
                }
            }.getOrNull()
        }
    }

    fun clearHdrPreview() { hdrPreview = null }

    // ---- AI Denoise ----
    /** The open photo's denoised base at preview size, or null if it hasn't been computed. */
    var denoised by mutableStateOf<Bitmap?>(null); private set
    /** 0..1 while a denoise runs, else null. */
    var denoiseProgress by mutableStateOf<Float?>(null); private set
    var denoiseModel by mutableStateOf<Denoiser.Model?>(null); private set
    @Volatile private var denoiseCancel = false

    /**
     * Runs the bundled denoise model over the full photo (up to 4096 px) on device,
     * stores the result beside the original, and turns AI Denoise on.
     */
    fun runDenoise(model: Denoiser.Model) {
        val photo = openPhoto ?: return
        if (photo.isVideo || denoiseProgress != null) return
        val app = getApplication<Application>()
        denoiseCancel = false
        denoiseProgress = 0f
        denoiseModel = model
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            runCatching {
                withContext(Dispatchers.Default) {
                    val src = ImageLoader.decode(photo.original, Denoiser.MAX_EDGE)
                    val out = try {
                        Denoiser.denoise(app, src, model,
                            onProgress = { f -> viewModelScope.launch { if (denoiseProgress != null) denoiseProgress = f } },
                            isCancelled = { denoiseCancel })
                    } finally { src.recycle() }
                    val f = Denoiser.fileFor(photo.original)
                    val tmp = File(f.path + ".tmp")
                    tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 97, it) }
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                    out.recycle()
                    ImageLoader.decode(f, 3072)
                }
            }.onSuccess { bmp ->
                android.util.Log.d("LumenDenoise", "${model.name} done in ${System.currentTimeMillis() - t0} ms")
                if (openPhoto?.id == photo.id) {
                    denoised = bmp
                    val amt = params[Param.AI_DENOISE]
                    edit("AI Denoise · ${model.label}") { it.with(Param.AI_DENOISE, if (amt > 0f) amt else 0.8f) }
                }
                message = "AI Denoise done · ${(System.currentTimeMillis() - t0) / 1000} s on device"
            }.onFailure {
                if (it !is Denoiser.Cancelled) message = "AI Denoise failed: ${it.message}"
            }
            denoiseProgress = null
            denoiseModel = null
        }
    }

    fun cancelDenoise() { denoiseCancel = true }

    /** Removes the denoised base and sets the amount to 0. */
    fun removeDenoise() {
        val photo = openPhoto ?: return
        denoised = null
        edit("Remove AI Denoise") { it.with(Param.AI_DENOISE, 0f) }
        viewModelScope.launch(Dispatchers.IO) { Denoiser.fileFor(photo.original).delete() }
    }

    /** Merges 2-7 bracketed exposures into a new Ultra HDR photo in the library. */
    fun mergeHdr(ids: List<String>) {
        if (ids.size < 2) return
        viewModelScope.launch {
            busy = "Merging ${ids.size} exposures to HDR"
            runCatching {
                val files = ids.map { catalog.photo(it) }.filter { !it.isVideo }.map { it.original }
                val r = withContext(Dispatchers.Default) { HdrMerge.merge(files) }
                android.util.Log.d("LumenHDR", "merge shifts=${r.shifts} exposures=${r.exposures} gainmap=${r.gainmap != null}")
                catalog.importBitmap(r.sdr, "HDR merge ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())}.jpg")
                r.gainmap != null
            }.onSuccess { hdr ->
                selection = emptySet()
                message = if (hdr) "HDR photo added to your library" else "Merged photo added (HDR needs Android 14)"
            }.onFailure { message = "HDR merge failed: ${it.message}" }
            busy = null
            refresh()
        }
    }

    // ---- Slideshow / collage ----
    var showIds by mutableStateOf<List<String>>(emptyList()); private set

    // ---- Shared UI ----
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    /** Files ready to hand to the system share sheet; the UI launches it and clears this. */
    var shareRequest by mutableStateOf<List<Uri>?>(null)

    // ---- USB camera import ----
    private val cam = CameraImport(app)
    var camDevices by mutableStateOf<List<UsbDevice>>(emptyList()); private set
    var camConnected by mutableStateOf<String?>(null); private set
    var camPhotos by mutableStateOf<List<CamPhoto>>(emptyList()); private set
    var camSelected by mutableStateOf<Set<Int>>(emptySet())
    var camThumbs by mutableStateOf<Map<Int, Bitmap>>(emptyMap()); private set
    var camStatus by mutableStateOf<String?>(null); private set

    fun openCameraImport() { screen = Screen.CAMERA; refreshCameras() }

    fun refreshCameras() {
        camDevices = cam.cameras()
        if (camDevices.isEmpty()) { cam.close(); camConnected = null; camPhotos = emptyList(); camThumbs = emptyMap() }
    }

    fun connectCamera(d: UsbDevice) {
        viewModelScope.launch {
            camStatus = "Waiting for USB permission"
            if (!cam.requestPermission(d)) { camStatus = "USB access was declined"; return@launch }
            camStatus = "Connecting to ${cam.label(d)}"
            if (!cam.open(d)) { camStatus = "Couldn't open the camera. Set it to PTP/MTP (PC connection) mode."; return@launch }
            camConnected = cam.label(d)
            camStatus = "Reading the card"
            val imported = allPhotos.map { it.meta.name to it.meta.bytes }.toSet()
            camPhotos = cam.listPhotos { n, s -> (n to s) in imported }
            camSelected = camPhotos.filter { !it.alreadyImported }.map { it.handle }.toSet()
            camStatus = null
            // Thumbnails stream in after the list is shown.
            for (p in camPhotos.take(400)) {
                if (camConnected == null) break
                cam.thumbnail(p.handle)?.let { camThumbs = camThumbs + (p.handle to it) }
            }
        }
    }

    fun importFromCamera() {
        val chosen = camPhotos.filter { it.handle in camSelected }
        if (chosen.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            var skipped = 0
            val okHandles = mutableSetOf<Int>()
            val tmpDir = File(app.cacheDir, "usb")
            for (p in chosen) {
                busy = "Importing ${okHandles.size + skipped + 1} of ${chosen.size}"
                val tmp = File(tmpDir, "${System.nanoTime()}_${p.name}")
                val ok = cam.download(p.handle, tmp) && catalog.importFile(tmp, p.name, p.takenAt) != null
                if (ok) okHandles += p.handle else { skipped++; tmp.delete() }
            }
            val done = okHandles.size
            busy = null
            refresh()
            camPhotos = camPhotos.map { if (it.handle in okHandles) it.copy(alreadyImported = true) else it }
            camSelected = emptySet()
            message = if (skipped == 0) "Imported $done from camera" else "Imported $done · $skipped not readable on this phone"
        }
    }

    fun leaveCameraImport() { cam.close(); camConnected = null; camPhotos = emptyList(); camThumbs = emptyMap(); screen = Screen.LIBRARY }

    private data class Step(val label: String, val before: EditParams)
    private var committed = EditParams()
    private var pendingLabel = "Edit"
    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            allPhotos = catalog.photos()
            albums = catalog.albums()
            selection = selection.filter { id -> allPhotos.any { it.id == id } }.toSet()
        }
    }

    fun consumeMessage() { message = null }
    fun toast(text: String) { message = text }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "Importing ${uris.size} photo(s)"
            val ids = catalog.import(uris)
            albumFilter?.let { a -> catalog.updateMeta(ids) { it.copy(albums = it.albums + a) } }
            busy = null
            refresh()
            message = if (ids.size == uris.size) "Imported ${ids.size} photo(s)" else "Imported ${ids.size} of ${uris.size}"
        }
    }

    /** Imports a camera capture from app cache, then deletes the temporary file. */
    fun importCapture(uri: Uri, file: File) {
        viewModelScope.launch {
            catalog.import(listOf(uri))
            withContext(Dispatchers.IO) { file.delete() }
            refresh()
            message = "Photo added"
        }
    }

    // ---- Selection & batch ----
    fun toggleSelect(id: String) { selection = if (id in selection) selection - id else selection + id }
    fun selectAll() { selection = photos.map { it.id }.toSet() }
    fun clearSelection() { selection = emptySet() }

    fun rate(ids: Set<String>, rating: Int) = updateMeta(ids) { it.copy(rating = rating) }
    fun flag(ids: Set<String>, flag: Int) = updateMeta(ids) { it.copy(flag = flag) }
    fun addToAlbum(ids: Set<String>, albumId: String) = updateMeta(ids) { it.copy(albums = it.albums + albumId) }
    fun removeFromAlbum(ids: Set<String>, albumId: String) = updateMeta(ids) { it.copy(albums = it.albums - albumId) }

    private fun updateMeta(ids: Set<String>, t: (com.vknxworks.lumenctrl.data.PhotoMeta) -> com.vknxworks.lumenctrl.data.PhotoMeta) {
        viewModelScope.launch { catalog.updateMeta(ids, t); refresh() }
    }

    fun createAlbum(name: String, add: Set<String> = emptySet()) {
        viewModelScope.launch {
            val a = catalog.createAlbum(name)
            if (add.isNotEmpty()) catalog.updateMeta(add) { it.copy(albums = it.albums + a.id) }
            refresh()
            message = if (add.isEmpty()) "Album created" else "Added ${add.size} to ${a.name}"
        }
    }

    fun renameAlbum(id: String, name: String) { viewModelScope.launch { catalog.renameAlbum(id, name); refresh() } }

    fun deleteAlbum(id: String) {
        viewModelScope.launch {
            catalog.deleteAlbum(id)
            if (albumFilter == id) albumFilter = null
            refresh()
        }
    }

    fun deletePhotos(ids: Set<String>) {
        viewModelScope.launch {
            allPhotos.filter { it.id in ids }.forEach { catalog.delete(it) }
            selection = emptySet()
            refresh()
            message = "Removed ${ids.size} photo(s)"
        }
    }

    fun pasteToSelected() {
        val c = clipboard ?: return
        val ids = selection
        viewModelScope.launch {
            busy = "Pasting settings"
            allPhotos.filter { it.id in ids }.forEach { p ->
                val merged = catalog.loadParams(p).pasteAdjustmentsFrom(c)
                catalog.saveParams(p, merged)
                renderThumb(p, merged)
            }
            busy = null
            thumbVersion++
            refresh()
            message = "Pasted settings to ${ids.size} photo(s)"
        }
    }

    fun exportPhotos(allIds: List<String>, options: ExportOptions, share: Boolean) {
        // Videos go through the video exporter (1080p) instead.
        val videoIds = allIds.filter { id -> allPhotos.firstOrNull { it.id == id }?.isVideo == true }
        if (videoIds.isNotEmpty()) exportVideos(videoIds, 1920, share)
        val ids = allIds - videoIds.toSet()
        if (ids.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = if (ids.size == 1) "Exporting" else "Exporting ${ids.size} photos"
            runCatching {
                withContext(Dispatchers.Default) {
                    val outDir = File(app.cacheDir, if (share) "share" else "export")
                    if (share) outDir.deleteRecursively()
                    val uris = mutableListOf<Uri>()
                    ids.forEachIndexed { i, id ->
                        val p = catalog.photo(id)
                        val params = catalog.loadParams(p)
                        val base = p.meta.name.substringBeforeLast('.').ifBlank { "Lumen" }
                            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(40) + "_" + stamp() + if (ids.size > 1) "_${i + 1}" else ""
                        val file = Exporter.renderToFile(app, p.original, params, options, outDir, base, atlasFor(p, params))
                        if (share) {
                            uris += FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                        } else {
                            Exporter.saveToGallery(app, file, options.format.mime)
                            file.delete()
                        }
                    }
                    uris
                }
            }.onSuccess { uris ->
                if (share) shareRequest = uris else message = "Saved ${ids.size} to Pictures/Lumen"
            }.onFailure { message = "Export failed: ${it.message}" }
            busy = null
        }
    }

    fun startSlideshow(all: List<String>) {
        val ids = all.filter { id -> allPhotos.firstOrNull { it.id == id }?.isVideo != true }
        if (ids.isNotEmpty()) { showIds = ids; screen = Screen.SLIDESHOW }
    }
    fun startCollage(ids: List<String>) { showIds = ids.take(6); screen = Screen.COLLAGE }
    fun backToLibrary() { screen = Screen.LIBRARY }

    /** Renders a photo with its edits for slideshow/collage. */
    suspend fun renderEdited(id: String, maxDim: Int): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val p = catalog.photo(id)
            val params = catalog.loadParams(p)
            val src = ImageLoader.decodeAny(p, maxDim, params.video.trimStartMs + 500)
            if (params.isDefault()) src else {
                val dn = Exporter.loadDenoised(p.original, params, maxDim)
                Exporter.render(src, params, maxDim, atlasFor(p, params), denoised = dn).also { src.recycle(); dn?.recycle() }
            }
        }.getOrNull()
    }

    /** AI mask atlas for a photo's edit, loaded from disk (null when it has no AI masks). */
    private suspend fun atlasFor(photo: Catalog.Photo, p: EditParams): Bitmap? {
        if (p.masks.none { it.type == MaskType.AI }) return null
        val maps = if (photo.id == openPhoto?.id) aiMasks else catalog.loadAiMasks(photo)
        return withContext(Dispatchers.Default) { AiAtlas.build(p.masks, maps) }
    }

    /**
     * Offline AI mask: runs the bundled segmentation model on the preview,
     * stores the refined mask beside the photo, and adds it as a local mask.
     */
    fun addAiMask(kind: AiKind, onAdded: (Int) -> Unit) {
        val bmp = preview ?: return
        val photo = openPhoto ?: return
        if (params.masks.size >= MAX_MASKS) { message = "Up to $MAX_MASKS masks per photo"; return }
        if (params.masks.count { it.type == MaskType.AI } >= AiAtlas.MAX_AI) { message = "Up to ${AiAtlas.MAX_AI} AI masks per photo"; return }
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = "Finding ${kind.label.lowercase()} on device"
            val t0 = System.currentTimeMillis()
            runCatching {
                val mask = withContext(Dispatchers.Default) { Segmenter.segment(app, bmp, kind) }
                val id = UUID.randomUUID().toString().take(8)
                catalog.saveAiMask(photo, id, mask)
                aiMasks = aiMasks + (id to mask)
                id
            }.onSuccess { id ->
                android.util.Log.d("LumenAI", "${kind.name} mask in ${System.currentTimeMillis() - t0} ms")
                edit("Select ${kind.label.lowercase()}") {
                    it.copy(masks = it.masks + LocalMask(id, MaskType.AI, 0f, 0f, 0f, 0f, feather = 0.3f, ai = kind))
                }
                onAdded(params.masks.lastIndex)
            }.onFailure { message = if (it is Segmenter.NothingFound) it.message else "AI mask failed: ${it.message}" }
            busy = null
        }
    }

    fun saveCollage(bitmap: Bitmap) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = "Saving collage"
            runCatching {
                withContext(Dispatchers.IO) { Exporter.saveBitmapToGallery(app, bitmap, "Lumen_collage_${stamp()}") }
                catalog.importBitmap(bitmap, "Collage ${stamp()}")
            }.onSuccess { message = "Collage saved to Pictures/Lumen and your library" }
                .onFailure { message = "Couldn't save collage: ${it.message}" }
            busy = null
            refresh()
        }
    }

    suspend fun storageBytes() = catalog.storageBytes()

    // ---- Editor lifecycle ----

    fun open(photo: Catalog.Photo) {
        if (photo.isVideo) { openVideo(photo); return }
        openPhoto = photo
        preview = null
        histogram = null
        screen = Screen.EDITOR
        viewModelScope.launch {
            val loaded = catalog.loadParams(photo)
            params = loaded
            committed = loaded
            undoStack.clear(); redoStack.clear(); updateHistory()
            versions = catalog.versions(photo)
            aiMasks = catalog.loadAiMasks(photo)
            sourceGain = withContext(Dispatchers.IO) { Hdr.readSourceGain(photo.original) }
            denoised = withContext(Dispatchers.IO) {
                Denoiser.fileFor(photo.original).takeIf { it.exists() }
                    ?.let { runCatching { ImageLoader.decode(it, 3072) }.getOrNull() }
            }
            hdrPreview = null
            preview = runCatching {
                // 3072 px keeps detail when zooming in the loupe.
                withContext(Dispatchers.IO) { ImageLoader.decode(photo.original, 3072) }
            }.getOrElse {
                message = "Can't open this photo: ${it.message}"
                openPhoto = null
                screen = Screen.LIBRARY
                null
            }
        }
    }

    fun close() {
        val photo = openPhoto ?: return
        val finalParams = params
        val keepAi = (listOf(finalParams) + versions.map { it.params }).flatMap { p -> p.masks.map { it.id } }.toSet()
        openPhoto = null
        preview = null
        aiMasks = emptyMap()
        sourceGain = null
        denoised = null
        denoiseCancel = true
        hdrPreview = null
        screen = Screen.LIBRARY
        viewModelScope.launch {
            catalog.saveParams(photo, finalParams)
            catalog.pruneAiMasks(photo, keepAi)
            renderThumb(photo, finalParams)
            thumbVersion++
            refresh()
        }
    }

    // ---- Video ----

    private fun openVideo(photo: Catalog.Photo) {
        openPhoto = photo
        preview = null
        histogram = null
        sourceGain = null
        denoised = null
        aiMasks = emptyMap()
        screen = Screen.VIDEO
        viewModelScope.launch {
            val loaded = catalog.loadParams(photo)
            params = loaded
            committed = loaded
            undoStack.clear(); redoStack.clear(); updateHistory()
            versions = catalog.versions(photo)
            // A still frame stands in for the photo: Auto, the WB eyedropper and crop use it.
            preview = withContext(Dispatchers.IO) { ImageLoader.decodeAny(photo, 1600, loaded.video.trimStartMs + 500) }
        }
    }

    /** Refreshes the still frame (e.g. when playback pauses) so Auto and WB use what's on screen. */
    fun refreshVideoFrame(timeMs: Long) {
        val photo = openPhoto ?: return
        if (!photo.isVideo) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ImageLoader.videoFrame(photo.original, timeMs, 1600, photo.meta.width, photo.meta.height) }
                ?.let { preview = it }
        }
    }

    var videoProgress by mutableStateOf<Float?>(null); private set

    private fun clipFor(p: Catalog.Photo, params: EditParams) =
        VideoExporter.Clip(p.original, params, p.meta.width, p.meta.height, p.meta.durationMs)

    /** Exports videos (each with its edit) to Movies/Lumen, or shares them. */
    fun exportVideos(ids: List<String>, maxEdge: Int, share: Boolean, reel: Boolean = false) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (openPhoto?.isVideo == true) catalog.saveParams(openPhoto!!, params)
            val clips = ids.map { catalog.photo(it) }.filter { it.isVideo }.map { clipFor(it, catalog.loadParams(it)) }
            if (clips.isEmpty()) return@launch
            val jobs = if (reel) listOf(clips) else clips.map { listOf(it) }
            val outDir = File(app.cacheDir, if (share) "share" else "export").apply { if (share) deleteRecursively(); mkdirs() }
            val uris = mutableListOf<Uri>()
            var audioDropped = false
            runCatching {
                jobs.forEachIndexed { j, job ->
                    val label = if (reel) "Rendering reel" else "Exporting video ${j + 1} of ${jobs.size}"
                    busy = label
                    val out = File(outDir, "Lumen_${if (reel) "reel" else "video"}_${stamp()}_${j + 1}.mp4")
                    val audioOk = withContext(Dispatchers.Default) {
                        VideoExporter.export(job, out, maxEdge) { f -> viewModelScope.launch { busy = "$label · ${(f * 100).toInt()}%" } }
                    }
                    if (!audioOk) audioDropped = true
                    if (share) uris += FileProvider.getUriForFile(app, "${app.packageName}.files", out)
                    else {
                        withContext(Dispatchers.IO) { VideoExporter.saveToGallery(app, out) }
                        if (reel) catalog.import(listOf(Uri.fromFile(out)))
                        out.delete()
                    }
                }
            }.onSuccess {
                if (share) shareRequest = uris
                else message = (if (reel) "Reel saved to Movies/Lumen and your library" else "Saved ${jobs.size} video(s) to Movies/Lumen") +
                    if (audioDropped) " · audio left out (speed change or mixed formats)" else ""
            }.onFailure { message = "Video export failed: ${it.message}" }
            busy = null
            refresh()
        }
    }

    /** Renders the edited thumbnail off-screen, the way Lightroom refreshes its grid. */
    private suspend fun renderThumb(photo: Catalog.Photo, p: EditParams) {
        runCatching {
            withContext(Dispatchers.Default) {
                if (p.isDefault()) photo.thumb.delete()
                else {
                    val src = ImageLoader.decodeAny(photo, 1024, p.video.trimStartMs + 500)
                    val dn = Exporter.loadDenoised(photo.original, p, 1024)
                    val thumb = Exporter.render(src, p, maxOut = 512, aiAtlas = atlasFor(photo, p), denoised = dn)
                    Exporter.saveJpeg(thumb, photo.thumb)
                    src.recycle(); thumb.recycle(); dn?.recycle()
                }
            }
        }
    }

    // ---- Editing & history ----

    /** A live slider move: re-renders, no history entry until [commit]. */
    fun setLive(p: Param, v: Float) { params = params.with(p, v); pendingLabel = p.label }

    /** A live change from an on-image handle (crop, mask, spot). */
    fun setLiveParams(p: EditParams, label: String) { params = p; pendingLabel = label }

    fun commit() {
        if (params == committed) return
        undoStack.addLast(Step(pendingLabel, committed))
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        committed = params
        updateHistory()
        persist()
    }

    /** A discrete edit with its own history step. */
    fun edit(label: String, transform: (EditParams) -> EditParams) {
        params = transform(params)
        pendingLabel = label
        commit()
    }

    fun reset(p: Param) = edit("Reset ${p.label}") { it.with(p, p.default) }

    fun undo() {
        val step = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Step(step.label, params))
        params = step.before; committed = step.before
        updateHistory(); persist()
    }

    fun redo() {
        val step = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Step(step.label, params))
        params = step.before; committed = step.before
        updateHistory(); persist()
    }

    /** Steps back until [count] history entries remain. */
    fun undoTo(count: Int) { while (undoStack.size > count) undo() }

    private fun updateHistory() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        history = undoStack.map { it.label }
    }

    private fun persist() {
        val photo = openPhoto ?: return
        val p = params
        viewModelScope.launch { catalog.saveParams(photo, p) }
    }

    fun copySettings() { clipboard = params; message = "Settings copied" }
    fun pasteSettings() { clipboard?.let { c -> edit("Paste settings") { it.pasteAdjustmentsFrom(c) } } }

    // ---- Auto tools (on-device statistics) ----

    fun autoTone() {
        val bmp = preview ?: return
        viewModelScope.launch {
            val auto = withContext(Dispatchers.Default) { AutoTools.autoTone(bmp) }
            edit("Auto") { p -> auto.entries.fold(p) { acc, (k, v) -> acc.with(k, v) } }
        }
    }

    fun pickWhiteBalance(u: Float, v: Float) {
        val bmp = preview ?: return
        viewModelScope.launch {
            val (t, i) = withContext(Dispatchers.Default) { AutoTools.neutralWhiteBalance(bmp, u, v) }
            edit("White balance") { it.with(Param.TEMPERATURE, t).with(Param.TINT, i) }
        }
    }

    fun autoStraighten() {
        val bmp = preview ?: return
        viewModelScope.launch {
            val angle = withContext(Dispatchers.Default) { AutoTools.autoStraighten(bmp, params) }
            if (angle == null) message = "No clear horizon or verticals found"
            else edit("Auto straighten") { it.with(Param.STRAIGHTEN, angle) }
        }
    }

    // ---- Heal spots ----

    fun addSpot(u: Float, v: Float, radius: Float) {
        val bmp = preview ?: return
        if (params.spots.size >= MAX_SPOTS) { message = "Up to $MAX_SPOTS spots per photo"; return }
        viewModelScope.launch {
            val (sx, sy) = withContext(Dispatchers.Default) { AutoTools.findHealSource(bmp, u, v, radius) }
            edit("Heal spot") { it.copy(spots = it.spots + Spot(u, v, sx, sy, radius)) }
        }
    }

    // ---- Masks ----

    fun addMask(type: MaskType): Int {
        if (params.masks.size >= MAX_MASKS) { message = "Up to $MAX_MASKS masks per photo"; return params.masks.size - 1 }
        val id = UUID.randomUUID().toString().take(8)
        val m = when (type) {
            MaskType.LINEAR -> LocalMask(id, type, 0.5f, 0.15f, 0.5f, 0.55f)
            MaskType.RADIAL, MaskType.AI -> LocalMask(id, MaskType.RADIAL, 0.5f, 0.5f, 0.25f, 0.25f, feather = 0.6f)
            MaskType.LUMA -> LocalMask(id, type, 0.6f, 0f, 1f, 0f, feather = 0.4f)
            MaskType.COLOR -> LocalMask(id, type, 0.6f, 0f, 0.08f, 0f, feather = 0.5f)
        }
        edit("Add ${type.label.lowercase()} mask") { it.copy(masks = it.masks + m) }
        return params.masks.size - 1
    }

    // ---- Versions ----

    fun saveVersion() {
        val photo = openPhoto ?: return
        val v = Version("Version ${versions.size + 1}", System.currentTimeMillis(), params)
        versions = versions + v
        viewModelScope.launch { catalog.saveVersions(photo, versions) }
        message = "Saved ${v.name}"
    }

    fun restoreVersion(v: Version) = edit("Restore ${v.name}") { v.params }

    fun deleteVersion(v: Version) {
        val photo = openPhoto ?: return
        versions = versions - v
        viewModelScope.launch { catalog.saveVersions(photo, versions) }
    }

    fun exportCurrent(options: ExportOptions, share: Boolean) {
        val photo = openPhoto ?: return
        viewModelScope.launch {
            catalog.saveParams(photo, params)
            exportPhotos(listOf(photo.id), options, share)
        }
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
}
