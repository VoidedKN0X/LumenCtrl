package com.vknxworks.lumenctrl.edit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.vknxworks.lumenctrl.model.Removal
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Object removal on the phone, with no model and nothing downloaded: you brush over something, [compute] fills that
 * area from the rest of the picture with [Inpaint] and returns a small patch; [apply] draws the patches over the
 * source picture wherever LumenCtrl renders the photo (editor, export, thumbnails, AI Denoise).
 */
object Removals {
    /** The fill is worked out at up to this many pixels across the area around the brush. */
    private const val WORK_MAX = 1024

    fun fileFor(original: File, id: String) = File(original.parentFile, "${original.nameWithoutExtension}.rm-$id.png")

    /**
     * Returns [bmp] with the patches of [list] drawn in. Without removals it is [bmp] itself. Otherwise it is a new
     * bitmap, and [bmp] is recycled when [recycleInput]. Patches whose file is missing are skipped.
     */
    fun apply(bmp: Bitmap, original: File, list: List<Removal>, recycleInput: Boolean): Bitmap {
        if (list.isEmpty()) return bmp
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        if (recycleInput) bmp.recycle()
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val w = out.width.toFloat(); val h = out.height.toFloat()
        for (r in list) {
            val f = fileFor(original, r.id)
            if (!f.exists()) continue
            val patch = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: continue
            canvas.drawBitmap(patch, null, RectF(r.l * w, r.t * h, r.r * w, r.b * h), paint)
            patch.recycle()
        }
        return out
    }

    class NothingToRemove : Exception()

    /**
     * Fills what the [strokes] cover in [picture] (the source picture as the editor shows it, earlier removals
     * included). Strokes are lists of x, y pairs in source uv; [radius] is the brush radius in picture heights.
     * Returns the new removal and its patch (not yet saved).
     */
    fun compute(picture: Bitmap, strokes: List<List<Float>>, radius: Float, isCancelled: () -> Boolean): Pair<Removal, Bitmap> {
        val pw = picture.width; val ph = picture.height
        val rPx = radius * ph
        val pts = strokes.filter { it.size >= 2 }
        if (pts.isEmpty()) throw NothingToRemove()
        // Brushed area in picture pixels.
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        pts.forEach { s ->
            var i = 0
            while (i + 1 < s.size) { val x = s[i] * pw; val y = s[i + 1] * ph; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y); i += 2 }
        }
        x0 -= rPx; y0 -= rPx; x1 += rPx; y1 += rPx
        if (x1 < 0 || y1 < 0 || x0 >= pw || y0 >= ph) throw NothingToRemove()
        // The area the fill may copy from: generously around the brushed part.
        val margin = max(max(x1 - x0, y1 - y0) * 0.75f, 48f)
        val rx0 = (x0 - margin).toInt().coerceIn(0, pw - 1); val ry0 = (y0 - margin).toInt().coerceIn(0, ph - 1)
        val rx1 = (x1 + margin).roundToInt().coerceIn(rx0 + 1, pw); val ry1 = (y1 + margin).roundToInt().coerceIn(ry0 + 1, ph)
        val rw = rx1 - rx0; val rh = ry1 - ry0
        val s = min(1f, WORK_MAX / max(rw, rh).toFloat())
        val ww = (rw * s).roundToInt().coerceAtLeast(8); val wh = (rh * s).roundToInt().coerceAtLeast(8)

        val region = Bitmap.createBitmap(picture, rx0, ry0, rw, rh)
        val work = Bitmap.createScaledBitmap(region, ww, wh, true)
        if (work !== region) region.recycle()
        val px = IntArray(ww * wh)
        work.getPixels(px, 0, ww, 0, 0, ww, wh)
        work.recycle()

        // The hole: the brush strokes, a little wider so the object's soft edge goes too.
        val maskBmp = Bitmap.createBitmap(ww, wh, Bitmap.Config.ARGB_8888)
        val mc = Canvas(maskBmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            strokeWidth = 2f * rPx * s + 4f; color = -0x1
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = -0x1 }
        pts.forEach { st ->
            fun wx(i: Int) = (st[i] * pw - rx0) * s
            fun wy(i: Int) = (st[i + 1] * ph - ry0) * s
            if (st.size == 2) { mc.drawCircle(wx(0), wy(0), rPx * s + 2f, dot); return@forEach }
            val path = Path().apply { moveTo(wx(0), wy(0)); var i = 2; while (i + 1 < st.size) { lineTo(wx(i), wy(i)); i += 2 } }
            mc.drawPath(path, stroke)
        }
        val mpx = IntArray(ww * wh)
        maskBmp.getPixels(mpx, 0, ww, 0, 0, ww, wh)
        maskBmp.recycle()
        val hole = BooleanArray(ww * wh) { ((mpx[it] ushr 24) and 0xFF) > 20 }
        // Nothing to copy from if the hole is (nearly) everything.
        if (hole.count { it } > ww * wh * 0.85) throw NothingToRemove()
        if (hole.none { it }) throw NothingToRemove()

        val filled = Inpaint.fill(px, ww, wh, hole, isCancelled = isCancelled)

        // The patch: the hole's bounding box plus a few pixels, opaque over the hole and fading out around it.
        val feather = 3
        var hx0 = ww; var hy0 = wh; var hx1 = -1; var hy1 = -1
        for (y in 0 until wh) for (x in 0 until ww) if (hole[y * ww + x]) { hx0 = min(hx0, x); hx1 = max(hx1, x); hy0 = min(hy0, y); hy1 = max(hy1, y) }
        hx0 = max(0, hx0 - feather); hy0 = max(0, hy0 - feather); hx1 = min(ww - 1, hx1 + feather); hy1 = min(wh - 1, hy1 + feather)
        val qw = hx1 - hx0 + 1; val qh = hy1 - hy0 + 1
        val alpha = FloatArray(qw * qh)
        for (y in 0 until qh) for (x in 0 until qw) {
            // Fraction of hole pixels nearby: 1 well inside, falling off over [feather] pixels outside.
            var n = 0; var k = 0
            for (dy in -feather..feather) for (dx in -feather..feather) {
                val xx = hx0 + x + dx; val yy = hy0 + y + dy
                if (xx < 0 || yy < 0 || xx >= ww || yy >= wh) continue
                k++; if (hole[yy * ww + xx]) n++
            }
            val inHole = hole[(hy0 + y) * ww + hx0 + x]
            alpha[y * qw + x] = if (inHole) 1f else (n.toFloat() / k * 2f).coerceIn(0f, 1f)
        }
        val out = IntArray(qw * qh)
        for (y in 0 until qh) for (x in 0 until qw) {
            val c = filled[(hy0 + y) * ww + hx0 + x]
            val a = (alpha[y * qw + x] * 255f).roundToInt()
            out[y * qw + x] = (a shl 24) or (c and 0xFFFFFF)
        }
        val patch = Bitmap.createBitmap(out, qw, qh, Bitmap.Config.ARGB_8888)
        val removal = Removal(
            UUID.randomUUID().toString().replace("-", "").take(8),
            l = (rx0 + hx0 / s) / pw, t = (ry0 + hy0 / s) / ph,
            r = (rx0 + (hx1 + 1) / s) / pw, b = (ry0 + (hy1 + 1) / s) / ph,
        )
        return removal to patch
    }

    /** Saves a patch as PNG next to the photo's original. */
    fun save(original: File, id: String, patch: Bitmap) {
        val f = fileFor(original, id)
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { patch.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
