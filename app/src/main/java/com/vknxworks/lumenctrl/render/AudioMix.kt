package com.vknxworks.lumenctrl.render

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.vknxworks.lumenctrl.model.VideoEdit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * The sound of a video export with music: for every clip, its own sound (when it's kept: not muted, normal speed)
 * mixed with its music, starting at the clip's start, at the music volume and fading out at the clip's end. Decoded
 * with Android's decoders, mixed at 48 kHz stereo and encoded once to AAC, all on the phone.
 */
object AudioMix {
    const val RATE = 48_000
    private const val CHANNELS = 2
    private const val TIMEOUT_US = 10_000L
    private const val CHUNK = 2048
    private const val FADE_OUT_US = 1_500_000L
    private const val FADE_IN_US = 20_000L

    /** An encoded AAC track: its format for the muxer and its samples (data and timing). */
    class Encoded(val format: MediaFormat, val samples: List<Pair<ByteArray, MediaCodec.BufferInfo>>)

    /** One clip of the export: its sound span and its music. [outUs] is how long the clip lasts in the export. */
    class Part(val video: File, val edit: VideoEdit, val startUs: Long, val endUs: Long, val outUs: Long)

    /** Whether any part has music whose file is here. */
    fun hasMusic(parts: List<Part>) = parts.any { p -> p.edit.musicId?.let { VideoEdit.musicFile(p.video, it).exists() } == true }

    /**
     * Mixes and encodes all [parts] one after the other, with [gapUs] of silence between them (matching the video
     * timeline). [isCancelled] is checked between chunks.
     */
    fun render(parts: List<Part>, gapUs: Long, isCancelled: () -> Boolean = { false }): Encoded {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * CHANNELS * 2)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = ArrayList<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outFormat: MediaFormat? = null
        try {
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            var framesIn = 0L
            val pcm = ShortArray(CHUNK * CHANNELS)
            val mix = FloatArray(CHUNK * CHANNELS)
            val tmp = FloatArray(CHUNK * CHANNELS)
            val info = MediaCodec.BufferInfo()

            fun drain(eos: Boolean) {
                while (true) {
                    val oi = enc.dequeueOutputBuffer(info, if (eos) TIMEOUT_US else 0)
                    when {
                        oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = enc.outputFormat
                        oi >= 0 -> {
                            val buf = enc.getOutputBuffer(oi)!!
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val bytes = ByteArray(info.size)
                                buf.position(info.offset); buf.get(bytes)
                                samples += bytes to MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                            }
                            enc.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            fun feed(frames: Int, eos: Boolean) {
                var off = 0
                while (true) {
                    val ii = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (ii < 0) { drain(false); continue }
                    val buf = enc.getInputBuffer(ii)!!
                    buf.clear()
                    val n = min(frames - off, buf.capacity() / (CHANNELS * 2))
                    buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off * CHANNELS, n * CHANNELS)
                    val last = eos && off + n >= frames
                    enc.queueInputBuffer(ii, 0, n * CHANNELS * 2, framesIn * 1_000_000L / RATE, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    framesIn += n
                    off += n
                    drain(false)
                    if (off >= frames) return
                }
            }

            parts.forEachIndexed { index, part ->
                val total = (part.outUs * RATE / 1_000_000L).coerceAtLeast(0L)
                val keepOriginal = !part.edit.mute && part.edit.speed == 1f
                val original = if (keepOriginal) PcmReader.open(part.video, part.startUs, part.endUs) else null
                val musicFile = part.edit.musicId?.let { VideoEdit.musicFile(part.video, it) }?.takeIf { it.exists() }
                val music = musicFile?.let { PcmReader.open(it, 0L, Long.MAX_VALUE) }
                val vol = part.edit.musicVolume
                try {
                    var done = 0L
                    while (done < total) {
                        if (isCancelled()) throw InterruptedException()
                        val n = min(CHUNK.toLong(), total - done).toInt()
                        java.util.Arrays.fill(mix, 0, n * CHANNELS, 0f)
                        original?.let { r ->
                            val got = r.read(tmp, n)
                            for (i in 0 until got * CHANNELS) mix[i] += tmp[i]
                        }
                        music?.let { r ->
                            val got = r.read(tmp, n)
                            for (f in 0 until got) {
                                val tUs = (done + f) * 1_000_000L / RATE
                                val fadeIn = (tUs.toFloat() / FADE_IN_US).coerceAtMost(1f)
                                val fadeOut = ((part.outUs - tUs).toFloat() / FADE_OUT_US).coerceIn(0f, 1f)
                                val g = vol * fadeIn * fadeOut
                                mix[f * 2] += tmp[f * 2] * g
                                mix[f * 2 + 1] += tmp[f * 2 + 1] * g
                            }
                        }
                        for (i in 0 until n * CHANNELS) pcm[i] = (mix[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        feed(n, eos = false)
                        done += n
                    }
                } finally {
                    original?.close()
                    music?.close()
                }
                if (index < parts.lastIndex) {
                    var gap = (gapUs * RATE / 1_000_000L).toInt()
                    while (gap > 0) {
                        val n = min(CHUNK, gap)
                        java.util.Arrays.fill(pcm, 0, n * CHANNELS, 0)
                        feed(n, eos = false)
                        gap -= n
                    }
                }
            }
            // End of stream.
            java.util.Arrays.fill(pcm, 0, CHANNELS, 0)
            feed(1, eos = true)
            drain(true)
        } finally {
            runCatching { enc.stop() }
            enc.release()
        }
        return Encoded(outFormat ?: error("AAC encoder gave no format"), samples)
    }

    /**
     * Decodes an audio file from [startUs] to [endUs] and hands it out as 48 kHz stereo floats (linear resampling;
     * mono is doubled, extra channels dropped).
     */
    class PcmReader private constructor(private val ex: MediaExtractor, private val dec: MediaCodec, private val startUs: Long, private val endUs: Long) {
        private var rate = RATE
        private var channels = 2
        private var floatPcm = false
        /** Decoded source frames not yet used (stereo floats) and the read position in them. */
        private var buf = FloatArray(0)
        private var bufFrames = 0
        private var pos = 0.0
        private var inputDone = false
        private var outputDone = false
        private val info = MediaCodec.BufferInfo()

        companion object {
            fun open(file: File, startUs: Long, endUs: Long): PcmReader? = runCatching {
                val ex = MediaExtractor()
                ex.setDataSource(file.path)
                val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                if (track == null) { ex.release(); return@runCatching null }
                ex.selectTrack(track)
                val f = ex.getTrackFormat(track)
                if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
                try {
                    dec.configure(f, null, null, 0)
                    dec.start()
                } catch (t: Throwable) { dec.release(); ex.release(); throw t }
                PcmReader(ex, dec, startUs, endUs).also { r ->
                    r.rate = f.getIntOrDefault(MediaFormat.KEY_SAMPLE_RATE, RATE)
                    r.channels = f.getIntOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
                }
            }.onFailure { android.util.Log.w("LumenAudio", "can't decode ${file.name}", it) }.getOrNull()

            private fun MediaFormat.getIntOrDefault(key: String, def: Int) = if (containsKey(key)) getInteger(key) else def
        }

        /** Fills [dst] with up to [frames] stereo frames; returns how many (fewer only at the end). */
        fun read(dst: FloatArray, frames: Int): Int {
            var n = 0
            while (n < frames) {
                val step = rate.toDouble() / RATE
                val i0 = pos.toInt()
                if (i0 + 1 >= bufFrames) {
                    if (!decodeMore()) {
                        // Last frame: hold it, then stop.
                        val j = pos.toInt()
                        if (j < bufFrames) { dst[n * 2] = buf[j * 2]; dst[n * 2 + 1] = buf[j * 2 + 1]; n++; pos += step; continue }
                        break
                    }
                    continue
                }
                val t = (pos - i0).toFloat()
                dst[n * 2] = buf[i0 * 2] * (1 - t) + buf[i0 * 2 + 2] * t
                dst[n * 2 + 1] = buf[i0 * 2 + 1] * (1 - t) + buf[i0 * 2 + 3] * t
                n++
                pos += step
            }
            return n
        }

        /** Decodes the next output buffer into [buf]; false at the end of the span. */
        private fun decodeMore(): Boolean {
            // Keep the frame under the read position (needed for interpolation), drop the rest before it.
            val keep = pos.toInt().coerceAtMost(bufFrames)
            if (keep > 0) {
                System.arraycopy(buf, keep * 2, buf, 0, (bufFrames - keep) * 2)
                bufFrames -= keep
                pos -= keep
            }
            while (!outputDone) {
                if (!inputDone) {
                    val ii = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val ib = dec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(ib, 0)
                        val t = ex.sampleTime
                        if (size < 0 || t > endUs) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(ii, 0, size, t, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = dec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    continue
                }
                if (oi < 0) continue
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val ob = dec.getOutputBuffer(oi)!!
                var added = 0
                if (info.size > 0 && info.presentationTimeUs <= endUs) {
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    ob.order(ByteOrder.nativeOrder())
                    val ch = channels.coerceAtLeast(1)
                    val bytesPerSample = if (floatPcm) 4 else 2
                    val frames = info.size / (bytesPerSample * ch)
                    var skip = 0
                    // Drop what lies before the span's start (the decoder starts at a sync point before it).
                    if (info.presentationTimeUs < startUs) {
                        skip = ((startUs - info.presentationTimeUs) * rate / 1_000_000L).toInt().coerceAtMost(frames)
                    }
                    val usable = frames - skip
                    if (usable > 0) {
                        if (buf.size < (bufFrames + usable) * 2) buf = buf.copyOf(((bufFrames + usable) * 2 * 3 / 2).coerceAtLeast(4096))
                        val sb = if (floatPcm) null else ob.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                        val fb = if (floatPcm) ob.slice().order(ByteOrder.nativeOrder()).asFloatBuffer() else null
                        for (f in skip until frames) {
                            val l: Float; val r: Float
                            if (fb != null) { l = fb.get(f * ch); r = if (ch > 1) fb.get(f * ch + 1) else l }
                            else { l = sb!!.get(f * ch) / 32768f; r = if (ch > 1) sb.get(f * ch + 1) / 32768f else l }
                            buf[(bufFrames + added) * 2] = l
                            buf[(bufFrames + added) * 2 + 1] = r
                            added++
                        }
                        bufFrames += added
                    }
                }
                dec.releaseOutputBuffer(oi, false)
                if (eos || info.presentationTimeUs > endUs) outputDone = true
                if (added > 0) return true
            }
            return false
        }

        fun close() {
            runCatching { dec.stop() }
            runCatching { dec.release() }
            runCatching { ex.release() }
        }
    }

    @Suppress("unused")
    private fun usToFrames(us: Long) = (us.toDouble() * RATE / 1_000_000.0).roundToLong()
}
