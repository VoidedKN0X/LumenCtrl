package com.vknxworks.lumenctrl.render

import com.vknxworks.lumenctrl.model.CropRect
import com.vknxworks.lumenctrl.model.EditParams
import com.vknxworks.lumenctrl.model.Param
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * All framing as one 3x3 homography H (row-major) mapping output uv -> source uv.
 * Steps, output to source: crop -> centre (aspect-correct units) -> offset ->
 * scale (with auto-fill for straighten) -> aspect -> rotate -> perspective ->
 * uncentre -> flip -> quarter turns. The shader samples at H * (u, v, 1), and
 * the UI uses the inverse to draw crop handles, masks and heal spots.
 */
object Geometry {
    fun orientedSize(w: Int, h: Int, quarterTurns: Int): Pair<Int, Int> =
        if (quarterTurns % 2 == 1) h to w else w to h

    /** Width / height of the oriented, uncropped frame. */
    fun frameAspect(p: EditParams, w: Int, h: Int): Float {
        val (ow, oh) = orientedSize(w, h, p.quarterTurns)
        return ow.toFloat() / oh
    }

    fun outputAspect(p: EditParams, w: Int, h: Int, ignoreCrop: Boolean): Float {
        val c = if (ignoreCrop) CropRect.FULL else p.crop
        return frameAspect(p, w, h) * c.w / c.h
    }

    fun outputToSource(p: EditParams, w: Int, h: Int, ignoreCrop: Boolean): FloatArray {
        val a = frameAspect(p, w, h)
        val c = if (ignoreCrop) CropRect.FULL else p.crop
        val crop = floatArrayOf(c.w, 0f, c.l, 0f, c.h, c.t, 0f, 0f, 1f)
        val centre = floatArrayOf(a, 0f, -0.5f * a, 0f, 1f, -0.5f, 0f, 0f, 1f)
        val off = floatArrayOf(1f, 0f, -p[Param.OFFSET_X] * 0.5f * a, 0f, 1f, -p[Param.OFFSET_Y] * 0.5f, 0f, 0f, 1f)
        val th = Math.toRadians(p[Param.STRAIGHTEN].toDouble())
        val cs = cos(th).toFloat()
        val sn = sin(th).toFloat()
        val fill = abs(cs) + abs(sn) * max(a, 1f / a)
        val s = 1f / (p[Param.SCALE] * fill)
        val scale = floatArrayOf(s, 0f, 0f, 0f, s, 0f, 0f, 0f, 1f)
        val e = exp(0.3f * p[Param.ASPECT])
        val aspect = floatArrayOf(1f / e, 0f, 0f, 0f, e, 0f, 0f, 0f, 1f)
        val rot = floatArrayOf(cs, -sn, 0f, sn, cs, 0f, 0f, 0f, 1f)
        val persp = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, p[Param.HORIZONTAL] * 0.5f, p[Param.VERTICAL] * 0.5f, 1f)
        val uncentre = floatArrayOf(1f / a, 0f, 0.5f, 0f, 1f, 0.5f, 0f, 0f, 1f)
        val flip = if (p.flipH) floatArrayOf(-1f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 1f) else IDENTITY
        val quarter = when (p.quarterTurns.mod(4)) {
            1 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f, 1f)
            2 -> floatArrayOf(-1f, 0f, 1f, 0f, -1f, 1f, 0f, 0f, 1f)
            3 -> floatArrayOf(0f, -1f, 1f, 1f, 0f, 0f, 0f, 0f, 1f)
            else -> IDENTITY
        }
        return listOf(quarter, flip, uncentre, persp, rot, aspect, scale, off, centre, crop).reduce(::mul)
    }

    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        }
        return r
    }

    /** Applies m to (x, y, 1) and divides by w. */
    fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val X = m[0] * x + m[1] * y + m[2]
        val Y = m[3] * x + m[4] * y + m[5]
        val W = m[6] * x + m[7] * y + m[8]
        return X / W to Y / W
    }

    fun invert(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        val inv = 1f / det
        return floatArrayOf(
            A * inv, -(b * i - c * h) * inv, (b * f - c * e) * inv,
            B * inv, (a * i - c * g) * inv, -(a * f - c * d) * inv,
            C * inv, -(a * h - b * g) * inv, (a * e - b * d) * inv,
        )
    }
}
