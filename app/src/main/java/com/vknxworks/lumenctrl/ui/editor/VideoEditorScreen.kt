package com.vknxworks.lumenctrl.ui.editor

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vknxworks.lumenctrl.LumenViewModel
import com.vknxworks.lumenctrl.data.ImageLoader
import com.vknxworks.lumenctrl.model.VideoEdit
import com.vknxworks.lumenctrl.render.DrawOptions
import com.vknxworks.lumenctrl.render.VideoRenderer
import com.vknxworks.lumenctrl.ui.ctrl.Ctrl
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlButtonStyle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlChip
import com.vknxworks.lumenctrl.ui.ctrl.CtrlIconButton
import com.vknxworks.lumenctrl.ui.ctrl.CtrlNavPill
import com.vknxworks.lumenctrl.ui.ctrl.CtrlRow
import com.vknxworks.lumenctrl.ui.ctrl.CtrlSheet
import com.vknxworks.lumenctrl.ui.ctrl.CtrlToggle
import com.vknxworks.lumenctrl.ui.ctrl.CtrlType
import com.vknxworks.lumenctrl.ui.ctrl.MonoLabel
import com.vknxworks.lumenctrl.ui.ctrl.NavItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val videoTools = listOf(
    NavItem("Presets", Icons.Outlined.AutoAwesome),
    NavItem("Edit", Icons.Outlined.Tune),
    NavItem("Crop", Icons.Outlined.Crop),
    NavItem("Video", Icons.Outlined.Movie),
)

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

/** Lumen's video editor (Lightroom: VideoPlayerActivity + edit), all on-device. */
@Composable
fun VideoEditorScreen(vm: LumenViewModel) {
    val c = Ctrl.colors
    val photo = vm.openPhoto ?: return
    val st = remember { EditorState() }
    var tool by remember { mutableStateOf(1) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var showExport by remember { mutableStateOf(false) }
    val duration = photo.meta.durationMs.coerceAtLeast(1)
    val v = vm.params.video
    val trimEnd = v.end(duration)
    BackHandler { player?.pause(); vm.close() }

    // Keep playback inside the trim range, and mute/speed in sync with the edit.
    LaunchedEffect(player, playing, v) {
        val mp = player ?: return@LaunchedEffect
        runCatching {
            mp.setVolume(if (v.mute) 0f else 1f, if (v.mute) 0f else 1f)
            if (playing) mp.playbackParams = PlaybackParams().setSpeed(v.speed) else if (mp.isPlaying) mp.pause()
        }
        while (playing) {
            position = mp.currentPosition.toLong()
            if (position >= trimEnd || position < v.trimStartMs - 50) mp.seekTo(v.trimStartMs, MediaPlayer.SEEK_CLOSEST)
            delay(40)
        }
        position = mp.currentPosition.toLong()
        vm.refreshVideoFrame(position)
    }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back to library", { player?.pause(); vm.close() })
            Spacer(Modifier.size(10.dp))
            MonoLabel("Video · ${fmt(trimEnd - v.trimStartMs)}", Modifier.weight(1f))
            CtrlIconButton(Icons.AutoMirrored.Outlined.Undo, "Undo", vm::undo, style = CtrlButtonStyle.Ghost, enabled = vm.canUndo, size = 40)
            CtrlIconButton(Icons.AutoMirrored.Outlined.Redo, "Redo", vm::redo, style = CtrlButtonStyle.Ghost, enabled = vm.canRedo, size = 40)
            Spacer(Modifier.size(8.dp))
            CtrlIconButton(Icons.Outlined.Download, "Export video", { player?.pause(); playing = false; showExport = true }, style = CtrlButtonStyle.Accent)
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            VideoCanvas(vm, st, onPlayer = { mp ->
                player = mp
                mp.seekTo(vm.params.video.trimStartMs, MediaPlayer.SEEK_CLOSEST)
            }, modifier = Modifier.fillMaxSize())
            Surface(
                onClick = {
                    player?.let { mp ->
                        if (playing) { mp.pause(); playing = false }
                        else {
                            if (mp.currentPosition >= trimEnd - 100) mp.seekTo(v.trimStartMs, MediaPlayer.SEEK_CLOSEST)
                            mp.start(); playing = true
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp), color = c.accent,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play", tint = c.onAccent)
                }
            }
        }

        Timeline(vm, position, duration) { ms ->
            player?.seekTo(ms, MediaPlayer.SEEK_CLOSEST); position = ms
            if (!playing) vm.refreshVideoFrame(ms)
        }

        Box(Modifier.fillMaxWidth().height(200.dp)) {
            when (tool) {
                0 -> PresetPanel(vm)
                1 -> EditPanel(vm, st)
                2 -> CropPanel(vm)
                else -> VideoPanel(vm, photo.meta.hasAudio, duration)
            }
        }
        CtrlNavPill(videoTools, tool, { tool = it }, Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp))
    }

    if (showExport) VideoExportSheet(onDismiss = { showExport = false }) { edge, share ->
        showExport = false
        vm.exportVideos(listOf(photo.id), edge, share)
    }
}

