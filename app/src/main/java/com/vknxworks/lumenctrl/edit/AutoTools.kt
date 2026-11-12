package com.vknxworks.lumenctrl.edit

import android.graphics.Bitmap
import com.vknxworks.lumenctrl.model.EditParams
import com.vknxworks.lumenctrl.model.Param
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device analysis behind Auto, the white-balance eyedropper, auto
 * straighten and heal-source picking. Plain statistics, no ML model and no
 * network: Lumen's own version of Lightroom's "edit suggestions" and upright.
 * All inputs are the upright preview bitmap (source uv space).
 */
object AutoTools {
    private fun lin(v: Float) = v.coerceIn(0f, 1f).pow(2.2f)
    private fun srgb(v: Float) = v.coerceIn(0f, 1f).pow(1f / 2.2f)

    private fun small(bmp: Bitmap, maxDim: Int): Bitmap {
        val s = maxDim / max(bmp.width, bmp.height).toFloat()
        return if (s >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
    }

    /** White balance (temperature, tint) that makes linear (r, g, b) neutral under the shader's model. */
    fun neutralize(r: Float, g: Float, b: Float): Pair<Float, Float> {
        val t = ((b - r) / (0.3f * (r + b)).coerceAtLeast(1e-4f)).coerceIn(-1f, 1f)
        val a = (1f + 0.3f * t) * r
        val i = ((g - a) / (0.15f * a + 0.2f * g).coerceAtLeast(1e-4f)).coerceIn(-1f, 1f)
        return t to i
    }

    /** Eyedropper: average a small patch at source uv and neutralize it. */
    fun neutralWhiteBalance(bmp: Bitmap, u: Float, v: Float): Pair<Float, Float> {
        val cx = (u * bmp.width).roundToInt(); val cy = (v * bmp.height).roundToInt()
        val rad = max(2, bmp.width / 200)
        var r = 0f; var g = 0f; var b = 0f; var n = 0
        for (y in cy - rad..cy + rad) for (x in cx - rad..cx + rad) {
            if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
            val c = bmp.getPixel(x, y)
            r += lin(((c shr 16) and 0xFF) / 255f); g += lin(((c shr 8) and 0xFF) / 255f); b += lin((c and 0xFF) / 255f); n++
        }
        if (n == 0) return 0f to 0f
        return neutralize(r / n, g / n, b / n)
    }

    /** Auto tone: exposure, contrast, highlights, shadows, whites, blacks, white balance, vibrance. */
    fun autoTone(bmp: Bitmap): Map<Param, Float> {
        val s = small(bmp, 256)
        val px = IntArray(s.width * s.height)
        s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        if (s !== bmp) s.recycle()
        val hist = IntArray(256)
        var rs = 0.0; var gs = 0.0; var bs = 0.0; var ns = 0
        for (c in px) {
            val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            hist[(l * 255).roundToInt().coerceIn(0, 255)]++
            if (l in 0.15f..0.85f) { rs += lin(r); gs += lin(g); bs += lin(b); ns++ }
        }
        fun pct(q: Float): Float {
            val target = q * px.size; var acc = 0
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i / 255f }
            return 1f
        }
        val p50 = pct(0.5f).coerceIn(0.02f, 0.98f)
        val exposure = (ln(lin(0.46f) / lin(p50)) / ln(2f) * 0.8f).coerceIn(-2f, 2f)
        val k = 2f.pow(exposure)
        fun after(p: Float) = srgb(lin(p) * k)
        val p005 = after(pct(0.005f)); val p10 = after(pct(0.1f))
        val p95 = after(pct(0.95f)); val p995 = after(pct(0.995f))

        val out = mutableMapOf<Param, Float>(Param.EXPOSURE to exposure)
        out[Param.WHITES] = ((0.97f - p995) * 2.5f).coerceIn(-0.6f, 0.8f)
        out[Param.BLACKS] = (-(p005 - 0.02f) * 4f).coerceIn(-0.8f, 0.4f)
        out[Param.HIGHLIGHTS] = if (p95 > 0.85f) -((p95 - 0.85f) * 4f).coerceIn(0f, 0.6f) else 0f
        out[Param.SHADOWS] = if (p10 < 0.12f) ((0.12f - p10) * 4f).coerceIn(0f, 0.5f) else 0f
        out[Param.CONTRAST] = ((0.55f - (p95 - p10)) * 0.6f).coerceIn(-0.2f, 0.3f)
        out[Param.VIBRANCE] = 0.12f
        if (ns > 100) {
            val (t, i) = neutralize((rs / ns).toFloat(), (gs / ns).toFloat(), (bs / ns).toFloat())
            out[Param.TEMPERATURE] = t * 0.5f // gray-world is a guess; apply half
            out[Param.TINT] = i * 0.5f
        }
        return out
    }

