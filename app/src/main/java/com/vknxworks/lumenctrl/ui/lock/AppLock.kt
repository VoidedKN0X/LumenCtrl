package com.vknxworks.lumenctrl.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.data.lock.LockVault
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.SpaceGrotesk
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.vknxworks.lumenctrl.ui.ctrl.CtrlType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.math.roundToInt

/**
 * App lock state (the shared Ctrl series lock): whether the app is unlocked, and when it locks again.
 * The PIN and fingerprint keys live in [LockVault]. LumenCtrl has no servers or passwords, so the lock
 * keeps the app (your library) closed until it's unlocked. Copied from SumCtrl, the series' reference.
 */
object AppLock {
    var unlocked by mutableStateOf(false)
        private set

    /** Goes up every time the app locks, so the lock screen offers the fingerprint once per lock. */
    var lockCount by mutableIntStateOf(0)
        private set

    /** A PIN screen shown on top of the app: turning the lock on or off, or changing the PIN. */
    var request by mutableStateOf<PinRequest?>(null)

    /** Set by the app: wipes the vault key from memory. */
    var onLocked: () -> Unit = {}

    /**
     * Goes up every time you come back to the app after really leaving it (not for the fingerprint prompt
     * itself), so the lock screen offers the fingerprint again on every return, not just once per lock.
     */
    var returns by mutableIntStateOf(0)
        private set

    /** The fingerprint prompt that is open right now, if any. */
    private var promptSignal: CancellationSignal? = null
    private var promptStartedAt = 0L
    private var promptGeneration = 0

    /**
     * A fingerprint prompt is open. Some phones drop a prompt without ever answering (for example one asked
     * for while the app was still coming back on screen); such a prompt stops counting after a minute, and
     * asking again always replaces it, so it can never block fingerprint unlock until the app is closed.
     */
    private val authenticating: Boolean
        get() = promptSignal != null && SystemClock.elapsedRealtime() - promptStartedAt < PROMPT_STALE_MS

    private const val PROMPT_STALE_MS = 60_000L

    private var backgroundAt = 0L
    private var expectReturnAt = 0L

    /**
     * The app itself is opening another app (file picker, browser). That trip doesn't count as leaving, so you
     * don't have to unlock again when you come back.
     */
    fun expectReturn() {
        expectReturnAt = SystemClock.elapsedRealtime()
    }

    /** The current trip away was started by the app itself. */
    private var exemptTrip = false

    /**
     * A pop-up of the app that shows nothing from behind the lock is opening (SumCtrl's text-selection pop-up;
     * LumenCtrl has none and never calls this). It must not count as coming back: the time away keeps running.
     */
    private var sideTrip = false

    fun startSideTrip() {
        sideTrip = true
    }

    fun onBackground(enabled: Boolean, timeoutMillis: Long) {
        if (authenticating) return
        if (sideTrip) {
            sideTrip = false
            // Already away since leaving the app itself: keep that time.
            if (backgroundAt > 0) return
        }
        backgroundAt = SystemClock.elapsedRealtime()
        // Only right after the app opened something itself; leaving later still counts.
        exemptTrip = expectReturnAt > 0 && backgroundAt - expectReturnAt < 5_000
        expectReturnAt = 0L
        // "As soon as you leave" locks right away, so the key is gone while the app is in the background.
        if (enabled && unlocked && timeoutMillis == 0L && !exemptTrip) lock()
    }

    fun onForeground(enabled: Boolean, timeoutMillis: Long) {
        if (sideTrip) {
            // Only the pop-up came to the front: still lock once the time away has run out, but keep counting.
            val away = backgroundAt
            if (enabled && unlocked && away > 0 && timeoutMillis >= 0) {
                val limit = if (exemptTrip) maxOf(timeoutMillis, EXEMPT_TRIP_MAX) else timeoutMillis
                if (SystemClock.elapsedRealtime() - away >= limit) lock()
            }
            return
        }
        val since = backgroundAt
        val exempt = exemptTrip
        backgroundAt = 0L
        expectReturnAt = 0L
        exemptTrip = false
        if (since > 0) returns++
        if (!enabled || !unlocked || since <= 0 || timeoutMillis < 0) return
        // A trip the app started still locks if you stay away long: after 5 minutes, or the chosen time if longer.
        val limit = if (exempt) maxOf(timeoutMillis, EXEMPT_TRIP_MAX) else timeoutMillis
        if (SystemClock.elapsedRealtime() - since >= limit) lock()
    }

