package com.vknxworks.lumenctrl.ui

import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.tr
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
            CtrlIconButton(Icons.Outlined.Close, tr(R.string.close_settings), vm::backToLibrary)
            Spacer(Modifier.weight(1f))
            MonoLabel(tr(R.string.settings))
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }

        Section(tr(R.string.privacy)) {
            CtrlRow(tr(R.string.app_lock), if (canLock) tr(R.string.fingerprint_face_or_screen_lock_on) else tr(R.string.set_a_screen_lock_in_android)) {
                CtrlToggle(s.appLock, { if (canLock || !it) s.updateAppLock(it) }, tr(R.string.app_lock))
            }
            Divider()
            CtrlRow(tr(R.string.hide_in_screenshots), tr(R.string.blocks_screenshots_and_screen_recording)) {
                CtrlToggle(s.hideInScreenshots, s::updateHideInScreenshots, tr(R.string.hide_in_screenshots))
            }
            Divider()
            CtrlRow(tr(R.string.hide_in_recent_apps), tr(R.string.blanks_the_app_switcher_preview)) {
                CtrlToggle(s.hideInRecents, s::updateHideInRecents, tr(R.string.hide_in_recent_apps))
            }
        }

        Section(tr(R.string.look)) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ACCENTS.forEachIndexed { i, a -> CtrlChip(a.name, s.accent == i, onClick = { s.updateAccent(i) }, swatch = a.dark) }
            }
        }

        Section(tr(R.string.storage)) {
            CtrlRow(tr(R.string.library), "${vm.allPhotos.size} photos · ${"%.1f".format(storage / 1e6)} MB in private app storage")
            Divider()
            CtrlRow(tr(R.string.exports), tr(R.string.pictures_lumen_location_removed_by_default))
        }

        Section(tr(R.string.about)) {
            CtrlRow(tr(R.string.network), tr(R.string.none_lumenctrl_has_no_internet_permission))
            Divider()
            CtrlRow(tr(R.string.accounts), tr(R.string.none_no_sign_in_no_cloud))
            Divider()
            CtrlRow(tr(R.string.ai_masks), tr(R.string.deeplab_v3_and_selfie_multiclass_mediapipe))
            Divider()
            CtrlRow(tr(R.string.ai_denoise), tr(R.string.nafnet_and_u_net_models_from))
            Divider()
            CtrlRow(tr(R.string.fonts), tr(R.string.space_grotesk_and_jetbrains_mono_sil))
            Divider()
            CtrlRow(tr(R.string.licence), tr(R.string.gpl_3_0_source_at_github))
            Divider()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
            CtrlRow(tr(R.string.version), tr(R.string.lumenctrl_x_vknxworks_ctrl_series, version))
        }

        Section(tr(R.string.quick_guide)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    tr(R.string.library_long_press_to_select_batch),
                    tr(R.string.editor_pinch_or_double_tap_to),
                    tr(R.string.sliders_double_tap_a_label_to),
                    tr(R.string.masks_drag_handles_on_the_photo),
                    tr(R.string.heal_tap_a_blemish_drag_circles),
                    tr(R.string.share_any_image_into_lumen_from),
                ).forEach { Text(it, style = CtrlType.value.copy(fontFamily = com.vknxworks.lumenctrl.ui.ctrl.SpaceGrotesk), color = c.muted) }
            }
        }
        MonoLabel(tr(R.string.offline_telemetry_free_on_device_gpu), Modifier.fillMaxWidth().padding(bottom = 24.dp))
    }
}

// Strong (Class 3) biometrics only, like the other Ctrl apps. A phone whose only biometric is a
// weaker one (most camera-only face unlock) falls back to the screen lock.
const val LOCK_AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

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
