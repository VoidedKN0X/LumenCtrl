package com.vknxworks.lumenctrl.render

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30 as GL
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import com.vknxworks.lumenctrl.model.EditParams
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Video export (Lumen's version of Lightroom's libvf exporter), all on-device:
 * MediaExtractor → MediaCodec decoder → SurfaceTexture → VideoBlit + develop
 * shader → encoder input surface → H.264 → MediaMuxer, with the original audio
 * copied (trimmed) when it can be. Several clips make a reel.
 */
object VideoExporter {
    class Clip(
        val file: File,
        val params: EditParams,
        val uprightW: Int,
        val uprightH: Int,
        val durationMs: Long,
    )

    private const val EGL_RECORDABLE_ANDROID = 0x3142
    private const val TIMEOUT_US = 10_000L

    /** Renders [clips] into an MP4 at [out]. Returns false for audio when it had to be dropped. */
    fun export(clips: List<Clip>, out: File, maxEdge: Int, onProgress: (Float) -> Unit): Boolean {
        require(clips.isNotEmpty())
        val first = clips[0]
        val (fw, fh) = VideoBlit.workSize(first.uprightW, first.uprightH, maxEdge)
        val aspect = Geometry.outputAspect(first.params, fw, fh, ignoreCrop = false)
        // Output: cropped aspect, long edge ≤ maxEdge, multiples of 16 for encoder friendliness.
        var ow: Int; var oh: Int
        val long = minOf(maxEdge, max(fw, fh)).toFloat()
        if (aspect >= 1f) { ow = long.roundToInt(); oh = (long / aspect).roundToInt() } else { oh = long.roundToInt(); ow = (long * aspect).roundToInt() }
        ow = (ow / 16).coerceAtLeast(1) * 16; oh = (oh / 16).coerceAtLeast(1) * 16

        val audio = audioPlan(clips)
        val totalUs = clips.sumOf { c -> ((c.params.video.end(c.durationMs) - c.params.video.trimStartMs) * 1000 / c.params.video.speed).toLong() }.coerceAtLeast(1)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, max(4_000_000, (ow.toLong() * oh * 30 * 0.13).toInt()))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val egl = Egl(inputSurface)
        val program = EditProgram()
        val blit = VideoBlit()
        val thread = HandlerThread("lumen-video").apply { start() }
        val lock = Object()
        var frameAvailable = false
        val muxState = MuxState()
        try {
            program.init(); blit.init()
            val oes = blit.createOesTexture()
            val st = SurfaceTexture(oes)
            st.setOnFrameAvailableListener({ synchronized(lock) { frameAvailable = true; lock.notifyAll() } }, Handler(thread.looper))
            val decoderSurface = Surface(st)
            val m = FloatArray(16)
            val info = MediaCodec.BufferInfo()
            var offsetUs = 0L

            for (clip in clips) {
                val v = clip.params.video
                val startUs = v.trimStartMs * 1000
                val endUs = v.end(clip.durationMs) * 1000
                val (cw, ch) = VideoBlit.workSize(clip.uprightW, clip.uprightH, maxEdge)
                val ex = MediaExtractor().apply { setDataSource(clip.file.path) }
                val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                ex.selectTrack(track)
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val inFormat = ex.getTrackFormat(track)
                val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
                decoder.configure(inFormat, decoderSurface, null, 0)
                decoder.start()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val ii = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (ii >= 0) {
                            val buf = decoder.getInputBuffer(ii)!!
                            val size = ex.readSampleData(buf, 0)
                            val t = ex.sampleTime
                            if (size < 0 || t > endUs) {
                                decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(ii, 0, size, t, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (oi >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val pts = info.presentationTimeUs
                        val render = info.size != 0 && pts in startUs..endUs
                        decoder.releaseOutputBuffer(oi, render)
                        if (render) {
                            synchronized(lock) {
                                val deadline = System.currentTimeMillis() + 3000
                                while (!frameAvailable) {
                                    lock.wait(500)
                                    if (System.currentTimeMillis() > deadline) error("Decoder frame timed out")
                                }
                                frameAvailable = false
                            }
                            st.updateTexImage()
                            st.getTransformMatrix(m)
                            val src = blit.blit(oes, m, cw, ch)
                            // Letterbox clips of a different shape into the reel frame.
                            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
                            GL.glViewport(0, 0, ow, oh)
                            GL.glClearColor(0f, 0f, 0f, 1f)
                            GL.glClear(GL.GL_COLOR_BUFFER_BIT)
                            val r = EditorRenderer.fitRect(ow, oh, Geometry.outputAspect(clip.params, cw, ch, false))
                            GL.glViewport(r.x, r.y, r.w, r.h)
                            program.draw(src, cw, ch, r.w, r.h, clip.params, DrawOptions())
                            val outPts = offsetUs + ((pts - startUs) / v.speed).toLong()
                            EGLExt.eglPresentationTimeANDROID(egl.display, egl.surface, outPts * 1000)
                            EGL14.eglSwapBuffers(egl.display, egl.surface)
                            drainEncoder(encoder, muxer, muxState, audio, false)
                            onProgress((outPts.toFloat() / totalUs).coerceIn(0f, 0.97f))
                        }
                        if (eos) outputDone = true
                    }
                }
                decoder.stop(); decoder.release(); ex.release()
                offsetUs += ((endUs - startUs) / v.speed).toLong() + 33_333
            }
            encoder.signalEndOfInputStream()
            drainEncoder(encoder, muxer, muxState, audio, true)

            // Audio pass: copy the original compressed samples, trimmed, after video.
            if (audio != null && muxState.started) {
                var aOffset = 0L
                val buf = ByteBuffer.allocate(1 shl 20)
                for (clip in clips) {
                    val v = clip.params.video
                    val startUs = v.trimStartMs * 1000
                    val endUs = v.end(clip.durationMs) * 1000
                    val ex = MediaExtractor().apply { setDataSource(clip.file.path) }
                    val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                    ex.selectTrack(t)
                    ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val ai = MediaCodec.BufferInfo()
                    while (true) {
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) break
                        val s = ex.sampleTime
                        if (s > endUs) break
                        if (s >= startUs) {
                            ai.set(0, size, aOffset + (s - startUs), if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            muxer.writeSampleData(muxState.audioTrack, buf, ai)
                        }
                        ex.advance()
                    }
                    ex.release()
                    aOffset += (endUs - startUs) + 33_333
                }
            }
            st.release()
            decoderSurface.release()
            onProgress(1f)
        } finally {
            runCatching { program.release(); blit.release() }
            egl.release()
            thread.quitSafely()
            runCatching { encoder.stop() }; encoder.release()
            runCatching { if (muxState.started) muxer.stop() }; muxer.release()
            inputSurface.release()
        }
        return audio != null || clips.all { it.params.video.mute || !hasAudio(it.file) }
    }

    private class MuxState { var videoTrack = -1; var audioTrack = -1; var started = false }

    private fun drainEncoder(encoder: MediaCodec, muxer: MediaMuxer, s: MuxState, audio: MediaFormat?, eos: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, if (eos) TIMEOUT_US else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    s.videoTrack = muxer.addTrack(encoder.outputFormat)
                    if (audio != null) s.audioTrack = muxer.addTrack(audio)
                    muxer.start(); s.started = true
                }
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && s.started) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(s.videoTrack, buf, info)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun audioFormat(f: File): MediaFormat? = runCatching {
        val ex = MediaExtractor().apply { setDataSource(f.path) }
        try {
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        } finally { ex.release() }
    }.getOrNull()

