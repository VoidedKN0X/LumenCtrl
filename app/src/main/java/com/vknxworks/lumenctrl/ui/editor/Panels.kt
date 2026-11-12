package com.vknxworks.lumenctrl.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.vknxworks.lumenctrl.LumenViewModel
import androidx.compose.material.icons.outlined.AutoAwesome
import com.vknxworks.lumenctrl.model.AiKind
import com.vknxworks.lumenctrl.model.AspectLock
import com.vknxworks.lumenctrl.model.CurvePoint
import com.vknxworks.lumenctrl.model.Group
import com.vknxworks.lumenctrl.model.MaskAdj
import com.vknxworks.lumenctrl.model.MaskType
import com.vknxworks.lumenctrl.model.MixBand
import com.vknxworks.lumenctrl.model.Param
import com.vknxworks.lumenctrl.model.Presets
import com.vknxworks.lumenctrl.render.CurveLut
import com.vknxworks.lumenctrl.render.Geometry
import com.vknxworks.lumenctrl.ui.ctrl.CardShape
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlChip
import com.vknxworks.lumenctrl.ui.ctrl.LabeledSlider
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import kotlin.math.abs

private val HueBrush = Brush.horizontalGradient(
    listOf(0xFFFF4D4D, 0xFFFFD84D, 0xFF5CFF6A, 0xFF4DE1FF, 0xFF5C6BFF, 0xFFE14DFF, 0xFFFF4D4D).map { Color(it) }
)

@Composable
private fun ParamSlider(vm: LumenViewModel, p: Param) {
    val v = vm.params[p]
    LabeledSlider(
        label = p.label, valueText = p.format(v), value = v,
        range = p.min..p.max, origin = if (p.min < 0f) 0f else p.default,
        changed = v != p.default,
        onChange = { vm.setLive(p, it) }, onDone = vm::commit, onReset = { vm.reset(p) },
        trackBrush = if (p.fmt == com.vknxworks.lumenctrl.model.Fmt.HUE) HueBrush else null,
    )
}

/** Sliders with section headers, e.g. Effects -> Presence / Vignette / Grain / Glow. */
private fun LazyListScope.paramSections(vm: LumenViewModel, params: List<Param>) {
    params.groupBy { it.section }.forEach { (section, ps) ->
        item(key = "h-$section") { MonoLabel(section, Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp)) }
        items(ps, key = { it.key }) { ParamSlider(vm, it) }
    }
}

@Composable
private fun PanelHeader(left: String, right: String? = null, rightAccent: Boolean = false) {
    val c = Ctrl.colors
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        MonoLabel(left)
        Spacer(Modifier.weight(1f))
        if (right != null) MonoLabel(right, color = if (rightAccent) c.accentText else c.muted)
    }
}

// ---------------- Presets ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetPanel(vm: LumenViewModel) {
    val active = Presets.byName(vm.params.preset)
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Presets", "${Presets.all.size} looks")
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (active != null && active.values.isNotEmpty()) {
                LabeledSlider(
                    label = "${active.name} amount", valueText = "${(vm.params.presetAmount * 100).toInt()}",
                    value = vm.params.presetAmount, range = 0f..2f, origin = 0f, changed = vm.params.presetAmount != 1f,
                    onChange = { vm.setLiveParams(active.applyTo(vm.params, it), "Preset amount") },
                    onDone = vm::commit, onReset = { vm.edit("Preset amount") { active.applyTo(it, 1f) } },
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Presets.all.forEach { preset ->
                    val selected = vm.params.preset == preset.name || (preset.values.isEmpty() && vm.params.preset == null && vm.params.values.isEmpty())
                    CtrlChip(preset.name, selected, onClick = { vm.edit("Preset ${preset.name}") { preset.applyTo(it) } })
                }
            }
        }
    }
}

// ---------------- Edit ----------------

private val editGroups = Group.entries.filter { it != Group.GEOMETRY }

