package com.vknxworks.lumenctrl.model

import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.tr
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Edit panel groups, in the order the editor shows them. */
enum class Group(val label: String) {
    LIGHT(tr(R.string.light)), CURVE(tr(R.string.curve)), COLOR(tr(R.string.color)), MIXER(tr(R.string.mixer)), GRADING(tr(R.string.grading)),
    EFFECTS(tr(R.string.effects)), DETAIL(tr(R.string.detail)), OPTICS(tr(R.string.optics)), BLUR(tr(R.string.blur)), GEOMETRY(tr(R.string.geometry)),
}

enum class Fmt { SIGNED, PERCENT, EV, DEGREES, HUE, SCALE, STOPS }

/** The eight color-mixer bands and their hue centers (degrees). */
enum class MixBand(val label: String, val hue: Float, val swatch: Long) {
    RED(tr(R.string.red), 0f, 0xFFE5484D), ORANGE(tr(R.string.orange), 30f, 0xFFF08A3C), YELLOW(tr(R.string.yellow), 60f, 0xFFF2D34B),
    GREEN(tr(R.string.green), 120f, 0xFF4CC46A), AQUA(tr(R.string.aqua), 180f, 0xFF3FC8D4), BLUE(tr(R.string.blue), 225f, 0xFF4F7CF0),
    PURPLE(tr(R.string.purple), 270f, 0xFF9B6BF2), MAGENTA(tr(R.string.magenta), 315f, 0xFFE15AC4),
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
    EXPOSURE("exposure", tr(R.string.exposure), Group.LIGHT, tr(R.string.tone), -5f, 5f, fmt = Fmt.EV),
    CONTRAST("contrast", tr(R.string.contrast), Group.LIGHT, tr(R.string.tone), -1f, 1f),
    HIGHLIGHTS("highlights", tr(R.string.highlights), Group.LIGHT, tr(R.string.tone), -1f, 1f),
    SHADOWS("shadows", tr(R.string.shadows), Group.LIGHT, tr(R.string.tone), -1f, 1f),
    WHITES("whites", tr(R.string.whites), Group.LIGHT, tr(R.string.tone), -1f, 1f),
    BLACKS("blacks", tr(R.string.blacks), Group.LIGHT, tr(R.string.tone), -1f, 1f),

    // HDR (Lightroom: HDR_LIMIT + SDR rendition). Highlights beyond SDR white go into a gain map.
    HDR_ON("hdr", tr(R.string.hdr), Group.LIGHT, tr(R.string.hdr), 0f, 1f, ui = false),
    HDR_LIMIT("hdrLimit", tr(R.string.hdr_limit), Group.LIGHT, tr(R.string.hdr), 0.5f, 3f, 2f, Fmt.STOPS),
    HDR_BOOST("hdrBoost", tr(R.string.highlight_boost), Group.LIGHT, tr(R.string.hdr), 0f, 1f, 0.6f, Fmt.PERCENT),
    SDR_HIGHLIGHTS("sdrHighlights", tr(R.string.sdr_highlights), Group.LIGHT, tr(R.string.hdr), -1f, 1f),

    // Parametric curve (baked into the curve LUT on the CPU)
    CURVE_HIGHLIGHTS("curveHighlights", tr(R.string.highlights), Group.CURVE, tr(R.string.parametric), -1f, 1f),
    CURVE_LIGHTS("curveLights", tr(R.string.lights), Group.CURVE, tr(R.string.parametric), -1f, 1f),
    CURVE_DARKS("curveDarks", tr(R.string.darks), Group.CURVE, tr(R.string.parametric), -1f, 1f),
    CURVE_SHADOWS("curveShadows", tr(R.string.shadows), Group.CURVE, tr(R.string.parametric), -1f, 1f),

    // Color
    TEMPERATURE("temperature", tr(R.string.temperature), Group.COLOR, tr(R.string.white_balance), -1f, 1f),
    TINT("tint", tr(R.string.tint), Group.COLOR, tr(R.string.white_balance), -1f, 1f),
    VIBRANCE("vibrance", tr(R.string.vibrance), Group.COLOR, tr(R.string.presence), -1f, 1f),
    SATURATION("saturation", tr(R.string.saturation), Group.COLOR, tr(R.string.presence), -1f, 1f),

