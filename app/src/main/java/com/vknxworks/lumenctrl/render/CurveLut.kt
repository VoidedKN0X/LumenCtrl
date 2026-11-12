package com.vknxworks.lumenctrl.render

import com.vknxworks.lumenctrl.model.CurvePoint
import com.vknxworks.lumenctrl.model.Curves
import com.vknxworks.lumenctrl.model.EditParams
import com.vknxworks.lumenctrl.model.Param
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bakes the point curves and the parametric curve into a 256x1 RGBA lookup
 * texture: R = master (with parametric), G/B/A = red/green/blue channels.
 */
object CurveLut {
    private val PARAMETRIC = listOf(Param.CURVE_SHADOWS, Param.CURVE_DARKS, Param.CURVE_LIGHTS, Param.CURVE_HIGHLIGHTS)
    private val CENTRES = floatArrayOf(0.125f, 0.375f, 0.625f, 0.875f)

    fun isActive(p: EditParams) = !p.curves.isIdentity() || PARAMETRIC.any { p[it] != 0f }

    fun signature(p: EditParams): Any = p.curves to PARAMETRIC.map { p[it] }

    fun build(p: EditParams): ByteArray {
        val master = sampleSpline(p.curves.master)
        val red = sampleSpline(p.curves.red)
        val green = sampleSpline(p.curves.green)
        val blue = sampleSpline(p.curves.blue)
        val amounts = PARAMETRIC.map { p[it] }
        val out = ByteArray(256 * 4)
        var last = 0f
        for (i in 0 until 256) {
            val x = i / 255f
            var y = x
            for (k in 0..3) {
                // Smooth bump per region; neighbouring regions overlap.
                val t = 1f - min(abs(x - CENTRES[k]) / 0.3f, 1f)
                y += amounts[k] * 0.18f * t * t * (3f - 2f * t) * (x * (1f - x) * 4f).pow(0.5f)
            }
            y = max(y.coerceIn(0f, 1f), last) // keep monotonic
            last = y
            val m = master[(y * 255f).roundToInt().coerceIn(0, 255)]
            out[i * 4] = toByte(m)
            out[i * 4 + 1] = toByte(red[i])
            out[i * 4 + 2] = toByte(green[i])
            out[i * 4 + 3] = toByte(blue[i])
        }
        return out
    }

    private fun toByte(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()

    /** Monotone cubic (Fritsch-Carlson) through the points, sampled at 256 steps. */
    fun sampleSpline(pts0: List<CurvePoint>): FloatArray {
        val pts = pts0.sortedBy { it.x }.distinctBy { it.x }
        val out = FloatArray(256)
        if (pts.size < 2) { for (i in 0..255) out[i] = i / 255f; return out }
        val n = pts.size
        val dx = FloatArray(n - 1) { pts[it + 1].x - pts[it].x }
        val sl = FloatArray(n - 1) { (pts[it + 1].y - pts[it].y) / max(dx[it], 1e-6f) }
        val m = FloatArray(n)
        m[0] = sl[0]; m[n - 1] = sl[n - 2]
        for (i in 1 until n - 1) m[i] = if (sl[i - 1] * sl[i] <= 0f) 0f else (sl[i - 1] + sl[i]) / 2f
        for (i in 0 until n - 1) {
            if (sl[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / sl[i]; val b = m[i + 1] / sl[i]
            val s = a * a + b * b
            if (s > 9f) { val t = 3f / sqrt(s); m[i] = t * a * sl[i]; m[i + 1] = t * b * sl[i] }
        }
        var seg = 0
        for (i in 0..255) {
            val x = i / 255f
            when {
                x <= pts[0].x -> out[i] = pts[0].y
                x >= pts[n - 1].x -> out[i] = pts[n - 1].y
                else -> {
                    while (seg < n - 2 && x > pts[seg + 1].x) seg++
                    val h = dx[seg]
                    val t = (x - pts[seg].x) / h
                    val t2 = t * t; val t3 = t2 * t
                    out[i] = (2 * t3 - 3 * t2 + 1) * pts[seg].y + (t3 - 2 * t2 + t) * h * m[seg] +
                        (-2 * t3 + 3 * t2) * pts[seg + 1].y + (t3 - t2) * h * m[seg + 1]
                }
            }
            out[i] = out[i].coerceIn(0f, 1f)
        }
        return out
    }

    @Suppress("unused")
    fun identityCurves() = Curves()
}
