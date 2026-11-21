package com.vknxworks.lumenctrl.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vknxworks.lumenctrl.render.ExportFormat
import com.vknxworks.lumenctrl.render.ExportOptions
import com.vknxworks.lumenctrl.render.MetadataMode

/** App preferences, kept in private SharedPreferences and exposed as Compose state. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    var appLock by mutableStateOf(prefs.getBoolean("appLock", false)); private set
    var hideInScreenshots by mutableStateOf(prefs.getBoolean("hideShots", false)); private set
    var hideInRecents by mutableStateOf(prefs.getBoolean("hideRecents", false)); private set
    var accent by mutableIntStateOf(prefs.getInt("accent", 0)); private set
    var export by mutableStateOf(loadExport()); private set

    fun updateAppLock(v: Boolean) { appLock = v; prefs.edit().putBoolean("appLock", v).apply() }
    fun updateHideInScreenshots(v: Boolean) { hideInScreenshots = v; prefs.edit().putBoolean("hideShots", v).apply() }
    fun updateHideInRecents(v: Boolean) { hideInRecents = v; prefs.edit().putBoolean("hideRecents", v).apply() }
    fun updateAccent(i: Int) { accent = i; prefs.edit().putInt("accent", i).apply() }

    fun updateExport(o: ExportOptions) {
        export = o
        prefs.edit()
            .putString("exFormat", o.format.name).putInt("exQuality", o.quality)
            .putInt("exMax", o.maxDim ?: 0).putString("exMeta", o.metadata.name)
            .putString("exWatermark", o.watermark ?: "").putBoolean("exRecord", o.embedEditRecord)
            .putBoolean("exHdr", o.hdr)
            .apply()
    }

    // ---- Settings file (export and import) ----

    /**
     * The settings as a small JSON file: privacy switches, accent and export defaults. Never the app lock or anything
     * secret; importing one can't turn the app lock off.
     */
    fun toFileJson(): String = org.json.JSONObject()
        .put("format", FILE_FORMAT).put("version", 1)
        .put("hideInScreenshots", hideInScreenshots).put("hideInRecents", hideInRecents).put("accent", accent)
        .put(
            "export", org.json.JSONObject()
                .put("format", export.format.name).put("quality", export.quality).put("maxDim", export.maxDim ?: 0)
                .put("metadata", export.metadata.name).put("watermark", export.watermark ?: "")
                .put("embedEditRecord", export.embedEditRecord).put("hdr", export.hdr),
        )
        .toString(2)

    /** What a settings file would set. */
    data class Incoming(val hideInScreenshots: Boolean, val hideInRecents: Boolean, val accent: Int, val export: ExportOptions)

    /** One setting that an import would change: its name, the value now and the value from the file. */
    data class Change(val label: String, val from: String, val to: String)

    fun changesFrom(i: Incoming, accentName: (Int) -> String, onOff: (Boolean) -> String): List<Change> = buildList {
        fun add(label: String, a: String, b: String) { if (a != b) add(Change(label, a, b)) }
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.hide_in_screenshots), onOff(hideInScreenshots), onOff(i.hideInScreenshots))
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.hide_in_recent_apps), onOff(hideInRecents), onOff(i.hideInRecents))
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.accent_color), accentName(accent), accentName(i.accent))
        val e = export; val n = i.export
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.format), e.format.label, n.format.label)
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.quality), "${e.quality}", "${n.quality}")
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.long_edge), e.maxDim?.toString() ?: com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.full), n.maxDim?.toString() ?: com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.full))
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.metadata), e.metadata.label, n.metadata.label)
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.watermark), e.watermark ?: onOff(false), n.watermark ?: onOff(false))
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.embed_edit_record), onOff(e.embedEditRecord), onOff(n.embedEditRecord))
        add(com.vknxworks.lumenctrl.tr(com.vknxworks.lumenctrl.R.string.ultra_hdr), onOff(e.hdr), onOff(n.hdr))
    }

    fun apply(i: Incoming) {
        updateHideInScreenshots(i.hideInScreenshots)
        updateHideInRecents(i.hideInRecents)
        updateAccent(i.accent)
        updateExport(i.export)
    }

    companion object {
        const val FILE_FORMAT = "lumen-settings"

        /** Reads a settings file; values that don't make sense fall back to the defaults. Throws if it isn't one. */
        fun parseFile(json: String, accents: Int): Incoming {
            val o = org.json.JSONObject(json)
            require(o.optString("format") == FILE_FORMAT) { "not a LumenCtrl settings file" }
            val e = o.optJSONObject("export") ?: org.json.JSONObject()
            val export = ExportOptions(
                format = runCatching { ExportFormat.valueOf(e.optString("format")) }.getOrDefault(ExportFormat.JPEG),
                quality = e.optInt("quality", 92).coerceIn(50, 100),
                maxDim = e.optInt("maxDim", 0).takeIf { it in 256..20000 },
                metadata = runCatching { MetadataMode.valueOf(e.optString("metadata")) }.getOrDefault(MetadataMode.NO_LOCATION),
                watermark = e.optString("watermark").take(60).ifBlank { null },
                embedEditRecord = e.optBoolean("embedEditRecord", true),
                hdr = e.optBoolean("hdr", true),
            )
            return Incoming(
                o.optBoolean("hideInScreenshots", false), o.optBoolean("hideInRecents", false),
                o.optInt("accent", 0).let { if (it in 0 until accents) it else 0 }, export,
            )
        }
    }

    private fun loadExport() = ExportOptions(
        format = runCatching { ExportFormat.valueOf(prefs.getString("exFormat", "JPEG")!!) }.getOrDefault(ExportFormat.JPEG),
        quality = prefs.getInt("exQuality", 92),
        maxDim = prefs.getInt("exMax", 0).takeIf { it > 0 },
        metadata = runCatching { MetadataMode.valueOf(prefs.getString("exMeta", "NO_LOCATION")!!) }.getOrDefault(MetadataMode.NO_LOCATION),
        watermark = prefs.getString("exWatermark", "")!!.ifBlank { null },
        embedEditRecord = prefs.getBoolean("exRecord", true),
        hdr = prefs.getBoolean("exHdr", true),
    )
}
