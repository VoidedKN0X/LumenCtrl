package com.vknxworks.lumenctrl.data

import com.vknxworks.lumenctrl.model.CubeLut
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** An imported 3D LUT as the user sees it. */
data class LutInfo(val id: String, val name: String, val size: Int)

/**
 * Imported 3D LUTs (`.cube` files), kept in `filesDir/luts`: `luts.json` lists them, and each table is stored
 * already parsed as `<id>.lut` (a small binary file), so rendering never parses text. An edit refers to a LUT by its
 * id; a missing or unreadable table simply renders without it.
 *
 * Shared by the preview, export, thumbnails and video (all render threads), hence one object with a lock.
 */
object Luts {
    @Volatile private var dir: File? = null
    private val lock = Any()
    /** The tables used last, by id. Small: LUTs are at most 96³ (about 10 MB as floats), usually 33³ (430 KB). */
    private val cache = LinkedHashMap<String, CubeLut>(4, 0.75f, true)
    private const val CACHE_MAX = 3
    private const val MAGIC = 0x4C555431 // "LUT1"

    /** A LUT id as LumenCtrl makes them; anything else (from a damaged or foreign file) is never used as a file name. */
    private val ID = Regex("^[a-f0-9-]{8,40}$")

    fun init(filesDir: File) {
        dir = File(filesDir, "luts").apply { mkdirs() }
    }

    fun isValidId(id: String) = ID.matches(id)

    private fun indexFile() = dir?.let { File(it, "luts.json") }

    fun list(): List<LutInfo> = synchronized(lock) {
        val f = indexFile() ?: return emptyList()
        if (!f.exists()) return emptyList()
        runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val id = o.getString("id")
                if (!isValidId(id)) null else LutInfo(id, o.optString("name").ifBlank { "LUT" }.take(60), o.optInt("size"))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(list: List<LutInfo>) {
        val f = indexFile() ?: return
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size)) }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Stores a parsed LUT under [name] and returns it. Call off the main thread. */
    fun add(lut: CubeLut, name: String): LutInfo = synchronized(lock) {
        val d = dir ?: error("LUT storage not ready")
        val info = LutInfo(UUID.randomUUID().toString(), name.trim().ifBlank { "LUT" }.take(60), lut.size)
        write(File(d, "${info.id}.lut"), lut)
        writeIndex(list() + info)
        cache[info.id] = lut
        info
    }

    /** Adds a LUT file from a library backup under its own id, unless one with that id is already here. */
    fun restore(info: LutInfo, file: File): Boolean = synchronized(lock) {
        val d = dir ?: return false
        if (!isValidId(info.id) || list().any { it.id == info.id }) return false
        val lut = runCatching { read(file) }.getOrNull() ?: return false
        write(File(d, "${info.id}.lut"), lut)
        writeIndex(list() + info.copy(name = info.name.take(60)))
        true
    }

    fun rename(id: String, name: String) {
        synchronized(lock) {
            writeIndex(list().map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }.take(60)) else it })
        }
    }

    fun delete(id: String) {
        if (!isValidId(id)) return
        synchronized(lock) {
            writeIndex(list().filter { it.id != id })
            dir?.let { File(it, "$id.lut").delete() }
            cache.remove(id)
        }
    }

    /** The stored file of a LUT (for library export), or null. */
    fun fileOf(id: String): File? = if (!isValidId(id)) null else dir?.let { File(it, "$id.lut") }?.takeIf { it.exists() }

    /** The table for [id], or null when it doesn't exist or can't be read. May read the disk once. */
    fun get(id: String): CubeLut? = synchronized(lock) {
        cache[id]?.let { return it }
        val f = fileOf(id) ?: return null
        val lut = runCatching { read(f) }.getOrNull() ?: return null
        cache[id] = lut
        while (cache.size > CACHE_MAX) cache.remove(cache.keys.first())
        lut
    }

    private fun write(f: File, lut: CubeLut) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(lut.size)
            lut.domainMin.forEach(out::writeFloat)
            lut.domainMax.forEach(out::writeFloat)
            lut.data.forEach(out::writeFloat)
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun read(f: File): CubeLut = DataInputStream(f.inputStream().buffered()).use { inp ->
        check(inp.readInt() == MAGIC) { "Not a LumenCtrl LUT" }
        val size = inp.readInt()
        check(size in CubeLut.MIN_SIZE..CubeLut.MAX_SIZE) { "Bad LUT size" }
        val min = FloatArray(3) { inp.readFloat() }
        val max = FloatArray(3) { inp.readFloat() }
        val data = FloatArray(size * size * size * 3) { inp.readFloat() }
        check(data.all { it.isFinite() } && (0 until 3).all { max[it] > min[it] }) { "Bad LUT data" }
        CubeLut(null, size, min, max, data)
    }
}
