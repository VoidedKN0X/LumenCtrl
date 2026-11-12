package com.vknxworks.lumenctrl.ui.editor

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.model.AspectLock
import com.vknxworks.lumenctrl.model.CropRect
import com.vknxworks.lumenctrl.model.EditParams
import com.vknxworks.lumenctrl.model.Group
import com.vknxworks.lumenctrl.model.MaskType
import com.vknxworks.lumenctrl.model.Param
import com.vknxworks.lumenctrl.render.DrawOptions
import com.vknxworks.lumenctrl.render.EditorRenderer
import com.vknxworks.lumenctrl.render.Geometry
import com.vknxworks.lumenctrl.render.Histogram
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import com.vknxworks.lumenctrl.ui.ctrl.SquircleSm
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Screen <-> image mapping for overlays; mirrors EditorRenderer's viewport math. */
class Mapping(
    val left: Float, val top: Float, val w: Float, val h: Float,
    private val hMat: FloatArray, private val hInv: FloatArray, val srcAspect: Float,
) {
    fun toOutput(x: Float, y: Float) = (x - left) / w to (y - top) / h
    fun toSource(x: Float, y: Float): Pair<Float, Float> = toOutput(x, y).let { (u, v) -> Geometry.apply(hMat, u, v) }
    fun toScreen(u: Float, v: Float): Offset = Geometry.apply(hInv, u, v).let { (a, b) -> Offset(left + a * w, top + b * h) }
    fun outputToScreen(u: Float, v: Float) = Offset(left + u * w, top + v * h)
}

private fun mapping(view: IntSize, p: EditParams, texW: Int, texH: Int, opt: DrawOptions): Mapping {
    val aspect = Geometry.outputAspect(p, texW, texH, opt.ignoreCrop)
    val r = EditorRenderer.fitRect(view.width, view.height, aspect)
    val w = r.w * opt.zoom
    val h = r.h * opt.zoom
    val left = r.x + (r.w - w) / 2f + opt.panX
    val top = (view.height - r.y - r.h) + (r.h - h) / 2f + opt.panY
    val m = Geometry.outputToSource(p, texW, texH, opt.ignoreCrop)
    return Mapping(left, top, w, h, m, Geometry.invert(m), texW / texH.toFloat())
}

private fun sampleLuma(bmp: Bitmap, u: Float, v: Float): Float {
    val c = bmp.getPixel((u * bmp.width).toInt().coerceIn(0, bmp.width - 1), (v * bmp.height).toInt().coerceIn(0, bmp.height - 1))
    return (((c shr 16) and 0xFF) * 0.2126f + ((c shr 8) and 0xFF) * 0.7152f + (c and 0xFF) * 0.0722f) / 255f
}

private fun sampleHue(bmp: Bitmap, u: Float, v: Float): Float {
    val c = bmp.getPixel((u * bmp.width).toInt().coerceIn(0, bmp.width - 1), (v * bmp.height).toInt().coerceIn(0, bmp.height - 1))
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c, hsv)
    return hsv[0] / 360f
}

private enum class CropHandle { TL, TR, BL, BR, L, R, T, B, MOVE }

/** Frame-space width/height ratio for an aspect lock (pixel ratio divided by frame aspect). */
internal fun lockRatio(lock: AspectLock, frameAspect: Float): Float? = when (val r = lock.ratio) {
    null -> null
    0f -> 1f
    else -> r / frameAspect
}

