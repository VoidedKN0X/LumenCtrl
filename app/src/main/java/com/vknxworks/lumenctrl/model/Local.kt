package com.vknxworks.lumenctrl.model

import org.json.JSONArray
import org.json.JSONObject

/** Crop in frame-normalized coordinates (0..1 of the straightened/transformed frame). */
data class CropRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val w get() = r - l
    val h get() = b - t
    fun isFull() = l <= 0f && t <= 0f && r >= 1f && b >= 1f

    fun toJson(): JSONObject = JSONObject().put("l", l.toDouble()).put("t", t.toDouble()).put("r", r.toDouble()).put("b", b.toDouble())

    companion object {
        val FULL = CropRect()
        fun fromJson(o: JSONObject?): CropRect = if (o == null) FULL else CropRect(
            o.optDouble("l", 0.0).toFloat(), o.optDouble("t", 0.0).toFloat(),
            o.optDouble("r", 1.0).toFloat(), o.optDouble("b", 1.0).toFloat(),
        ).let { if (it.w < 0.02f || it.h < 0.02f) FULL else it }
    }
}

/** Aspect locks for the crop tool. ratio = width / height in pixels; null = free, 0 = original. */
enum class AspectLock(val key: String, val label: String, val ratio: Float?) {
    FREE("free", "Free", null), ORIGINAL("original", "Original", 0f),
    SQUARE("1:1", "1:1", 1f), PORTRAIT("4:5", "4:5", 0.8f), CLASSIC("3:2", "3:2", 1.5f),
    WIDE("16:9", "16:9", 16f / 9f), STORY("9:16", "9:16", 9f / 16f);

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: FREE
    }
}

data class CurvePoint(val x: Float, val y: Float)

/** Point curves (Lightroom's point curve): master plus per-channel. */
data class Curves(
    val master: List<CurvePoint> = IDENTITY,
    val red: List<CurvePoint> = IDENTITY,
    val green: List<CurvePoint> = IDENTITY,
    val blue: List<CurvePoint> = IDENTITY,
) {
    fun channel(i: Int) = when (i) { 1 -> red; 2 -> green; 3 -> blue; else -> master }
    fun withChannel(i: Int, pts: List<CurvePoint>): Curves {
        val sorted = pts.sortedBy { it.x }
        return when (i) { 1 -> copy(red = sorted); 2 -> copy(green = sorted); 3 -> copy(blue = sorted); else -> copy(master = sorted) }
    }
    fun isIdentity() = listOf(master, red, green, blue).all { it == IDENTITY }

    fun toJson(): JSONObject = JSONObject().apply {
        listOf("master" to master, "red" to red, "green" to green, "blue" to blue).forEach { (k, pts) ->
            put(k, JSONArray().apply { pts.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } })
        }
    }

    companion object {
        val IDENTITY = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        private fun pts(a: JSONArray?): List<CurvePoint> {
            if (a == null || a.length() < 2) return IDENTITY
            return (0 until a.length()).map {
                val p = a.getJSONArray(it)
                CurvePoint(p.getDouble(0).toFloat().coerceIn(0f, 1f), p.getDouble(1).toFloat().coerceIn(0f, 1f))
            }.sortedBy { it.x }
        }
        fun fromJson(o: JSONObject?): Curves = if (o == null) Curves() else Curves(
            pts(o.optJSONArray("master")), pts(o.optJSONArray("red")),
            pts(o.optJSONArray("green")), pts(o.optJSONArray("blue")),
        )
    }
}

enum class MaskType(val label: String) { LINEAR("Linear"), RADIAL("Radial"), LUMA("Luminance"), COLOR("Color"), AI("AI") }

/** What an offline AI mask selects (Lightroom's Select Subject / Sky / People). */
enum class AiKind(val label: String) {
    SUBJECT("Subject"), SKY("Sky"), BACKGROUND("Background"),
    PEOPLE("People"), FACE("Face skin"), BODY("Body skin"), HAIR("Hair"), CLOTHES("Clothes"),
}

/** Adjustments a local mask can apply (Lightroom's local adjustment set, trimmed). */
enum class MaskAdj(val key: String, val label: String, val min: Float, val max: Float) {
    EXPOSURE("exposure", "Exposure", -3f, 3f), CONTRAST("contrast", "Contrast", -1f, 1f),
    HIGHLIGHTS("highlights", "Highlights", -1f, 1f), SHADOWS("shadows", "Shadows", -1f, 1f),
    TEMPERATURE("temperature", "Temperature", -1f, 1f), TINT("tint", "Tint", -1f, 1f),
    SATURATION("saturation", "Saturation", -1f, 1f), CLARITY("clarity", "Clarity", -1f, 1f),
    TEXTURE("texture", "Texture", -1f, 1f), DEHAZE("dehaze", "Dehaze", -1f, 1f);

