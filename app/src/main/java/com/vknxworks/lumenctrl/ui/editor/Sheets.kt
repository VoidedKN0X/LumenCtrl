package com.vknxworks.lumenctrl.ui.editor

import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.tr
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.ui.ctrl.CardShape
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlIconButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlSheet
import com.vknxworks.lumenctrl.ui.ctrl.CtrlType
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import java.text.DateFormat
import java.util.Date

@Composable
fun VersionsSheet(vm: LumenViewModel, onDismiss: () -> Unit) {
    val c = Ctrl.colors
    CtrlSheet(tr(R.string.versions), onDismiss) {
        Text(tr(R.string.save_snapshots_of_this_edit_and), color = c.muted)
        CtrlButton(tr(R.string.save_current_as_version), onClick = vm::saveVersion, icon = Icons.Outlined.Add, style = CtrlButtonStyle.Accent)
        if (vm.versions.isEmpty()) MonoLabel(tr(R.string.no_saved_versions_yet))
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(vm.versions) { _, v ->
                Surface(shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border), onClick = { vm.restoreVersion(v); onDismiss() }) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = CtrlType.body, color = c.text)
                            MonoLabel(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(v.createdAt)))
                        }
                        CtrlIconButton(Icons.Outlined.Delete, tr(R.string.delete_x, v.name), { vm.deleteVersion(v) }, style = CtrlButtonStyle.Ghost)
                    }
                }
            }
        }
    }
}

@Composable
fun HistorySheet(vm: LumenViewModel, onDismiss: () -> Unit) {
    val c = Ctrl.colors
    CtrlSheet(tr(R.string.history), onDismiss) {
        if (vm.history.isEmpty()) MonoLabel(tr(R.string.no_changes_in_this_session))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item {
                Surface(onClick = { vm.undoTo(0); onDismiss() }, color = c.bg) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        MonoLabel("00", Modifier.padding(end = 14.dp))
                        Text(tr(R.string.opened), style = CtrlType.body, color = c.muted)
                    }
                }
            }
            itemsIndexed(vm.history) { i, label ->
                val current = i == vm.history.lastIndex
                Surface(onClick = { vm.undoTo(i + 1); onDismiss() }, color = c.bg) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        MonoLabel("%02d".format(i + 1), Modifier.padding(end = 14.dp), color = if (current) c.accentText else c.muted)
                        Text(label, style = CtrlType.body, color = if (current) c.text else c.muted)
                        Spacer(Modifier.weight(1f))
                        if (current) MonoLabel(tr(R.string.current), color = c.accentText)
                    }
                }
            }
        }
        MonoLabel(tr(R.string.tap_a_step_to_go_back))
    }
}

@Composable
fun InfoSheet(vm: LumenViewModel, onDismiss: () -> Unit) {
    val c = Ctrl.colors
    val m = vm.openPhoto?.meta ?: return
    CtrlSheet(tr(R.string.info), onDismiss) {
        Text(m.name, style = CtrlType.title, color = c.text)
        Surface(shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                val rows = listOfNotNull(
                    tr(R.string.taken) to (m.takenAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: tr(R.string.unknown)),
                    tr(R.string.imported) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.importedAt)),
                    m.camera?.let { tr(R.string.camera) to it },
                    m.lens?.let { tr(R.string.lens) to it },
                    listOfNotNull(m.shutter, m.aperture, m.iso, m.focal).takeIf { it.isNotEmpty() }?.let { tr(R.string.exposure) to it.joinToString(" · ") },
                    if (m.width > 0) tr(R.string.size) to "${m.width} × ${m.height} · ${"%.1f".format(m.width * m.height / 1e6)} MP" else null,
                    tr(R.string.file) to "%.1f MB".format(m.bytes / 1e6),
                    tr(R.string.rating) to if (m.rating == 0) tr(R.string.none) else "★".repeat(m.rating),
                    tr(R.string.flag) to when (m.flag) { 1 -> tr(R.string.pick); -1 -> tr(R.string.reject); else -> tr(R.string.none) },
                )
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        MonoLabel(k, Modifier.weight(0.35f))
                        Text(v, style = CtrlType.value, color = c.text, modifier = Modifier.weight(0.65f))
                    }
                }
            }
        }
        MonoLabel(tr(R.string.stored_privately_on_this_device_originals))
    }
}
