package com.vknxworks.lumenctrl.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * A text or drawing layer on top of the finished photo. Positions are in the cropped output frame (0..1 across and
 * down), sizes are fractions of the output height, so a layer keeps its place and size at every export size.
 *
 * TEXT: [text] centred on ([x], [y]), [size] = text height, optional [box] behind it.
 * DRAW: one stroke through [points] (x0, y0, x1, y1, …), [size] = line width.
 */
data class Overlay(
    val id: String,
    val kind: Kind,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val size: Float = 0.06f,
    /** ARGB. */
    val color: Int = WHITE,
    val text: String = "",
    val box: Boolean = false,
    val points: List<Float> = emptyList(),
) {
    enum class Kind { TEXT, DRAW }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name)
        .put("x", x.toDouble()).put("y", y.toDouble()).put("size", size.toDouble())
        .put("color", color).put("text", text).put("box", box)
        .put("points", JSONArray().apply { points.forEach { put(it.toDouble()) } })

    companion object {
        const val WHITE = -0x1 // 0xFFFFFFFF
        const val MAX_LAYERS = 100
        const val MAX_POINTS = 1000
        const val MAX_TEXT = 200

        /** Text sizes and line widths are kept within sensible bounds (fractions of the photo's height). */
        val TEXT_SIZES = 0.015f..0.4f
        val LINE_WIDTHS = 0.002f..0.08f

        /** Swatches offered in the editor. */
        val COLORS = listOf(
            WHITE, 0xFF000000.toInt(), 0xFFE5484D.toInt(), 0xFFF2D34B.toInt(),
            0xFF4CC46A.toInt(), 0xFF4F7CF0.toInt(), 0xFF9B6BF2.toInt(), 0xFFF08A3C.toInt(),
        )

        fun fromJson(o: JSONObject): Overlay? = runCatching {
            val kind = Kind.valueOf(o.getString("kind"))
            val a = o.optJSONArray("points")
            val pts = if (a == null) emptyList() else (0 until minOf(a.length(), MAX_POINTS * 2)).map { a.getDouble(it).toFloat() }
            val sizes = if (kind == Kind.TEXT) TEXT_SIZES else LINE_WIDTHS
            Overlay(
                id = o.getString("id").take(16),
                kind = kind,
                x = o.optDouble("x", 0.5).toFloat().coerceIn(-0.5f, 1.5f),
                y = o.optDouble("y", 0.5).toFloat().coerceIn(-0.5f, 1.5f),
                size = o.optDouble("size", 0.06).toFloat().coerceIn(sizes.start, sizes.endInclusive),
                color = o.optInt("color", WHITE),
                text = o.optString("text").take(MAX_TEXT),
                box = o.optBoolean("box", false),
                points = if (pts.size % 2 == 0) pts else pts.dropLast(1),
            ).takeIf { (it.kind == Kind.TEXT && it.text.isNotBlank()) || (it.kind == Kind.DRAW && it.points.size >= 2) }
        }.getOrNull()
    }
}
