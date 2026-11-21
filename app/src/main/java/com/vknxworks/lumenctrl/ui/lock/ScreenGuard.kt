package com.vknxworks.lumenctrl.ui.lock

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * Protects a window while a PIN, lock or confirmation screen is on it:
 * - other apps' floating windows (chat heads, overlays) are hidden over it (HIDE_OVERLAY_WINDOWS), so
 *   nothing can cover or imitate the screen while you type a PIN or confirm a destructive action;
 * - for the lock and PIN screens only ([sensitive]), its content is marked as sensitive for accessibility
 *   (Android 14+), so only real accessibility tools such as screen readers can read it, not apps that misuse the
 *   accessibility API. Sign-in, password and token fields are never marked, so password managers that fill
 *   through accessibility keep working.
 *
 * Counted per window, so nested screens (a confirmation dialog over a PIN screen) keep it on until the last
 * one closes. Everywhere else, overlays and accessibility services work as usual.
 */
object ScreenGuard {
    private val overlays = WeakHashMap<Window, Int>()
    private val sensitive = WeakHashMap<Window, Int>()

    fun acquire(window: Window, sensitive: Boolean) {
        if (count(overlays, window, +1) == 1) runCatching { window.setHideOverlayWindows(true) }
        if (sensitive && count(this.sensitive, window, +1) == 1) markSensitive(window, true)
    }

    fun release(window: Window, sensitive: Boolean) {
        if (count(overlays, window, -1) == 0) runCatching { window.setHideOverlayWindows(false) }
        if (sensitive && count(this.sensitive, window, -1) == 0) markSensitive(window, false)
    }

    /** New count for [window] after adding [delta]; -1 when it wasn't counted. */
    private fun count(map: WeakHashMap<Window, Int>, window: Window, delta: Int): Int {
        val old = map[window] ?: if (delta < 0) return -1 else 0
        val n = old + delta
        if (n <= 0) map.remove(window) else map[window] = n
        return n.coerceAtLeast(0)
    }

    private fun markSensitive(window: Window, on: Boolean) {
        runCatching {
            window.decorView.setAccessibilityDataSensitive(
                if (on) View.ACCESSIBILITY_DATA_SENSITIVE_YES else View.ACCESSIBILITY_DATA_SENSITIVE_AUTO,
            )
        }
    }
}

/**
 * Applies [ScreenGuard] to the window this composable is shown in (the activity, or a dialog) while [active].
 * [sensitive] is for the lock and PIN screens only.
 */
@Composable
fun GuardWindow(active: Boolean = true, sensitive: Boolean = false) {
    val view = LocalView.current
    DisposableEffect(view, active, sensitive) {
        val window = if (active) view.hostWindow() else null
        if (window != null) ScreenGuard.acquire(window, sensitive)
        onDispose { if (window != null) ScreenGuard.release(window, sensitive) }
    }
}

private fun View.hostWindow(): Window? =
    (parent as? DialogWindowProvider)?.window ?: context.guardActivity()?.window

private tailrec fun Context.guardActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.guardActivity()
    else -> null
}