@Composable
fun EditPanel(vm: LumenViewModel, st: EditorState) {
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(editGroups) { g ->
                val changed = Param.entries.any { it.group == g && it.ui && vm.params[it] != it.default } ||
                    (g == Group.CURVE && !vm.params.curves.isIdentity())
                CtrlChip(
                    g.label + if (changed) " •" else "", st.group == g,
                    onClick = { st.group = g; st.pickWhiteBalance = false; st.pickFocus = false },
                )
            }
        }
        when (st.group) {
            Group.CURVE -> CurvePanel(vm, st)
            Group.MIXER -> MixerPanel(vm, st)
            else -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                item(key = "tools") { GroupTools(vm, st) }
                val hdrOn = vm.params[Param.HDR_ON] >= 0.5f
                val hasDn = vm.denoised != null
                paramSections(vm, sliderParams(st.group).filter {
                    (it.section != "HDR" || hdrOn) && (it != Param.AI_DENOISE || hasDn)
                })
            }
        }
    }
}

@Composable
private fun GroupTools(vm: LumenViewModel, st: EditorState) {
    val row: @Composable (@Composable () -> Unit) -> Unit = { content ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
    when (st.group) {
        Group.LIGHT -> Column {
            row {
                CtrlButton("Auto", onClick = vm::autoTone, icon = Icons.Outlined.AutoFixHigh, style = CtrlButtonStyle.Accent)
                MonoLabel("On-device · no AI service", Modifier.padding(top = 14.dp))
            }
            if (vm.openPhoto?.isVideo != true) HdrTools(vm, st) // HDR export is photo-only

        }
        Group.COLOR -> row {
            CtrlButton(
                if (st.pickWhiteBalance) "Tap the photo" else "Pick white balance",
                onClick = { st.pickWhiteBalance = !st.pickWhiteBalance }, icon = Icons.Outlined.Colorize,
                selected = st.pickWhiteBalance,
            )
        }
        Group.BLUR -> row {
            CtrlChip("Radial", vm.params[Param.BLUR_MODE] < 0.5f, onClick = { vm.edit("Blur mode") { it.with(Param.BLUR_MODE, 0f) } })
            CtrlChip("Tilt-shift", vm.params[Param.BLUR_MODE] >= 0.5f, onClick = { vm.edit("Blur mode") { it.with(Param.BLUR_MODE, 1f) } })
            CtrlChip("Set focus", st.pickFocus, onClick = { st.pickFocus = !st.pickFocus }, icon = Icons.Outlined.CenterFocusStrong)
        }
        Group.DETAIL -> if (vm.openPhoto?.isVideo != true) DenoiseTools(vm)
        else -> Unit
    }
}

/** AI Denoise: pick a model and run it once; the Amount slider then blends it in live. */
@Composable
private fun DenoiseTools(vm: LumenViewModel) {
    val c = Ctrl.colors
    val progress = vm.denoiseProgress
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MonoLabel("AI DENOISE")
        if (progress != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress }, modifier = Modifier.weight(1f).height(4.dp),
                    color = c.accent, trackColor = c.border,
                )
                MonoLabel("${(progress * 100).toInt()}%")
                CtrlButton("Cancel", onClick = vm::cancelDenoise)
            }
            MonoLabel("${vm.denoiseModel?.label ?: ""} model · on device · keep the editor open")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CtrlButton(
                    if (vm.denoised == null) "Quality" else "Redo · Quality",
                    onClick = { vm.runDenoise(com.vknxworks.lumenctrl.edit.Denoiser.Model.QUALITY) },
                    icon = Icons.Outlined.AutoFixHigh, style = CtrlButtonStyle.Accent,
                )
                CtrlButton(
                    if (vm.denoised == null) "Fast" else "Redo · Fast",
                    onClick = { vm.runDenoise(com.vknxworks.lumenctrl.edit.Denoiser.Model.FAST) },
                )
                if (vm.denoised != null) CtrlButton("Remove", onClick = vm::removeDenoise)
            }
            MonoLabel(
                if (vm.denoised == null) "NAFNet (Quality) or U-Net (Fast) · runs offline, up to 4096 px"
                else "Denoised base ready · blend it with Amount",
                color = if (vm.denoised != null) c.accentText else c.muted,
            )
        }
    }
}

