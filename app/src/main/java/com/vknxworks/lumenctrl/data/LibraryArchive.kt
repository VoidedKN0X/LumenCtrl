package com.vknxworks.lumenctrl.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Library export and import: one ZIP file with every photo and video (the untouched originals), their edits, saved
 * versions, ratings, flags, albums, AI masks, denoised copies, object-removal patches, video music, your presets and
 * your LUTs. Import adds what isn't in the library yet and never replaces or deletes anything that is.
 *
 * Archive layout: `lumen-library.json` (what it is), `library/…` (files exactly as LumenCtrl stores them),
 * `presets.json`, `luts/luts.json`, `luts/<id>.lut`.
 */
class LibraryArchive(private val context: Context) {
    private val libDir = File(context.filesDir, "library")

    class Summary(
        val photos: Int,
        /** Photos in the file that the library already has (skipped). */
        val alreadyHere: Int,
        val albums: Int,
        val presets: Int,
        val luts: Int,
        val bytes: Long,
    )

    class NotALibrary : Exception()

    // ------------------------------------------------------------------ export

    /** Writes the whole library to [uri]. [onProgress] gets (files done, files in total). Call off the main thread. */
    fun export(uri: Uri, onProgress: (Int, Int) -> Unit) {
        val files = libDir.listFiles().orEmpty().filter { it.isFile && LIBRARY_FILE.matches(it.name) }.sortedBy { it.name }
        val luts = Luts.list()
        val presets = File(context.filesDir, "presets.json").takeIf { it.exists() }
        val total = files.size + luts.size + 2
        var done = 0
        val out: OutputStream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Can't write there")
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            // Photos and videos are already compressed; fast compression is enough for the rest.
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(
                JSONObject().put("format", FORMAT).put("version", 1).put("created", System.currentTimeMillis())
                    .put("photos", files.count { it.name.endsWith(".img") || it.name.endsWith(".vid") })
                    .toString(2).toByteArray(),
            )
            zip.closeEntry()
            for (f in files) {
                zip.putNextEntry(ZipEntry("library/${f.name}").apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
                onProgress(++done, total)
            }
            if (presets != null) {
                zip.putNextEntry(ZipEntry("presets.json"))
                presets.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            onProgress(++done, total)
            val index = JSONArray()
            for (l in luts) {
                val f = Luts.fileOf(l.id) ?: continue
                index.put(JSONObject().put("id", l.id).put("name", l.name).put("size", l.size))
                zip.putNextEntry(ZipEntry("luts/${l.id}.lut"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                onProgress(++done, total)
            }
            zip.putNextEntry(ZipEntry("luts/luts.json"))
            zip.write(index.toString().toByteArray())
            zip.closeEntry()
            onProgress(total, total)
        }
    }

    // ------------------------------------------------------------------ import

    /** Reads what's in the file without changing anything. Throws [NotALibrary] for anything else. */
    fun inspect(uri: Uri): Summary {
        val existing = existingIds()
        var manifest = false
        val photos = HashSet<String>()
        var albums = 0; var presets = 0; var luts = 0; var bytes = 0L
        read(uri) { name, input ->
            when {
                name == MANIFEST -> {
                    val o = runCatching { JSONObject(String(readLimited(input, 64 * 1024))) }.getOrNull()
                    manifest = o?.optString("format") == FORMAT
                }
                name == "library/albums.json" ->
                    albums = runCatching { JSONArray(String(readLimited(input, JSON_MAX))).length() }.getOrDefault(0)
                name == "presets.json" ->
                    presets = runCatching { UserPresets.parse(String(readLimited(input, JSON_MAX))).size }.getOrDefault(0)
                name == "luts/luts.json" ->
                    luts = runCatching { JSONArray(String(readLimited(input, JSON_MAX))).length() }.getOrDefault(0)
                name.startsWith("library/") -> {
                    val file = name.removePrefix("library/")
                    val m = LIBRARY_FILE.matchEntire(file)
                    if (m != null && (file.endsWith(".img") || file.endsWith(".vid"))) photos += m.groupValues[1]
                    bytes += skipCounting(input)
                }
                else -> bytes += skipCounting(input)
            }
        }
        if (!manifest) throw NotALibrary()
        return Summary(photos.size, photos.count { it in existing }, albums, presets, luts, bytes)
    }

    class Result(val photos: Int, val albums: Int, val presets: Int, val luts: Int)

    /**
     * Adds everything from the file that isn't here yet. Files are first written to a private staging folder and only
     * moved into the library once the whole file was read, so a broken file adds nothing. Call off the main thread.
     */
    fun import(uri: Uri, onProgress: (Int) -> Unit): Result {
        val existing = existingIds()
        val staging = File(context.filesDir, "import-staging").apply { deleteRecursively(); mkdirs() }
        val lutStage = File(staging, "luts").apply { mkdirs() }
        val libStage = File(staging, "library").apply { mkdirs() }
        try {
            var albumsJson: String? = null
            var presetsJson: String? = null
            var lutIndex: String? = null
            var manifest = false
            var count = 0
            val free = context.filesDir.usableSpace
            var written = 0L
            read(uri) { name, input ->
                when {
                    name == MANIFEST -> manifest = runCatching { JSONObject(String(readLimited(input, 64 * 1024))).optString("format") == FORMAT }.getOrDefault(false)
                    name == "library/albums.json" -> albumsJson = String(readLimited(input, JSON_MAX))
                    name == "presets.json" -> presetsJson = String(readLimited(input, JSON_MAX))
                    name == "luts/luts.json" -> lutIndex = String(readLimited(input, JSON_MAX))
                    name.startsWith("luts/") -> {
                        val id = name.removePrefix("luts/").removeSuffix(".lut")
                        if (name.endsWith(".lut") && Luts.isValidId(id)) {
                            written += copyLimited(input, File(lutStage, "$id.lut"), free - written - SPACE_RESERVE)
                        } else skipCounting(input)
                    }
                    name.startsWith("library/") -> {
                        val file = name.removePrefix("library/")
                        val m = LIBRARY_FILE.matchEntire(file)
                        // Only files of photos that aren't in the library yet; names are checked, never trusted as paths.
                        if (m != null && m.groupValues[1] !in existing) {
                            written += copyLimited(input, File(libStage, file), free - written - SPACE_RESERVE)
                            onProgress(++count)
                        } else skipCounting(input)
                    }
                    else -> skipCounting(input)
                }
            }
            if (!manifest) throw NotALibrary()

            // A photo is added only with its original; stray files of a photo without one are left out.
            val staged = libStage.listFiles().orEmpty()
            val ids = staged.mapNotNull { f -> LIBRARY_FILE.matchEntire(f.name)?.groupValues?.get(1)?.takeIf { f.name.endsWith(".img") || f.name.endsWith(".vid") } }.toSet()
            libDir.mkdirs()
            var photos = 0
            for (f in staged) {
                val id = LIBRARY_FILE.matchEntire(f.name)?.groupValues?.get(1) ?: continue
                if (id !in ids) continue
                val target = File(libDir, f.name)
                if (target.exists()) continue // never replace what's here
                if (!f.renameTo(target)) { f.copyTo(target); f.delete() }
                if (f.name.endsWith(".img") || f.name.endsWith(".vid")) photos++
            }

            // Albums: add the ones the library doesn't have, by id.
            var albums = 0
            albumsJson?.let { json ->
                val incoming = runCatching { JSONArray(json) }.getOrNull() ?: return@let
                val file = File(libDir, "albums.json")
                val current = if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrNull() else JSONArray()
                if (current == null) return@let // can't read ours: leave it alone
                val have = (0 until current.length()).mapNotNull { current.optJSONObject(it)?.optString("id") }.toMutableSet()
                for (i in 0 until incoming.length()) {
                    val o = incoming.optJSONObject(i) ?: continue
                    val id = o.optString("id"); val name = o.optString("name").take(40)
                    if (id.isBlank() || id.length > 40 || name.isBlank() || id in have) continue
                    current.put(JSONObject().put("id", id).put("name", name)); have += id; albums++
                }
                if (albums > 0) {
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(current.toString())
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                }
            }

            // Presets: add the ones with ids the library doesn't have.
            var presets = 0
            presetsJson?.let { json ->
                val store = UserPresets(context.filesDir)
                if (store.file.exists() && runCatching { UserPresets.parse(store.file.readText()) }.isFailure) return@let
                val mine = store.load()
                val add = runCatching { UserPresets.parse(json) }.getOrDefault(emptyList()).filter { p -> mine.none { it.id == p.id } }
                if (add.isNotEmpty()) { store.save(mine + add); presets = add.size }
            }

            // LUTs: by id, from the staged tables.
            var luts = 0
            lutIndex?.let { json ->
                val a = runCatching { JSONArray(json) }.getOrNull() ?: return@let
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (!Luts.isValidId(id)) continue
                    val f = File(lutStage, "$id.lut")
                    if (f.exists() && Luts.restore(LutInfo(id, o.optString("name").ifBlank { "LUT" }, o.optInt("size")), f)) luts++
                }
            }
            return Result(photos, albums, presets, luts)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun existingIds(): Set<String> = libDir.listFiles().orEmpty()
        .filter { it.name.endsWith(".img") || it.name.endsWith(".vid") }.map { it.nameWithoutExtension }.toSet()

    /** Calls [onEntry] for every file in the ZIP; the stream must be read or skipped by it. */
    private fun read(uri: Uri, onEntry: (String, InputStream) -> Unit) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Can't read the file")
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) onEntry(e.name, zip)
                zip.closeEntry()
            }
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            check(out.size() <= max) { "Entry too large" }
        }
        return out.toByteArray()
    }

