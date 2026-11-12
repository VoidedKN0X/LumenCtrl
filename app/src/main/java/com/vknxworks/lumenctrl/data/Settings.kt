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