/** HDR on/off, visualize, HDR view; plus what the source and screen support. */
@Composable
private fun HdrTools(vm: LumenViewModel, st: EditorState) {
    val c = Ctrl.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val on = vm.params[Param.HDR_ON] >= 0.5f
    val supported = com.vknxworks.lumenctrl.render.Hdr.supported
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CtrlChip("HDR", on, onClick = {
                vm.edit(if (on) "HDR off" else "HDR on") { it.with(Param.HDR_ON, if (on) 0f else 1f) }
                if (on) { st.vizHdr = false; st.hdrView = false }
            })
            if (on) CtrlChip("Visualize", st.vizHdr, onClick = { st.vizHdr = !st.vizHdr })
            if (on && supported) CtrlChip("HDR view", st.hdrView, onClick = { st.hdrView = !st.hdrView })
        }
        val source = vm.sourceGain?.let { "Ultra HDR source · up to %.1f×".format(it.fullRatio) }
        val screen = if (com.vknxworks.lumenctrl.render.Hdr.screenSupportsHdr(context)) "HDR screen" else "SDR screen"
        val note = when {
            !supported -> "HDR export needs Android 14"
            else -> listOfNotNull(source, screen).joinToString(" · ").ifBlank { null }
        }
        note?.let { MonoLabel(it, color = if (vm.sourceGain != null) c.accentText else c.muted) }
    }
}

@Composable
private fun MixerPanel(vm: LumenViewModel, st: EditorState) {
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(MixBand.entries) { b ->
                    val changed = (0..2).any { k -> vm.params[Param.mixer(k, b)] != 0f }
                    CtrlChip(b.label + if (changed) " •" else "", st.mixBand == b, onClick = { st.mixBand = b }, swatch = Color(b.swatch))
                }
            }
        }
        items((0..2).toList()) { k -> ParamSlider(vm, Param.mixer(k, st.mixBand)) }
    }
}

// ---------------- Curve ----------------

@Composable
private fun CurvePanel(vm: LumenViewModel, st: EditorState) {
    val c = Ctrl.colors
    val channelColors = listOf(c.text, Color(0xFFFF5A5A), Color(0xFF4CD964), Color(0xFF5A8CFF))
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("RGB", "Red", "Green", "Blue").forEachIndexed { i, name ->
                    CtrlChip(name, st.curveChannel == i, onClick = { st.curveChannel = i }, swatch = if (i == 0) null else channelColors[i])
                }
                Spacer(Modifier.weight(1f))
                CtrlButton("Reset", onClick = {
                    vm.edit("Reset curve") { it.copy(curves = it.curves.withChannel(st.curveChannel, com.vknxworks.lumenctrl.model.Curves.IDENTITY)) }
                }, style = CtrlButtonStyle.Ghost)
            }
        }
        item {
            CurveEditor(vm, st.curveChannel, channelColors[st.curveChannel], Modifier.fillMaxWidth().height(180.dp).padding(top = 8.dp))
            MonoLabel("Drag to shape · tap to add · double-tap a point to remove", Modifier.padding(top = 6.dp))
        }
        paramSections(vm, sliderParams(Group.CURVE))
    }
}

