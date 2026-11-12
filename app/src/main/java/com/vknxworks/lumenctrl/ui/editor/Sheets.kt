package com.vknxworks.lumenctrl.ui.editor

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
    CtrlSheet("Versions", onDismiss) {
        Text("Save snapshots of this edit and switch between them. Restoring is undoable.", color = c.muted)
        CtrlButton("Save current as version", onClick = vm::saveVersion, icon = Icons.Outlined.Add, style = CtrlButtonStyle.Accent)
        if (vm.versions.isEmpty()) MonoLabel("No saved versions yet")
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(vm.versions) { _, v ->
                Surface(shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border), onClick = { vm.restoreVersion(v); onDismiss() }) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = CtrlType.body, color = c.text)
                            MonoLabel(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(v.createdAt)))
                        }
                        CtrlIconButton(Icons.Outlined.Delete, "Delete ${v.name}", { vm.deleteVersion(v) }, style = CtrlButtonStyle.Ghost)
                    }
                }
            }
        }
    }
}

@Composable
fun HistorySheet(vm: LumenViewModel, onDismiss: () -> Unit) {
    val c = Ctrl.colors
    CtrlSheet("History", onDismiss) {
        if (vm.history.isEmpty()) MonoLabel("No changes in this session")
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item {
                Surface(onClick = { vm.undoTo(0); onDismiss() }, color = c.bg) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        MonoLabel("00", Modifier.padding(end = 14.dp))
                        Text("Opened", style = CtrlType.body, color = c.muted)
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
                        if (current) MonoLabel("Current", color = c.accentText)
                    }
                }
            }
        }
        MonoLabel("Tap a step to go back to it")
    }
}

@Composable
fun InfoSheet(vm: LumenViewModel, onDismiss: () -> Unit) {
    val c = Ctrl.colors
    val m = vm.openPhoto?.meta ?: return
    CtrlSheet("Info", onDismiss) {
        Text(m.name, style = CtrlType.title, color = c.text)
        Surface(shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border)) {
            Column(Modifier.padding(vertical = 6.dp)) {
                val rows = listOfNotNull(
                    "Taken" to (m.takenAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Unknown"),
                    "Imported" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.importedAt)),
                    m.camera?.let { "Camera" to it },
                    m.lens?.let { "Lens" to it },
                    listOfNotNull(m.shutter, m.aperture, m.iso, m.focal).takeIf { it.isNotEmpty() }?.let { "Exposure" to it.joinToString(" · ") },
                    if (m.width > 0) "Size" to "${m.width} × ${m.height} · ${"%.1f".format(m.width * m.height / 1e6)} MP" else null,
                    "File" to "%.1f MB".format(m.bytes / 1e6),
                    "Rating" to if (m.rating == 0) "None" else "★".repeat(m.rating),
                    "Flag" to when (m.flag) { 1 -> "Pick"; -1 -> "Reject"; else -> "None" },
                )
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        MonoLabel(k, Modifier.weight(0.35f))
                        Text(v, style = CtrlType.value, color = c.text, modifier = Modifier.weight(0.65f))
                    }
                }
            }
        }
        MonoLabel("Stored privately on this device · originals untouched")
    }
}
