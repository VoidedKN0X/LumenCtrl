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
import com.vknxworks.lumenctrl.edit.Removals
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
import com.vknxworks.lumenctrl.ui.lock.AppLock
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
    ALL(tr(R.string.all)), PICKS(tr(R.string.picks)), REJECTED(tr(R.string.rejected)), EDITED(tr(R.string.edited)), UNEDITED(tr(R.string.unedited)), RATED("★ 3+"),
}

enum class SortOrder(val label: String) { NEWEST(tr(R.string.newest)), OLDEST(tr(R.string.oldest)), IMPORTED(tr(R.string.recently_added)) }

class LumenViewModel(app: Application) : AndroidViewModel(app), com.vknxworks.lumenctrl.ui.lock.LockHost {
    private val catalog = Catalog(app)
    val settings = Settings(app)

    var screen by mutableStateOf(Screen.LIBRARY)

    // ---- App lock (the shared Ctrl series PIN lock, see ui/lock/AppLock) ----
    override val vault = com.vknxworks.lumenctrl.data.lock.LockVault(app, "lumenctrl")

    /**
     * The old lock (the phone's screen lock) was on and no PIN is set yet: the app asks for the screen lock once
     * more and then for a new app PIN (or turns the lock off when you cancel).
     */
    var legacyLock by mutableStateOf(settings.appLock && !vault.enabled); private set
    var legacyUnlocked by mutableStateOf(false); private set

    /** Something of the app is behind a lock screen right now. */
    val lockShowing: Boolean get() = (vault.enabled && !AppLock.unlocked) || (legacyLock && !legacyUnlocked)

    /** The old lock was unlocked: now choose an app PIN. */
    fun onLegacyUnlocked() {
        legacyUnlocked = true
        AppLock.markUnlocked()
        AppLock.request = com.vknxworks.lumenctrl.ui.lock.PinRequest.UPGRADE
        processPending()
    }

    /** "Cancel" on choosing the new PIN: the old lock is turned off. */
    override fun cancelUpgrade() {
        settings.updateAppLock(false)
        legacyLock = false
        message = tr(R.string.lock_turned_off_no_pin)
    }

    override suspend fun enableLock(pin: String) {
        // Unlocked first, so the lock screen doesn't flash up while the PIN is being saved.
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
        settings.updateAppLock(false)
        legacyLock = false
    }

    override fun disableLock() { vault.disable() }