@Composable
private fun CurveEditor(vm: LumenViewModel, channel: Int, color: Color, modifier: Modifier) {
    val c = Ctrl.colors
    val params by rememberUpdatedState(vm.params)
    var dragging by remember { mutableStateOf(-1) }
    val pts = vm.params.curves.channel(channel)

    fun toPt(o: Offset, w: Float, h: Float) = CurvePoint((o.x / w).coerceIn(0f, 1f), (1f - o.y / h).coerceIn(0f, 1f))

    Surface(shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border), modifier = modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(10.dp)
                .pointerInput(channel) {
                    detectTapGestures(
                        onTap = { o ->
                            val list = params.curves.channel(channel)
                            val p = toPt(o, size.width.toFloat(), size.height.toFloat())
                            if (list.any { abs(it.x - p.x) < 0.04f } || list.size >= 12) return@detectTapGestures
                            vm.edit("Curve point") { it.copy(curves = it.curves.withChannel(channel, list + p)) }
                        },
                        onDoubleTap = { o ->
                            val list = params.curves.channel(channel)
                            val p = toPt(o, size.width.toFloat(), size.height.toFloat())
                            val i = list.indices.minByOrNull { abs(list[it].x - p.x) + abs(list[it].y - p.y) } ?: return@detectTapGestures
                            if (i == 0 || i == list.lastIndex) return@detectTapGestures
                            vm.edit("Remove curve point") { it.copy(curves = it.curves.withChannel(channel, list.filterIndexed { k, _ -> k != i })) }
                        },
                    )
                }
                .pointerInput(channel) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val list = params.curves.channel(channel)
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val i = list.indices.minByOrNull { (Offset(list[it].x * w, (1 - list[it].y) * h) - o).getDistance() }
                            dragging = if (i != null && (Offset(list[i].x * w, (1 - list[i].y) * h) - o).getDistance() < 40.dp.toPx()) i else {
                                // Start a new point where the drag began.
                                val p = toPt(o, w, h)
                                if (list.size < 12 && list.none { abs(it.x - p.x) < 0.04f }) {
                                    val next = (list + p).sortedBy { it.x }
                                    vm.setLiveParams(params.copy(curves = params.curves.withChannel(channel, next)), "Curve")
                                    next.indexOf(p)
                                } else -1
                            }
                        },
                        onDrag = { change, _ ->
                            if (dragging < 0) return@detectDragGestures
                            change.consume()
                            val list = params.curves.channel(channel).toMutableList()
                            if (dragging > list.lastIndex) return@detectDragGestures
                            val p = toPt(change.position, size.width.toFloat(), size.height.toFloat())
                            val lo = if (dragging == 0) 0f else list[dragging - 1].x + 0.02f
                            val hi = if (dragging == list.lastIndex) 1f else list[dragging + 1].x - 0.02f
                            val x = when (dragging) { 0 -> list[0].x; list.lastIndex -> list.last().x; else -> p.x.coerceIn(lo, hi) }
                            list[dragging] = CurvePoint(x, p.y)
                            vm.setLiveParams(params.copy(curves = params.curves.withChannel(channel, list)), "Curve")
                        },
                        onDragEnd = { dragging = -1; vm.commit() },
                        onDragCancel = { dragging = -1; vm.commit() },
                    )
                },
        ) {
            val w = size.width; val h = size.height
            for (k in 1..3) {
                drawLine(c.border, Offset(w * k / 4f, 0f), Offset(w * k / 4f, h), 1f)
                drawLine(c.border, Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
            }
            vm.histogram?.let { hist ->
                val maxV = hist.l.maxOrNull()?.coerceAtLeast(1)?.toFloat() ?: 1f
                val path = Path().apply {
                    moveTo(0f, h)
                    hist.l.forEachIndexed { i, v -> lineTo(i / 63f * w, h - (v / maxV).coerceAtMost(1f) * h * 0.8f) }
                    lineTo(w, h); close()
                }
                drawPath(path, c.track)
            }
            drawLine(c.dim.copy(alpha = 0.5f), Offset(0f, h), Offset(w, 0f), 1f)
            val lut = CurveLut.sampleSpline(pts)
            val path = Path().apply {
                lut.forEachIndexed { i, v -> val x = i / 255f * w; val y = h - v * h; if (i == 0) moveTo(x, y) else lineTo(x, y) }
            }
            drawPath(path, color, style = Stroke(2.5.dp.toPx()))
            pts.forEach { p ->
                drawCircle(color, 6.dp.toPx(), Offset(p.x * w, h - p.y * h))
                drawCircle(c.surface, 3.dp.toPx(), Offset(p.x * w, h - p.y * h))
            }
        }
    }
}

// ---------------- Masks ----------------