    private fun skipCounting(input: InputStream): Long {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) { val n = input.read(buf); if (n < 0) break; total += n }
        return total
    }

    /** Copies an entry to [target], refusing to go past [budget] bytes (the phone's free space, with room to spare). */
    private fun copyLimited(input: InputStream, target: File, budget: Long): Long {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        target.outputStream().use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > budget) throw java.io.IOException("Not enough free space on the phone")
                out.write(buf, 0, n)
            }
        }
        return total
    }

    companion object {
        const val FORMAT = "lumen-library"
        const val MANIFEST = "lumen-library.json"
        private const val JSON_MAX = 8 * 1024 * 1024
        private const val SPACE_RESERVE = 200L * 1024 * 1024

        /**
         * Every file name LumenCtrl itself puts in its library folder, with the photo id as group 1. Anything else in
         * an archive (other names, folders, "../") is ignored.
         */
        val LIBRARY_FILE = Regex(
            "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})" +
                "(\\.img|\\.vid|\\.json|\\.meta\\.json|\\.versions\\.json|_thumb\\.jpg|\\.denoise\\.jpg" +
                "|\\.ai-[0-9a-f-]{1,40}\\.png|\\.rm-[0-9a-f]{8}\\.png|\\.music-[0-9a-f]{8})$",
        )
    }
}
