package com.vknxworks.lumenctrl.ui

import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vknxworks.lumenctrl.render.ExportFormat
import com.vknxworks.lumenctrl.render.ExportOptions
import com.vknxworks.lumenctrl.render.MetadataMode
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlChip
import com.vknxworks.lumenctrl.ui.ctrl.CtrlRow
import com.vknxworks.lumenctrl.ui.ctrl.CtrlSheet
import com.vknxworks.lumenctrl.ui.ctrl.CtrlTextField
import com.vknxworks.lumenctrl.ui.ctrl.CtrlToggle
import com.vknxworks.lumenctrl.ui.ctrl.LabeledSlider
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel

private val SIZES = listOf<Pair<String, Int?>>(tr(R.string.full) to null, "4096" to 4096, "2048" to 2048, "1080" to 1080)

/** Export options for one or many photos: format, size, metadata policy, watermark, edit record. */
@Composable
fun ExportSheet(initial: ExportOptions, count: Int, onDismiss: () -> Unit, onExport: (ExportOptions, share: Boolean) -> Unit) {
    var o by remember { mutableStateOf(initial) }
    var watermarkOn by remember { mutableStateOf(initial.watermark != null) }
    var watermark by remember { mutableStateOf(initial.watermark ?: "") }

    CtrlSheet(if (count == 1) tr(R.string.export) else tr(R.string.export_x_photos, count), onDismiss) {
        MonoLabel(tr(R.string.format))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportFormat.entries.forEach { f -> CtrlChip(f.label, o.format == f, onClick = { o = o.copy(format = f) }) }
        }
        if (o.format != ExportFormat.PNG) {
            LabeledSlider(
                tr(R.string.quality), "${o.quality}", o.quality.toFloat(), 50f..100f, 50f, false,
                onChange = { o = o.copy(quality = it.toInt()) }, onDone = {}, onReset = { o = o.copy(quality = 92) },
            )
        }
        MonoLabel(tr(R.string.long_edge))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SIZES.forEach { (label, v) -> CtrlChip(label, o.maxDim == v, onClick = { o = o.copy(maxDim = v) }) }
        }
        MonoLabel(tr(R.string.metadata))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(MetadataMode.entries) { m -> CtrlChip(m.label, o.metadata == m, onClick = { o = o.copy(metadata = m) }) }
        }
        CtrlRow(tr(R.string.watermark), tr(R.string.text_in_the_bottom_right_corner)) {
            CtrlToggle(watermarkOn, { watermarkOn = it }, tr(R.string.watermark))
        }
        if (watermarkOn) CtrlTextField(watermark, { watermark = it.take(60) }, tr(R.string.your_name), Modifier.fillMaxWidth())
        CtrlRow(tr(R.string.embed_edit_record), tr(R.string.stores_the_edit_settings_in_the)) {
            CtrlToggle(o.embedEditRecord, { o = o.copy(embedEditRecord = it) }, tr(R.string.embed_edit_record))
        }
        if (com.vknxworks.lumenctrl.render.Hdr.supported && o.format == ExportFormat.JPEG) {
            CtrlRow(tr(R.string.ultra_hdr), tr(R.string.adds_a_gain_map_for_hdr)) {
                CtrlToggle(o.hdr, { o = o.copy(hdr = it) }, tr(R.string.ultra_hdr))
            }
        }
        val final = o.copy(watermark = watermark.takeIf { watermarkOn && it.isNotBlank() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlButton(tr(R.string.save_to_gallery), onClick = { onExport(final, false) }, icon = Icons.Outlined.Download, style = CtrlButtonStyle.Accent, modifier = Modifier.weight(1f))
            CtrlButton(tr(R.string.share), onClick = { onExport(final, true) }, icon = Icons.Outlined.Share)
        }
        MonoLabel(tr(R.string.rendered_on_this_device_nothing_is))
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