/** Largest crop of frame-ratio [fr] centred on the current crop's centre. */
internal fun fitCrop(c: CropRect, fr: Float?): CropRect {
    if (fr == null) return c
    var w = 1f; var h = w / fr
    if (h > 1f) { h = 1f; w = fr }
    val cx = ((c.l + c.r) / 2f).coerceIn(w / 2f, 1f - w / 2f)
    val cy = ((c.t + c.b) / 2f).coerceIn(h / 2f, 1f - h / 2f)
    return CropRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

private fun dragCrop(c: CropRect, handle: CropHandle, dx: Float, dy: Float, fr: Float?): CropRect {
    val minS = 0.05f
    if (handle == CropHandle.MOVE) {
        val l = (c.l + dx).coerceIn(0f, 1f - c.w)
        val t = (c.t + dy).coerceIn(0f, 1f - c.h)
        return CropRect(l, t, l + c.w, t + c.h)
    }
    var l = c.l; var t = c.t; var r = c.r; var b = c.b
    when (handle) {
        CropHandle.TL -> { l += dx; t += dy }
        CropHandle.TR -> { r += dx; t += dy }
        CropHandle.BL -> { l += dx; b += dy }
        CropHandle.BR -> { r += dx; b += dy }
        CropHandle.L -> l += dx
        CropHandle.R -> r += dx
        CropHandle.T -> t += dy
        CropHandle.B -> b += dy
        CropHandle.MOVE -> Unit
    }
    l = l.coerceIn(0f, c.r - minS); r = r.coerceIn(c.l + minS, 1f)
    t = t.coerceIn(0f, c.b - minS); b = b.coerceIn(c.t + minS, 1f)
    if (fr != null) {
        when (handle) {
            CropHandle.T, CropHandle.B -> {
                val w = (b - t) * fr; val cx = (c.l + c.r) / 2f
                l = cx - w / 2f; r = cx + w / 2f
            }
            CropHandle.L, CropHandle.R -> {
                val h = (r - l) / fr; val cy = (c.t + c.b) / 2f
                t = cy - h / 2f; b = cy + h / 2f
            }
            CropHandle.TL, CropHandle.TR -> t = b - (r - l) / fr
            else -> b = t + (r - l) / fr
        }
        if (l < -1e-4f || t < -1e-4f || r > 1.0001f || b > 1.0001f || r - l < minS || b - t < minS) return c
    }
    return CropRect(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}

@Composable
fun ImageArea(vm: LumenViewModel, st: EditorState, modifier: Modifier) {
    val c = Ctrl.colors
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val bmp = vm.preview
    val params = vm.params
    val cropMode = st.tool == Tool.CROP
    val zoomable = (st.tool == Tool.EDIT || st.tool == Tool.PRESETS) && !st.hdrView
    if (!zoomable && (zoom != 1f || pan != Offset.Zero)) { zoom = 1f; pan = Offset.Zero }

    val ground = c.bg
    val options = DrawOptions(
        before = st.showBefore,
        ignoreCrop = cropMode,
        showMask = if (st.tool == Tool.MASKS && st.showMaskOverlay) st.selectedMask else -1,
        groundR = ground.red, groundG = ground.green, groundB = ground.blue,
        zoom = zoom, panX = pan.x, panY = pan.y,
        vizHdr = st.vizHdr && st.tool == Tool.EDIT,
    )
    val showHistogram = st.tool == Tool.EDIT && (st.group == Group.LIGHT || st.group == Group.CURVE)
    val map = if (bmp != null && size != IntSize.Zero) mapping(size, params, bmp.width, bmp.height, options) else null
    val currentMap by rememberUpdatedState(map)
    val currentParams by rememberUpdatedState(params)

    // Rebuild the AI mask atlas only when the set/order of AI masks or their images change.
    val aiIds = params.masks.filter { it.type == MaskType.AI }.map { it.id }
    val atlas = remember(aiIds, vm.aiMasks) { com.vknxworks.lumenctrl.edit.AiAtlas.build(params.masks, vm.aiMasks) }

    // HDR view: show a rendered Ultra HDR image in an HDR-mode window (Android 14+).
    val hdrViewOn = st.hdrView && params[Param.HDR_ON] >= 0.5f && com.vknxworks.lumenctrl.render.Hdr.supported
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(hdrViewOn) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            activity?.window?.colorMode = if (hdrViewOn && android.os.Build.VERSION.SDK_INT >= 34)
                android.content.pm.ActivityInfo.COLOR_MODE_HDR else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        }
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= 26) activity?.window?.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        }
    }
    LaunchedEffect(hdrViewOn, params, size) {
        if (!hdrViewOn) { vm.clearHdrPreview(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250) // re-render after edits settle
        vm.renderHdrPreview(max(size.width, size.height).coerceIn(512, 2048))
    }

    Box(modifier.onSizeChanged { size = it }) {
        GlCanvas(bmp, params, options, if (showHistogram) { h -> vm.histogram = h } else null, atlas, vm.sourceGain, vm.denoised, Modifier.fillMaxSize())
        if (hdrViewOn) vm.hdrPreview?.let { hdr ->
            androidx.compose.foundation.Image(
                hdr.asImageBitmap(), "HDR preview",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize().background(c.bg),
            )
        }
        if (bmp == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = c.accent, strokeWidth = 2.dp)

        // Gesture + drawing layer.
        var crop0 by remember { mutableStateOf(CropRect.FULL) }
        var cropHandle by remember { mutableStateOf<CropHandle?>(null) }
        var dragIndex by remember { mutableStateOf(-1) }
        var lastSrc by remember { mutableStateOf(0f to 0f) }

        val gestures = when {
            bmp == null -> Modifier
            st.tool == Tool.EDIT && (st.pickWhiteBalance || st.pickFocus) -> Modifier.pointerInput(st.pickWhiteBalance, st.pickFocus) {
                detectTapGestures { pos ->
                    val m = currentMap ?: return@detectTapGestures
                    val (u, v) = m.toSource(pos.x, pos.y)
                    if (u !in 0f..1f || v !in 0f..1f) return@detectTapGestures
                    if (st.pickWhiteBalance) { vm.pickWhiteBalance(u, v); st.pickWhiteBalance = false }
                    else { vm.edit("Blur focus") { it.with(Param.BLUR_FOCUS_X, u).with(Param.BLUR_FOCUS_Y, v) }; st.pickFocus = false }
                }
            }
            zoomable -> Modifier
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val nz = (zoom * zoomChange).coerceIn(1f, 6f)
                        val k = nz / zoom
                        val cx = size.width / 2f; val cy = size.height / 2f
                        // Zoom around the gesture centroid, then follow the pan.
                        var np = Offset((pan.x - (centroid.x - cx)) * k + (centroid.x - cx), (pan.y - (centroid.y - cy)) * k + (centroid.y - cy)) + panChange
                        if (nz <= 1.001f) np = Offset.Zero
                        val m = currentMap
                        if (m != null) {
                            val maxX = max(0f, (m.w / zoom * nz - size.width) / 2f)
                            val maxY = max(0f, (m.h / zoom * nz - size.height) / 2f)
                            np = Offset(np.x.coerceIn(-maxX, maxX), np.y.coerceIn(-maxY, maxY))
                        }
                        zoom = nz; pan = np
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { pos ->
                        if (zoom > 1.01f) { zoom = 1f; pan = Offset.Zero }
                        else { zoom = 2.5f; pan = Offset((size.width / 2f - pos.x) * 1.5f, (size.height / 2f - pos.y) * 1.5f) }
                    })
                }
            st.tool == Tool.CROP -> Modifier.pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val m = currentMap ?: return@detectDragGestures
                        val cr = currentParams.crop
                        val tl = m.outputToScreen(cr.l, cr.t); val br = m.outputToScreen(cr.r, cr.b)
                        val tol = 36.dp.toPx()
                        val nearL = abs(pos.x - tl.x) < tol; val nearR = abs(pos.x - br.x) < tol
                        val nearT = abs(pos.y - tl.y) < tol; val nearB = abs(pos.y - br.y) < tol
                        val inX = pos.x in tl.x..br.x; val inY = pos.y in tl.y..br.y
                        cropHandle = when {
                            nearL && nearT -> CropHandle.TL; nearR && nearT -> CropHandle.TR
                            nearL && nearB -> CropHandle.BL; nearR && nearB -> CropHandle.BR
                            nearL && inY -> CropHandle.L; nearR && inY -> CropHandle.R
                            nearT && inX -> CropHandle.T; nearB && inX -> CropHandle.B
                            inX && inY -> CropHandle.MOVE
                            else -> null
                        }
                        crop0 = cr
                    },
                    onDrag = { change, drag ->
                        val m = currentMap ?: return@detectDragGestures
                        val handle = cropHandle ?: return@detectDragGestures
                        change.consume()
                        val p = currentParams
                        val fr = lockRatio(AspectLock.of(p.cropAspect), Geometry.frameAspect(p, bmp.width, bmp.height))
                        val next = dragCrop(p.crop, handle, drag.x / m.w, drag.y / m.h, fr)
                        vm.setLiveParams(p.copy(crop = next), "Crop")
                    },
                    onDragEnd = { cropHandle = null; vm.commit() },
                    onDragCancel = { cropHandle = null; vm.commit() },
                )
            }
            st.tool == Tool.MASKS -> Modifier
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val m = currentMap ?: return@detectTapGestures
                        val i = st.selectedMask
                        val mask = currentParams.masks.getOrNull(i) ?: return@detectTapGestures
                        val (u, v) = m.toSource(pos.x, pos.y)
                        if (u !in 0f..1f || v !in 0f..1f) return@detectTapGestures
                        val updated = when (mask.type) {
                            MaskType.LUMA -> sampleLuma(bmp, u, v).let { l -> mask.copy(x1 = (l - 0.15f).coerceAtLeast(0f), x2 = (l + 0.15f).coerceAtMost(1f)) }
                            MaskType.COLOR -> mask.copy(x1 = sampleHue(bmp, u, v))
                            MaskType.RADIAL -> mask.copy(x1 = u, y1 = v)
                            MaskType.LINEAR, MaskType.AI -> return@detectTapGestures
                        }
                        vm.edit("Mask") { it.copy(masks = it.masks.toMutableList().also { l -> l[i] = updated }) }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val m = currentMap ?: return@detectDragGestures
                            val mask = currentParams.masks.getOrNull(st.selectedMask) ?: return@detectDragGestures
                            val handles = maskHandles(mask, m)
                            dragIndex = handles.indices.minByOrNull { (handles[it] - pos).getDistance() }
                                ?.takeIf { (handles[it] - pos).getDistance() < 40.dp.toPx() } ?: -1
                            lastSrc = m.toSource(pos.x, pos.y)
                        },
                        onDrag = { change, _ ->
                            val m = currentMap ?: return@detectDragGestures
                            if (dragIndex < 0) return@detectDragGestures
                            change.consume()
                            val i = st.selectedMask
                            val p = currentParams
                            val mask = p.masks.getOrNull(i) ?: return@detectDragGestures
                            val (u, v) = m.toSource(change.position.x, change.position.y)
                            val du = u - lastSrc.first; val dv = v - lastSrc.second
                            lastSrc = u to v
                            val a = m.srcAspect
                            val next = when (mask.type) {
                                MaskType.LINEAR -> when (dragIndex) {
                                    0 -> mask.copy(x1 = u, y1 = v)
                                    1 -> mask.copy(x2 = u, y2 = v)
                                    else -> mask.copy(x1 = mask.x1 + du, y1 = mask.y1 + dv, x2 = mask.x2 + du, y2 = mask.y2 + dv)
                                }
                                MaskType.RADIAL -> when (dragIndex) {
                                    0 -> mask.copy(x1 = u, y1 = v)
                                    1 -> mask.copy(x2 = (abs(u - mask.x1) * a).coerceAtLeast(0.01f))
                                    else -> mask.copy(y2 = abs(v - mask.y1).coerceAtLeast(0.01f))
                                }
                                else -> mask
                            }
                            vm.setLiveParams(p.copy(masks = p.masks.toMutableList().also { it[i] = next }), "Move mask")
                        },
                        onDragEnd = { if (dragIndex >= 0) vm.commit(); dragIndex = -1 },
                        onDragCancel = { if (dragIndex >= 0) vm.commit(); dragIndex = -1 },
                    )
                }
            st.tool == Tool.HEAL -> Modifier
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val m = currentMap ?: return@detectTapGestures
                        val spots = currentParams.spots
                        val hit = spots.indices.firstOrNull { i ->
                            val s = spots[i]
                            val ctr = m.toScreen(s.tx, s.ty)
                            (ctr - pos).getDistance() < (m.toScreen(s.tx, s.ty + s.radius) - ctr).getDistance() + 12.dp.toPx()
                        }
                        if (hit != null) { st.selectedSpot = hit; return@detectTapGestures }
                        val (u, v) = m.toSource(pos.x, pos.y)
                        if (u !in 0f..1f || v !in 0f..1f) return@detectTapGestures
                        st.selectedSpot = spots.size
                        vm.addSpot(u, v, st.healRadius)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val m = currentMap ?: return@detectDragGestures
                            val spots = currentParams.spots
                            // Encode target handles as 2i, source handles as 2i+1.
                            var best = -1; var bestD = 40.dp.toPx()
                            spots.forEachIndexed { i, s ->
                                val dt = (m.toScreen(s.tx, s.ty) - pos).getDistance()
                                val ds = (m.toScreen(s.sx, s.sy) - pos).getDistance()
                                if (dt < bestD) { bestD = dt; best = 2 * i }
                                if (ds < bestD) { bestD = ds; best = 2 * i + 1 }
                            }
                            dragIndex = best
                            if (best >= 0) st.selectedSpot = best / 2
                        },
                        onDrag = { change, _ ->
                            val m = currentMap ?: return@detectDragGestures
                            if (dragIndex < 0) return@detectDragGestures
                            change.consume()
                            val p = currentParams
                            val i = dragIndex / 2
                            val s = p.spots.getOrNull(i) ?: return@detectDragGestures
                            val (u, v) = m.toSource(change.position.x, change.position.y)
                            val next = if (dragIndex % 2 == 0) s.copy(tx = u, ty = v) else s.copy(sx = u, sy = v)
                            vm.setLiveParams(p.copy(spots = p.spots.toMutableList().also { it[i] = next }), "Move spot")
                        },
                        onDragEnd = { if (dragIndex >= 0) vm.commit(); dragIndex = -1 },
                        onDragCancel = { if (dragIndex >= 0) vm.commit(); dragIndex = -1 },
                    )
                }
            else -> Modifier
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            val m = map ?: return@Canvas
            when (st.tool) {
                Tool.CROP -> drawCrop(m, params.crop, c.accent)
                Tool.MASKS -> params.masks.getOrNull(st.selectedMask)?.let { mask ->
                    val handles = maskHandles(mask, m)
                    if (mask.type == MaskType.LINEAR) {
                        drawLine(Color.White, handles[0], handles[1], 2.dp.toPx())
                    } else if (mask.type == MaskType.RADIAL) {
                        val path = Path()
                        for (k in 0..48) {
                            val ang = k / 48f * 6.2832f
                            val pnt = m.toScreen(mask.x1 + kotlin.math.cos(ang) * mask.x2 / m.srcAspect, mask.y1 + kotlin.math.sin(ang) * mask.y2)
                            if (k == 0) path.moveTo(pnt.x, pnt.y) else path.lineTo(pnt.x, pnt.y)
                        }
                        drawPath(path, Color.White, style = Stroke(2.dp.toPx()))
                    }
                    handles.forEachIndexed { i, h -> drawHandle(h, if (i == 0) c.accent else Color.White) }
                }
                Tool.HEAL -> params.spots.forEachIndexed { i, s ->
                    val t = m.toScreen(s.tx, s.ty); val src = m.toScreen(s.sx, s.sy)
                    val r = (m.toScreen(s.tx, s.ty + s.radius) - t).getDistance()
                    val sel = i == st.selectedSpot
                    val col = if (sel) c.accent else Color.White.copy(alpha = 0.75f)
                    drawLine(col.copy(alpha = 0.6f), t, src, 1.5.dp.toPx())
                    drawCircle(col, r, t, style = Stroke(2.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.7f), r, src, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                }
                Tool.EDIT -> if (st.group == Group.BLUR && (params[Param.BLUR_AMOUNT] > 0f || st.pickFocus)) {
                    val f = m.toScreen(params[Param.BLUR_FOCUS_X], params[Param.BLUR_FOCUS_Y])
                    drawHandle(f, c.accent)
                    if (params[Param.BLUR_MODE] > 0.5f) {
                        val dy = (m.toScreen(params[Param.BLUR_FOCUS_X], params[Param.BLUR_FOCUS_Y] + params[Param.BLUR_FOCUS_SIZE] * 0.6f) - f).getDistance()
                        listOf(-dy, dy).forEach { o -> drawLine(Color.White.copy(alpha = 0.7f), Offset(0f, f.y + o), Offset(this.size.width, f.y + o), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))) }
                    } else {
                        val r = (m.toScreen(params[Param.BLUR_FOCUS_X], params[Param.BLUR_FOCUS_Y] + params[Param.BLUR_FOCUS_SIZE] * 0.6f) - f).getDistance()
                        drawCircle(Color.White.copy(alpha = 0.7f), r, f, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
                    }
                }
                else -> Unit
            }
        }

        if (showHistogram) vm.histogram?.let { HistogramView(it, Modifier.align(Alignment.TopStart).padding(12.dp)) }

        val hint = when {
            st.pickWhiteBalance -> "Tap something neutral gray"
            st.pickFocus -> "Tap the point to keep sharp"
            st.tool == Tool.HEAL && params.spots.isEmpty() -> "Tap a blemish to heal it"
            st.tool == Tool.MASKS && params.masks.getOrNull(st.selectedMask)?.type == MaskType.LUMA -> "Tap to pick a brightness range"
            st.tool == Tool.MASKS && params.masks.getOrNull(st.selectedMask)?.type == MaskType.COLOR -> "Tap to pick a color"
            st.showBefore -> "Before · original"
            else -> null
        }
        hint?.let {
            MonoLabel(
                it, color = c.onAccent,
                modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                    .background(c.accent, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Press and hold to see the original.
        Surface(
            shape = SquircleSm, color = c.surface, border = BorderStroke(1.dp, c.borderStrong),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .size(44.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = "Hold to compare with original"
                    onClick { false }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        st.showBefore = true
                        tryAwaitRelease()
                        st.showBefore = false
                    })
                },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Compare, null, tint = c.text, modifier = Modifier.size(20.dp)) }
        }
    }
}