@Composable
fun MasksPanel(vm: LumenViewModel, st: EditorState) {
    val c = Ctrl.colors
    val masks = vm.params.masks
    if (st.selectedMask > masks.lastIndex) st.selectedMask = masks.lastIndex
    val sel = masks.getOrNull(st.selectedMask)
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(masks) { i, m -> CtrlChip("${i + 1} ${m.label}", st.selectedMask == i, onClick = { st.selectedMask = i }) }
            items(MaskType.entries.filter { it != MaskType.AI }) { t ->
                CtrlChip(t.label, false, icon = Icons.Outlined.Add, onClick = { st.selectedMask = vm.addMask(t) })
            }
        }
        // Offline AI selections (bundled models, run on this phone).
        LazyRow(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AiKind.entries) { k ->
                CtrlChip(k.label, false, icon = Icons.Outlined.AutoAwesome, onClick = { vm.addAiMask(k) { st.selectedMask = it } })
            }
        }
        if (sel == null) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Local adjustments", style = com.vknxworks.lumenctrl.ui.ctrl.CtrlType.title, color = c.text)
                Text(
                    "Select a subject, sky, background or people parts with on-device AI, or add a gradient, luminance or color range. Each mask has its own exposure, color and detail sliders.",
                    color = c.muted,
                )
            }
            return@Column
        }
        val i = st.selectedMask
        fun update(label: String, live: Boolean, t: (com.vknxworks.lumenctrl.model.LocalMask) -> com.vknxworks.lumenctrl.model.LocalMask) {
            val p = vm.params
            val next = p.copy(masks = p.masks.toMutableList().also { it[i] = t(it[i]) })
            if (live) vm.setLiveParams(next, label) else vm.edit(label) { next }
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CtrlChip("Invert", sel.invert, icon = Icons.Outlined.InvertColors, onClick = { update("Invert mask", false) { it.copy(invert = !it.invert) } })
                    CtrlChip(
                        "Overlay", st.showMaskOverlay,
                        icon = if (st.showMaskOverlay) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        onClick = { st.showMaskOverlay = !st.showMaskOverlay },
                    )
                    CtrlChip("Delete", false, icon = Icons.Outlined.Delete, onClick = {
                        vm.edit("Delete mask") { p -> p.copy(masks = p.masks.filterIndexed { k, _ -> k != i }) }
                        st.selectedMask = -1
                    })
                }
            }
            item { MonoLabel("Mask", Modifier.padding(start = 4.dp, top = 12.dp)) }
            when (sel.type) {
                MaskType.LUMA -> {
                    item { LabeledSlider("Range low", "${(sel.x1 * 100).toInt()}", sel.x1, 0f..1f, 0f, true, { v -> update("Mask range", true) { it.copy(x1 = v.coerceAtMost(it.x2)) } }, vm::commit, {}) }
                    item { LabeledSlider("Range high", "${(sel.x2 * 100).toInt()}", sel.x2, 0f..1f, 0f, true, { v -> update("Mask range", true) { it.copy(x2 = v.coerceAtLeast(it.x1)) } }, vm::commit, {}) }
                }
                MaskType.COLOR -> {
                    item { LabeledSlider("Hue", "${(sel.x1 * 360).toInt()}°", sel.x1, 0f..1f, 0f, true, { v -> update("Mask hue", true) { it.copy(x1 = v) } }, vm::commit, {}, trackBrush = HueBrush) }
                    item { LabeledSlider("Range", "${(sel.x2 * 100).toInt()}", sel.x2, 0.01f..0.5f, 0.01f, true, { v -> update("Mask range", true) { it.copy(x2 = v) } }, vm::commit, {}) }
                }
                else -> Unit
            }
            if (sel.type != MaskType.LINEAR) {
                item { LabeledSlider("Feather", "${(sel.feather * 100).toInt()}", sel.feather, 0f..1f, 0f, true, { v -> update("Mask feather", true) { it.copy(feather = v) } }, vm::commit, {}) }
            }
            item { MonoLabel("Adjust", Modifier.padding(start = 4.dp, top = 12.dp)) }
            items(MaskAdj.entries) { a ->
                val v = sel[a]
                LabeledSlider(
                    a.label, a.format(v), v, a.min..a.max, 0f, v != 0f,
                    onChange = { nv -> update(a.label, true) { it.with(a, nv) } },
                    onDone = vm::commit,
                    onReset = { update("Reset ${a.label}", false) { it.with(a, 0f) } },
                )
            }
        }
    }
}

// ---------------- Heal ----------------

