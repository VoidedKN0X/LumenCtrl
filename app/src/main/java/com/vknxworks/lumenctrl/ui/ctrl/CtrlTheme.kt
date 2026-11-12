package com.vknxworks.lumenctrl.ui.ctrl

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vknxworks.lumenctrl.R

/**
 * Tokens from the "Ctrl Series Redesign" canvas: Ctrl / Signal (dark) and
 * Ctrl / Signal Light (follows the device theme).
 */
@Immutable
data class CtrlPalette(
    val isDark: Boolean,
    val bg: Color,          // screen ground
    val surface: Color,     // cards, raised buttons
    val border: Color,      // card outline
    val borderStrong: Color,// button and nav outline
    val divider: Color,     // list row rules
    val track: Color,       // progress and slider track
    val text: Color,
    val muted: Color,
    val dim: Color,         // inactive states
    val accent: Color,      // fills
    val onAccent: Color,
    val accentText: Color,  // accent used as text on bg (darker in light mode for contrast)
    val navBg: Color,       // floating nav, 92% opaque surface
)

val SignalDark = CtrlPalette(
    isDark = true,
    bg = Color(0xFF08080A), surface = Color(0xFF141417),
    border = Color(0xFF232328), borderStrong = Color(0xFF2A2A2F),
    divider = Color(0xFF1E1E23), track = Color(0xFF26262C),
    text = Color(0xFFF4F4F2), muted = Color(0xFFA3A3AB), dim = Color(0xFF8C8C94),
    accent = Color(0xFFB48CFF), onAccent = Color(0xFF08080A), accentText = Color(0xFFB48CFF),
    navBg = Color(0xEB141417),
)

val SignalLight = CtrlPalette(
    isDark = false,
    bg = Color(0xFFF5F5F1), surface = Color(0xFFFFFFFF),
    border = Color(0xFFDEDED7), borderStrong = Color(0xFFD2D2CA),
    divider = Color(0xFFE6E6E0), track = Color(0xFFE2E2DC),
    text = Color(0xFF0B0B0D), muted = Color(0xFF5C5C64), dim = Color(0xFF74747C),
    accent = Color(0xFFB48CFF), onAccent = Color(0xFF08080A), accentText = Color(0xFF6A3BDB),
    navBg = Color(0xEBFFFFFF),
)

val LocalCtrl = staticCompositionLocalOf { SignalDark }

object Ctrl {
    val colors: CtrlPalette @Composable get() = LocalCtrl.current
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) =
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk, 400),
    variable(R.font.space_grotesk, 500),
    variable(R.font.space_grotesk, 600),
    variable(R.font.space_grotesk, 700),
)

val JetBrainsMono = FontFamily(
    variable(R.font.jetbrains_mono, 400),
    variable(R.font.jetbrains_mono, 500),
    variable(R.font.jetbrains_mono, 600),
)

/** Named text styles used across the series. */
object CtrlType {
    /** 11sp uppercase mono with wide tracking, e.g. "NODE · ONLINE". */
    val label = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 0.16.em, fontWeight = FontWeight.W500)
    /** Mono values next to rows, e.g. "+0.30 EV". */
    val value = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, fontWeight = FontWeight.W500)
    /** Wordmark, e.g. "PROX/CTRL". */
    val wordmark = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, letterSpacing = 0.14.em, fontWeight = FontWeight.W600)
    /** Screen headline, e.g. "VKLab". */
    val display = TextStyle(fontFamily = SpaceGrotesk, fontSize = 56.sp, lineHeight = 53.sp, letterSpacing = (-0.04).em, fontWeight = FontWeight.W700)
    val title = TextStyle(fontFamily = SpaceGrotesk, fontSize = 18.sp, fontWeight = FontWeight.W600)
    val body = TextStyle(fontFamily = SpaceGrotesk, fontSize = 16.sp, fontWeight = FontWeight.W500)
    val button = TextStyle(fontFamily = SpaceGrotesk, fontSize = 14.sp, fontWeight = FontWeight.W600)
}

private fun Typography.withFamily(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f), displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f), headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f), headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f), titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f), bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f), bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f), labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

/** The accent options from the canvas's accent tweak. */
data class Accent(val name: String, val dark: Color, val lightFill: Color, val lightOn: Color, val lightText: Color)

val ACCENTS = listOf(
    Accent("Lavender", Color(0xFFB48CFF), Color(0xFFB48CFF), Color(0xFF08080A), Color(0xFF6A3BDB)),
    Accent("Ember", Color(0xFFFF6A2B), Color(0xFFFF6A2B), Color(0xFF08080A), Color(0xFFB8400F)),
    Accent("Sky", Color(0xFF5CC8FF), Color(0xFF5CC8FF), Color(0xFF08080A), Color(0xFF0A6E9E)),
    Accent("Chalk", Color(0xFFF4F4F2), Color(0xFF0B0B0D), Color(0xFFF5F5F1), Color(0xFF0B0B0D)),
)

@Composable
fun CtrlTheme(accentIndex: Int = 0, content: @Composable () -> Unit) {
    val a = ACCENTS.getOrElse(accentIndex) { ACCENTS[0] }
    val p = if (isSystemInDarkTheme()) SignalDark.copy(accent = a.dark, accentText = a.dark)
    else SignalLight.copy(accent = a.lightFill, onAccent = a.lightOn, accentText = a.lightText)
    val scheme = if (p.isDark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.text,
            surface = p.surface, onSurface = p.text, onSurfaceVariant = p.muted, outline = p.borderStrong,
            surfaceContainerHigh = p.surface, surfaceContainer = p.surface,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, background = p.bg, onBackground = p.text,
            surface = p.surface, onSurface = p.text, onSurfaceVariant = p.muted, outline = p.borderStrong,
            surfaceContainerHigh = p.surface, surfaceContainer = p.surface,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = Typography().withFamily(SpaceGrotesk)) {
        CompositionLocalProvider(LocalCtrl provides p, LocalContentColor provides p.text, content = content)
    }
}
