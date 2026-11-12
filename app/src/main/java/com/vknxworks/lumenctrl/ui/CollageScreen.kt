package com.vknxworks.lumenctrl.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlChip
import com.vknxworks.lumenctrl.ui.ctrl.CtrlIconButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlType
import com.vknxworks.lumenctrl.ui.ctrl.LabeledSlider
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Layout templates in a unit square, by photo count. */
private fun templates(n: Int): List<List<RectF>> = when (n) {
    2 -> listOf(
        listOf(RectF(0f, 0f, .5f, 1f), RectF(.5f, 0f, 1f, 1f)),
        listOf(RectF(0f, 0f, 1f, .5f), RectF(0f, .5f, 1f, 1f)),
    )
    3 -> listOf(
        listOf(RectF(0f, 0f, .6f, 1f), RectF(.6f, 0f, 1f, .5f), RectF(.6f, .5f, 1f, 1f)),
        listOf(RectF(0f, 0f, 1f, .6f), RectF(0f, .6f, .5f, 1f), RectF(.5f, .6f, 1f, 1f)),
        listOf(RectF(0f, 0f, 1f / 3, 1f), RectF(1f / 3, 0f, 2f / 3, 1f), RectF(2f / 3, 0f, 1f, 1f)),
    )
    4 -> listOf(
        listOf(RectF(0f, 0f, .5f, .5f), RectF(.5f, 0f, 1f, .5f), RectF(0f, .5f, .5f, 1f), RectF(.5f, .5f, 1f, 1f)),
        listOf(RectF(0f, 0f, .65f, 1f), RectF(.65f, 0f, 1f, 1f / 3), RectF(.65f, 1f / 3, 1f, 2f / 3), RectF(.65f, 2f / 3, 1f, 1f)),
    )
    5 -> listOf(
        listOf(RectF(0f, 0f, .5f, .5f), RectF(.5f, 0f, 1f, .5f), RectF(0f, .5f, 1f / 3, 1f), RectF(1f / 3, .5f, 2f / 3, 1f), RectF(2f / 3, .5f, 1f, 1f)),
    )
    else -> listOf(
        (0 until 6).map { i -> RectF((i % 2) * .5f, (i / 2) / 3f, (i % 2) * .5f + .5f, (i / 2 + 1) / 3f) },
        (0 until 6).map { i -> RectF((i % 3) / 3f, (i / 3) * .5f, (i % 3 + 1) / 3f, (i / 3) * .5f + .5f) },
    )
}

private val ASPECTS = listOf("1:1" to 1f, "4:5" to 0.8f, "9:16" to 9f / 16f, "16:9" to 16f / 9f)

private fun compose(images: List<Bitmap>, cells: List<RectF>, w: Int, h: Int, gap: Float, radius: Float, bg: Int): Bitmap {
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(bg)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val g = gap * max(w, h)
    images.zip(cells).forEach { (img, cell) ->
        val dst = RectF(cell.left * w + g, cell.top * h + g, cell.right * w - g, cell.bottom * h - g)
        // Centre-crop the photo into the cell.
        val scale = max(dst.width() / img.width, dst.height() / img.height)
        val sw = (dst.width() / scale).toInt(); val sh = (dst.height() / scale).toInt()
        val sx = (img.width - sw) / 2; val sy = (img.height - sh) / 2
        canvas.save()
        val r = radius * max(w, h)
        canvas.clipPath(Path().apply { addRoundRect(dst, r, r, Path.Direction.CW) })
        canvas.drawBitmap(img, Rect(sx, sy, sx + sw, sy + sh), dst, paint)
        canvas.restore()
    }
    return out
}

/** Collage maker: 2-6 selected photos with their edits, templates, spacing and background. */
@Composable
fun CollageScreen(vm: LumenViewModel) {
    val c = Ctrl.colors
    val ids = vm.showIds
    val images = remember { mutableStateListOf<Bitmap>() }
    var template by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableIntStateOf(0) }
    var gap by remember { mutableFloatStateOf(0.01f) }
    var radius by remember { mutableFloatStateOf(0.01f) }
    var bgIndex by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val bgs = listOf("Black" to android.graphics.Color.BLACK, "White" to android.graphics.Color.WHITE, "Accent" to c.accent.toArgb())
    BackHandler { vm.backToLibrary() }

    LaunchedEffect(ids) {
        images.clear()
        ids.forEach { id -> vm.renderEdited(id, 1600)?.let { images.add(it) } }
    }
    val layouts = templates(ids.size.coerceIn(2, 6))
    LaunchedEffect(images.size, template, aspect, gap, radius, bgIndex) {
        if (images.size != ids.size) return@LaunchedEffect
        val a = ASPECTS[aspect].second
        val h = 1200; val w = (h * a).toInt()
        preview = withContext(Dispatchers.Default) { compose(images, layouts[template % layouts.size], w, h, gap, radius, bgs[bgIndex].second) }
    }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", vm::backToLibrary)
            Spacer(Modifier.padding(6.dp))
            Column(Modifier.weight(1f)) {
                MonoLabel("Collage")
                Text("${ids.size} photos", style = CtrlType.title, color = c.text)
            }
            CtrlButton("Save", icon = Icons.Outlined.Download, style = CtrlButtonStyle.Accent, onClick = {
                if (images.size != ids.size) return@CtrlButton
                val a = ASPECTS[aspect].second
                val h = 3000; val w = (h * a).toInt()
                vm.saveCollage(compose(images, layouts[template % layouts.size], w, h, gap, radius, bgs[bgIndex].second))
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            val p = preview
            if (p == null) CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp)
            else Image(p.asImageBitmap(), "Collage preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoLabel("Layout")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(layouts) { i, _ -> CtrlChip("Layout ${i + 1}", template % layouts.size == i, onClick = { template = i }) }
            }
            MonoLabel("Shape")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(ASPECTS) { i, (label, _) -> CtrlChip(label, aspect == i, onClick = { aspect = i }) }
                itemsIndexed(bgs) { i, (label, _) -> CtrlChip(label, bgIndex == i, onClick = { bgIndex = i }) }
            }
            LabeledSlider("Spacing", "${(gap * 1000).toInt()}", gap, 0f..0.03f, 0f, gap > 0f, { gap = it }, {}, { gap = 0.01f })
            LabeledSlider("Corners", "${(radius * 1000).toInt()}", radius, 0f..0.05f, 0f, radius > 0f, { radius = it }, {}, { radius = 0.01f })
        }
    }
}
