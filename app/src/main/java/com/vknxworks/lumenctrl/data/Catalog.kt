package com.vknxworks.lumenctrl.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.vknxworks.lumenctrl.model.EditParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/** Facts read at import plus library state (rating, flag, albums). */
data class PhotoMeta(
    val name: String = "",
    val importedAt: Long = 0,
    val takenAt: Long? = null,
    val camera: String? = null,
    val lens: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
    val iso: String? = null,
    val shutter: String? = null,
    val aperture: String? = null,
    val focal: String? = null,
    val rating: Int = 0,
    /** 1 = pick, -1 = reject, 0 = none. */
    val flag: Int = 0,
    val albums: Set<String> = emptySet(),
    /** Videos only. */
    val durationMs: Long = 0,
    val hasAudio: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("importedAt", importedAt); put("takenAt", takenAt ?: JSONObject.NULL)
        put("camera", camera ?: JSONObject.NULL); put("lens", lens ?: JSONObject.NULL)
        put("width", width); put("height", height); put("bytes", bytes)
        put("iso", iso ?: JSONObject.NULL); put("shutter", shutter ?: JSONObject.NULL)
        put("aperture", aperture ?: JSONObject.NULL); put("focal", focal ?: JSONObject.NULL)
        put("rating", rating); put("flag", flag); put("albums", JSONArray(albums.toList()))
        put("durationMs", durationMs); put("hasAudio", hasAudio)
    }

    /** Text the library search matches against. */
    val searchText: String get() = listOfNotNull(name, camera, lens, takenAt?.let { DATE.format(it) }).joinToString(" ").lowercase()

    companion object {
        val DATE = SimpleDateFormat("yyyy-MM-dd MMMM yyyy", Locale.getDefault())
        private fun JSONObject.str(k: String) = if (isNull(k)) null else optString(k).ifBlank { null }
        fun fromJson(o: JSONObject) = PhotoMeta(
            o.optString("name"), o.optLong("importedAt"), if (o.isNull("takenAt")) null else o.optLong("takenAt"),
            o.str("camera"), o.str("lens"), o.optInt("width"), o.optInt("height"), o.optLong("bytes"),
            o.str("iso"), o.str("shutter"), o.str("aperture"), o.str("focal"),
            o.optInt("rating").coerceIn(0, 5), o.optInt("flag").coerceIn(-1, 1),
            o.optJSONArray("albums")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
            o.optLong("durationMs", 0), o.optBoolean("hasAudio", false),
        )
    }
}

data class Album(val id: String, val name: String)

data class Version(val name: String, val createdAt: Long, val params: EditParams)

/**
 * The photo catalog, all in app-private storage (no other app can read it,
 * and backups are disabled in the manifest). Per photo:
 *   <id>.img           untouched original bytes
 *   <id>.json          edit sidecar (absent = unedited)
 *   <id>.meta.json     PhotoMeta
 *   <id>.versions.json saved versions
 *   <id>_thumb.jpg     rendered thumbnail of the edit
 * Plus albums.json for the album list.
 */
class Catalog(private val context: Context) {
    private val dir = File(context.filesDir, "library").apply { mkdirs() }
    private val albumsFile = File(dir, "albums.json")

    data class Photo(
        val id: String,
        val original: File,
        val sidecar: File,
        val thumb: File,
        val edited: Boolean,
        val meta: PhotoMeta,
    ) {
        /** Taken date when known, else import date (for sorting). */
        val sortTime: Long get() = meta.takenAt ?: meta.importedAt
        val isVideo: Boolean get() = original.name.endsWith(".vid")
    }

    private fun metaFile(id: String) = File(dir, "$id.meta.json")
    private fun versionsFile(id: String) = File(dir, "$id.versions.json")

    /** Call off the main thread: reads the disk. */
    fun photo(id: String): Photo {
        val img = File(dir, "$id.vid").takeIf { it.exists() } ?: File(dir, "$id.img")
        val sidecar = File(dir, "$id.json")
        val meta = metaFile(id).takeIf { it.exists() }
            ?.let { runCatching { PhotoMeta.fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?: PhotoMeta(name = "Photo", importedAt = img.lastModified(), bytes = img.length())
        return Photo(id, img, sidecar, File(dir, "${id}_thumb.jpg"), sidecar.exists(), meta)
    }

    suspend fun photos(): List<Photo> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.name.endsWith(".img") || f.name.endsWith(".vid") }.orEmpty().map { photo(it.nameWithoutExtension) }
            .onEach { p -> if (!p.edited && p.thumb.exists()) p.thumb.delete() } // stale: edit was reset while the app was killed
    }