    /**
     * Auto straighten: dominant near-horizontal/vertical edge angle from a Sobel
     * pass, returned as the Straighten value (degrees) for the current orientation,
     * or null if no confident line is found.
     */
    fun autoStraighten(bmp: Bitmap, p: EditParams): Float? {
        val s = small(bmp, 480)
        val w = s.width; val h = s.height
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== bmp) s.recycle()
        val gray = FloatArray(w * h) { i ->
            val c = px[i]; (((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f
        }
        val bins = FloatArray(161) // -20..+20 degrees in 0.25 steps
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1] - gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1]
            val gy = gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1] - gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1]
            val mag = hypot(gx, gy)
            if (mag < 0.35f) continue
            val lineDeg = Math.toDegrees(atan2(gy, gx).toDouble()).toFloat() + 90f
            var a = ((lineDeg + 45f) % 90f + 90f) % 90f - 45f
            if (abs(a) > 20f) continue
            bins[((a + 20f) * 4f).roundToInt().coerceIn(0, 160)] += mag * mag
        }
        val smooth = FloatArray(161) { i -> (max(0, i - 2)..min(160, i + 2)).sumOf { bins[it].toDouble() }.toFloat() }
        val peak = smooth.indices.maxByOrNull { smooth[it] } ?: return null
        val total = bins.sum()
        if (total <= 0f || smooth[peak] / total < 0.06f) return null
        var num = 0f; var den = 0f
        for (i in max(0, peak - 4)..min(160, peak + 4)) { num += (i / 4f - 20f) * bins[i]; den += bins[i] }
        val phi = if (den > 0f) num / den else peak / 4f - 20f
        // Quarter turns keep the angle mod 90; a horizontal flip mirrors it.
        val oriented = if (p.flipH) -phi else phi
        return oriented.coerceIn(-45f, 45f)
    }

    /** Picks a clean source patch near a heal target: similar surroundings, low texture difference. */
    fun findHealSource(bmp: Bitmap, tx: Float, ty: Float, radius: Float): Pair<Float, Float> {
        val a = bmp.width / bmp.height.toFloat()
        fun stats(cx: Float, cy: Float, ringOnly: Boolean): Pair<Float, Float> {
            var sum = 0f; var sq = 0f; var n = 0
            for (k in 0 until 24) {
                val ang = k * 0.2618f
                for (rr in if (ringOnly) listOf(1.2f, 1.45f) else listOf(0.3f, 0.65f, 0.95f)) {
                    val u = cx + cos(ang) * radius * rr / a; val v = cy + sin(ang) * radius * rr
                    val x = (u * bmp.width).roundToInt(); val y = (v * bmp.height).roundToInt()
                    if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
                    val c = bmp.getPixel(x, y)
                    val l = (((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f
                    sum += l; sq += l * l; n++
                }
            }
            if (n == 0) return 0f to 0f
            val m = sum / n
            return m to sqrt(max(sq / n - m * m, 0f))
        }
        val (tm, ts) = stats(tx, ty, ringOnly = true)
        var best = (tx + 2.4f * radius / a) to ty
        var bestCost = Float.MAX_VALUE
        for (k in 0 until 16) {
            val ang = k * 0.3927f
            for (dist in listOf(2.4f, 3.2f)) {
                val sx = tx + cos(ang) * radius * dist / a; val sy = ty + sin(ang) * radius * dist
                if (sx - radius / a < 0f || sx + radius / a > 1f || sy - radius < 0f || sy + radius > 1f) continue
                val (m, sd) = stats(sx, sy, ringOnly = false)
                val cost = abs(m - tm) + 1.5f * abs(sd - ts) + 0.02f * dist
                if (cost < bestCost) { bestCost = cost; best = sx to sy }
            }
        }
        return best
    }
}
