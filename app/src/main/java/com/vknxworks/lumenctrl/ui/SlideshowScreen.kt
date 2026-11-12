package com.vknxworks.lumenctrl.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlChip
import com.vknxworks.lumenctrl.ui.ctrl.CtrlIconButton
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import kotlinx.coroutines.delay

/** Full-screen slideshow of edited photos, rendered on the fly. Tap to show controls. */
@Composable
fun SlideshowScreen(vm: LumenViewModel) {
    val c = Ctrl.colors
    val ids = vm.showIds
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var chrome by remember { mutableStateOf(true) }
    var seconds by remember { mutableIntStateOf(4) }
    val cache = remember { mutableStateMapOf<String, Bitmap>() }
    BackHandler { vm.backToLibrary() }

    // Render current and next; keep a small window in memory.
    LaunchedEffect(index, ids) {
        listOf(index, (index + 1) % ids.size).forEach { i ->
            val id = ids[i]
            if (id !in cache) vm.renderEdited(id, 2048)?.let { cache[id] = it }
        }
        val keep = setOf(ids[index], ids[(index + 1) % ids.size], ids[(index - 1 + ids.size) % ids.size])
        cache.keys.filter { it !in keep }.forEach { cache.remove(it) }
    }
    LaunchedEffect(playing, index, seconds) {
        if (!playing || ids.size < 2) return@LaunchedEffect
        delay(seconds * 1000L)
        index = (index + 1) % ids.size
    }

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        detectTapGestures(onTap = { o ->
            when {
                o.x < size.width * 0.25f -> index = (index - 1 + ids.size) % ids.size
                o.x > size.width * 0.75f -> index = (index + 1) % ids.size
                else -> chrome = !chrome
            }
        })
    }) {
        Crossfade(targetState = ids.getOrNull(index)?.let { cache[it] }, animationSpec = tween(600), label = "slide") { bmp ->
            if (bmp != null) Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp) }
        }
        if (chrome) {
            Row(
                Modifier.fillMaxWidth().systemBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CtrlIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Close slideshow", vm::backToLibrary)
                MonoLabel("${index + 1} / ${ids.size}", color = Color.White)
                Spacer(Modifier.weight(1f))
                listOf(2, 4, 8).forEach { s -> CtrlChip("${s}s", seconds == s, onClick = { seconds = s }) }
                CtrlIconButton(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play", { playing = !playing }, style = CtrlButtonStyle.Accent)
            }
        }
    }
}
