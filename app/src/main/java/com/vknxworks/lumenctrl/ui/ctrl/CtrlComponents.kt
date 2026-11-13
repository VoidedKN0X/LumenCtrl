package com.vknxworks.lumenctrl.ui.ctrl

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

val SquircleSm = RoundedCornerShape(14.dp)   // 44dp buttons
val SquircleMd = RoundedCornerShape(16.dp)   // nav items
val CardShape = RoundedCornerShape(22.dp)    // cards, nav pill

/** "LUMEN/CTRL" in mono, with the slash in the accent color. */
@Composable
fun Wordmark(name: String, modifier: Modifier = Modifier) {
    val c = Ctrl.colors
    Text(
        buildAnnotatedString {
            append(name.uppercase())
            withStyle(SpanStyle(color = c.accentText)) { append("/") }
            append("CTRL")
        },
        style = CtrlType.wordmark, color = c.text, modifier = modifier,
    )
}

@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = Ctrl.colors.muted) {
    Text(text.uppercase(), style = CtrlType.label, color = color, modifier = modifier)
}

enum class CtrlButtonStyle { Bordered, Ghost, Accent }

/** 44dp squircle icon button: bordered (raised), ghost (flat) or accent (primary action). */
@Composable
fun CtrlIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CtrlButtonStyle = CtrlButtonStyle.Bordered,
    enabled: Boolean = true,
    size: Int = 44,
) {
    val c = Ctrl.colors
    val (bg, fg, border) = when (style) {
        CtrlButtonStyle.Bordered -> Triple(c.surface, c.text, BorderStroke(1.dp, c.borderStrong))
        CtrlButtonStyle.Ghost -> Triple(Color.Transparent, c.text, null)
        CtrlButtonStyle.Accent -> Triple(c.accent, c.onAccent, null)
    }
    Surface(
        onClick = onClick, enabled = enabled, shape = SquircleSm, color = bg, border = border,
        modifier = modifier.size(size.dp).semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (enabled) fg else c.dim.copy(alpha = 0.45f), modifier = Modifier.size(20.dp))
        }
    }
}

/** Text button with optional leading icon. */
@Composable
fun CtrlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: CtrlButtonStyle = CtrlButtonStyle.Bordered,
    selected: Boolean = false,
) {
    val c = Ctrl.colors
    val accent = style == CtrlButtonStyle.Accent || selected
    Surface(
        onClick = onClick, shape = SquircleSm,
        color = if (accent) c.accent else if (style == CtrlButtonStyle.Ghost) Color.Transparent else c.surface,
        border = if (accent || style == CtrlButtonStyle.Ghost) null else BorderStroke(1.dp, c.borderStrong),
        modifier = modifier.height(44.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val fg = if (accent) c.onAccent else c.text
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = CtrlType.button, color = fg)
        }
    }
}

/** Small square status dot (2dp radius), the series' "running" marker. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Int = 8) {
    Canvas(modifier.size(size.dp)) {
        drawRoundRect(color, cornerRadius = CornerRadius(2.dp.toPx()))
    }
}

/**
 * Ctrl slider: a 6dp rounded track like the series' meters, filled in accent
 * from the parameter's default to its value (so bipolar sliders fill from
 * the centre), with a slim bar thumb. Optional [trackBrush] draws a
 * gradient track instead (used for hue).
 */
@Composable
fun CtrlSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    origin: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    trackBrush: Brush? = null,
) {
    val c = Ctrl.colors
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val span = range.endInclusive - range.start
    fun frac(v: Float) = ((v - range.start) / span).coerceIn(0f, 1f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                setProgress { v -> change(v.coerceIn(range.start, range.endInclusive)); finished(); true }
            }
            .pointerInput(range) {
                val pad = 3.dp.toPx()
                fun at(x: Float) = range.start + ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * span
                detectTapGestures { change(at(it.x)); finished() }
            }
            .pointerInput(range) {
                val pad = 3.dp.toPx()
                fun at(x: Float) = range.start + ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * span
                detectHorizontalDragGestures(
                    onDragStart = { change(at(it.x)) },
                    onDragEnd = { finished() },
                    onDragCancel = { finished() },
                    onHorizontalDrag = { ch, _ -> ch.consume(); change(at(ch.position.x)) },
                )
            },
    ) {
        val pad = 3.dp.toPx()
        val w = size.width - 2 * pad
        val h = 6.dp.toPx()
        val y = (size.height - h) / 2
        val r = CornerRadius(h / 2)
        if (trackBrush != null) {
            drawRoundRect(trackBrush, Offset(pad, y), Size(w, h), r)
        } else {
            drawRoundRect(c.track, Offset(pad, y), Size(w, h), r)
            val a = pad + w * frac(origin)
            val b = pad + w * frac(value)
            if (b != a) drawRoundRect(c.accent, Offset(minOf(a, b), y), Size(kotlin.math.abs(b - a), h), r)
        }
        val tw = 4.dp.toPx()
        val th = 22.dp.toPx()
        val tx = pad + w * frac(value) - tw / 2
        drawRoundRect(c.text, Offset(tx, (size.height - th) / 2), Size(tw, th), CornerRadius(2.dp.toPx()))
    }
}