    private const val EXEMPT_TRIP_MAX = 5 * 60_000L

    fun lock() {
        unlocked = false
        lockCount++
        request = null
        onLocked()
    }

    fun markUnlocked() {
        backgroundAt = 0L
        unlocked = true
    }

    /** Only class 3 ("strong") biometrics: camera-only face unlock can be fooled by a photo. */
    fun biometricAvailable(context: Context): Boolean = runCatching {
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)

    /**
     * Shows the fingerprint prompt for [cipher], a Keystore key that only works after a strong match.
     * [onSuccess] gets the unlocked cipher; [onError] gets a message, or null when cancelled.
     */
    fun biometricPrompt(
        activity: Activity,
        title: String,
        subtitle: String,
        cipher: Cipher,
        onError: (String?) -> Unit = {},
        onSuccess: (Cipher) -> Unit,
    ) {
        // Replaces a prompt that is still marked as open; its late answer (if any) is ignored below.
        promptSignal?.let { runCatching { it.cancel() } }
        val generation = ++promptGeneration
        val signal = CancellationSignal()
        promptSignal = signal
        promptStartedAt = SystemClock.elapsedRealtime()
        // True once, and only for the newest prompt.
        fun finish(): Boolean {
            if (generation != promptGeneration || promptSignal !== signal) return false
            promptSignal = null
            return true
        }
        try {
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton(activity.getString(R.string.lock_use_pin), activity.mainExecutor) { _, _ ->
                    if (finish()) onError(null)
                }
                .build()
            prompt.authenticate(
                BiometricPrompt.CryptoObject(cipher),
                signal,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (!finish()) return
                        val c = result.cryptoObject?.cipher
                        if (c != null) onSuccess(c) else onError(activity.getString(R.string.lock_fingerprint_failed))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!finish()) return
                        val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        onError(if (cancelled) null else errString.toString())
                    }
                },
            )
        } catch (e: Exception) {
            if (finish()) onError(activity.getString(R.string.lock_fingerprint_failed))
        }
    }

    /** The phone has a screen lock (PIN, pattern or password) that can confirm it's the owner. */
    fun screenLockSet(context: Context): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }.getOrDefault(true)

    /**
     * The phone's own screen lock (or a strong fingerprint). Only used for "Forgot PIN?", so that resetting the
     * app lock isn't open to anyone who picks up the unlocked phone. [onResult] gets null when confirmed, or an
     * error message; cancelling calls [onCancel] instead.
     */
    fun screenLockAuthenticate(activity: Activity, onCancel: () -> Unit, onResult: (String?) -> Unit) {
        // Replaces a prompt that is still marked as open; its late answer (if any) is ignored below.
        promptSignal?.let { runCatching { it.cancel() } }
        val generation = ++promptGeneration
        val signal = CancellationSignal()
        promptSignal = signal
        promptStartedAt = SystemClock.elapsedRealtime()
        fun finish(): Boolean {
            if (generation != promptGeneration || promptSignal !== signal) return false
            promptSignal = null
            return true
        }
        try {
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.lock_reset_prompt_title))
                .setSubtitle(activity.getString(R.string.lock_reset_prompt_subtitle))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (finish()) onResult(null)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!finish()) return
                    val cancelled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    if (cancelled) onCancel() else onResult(errString.toString())
                }
            })
        } catch (e: Exception) {
            if (finish()) onResult(activity.getString(R.string.lock_reset_prompt_failed))
        }
    }
}

/**
 * Waits until the app is really back on screen: resumed, with its window focused. A fingerprint prompt asked
 * for earlier (while the app is still coming back from the background) is dropped by the system, on some
 * phones without any answer, which used to leave fingerprint unlock not working until the app was closed.
 */
