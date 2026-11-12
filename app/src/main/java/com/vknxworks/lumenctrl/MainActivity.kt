package com.vknxworks.lumenctrl

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.vknxworks.lumenctrl.ui.CollageScreen
import com.vknxworks.lumenctrl.ui.LOCK_AUTHENTICATORS
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

/** FragmentActivity (a ComponentActivity) because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private val vm: LumenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyPrivacyFlags()
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            CtrlTheme(vm.settings.accent) {
                LaunchedEffect(vm.settings.hideInScreenshots, vm.settings.hideInRecents) { applyPrivacyFlags() }
                LumenApp(vm, onUnlock = ::authenticate)
            }
        }
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
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.import(uris)
    }

    private fun applyPrivacyFlags() {
        val s = vm.settings
        // FLAG_SECURE blocks screenshots/recording and also blanks the recents preview.
        if (s.hideInScreenshots || (s.hideInRecents && Build.VERSION.SDK_INT < 33)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!s.hideInRecents)
    }

    private fun authenticate() {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { vm.unlocked = true }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock LumenCtrl")
                .setAllowedAuthenticators(LOCK_AUTHENTICATORS)
                .build()
        )
    }
}

@Composable
private fun LumenApp(vm: LumenViewModel, onUnlock: () -> Unit) {
    val c = Ctrl.colors
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }
    LaunchedEffect(vm.shareRequest) {
        val uris = vm.shareRequest ?: return@LaunchedEffect
        vm.shareRequest = null
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = "image/*"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share"))
    }

    if (!vm.unlocked && vm.settings.appLock) {
        LockScreen(onUnlock)
        return
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

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val c = Ctrl.colors
    LaunchedEffect(Unit) { onUnlock() }
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Wordmark("Lumen")
            Text("Locked", style = CtrlType.display, color = c.text)
            MonoLabel("Your library stays private")
            CtrlButton("Unlock", onClick = onUnlock, icon = Icons.Outlined.Lock, style = CtrlButtonStyle.Accent)
        }
    }
}