    fun format(v: Float) = if (this == EXPOSURE) "%+.2f EV".format(v)
    else (v * 100).toInt().let { if (it > 0) "+$it" else "$it" }
}

/**
 * A local adjustment. Positions are in source-image uv (0..1), so masks stay
 * put when you crop, rotate or straighten. Field meaning per [type]:
 * LINEAR  (x1,y1) = full effect, (x2,y2) = no effect
 * RADIAL  (x1,y1) = centre, (x2,y2) = radii in image-height units
 * LUMA    x1..x2 = luminance range
 * COLOR   x1 = hue (0..1), x2 = hue width
 * AI      [ai] names what was selected; the mask image is stored beside the photo
 */
data class LocalMask(
    val id: String,
    val type: MaskType,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val feather: Float = 0.5f,
    val invert: Boolean = false,
    val adj: Map<String, Float> = emptyMap(),
    val ai: AiKind? = null,
) {
    operator fun get(a: MaskAdj): Float = adj[a.key] ?: 0f
    fun with(a: MaskAdj, v: Float) = copy(adj = adj + (a.key to v.coerceIn(a.min, a.max)))
    val label: String get() = ai?.label ?: type.label

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("ai", ai?.name ?: JSONObject.NULL)
        .put("x1", x1.toDouble()).put("y1", y1.toDouble()).put("x2", x2.toDouble()).put("y2", y2.toDouble())
        .put("feather", feather.toDouble()).put("invert", invert)
        .put("adj", JSONObject().apply { adj.forEach { (k, v) -> put(k, v.toDouble()) } })

    companion object {
        fun fromJson(o: JSONObject): LocalMask? = runCatching {
            val a = o.optJSONObject("adj")
            LocalMask(
                o.getString("id"), MaskType.valueOf(o.getString("type")),
                o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat(),
                o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat(),
                o.optDouble("feather", 0.5).toFloat(), o.optBoolean("invert", false),
                buildMap { a?.keys()?.forEach { k -> put(k, a.getDouble(k).toFloat()) } },
                if (o.isNull("ai")) null else runCatching { AiKind.valueOf(o.getString("ai")) }.getOrNull(),
            )
        }.getOrNull()
    }
}

/** A heal/clone spot: target (tx,ty) is replaced from source (sx,sy). Radius in image-height units. */
data class Spot(
    val tx: Float, val ty: Float, val sx: Float, val sy: Float,
    val radius: Float, val feather: Float = 0.5f, val opacity: Float = 1f, val clone: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tx", tx.toDouble()).put("ty", ty.toDouble()).put("sx", sx.toDouble()).put("sy", sy.toDouble())
        .put("radius", radius.toDouble()).put("feather", feather.toDouble())
        .put("opacity", opacity.toDouble()).put("clone", clone)

    companion object {
        fun fromJson(o: JSONObject): Spot? = runCatching {
            Spot(
                o.getDouble("tx").toFloat(), o.getDouble("ty").toFloat(),
                o.getDouble("sx").toFloat(), o.getDouble("sy").toFloat(),
                o.getDouble("radius").toFloat(), o.optDouble("feather", 0.5).toFloat(),
                o.optDouble("opacity", 1.0).toFloat(), o.optBoolean("clone", false),
            )
        }.getOrNull()
    }
}

/** Video-only edit state: trim (ms), mute and playback speed. */
data class VideoEdit(
    val trimStartMs: Long = 0,
    /** -1 = until the end. */
    val trimEndMs: Long = -1,
    val mute: Boolean = false,
    val speed: Float = 1f,
) {
    fun isDefault() = trimStartMs == 0L && trimEndMs < 0 && !mute && speed == 1f
    fun end(durationMs: Long) = if (trimEndMs < 0) durationMs else trimEndMs.coerceAtMost(durationMs)

    fun toJson(): JSONObject = JSONObject().put("trimStartMs", trimStartMs).put("trimEndMs", trimEndMs)
        .put("mute", mute).put("speed", speed.toDouble())

    companion object {
        val SPEEDS = listOf(0.5f, 1f, 2f)
        fun fromJson(o: JSONObject?): VideoEdit = if (o == null) VideoEdit() else VideoEdit(
            o.optLong("trimStartMs", 0).coerceAtLeast(0), o.optLong("trimEndMs", -1),
            o.optBoolean("mute", false), o.optDouble("speed", 1.0).toFloat().takeIf { it in 0.25f..4f } ?: 1f,
        )
    }
}

const val MAX_MASKS = 8
const val MAX_SPOTS = 24