suspend fun awaitOnScreen(lifecycle: Lifecycle, windowInfo: WindowInfo) {
    lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
    snapshotFlow { windowInfo.isWindowFocused }.first { it }
}

/** What a PIN screen on top of the app is for. */
enum class PinRequest { ENABLE, CHANGE, DISABLE, UPGRADE }

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Whether a lock or PIN screen is showing right now (the window is then always kept out of screenshots). */
fun lockCovering(vault: LockVault): Boolean = (vault.enabled && !AppLock.unlocked) || AppLock.request != null

/**
 * Shows [content] only while the app is unlocked (or the lock is off), with the PIN screens for
 * turning the lock on or off and changing the PIN on top of it.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val c = LocalLockHost.current
    val vault = c.vault
    val activity = LocalContext.current.findActivity()
    if (vault.enabled && !AppLock.unlocked) {
        // Locking as the app closes (Back on the first screen, swiped away) must not open anything on a window
        // that is going away: the app is locked all the same, and opens on the lock screen next time.
        if (activity == null || !(activity.isFinishing || activity.isDestroyed)) PinLockScreen()
        else Box(Modifier.fillMaxSize())
        return
    }
    val request = AppLock.request
    Box(Modifier.fillMaxSize()) {
        // Hidden from TalkBack while a PIN screen covers it.
        Box(if (request != null) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) { content() }
        when (request) {
            PinRequest.ENABLE -> PinSetupScreen(
                title = stringResource(R.string.lock_choose_pin),
                intro = stringResource(R.string.lock_enable_intro, LockVault.PIN_MIN),
                verifyCurrent = false,
                cancellable = true,
                onDone = { pin -> c.enableLock(pin); AppLock.request = null },
                onCancel = { AppLock.request = null },
            )
            PinRequest.CHANGE -> PinSetupScreen(
                title = stringResource(R.string.lock_change_pin),
                intro = stringResource(R.string.lock_change_intro, LockVault.PIN_MIN),
                verifyCurrent = true,
                cancellable = true,
                onDone = { pin -> withContext(Dispatchers.Default) { vault.changePin(pin) }; AppLock.request = null },
                onCancel = { AppLock.request = null },
            )
            // LumenCtrl only: moving from the old screen-lock lock to the PIN.
            PinRequest.UPGRADE -> PinSetupScreen(
                title = stringResource(R.string.lock_upgrade_title),
                intro = stringResource(R.string.lock_upgrade_intro, LockVault.PIN_MIN),
                verifyCurrent = false,
                cancellable = true,
                onDone = { pin -> c.enableLock(pin); AppLock.request = null },
                onCancel = { c.cancelUpgrade(); AppLock.request = null },
            )
            PinRequest.DISABLE -> PinSetupScreen(
                title = stringResource(R.string.lock_turn_off),
                intro = stringResource(R.string.lock_turn_off_intro),
                verifyCurrent = true,
                verifyOnly = true,
                cancellable = true,
                onDone = { c.disableLock(); AppLock.request = null },
                onCancel = { AppLock.request = null },
            )
            null -> Unit
        }
    }
}

/** Swallows every touch, so nothing underneath a lock screen can be used. */
@Composable
private fun Modifier.blockTouches(): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }

/** 72dp accent square with the lock, at the top of every lock and PIN screen. */
@Composable
private fun LockBadge() {
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(Ctrl.colors.accent),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Lock, null, Modifier.size(30.dp), tint = Ctrl.colors.onAccent) }
}