    /**
     * "Forgot PIN?": the PIN can't be recovered, and skipping it would make the lock pointless, so everything the
     * lock protects is deleted (the library, presets and LUTs), the settings are reset and the lock is turned off.
     * Exports already saved to Pictures/Lumen and Movies/Lumen are not touched.
     */
    override suspend fun forgetEverything() {
        if (openPhoto != null) { openPhoto = null; preview = null; basePreview = null; baseDenoised = null; denoised = null; screen = Screen.LIBRARY }
        pendingShare = emptyList(); pendingEdit = null
        val app = getApplication<Application>()
        withContext(Dispatchers.IO) {
            File(app.filesDir, "library").listFiles()?.forEach { it.deleteRecursively() }
            File(app.filesDir, "presets.json").delete()
            File(app.filesDir, "luts").listFiles()?.forEach { it.deleteRecursively() }
            File(app.filesDir, "import-staging").deleteRecursively()
            app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        catalog.forgetCachedMeta()
        settings.resetAll()
        vault.disable()
        legacyLock = false
        AppLock.markUnlocked()
        selection = emptySet()
        refresh()
        loadLooks()
    }

    override fun onUnlocked() {
        AppLock.markUnlocked()
        processPending()
    }

    /** Images shared in while the app lock was showing; they are imported once it's unlocked. */
    private var pendingShare: List<Uri> = emptyList()

    /** Imports shared images, or keeps them until the app lock is unlocked. */
    fun importWhenUnlocked(uris: List<Uri>) {
        if (lockShowing) pendingShare = pendingShare + uris else this.import(uris)
    }

    /** A photo or video another app asked LumenCtrl to edit while the app lock was showing. */
    private var pendingEdit: Uri? = null

    /** "Edit in LumenCtrl": imports the item and opens it in the editor, after unlocking when the app lock shows. */
    fun editWhenUnlocked(uri: Uri) {
        if (lockShowing) pendingEdit = uri else importAndOpen(uri)
    }

    private fun processPending() {
        val waiting = pendingShare
        pendingShare = emptyList()
        this.import(waiting)
        pendingEdit?.let { pendingEdit = null; importAndOpen(it) }
    }

    /** Copies one item into the library (the other app's file is never changed) and opens it in the editor. */
    private fun importAndOpen(uri: Uri) {
        if (openPhoto != null) close()
        viewModelScope.launch {
            busy = tr(R.string.opening_for_editing)
            val id = catalog.import(listOf(uri)).firstOrNull()
            val photo = id?.let { withContext(Dispatchers.IO) { catalog.photo(it) } }
            busy = null
            refresh()
            if (photo == null) { message = tr(R.string.cant_open_this_file_for_editing); return@launch }
            selection = emptySet()
            open(photo)
        }
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
                    val src = Removals.apply(ImageLoader.decode(photo.original, maxDim), photo.original, p.removals, recycleInput = true)
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
                    // Denoise the picture with removed objects already filled in.
                    val src = Removals.apply(ImageLoader.decode(photo.original, Denoiser.MAX_EDGE), photo.original, params.removals, recycleInput = true)
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
            }.onSuccess { decoded ->
                if (openPhoto?.id == photo.id) baseDenoised = decoded
                val bmp = withContext(Dispatchers.Default) { Removals.apply(decoded, photo.original, params.removals, recycleInput = false) }
                android.util.Log.d("LumenDenoise", "${model.name} done in ${System.currentTimeMillis() - t0} ms")
                if (openPhoto?.id == photo.id) {
                    denoised = bmp
                    val amt = params[Param.AI_DENOISE]
                    edit(tr(R.string.ai_denoise_x, model.label)) { it.with(Param.AI_DENOISE, if (amt > 0f) amt else 0.8f) }
                }
                message = tr(R.string.ai_denoise_done_x_s_on, (System.currentTimeMillis() - t0) / 1000)
            }.onFailure {
                if (it !is Denoiser.Cancelled) message = tr(R.string.ai_denoise_failed_x, it.message)
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
        baseDenoised = null
        edit(tr(R.string.remove_ai_denoise)) { it.with(Param.AI_DENOISE, 0f) }
        viewModelScope.launch(Dispatchers.IO) { Denoiser.fileFor(photo.original).delete() }
    }

    /** Merges 2-7 bracketed exposures into a new Ultra HDR photo in the library. */
    fun mergeHdr(ids: List<String>) {
        if (ids.size < 2) return
        viewModelScope.launch {
            busy = tr(R.string.merging_x_exposures_to_hdr, ids.size)
            runCatching {
                val files = ids.map { catalog.photo(it) }.filter { !it.isVideo }.map { it.original }
                val r = withContext(Dispatchers.Default) { HdrMerge.merge(files) }
                android.util.Log.d("LumenHDR", "merge shifts=${r.shifts} exposures=${r.exposures} gainmap=${r.gainmap != null}")
                catalog.importBitmap(r.sdr, "HDR merge ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())}.jpg")
                r.gainmap != null
            }.onSuccess { hdr ->
                selection = emptySet()
                message = if (hdr) tr(R.string.hdr_photo_added_to_your_library) else tr(R.string.merged_photo_added_hdr_needs_android)
            }.onFailure { message = tr(R.string.hdr_merge_failed_x, it.message) }
            busy = null
            refresh()
        }
    }

    // ---- Your presets and LUTs ----
    private val presetStore = com.vknxworks.lumenctrl.data.UserPresets(app.filesDir)
    var userPresets by mutableStateOf<List<com.vknxworks.lumenctrl.model.UserPreset>>(emptyList()); private set
    var luts by mutableStateOf<List<com.vknxworks.lumenctrl.data.LutInfo>>(emptyList()); private set

    private fun loadLooks() {
        viewModelScope.launch {
            userPresets = withContext(Dispatchers.IO) { presetStore.load() }
            luts = withContext(Dispatchers.IO) { com.vknxworks.lumenctrl.data.Luts.list() }
        }
    }

    /** Saves the open photo's global look (sliders, curves, LUT; not crop, masks or spots) as a preset. */
    fun saveUserPreset(name: String) {
        val p = params
        val preset = com.vknxworks.lumenctrl.model.UserPreset.from(UUID.randomUUID().toString(), name.trim().ifBlank { tr(R.string.my_preset) }.take(40), p)
        userPresets = userPresets + preset
        val list = userPresets
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { presetStore.save(list) } }
                .onSuccess { message = tr(R.string.saved_preset_x, preset.name) }
                .onFailure { message = tr(R.string.couldnt_save_the_preset_x, it.message) }
        }
        // The open photo now wears exactly this preset.
        edit(tr(R.string.preset_x, preset.name)) { it.copy(preset = preset.key, presetAmount = 1f) }
    }

    fun renameUserPreset(id: String, name: String) {
        userPresets = userPresets.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }.take(40)) else it }
        val list = userPresets
        viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { presetStore.save(list) } } }
    }

    /** Deletes a preset. Photos that used it keep their look; only the preset's name disappears from them. */
    fun deleteUserPreset(id: String) {
        userPresets = userPresets.filter { it.id != id }
        val list = userPresets
        viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { presetStore.save(list) } } }
    }

    fun userPresetFor(key: String?): com.vknxworks.lumenctrl.model.UserPreset? =
        if (key == null || !key.startsWith(com.vknxworks.lumenctrl.model.UserPreset.KEY_PREFIX)) null
        else userPresets.firstOrNull { it.key == key }

    /** Reads a `.cube` file, stores it and puts it on the open photo. */
    fun importLut(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = tr(R.string.importing_lut)
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = runCatching {
                        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull()?.substringBeforeLast('.')
                    val bytes = app.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > MAX_LUT_BYTES) throw com.vknxworks.lumenctrl.model.CubeLut.Invalid(tr(R.string.the_file_is_too_large))
                        }
                        out.toByteArray()
                    }
                    val lut = com.vknxworks.lumenctrl.model.CubeLut.parse(String(bytes, Charsets.UTF_8))
                    com.vknxworks.lumenctrl.data.Luts.add(lut, lut.title ?: name ?: "LUT")
                }
            }.onSuccess { info ->
                luts = luts + info
                if (openPhoto != null) setLut(info.id)
                message = tr(R.string.imported_lut_x, info.name)
            }.onFailure {
                message = if (it is com.vknxworks.lumenctrl.model.CubeLut.Invalid) tr(R.string.not_a_usable_3d_lut_x, it.message)
                else tr(R.string.couldnt_import_the_lut_x, it.message)
            }
            busy = null
        }
    }

    /** Puts a LUT on the open photo (null takes it off). */
    fun setLut(id: String?) {
        val label = id?.let { i -> luts.firstOrNull { it.id == i }?.name } ?: tr(R.string.no_lut)
        edit(tr(R.string.lut_x, label)) { it.copy(lut = id).with(Param.LUT_AMOUNT, 1f) }
    }

    fun renameLut(id: String, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.vknxworks.lumenctrl.data.Luts.rename(id, name) }
            luts = withContext(Dispatchers.IO) { com.vknxworks.lumenctrl.data.Luts.list() }
        }
    }

    /** Deletes an imported LUT. Edits that used it render without it from now on. */
    fun deleteLut(id: String) {
        if (params.lut == id) edit(tr(R.string.lut_x, tr(R.string.no_lut))) { it.copy(lut = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { com.vknxworks.lumenctrl.data.Luts.delete(id) }
            luts = withContext(Dispatchers.IO) { com.vknxworks.lumenctrl.data.Luts.list() }
            thumbVersion++
        }
    }

    // ---- Settings file ----

    /** A settings file that was read; the UI lists [Settings.changesFrom] and asks before applying it. */
    var pendingSettings by mutableStateOf<Settings.Incoming?>(null); private set

    fun exportSettings(uri: Uri) {
        val app = getApplication<Application>()
        val json = settings.toFileJson()
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                }
            }.onSuccess { message = tr(R.string.settings_exported) }
                .onFailure { message = tr(R.string.settings_export_failed_x, it.message) }
        }
    }

    fun inspectSettings(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val text = app.contentResolver.openInputStream(uri)!!.use { input ->
                        val bytes = input.readNBytes(256 * 1024)
                        String(bytes, Charsets.UTF_8)
                    }
                    Settings.parseFile(text, com.vknxworks.lumenctrl.ui.ctrl.ACCENTS.size)
                }
            }.onSuccess { incoming ->
                if (incoming == Settings.Incoming(settings.hideInScreenshots, settings.hideInRecents, settings.accent, settings.export)) {
                    message = tr(R.string.settings_already_the_same)
                } else pendingSettings = incoming
            }.onFailure { message = tr(R.string.not_a_lumenctrl_settings_file) }
        }
    }

    fun applyPendingSettings() {
        pendingSettings?.let { settings.apply(it); message = tr(R.string.settings_imported) }
        pendingSettings = null
    }

    fun cancelPendingSettings() { pendingSettings = null }

    // ---- Library export and import ----
    private val archive = com.vknxworks.lumenctrl.data.LibraryArchive(app)

    /** A library file that was read and waits for "Import" to be confirmed. */
    var pendingImport by mutableStateOf<Pair<Uri, com.vknxworks.lumenctrl.data.LibraryArchive.Summary>?>(null); private set

    fun exportLibrary(uri: Uri) {
        viewModelScope.launch {
            busy = tr(R.string.exporting_library)
            runCatching {
                withContext(Dispatchers.IO) {
                    archive.export(uri) { done, total -> viewModelScope.launch { busy = tr(R.string.exporting_library_x_of_x, done, total) } }
                }
            }.onSuccess { message = tr(R.string.library_exported) }
                .onFailure { message = tr(R.string.library_export_failed_x, it.message) }
            busy = null
        }
    }

    /** Reads a library file and asks for confirmation (see [pendingImport]); nothing is changed yet. */
    fun inspectLibrary(uri: Uri) {
        viewModelScope.launch {
            busy = tr(R.string.reading_library_file)
            runCatching { withContext(Dispatchers.IO) { archive.inspect(uri) } }
                .onSuccess { pendingImport = uri to it }
                .onFailure {
                    message = if (it is com.vknxworks.lumenctrl.data.LibraryArchive.NotALibrary) tr(R.string.not_a_lumenctrl_library_file)
                    else tr(R.string.couldnt_read_the_file_x, it.message)
                }
            busy = null
        }
    }

    fun cancelImport() { pendingImport = null }

    fun importLibrary() {
        val (uri, _) = pendingImport ?: return
        pendingImport = null
        viewModelScope.launch {
            busy = tr(R.string.importing_library)
            runCatching {
                withContext(Dispatchers.IO) {
                    archive.import(uri) { n -> viewModelScope.launch { busy = tr(R.string.importing_library_x_files, n) } }
                }
            }.onSuccess { r ->
                message = tr(R.string.library_imported_x_x_x_x, r.photos, r.albums, r.presets, r.luts)
            }.onFailure {
                message = if (it is com.vknxworks.lumenctrl.data.LibraryArchive.NotALibrary) tr(R.string.not_a_lumenctrl_library_file)
                else tr(R.string.library_import_failed_x, it.message)
            }
            busy = null
            refresh()
            loadLooks()
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
            camStatus = tr(R.string.waiting_for_usb_permission)
            if (!cam.requestPermission(d)) { camStatus = tr(R.string.usb_access_was_declined); return@launch }
            camStatus = tr(R.string.connecting_to_x, cam.label(d))
            if (!cam.open(d)) { camStatus = tr(R.string.couldnt_open_the_camera_set_it); return@launch }
            camConnected = cam.label(d)
            camStatus = tr(R.string.reading_the_card)
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
                busy = tr(R.string.importing_x_of_x, okHandles.size + skipped + 1, chosen.size)
                // The name comes from the USB device: never let it pick the path ("../" or "/" in it). Only a
                // plain extension is kept.
                val ext = p.name.substringAfterLast('.', "").lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(8)
                val tmp = File(tmpDir, "${System.nanoTime()}${if (ext.isEmpty()) "" else ".$ext"}")
                val ok = cam.download(p.handle, tmp) && catalog.importFile(tmp, p.name, p.takenAt) != null
                if (ok) okHandles += p.handle else { skipped++; tmp.delete() }
            }
            val done = okHandles.size
            busy = null
            refresh()
            camPhotos = camPhotos.map { if (it.handle in okHandles) it.copy(alreadyImported = true) else it }
            camSelected = emptySet()
            message = if (skipped == 0) tr(R.string.imported_x_from_camera, done) else tr(R.string.imported_x_x_not_readable_on, done, skipped)
        }
    }

    fun leaveCameraImport() { cam.close(); camConnected = null; camPhotos = emptyList(); camThumbs = emptyMap(); screen = Screen.LIBRARY }

    private data class Step(val label: String, val before: EditParams)
    private var committed = EditParams()
    private var pendingLabel = tr(R.string.edit)
    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()

    init {
        // Locking wipes the vault key from memory.
        AppLock.onLocked = { vault.close() }
        com.vknxworks.lumenctrl.render.OverlayPainter.init(app)
        refresh(); loadLooks()
    }

    fun refresh() {
        viewModelScope.launch {
            allPhotos = catalog.photos()
            albums = catalog.albums()
            val present = allPhotos.mapTo(HashSet()) { it.id }
            selection = selection.filter { it in present }.toSet()
        }
    }

    fun consumeMessage() { message = null }
    fun toast(text: String) { message = text }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = tr(R.string.importing_x_photo_s, uris.size)
            val ids = catalog.import(uris)
            albumFilter?.let { a -> catalog.updateMeta(ids) { it.copy(albums = it.albums + a) } }
            busy = null
            refresh()
            message = if (ids.size == uris.size) tr(R.string.imported_x_photo_s, ids.size) else tr(R.string.imported_x_of_x, ids.size, uris.size)
        }
    }

    /** Imports a camera capture from app cache, then deletes the temporary file. */
    fun importCapture(uri: Uri, file: File) {
        viewModelScope.launch {
            catalog.import(listOf(uri))
            withContext(Dispatchers.IO) { file.delete() }
            refresh()
            message = tr(R.string.photo_added)
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
            message = if (add.isEmpty()) tr(R.string.album_created) else tr(R.string.added_x_to_x, add.size, a.name)
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
            message = tr(R.string.removed_x_photo_s, ids.size)
        }
    }

    fun pasteToSelected() {
        val c = clipboard ?: return
        val ids = selection
        viewModelScope.launch {
            busy = tr(R.string.pasting_settings)
            allPhotos.filter { it.id in ids }.forEach { p ->
                val merged = catalog.loadParams(p).pasteAdjustmentsFrom(c)
                catalog.saveParams(p, merged)
                renderThumb(p, merged)
            }
            busy = null
            thumbVersion++
            refresh()
            message = tr(R.string.pasted_settings_to_x_photo_s, ids.size)
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
            busy = if (ids.size == 1) tr(R.string.exporting) else tr(R.string.exporting_x_photos, ids.size)
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
                if (share) shareRequest = uris else message = tr(R.string.saved_x_to_pictures_lumen, ids.size)
            }.onFailure { message = tr(R.string.export_failed_x, it.message) }
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
                .let { if (p.isVideo) it else Removals.apply(it, p.original, params.removals, recycleInput = true) }
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
        if (params.masks.size >= MAX_MASKS) { message = tr(R.string.up_to_x_masks_per_photo, MAX_MASKS); return }
        if (params.masks.count { it.type == MaskType.AI } >= AiAtlas.MAX_AI) { message = tr(R.string.up_to_x_ai_masks_per, AiAtlas.MAX_AI); return }
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = tr(R.string.finding_x_on_device, inSentence(kind.label))
            val t0 = System.currentTimeMillis()
            runCatching {
                val mask = withContext(Dispatchers.Default) { Segmenter.segment(app, bmp, kind) }
                val id = UUID.randomUUID().toString().take(8)
                catalog.saveAiMask(photo, id, mask)
                aiMasks = aiMasks + (id to mask)
                id
            }.onSuccess { id ->
                android.util.Log.d("LumenAI", "${kind.name} mask in ${System.currentTimeMillis() - t0} ms")
                edit(tr(R.string.select_x, inSentence(kind.label))) {
                    it.copy(masks = it.masks + LocalMask(id, MaskType.AI, 0f, 0f, 0f, 0f, feather = 0.3f, ai = kind))
                }
                onAdded(params.masks.lastIndex)
            }.onFailure { message = if (it is Segmenter.NothingFound) it.message else tr(R.string.ai_mask_failed_x, it.message) }
            busy = null
        }
    }

    fun saveCollage(bitmap: Bitmap) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = tr(R.string.saving_collage)
            runCatching {
                withContext(Dispatchers.IO) { Exporter.saveBitmapToGallery(app, bitmap, "Lumen_collage_${stamp()}") }
                catalog.importBitmap(bitmap, tr(R.string.collage_x, stamp()))
            }.onSuccess { message = tr(R.string.collage_saved_to_pictures_lumen_and) }
                .onFailure { message = tr(R.string.couldnt_save_collage_x, it.message) }
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
            baseDenoised = withContext(Dispatchers.IO) {
                Denoiser.fileFor(photo.original).takeIf { it.exists() }
                    ?.let { runCatching { ImageLoader.decode(it, 3072) }.getOrNull() }
            }
            denoised = baseDenoised?.let { withContext(Dispatchers.Default) { Removals.apply(it, photo.original, loaded.removals, recycleInput = false) } }
            hdrPreview = null
            val base = runCatching {
                // 3072 px keeps detail when zooming in the loupe.
                withContext(Dispatchers.IO) { ImageLoader.decode(photo.original, 3072) }
            }.getOrElse {
                message = tr(R.string.cant_open_this_photo_x, it.message)
                openPhoto = null
                screen = Screen.LIBRARY
                null
            }
            basePreview = base
            appliedRemovals = loaded.removals
            preview = base?.let { withContext(Dispatchers.Default) { Removals.apply(it, photo.original, loaded.removals, recycleInput = false) } }
        }
    }

    // ---- Object removal ----

    /** The open photo's picture without removals, and the removals [preview] and [denoised] were made with. */
    private var basePreview: Bitmap? = null
    private var baseDenoised: Bitmap? = null
    private var appliedRemovals: List<com.vknxworks.lumenctrl.model.Removal> = emptyList()

    /** After undo, redo or a restored version: rebuilds the editor's picture when the set of removals changed. */
    private fun syncRemovals() {
        val photo = openPhoto ?: return
        if (photo.isVideo) return
        val want = params.removals
        if (want == appliedRemovals) return
        val base = basePreview ?: return
        val dnBase = baseDenoised
        appliedRemovals = want
        viewModelScope.launch {
            val (pic, dn) = withContext(Dispatchers.Default) {
                Removals.apply(base, photo.original, want, recycleInput = false) to
                    dnBase?.let { Removals.apply(it, photo.original, want, recycleInput = false) }
            }
            // Only if nothing changed meanwhile.
            if (openPhoto?.id == photo.id && appliedRemovals == want) {
                preview = pic
                if (dnBase != null && baseDenoised === dnBase) denoised = dn
            }
        }
    }

    var removing by mutableStateOf(false); private set
    @Volatile private var removeCancel = false

    /**
     * Removes what the [strokes] (x, y pairs in source uv) brushed over, filling it from the rest of the photo on
     * the phone. [radius] is the brush radius in picture heights.
     */
    fun removeObject(strokes: List<List<Float>>, radius: Float, onDone: () -> Unit) {
        val photo = openPhoto ?: return
        val pic = preview ?: return
        if (removing) return
        if (params.removals.size >= com.vknxworks.lumenctrl.model.MAX_REMOVALS) {
            message = tr(R.string.up_to_x_removals_per_photo, com.vknxworks.lumenctrl.model.MAX_REMOVALS); return
        }
        removing = true
        removeCancel = false
        viewModelScope.launch {
            busy = tr(R.string.removing_object)
            val t0 = System.currentTimeMillis()
            runCatching {
                withContext(Dispatchers.Default) {
                    val (removal, patch) = Removals.compute(pic, strokes, radius) { removeCancel || openPhoto?.id != photo.id }
                    Removals.save(photo.original, removal.id, patch)
                    patch.recycle()
                    removal
                }
            }.onSuccess { removal ->
                android.util.Log.d("LumenRemove", "object removed in ${System.currentTimeMillis() - t0} ms")
                if (openPhoto?.id == photo.id) {
                    edit(tr(R.string.remove_object)) { it.copy(removals = it.removals + removal) }
                    onDone()
                }
            }.onFailure {
                message = when (it) {
                    is Removals.NothingToRemove -> tr(R.string.brush_over_what_to_remove)
                    is com.vknxworks.lumenctrl.edit.Inpaint.Cancelled -> null
                    else -> tr(R.string.couldnt_remove_it_x, it.message)
                }
            }
            busy = null
            removing = false
        }
    }

    fun close() {
        val photo = openPhoto ?: return
        val finalParams = params
        val keepAi = (listOf(finalParams) + versions.map { it.params }).flatMap { p -> p.masks.map { it.id } }.toSet()
        val keepRemovals = (listOf(finalParams) + versions.map { it.params }).flatMap { p -> p.removals.map { it.id } }.toSet()
        val keepMusic = (listOf(finalParams) + versions.map { it.params }).mapNotNull { it.video.musicId }.toSet()
        openPhoto = null
        preview = null
        basePreview = null
        baseDenoised = null
        appliedRemovals = emptyList()
        removeCancel = true
        aiMasks = emptyMap()
        sourceGain = null
        denoised = null
        denoiseCancel = true
        hdrPreview = null
        screen = Screen.LIBRARY
        viewModelScope.launch {
            catalog.saveParams(photo, finalParams)
            catalog.pruneAiMasks(photo, keepAi)
            catalog.pruneRemovals(photo, keepRemovals)
            catalog.pruneMusic(photo, keepMusic)
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

    /** Copies an audio file into the library as the open video's music. */
    fun setMusic(uri: Uri) {
        val photo = openPhoto ?: return
        if (!photo.isVideo) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = tr(R.string.adding_music)
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = runCatching {
                        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull()?.take(80)
                    val id = UUID.randomUUID().toString().replace("-", "").take(8)
                    val f = com.vknxworks.lumenctrl.model.VideoEdit.musicFile(photo.original, id)
                    val tmp = File(f.path + ".part")
                    try {
                        app.contentResolver.openInputStream(uri)!!.use { input ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_MUSIC_BYTES) error(tr(R.string.the_file_is_too_large))
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        // It must be something Android can play.
                        val ex = android.media.MediaExtractor()
                        val ok = try {
                            ex.setDataSource(tmp.path)
                            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                        } finally { ex.release() }
                        if (!ok) error(tr(R.string.no_audio_in_this_file))
                        if (!tmp.renameTo(f)) error(tr(R.string.couldnt_save_the_music))
                    } finally { tmp.delete() }
                    id to (name ?: tr(R.string.music))
                }
            }.onSuccess { (id, name) ->
                if (openPhoto?.id == photo.id) {
                    edit(tr(R.string.add_music)) { it.copy(video = it.video.copy(musicId = id, musicName = name)) }
                }
            }.onFailure { message = tr(R.string.couldnt_add_the_music_x, it.message) }
            busy = null
        }
    }

    fun removeMusic() = edit(tr(R.string.remove_music)) { it.copy(video = it.video.copy(musicId = null, musicName = null)) }

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
                    val label = if (reel) tr(R.string.rendering_reel) else tr(R.string.exporting_video_x_of_x, j + 1, jobs.size)
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
                else message = (if (reel) tr(R.string.reel_saved_to_movies_lumen_and) else tr(R.string.saved_x_video_s_to_movies, jobs.size)) +
                    if (audioDropped) tr(R.string.audio_left_out_speed_change_or) else ""
            }.onFailure { message = tr(R.string.video_export_failed_x, it.message) }
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
                        .let { if (photo.isVideo) it else Removals.apply(it, photo.original, p.removals, recycleInput = true) }
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
        syncRemovals()
    }

    /** A discrete edit with its own history step. */
    fun edit(label: String, transform: (EditParams) -> EditParams) {
        params = transform(params)
        pendingLabel = label
        commit()
    }

    fun reset(p: Param) = edit(tr(R.string.reset_x, p.label)) { it.with(p, p.default) }

    fun undo() {
        val step = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(Step(step.label, params))
        params = step.before; committed = step.before
        updateHistory(); persist(); syncRemovals()
    }

    fun redo() {
        val step = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(Step(step.label, params))
        params = step.before; committed = step.before
        updateHistory(); persist(); syncRemovals()
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

    fun copySettings() { clipboard = params; message = tr(R.string.settings_copied) }
    fun pasteSettings() { clipboard?.let { c -> edit(tr(R.string.paste_settings)) { it.pasteAdjustmentsFrom(c) } } }

    // ---- Auto tools (on-device statistics) ----

    fun autoTone() {
        val bmp = preview ?: return
        viewModelScope.launch {
            val auto = withContext(Dispatchers.Default) { AutoTools.autoTone(bmp) }
            edit(tr(R.string.auto)) { p -> auto.entries.fold(p) { acc, (k, v) -> acc.with(k, v) } }
        }
    }

    fun pickWhiteBalance(u: Float, v: Float) {
        val bmp = preview ?: return
        viewModelScope.launch {
            val (t, i) = withContext(Dispatchers.Default) { AutoTools.neutralWhiteBalance(bmp, u, v) }
            edit(tr(R.string.white_balance)) { it.with(Param.TEMPERATURE, t).with(Param.TINT, i) }
        }
    }

    fun autoStraighten() {
        val bmp = preview ?: return
        viewModelScope.launch {
            val angle = withContext(Dispatchers.Default) { AutoTools.autoStraighten(bmp, params) }
            if (angle == null) message = tr(R.string.no_clear_horizon_or_verticals_found)
            else edit(tr(R.string.auto_straighten)) { it.with(Param.STRAIGHTEN, angle) }
        }
    }

    // ---- Heal spots ----

    fun addSpot(u: Float, v: Float, radius: Float) {
        val bmp = preview ?: return
        if (params.spots.size >= MAX_SPOTS) { message = tr(R.string.up_to_x_spots_per_photo, MAX_SPOTS); return }
        viewModelScope.launch {
            val (sx, sy) = withContext(Dispatchers.Default) { AutoTools.findHealSource(bmp, u, v, radius) }
            edit(tr(R.string.heal_spot)) { it.copy(spots = it.spots + Spot(u, v, sx, sy, radius)) }
        }
    }

    // ---- Masks ----

    fun addMask(type: MaskType): Int {
        if (params.masks.size >= MAX_MASKS) { message = tr(R.string.up_to_x_masks_per_photo, MAX_MASKS); return params.masks.size - 1 }
        val id = UUID.randomUUID().toString().take(8)
        val m = when (type) {
            MaskType.LINEAR -> LocalMask(id, type, 0.5f, 0.15f, 0.5f, 0.55f)
            MaskType.RADIAL, MaskType.AI -> LocalMask(id, MaskType.RADIAL, 0.5f, 0.5f, 0.25f, 0.25f, feather = 0.6f)
            MaskType.LUMA -> LocalMask(id, type, 0.6f, 0f, 1f, 0f, feather = 0.4f)
            MaskType.COLOR -> LocalMask(id, type, 0.6f, 0f, 0.08f, 0f, feather = 0.5f)
        }
        edit(tr(R.string.add_x_mask, inSentence(type.label))) { it.copy(masks = it.masks + m) }
        return params.masks.size - 1
    }

    // ---- Versions ----

    fun saveVersion() {
        val photo = openPhoto ?: return
        val v = Version(tr(R.string.version_x, versions.size + 1), System.currentTimeMillis(), params)
        versions = versions + v
        viewModelScope.launch { catalog.saveVersions(photo, versions) }
        message = tr(R.string.saved_x, v.name)
    }

    fun restoreVersion(v: Version) = edit(tr(R.string.restore_x, v.name)) { v.params }

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

    private companion object {
        /** A 96³ .cube file is about 25 MB of text; anything bigger isn't a LUT LumenCtrl can use. */
        const val MAX_LUT_BYTES = 32L * 1024 * 1024
        /** Music copied into the library: generous for long songs in lossless formats. */
        const val MAX_MUSIC_BYTES = 300L * 1024 * 1024
    }
}
