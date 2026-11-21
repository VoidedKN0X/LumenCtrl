package com.vknxworks.lumenctrl

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import com.vknxworks.lumenctrl.ui.lock.AppLock
import com.vknxworks.lumenctrl.ui.lock.AppLockGate
import com.vknxworks.lumenctrl.ui.lock.GuardWindow
import com.vknxworks.lumenctrl.ui.lock.LocalLockHost
import com.vknxworks.lumenctrl.ui.lock.lockCovering
import com.vknxworks.lumenctrl.ui.CollageScreen
import com.vknxworks.lumenctrl.ui.LibraryScreen
import com.vknxworks.lumenctrl.ui.SettingsScreen
import com.vknxworks.lumenctrl.ui.SlideshowScreen
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlSnackbar
import com.vknxworks.lumenctrl.ui.ctrl.CtrlTheme
import com.vknxworks.lumenctrl.ui.ctrl.CtrlType
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import com.vknxworks.lumenctrl.ui.ctrl.Wordmark
import com.vknxworks.lumenctrl.ui.editor.EditorScreen

class MainActivity : ComponentActivity() {
    private val vm: LumenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before anything else: the view model, the screens and the model classes take their text from here.
        AppStrings.init(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            CtrlTheme(vm.settings.accent) {
                val s = vm.settings
                val legacyShowing = vm.legacyLock && !vm.legacyUnlocked
                // "Hide in screenshots": always on while a lock or PIN screen shows.
                val secure = s.hideInScreenshots || lockCovering(vm.vault) || legacyShowing
                // "Hide in recent apps": always with the app lock on, so Recents never shows what's behind it.
                val blankRecents = s.hideInRecents || vm.vault.enabled || vm.legacyLock
                // No overlays over the lock and PIN screens, and their content hidden from accessibility misuse.
                GuardWindow(lockCovering(vm.vault) || legacyShowing, sensitive = true)
                LaunchedEffect(secure, blankRecents) { applyPrivacyFlags(secure, blankRecents) }
                CompositionLocalProvider(LocalLockHost provides vm) {
                    if (legacyShowing) LegacyLockScreen(onUnlock = ::legacyUnlock)
                    // Nothing of the app is shown while the app lock is locked.
                    else AppLockGate { LumenApp(vm) }
                }
            }
        }
    }

    // The app lock counts time away from the app; trips LumenCtrl itself starts (pickers, camera, share sheet)
    // don't count as leaving (see AppLock.expectReturn).
    override fun onStart() {
        super.onStart()
        AppLock.onForeground(vm.vault.enabled, vm.settings.lockTimeout.millis)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppLock.onBackground(vm.vault.enabled, vm.settings.lockTimeout.millis)
    }

    @Deprecated("Activity Result API launchers end up here")
    @Suppress("DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        AppLock.expectReturn()
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() { super.onStart(); vm.onAppStart() }
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) vm.onAppStop() }

    /** "Share to LumenCtrl" / "Open with": imports the images into the private library. */
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        // A camera was plugged in and the user chose LumenCtrl: go straight to camera import.
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            if (vm.screen != Screen.EDITOR) vm.openCameraImport()
            return
        }
        // "Edit in LumenCtrl" from a gallery app: copy it into the library and open it in the editor.
        if (intent.action == Intent.ACTION_EDIT) {
            val uri = intent.data ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri != null) vm.editWhenUnlocked(uri)
            return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        // With the app lock showing, the images wait and are imported after unlocking.
        if (uris.isNotEmpty()) vm.importWhenUnlocked(uris)
    }

    private fun applyPrivacyFlags(secure: Boolean, blankRecents: Boolean) {
        // FLAG_SECURE blocks screenshots/recording; the recents preview has its own switch.
        if (secure) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(!blankRecents)
    }

    /** The old lock, once, to move to the app PIN: strong biometrics or the phone's screen lock, as before. */
    private fun legacyUnlock() {
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.unlock_title))
            .setSubtitle(getString(R.string.lock_upgrade_unlock))
            .setAllowedAuthenticators(
                android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        runCatching {
            prompt.authenticate(
                android.os.CancellationSignal(), mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                        vm.onLegacyUnlocked()
                    }
                },
            )
        }
    }
}

@Composable
private fun LumenApp(vm: LumenViewModel) {
    val c = Ctrl.colors
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    val shareTitle = stringResource(R.string.share_title)
    LaunchedEffect(vm.shareRequest) {
        val uris = vm.shareRequest ?: return@LaunchedEffect
        vm.shareRequest = null
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = "image/*"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        AppLock.expectReturn()
        context.startActivity(Intent.createChooser(send, shareTitle))
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        when (vm.screen) {
            Screen.EDITOR -> if (vm.openPhoto != null) EditorScreen(vm) else LibraryScreen(vm)
            Screen.SLIDESHOW -> SlideshowScreen(vm)
            Screen.COLLAGE -> CollageScreen(vm)
            Screen.SETTINGS -> SettingsScreen(vm)
            Screen.CAMERA -> com.vknxworks.lumenctrl.ui.CameraImportScreen(vm)
            Screen.VIDEO -> if (vm.openPhoto != null) com.vknxworks.lumenctrl.ui.editor.VideoEditorScreen(vm) else LibraryScreen(vm)
            Screen.LIBRARY -> LibraryScreen(vm)
        }
        BackHandler(enabled = vm.screen == Screen.EDITOR) { vm.close() }
        BackHandler(enabled = vm.screen == Screen.LIBRARY && vm.selection.isNotEmpty()) { vm.clearSelection() }

        vm.busy?.let { label ->
            Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(36.dp), color = c.accent, strokeWidth = 2.dp, trackColor = c.track)
                    MonoLabel(label, color = c.text)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp)) { CtrlSnackbar(it) }
    }
}

/** The old lock's screen, shown once after the update to people who had it on (see LumenViewModel.legacyLock). */
@Composable
private fun LegacyLockScreen(onUnlock: () -> Unit) {
    val c = Ctrl.colors
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Wordmark("Lumen")
            Text(tr(R.string.locked), style = CtrlType.display, color = c.text)
            MonoLabel(tr(R.string.lock_upgrade_unlock))
            CtrlButton(tr(R.string.unlock), onClick = onUnlock, icon = Icons.Outlined.Lock, style = CtrlButtonStyle.Accent)
        }
    }
}
