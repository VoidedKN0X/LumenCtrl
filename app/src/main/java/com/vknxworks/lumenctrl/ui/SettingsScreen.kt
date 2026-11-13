package com.vknxworks.lumenctrl.ui

import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.ui.ctrl.ACCENTS
import com.vknxworks.lumenctrl.ui.ctrl.CardShape
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlChip
import com.vknxworks.lumenctrl.ui.ctrl.CtrlIconButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlRow
import com.vknxworks.lumenctrl.ui.ctrl.CtrlToggle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlType
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel

/** Settings, laid out like the series' Profile & settings screen. */
@Composable
fun SettingsScreen(vm: LumenViewModel) {
    val c = Ctrl.colors
    val s = vm.settings
    val context = LocalContext.current
    BackHandler { vm.backToLibrary() }
    val canLock = BiometricManager.from(context).canAuthenticate(LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    val storage by produceState(0L) { value = vm.storageBytes() }

    Column(
        Modifier.fillMaxSize().background(c.bg).systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CtrlIconButton(Icons.Outlined.Close, "Close settings", vm::backToLibrary)
            Spacer(Modifier.weight(1f))
            MonoLabel("Settings")
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }

        Section("Privacy") {
            CtrlRow("App lock", if (canLock) "Fingerprint, face or screen lock on open" else "Set a screen lock in Android settings first") {
                CtrlToggle(s.appLock, { if (canLock || !it) s.updateAppLock(it) }, "App lock")
            }
            Divider()
            CtrlRow("Hide in screenshots", "Blocks screenshots and screen recording") {
                CtrlToggle(s.hideInScreenshots, s::updateHideInScreenshots, "Hide in screenshots")
            }
            Divider()
            CtrlRow("Hide in recent apps", "Blanks the app switcher preview") {
                CtrlToggle(s.hideInRecents, s::updateHideInRecents, "Hide in recent apps")
            }
        }

        Section("Look") {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ACCENTS.forEachIndexed { i, a -> CtrlChip(a.name, s.accent == i, onClick = { s.updateAccent(i) }, swatch = a.dark) }
            }
        }

        Section("Storage") {
            CtrlRow("Library", "${vm.allPhotos.size} photos · ${"%.1f".format(storage / 1e6)} MB in private app storage")
            Divider()
            CtrlRow("Exports", "Pictures/Lumen · location removed by default")
        }

        Section("About") {
            CtrlRow("Network", "None. LumenCtrl has no internet permission; Android blocks all network access.")
            Divider()
            CtrlRow("Accounts", "None. No sign-in, no cloud, no Adobe or Google services.")
            Divider()
            CtrlRow("AI masks", "DeepLab v3 and Selfie Multiclass (MediaPipe, Apache 2.0) on TensorFlow Lite. Bundled; runs on this phone.")
            Divider()
            CtrlRow("AI Denoise", "NAFNet and U-Net models from Raspberry Pi's AI_denoise (BSD-2-Clause, © 2025 Raspberry Pi Ltd). Bundled; runs on this phone.")
            Divider()
            CtrlRow("Fonts", "Space Grotesk and JetBrains Mono, SIL Open Font License")
            Divider()
            CtrlRow("Licence", "GPL-3.0 · source at github.com/VoidedKN0X/LumenCtrl")
            Divider()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
            CtrlRow("Version", "LumenCtrl $version · VKNXWorks Ctrl series")
        }

        Section("Quick guide") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Library: long-press to select · batch rate, flag, album, paste, export, collage",
                    "Editor: pinch or double-tap to zoom · hold the compare button for the original",
                    "Sliders: double-tap a label to reset",
                    "Masks: drag handles on the photo · tap to sample luminance or color ranges",
                    "Heal: tap a blemish · drag circles to move source or target",
                    "Share any image into Lumen from other apps",
                ).forEach { Text(it, style = CtrlType.value.copy(fontFamily = com.vknxworks.lumenctrl.ui.ctrl.SpaceGrotesk), color = c.muted) }
            }
        }
        MonoLabel("Offline · telemetry-free · on-device GPU", Modifier.fillMaxWidth().padding(bottom = 24.dp))
    }
}

const val LOCK_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = Ctrl.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MonoLabel(title, Modifier.padding(start = 4.dp))
        Surface(shape = CardShape, color = c.surface, border = BorderStroke(1.dp, c.border)) {
            Column { content() }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = Ctrl.colors.border, thickness = 1.dp)

@Suppress("unused")
private val transparent = Color.Transparent
