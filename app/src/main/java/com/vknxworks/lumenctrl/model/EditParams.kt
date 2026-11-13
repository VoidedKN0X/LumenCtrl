package com.vknxworks.lumenctrl.model

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Edit panel groups, in the order the editor shows them. */
enum class Group(val label: String) {
    LIGHT("Light"), CURVE("Curve"), COLOR("Color"), MIXER("Mixer"), GRADING("Grading"),
    EFFECTS("Effects"), DETAIL("Detail"), OPTICS("Optics"), BLUR("Blur"), GEOMETRY("Geometry"),
}

enum class Fmt { SIGNED, PERCENT, EV, DEGREES, HUE, SCALE, STOPS }

/** The eight color-mixer bands and their hue centers (degrees). */
enum class MixBand(val label: String, val hue: Float, val swatch: Long) {
    RED("Red", 0f, 0xFFE5484D), ORANGE("Orange", 30f, 0xFFF08A3C), YELLOW("Yellow", 60f, 0xFFF2D34B),
    GREEN("Green", 120f, 0xFF4CC46A), AQUA("Aqua", 180f, 0xFF3FC8D4), BLUE("Blue", 225f, 0xFF4F7CF0),
    PURPLE("Purple", 270f, 0xFF9B6BF2), MAGENTA("Magenta", 315f, 0xFFE15AC4),
}

/**
 * Every global slider. The enum order is the GPU packing order: the shader
 * reads param i from u_p[i/4][i%4] through generated #defines (P_<key>),
 * so adding a slider is one entry here plus its math in Shaders.kt.
 * Keys are also the sidecar JSON keys.
 */
