package com.vknxworks.lumenctrl.render

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Writes a bitmap as a HEIC (HEVC) or AVIF (AV1) still image with Android's own encoders and muxer: the photo is cut
 * into 512 px tiles, each tile is encoded as one frame, and MediaMuxer's HEIF output joins them into a grid image.
 * This follows the approach of AndroidX HeifWriter (Apache 2.0) without the library. Call off the main thread.
 *
 * HEIC needs an HEVC encoder (every phone LumenCtrl runs on has one). AVIF needs an AV1 encoder, which many phones
 * don't have; [supported] says whether this one does. Neither format carries EXIF here, so no metadata (and never
 * the location) ends up in the file.
 */
object HeifImageWriter {
    enum class Kind(val codecMime: String, val trackMime: String) {
        HEIC(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC),
        AVIF(MediaFormat.MIMETYPE_VIDEO_AV1, MediaFormat.MIMETYPE_IMAGE_AVIF),
    }

    private const val TILE = 512
    private const val BLOCK = 64
    private const val TIMEOUT_US = 10_000L
    private const val MAX_COMPRESS_RATIO = 0.25

    private val cache = HashMap<Kind, String?>()

    /** Whether this phone has an encoder for [kind]. */
    fun supported(kind: Kind): Boolean = encoderFor(kind) != null