data class NavItem(val label: String, val icon: ImageVector)

/** Floating bottom nav: the active item is an accent pill with its label, the rest are icons. */
@Composable
fun CtrlNavPill(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ctrl.colors
    Surface(
        shape = CardShape, color = c.navBg, border = BorderStroke(1.dp, c.borderStrong),
        modifier = modifier.fillMaxWidth().height(64.dp),
    ) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, item ->
                if (i == selected) {
                    Surface(onClick = { onSelect(i) }, shape = SquircleMd, color = c.accent, modifier = Modifier.height(48.dp)) {
                        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(item.icon, null, tint = c.onAccent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(item.label, style = CtrlType.button, color = c.onAccent)
                        }
                    }
                } else {
                    Surface(
                        onClick = { onSelect(i) }, shape = SquircleMd, color = Color.Transparent,
                        modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = item.label },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(item.icon, null, tint = c.muted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Compact 36dp chip for filters and sub-tabs; selected = accent fill. */
@Composable
fun CtrlChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    swatch: Color? = null,
) {
    val c = Ctrl.colors
    Surface(
        onClick = onClick, shape = RoundedCornerShape(12.dp),
        color = if (selected) c.accent else c.surface,
        border = if (selected) null else BorderStroke(1.dp, c.border),
        modifier = modifier.height(36.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val fg = if (selected) c.onAccent else c.text
            if (swatch != null) { StatusDot(swatch, size = 10); Spacer(Modifier.width(8.dp)) }
            if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(text, style = CtrlType.button.copy(fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp)), color = fg)
        }
    }
}

/** Label + mono value row over a [CtrlSlider]; double-tap the label row to reset. */
@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    origin: Float,
    changed: Boolean,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    trackBrush: Brush? = null,
) {
    val c = Ctrl.colors
    androidx.compose.foundation.layout.Column(modifier.padding(horizontal = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .pointerInput(label) { detectTapGestures(onDoubleTap = { onReset() }) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = CtrlType.body.copy(fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.text)
            Spacer(Modifier.weight(1f))
            Text(valueText, style = CtrlType.value, color = if (changed) c.accentText else c.muted)
        }
        CtrlSlider(value, range, origin, onChange, onDone, trackBrush = trackBrush)
    }
}

/** The series toggle from the Profile screen: 52x32 squircle track, 24dp knob. */
@Composable
fun CtrlToggle(checked: Boolean, onChange: (Boolean) -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    val c = Ctrl.colors
    Surface(
        onClick = { onChange(!checked) }, shape = RoundedCornerShape(10.dp),
        color = if (checked) c.accent else c.track,
        modifier = modifier.size(52.dp, 32.dp).semantics {
            this.contentDescription = contentDescription
            this.stateDescription = if (checked) "On" else "Off"
        },
    ) {
        Box(Modifier.padding(4.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(24.dp).background(if (checked) c.onAccent else c.dim, RoundedCornerShape(7.dp)))
        }
    }
}

/** A settings-style row: title, optional subtitle, trailing content. */
@Composable
fun CtrlRow(title: String, subtitle: String? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val c = Ctrl.colors
    Row(modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = CtrlType.body, color = c.text)
            if (subtitle != null) Text(subtitle, style = CtrlType.value.copy(fontFamily = SpaceGrotesk), color = c.muted)
        }
        trailing()
    }
}

/** Single-line text input in a squircle surface. */
@Composable
fun CtrlTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
) {
    val c = Ctrl.colors
    Surface(shape = SquircleSm, color = c.surface, border = BorderStroke(1.dp, c.borderStrong), modifier = modifier.height(48.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { Icon(leading, null, tint = c.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = CtrlType.body, color = c.muted)
                androidx.compose.foundation.text.BasicTextField(
                    value = value, onValueChange = onChange, singleLine = true,
                    textStyle = CtrlType.body.copy(color = c.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Bottom sheet in series colors. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CtrlSheet(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val c = Ctrl.colors
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.bg,
        contentColor = c.text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp).size(40.dp, 4.dp).background(c.borderStrong, RoundedCornerShape(2.dp))) },
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MonoLabel(title, Modifier.padding(top = 8.dp))
            content()
        }
    }
}

/** Dropdown menu in series colors. */
@Composable
fun CtrlMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val c = Ctrl.colors
    androidx.compose.material3.DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        containerColor = c.surface, shape = SquircleMd, border = BorderStroke(1.dp, c.borderStrong),
        content = content,
    )
}

@Composable
fun CtrlMenuItem(text: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Ctrl.colors
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(text, style = CtrlType.body, color = if (enabled) c.text else c.dim) },
        leadingIcon = icon?.let { { Icon(it, null, tint = if (enabled) c.text else c.dim, modifier = Modifier.size(20.dp)) } },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Snackbar in the series style: surface card, outline, mono text. */
@Composable
fun CtrlSnackbar(data: SnackbarData) {
    val c = Ctrl.colors
    Surface(
        shape = SquircleMd, color = c.surface, border = BorderStroke(1.dp, c.borderStrong),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(c.accent)
            Spacer(Modifier.width(12.dp))
            Text(data.visuals.message, style = CtrlType.value, color = c.text)
        }
    }
}