/** The lock screen: PIN pad, fingerprint, and "Forgot PIN?". */
@Composable
private fun PinLockScreen() {
    val context = LocalContext.current
    val c = LocalLockHost.current
    val vault = c.vault
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lockout by remember { mutableLongStateOf(vault.lockoutRemaining()) }
    var forgetting by remember { mutableStateOf(false) }
    val bioAvailable = remember(AppLock.lockCount, AppLock.returns) {
        vault.biometricEnabled && AppLock.biometricAvailable(context) && !vault.pinRequired()
    }

    LaunchedEffect(lockout > 0) {
        while (lockout > 0) {
            delay(500)
            lockout = vault.lockoutRemaining()
        }
    }

    fun askFingerprint() {
        val a = activity ?: return
        if (a.isFinishing || a.isDestroyed) return
        val cipher = vault.biometricUnlockCipher() ?: run {
            message = if (!vault.biometricEnabled) context.getString(R.string.lock_fingerprint_changed) else null
            return
        }
        AppLock.biometricPrompt(
            a,
            context.getString(R.string.lock_prompt_title),
            context.getString(R.string.lock_prompt_subtitle),
            cipher,
            onError = { message = it },
        ) { unlocked ->
            if (vault.unlockWithBiometric(unlocked)) c.onUnlocked() else message = context.getString(R.string.lock_fingerprint_failed)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowInfo = LocalWindowInfo.current
    // Offered on every lock and every time you come back to the lock screen, once the app is really on screen.
    LaunchedEffect(AppLock.lockCount, AppLock.returns) {
        if (!bioAvailable) return@LaunchedEffect
        awaitOnScreen(lifecycle, windowInfo)
        askFingerprint()
    }

    fun submit() {
        if (checking || lockout > 0 || pin.length < LockVault.PIN_MIN) return
        val entered = pin
        checking = true
        scope.launch {
            // Slow on purpose (it makes guessing slow too), so off the main thread.
            val result = withContext(Dispatchers.Default) { vault.unlockWithPin(entered) }
            checking = false
            pin = ""
            when (result) {
                LockVault.PinResult.Ok -> c.onUnlocked()
                LockVault.PinResult.Wrong, LockVault.PinResult.LockedOut -> {
                    lockout = vault.lockoutRemaining()
                    message = context.getString(R.string.lock_wrong_pin)
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(45))
                }
            }
        }
    }

    BackHandler { activity?.moveTaskToBack(true) }
    PinPadScreen(
        title = stringResource(R.string.lock_title),
        message = when {
            lockout > 0 -> stringResource(R.string.lock_too_many, ((lockout + 999) / 1000).toInt())
            message != null -> message!!
            vault.biometricEnabled && vault.pinRequired() -> stringResource(R.string.lock_pin_required)
            else -> stringResource(R.string.lock_enter_pin)
        },
        isError = lockout > 0 || message != null,
        pin = pin,
        shake = shake.value,
        checking = checking,
        enabled = lockout == 0L && !checking,
        onDigit = { d ->
            if (pin.length < LockVault.PIN_MAX) {
                message = null
                pin += d
                if (pin.length == vault.pinLength) submit()
            }
        },
        onBackspace = { pin = pin.dropLast(1) },
        onConfirm = ::submit,
        showConfirm = pin.length >= LockVault.PIN_MIN && pin.length != vault.pinLength,
        confirmLabel = stringResource(R.string.lock_ok),
        onBiometric = if (bioAvailable) ::askFingerprint else null,
        bottom = {
            TextButton(onClick = { forgetting = true }) { Text(stringResource(R.string.lock_forgot), color = Ctrl.colors.muted) }
        },
    )
    if (forgetting) {
        LockAlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(R.string.lock_forgot_title)) },
            text = { Text(stringResource(R.string.lock_forgot_body)) },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = false
                    val a = activity
                    if (!AppLock.screenLockSet(context)) {
                        // No screen lock on the phone to confirm with: reset as before.
                        scope.launch { c.forgetEverything() }
                    } else if (a != null && !a.isFinishing && !a.isDestroyed) {
                        // Only the phone's owner may reset it: confirm with the phone's own screen lock first.
                        AppLock.screenLockAuthenticate(a, onCancel = {}) { error ->
                            if (error == null) scope.launch { c.forgetEverything() } else message = error
                        }
                    }
                }) { Text(stringResource(R.string.lock_forgot_confirm), color = LockError) }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text(stringResource(R.string.cancel), color = Ctrl.colors.text) } },
            guard = true,
        )
    }
}

