package com.vknxworks.lumenctrl.model

import com.vknxworks.lumenctrl.model.Param.*

data class Preset(val name: String, val values: Map<Param, Float>) {
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
    )
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
