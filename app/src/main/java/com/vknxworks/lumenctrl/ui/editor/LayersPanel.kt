package com.vknxworks.lumenctrl.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.model.Overlay
import com.vknxworks.lumenctrl.tr
import com.vknxworks.lumenctrl.ui.ConfirmDialog
import com.vknxworks.lumenctrl.ui.TextDialog
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlChip
import com.vknxworks.lumenctrl.ui.ctrl.LabeledSlider
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import java.util.UUID

/** Text & draw: text layers you can place, resize and recolour, and freehand drawing, on top of the photo. */
@Composable
fun LayersPanel(vm: LumenViewModel, st: EditorState) {
    val layers = vm.params.layers
    val sel = layers.firstOrNull { it.id == st.selectedLayer && it.kind == Overlay.Kind.TEXT }
    var dialog by remember { mutableStateOf<String?>(null) }

    fun update(label: String, live: Boolean, t: (Overlay) -> Overlay) {
        val id = sel?.id ?: return
        val p = vm.params
        val next = p.copy(layers = p.layers.map { if (it.id == id) t(it) else it })
        if (live) vm.setLiveParams(next, label) else vm.edit(label) { next }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            CtrlChip(tr(R.string.text), st.layerMode == LayerMode.TEXT, icon = Icons.Outlined.TextFields, onClick = { st.layerMode = LayerMode.TEXT })
            CtrlChip(tr(R.string.draw), st.layerMode == LayerMode.DRAW, icon = Icons.Outlined.Brush, onClick = { st.layerMode = LayerMode.DRAW; st.selectedLayer = null })
            MonoLabel(tr(R.string.x_layers, layers.size), Modifier.padding(start = 6.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
            if (st.layerMode == LayerMode.TEXT) {
                item {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CtrlButton(tr(R.string.add_text), onClick = { dialog = "add" }, icon = Icons.Outlined.Add, style = CtrlButtonStyle.Accent)
                        if (sel != null) {
                            CtrlButton(tr(R.string.edit_text), onClick = { dialog = "edit" }, icon = Icons.Outlined.Edit)
                            CtrlButton(tr(R.string.delete), onClick = {
                                vm.edit(tr(R.string.delete_text)) { p -> p.copy(layers = p.layers.filter { it.id != sel.id }) }
                                st.selectedLayer = null
                            }, icon = Icons.Outlined.Delete, style = CtrlButtonStyle.Ghost)
                        }
                    }
                }
                if (sel == null) {
                    item { MonoLabel(tr(R.string.text_layers_hint), Modifier.padding(start = 4.dp, top = 8.dp)) }
                } else {
                    item {
                        LabeledSlider(
                            tr(R.string.size), "${(sel.size * 1000).toInt()}", sel.size, Overlay.TEXT_SIZES, Overlay.TEXT_SIZES.start, true,
                            onChange = { v -> update(tr(R.string.text_size), true) { it.copy(size = v) } }, onDone = vm::commit, onReset = {},
                        )
                    }
                    item { Swatches(sel.color) { col -> update(tr(R.string.text_color), false) { it.copy(color = col) } } }
                    item {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CtrlChip(tr(R.string.background_box), sel.box, onClick = { update(tr(R.string.background_box), false) { it.copy(box = !it.box) } })
                        }
                    }
                    item { MonoLabel(tr(R.string.drag_the_text_to_move_it), Modifier.padding(start = 4.dp, top = 8.dp)) }
                }
            } else {
                item { Swatches(st.drawColor) { st.drawColor = it } }
                item {
                    LabeledSlider(
                        tr(R.string.line_width), "${(st.drawWidth * 1000).toInt()}", st.drawWidth, Overlay.LINE_WIDTHS, Overlay.LINE_WIDTHS.start, false,
                        onChange = { st.drawWidth = it }, onDone = {}, onReset = { st.drawWidth = 0.008f },
                    )
                }
                if (layers.any { it.kind == Overlay.Kind.DRAW }) {
                    item {
                        CtrlButton(
                            tr(R.string.clear_drawing), onClick = { dialog = "clear" }, icon = Icons.Outlined.Delete,
                            style = CtrlButtonStyle.Ghost, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item { MonoLabel(tr(R.string.drawing_hint), Modifier.padding(start = 4.dp, top = 8.dp)) }
            }
        }
    }

    when (dialog) {
        "add" -> TextDialog(
            tr(R.string.add_text), "", tr(R.string.add),
            onConfirm = { text ->
                if (text.isNotBlank()) {
                    if (vm.params.layers.size >= Overlay.MAX_LAYERS) vm.toast(tr(R.string.up_to_x_layers_per_photo, Overlay.MAX_LAYERS))
                    else {
                        val id = UUID.randomUUID().toString().take(8)
                        vm.edit(tr(R.string.add_text)) { p -> p.copy(layers = p.layers + Overlay(id, Overlay.Kind.TEXT, text = text.trim())) }
                        st.selectedLayer = id
                    }
                }
            },
            onDismiss = { dialog = null }, maxLength = Overlay.MAX_TEXT, placeholder = tr(R.string.your_text),
        )
        "edit" -> if (sel != null) {
            TextDialog(
                tr(R.string.edit_text), sel.text, tr(R.string.save),
                onConfirm = { text -> if (text.isNotBlank()) update(tr(R.string.edit_text), false) { it.copy(text = text.trim()) } },
                onDismiss = { dialog = null }, maxLength = Overlay.MAX_TEXT, placeholder = tr(R.string.your_text),
            )
        } else {
            dialog = null
        }
        "clear" -> ConfirmDialog(
            tr(R.string.clear_drawing_q), tr(R.string.clear_drawing_text), tr(R.string.clear),
            onConfirm = { vm.edit(tr(R.string.clear_drawing)) { p -> p.copy(layers = p.layers.filter { it.kind != Overlay.Kind.DRAW }) } },
            onDismiss = { dialog = null },
        )
    }
}

/** A row of colour dots; the chosen one has an accent ring. */
@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) {
    val c = Ctrl.colors
    LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(Overlay.COLORS) { col ->
            val on = col == selected
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(if (on) 3.dp else 1.dp, if (on) c.accent else c.borderStrong), CircleShape)
                    .padding(if (on) 5.dp else 3.dp)
                    .clip(CircleShape)
                    .background(Color(col))
                    .clickable(role = Role.Button) { onPick(col) }
                    .semantics { contentDescription = tr(R.string.color_x, "#%06X".format(col and 0xFFFFFF)); this.selected = on },
            )
        }
    }
}
