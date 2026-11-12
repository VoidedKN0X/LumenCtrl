package com.vknxworks.lumenctrl.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewTimeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.model.EditParams
import com.vknxworks.lumenctrl.model.Group
import com.vknxworks.lumenctrl.model.MixBand
import com.vknxworks.lumenctrl.model.Param
import com.vknxworks.lumenctrl.ui.ExportSheet
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlIconButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlMenu
import com.vknxworks.lumenctrl.ui.ctrl.CtrlMenuItem
import com.vknxworks.lumenctrl.ui.ctrl.CtrlNavPill
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import com.vknxworks.lumenctrl.ui.ctrl.NavItem

enum class Tool(val label: String) { PRESETS("Presets"), EDIT("Edit"), MASKS("Masks"), HEAL("Heal"), CROP("Crop") }

enum class Sheet { EXPORT, VERSIONS, HISTORY, INFO }

/** UI-only editor state (which tool, selections, pick modes). Edit data lives in the ViewModel. */
@Stable
class EditorState {
    var tool by mutableStateOf(Tool.EDIT)
    var group by mutableStateOf(Group.LIGHT)
    var selectedMask by mutableIntStateOf(-1)
    var selectedSpot by mutableIntStateOf(-1)
    var showMaskOverlay by mutableStateOf(true)
    var pickWhiteBalance by mutableStateOf(false)
    var pickFocus by mutableStateOf(false)
    var curveChannel by mutableIntStateOf(0)
    var mixBand by mutableStateOf(MixBand.RED)
    var healRadius by mutableFloatStateOf(0.04f)
    var showBefore by mutableStateOf(false)
    var sheet by mutableStateOf<Sheet?>(null)
    var vizHdr by mutableStateOf(false)
    var hdrView by mutableStateOf(false)
}

private val toolItems = listOf(
    NavItem(Tool.PRESETS.label, Icons.Outlined.AutoAwesome),
    NavItem(Tool.EDIT.label, Icons.Outlined.Tune),
    NavItem(Tool.MASKS.label, Icons.Outlined.Layers),
    NavItem(Tool.HEAL.label, Icons.Outlined.Healing),
    NavItem(Tool.CROP.label, Icons.Outlined.Crop),
)

@Composable
fun EditorScreen(vm: LumenViewModel) {
    val c = Ctrl.colors
    val st = remember { EditorState() }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        TopBar(vm, st)
        ImageArea(vm, st, Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
        Box(Modifier.fillMaxWidth().height(262.dp)) {
            when (st.tool) {
                Tool.PRESETS -> PresetPanel(vm)
                Tool.EDIT -> EditPanel(vm, st)
                Tool.MASKS -> MasksPanel(vm, st)
                Tool.HEAL -> HealPanel(vm, st)
                Tool.CROP -> CropPanel(vm)
            }
        }
        CtrlNavPill(
            items = toolItems, selected = st.tool.ordinal,
            onSelect = {
                st.tool = Tool.entries[it]
                st.pickWhiteBalance = false
                st.pickFocus = false
            },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
        )
    }

    when (st.sheet) {
        Sheet.EXPORT -> ExportSheet(
            initial = vm.settings.export, count = 1,
            onDismiss = { st.sheet = null },
            onExport = { opts, share -> vm.settings.updateExport(opts); vm.exportCurrent(opts, share); st.sheet = null },
        )
        Sheet.VERSIONS -> VersionsSheet(vm) { st.sheet = null }
        Sheet.HISTORY -> HistorySheet(vm) { st.sheet = null }
        Sheet.INFO -> InfoSheet(vm) { st.sheet = null }
        null -> Unit
    }
}

@Composable
private fun TopBar(vm: LumenViewModel, st: EditorState) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CtrlIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", vm::close)
        Spacer(Modifier.size(10.dp))
        MonoLabel(vm.openPhoto?.meta?.name?.take(18) ?: "", Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val ghost = CtrlButtonStyle.Ghost
            CtrlIconButton(Icons.AutoMirrored.Outlined.Undo, "Undo", vm::undo, style = ghost, enabled = vm.canUndo, size = 40)
            CtrlIconButton(Icons.AutoMirrored.Outlined.Redo, "Redo", vm::redo, style = ghost, enabled = vm.canRedo, size = 40)
            Box {
                CtrlIconButton(Icons.Outlined.MoreVert, "More actions", { menu = true }, style = ghost, size = 40)
                CtrlMenu(menu, { menu = false }) {
                    CtrlMenuItem("Copy settings", Icons.Outlined.ContentCopy) { vm.copySettings(); menu = false }
                    CtrlMenuItem("Paste settings", Icons.Outlined.ContentPaste, enabled = vm.clipboard != null) { vm.pasteSettings(); menu = false }
                    CtrlMenuItem("Versions", Icons.Outlined.ViewTimeline) { st.sheet = Sheet.VERSIONS; menu = false }
                    CtrlMenuItem("History", Icons.Outlined.History) { st.sheet = Sheet.HISTORY; menu = false }
                    CtrlMenuItem("Info", Icons.Outlined.Info) { st.sheet = Sheet.INFO; menu = false }
                    CtrlMenuItem("Reset all", Icons.Outlined.RestartAlt) { vm.edit("Reset all") { EditParams() }; menu = false }
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        CtrlIconButton(Icons.Outlined.Download, "Export or share", { st.sheet = Sheet.EXPORT }, style = CtrlButtonStyle.Accent, enabled = vm.preview != null)
    }
}

/** Params shown as sliders for a group (hidden ones excluded). */
internal fun sliderParams(group: Group) = Param.entries.filter { it.group == group && it.ui }