    private fun hasAudio(f: File) = audioFormat(f) != null

    /**
     * Audio is copied only when it can be done losslessly: normal speed, nothing muted,
     * and (for reels) every clip has the same audio codec, rate and channels.
     */
    private fun audioPlan(clips: List<Clip>): MediaFormat? {
        if (clips.any { it.params.video.mute || it.params.video.speed != 1f }) return null
        val formats = clips.map { audioFormat(it.file) ?: return null }
        val f0 = formats[0]
        fun sig(f: MediaFormat) = Triple(f.getString(MediaFormat.KEY_MIME), f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        return if (formats.all { sig(it) == sig(f0) }) f0 else null
    }

    /** EGL window surface on the encoder's input surface (recordable config). */
    private class Egl(surface: Surface) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val context: android.opengl.EGLContext
        val surface: android.opengl.EGLSurface
        init {
            val v = IntArray(2)
            EGL14.eglInitialize(display, v, 0, v, 1)
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            EGL14.eglChooseConfig(
                display, intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
                ), 0, configs, 0, 1, n, 0,
            )
            check(n[0] > 0) { "No recordable EGL config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            this.surface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, this.surface, this.surface, context)) { "eglMakeCurrent failed" }
        }
        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }

    /** Copies a finished MP4 into Movies/Lumen through MediaStore (no permission needed). */
    fun saveToGallery(context: Context, file: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Lumen")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null); throw t
        }
        return uri
    }
}