@Composable
private fun VideoPanel(vm: LumenViewModel, hasAudio: Boolean, duration: Long) {
    val v = vm.params.video
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("Trim ${fmt(v.trimStartMs)} – ${fmt(v.end(duration))}", Modifier.weight(1f))
            CtrlButton("Reset trim", onClick = { vm.edit("Reset trim") { it.copy(video = it.video.copy(trimStartMs = 0, trimEndMs = -1)) } }, style = CtrlButtonStyle.Ghost)
        }
        if (hasAudio) {
            CtrlRow("Sound", if (v.mute) "Muted in preview and export" else "Original audio") {
                CtrlToggle(!v.mute, { on -> vm.edit(if (on) "Unmute" else "Mute") { it.copy(video = it.video.copy(mute = !on)) } }, "Sound")
            }
        } else MonoLabel("No audio track")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("Speed", Modifier.padding(end = 6.dp))
            VideoEdit.SPEEDS.forEach { s ->
                CtrlChip("${if (s == 0.5f) "0.5" else s.toInt()}×", v.speed == s, onClick = { vm.edit("Speed ${s}×") { it.copy(video = it.video.copy(speed = s)) } })
            }
        }
        if (v.speed != 1f && hasAudio) MonoLabel("Audio is left out at other speeds")
    }
}

