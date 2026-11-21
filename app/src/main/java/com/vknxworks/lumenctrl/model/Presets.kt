package com.vknxworks.lumenctrl.model

import com.vknxworks.lumenctrl.model.Param.*

data class Preset(val name: String, val values: Map<Param, Float>) {
    /** The name as shown, in the phone's language. [name] itself is what edits store, so it never changes. */
    val label: String get() = LABELS[name]?.let { com.vknxworks.lumenctrl.tr(it) } ?: name

    /**
     * Replace global adjustments with this look at [amount] (0..2, 1 = as designed),
     * like Lightroom's preset amount slider. Framing, masks and spots are kept.
     */
    fun applyTo(base: EditParams, amount: Float = 1f): EditParams = base.copy(
        values = base.values.filterKeys { it in Param.PER_PHOTO } +
            values.map { (p, v) -> p.key to (p.default + (v - p.default) * amount).coerceIn(p.min, p.max) },
        curves = Curves(),
        preset = name,
        presetAmount = amount,
        lut = null,
    )
}

/**
 * A look the user saved from one of their own edits: the global sliders, the curves and the LUT, without anything
 * that belongs to one photo's framing (crop, geometry, focus point, AI Denoise) and without masks or spots.
 * Kept in `presets.json` (see data/UserPresets). In an edit it is named [key].
 */
data class UserPreset(
    val id: String,
    val name: String,
    val values: Map<String, Float>,
    val curves: Curves,
    val lut: String?,
) {
    val key: String get() = KEY_PREFIX + id

    /** Like [Preset.applyTo]: replaces the global look at [amount] (0..2) and keeps framing, masks and spots. */
    fun applyTo(base: EditParams, amount: Float = 1f): EditParams {
        val look = values.filterKeys { it !in Param.PER_PHOTO && it != Param.LUT_AMOUNT.key }.mapNotNull { (k, v) ->
            val p = Param.byKey[k] ?: return@mapNotNull null
            k to (p.default + (v - p.default) * amount).coerceIn(p.min, p.max)
        }.toMap()
        // The LUT's strength fades toward "no LUT" (0), not toward the slider's default.
        val lutAmount = if (lut != null) mapOf(Param.LUT_AMOUNT.key to ((values[Param.LUT_AMOUNT.key] ?: 1f) * amount).coerceIn(0f, 1f)) else emptyMap()
        return base.copy(
            values = base.values.filterKeys { it in Param.PER_PHOTO } + look + lutAmount,
            curves = Curves(
                blend(curves.master, amount), blend(curves.red, amount), blend(curves.green, amount), blend(curves.blue, amount),
            ),
            preset = key,
            presetAmount = amount,
            lut = lut,
        )
    }

    private fun blend(pts: List<CurvePoint>, amount: Float): List<CurvePoint> =
        if (pts == Curves.IDENTITY) pts else pts.map { CurvePoint(it.x, (it.x + (it.y - it.x) * amount).coerceIn(0f, 1f)) }

    companion object {
        const val KEY_PREFIX = "user:"

        /** A preset made from [p]'s global look. */
        fun from(id: String, name: String, p: EditParams) = UserPreset(
            id, name,
            values = p.values.filterKeys { it !in Param.PER_PHOTO },
            curves = p.curves,
            lut = p.lut,
        )
    }
}

object Presets {
    val all = listOf(
        Preset("Original", emptyMap()),
        Preset("Clean", mapOf(EXPOSURE to 0.25f, HIGHLIGHTS to -0.35f, SHADOWS to 0.35f, VIBRANCE to 0.15f, DEHAZE to 0.1f, TEXTURE to 0.1f)),
        Preset("Vivid", mapOf(CONTRAST to 0.2f, VIBRANCE to 0.4f, SATURATION to 0.1f, CLARITY to 0.15f)),
        Preset("Matte", mapOf(CONTRAST to -0.15f, FADE to 0.4f, HIGHLIGHTS to -0.2f, SATURATION to -0.15f, CURVE_SHADOWS to 0.3f)),
        Preset(
            "Warm film", mapOf(
                TEMPERATURE to 0.3f, FADE to 0.2f, GRAIN to 0.35f, GRAIN_SIZE to 0.4f,
                SHADOW_HUE to 200f, SHADOW_SAT to 0.25f, HIGHLIGHT_HUE to 40f, HIGHLIGHT_SAT to 0.3f,
            )
        ),
        Preset(
            "Teal & amber", mapOf(
                CONTRAST to 0.15f, SHADOW_HUE to 190f, SHADOW_SAT to 0.45f,
                HIGHLIGHT_HUE to 32f, HIGHLIGHT_SAT to 0.4f, VIBRANCE to 0.1f,
                HUE_ORANGE to -0.15f, SAT_BLUE to 0.2f, HUE_GREEN to 0.3f,
            )
        ),
        Preset("Cool fade", mapOf(TEMPERATURE to -0.25f, FADE to 0.3f, SATURATION to -0.2f, SHADOW_HUE to 215f, SHADOW_SAT to 0.3f)),
        Preset("Moody", mapOf(EXPOSURE to -0.3f, CONTRAST to 0.25f, SHADOWS to -0.2f, VIBRANCE to -0.25f, VIGNETTE to -0.45f, CLARITY to 0.2f)),
        Preset("Dreamy", mapOf(GLOW to 0.45f, GLOW_WARMTH to 0.3f, CLARITY to -0.25f, HIGHLIGHTS to -0.1f, FADE to 0.15f)),
        Preset("Portrait", mapOf(TEXTURE to -0.2f, CLARITY to -0.1f, LUM_ORANGE to 0.15f, SAT_ORANGE to -0.1f, VIGNETTE to -0.2f, VIG_ROUNDNESS to 0.6f)),
        Preset("B&W", mapOf(SATURATION to -1f, CONTRAST to 0.3f, CLARITY to 0.2f)),
        Preset("B&W soft", mapOf(SATURATION to -1f, CONTRAST to -0.2f, FADE to 0.3f, GRAIN to 0.4f)),
    )

    fun byName(name: String?) = all.firstOrNull { it.name == name }
}

private val LABELS = mapOf(
    "Original" to com.vknxworks.lumenctrl.R.string.preset_original,
    "Clean" to com.vknxworks.lumenctrl.R.string.preset_clean,
    "Vivid" to com.vknxworks.lumenctrl.R.string.preset_vivid,
    "Matte" to com.vknxworks.lumenctrl.R.string.preset_matte,
    "Warm film" to com.vknxworks.lumenctrl.R.string.preset_warm_film,
    "Teal & amber" to com.vknxworks.lumenctrl.R.string.preset_teal_amber,
    "Cool fade" to com.vknxworks.lumenctrl.R.string.preset_cool_fade,
    "Moody" to com.vknxworks.lumenctrl.R.string.preset_moody,
    "Dreamy" to com.vknxworks.lumenctrl.R.string.preset_dreamy,
    "Portrait" to com.vknxworks.lumenctrl.R.string.preset_portrait,
    "B&W" to com.vknxworks.lumenctrl.R.string.preset_bw,
    "B&W soft" to com.vknxworks.lumenctrl.R.string.preset_bw_soft,
)