@Composable
fun HealPanel(vm: LumenViewModel, st: EditorState) {
    val spots = vm.params.spots
    val sel = spots.getOrNull(st.selectedSpot)
    val i = st.selectedSpot
    fun update(label: String, live: Boolean, t: (com.vknxworks.lumenctrl.model.Spot) -> com.vknxworks.lumenctrl.model.Spot) {
        val p = vm.params
        val next = p.copy(spots = p.spots.toMutableList().also { it[i] = t(it[i]) })
        if (live) vm.setLiveParams(next, label) else vm.edit(label) { next }
    }
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Heal & clone", "${spots.size} spots", spots.isNotEmpty())
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
            item {
                LabeledSlider(
                    "Brush size", "${(st.healRadius * 1000).toInt()}", st.healRadius, 0.01f..0.15f, 0.01f, false,
                    onChange = { st.healRadius = it }, onDone = {}, onReset = { st.healRadius = 0.04f },
                )
                MonoLabel("Tap a spot to heal · drag circles to move source or target", Modifier.padding(start = 4.dp, top = 4.dp))
            }
            if (sel != null) {
                item {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CtrlChip("Heal", !sel.clone, onClick = { update("Heal mode", false) { it.copy(clone = false) } })
                        CtrlChip("Clone", sel.clone, onClick = { update("Clone mode", false) { it.copy(clone = true) } })
                        CtrlChip("Delete", false, icon = Icons.Outlined.Delete, onClick = {
                            vm.edit("Delete spot") { p -> p.copy(spots = p.spots.filterIndexed { k, _ -> k != i }) }
                            st.selectedSpot = -1
                        })
                    }
                }
                item { LabeledSlider("Size", "${(sel.radius * 1000).toInt()}", sel.radius, 0.005f..0.2f, 0.005f, true, { v -> update("Spot size", true) { it.copy(radius = v) } }, vm::commit, {}) }
                item { LabeledSlider("Feather", "${(sel.feather * 100).toInt()}", sel.feather, 0f..1f, 0f, true, { v -> update("Spot feather", true) { it.copy(feather = v) } }, vm::commit, {}) }
                item { LabeledSlider("Opacity", "${(sel.opacity * 100).toInt()}", sel.opacity, 0f..1f, 0f, true, { v -> update("Spot opacity", true) { it.copy(opacity = v) } }, vm::commit, {}) }
            }
            if (spots.isNotEmpty()) {
                item {
                    CtrlButton("Clear all spots", onClick = { vm.edit("Clear spots") { it.copy(spots = emptyList()) }; st.selectedSpot = -1 },
                        style = CtrlButtonStyle.Ghost, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

// ---------------- Crop ----------------

@Composable
fun CropPanel(vm: LumenViewModel) {
    val bmp = vm.preview
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(AspectLock.entries) { lock ->
                    CtrlChip(lock.label, vm.params.cropAspect == lock.key, onClick = {
                        if (bmp == null) return@CtrlChip
                        vm.edit("Aspect ${lock.label}") { p ->
                            val fr = lockRatio(lock, Geometry.frameAspect(p, bmp.width, bmp.height))
                            p.copy(cropAspect = lock.key, crop = fitCrop(p.crop, fr))
                        }
                    })
                }
            }
        }
        item {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CtrlChip("Rotate", false, icon = Icons.Outlined.Rotate90DegreesCw, onClick = {
                    vm.edit("Rotate") { p -> p.copy(quarterTurns = (p.quarterTurns + 1) % 4, crop = com.vknxworks.lumenctrl.model.CropRect.FULL) }
                })
                CtrlChip("Flip", vm.params.flipH, icon = Icons.Outlined.Flip, onClick = { vm.edit("Flip") { it.copy(flipH = !it.flipH) } })
                CtrlChip("Auto", false, icon = Icons.Outlined.Straighten, onClick = vm::autoStraighten)
                CtrlChip("Reset", false, onClick = { vm.edit("Reset geometry") { it.resetGeometry() } })
            }
        }
        paramSections(vm, sliderParams(Group.GEOMETRY))
    }
}

@Suppress("unused")
private val keepBox: @Composable () -> Unit = { Box(Modifier.size(0.dp)) { Surface(shape = RoundedCornerShape(1.dp)) {} } }