    /** Returns the ids imported successfully. */
    suspend fun import(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            runCatching {
                val id = UUID.randomUUID().toString()
                val tmp = File(dir, "$id.part")
                context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val isImage = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    .also { BitmapFactory.decodeFile(tmp.path, it) }.outWidth > 0
                val video = if (isImage) null else readVideoMeta(tmp, displayName(uri))
                if (!isImage && video == null) { tmp.delete(); error("Not an image or video") }
                val target = File(dir, if (isImage) "$id.img" else "$id.vid")
                check(tmp.renameTo(target))
                writeMeta(id, video ?: readMeta(target, displayName(uri)))
                id
            }.onFailure { android.util.Log.w("LumenImport", "import failed for $uri", it) }.getOrNull()
        }
    }

    /**
     * Moves a file already on this device (e.g. downloaded from a USB camera) into the
     * library. Returns null, and deletes the file, if Android can't decode it.
     */
    suspend fun importFile(file: File, name: String, takenAt: Long? = null): String? = withContext(Dispatchers.IO) {
        val ok = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(file.path, it) }.outWidth > 0
        if (!ok) { file.delete(); return@withContext null }
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.img")
        if (!file.renameTo(target)) { file.copyTo(target); file.delete() }
        val meta = readMeta(target, name)
        writeMeta(id, if (meta.takenAt == null && takenAt != null && takenAt > 0) meta.copy(takenAt = takenAt) else meta)
        id
    }

    /** Adds a rendered bitmap (e.g. a collage) to the library as a new original. */
    suspend fun importBitmap(bitmap: Bitmap, name: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.img")
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        writeMeta(id, readMeta(target, name))
        id
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Photo"

    private fun readMeta(file: File, name: String): PhotoMeta {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val exif = runCatching { ExifInterface(file.path) }.getOrNull()
        val rotated = exif?.rotationDegrees?.let { it % 180 != 0 } == true
        fun tag(t: String) = exif?.getAttribute(t)?.trim()?.ifBlank { null }
        val make = tag(ExifInterface.TAG_MAKE)
        val model = tag(ExifInterface.TAG_MODEL)
        val camera = listOfNotNull(make, model?.removePrefix(make ?: "")?.trim()).joinToString(" ").ifBlank { null }
        val taken = tag(ExifInterface.TAG_DATETIME_ORIGINAL) ?: tag(ExifInterface.TAG_DATETIME)
        val takenAt = taken?.let { runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull() }
        val shutter = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)?.takeIf { it > 0 }?.let {
            if (it >= 1) "${"%.1f".format(it)}s" else "1/${(1 / it).toInt()}s"
        }
        val f = exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)?.takeIf { it > 0 }?.let { "f/${"%.1f".format(it)}" }
        val focal = exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)?.takeIf { it > 0 }?.let { "${"%.0f".format(it)} mm" }
        return PhotoMeta(
            name = name, importedAt = System.currentTimeMillis(), takenAt = takenAt,
            camera = camera, lens = tag(ExifInterface.TAG_LENS_MODEL),
            width = if (rotated) bounds.outHeight else bounds.outWidth,
            height = if (rotated) bounds.outWidth else bounds.outHeight,
            bytes = file.length(), iso = tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
            shutter = shutter, aperture = f, focal = focal,
        )
    }

    /** Video facts via MediaMetadataRetriever; null if Android can't read it as a video. */
    private fun readVideoMeta(file: File, name: String): PhotoMeta? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            fun key(k: Int) = r.extractMetadata(k)
            if (key(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return@runCatching null
            val w = key(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = key(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = key(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val date = key(android.media.MediaMetadataRetriever.METADATA_KEY_DATE)?.let {
                runCatching { SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(it)?.time }.getOrNull()
            }
            PhotoMeta(
                name = name, importedAt = System.currentTimeMillis(), takenAt = date,
                width = if (rot % 180 != 0) h else w, height = if (rot % 180 != 0) w else h,
                bytes = file.length(),
                durationMs = key(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                hasAudio = key(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } finally { r.release() }
    }.onFailure { android.util.Log.w("LumenImport", "video metadata failed", it) }.getOrNull()

    private fun writeMeta(id: String, meta: PhotoMeta) = atomicWrite(metaFile(id), meta.toJson().toString())

    suspend fun updateMeta(ids: Collection<String>, transform: (PhotoMeta) -> PhotoMeta) = withContext(Dispatchers.IO) {
        ids.forEach { id -> writeMeta(id, transform(photo(id).meta)) }
    }

    suspend fun loadParams(photo: Photo): EditParams = withContext(Dispatchers.IO) {
        if (!photo.sidecar.exists()) EditParams()
        else runCatching { EditParams.fromJson(photo.sidecar.readText()) }.getOrDefault(EditParams())
    }

    suspend fun saveParams(photo: Photo, params: EditParams) = withContext(Dispatchers.IO) {
        if (params.isDefault()) photo.sidecar.delete() else atomicWrite(photo.sidecar, params.toJson())
    }

    suspend fun versions(photo: Photo): List<Version> = withContext(Dispatchers.IO) {
        val f = versionsFile(photo.id)
        if (!f.exists()) return@withContext emptyList()
        runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Version(o.getString("name"), o.getLong("createdAt"), EditParams.fromJson(o.getJSONObject("params").toString()))
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveVersions(photo: Photo, list: List<Version>) = withContext(Dispatchers.IO) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("createdAt", it.createdAt).put("params", JSONObject(it.params.toJson()))) }
        atomicWrite(versionsFile(photo.id), a.toString())
    }

    // ---- Albums ----

    suspend fun albums(): List<Album> = withContext(Dispatchers.IO) {
        if (!albumsFile.exists()) return@withContext emptyList()
        runCatching {
            val a = JSONArray(albumsFile.readText())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Album(o.getString("id"), o.getString("name")) } }
        }.getOrDefault(emptyList())
    }

    private fun writeAlbums(list: List<Album>) =
        atomicWrite(albumsFile, JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }.toString())

    suspend fun createAlbum(name: String): Album = withContext(Dispatchers.IO) {
        Album(UUID.randomUUID().toString(), name.trim().ifBlank { "Album" }).also { writeAlbums(albums() + it) }
    }

    suspend fun renameAlbum(id: String, name: String) = withContext(Dispatchers.IO) {
        writeAlbums(albums().map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
    }

    suspend fun deleteAlbum(id: String) = withContext(Dispatchers.IO) {
        writeAlbums(albums().filter { it.id != id })
        photos().filter { id in it.meta.albums }.forEach { p -> writeMeta(p.id, p.meta.copy(albums = p.meta.albums - id)) }
    }

    suspend fun delete(photo: Photo) = withContext(Dispatchers.IO) {
        listOf(
            photo.original, photo.sidecar, photo.thumb, metaFile(photo.id), versionsFile(photo.id),
            com.vknxworks.lumenctrl.edit.Denoiser.fileFor(photo.original),
        ).forEach { it.delete() }
        aiFiles(photo.id).forEach { it.delete() }
    }

    // ---- AI mask images (<id>.ai-<maskId>.png, source-aligned grayscale) ----

    private fun aiFiles(photoId: String) = dir.listFiles { f -> f.name.startsWith("$photoId.ai-") && f.name.endsWith(".png") }.orEmpty().toList()

    suspend fun saveAiMask(photo: Photo, maskId: String, bmp: Bitmap) = withContext(Dispatchers.IO) {
        val f = File(dir, "${photo.id}.ai-$maskId.png")
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    suspend fun loadAiMasks(photo: Photo): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        aiFiles(photo.id).mapNotNull { f ->
            val id = f.name.removePrefix("${photo.id}.ai-").removeSuffix(".png")
            BitmapFactory.decodeFile(f.path)?.let { id to it }
        }.toMap()
    }

    /** Deletes AI mask images no longer used by the edit or any saved version. */
    suspend fun pruneAiMasks(photo: Photo, keep: Set<String>) = withContext(Dispatchers.IO) {
        aiFiles(photo.id).forEach { f ->
            val id = f.name.removePrefix("${photo.id}.ai-").removeSuffix(".png")
            if (id !in keep) f.delete()
        }
    }

    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) { dir.listFiles().orEmpty().sumOf { it.length() } }

    private fun atomicWrite(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