    // Color mixer: hue x8, saturation x8, luminance x8 (contiguous, the shader indexes them)
    HUE_RED("hueRed", tr(R.string.hue), Group.MIXER, tr(R.string.red), -1f, 1f),
    HUE_ORANGE("hueOrange", tr(R.string.hue), Group.MIXER, tr(R.string.orange), -1f, 1f),
    HUE_YELLOW("hueYellow", tr(R.string.hue), Group.MIXER, tr(R.string.yellow), -1f, 1f),
    HUE_GREEN("hueGreen", tr(R.string.hue), Group.MIXER, tr(R.string.green), -1f, 1f),
    HUE_AQUA("hueAqua", tr(R.string.hue), Group.MIXER, tr(R.string.aqua), -1f, 1f),
    HUE_BLUE("hueBlue", tr(R.string.hue), Group.MIXER, tr(R.string.blue), -1f, 1f),
    HUE_PURPLE("huePurple", tr(R.string.hue), Group.MIXER, tr(R.string.purple), -1f, 1f),
    HUE_MAGENTA("hueMagenta", tr(R.string.hue), Group.MIXER, tr(R.string.magenta), -1f, 1f),
    SAT_RED("satRed", tr(R.string.saturation), Group.MIXER, tr(R.string.red), -1f, 1f),
    SAT_ORANGE("satOrange", tr(R.string.saturation), Group.MIXER, tr(R.string.orange), -1f, 1f),
    SAT_YELLOW("satYellow", tr(R.string.saturation), Group.MIXER, tr(R.string.yellow), -1f, 1f),
    SAT_GREEN("satGreen", tr(R.string.saturation), Group.MIXER, tr(R.string.green), -1f, 1f),
    SAT_AQUA("satAqua", tr(R.string.saturation), Group.MIXER, tr(R.string.aqua), -1f, 1f),
    SAT_BLUE("satBlue", tr(R.string.saturation), Group.MIXER, tr(R.string.blue), -1f, 1f),
    SAT_PURPLE("satPurple", tr(R.string.saturation), Group.MIXER, tr(R.string.purple), -1f, 1f),
    SAT_MAGENTA("satMagenta", tr(R.string.saturation), Group.MIXER, tr(R.string.magenta), -1f, 1f),
    LUM_RED("lumRed", tr(R.string.luminance), Group.MIXER, tr(R.string.red), -1f, 1f),
    LUM_ORANGE("lumOrange", tr(R.string.luminance), Group.MIXER, tr(R.string.orange), -1f, 1f),
    LUM_YELLOW("lumYellow", tr(R.string.luminance), Group.MIXER, tr(R.string.yellow), -1f, 1f),
    LUM_GREEN("lumGreen", tr(R.string.luminance), Group.MIXER, tr(R.string.green), -1f, 1f),
    LUM_AQUA("lumAqua", tr(R.string.luminance), Group.MIXER, tr(R.string.aqua), -1f, 1f),
    LUM_BLUE("lumBlue", tr(R.string.luminance), Group.MIXER, tr(R.string.blue), -1f, 1f),
    LUM_PURPLE("lumPurple", tr(R.string.luminance), Group.MIXER, tr(R.string.purple), -1f, 1f),
    LUM_MAGENTA("lumMagenta", tr(R.string.luminance), Group.MIXER, tr(R.string.magenta), -1f, 1f),

    // Color grading (3-way + global)
    SHADOW_HUE("shadowHue", tr(R.string.hue), Group.GRADING, tr(R.string.shadows), 0f, 360f, 220f, Fmt.HUE),
    SHADOW_SAT("shadowSat", tr(R.string.saturation), Group.GRADING, tr(R.string.shadows), 0f, 1f, fmt = Fmt.PERCENT),
    SHADOW_LUM("shadowLum", tr(R.string.luminance), Group.GRADING, tr(R.string.shadows), -1f, 1f),
    MID_HUE("midHue", tr(R.string.hue), Group.GRADING, tr(R.string.midtones), 0f, 360f, 30f, Fmt.HUE),
    MID_SAT("midSat", tr(R.string.saturation), Group.GRADING, tr(R.string.midtones), 0f, 1f, fmt = Fmt.PERCENT),
    MID_LUM("midLum", tr(R.string.luminance), Group.GRADING, tr(R.string.midtones), -1f, 1f),
    HIGHLIGHT_HUE("highlightHue", tr(R.string.hue), Group.GRADING, tr(R.string.highlights), 0f, 360f, 40f, Fmt.HUE),
    HIGHLIGHT_SAT("highlightSat", tr(R.string.saturation), Group.GRADING, tr(R.string.highlights), 0f, 1f, fmt = Fmt.PERCENT),
    HIGHLIGHT_LUM("highlightLum", tr(R.string.luminance), Group.GRADING, tr(R.string.highlights), -1f, 1f),
    GLOBAL_HUE("globalHue", tr(R.string.hue), Group.GRADING, tr(R.string.global), 0f, 360f, 0f, Fmt.HUE),
    GLOBAL_SAT("globalSat", tr(R.string.saturation), Group.GRADING, tr(R.string.global), 0f, 1f, fmt = Fmt.PERCENT),
    GLOBAL_LUM("globalLum", tr(R.string.luminance), Group.GRADING, tr(R.string.global), -1f, 1f),
    GRADE_BLENDING("gradeBlending", tr(R.string.blending), Group.GRADING, tr(R.string.mix), 0f, 1f, 0.5f, Fmt.PERCENT),
    GRADE_BALANCE("gradeBalance", tr(R.string.balance), Group.GRADING, tr(R.string.mix), -1f, 1f),