/** Choosing a PIN (twice), changing it (current, then new twice) or confirming it to turn the lock off. */
@Composable
private fun PinSetupScreen(
    title: String,
    intro: String,
    verifyCurrent: Boolean,
    cancellable: Boolean,
    onDone: suspend (String) -> Unit,
    onCancel: () -> Unit,
    verifyOnly: Boolean = false,
) {
    val context = LocalContext.current
    val vault = LocalLockHost.current.vault
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }
    // 0 = current PIN, 1 = new PIN, 2 = repeat new PIN
    var step by remember { mutableIntStateOf(if (verifyCurrent) 0 else 1) }
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockout by remember { mutableLongStateOf(vault.lockoutRemaining()) }

    LaunchedEffect(lockout > 0) {
        while (lockout > 0) {
            delay(500)
            lockout = vault.lockoutRemaining()
        }
    }

    BackHandler { if (cancellable) onCancel() else activity?.moveTaskToBack(true) }

    fun wrong(text: String) {
        message = text
        pin = ""
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { for (x in listOf(18f, -14f, 10f, -6f, 0f)) shake.animateTo(x, tween(45)) }
    }

    fun finish(newPin: String) {
        busy = true
        scope.launch {
            runCatching { onDone(newPin) }.onFailure { busy = false; wrong(context.getString(R.string.lock_save_failed)) }
        }
    }

    fun next() {
        if (busy || lockout > 0) return
        when (step) {
            0 -> {
                if (pin.length < LockVault.PIN_MIN) return
                val entered = pin
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) { vault.unlockWithPin(entered) }
                    busy = false
                    if (result == LockVault.PinResult.Ok) {
                        pin = ""
                        message = null
                        if (verifyOnly) finish(entered) else step = 1
                    } else {
                        lockout = vault.lockoutRemaining()
                        wrong(context.getString(R.string.lock_wrong_pin))
                    }
                }
            }
            1 -> {
                if (pin.length < LockVault.PIN_MIN) return
                if (pin.toSet().size == 1 || "0123456789012".contains(pin) || "9876543210987".contains(pin)) {
                    wrong(context.getString(R.string.lock_pin_too_easy))
                    return
                }
                first = pin
                pin = ""
                message = null
                step = 2
            }
            else -> {
                if (pin != first) {
                    first = ""
                    step = 1
                    wrong(context.getString(R.string.lock_pin_mismatch))
                    return
                }
                finish(pin)
            }
        }
    }

    PinPadScreen(
        title = title,
        message = when {
            lockout > 0 -> stringResource(R.string.lock_too_many, ((lockout + 999) / 1000).toInt())
            message != null -> message!!
            step == 0 -> stringResource(R.string.lock_enter_current_pin)
            step == 1 -> intro
            else -> stringResource(R.string.lock_repeat_pin)
        },
        isError = lockout > 0 || message != null,
        pin = pin,
        shake = shake.value,
        checking = busy,
        enabled = !busy && lockout == 0L,
        onDigit = { d -> if (pin.length < LockVault.PIN_MAX) { message = null; pin += d } },
        onBackspace = { pin = pin.dropLast(1) },
        onConfirm = ::next,
        showConfirm = pin.length >= LockVault.PIN_MIN,
        confirmLabel = stringResource(if (step == 2 || (step == 0 && verifyOnly)) R.string.lock_done else R.string.lock_next),
        bottom = { if (cancellable) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = Ctrl.colors.muted) } },
    )
}

/**
 * Every lock and PIN screen: accent lock square, "VKNXWORKS · CTRL", the title, PIN cells, then the
 * 3-column keypad at the bottom. The bottom-left key is the fingerprint (outlined in the accent) or, once
 * enough digits are in and the PIN needs confirming, the accent confirm key.
 */