private fun maskHandles(mask: com.vknxworks.lumenctrl.model.LocalMask, m: Mapping): List<Offset> = when (mask.type) {
    MaskType.LINEAR -> {
        val a = m.toScreen(mask.x1, mask.y1); val b = m.toScreen(mask.x2, mask.y2)
        listOf(a, b, (a + b) / 2f)
    }
    MaskType.RADIAL -> listOf(
        m.toScreen(mask.x1, mask.y1),
        m.toScreen(mask.x1 + mask.x2 / m.srcAspect, mask.y1),
        m.toScreen(mask.x1, mask.y1 + mask.y2),
    )
    else -> emptyList()
}

private fun DrawScope.drawHandle(p: Offset, fill: Color) {
    drawCircle(Color.Black.copy(alpha = 0.5f), 11.dp.toPx(), p)
    drawCircle(fill, 8.dp.toPx(), p)
    drawCircle(Color.White, 8.dp.toPx(), p, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.drawCrop(m: Mapping, cr: CropRect, accent: Color) {
    val tl = m.outputToScreen(cr.l, cr.t); val br = m.outputToScreen(cr.r, cr.b)
    val full0 = m.outputToScreen(0f, 0f); val full1 = m.outputToScreen(1f, 1f)
    val shade = Color.Black.copy(alpha = 0.55f)
    drawRect(shade, full0, Size(full1.x - full0.x, tl.y - full0.y))
    drawRect(shade, Offset(full0.x, br.y), Size(full1.x - full0.x, full1.y - br.y))
    drawRect(shade, Offset(full0.x, tl.y), Size(tl.x - full0.x, br.y - tl.y))
    drawRect(shade, Offset(br.x, tl.y), Size(full1.x - br.x, br.y - tl.y))
    val w = br.x - tl.x; val h = br.y - tl.y
    for (k in 1..2) {
        drawLine(Color.White.copy(alpha = 0.4f), Offset(tl.x + w * k / 3f, tl.y), Offset(tl.x + w * k / 3f, br.y), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.4f), Offset(tl.x, tl.y + h * k / 3f), Offset(br.x, tl.y + h * k / 3f), 1.dp.toPx())
    }
    drawRect(Color.White, tl, Size(w, h), style = Stroke(1.5.dp.toPx()))
    val len = min(24.dp.toPx(), min(w, h) / 3f)
    val sw = 4.dp.toPx()
    listOf(tl to Offset(1f, 1f), Offset(br.x, tl.y) to Offset(-1f, 1f), Offset(tl.x, br.y) to Offset(1f, -1f), br to Offset(-1f, -1f)).forEach { (p, d) ->
        drawLine(accent, p, Offset(p.x + d.x * len, p.y), sw)
        drawLine(accent, p, Offset(p.x, p.y + d.y * len), sw)
    }
}

@Composable
private fun HistogramView(h: Histogram, modifier: Modifier) {
    val c = Ctrl.colors
    Surface(shape = RoundedCornerShape(12.dp), color = c.surface.copy(alpha = 0.85f), border = BorderStroke(1.dp, c.border), modifier = modifier.size(136.dp, 64.dp)) {
        Canvas(Modifier.padding(6.dp)) {
            val maxV = (h.r.indices.maxOf { maxOf(h.r[it], h.g[it], h.b[it]) }).coerceAtLeast(1).toFloat()
            fun channel(bins: IntArray, col: Color) {
                val path = Path().apply {
                    moveTo(0f, size.height)
                    bins.forEachIndexed { i, v -> lineTo(i / 63f * size.width, size.height - (v / maxV).coerceAtMost(1f) * size.height) }
                    lineTo(size.width, size.height); close()
                }
                drawPath(path, col)
            }
            channel(h.r, Color(0x88FF4D4D)); channel(h.g, Color(0x884DFF6A)); channel(h.b, Color(0x884D7CFF))
        }
    }
}

/** Hosts the GLSurfaceView. Compose state is forwarded to the GL thread. */
@Composable
private fun GlCanvas(
    bitmap: Bitmap?, params: EditParams, options: DrawOptions, onHistogram: ((Histogram) -> Unit)?,
    atlas: Bitmap?, sourceGain: com.vknxworks.lumenctrl.render.SourceGain?, denoised: Bitmap?, modifier: Modifier,
) {
    val renderer = remember { EditorRenderer() }
    var view by remember { mutableStateOf<GLSurfaceView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { Handler(Looper.getMainLooper()) }
    val histCb by rememberUpdatedState(onHistogram)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }.also { view = it }
        },
        update = { v ->
            val wantHist = onHistogram != null
            val histChanged = wantHist != (renderer.onHistogram != null)
            if (histChanged) renderer.onHistogram = if (wantHist) { h -> main.post { histCb?.invoke(h) } } else null
            // Only redraw when the edit or view actually changed (the histogram callback
            // recomposes this view, and must not trigger another frame).
            if (renderer.params != params || renderer.options != options || histChanged) {
                renderer.params = params
                renderer.options = options
                v.requestRender()
            }
        },
    )

    LaunchedEffect(bitmap, view) {
        if (bitmap != null) {
            renderer.setImage(bitmap)
            view?.requestRender()
        }
    }
    LaunchedEffect(atlas, view) {
        renderer.setAiAtlas(atlas)
        view?.requestRender()
    }
    LaunchedEffect(sourceGain, view) {
        renderer.setSourceGain(sourceGain)
        view?.requestRender()
    }
    LaunchedEffect(denoised, view) {
        renderer.setDenoised(denoised)
        view?.requestRender()
    }

    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> view?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Suppress("unused")
private fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)
