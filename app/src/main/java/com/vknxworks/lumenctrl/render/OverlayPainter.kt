package com.vknxworks.lumenctrl.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.model.Overlay

/**
 * Draws text and drawing layers with Android's Canvas: the editor draws them over the live preview, and exports,
 * thumbnails and video frames draw them into the picture with the very same code, so what you see is what you get.
 */
object OverlayPainter {
    @Volatile private var typeface: Typeface? = null

    /** Loads the bundled Space Grotesk once; until then (or if it fails) the system bold face is used. */
    fun init(context: Context) {
        if (typeface == null) typeface = runCatching { ResourcesCompat.getFont(context.applicationContext, R.font.space_grotesk) }.getOrNull()
    }

    private fun face(): Typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)

    private fun textPaint(o: Overlay, h: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face()
        textSize = o.size * h
        color = o.color
        textAlign = Paint.Align.CENTER
        if (!o.box) setShadowLayer(o.size * h * 0.08f, 0f, o.size * h * 0.03f, Color.argb(140, 0, 0, 0))
    }

    /** The text's box in pixels of a [w]×[h] frame starting at ([left], [top]). */
    fun textBounds(o: Overlay, left: Float, top: Float, w: Float, h: Float): RectF {
        val p = textPaint(o, h)
        val fm = p.fontMetrics
        val tw = p.measureText(o.text)
        val cx = left + o.x * w
        val base = baseline(o, top, h, fm)
        val pad = o.size * h * 0.25f
        return RectF(cx - tw / 2f - pad, base + fm.ascent * 0.8f - pad, cx + tw / 2f + pad, base + fm.descent + pad)
    }

    /** The text is centred vertically on its point. */
    private fun baseline(o: Overlay, top: Float, h: Float, fm: Paint.FontMetrics) = top + o.y * h - (fm.ascent * 0.8f + fm.descent) / 2f

    /** Draws [layers] into the frame ([left], [top], [w], [h]) of [canvas]. */
    fun draw(canvas: Canvas, layers: List<Overlay>, left: Float, top: Float, w: Float, h: Float) {
        if (layers.isEmpty() || w <= 0f || h <= 0f) return
        canvas.save()
        canvas.clipRect(left, top, left + w, top + h)
        for (o in layers) {
            when (o.kind) {
                Overlay.Kind.DRAW -> {
                    if (o.points.size < 2) continue
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = (o.size * h).coerceAtLeast(1f)
                        color = o.color
                    }
                    val path = Path()
                    path.moveTo(left + o.points[0] * w, top + o.points[1] * h)
                    var i = 2
                    // A lone point still shows as a dot.
                    if (o.points.size == 2) path.lineTo(left + o.points[0] * w + 0.01f, top + o.points[1] * h)
                    while (i + 1 < o.points.size) {
                        path.lineTo(left + o.points[i] * w, top + o.points[i + 1] * h)
                        i += 2
                    }
                    canvas.drawPath(path, paint)
                }
                Overlay.Kind.TEXT -> {
                    if (o.text.isBlank()) continue
                    val p = textPaint(o, h)
                    if (o.box) {
                        val r = textBounds(o, left, top, w, h)
                        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            // Black box behind light text, white behind dark text.
                            color = if (luma(o.color) > 0.5f) Color.argb(170, 0, 0, 0) else Color.argb(200, 255, 255, 255)
                        }
                        canvas.drawRoundRect(r, o.size * h * 0.2f, o.size * h * 0.2f, bg)
                    }
                    canvas.drawText(o.text, left + o.x * w, baseline(o, top, h, p.fontMetrics), p)
                }
            }
        }
        canvas.restore()
    }

    private fun luma(c: Int) = (Color.red(c) * 0.2126f + Color.green(c) * 0.7152f + Color.blue(c) * 0.0722f) / 255f
}