    // Effects
    TEXTURE("texture", tr(R.string.texture), Group.EFFECTS, tr(R.string.presence), -1f, 1f),
    CLARITY("clarity", tr(R.string.clarity), Group.EFFECTS, tr(R.string.presence), -1f, 1f),
    DEHAZE("dehaze", tr(R.string.dehaze), Group.EFFECTS, tr(R.string.presence), -1f, 1f),
    FADE("fade", tr(R.string.fade), Group.EFFECTS, tr(R.string.presence), 0f, 1f, fmt = Fmt.PERCENT),
    VIGNETTE("vignette", tr(R.string.amount), Group.EFFECTS, tr(R.string.vignette), -1f, 1f),
    VIG_MIDPOINT("vigMidpoint", tr(R.string.midpoint), Group.EFFECTS, tr(R.string.vignette), 0f, 1f, 0.5f, Fmt.PERCENT),
    VIG_ROUNDNESS("vigRoundness", tr(R.string.roundness), Group.EFFECTS, tr(R.string.vignette), -1f, 1f),
    VIG_FEATHER("vigFeather", tr(R.string.feather), Group.EFFECTS, tr(R.string.vignette), 0f, 1f, 0.5f, Fmt.PERCENT),
    VIG_HIGHLIGHTS("vigHighlights", tr(R.string.highlights), Group.EFFECTS, tr(R.string.vignette), 0f, 1f, fmt = Fmt.PERCENT),
    GRAIN("grain", tr(R.string.amount), Group.EFFECTS, tr(R.string.grain), 0f, 1f, fmt = Fmt.PERCENT),
    GRAIN_SIZE("grainSize", tr(R.string.size), Group.EFFECTS, tr(R.string.grain), 0f, 1f, 0.25f, Fmt.PERCENT),
    GRAIN_ROUGHNESS("grainRoughness", tr(R.string.roughness), Group.EFFECTS, tr(R.string.grain), 0f, 1f, 0.5f, Fmt.PERCENT),
    GLOW("glow", tr(R.string.amount), Group.EFFECTS, tr(R.string.glow), 0f, 1f, fmt = Fmt.PERCENT),
    GLOW_SIZE("glowSize", tr(R.string.size), Group.EFFECTS, tr(R.string.glow), 0f, 1f, 0.5f, Fmt.PERCENT),
    GLOW_WARMTH("glowWarmth", tr(R.string.warmth), Group.EFFECTS, tr(R.string.glow), -1f, 1f),

    // Detail. AI Denoise blends toward a denoised base computed once per photo.
    AI_DENOISE("aiDenoise", tr(R.string.amount), Group.DETAIL, tr(R.string.ai_denoise), 0f, 1f, fmt = Fmt.PERCENT),
    SHARPEN("sharpen", tr(R.string.amount), Group.DETAIL, tr(R.string.sharpening), 0f, 1f, fmt = Fmt.PERCENT),
    SHARPEN_RADIUS("sharpenRadius", tr(R.string.radius), Group.DETAIL, tr(R.string.sharpening), 0f, 1f, 0.3f, Fmt.PERCENT),
    SHARPEN_DETAIL("sharpenDetail", tr(R.string.detail), Group.DETAIL, tr(R.string.sharpening), 0f, 1f, 0.25f, Fmt.PERCENT),
    SHARPEN_MASKING("sharpenMasking", tr(R.string.masking), Group.DETAIL, tr(R.string.sharpening), 0f, 1f, fmt = Fmt.PERCENT),
    NOISE_LUM("noiseLum", tr(R.string.luminance), Group.DETAIL, tr(R.string.noise_reduction), 0f, 1f, fmt = Fmt.PERCENT),
    NOISE_DETAIL("noiseDetail", tr(R.string.detail), Group.DETAIL, tr(R.string.noise_reduction), 0f, 1f, 0.5f, Fmt.PERCENT),
    NOISE_COLOR("noiseColor", tr(R.string.color), Group.DETAIL, tr(R.string.noise_reduction), 0f, 1f, fmt = Fmt.PERCENT),

