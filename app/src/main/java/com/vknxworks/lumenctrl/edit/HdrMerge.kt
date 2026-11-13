package com.vknxworks.lumenctrl.edit

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import com.vknxworks.lumenctrl.data.ImageLoader
import com.vknxworks.lumenctrl.render.Hdr
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Merge bracketed exposures into one Ultra HDR photo (Lumen's version of
 * Lightroom's HDR capture merge, HDRExecutionService). All on the phone's CPU.
 *  1. Decode each frame upright.
 *  2. Relative exposure from EXIF (time × ISO / f²), or estimated from mid-tones.
 *  3. Align by translation with median-threshold bitmaps (robust to exposure).
 *  4. Weighted radiance merge (mid-tones trusted, clipped pixels ignored).
 *  5. Tone-map to an SDR base; the extra brightness becomes the gain map.
 */
object HdrMerge {
    const val HEADROOM_STOPS = 3f

    class Result(val sdr: Bitmap, val gainmap: Bitmap?, val shifts: List<Pair<Int, Int>>, val exposures: List<Float>)

    private val LIN = FloatArray(256) { (it / 255f).pow(2.2f) }

    fun merge(files: List<File>, maxDim: Int = 2560): Result {
        require(files.size in 2..7) { "Select 2 to 7 exposures" }
        val frames = files.map { ImageLoader.decode(it, maxDim) }
        val w = frames[0].width; val h = frames[0].height
        val imgs = frames.map { if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true) }

        // 1-2: exposure factors.
        val exif = files.map { exposureFromExif(it) }
        val px = imgs.map { b -> IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) } }
        (frames + imgs).distinct().forEach { it.recycle() } // pixels copied; free the bitmaps early
        val k = FloatArray(imgs.size)
        val ref = imgs.indices.sortedBy { i -> meanLuma(px[i]) }[imgs.size / 2] // middle brightness = reference
        for (i in imgs.indices) {
            val e = exif[i]; val er = exif[ref]
            k[i] = if (e != null && er != null) e / er else estimateRatio(px[i], px[ref])
        }

        // 3: alignment against the reference.
        val shifts = imgs.indices.map { i -> if (i == ref) 0 to 0 else align(px[ref], px[i], w, h) }

        // 4: radiance merge into float luminance + chroma kept from a per-pixel weighted average.
        val rad = FloatArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0f; var g = 0f; var b = 0f; var ws = 0f
            var fallback = -1
            for (i in imgs.indices) {
                val sx = x + shifts[i].first; val sy = y + shifts[i].second
                if (sx !in 0 until w || sy !in 0 until h) continue
                val c = px[i][sy * w + sx]
                val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                val m = max(cr, max(cg, cb))
                // Hat weight on the brightest channel: trust mid-tones, ignore clipped/black.
                val t = m / 255f
                val wgt = if (m >= 250 || m <= 4) 0f else 1f - (2f * t - 1f).pow(12)
                if (wgt > 0f) {
                    r += wgt * LIN[cr] / k[i]; g += wgt * LIN[cg] / k[i]; b += wgt * LIN[cb] / k[i]; ws += wgt
                }
                // Fallbacks: darkest frame for blown pixels, brightest for black ones.
                if (fallback < 0 || (m >= 250 && k[i] < k[fallback]) || (m <= 4 && k[i] > k[fallback])) fallback = i
            }
            val o = (y * w + x) * 3
            if (ws > 0f) { rad[o] = r / ws; rad[o + 1] = g / ws; rad[o + 2] = b / ws }
            else if (fallback >= 0) {
                val c = px[fallback][y * w + x]
                rad[o] = LIN[(c shr 16) and 0xFF] / k[fallback]; rad[o + 1] = LIN[(c shr 8) and 0xFF] / k[fallback]; rad[o + 2] = LIN[c and 0xFF] / k[fallback]
            }
        }
        // 5: tone map. Exposure so the median sits at middle gray, then extended Reinhard.
        val lum = FloatArray(w * h) { i -> 0.2126f * rad[i * 3] + 0.7152f * rad[i * 3 + 1] + 0.0722f * rad[i * 3 + 2] }
        val sorted = lum.copyOf().also { it.sort() }
        val median = max(sorted[sorted.size / 2], 1e-5f)
        val scale = 0.18f / median
        val white = max(sorted[(sorted.size * 0.995f).toInt().coerceAtMost(sorted.size - 1)] * scale, 1f)
        val head = 2f.pow(HEADROOM_STOPS)
        val sdr = IntArray(w * h)
        val gain = IntArray(w * h)
        for (i in 0 until w * h) {
            val l = lum[i] * scale
            val ld = l * (1f + l / (white * white)) / (1f + l)
            val f = if (l > 1e-6f) ld / l else 0f
            val rr = (rad[i * 3] * scale * f).coerceIn(0f, 1f).pow(1f / 2.2f)
            val gg = (rad[i * 3 + 1] * scale * f).coerceIn(0f, 1f).pow(1f / 2.2f)
            val bb = (rad[i * 3 + 2] * scale * f).coerceIn(0f, 1f).pow(1f / 2.2f)
            sdr[i] = (0xFF shl 24) or ((rr * 255).roundToInt() shl 16) or ((gg * 255).roundToInt() shl 8) or (bb * 255).roundToInt()
            // HDR keeps the un-compressed highlight luminance, up to the headroom.
            val boost = if (ld > 1e-4f) (min(l, head) / ld).coerceIn(1f, head) else 1f
            val gv = ((ln(boost) / ln(2f)) / HEADROOM_STOPS * 255f).roundToInt().coerceIn(0, 255)
            gain[i] = (0xFF shl 24) or (gv shl 16) or (gv shl 8) or gv
        }
        val sdrBmp = Bitmap.createBitmap(sdr, w, h, Bitmap.Config.ARGB_8888)
        var gainBmp: Bitmap? = null
        if (Hdr.supported) {
            val full = Bitmap.createBitmap(gain, w, h, Bitmap.Config.ARGB_8888)
            gainBmp = Bitmap.createScaledBitmap(full, max(1, w / 2), max(1, h / 2), true)
            if (gainBmp !== full) full.recycle()
            Hdr.attachGainmap(sdrBmp, gainBmp, HEADROOM_STOPS)
        }
        return Result(sdrBmp, gainBmp, shifts, k.toList())
    }

    /** Relative brightness a frame received: exposure time × ISO / f². */
    private fun exposureFromExif(f: File): Float? = runCatching {
        val e = ExifInterface(f.path)
        val t = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        val n = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 } ?: 1.0
        val iso = e.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 } ?: 100
        if (t <= 0.0) null else (t * iso / (n * n)).toFloat()
    }.getOrNull()

    private fun meanLuma(p: IntArray): Float {
        var s = 0L
        var i = 0
        while (i < p.size) { val c = p[i]; s += ((c shr 16) and 0xFF) * 54 + ((c shr 8) and 0xFF) * 183 + (c and 0xFF) * 19; i += 7 }
        return s.toFloat() / (p.size / 7)
    }

    /** Median brightness ratio over pixels well exposed in both frames. */
    private fun estimateRatio(a: IntArray, ref: IntArray): Float {
        val ratios = ArrayList<Float>()
        var i = 0
        while (i < a.size) {
            val la = (a[i] shr 8) and 0xFF; val lr = (ref[i] shr 8) and 0xFF
            if (la in 20..235 && lr in 20..235) ratios += LIN[la] / LIN[lr]
            i += 13
        }
        if (ratios.isEmpty()) return 1f
        ratios.sort()
        return ratios[ratios.size / 2].coerceIn(1f / 256f, 256f)
    }

    /** Ward's median-threshold-bitmap alignment, translation only, coarse-to-fine. */
    private fun align(ref: IntArray, img: IntArray, w: Int, h: Int): Pair<Int, Int> {
        fun gray(p: IntArray) = IntArray(p.size) { ((p[it] shr 16 and 0xFF) * 54 + (p[it] shr 8 and 0xFF) * 183 + (p[it] and 0xFF) * 19) shr 8 }
        var g1 = gray(ref); var g2 = gray(img)
        var cw = w; var ch = h
        val levels = ArrayList<Triple<IntArray, IntArray, Pair<Int, Int>>>()
        levels += Triple(g1, g2, cw to ch)
        while (min(cw, ch) > 256 && levels.size < 6) {
            val nw = cw / 2; val nh = ch / 2
            fun down(g: IntArray) = IntArray(nw * nh) { i -> val x = (i % nw) * 2; val y = (i / nw) * 2; (g[y * cw + x] + g[y * cw + x + 1] + g[(y + 1) * cw + x] + g[(y + 1) * cw + x + 1]) / 4 }
            g1 = down(g1); g2 = down(g2); cw = nw; ch = nh
            levels += Triple(g1, g2, cw to ch)
        }
        var dx = 0; var dy = 0
        for (lv in levels.indices.reversed()) {
            val (a, b, size) = levels[lv]
            val (lw, lh) = size
            dx *= 2; dy *= 2
            val ma = a.copyOf().also { it.sort() }[a.size / 2]; val mb = b.copyOf().also { it.sort() }[b.size / 2]
            var best = Int.MAX_VALUE; var bx = dx; var by = dy
            for (oy in -1..1) for (ox in -1..1) {
                val sx = dx + ox; val sy = dy + oy
                var err = 0
                var y = 4
                while (y < lh - 4) {
                    var x = 4
                    while (x < lw - 4) {
                        val x2 = x + sx; val y2 = y + sy
                        if (x2 in 0 until lw && y2 in 0 until lh) {
                            val va = a[y * lw + x]; val vb = b[y2 * lw + x2]
                            // Exclude pixels near the median (noise) from the comparison.
                            if (abs(va - ma) > 4 && abs(vb - mb) > 4 && ((va > ma) != (vb > mb))) err++
                        }
                        x += 2
                    }
                    y += 2
                }
                if (err < best) { best = err; bx = sx; by = sy }
            }
            dx = bx; dy = by
        }
        return dx to dy
    }
}