enum class Param(
    val key: String,
    val label: String,
    val group: Group,
    val section: String,
    val min: Float,
    val max: Float,
    val default: Float = 0f,
    val fmt: Fmt = Fmt.SIGNED,
    val ui: Boolean = true,
) {
    // Light
    EXPOSURE("exposure", "Exposure", Group.LIGHT, "Tone", -5f, 5f, fmt = Fmt.EV),
    CONTRAST("contrast", "Contrast", Group.LIGHT, "Tone", -1f, 1f),
    HIGHLIGHTS("highlights", "Highlights", Group.LIGHT, "Tone", -1f, 1f),
    SHADOWS("shadows", "Shadows", Group.LIGHT, "Tone", -1f, 1f),
    WHITES("whites", "Whites", Group.LIGHT, "Tone", -1f, 1f),
    BLACKS("blacks", "Blacks", Group.LIGHT, "Tone", -1f, 1f),

    // HDR (Lightroom: HDR_LIMIT + SDR rendition). Highlights beyond SDR white go into a gain map.
    HDR_ON("hdr", "HDR", Group.LIGHT, "HDR", 0f, 1f, ui = false),
    HDR_LIMIT("hdrLimit", "HDR limit", Group.LIGHT, "HDR", 0.5f, 3f, 2f, Fmt.STOPS),
    HDR_BOOST("hdrBoost", "Highlight boost", Group.LIGHT, "HDR", 0f, 1f, 0.6f, Fmt.PERCENT),
    SDR_HIGHLIGHTS("sdrHighlights", "SDR highlights", Group.LIGHT, "HDR", -1f, 1f),

    // Parametric curve (baked into the curve LUT on the CPU)
    CURVE_HIGHLIGHTS("curveHighlights", "Highlights", Group.CURVE, "Parametric", -1f, 1f),
    CURVE_LIGHTS("curveLights", "Lights", Group.CURVE, "Parametric", -1f, 1f),
    CURVE_DARKS("curveDarks", "Darks", Group.CURVE, "Parametric", -1f, 1f),
    CURVE_SHADOWS("curveShadows", "Shadows", Group.CURVE, "Parametric", -1f, 1f),

    // Color
    TEMPERATURE("temperature", "Temperature", Group.COLOR, "White balance", -1f, 1f),
    TINT("tint", "Tint", Group.COLOR, "White balance", -1f, 1f),
    VIBRANCE("vibrance", "Vibrance", Group.COLOR, "Presence", -1f, 1f),
    SATURATION("saturation", "Saturation", Group.COLOR, "Presence", -1f, 1f),

    // Color mixer: hue x8, saturation x8, luminance x8 (contiguous, the shader indexes them)
    HUE_RED("hueRed", "Hue", Group.MIXER, "Red", -1f, 1f),
    HUE_ORANGE("hueOrange", "Hue", Group.MIXER, "Orange", -1f, 1f),
    HUE_YELLOW("hueYellow", "Hue", Group.MIXER, "Yellow", -1f, 1f),
    HUE_GREEN("hueGreen", "Hue", Group.MIXER, "Green", -1f, 1f),
    HUE_AQUA("hueAqua", "Hue", Group.MIXER, "Aqua", -1f, 1f),
    HUE_BLUE("hueBlue", "Hue", Group.MIXER, "Blue", -1f, 1f),
    HUE_PURPLE("huePurple", "Hue", Group.MIXER, "Purple", -1f, 1f),
    HUE_MAGENTA("hueMagenta", "Hue", Group.MIXER, "Magenta", -1f, 1f),
    SAT_RED("satRed", "Saturation", Group.MIXER, "Red", -1f, 1f),
    SAT_ORANGE("satOrange", "Saturation", Group.MIXER, "Orange", -1f, 1f),
    SAT_YELLOW("satYellow", "Saturation", Group.MIXER, "Yellow", -1f, 1f),
    SAT_GREEN("satGreen", "Saturation", Group.MIXER, "Green", -1f, 1f),
    SAT_AQUA("satAqua", "Saturation", Group.MIXER, "Aqua", -1f, 1f),
    SAT_BLUE("satBlue", "Saturation", Group.MIXER, "Blue", -1f, 1f),
    SAT_PURPLE("satPurple", "Saturation", Group.MIXER, "Purple", -1f, 1f),
    SAT_MAGENTA("satMagenta", "Saturation", Group.MIXER, "Magenta", -1f, 1f),
    LUM_RED("lumRed", "Luminance", Group.MIXER, "Red", -1f, 1f),
    LUM_ORANGE("lumOrange", "Luminance", Group.MIXER, "Orange", -1f, 1f),
    LUM_YELLOW("lumYellow", "Luminance", Group.MIXER, "Yellow", -1f, 1f),
    LUM_GREEN("lumGreen", "Luminance", Group.MIXER, "Green", -1f, 1f),
    LUM_AQUA("lumAqua", "Luminance", Group.MIXER, "Aqua", -1f, 1f),
    LUM_BLUE("lumBlue", "Luminance", Group.MIXER, "Blue", -1f, 1f),
    LUM_PURPLE("lumPurple", "Luminance", Group.MIXER, "Purple", -1f, 1f),
    LUM_MAGENTA("lumMagenta", "Luminance", Group.MIXER, "Magenta", -1f, 1f),

    // Color grading (3-way + global)
    SHADOW_HUE("shadowHue", "Hue", Group.GRADING, "Shadows", 0f, 360f, 220f, Fmt.HUE),
    SHADOW_SAT("shadowSat", "Saturation", Group.GRADING, "Shadows", 0f, 1f, fmt = Fmt.PERCENT),
    SHADOW_LUM("shadowLum", "Luminance", Group.GRADING, "Shadows", -1f, 1f),
    MID_HUE("midHue", "Hue", Group.GRADING, "Midtones", 0f, 360f, 30f, Fmt.HUE),
    MID_SAT("midSat", "Saturation", Group.GRADING, "Midtones", 0f, 1f, fmt = Fmt.PERCENT),
    MID_LUM("midLum", "Luminance", Group.GRADING, "Midtones", -1f, 1f),
    HIGHLIGHT_HUE("highlightHue", "Hue", Group.GRADING, "Highlights", 0f, 360f, 40f, Fmt.HUE),
    HIGHLIGHT_SAT("highlightSat", "Saturation", Group.GRADING, "Highlights", 0f, 1f, fmt = Fmt.PERCENT),
    HIGHLIGHT_LUM("highlightLum", "Luminance", Group.GRADING, "Highlights", -1f, 1f),
    GLOBAL_HUE("globalHue", "Hue", Group.GRADING, "Global", 0f, 360f, 0f, Fmt.HUE),
    GLOBAL_SAT("globalSat", "Saturation", Group.GRADING, "Global", 0f, 1f, fmt = Fmt.PERCENT),
    GLOBAL_LUM("globalLum", "Luminance", Group.GRADING, "Global", -1f, 1f),
    GRADE_BLENDING("gradeBlending", "Blending", Group.GRADING, "Mix", 0f, 1f, 0.5f, Fmt.PERCENT),
    GRADE_BALANCE("gradeBalance", "Balance", Group.GRADING, "Mix", -1f, 1f),

    // Effects
    TEXTURE("texture", "Texture", Group.EFFECTS, "Presence", -1f, 1f),
    CLARITY("clarity", "Clarity", Group.EFFECTS, "Presence", -1f, 1f),
    DEHAZE("dehaze", "Dehaze", Group.EFFECTS, "Presence", -1f, 1f),
    FADE("fade", "Fade", Group.EFFECTS, "Presence", 0f, 1f, fmt = Fmt.PERCENT),
    VIGNETTE("vignette", "Amount", Group.EFFECTS, "Vignette", -1f, 1f),
    VIG_MIDPOINT("vigMidpoint", "Midpoint", Group.EFFECTS, "Vignette", 0f, 1f, 0.5f, Fmt.PERCENT),
    VIG_ROUNDNESS("vigRoundness", "Roundness", Group.EFFECTS, "Vignette", -1f, 1f),
    VIG_FEATHER("vigFeather", "Feather", Group.EFFECTS, "Vignette", 0f, 1f, 0.5f, Fmt.PERCENT),
    VIG_HIGHLIGHTS("vigHighlights", "Highlights", Group.EFFECTS, "Vignette", 0f, 1f, fmt = Fmt.PERCENT),
    GRAIN("grain", "Amount", Group.EFFECTS, "Grain", 0f, 1f, fmt = Fmt.PERCENT),
    GRAIN_SIZE("grainSize", "Size", Group.EFFECTS, "Grain", 0f, 1f, 0.25f, Fmt.PERCENT),
    GRAIN_ROUGHNESS("grainRoughness", "Roughness", Group.EFFECTS, "Grain", 0f, 1f, 0.5f, Fmt.PERCENT),
    GLOW("glow", "Amount", Group.EFFECTS, "Glow", 0f, 1f, fmt = Fmt.PERCENT),
    GLOW_SIZE("glowSize", "Size", Group.EFFECTS, "Glow", 0f, 1f, 0.5f, Fmt.PERCENT),
    GLOW_WARMTH("glowWarmth", "Warmth", Group.EFFECTS, "Glow", -1f, 1f),

    // Detail. AI Denoise blends toward a denoised base computed once per photo.
    AI_DENOISE("aiDenoise", "Amount", Group.DETAIL, "AI Denoise", 0f, 1f, fmt = Fmt.PERCENT),
    SHARPEN("sharpen", "Amount", Group.DETAIL, "Sharpening", 0f, 1f, fmt = Fmt.PERCENT),
    SHARPEN_RADIUS("sharpenRadius", "Radius", Group.DETAIL, "Sharpening", 0f, 1f, 0.3f, Fmt.PERCENT),
    SHARPEN_DETAIL("sharpenDetail", "Detail", Group.DETAIL, "Sharpening", 0f, 1f, 0.25f, Fmt.PERCENT),
    SHARPEN_MASKING("sharpenMasking", "Masking", Group.DETAIL, "Sharpening", 0f, 1f, fmt = Fmt.PERCENT),
    NOISE_LUM("noiseLum", "Luminance", Group.DETAIL, "Noise reduction", 0f, 1f, fmt = Fmt.PERCENT),
    NOISE_DETAIL("noiseDetail", "Detail", Group.DETAIL, "Noise reduction", 0f, 1f, 0.5f, Fmt.PERCENT),
    NOISE_COLOR("noiseColor", "Color", Group.DETAIL, "Noise reduction", 0f, 1f, fmt = Fmt.PERCENT),

    // Optics
    DISTORTION("distortion", "Distortion", Group.OPTICS, "Lens corrections", -1f, 1f),
    LENS_VIGNETTE("lensVignette", "Vignetting", Group.OPTICS, "Lens corrections", -1f, 1f),
    CHROMATIC("chromatic", "Chromatic aberration", Group.OPTICS, "Fringing", -1f, 1f),
    DEFRINGE_PURPLE("defringePurple", "Purple defringe", Group.OPTICS, "Fringing", 0f, 1f, fmt = Fmt.PERCENT),
    DEFRINGE_GREEN("defringeGreen", "Green defringe", Group.OPTICS, "Fringing", 0f, 1f, fmt = Fmt.PERCENT),

    // Lens blur
    BLUR_AMOUNT("blurAmount", "Blur amount", Group.BLUR, "Lens blur", 0f, 1f, fmt = Fmt.PERCENT),
    BLUR_FOCUS_SIZE("blurFocusSize", "Focus range", Group.BLUR, "Lens blur", 0f, 1f, 0.25f, Fmt.PERCENT),
    BLUR_FEATHER("blurFeather", "Falloff", Group.BLUR, "Lens blur", 0f, 1f, 0.5f, Fmt.PERCENT),
    BLUR_MODE("blurMode", "Mode", Group.BLUR, "Lens blur", 0f, 1f, ui = false),
    BLUR_FOCUS_X("blurFocusX", "Focus X", Group.BLUR, "Lens blur", 0f, 1f, 0.5f, ui = false),
    BLUR_FOCUS_Y("blurFocusY", "Focus Y", Group.BLUR, "Lens blur", 0f, 1f, 0.5f, ui = false),

    // Geometry
    STRAIGHTEN("straighten", "Straighten", Group.GEOMETRY, "Rotate", -45f, 45f, fmt = Fmt.DEGREES),
    VERTICAL("vertical", "Vertical", Group.GEOMETRY, "Transform", -1f, 1f),
    HORIZONTAL("horizontal", "Horizontal", Group.GEOMETRY, "Transform", -1f, 1f),
    ASPECT("aspect", "Aspect", Group.GEOMETRY, "Transform", -1f, 1f),
    SCALE("scale", "Scale", Group.GEOMETRY, "Transform", 0.5f, 1.5f, 1f, Fmt.SCALE),
    OFFSET_X("offsetX", "X offset", Group.GEOMETRY, "Transform", -1f, 1f),
    OFFSET_Y("offsetY", "Y offset", Group.GEOMETRY, "Transform", -1f, 1f);

    fun format(v: Float): String = when (fmt) {
        Fmt.EV -> "%+.2f EV".format(v)
        Fmt.DEGREES -> "%+.1f°".format(v)
        Fmt.HUE -> "${v.roundToInt()}°"
        Fmt.SCALE -> "${(v * 100).roundToInt()}%"
        Fmt.STOPS -> "+%.1f stops · %.1f×".format(v, Math.pow(2.0, v.toDouble()))
        Fmt.PERCENT -> "${(v * 100).roundToInt()}"
        Fmt.SIGNED -> (v * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }
    }

    companion object {
        val byKey = entries.associateBy { it.key }
        /** vec4 slots the shader needs for all params. */
        val PACKED_VEC4 = (entries.size + 3) / 4
        /** Params that belong to one photo's framing; never copied between photos or replaced by presets. */
        val PER_PHOTO = (entries.filter { it.group == Group.GEOMETRY } + listOf(BLUR_FOCUS_X, BLUR_FOCUS_Y, AI_DENOISE)).map { it.key }.toSet()
        fun mixer(kind: Int, band: MixBand): Param = entries[HUE_RED.ordinal + kind * 8 + band.ordinal]
    }
}