/** Filmstrip with trim handles and a playhead. Drag handles to trim, tap to seek. */
@Composable
private fun Timeline(vm: LumenViewModel, position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val c = Ctrl.colors
    val photo = vm.openPhoto ?: return
    val frames = remember(photo.id) { mutableStateListOf<Bitmap>() }
    LaunchedEffect(photo.id) {
        for (i in 0 until 8) {
            val t = duration * (2 * i + 1) / 16
            withContext(Dispatchers.IO) { ImageLoader.videoFrame(photo.original, t, 160, photo.meta.width, photo.meta.height) }?.let { frames.add(it) }
        }
    }
    val params by rememberUpdatedState(vm.params)
    var dragging by remember { mutableStateOf(0) } // 1 = start handle, 2 = end handle

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row { MonoLabel(fmt(position)); Spacer(Modifier.weight(1f)); MonoLabel(fmt(duration)) }
        Box(
            Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp)
                // Trim handles sit near the screen edges: keep Android's back gesture off them.
                .systemGestureExclusion()
                .background(c.surface, RoundedCornerShape(10.dp))
                .pointerInput(photo.id) {
                    detectTapGestures { o -> onSeek((o.x / size.width * duration).toLong().coerceIn(0, duration)) }
                }
                .pointerInput(photo.id) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val v = params.video
                            val sx = v.trimStartMs.toFloat() / duration * size.width
                            val ex = v.end(duration).toFloat() / duration * size.width
                            dragging = if (kotlin.math.abs(o.x - sx) < kotlin.math.abs(o.x - ex)) 1 else 2
                        },
                        onDrag = { ch, _ ->
                            ch.consume()
                            val ms = (ch.position.x / size.width * duration).toLong().coerceIn(0, duration)
                            val v = params.video
                            val next = if (dragging == 1) v.copy(trimStartMs = ms.coerceAtMost(v.end(duration) - 500))
                            else v.copy(trimEndMs = ms.coerceAtLeast(v.trimStartMs + 500).let { if (it >= duration - 50) -1 else it })
                            vm.setLiveParams(params.copy(video = next), "Trim")
                            onSeek(if (dragging == 1) next.trimStartMs else next.end(duration))
                        },
                        onDragEnd = { dragging = 0; vm.commit() },
                        onDragCancel = { dragging = 0; vm.commit() },
                    )
                },
        ) {
            Row(Modifier.fillMaxSize()) {
                frames.forEach { f ->
                    Image(f.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
            val v = vm.params.video
            Canvas(Modifier.fillMaxSize()) {
                val sx = v.trimStartMs.toFloat() / duration * size.width
                val ex = v.end(duration).toFloat() / duration * size.width
                drawRect(Color.Black.copy(alpha = 0.6f), Offset.Zero, Size(sx, size.height))
                drawRect(Color.Black.copy(alpha = 0.6f), Offset(ex, 0f), Size(size.width - ex, size.height))
                val hw = 6.dp.toPx()
                drawRect(c.accent, Offset(sx, 0f), Size(hw, size.height))
                drawRect(c.accent, Offset(ex - hw, 0f), Size(hw, size.height))
                drawRect(c.accent, Offset(sx, 0f), Size(ex - sx, 2.dp.toPx()))
                drawRect(c.accent, Offset(sx, size.height - 2.dp.toPx()), Size(ex - sx, 2.dp.toPx()))
                val px = position.toFloat() / duration * size.width
                drawRect(Color.White, Offset(px - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            }
        }
    }
}

/** GLSurfaceView + MediaPlayer, graded live with the develop shader. */
@Composable
private fun VideoCanvas(vm: LumenViewModel, st: EditorState, onPlayer: (MediaPlayer) -> Unit, modifier: Modifier) {
    val c = Ctrl.colors
    val photo = vm.openPhoto ?: return
    val renderer = remember(photo.id) { VideoRenderer(photo.meta.width.coerceAtLeast(2), photo.meta.height.coerceAtLeast(2)) }
    var view by remember { mutableStateOf<GLSurfaceView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val player = remember(photo.id) { MediaPlayer() }
    val ground = c.bg

    DisposableEffect(photo.id) {
        renderer.onSurface = { surface ->
            main.post {
                runCatching {
                    player.reset()
                    player.setDataSource(photo.original.path)
                    player.setSurface(surface)
                    player.setOnPreparedListener { onPlayer(it) }
                    player.prepareAsync()
                }.onFailure { vm.toast("Can't play this video: ${it.message}") }
            }
        }
        onDispose { runCatching { player.release() }; renderer.release() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                renderer.requestRender = { requestRender() }
            }.also { view = it }
        },
        update = { v ->
            renderer.params = vm.params
            renderer.options = DrawOptions(
                before = st.showBefore, ignoreCrop = false,
                groundR = ground.red, groundG = ground.green, groundB = ground.blue,
            )
            v.requestRender()
        },
    )

    DisposableEffect(lifecycle, view) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> view?.onResume()
                Lifecycle.Event.ON_PAUSE -> { runCatching { if (player.isPlaying) player.pause() }; view?.onPause() }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
}

@Composable
fun VideoExportSheet(title: String = "Export video", onDismiss: () -> Unit, onExport: (maxEdge: Int, share: Boolean) -> Unit) {
    val c = Ctrl.colors
    var edge by remember { mutableStateOf(1920) }
    CtrlSheet(title, onDismiss) {
        MonoLabel("Resolution")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("720p" to 1280, "1080p" to 1920, "4K" to 3840).forEach { (l, e) -> CtrlChip(l, edge == e, onClick = { edge = e }) }
        }
        Text("H.264 MP4, 30 fps. Edits, trim and speed are applied; original audio is copied when the speed is normal.", style = CtrlType.value.copy(fontFamily = com.vknxworks.lumenctrl.ui.ctrl.SpaceGrotesk), color = c.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CtrlButton("Save to gallery", onClick = { onExport(edge, false) }, icon = Icons.Outlined.Download, style = CtrlButtonStyle.Accent, modifier = Modifier.weight(1f))
            CtrlButton("Share", onClick = { onExport(edge, true) }, icon = Icons.Outlined.Share)
        }
        MonoLabel("Rendered on this device · nothing is uploaded")
    }
}

@Suppress("unused")
private val keepIcons = listOf(Icons.Outlined.VolumeOff, Icons.Outlined.VolumeUp)

@Suppress("unused")
private val keepBorder = BorderStroke(1.dp, Color.Transparent)
