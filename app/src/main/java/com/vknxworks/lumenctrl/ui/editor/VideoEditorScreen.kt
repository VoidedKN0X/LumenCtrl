package com.vknxworks.lumenctrl.ui.editor

import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.tr
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
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
    NavItem(tr(R.string.presets), Icons.Outlined.AutoAwesome),
    NavItem(tr(R.string.edit), Icons.Outlined.Tune),
    NavItem(tr(R.string.crop), Icons.Outlined.Crop),
    NavItem(tr(R.string.video), Icons.Outlined.Movie),
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
    // The music plays along in the preview, from the clip's start (trim) at normal speed, like in the export.
    val musicFile = v.musicId?.let { VideoEdit.musicFile(photo.original, it) }
    val musicPlayer = remember(musicFile?.path) {
        musicFile?.takeIf { it.exists() }?.let { f ->
            runCatching { MediaPlayer().apply { setDataSource(f.path); prepare() } }.getOrNull()
        }
    }
    DisposableEffect(musicPlayer) { onDispose { runCatching { musicPlayer?.release() } } }
    BackHandler { player?.pause(); runCatching { musicPlayer?.pause() }; vm.close() }

    // Keep playback inside the trim range, and mute/speed in sync with the edit.
    LaunchedEffect(player, playing, v) {
        val mp = player ?: return@LaunchedEffect
        fun musicAt(videoMs: Long) = ((videoMs - v.trimStartMs).coerceAtLeast(0) / v.speed).toLong().toInt()
        runCatching {
            mp.setVolume(if (v.mute) 0f else 1f, if (v.mute) 0f else 1f)
            if (playing) mp.playbackParams = PlaybackParams().setSpeed(v.speed) else if (mp.isPlaying) mp.pause()
        }
        runCatching {
            musicPlayer?.let { m ->
                m.setVolume(v.musicVolume, v.musicVolume)
                if (playing) {
                    val at = musicAt(mp.currentPosition.toLong())
                    if (at < m.duration) { m.seekTo(at); m.start() } else if (m.isPlaying) m.pause()
                } else if (m.isPlaying) m.pause()
            }
        }
        while (playing) {
            position = mp.currentPosition.toLong()
            if (position >= trimEnd || position < v.trimStartMs - 50) {
                mp.seekTo(v.trimStartMs, MediaPlayer.SEEK_CLOSEST)
                runCatching { musicPlayer?.let { m -> m.seekTo(0); if (!m.isPlaying) m.start() } }
            }
            delay(40)
        }
        position = mp.currentPosition.toLong()
        runCatching { musicPlayer?.let { m -> if (m.isPlaying) m.pause() } }
        vm.refreshVideoFrame(position)
    }

    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CtrlIconButton(Icons.AutoMirrored.Outlined.ArrowBack, tr(R.string.back_to_library), { player?.pause(); runCatching { musicPlayer?.pause() }; vm.close() })
            Spacer(Modifier.size(10.dp))
            MonoLabel(tr(R.string.video_x, fmt(trimEnd - v.trimStartMs)), Modifier.weight(1f))
            CtrlIconButton(Icons.AutoMirrored.Outlined.Undo, tr(R.string.undo), vm::undo, style = CtrlButtonStyle.Ghost, enabled = vm.canUndo, size = 40)
            CtrlIconButton(Icons.AutoMirrored.Outlined.Redo, tr(R.string.redo), vm::redo, style = CtrlButtonStyle.Ghost, enabled = vm.canRedo, size = 40)
            Spacer(Modifier.size(8.dp))
            CtrlIconButton(Icons.Outlined.Download, tr(R.string.export_video), { player?.pause(); playing = false; showExport = true }, style = CtrlButtonStyle.Accent)
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            VideoCanvas(vm, st, cropping = tool == 2, onPlayer = { mp ->
                player = mp
                mp.seekTo(vm.params.video.trimStartMs, MediaPlayer.SEEK_CLOSEST)
            }, modifier = Modifier.fillMaxSize())
            // Crop: the whole frame with a draggable crop box on top.
            if (tool == 2) CropOverlay(vm, photo.meta.width, photo.meta.height, Modifier.fillMaxSize())
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
                    androidx.compose.material3.Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) tr(R.string.pause) else tr(R.string.play), tint = c.onAccent)
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
    val musicPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.setMusic(uri) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(tr(R.string.trim_x_x, fmt(v.trimStartMs), fmt(v.end(duration))), Modifier.weight(1f))
            CtrlButton(tr(R.string.reset_trim), onClick = { vm.edit(tr(R.string.reset_trim)) { it.copy(video = it.video.copy(trimStartMs = 0, trimEndMs = -1)) } }, style = CtrlButtonStyle.Ghost)
        }
        if (hasAudio) {
            CtrlRow(tr(R.string.sound), if (v.mute) tr(R.string.muted_in_preview_and_export) else tr(R.string.original_audio)) {
                CtrlToggle(!v.mute, { on -> vm.edit(if (on) tr(R.string.unmute) else tr(R.string.mute)) { it.copy(video = it.video.copy(mute = !on)) } }, tr(R.string.sound))
            }
        } else MonoLabel(tr(R.string.no_audio_track))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(tr(R.string.speed), Modifier.padding(end = 6.dp))
            VideoEdit.SPEEDS.forEach { s ->
                CtrlChip("${if (s == 0.5f) "0.5" else s.toInt()}×", v.speed == s, onClick = { vm.edit(tr(R.string.speed_x_x, s)) { it.copy(video = it.video.copy(speed = s)) } })
            }
        }
        if (v.speed != 1f && hasAudio) MonoLabel(tr(R.string.audio_is_left_out_at_other))
        MonoLabel(tr(R.string.music), Modifier.padding(top = 4.dp))
        if (v.musicId == null) {
            CtrlButton(tr(R.string.add_music), onClick = { musicPicker.launch(arrayOf("audio/*")) }, icon = Icons.Outlined.MusicNote)
            MonoLabel(tr(R.string.music_hint))
        } else {
            Text(v.musicName ?: tr(R.string.music), style = CtrlType.body, color = Ctrl.colors.text)
            com.vknxworks.lumenctrl.ui.ctrl.LabeledSlider(
                tr(R.string.music_volume), "${(v.musicVolume * 100).toInt()}", v.musicVolume, 0f..1f, 0f, v.musicVolume != 0.8f,
                onChange = { x -> vm.setLiveParams(vm.params.copy(video = vm.params.video.copy(musicVolume = x)), tr(R.string.music_volume)) },
                onDone = vm::commit,
                onReset = { vm.edit(tr(R.string.music_volume)) { it.copy(video = it.video.copy(musicVolume = 0.8f)) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CtrlButton(tr(R.string.change_music), onClick = { musicPicker.launch(arrayOf("audio/*")) }, icon = Icons.Outlined.MusicNote)
                CtrlButton(tr(R.string.remove_music), onClick = vm::removeMusic, style = CtrlButtonStyle.Ghost)
            }
            MonoLabel(if (v.mute || !hasAudio || v.speed != 1f) tr(R.string.music_only) else tr(R.string.music_mixed_with_sound))
        }
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
                            vm.setLiveParams(params.copy(video = next), tr(R.string.trim))
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
private fun VideoCanvas(vm: LumenViewModel, st: EditorState, cropping: Boolean, onPlayer: (MediaPlayer) -> Unit, modifier: Modifier) {
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
                }.onFailure { vm.toast(tr(R.string.cant_play_this_video_x, it.message)) }
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
                before = st.showBefore, ignoreCrop = cropping,
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
fun VideoExportSheet(title: String = tr(R.string.export_video), onDismiss: () -> Unit, onExport: (maxEdge: Int, share: Boolean) -> Unit) {
    val c = Ctrl.colors
    var edge by remember { mutableStateOf(1920) }
    CtrlSheet(title, onDismiss) {
        MonoLabel(tr(R.string.resolution))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("720p" to 1280, "1080p" to 1920, "4K" to 3840).forEach { (l, e) -> CtrlChip(l, edge == e, onClick = { edge = e }) }
        }
        Text(tr(R.string.h_264_mp4_30_fps_edits), style = CtrlType.value.copy(fontFamily = com.vknxworks.lumenctrl.ui.ctrl.SpaceGrotesk), color = c.muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CtrlButton(tr(R.string.save_to_gallery), onClick = { onExport(edge, false) }, icon = Icons.Outlined.Download, style = CtrlButtonStyle.Accent, modifier = Modifier.weight(1f))
            CtrlButton(tr(R.string.share), onClick = { onExport(edge, true) }, icon = Icons.Outlined.Share)
        }
        MonoLabel(tr(R.string.rendered_on_this_device_nothing_is))
    }
}

@Suppress("unused")
private val keepIcons = listOf(Icons.Outlined.VolumeOff, Icons.Outlined.VolumeUp)

@Suppress("unused")
private val keepBorder = BorderStroke(1.dp, Color.Transparent)
