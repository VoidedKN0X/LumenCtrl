package com.vknxworks.lumenctrl.ui

import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.tr
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlMenu
import com.vknxworks.lumenctrl.ui.ctrl.CtrlMenuItem
import com.vknxworks.lumenctrl.ui.lock.AppLock
import com.vknxworks.lumenctrl.ui.lock.PinRequest
import com.vknxworks.lumenctrl.ui.lock.findActivity
import com.vknxworks.lumenctrl.data.lock.LockVault
import com.vknxworks.lumenctrl.data.LockTimeout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
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
    val bioAvailable = remember { com.vknxworks.lumenctrl.ui.lock.AppLock.biometricAvailable(context) }
    var timeoutMenu by remember { mutableStateOf(false) }
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
            // The shared Ctrl series app lock: an app PIN, optionally the fingerprint.
            val vault = vm.vault
            val lockOn = vault.enabled
            CtrlRow(tr(R.string.app_lock), tr(R.string.app_lock_pin_subtitle)) {
                CtrlToggle(lockOn, { on -> AppLock.request = if (on) PinRequest.ENABLE else PinRequest.DISABLE }, tr(R.string.app_lock))
            }
            if (lockOn) {
                Divider()
                CtrlRow(
                    tr(R.string.lock_change_pin), tr(R.string.lock_change_pin_subtitle, LockVault.PIN_MIN),
                    Modifier.clickable(role = Role.Button) { AppLock.request = PinRequest.CHANGE },
                )
                Divider()
                CtrlRow(
                    tr(R.string.lock_fingerprint),
                    if (bioAvailable || vault.biometricEnabled) tr(R.string.lock_fingerprint_subtitle) else tr(R.string.lock_fingerprint_unavailable),
                ) {
                    CtrlToggle(vault.biometricEnabled, { on ->
                        if (!on) vault.disableBiometric()
                        else if (bioAvailable) {
                            val activity = context.findActivity()
                            val cipher = vault.biometricEnrolCipher()
                            if (activity == null || cipher == null) vm.toast(tr(R.string.lock_fingerprint_setup_failed))
                            else AppLock.biometricPrompt(
                                activity, tr(R.string.lock_fingerprint_enable_title), tr(R.string.lock_fingerprint_enable_subtitle), cipher,
                                onError = { err -> if (err != null) vm.toast(err) },
                            ) { unlocked -> if (!vault.finishBiometricEnrol(unlocked)) vm.toast(tr(R.string.lock_fingerprint_setup_failed)) }
                        }
                    }, tr(R.string.lock_fingerprint))
                }
                Divider()
                Box {
                    CtrlRow(tr(R.string.lock_again), tr(s.lockTimeout.label), Modifier.clickable(role = Role.Button) { timeoutMenu = true })
                    CtrlMenu(timeoutMenu, { timeoutMenu = false }) {
                        LockTimeout.entries.forEach { t ->
                            CtrlMenuItem(tr(t.label) + if (t == s.lockTimeout) "  ✓" else "") { s.updateLockTimeout(t); timeoutMenu = false }
                        }
                    }
                }
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

        val exportLib = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) vm.exportLibrary(uri)
        }
        val importLib = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.inspectLibrary(uri)
        }
        val exportSet = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) vm.exportSettings(uri)
        }
        val importSet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.inspectSettings(uri)
        }
        Section(tr(R.string.settings_file)) {
            CtrlRow(tr(R.string.export_settings), tr(R.string.export_settings_subtitle)) {
                CtrlButton(tr(R.string.export), onClick = { exportSet.launch("LumenCtrl-settings.json") })
            }
            Divider()
            CtrlRow(tr(R.string.import_settings), tr(R.string.import_settings_subtitle)) {
                CtrlButton(tr(R.string.import_), onClick = { importSet.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
            }
        }
        vm.pendingSettings?.let { incoming ->
            val changes = s.changesFrom(incoming, { ACCENTS.getOrNull(it)?.name ?: "?" }, { if (it) tr(R.string.on) else tr(R.string.off) })
            ConfirmDialog(
                tr(R.string.import_settings_q),
                tr(R.string.import_settings_replaces) + "\n\n" + changes.joinToString("\n") { "${it.label}: ${it.from} → ${it.to}" },
                tr(R.string.import_),
                onConfirm = vm::applyPendingSettings,
                onDismiss = vm::cancelPendingSettings,
            )
        }

        Section(tr(R.string.library_backup)) {
            CtrlRow(tr(R.string.export_library), tr(R.string.export_library_subtitle)) {
                CtrlButton(tr(R.string.export), onClick = {
                    exportLib.launch("LumenCtrl-library-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip")
                })
            }
            Divider()
            CtrlRow(tr(R.string.import_library), tr(R.string.import_library_subtitle)) {
                CtrlButton(tr(R.string.import_), onClick = { importLib.launch(arrayOf("application/zip", "application/octet-stream")) })
            }
        }
        vm.pendingImport?.let { (_, sum) ->
            ConfirmDialog(
                tr(R.string.import_library_q),
                tr(R.string.import_library_summary, sum.photos - sum.alreadyHere, sum.alreadyHere, sum.albums, sum.presets, sum.luts, "%.1f".format(sum.bytes / 1e6)),
                tr(R.string.import_),
                onConfirm = vm::importLibrary,
                onDismiss = vm::cancelImport,
            )
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