    // Optics
    DISTORTION("distortion", tr(R.string.distortion), Group.OPTICS, tr(R.string.lens_corrections), -1f, 1f),
    LENS_VIGNETTE("lensVignette", tr(R.string.vignetting), Group.OPTICS, tr(R.string.lens_corrections), -1f, 1f),
    CHROMATIC("chromatic", tr(R.string.chromatic_aberration), Group.OPTICS, tr(R.string.fringing), -1f, 1f),
    DEFRINGE_PURPLE("defringePurple", tr(R.string.purple_defringe), Group.OPTICS, tr(R.string.fringing), 0f, 1f, fmt = Fmt.PERCENT),
    DEFRINGE_GREEN("defringeGreen", tr(R.string.green_defringe), Group.OPTICS, tr(R.string.fringing), 0f, 1f, fmt = Fmt.PERCENT),

    // Lens blur
    BLUR_AMOUNT("blurAmount", tr(R.string.blur_amount), Group.BLUR, tr(R.string.lens_blur), 0f, 1f, fmt = Fmt.PERCENT),
    BLUR_FOCUS_SIZE("blurFocusSize", tr(R.string.focus_range), Group.BLUR, tr(R.string.lens_blur), 0f, 1f, 0.25f, Fmt.PERCENT),
    BLUR_FEATHER("blurFeather", tr(R.string.falloff), Group.BLUR, tr(R.string.lens_blur), 0f, 1f, 0.5f, Fmt.PERCENT),
    BLUR_MODE("blurMode", tr(R.string.mode), Group.BLUR, tr(R.string.lens_blur), 0f, 1f, ui = false),
    BLUR_FOCUS_X("blurFocusX", tr(R.string.focus_x), Group.BLUR, tr(R.string.lens_blur), 0f, 1f, 0.5f, ui = false),
    BLUR_FOCUS_Y("blurFocusY", tr(R.string.focus_y), Group.BLUR, tr(R.string.lens_blur), 0f, 1f, 0.5f, ui = false),

    // Geometry
    STRAIGHTEN("straighten", tr(R.string.straighten), Group.GEOMETRY, tr(R.string.rotate), -45f, 45f, fmt = Fmt.DEGREES),
    VERTICAL("vertical", tr(R.string.vertical), Group.GEOMETRY, tr(R.string.transform), -1f, 1f),
    HORIZONTAL("horizontal", tr(R.string.horizontal), Group.GEOMETRY, tr(R.string.transform), -1f, 1f),
    ASPECT("aspect", tr(R.string.aspect), Group.GEOMETRY, tr(R.string.transform), -1f, 1f),
    SCALE("scale", tr(R.string.scale), Group.GEOMETRY, tr(R.string.transform), 0.5f, 1.5f, 1f, Fmt.SCALE),
    OFFSET_X("offsetX", tr(R.string.x_offset), Group.GEOMETRY, tr(R.string.transform), -1f, 1f),
    OFFSET_Y("offsetY", tr(R.string.y_offset), Group.GEOMETRY, tr(R.string.transform), -1f, 1f),

    // LUT strength (the LUT itself is EditParams.lut); its slider sits with the LUT picker in Color.
    LUT_AMOUNT("lutAmount", tr(R.string.amount), Group.COLOR, tr(R.string.lut), 0f, 1f, 1f, Fmt.PERCENT, ui = false);

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
    /** Id of an imported 3D LUT (see data/Luts), applied after color grading at [Param.LUT_AMOUNT]. */
    val lut: String? = null,
) {
    operator fun get(p: Param): Float = values[p.key] ?: p.default

    fun with(p: Param, v: Float): EditParams =
        copy(values = values + (p.key to v.coerceIn(p.min, p.max)))

    fun isDefault(): Boolean =
        Param.entries.all { get(it) == it.default } && quarterTurns == 0 && !flipH &&
            crop.isFull() && curves.isIdentity() && masks.isEmpty() && spots.isEmpty() && video.isDefault() && lut == null

    /** Lightroom-style paste: global adjustments and curves; keeps this photo's framing, masks and spots. */
    fun pasteAdjustmentsFrom(other: EditParams): EditParams = copy(
        values = other.values.filterKeys { it !in Param.PER_PHOTO } + values.filterKeys { it in Param.PER_PHOTO },
        curves = other.curves,
        preset = other.preset,
        presetAmount = other.presetAmount,
        lut = other.lut,
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
            .put("lut", lut ?: JSONObject.NULL)
            .toString(2)
    }

    companion object {
        /** LUT ids are made by LumenCtrl (UUIDs); anything else in a sidecar is ignored. */
        private val LUT_ID = Regex("^[a-f0-9-]{8,40}$")

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
                lut = if (o.isNull("lut")) null else o.optString("lut").takeIf { LUT_ID.matches(it) },
            )
        }
    }
}
