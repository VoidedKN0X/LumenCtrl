package com.vknxworks.lumenctrl.data

import com.vknxworks.lumenctrl.model.Curves
import com.vknxworks.lumenctrl.model.Param
import com.vknxworks.lumenctrl.model.UserPreset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The user's own presets, in `filesDir/presets.json`. Call off the main thread. */
class UserPresets(filesDir: File) {
    val file = File(filesDir, "presets.json")

    fun load(): List<UserPreset> = if (!file.exists()) emptyList() else runCatching { parse(file.readText()) }.getOrDefault(emptyList())

    fun save(list: List<UserPreset>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(toJson(list))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        private val ID = Regex("^[a-f0-9-]{8,40}$")

        fun toJson(list: List<UserPreset>): String {
            val a = JSONArray()
            list.forEach { p ->
                val v = JSONObject()
                p.values.forEach { (k, x) -> v.put(k, x.toDouble()) }
                a.put(
                    JSONObject().put("id", p.id).put("name", p.name).put("values", v)
                        .put("curves", p.curves.toJson()).put("lut", p.lut ?: JSONObject.NULL)
                )
            }
            return JSONObject().put("format", "lumen-presets").put("version", 1).put("presets", a).toString(2)
        }

        /** Reads presets; entries that don't make sense are skipped. */
        fun parse(json: String): List<UserPreset> {
            val a = JSONObject(json).optJSONArray("presets") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                runCatching {
                    val o = a.getJSONObject(i)
                    val id = o.getString("id").takeIf { ID.matches(it) } ?: return@runCatching null
                    val v = o.optJSONObject("values")
                    val values = buildMap<String, Float> {
                        v?.keys()?.forEach { k ->
                            val p = Param.byKey[k] ?: return@forEach
                            if (k in Param.PER_PHOTO) return@forEach
                            put(k, v.getDouble(k).toFloat().coerceIn(p.min, p.max))
                        }
                    }
                    UserPreset(
                        id, o.optString("name").trim().ifBlank { "Preset" }.take(40), values,
                        Curves.fromJson(o.optJSONObject("curves")),
                        if (o.isNull("lut")) null else o.optString("lut").takeIf { ID.matches(it) },
                    )
                }.getOrNull()
            }.distinctBy { it.id }
        }
    }
}