    /** Name of the encoder to use: hardware before software, constant-quality mode preferred. */
    private fun encoderFor(kind: Kind): String? = synchronized(cache) {
        if (cache.containsKey(kind)) return cache[kind]
        val candidates = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrDefault(emptyArray())
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(kind.codecMime, ignoreCase = true) } }
            .mapNotNull { info ->
                val caps = runCatching { info.getCapabilitiesForType(kind.codecMime) }.getOrNull() ?: return@mapNotNull null
                val video = caps.videoCapabilities ?: return@mapNotNull null
                if (!video.isSizeSupported(TILE, TILE)) return@mapNotNull null
                if (MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible !in caps.colorFormats) return@mapNotNull null
                val cq = caps.encoderCapabilities?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) == true
                info to ((if (info.isHardwareAccelerated) 2 else 0) + (if (cq) 1 else 0))
            }
        val name = candidates.maxByOrNull { it.second }?.first?.name
        cache[kind] = name
        name
    }

    /** Encodes [bitmap] (ARGB_8888) at [quality] 1..100 into [out]. Throws when the phone can't. */
    fun write(bitmap: Bitmap, kind: Kind, quality: Int, out: File) {
        val name = encoderFor(kind) ?: error("No ${kind.name} encoder on this phone")
        val w = bitmap.width
        val h = bitmap.height
        val codec = MediaCodec.createByCodecName(name)
        val caps = codec.codecInfo.getCapabilitiesForType(kind.codecMime)
        val video = caps.videoCapabilities ?: run { codec.release(); error("${kind.name} encoder has no video capabilities") }
        val enc = caps.encoderCapabilities ?: run { codec.release(); error("${kind.name} encoder has no encoder capabilities") }
        val alignedW = (w + BLOCK - 1) / BLOCK * BLOCK
        val evenH = (h + 1) / 2 * 2
        // Tiles for anything bigger than one tile, or a size the encoder can't take in one piece.
        val grid = w > TILE || h > TILE || !video.isSizeSupported(alignedW, evenH)
        val tileW = if (grid) TILE else alignedW
        val tileH = if (grid) TILE else evenH
        val cols = if (grid) (w + TILE - 1) / TILE else 1
        val rows = if (grid) (h + TILE - 1) / TILE else 1
        val tiles = rows * cols

        val format = MediaFormat.createVideoFormat(kind.codecMime, tileW, tileH).apply {
            if (grid) {
                setInteger(MediaFormat.KEY_TILE_WIDTH, tileW)
                setInteger(MediaFormat.KEY_TILE_HEIGHT, tileH)
                setInteger(MediaFormat.KEY_GRID_COLUMNS, cols)
                setInteger(MediaFormat.KEY_GRID_ROWS, rows)
            }
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // The YUV below is full-range BT.601, the same as JPEG.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_FRAME_RATE, tiles)
            setInteger(MediaFormat.KEY_OPERATING_RATE, if (tiles > 1) 120 else 30)
            val q = quality.coerceIn(1, 100)
            if (enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
                val r = enc.qualityRange
                setInteger(MediaFormat.KEY_QUALITY, (r.lower + (r.upper - r.lower) * q / 100.0).roundToInt())
            } else {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    if (enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
                )
                // Frame rate = number of tiles, so this is the bits for the whole picture.
                val bits = (w.toDouble() * h * 1.5 * 8 * MAX_COMPRESS_RATIO * q / 100.0).toInt()
                setInteger(MediaFormat.KEY_BIT_RATE, video.bitrateRange.clamp(bits))
            }
        }

        out.delete()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF)
            muxer = mux
            val pixels = IntArray(tileW * tileH)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var queued = 0
            var eosQueued = false
            var written = 0
            var done = false
            val deadline = System.currentTimeMillis() + 120_000
            while (!done) {
                check(System.currentTimeMillis() < deadline) { "${kind.name} encoder timed out" }
                if (!eosQueued) {
                    val ii = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        if (queued < tiles) {
                            val image = codec.getInputImage(ii) ?: error("${kind.name} encoder gave no input image")
                            val left = (queued % cols) * tileW
                            val top = (queued / cols) * tileH
                            fillTile(bitmap, left, top, tileW, tileH, pixels)
                            writeYuv(pixels, tileW, tileH, image)
                            codec.queueInputBuffer(ii, 0, codec.getInputBuffer(ii)!!.capacity(), queued * 1_000_000L / tiles, 0)
                            queued++
                        } else {
                            codec.queueInputBuffer(ii, 0, 0, tiles * 1_000_000L / tiles, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        f.setString(MediaFormat.KEY_MIME, kind.trackMime)
                        f.setInteger(MediaFormat.KEY_WIDTH, if (grid) w else min(w, tileW))
                        f.setInteger(MediaFormat.KEY_HEIGHT, if (grid) h else min(h, tileH))
                        if (grid) {
                            f.setInteger(MediaFormat.KEY_TILE_WIDTH, tileW)
                            f.setInteger(MediaFormat.KEY_TILE_HEIGHT, tileH)
                            f.setInteger(MediaFormat.KEY_GRID_ROWS, rows)
                            f.setInteger(MediaFormat.KEY_GRID_COLUMNS, cols)
                        }
                        f.setInteger(MediaFormat.KEY_IS_DEFAULT, 1)
                        track = mux.addTrack(f)
                        mux.start()
                        muxerStarted = true
                    }
                    oi >= 0 -> {
                        val buf = codec.getOutputBuffer(oi)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !config && written < tiles) {
                            check(muxerStarted) { "Encoder output before its format" }
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val sample = MediaCodec.BufferInfo().apply { set(info.offset, info.size, 0, 0) }
                            mux.writeSampleData(track, buf, sample)
                            written++
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || written == tiles) done = true
                    }
                }
            }
            check(written == tiles) { "${kind.name} encoder wrote $written of $tiles tiles" }
            mux.stop()
            muxerStarted = false
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** Copies one tile of [bmp] into [dst]; past the photo's right and bottom edges the last pixels are repeated. */
    private fun fillTile(bmp: Bitmap, left: Int, top: Int, tw: Int, th: Int, dst: IntArray) {
        val cw = max(0, min(tw, bmp.width - left))
        val ch = max(0, min(th, bmp.height - top))
        if (cw > 0 && ch > 0) bmp.getPixels(dst, 0, tw, left, top, cw, ch)
        for (y in 0 until th) {
            val sy = min(y, ch - 1)
            if (y >= ch) System.arraycopy(dst, sy * tw, dst, y * tw, cw)
            val last = dst[y * tw + cw - 1]
            for (x in cw until tw) dst[y * tw + x] = last
        }
    }

    /** ARGB → full-range BT.601 YUV 4:2:0 into whatever plane layout the encoder's image uses. */
    private fun writeYuv(px: IntArray, w: Int, h: Int, image: android.media.Image) {
        val planes = image.planes
        val yb = planes[0].buffer; val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        val ub = planes[1].buffer; val uRow = planes[1].rowStride; val uPix = planes[1].pixelStride
        val vb = planes[2].buffer; val vRow = planes[2].rowStride; val vPix = planes[2].pixelStride
        for (y in 0 until h) {
            val row = y * w
            val yo = y * yRow
            for (x in 0 until w) {
                val c = px[row + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                yb.put(yo + x * yPix, clamp8((19595 * r + 38470 * g + 7471 * b + 32768) shr 16))
            }
        }
        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = px[(2 * y + dy) * w + 2 * x + dx]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                // Sums of 4 pixels: the coefficients are divided by 4 through the extra shift.
                val u = ((-11059 * r - 21709 * g + 32768 * b + (128 shl 18) + (1 shl 17)) shr 18)
                val v = ((32768 * r - 27439 * g - 5329 * b + (128 shl 18) + (1 shl 17)) shr 18)
                ub.put(y * uRow + x * uPix, clamp8(u))
                vb.put(y * vRow + x * vPix, clamp8(v))
            }
        }
    }

    private fun clamp8(v: Int): Byte = v.coerceIn(0, 255).toByte()
}
