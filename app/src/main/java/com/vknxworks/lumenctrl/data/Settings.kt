package com.vknxworks.lumenctrl.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vknxworks.lumenctrl.render.ExportFormat
import com.vknxworks.lumenctrl.render.ExportOptions
import com.vknxworks.lumenctrl.render.MetadataMode

/** "Lock again": how long you can be away before the app lock asks again (the series' choices). */
enum class LockTimeout(val label: Int, val millis: Long) {
    IMMEDIATELY(com.vknxworks.lumenctrl.R.string.lock_again_immediately, 0L),
    MINUTE_1(com.vknxworks.lumenctrl.R.string.lock_again_1m, 60_000L),
    MINUTES_5(com.vknxworks.lumenctrl.R.string.lock_again_5m, 5 * 60_000L),
    MINUTES_15(com.vknxworks.lumenctrl.R.string.lock_again_15m, 15 * 60_000L),
    HOUR_1(com.vknxworks.lumenctrl.R.string.lock_again_1h, 60 * 60_000L),
    RESTART(com.vknxworks.lumenctrl.R.string.lock_again_restart, -1L),
}

/** App preferences, kept in private SharedPreferences and exposed as Compose state. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    /**
     * The old app lock (the phone's screen lock, before LumenCtrl had its own PIN). Only read to move people who had
     * it on to the PIN lock; the PIN lock itself lives in data/lock/LockVault.
     */
    var appLock by mutableStateOf(prefs.getBoolean("appLock", false)); private set
    /** Lock again after this long away; 1 minute, as LumenCtrl always did, until you choose. */
    var lockTimeout by mutableStateOf(
        runCatching { LockTimeout.valueOf(prefs.getString("lockTimeout", LockTimeout.MINUTE_1.name)!!) }.getOrDefault(LockTimeout.MINUTE_1),
    ); private set
    var hideInScreenshots by mutableStateOf(prefs.getBoolean("hideShots", false)); private set
    var hideInRecents by mutableStateOf(prefs.getBoolean("hideRecents", false)); private set
    var accent by mutableIntStateOf(prefs.getInt("accent", 0)); private set
    var export by mutableStateOf(loadExport()); private set

    fun updateAppLock(v: Boolean) { appLock = v; prefs.edit().putBoolean("appLock", v).apply() }
    fun updateHideInScreenshots(v: Boolean) { hideInScreenshots = v; prefs.edit().putBoolean("hideShots", v).apply() }
    fun updateHideInRecents(v: Boolean) { hideInRecents = v; prefs.edit().putBoolean("hideRecents", v).apply() }
    fun updateAccent(i: Int) { accent = i; prefs.edit().putInt("accent", i).apply() }
    fun updateLockTimeout(t: LockTimeout) { lockTimeout = t; prefs.edit().putString("lockTimeout", t.name).apply() }

    /** Back to the defaults ("Forgot PIN?"). */
    fun resetAll() {
        prefs.edit().clear().commit()
        appLock = false; hideInScreenshots = false; hideInRecents = false; accent = 0
        lockTimeout = LockTimeout.MINUTE_1
        export = loadExport()
    }

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

    /**
     * A settings file can only make privacy stricter, never weaker: "Hide in screenshots" and "Hide in recent apps"
     * stay on if they are on now, and the export metadata choice only moves towards removing more
     * (Keep all -> Remove location -> Strip all). So a file someone hands you can't quietly turn these off.
     */
    fun stricter(i: Incoming): Incoming = i.copy(
        hideInScreenshots = ImportRules.keepOn(hideInScreenshots, i.hideInScreenshots),
        hideInRecents = ImportRules.keepOn(hideInRecents, i.hideInRecents),
        export = i.export.copy(
            metadata = ImportRules.stricterOf(export.metadata, i.export.metadata, METADATA_STRICTNESS),
        ),
    )

    fun changesFrom(raw: Incoming, accentName: (Int) -> String, onOff: (Boolean) -> String): List<Change> = buildList {
        val i = stricter(raw)
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

    fun apply(raw: Incoming) {
        val i = stricter(raw)
        updateHideInScreenshots(i.hideInScreenshots)
        updateHideInRecents(i.hideInRecents)
        updateAccent(i.accent)
        updateExport(i.export)
    }

    companion object {
        const val FILE_FORMAT = "lumen-settings"

        /** Export metadata choices from least to most private. */
        private val METADATA_STRICTNESS = listOf(MetadataMode.ALL, MetadataMode.NO_LOCATION, MetadataMode.NONE)

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