/**
 * Non-destructive edit state for one photo, saved as a JSON sidecar beside the
 * untouched original (Lightroom stores the same idea as XMP develop settings).
 */
data class EditParams(
    val values: Map<String, Float> = emptyMap(),
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val crop: CropRect = CropRect.FULL,
    val cropAspect: String = AspectLock.FREE.key,
    val curves: Curves = Curves(),
    val masks: List<LocalMask> = emptyList(),
    val spots: List<Spot> = emptyList(),
    val preset: String? = null,
    val presetAmount: Float = 1f,
    /** Trim / mute / speed; only used for videos. */
    val video: VideoEdit = VideoEdit(),
) {
    operator fun get(p: Param): Float = values[p.key] ?: p.default

    fun with(p: Param, v: Float): EditParams =
        copy(values = values + (p.key to v.coerceIn(p.min, p.max)))

    fun isDefault(): Boolean =
        Param.entries.all { get(it) == it.default } && quarterTurns == 0 && !flipH &&
            crop.isFull() && curves.isIdentity() && masks.isEmpty() && spots.isEmpty() && video.isDefault()

    /** Lightroom-style paste: global adjustments and curves; keeps this photo's framing, masks and spots. */
    fun pasteAdjustmentsFrom(other: EditParams): EditParams = copy(
        values = other.values.filterKeys { it !in Param.PER_PHOTO } + values.filterKeys { it in Param.PER_PHOTO },
        curves = other.curves,
        preset = other.preset,
        presetAmount = other.presetAmount,
    )

    fun resetGeometry(): EditParams = copy(
        values = values.filterKeys { k -> Param.byKey[k]?.group != Group.GEOMETRY },
        quarterTurns = 0, flipH = false, crop = CropRect.FULL, cropAspect = AspectLock.FREE.key,
    )

    fun toJson(): String {
        val v = JSONObject()
        values.forEach { (k, x) -> v.put(k, x.toDouble()) }
        return JSONObject()
            .put("format", "lumen-edit")
            .put("version", 2)
            .put("values", v)
            .put("quarterTurns", quarterTurns)
            .put("flipH", flipH)
            .put("crop", crop.toJson())
            .put("cropAspect", cropAspect)
            .put("curves", curves.toJson())
            .put("masks", JSONArray().apply { masks.forEach { put(it.toJson()) } })
            .put("spots", JSONArray().apply { spots.forEach { put(it.toJson()) } })
            .put("preset", preset ?: JSONObject.NULL)
            .put("presetAmount", presetAmount.toDouble())
            .put("video", video.toJson())
            .toString(2)
    }

    companion object {
        fun fromJson(json: String): EditParams {
            val o = JSONObject(json)
            val v = o.optJSONObject("values")
            val values = buildMap<String, Float> {
                v?.keys()?.forEach { k ->
                    val p = Param.byKey[k] ?: return@forEach // ignore keys from future versions
                    put(k, v.getDouble(k).toFloat().coerceIn(p.min, p.max))
                }
            }
            val masks = o.optJSONArray("masks")?.let { a -> (0 until a.length()).mapNotNull { LocalMask.fromJson(a.getJSONObject(it)) } }.orEmpty()
            val spots = o.optJSONArray("spots")?.let { a -> (0 until a.length()).mapNotNull { Spot.fromJson(a.getJSONObject(it)) } }.orEmpty()
            return EditParams(
                values = values,
                quarterTurns = o.optInt("quarterTurns", 0).mod(4),
                flipH = o.optBoolean("flipH", false),
                crop = CropRect.fromJson(o.optJSONObject("crop")),
                cropAspect = o.optString("cropAspect", AspectLock.FREE.key),
                curves = Curves.fromJson(o.optJSONObject("curves")),
                masks = masks.take(MAX_MASKS),
                spots = spots.take(MAX_SPOTS),
                preset = if (o.isNull("preset")) null else o.optString("preset").ifBlank { null },
                presetAmount = o.optDouble("presetAmount", 1.0).toFloat().coerceIn(0f, 2f),
                video = VideoEdit.fromJson(o.optJSONObject("video")),
            )
        }
    }
}