@Composable
private fun PinPadScreen(
    title: String,
    message: String,
    isError: Boolean,
    pin: String,
    shake: Float,
    checking: Boolean,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    showConfirm: Boolean,
    confirmLabel: String,
    onBiometric: (() -> Unit)? = null,
    bottom: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(Modifier.fillMaxSize().blockTouches(), color = Ctrl.colors.bg) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(40.dp))
                    LockBadge()
                    Spacer(Modifier.height(28.dp))
                    MonoLabel(stringResource(R.string.label_lock_brand))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.035).em),
                        textAlign = TextAlign.Center,
                        color = Ctrl.colors.text,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isError) LockError else Ctrl.colors.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                    Spacer(Modifier.height(28.dp))
                    Box(Modifier.height(20.dp).offset { IntOffset(shake.roundToInt(), 0) }, contentAlignment = Alignment.Center) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ctrl.colors.accentText)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                val cell = RoundedCornerShape(5.dp)
                                repeat(maxOf(pin.length, LockVault.PIN_MIN)) { i ->
                                    val filled = i < pin.length
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(cell)
                                            .then(
                                                if (filled) Modifier.background(Ctrl.colors.accent)
                                                else Modifier.border(2.dp, Ctrl.colors.borderStrong, cell),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }

                Column(
                    Modifier.fillMaxWidth().widthIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    listOf("123", "456", "789").forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { d ->
                                DigitKey(d.toString(), enabled, Modifier.weight(1f)) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDigit(d)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) {
                            when {
                                showConfirm -> PadKey(
                                    enabled = enabled,
                                    onClick = onConfirm,
                                    container = Ctrl.colors.accent,
                                    border = null,
                                    description = null,
                                ) { Text(confirmLabel.uppercase(), style = CtrlType.button, color = Ctrl.colors.onAccent, maxLines = 1) }
                                onBiometric != null -> PadKey(
                                    enabled = true,
                                    onClick = onBiometric,
                                    container = Color.Transparent,
                                    border = Ctrl.colors.accentText,
                                    description = stringResource(R.string.lock_use_fingerprint),
                                ) { Icon(Icons.Outlined.Fingerprint, null, Modifier.size(26.dp), tint = Ctrl.colors.accentText) }
                                else -> Spacer(Modifier.fillMaxWidth().height(68.dp))
                            }
                        }
                        DigitKey("0", enabled, Modifier.weight(1f)) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDigit('0')
                        }
                        Box(Modifier.weight(1f)) {
                            PadKey(
                                enabled = enabled && pin.isNotEmpty(),
                                onClick = onBackspace,
                                container = Color.Transparent,
                                border = null,
                                description = stringResource(R.string.lock_delete_digit),
                            ) { Icon(Icons.AutoMirrored.Outlined.Backspace, null, Modifier.size(24.dp), tint = Ctrl.colors.muted) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    bottom()
                }
            }
        }
    }
}

/** One keypad key: 68dp tall, 22dp corners. */
@Composable
private fun PadKey(
    enabled: Boolean,
    onClick: () -> Unit,
    container: Color,
    border: Color?,
    description: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(shape)
            .background(container)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun DigitKey(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PadKey(enabled, onClick, container = Ctrl.colors.surface, border = Ctrl.colors.borderStrong, description = null, modifier = modifier) {
        Text(label, style = KeyText, color = Ctrl.colors.text)
    }
}


/** What the lock screens need from the app: the vault and what turning the lock on and off, and "Forgot PIN?", do. */
interface LockHost {
    val vault: LockVault
    suspend fun enableLock(pin: String)
    fun disableLock()
    /** "Forgot PIN?": deletes what the lock protects, resets settings and turns the lock off. */
    suspend fun forgetEverything()
    fun onUnlocked()
    /** LumenCtrl only: "Cancel" while moving from the old screen-lock lock to a PIN. */
    fun cancelUpgrade()
}

val LocalLockHost = compositionLocalOf<LockHost> { error("No LockHost provided") }

/** Errors on the lock screens (the series' red). */
private val LockError = Color(0xFFE5484D)

/** Digits on the keypad. */
private val KeyText = TextStyle(fontFamily = SpaceGrotesk, fontSize = 26.sp, fontWeight = FontWeight.W500)

/** A confirmation over the lock screen, with other apps' overlays hidden while it shows. */
@Composable
private fun LockAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    guard: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            GuardWindow(guard)
            confirmButton()
        },
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = RoundedCornerShape(22.dp),
        containerColor = Ctrl.colors.surface,
        titleContentColor = Ctrl.colors.text,
        textContentColor = Ctrl.colors.muted,
        tonalElevation = 0.dp,
    )
}
