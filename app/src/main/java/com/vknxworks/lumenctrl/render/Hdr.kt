package com.vknxworks.lumenctrl.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.ln
import kotlin.math.max

/**
 * An Ultra HDR source's gain map, upright and aligned with the decoded photo.
 * Per pixel, HDR = (SDR + epsSdr) * 2^mix(logMin, logMax, gm^(1/gamma)) - epsHdr
 * (the ISO 21496-1 / Ultra HDR formula), averaged to one channel here.
 */
class SourceGain(
    val contents: Bitmap,
    val logMin: Float,
    val logMax: Float,
    val gamma: Float,
    val epsSdr: Float,
    val epsHdr: Float,
    /** Display brightness ratio at which the photo shows its full HDR. */
    val fullRatio: Float,
)

object Hdr {
    /** Gain maps (read and write) need Android 14. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 34

    private fun log2(v: Float) = (ln(max(v, 1e-6f).toDouble()) / ln(2.0)).toFloat()

    /** Reads an Ultra HDR photo's gain map, or null for SDR photos / older Android. */
    fun readSourceGain(file: File): SourceGain? {
        if (!supported) return null
        return runCatching { readApi34(file) }.getOrNull()
    }

    @RequiresApi(34)
    private fun readApi34(file: File): SourceGain? {
        // Decode small: we only need the gain map, which is low resolution anyway.
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val gm = bmp.gainmap
        bmp.recycle()
        gm ?: return null
        val src = gm.gainmapContents
        // Normalize to opaque gray ARGB (R = gain) and apply the same EXIF orientation as the photo.
        val w = src.width; val h = src.height
        val out = IntArray(w * h)
        if (src.config == Bitmap.Config.ALPHA_8) {
            val a = java.nio.ByteBuffer.allocate(src.byteCount)
            src.copyPixelsToBuffer(a)
            val stride = src.rowBytes
            for (y in 0 until h) for (x in 0 until w) {
                val v = a.get(y * stride + x).toInt() and 0xFF
                out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        } else {
            src.getPixels(out, 0, w, 0, 0, w, h)
            for (i in out.indices) {
                val c = out[i]
                val v = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
                out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        var gray = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        orientation(file)?.let { m -> gray = Bitmap.createBitmap(gray, 0, 0, w, h, m, true) }
        val rMin = gm.ratioMin; val rMax = gm.ratioMax; val g = gm.gamma
        return SourceGain(
            contents = gray,
            logMin = (log2(rMin[0]) + log2(rMin[1]) + log2(rMin[2])) / 3f,
            logMax = (log2(rMax[0]) + log2(rMax[1]) + log2(rMax[2])) / 3f,
            gamma = ((g[0] + g[1] + g[2]) / 3f).coerceAtLeast(0.01f),
            epsSdr = gm.epsilonSdr.average().toFloat(),
            epsHdr = gm.epsilonHdr.average().toFloat(),
            fullRatio = gm.displayRatioForFullHdr,
        )
    }

    private fun orientation(file: File): Matrix? {
        val o = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val m = Matrix()
        when (o) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            else -> return null
        }
        return m
    }

    /**
     * Attaches a gain map to [sdr] so Bitmap.compress(JPEG) writes an Ultra HDR file.
     * [gain] holds log2(boost)/stops in its red channel, 0..1; [stops] is the HDR limit.
     */
    fun attachGainmap(sdr: Bitmap, gain: Bitmap, stops: Float) {
        if (!supported) return
        attachApi34(sdr, gain, stops)
    }

    @RequiresApi(34)
    private fun attachApi34(sdr: Bitmap, gain: Bitmap, stops: Float) {
        val ratio = Math.pow(2.0, stops.toDouble()).toFloat()
        val gm = Gainmap(gain)
        gm.setRatioMin(1f, 1f, 1f)
        gm.setRatioMax(ratio, ratio, ratio)
        gm.setGamma(1f, 1f, 1f)
        gm.setEpsilonSdr(0f, 0f, 0f)
        gm.setEpsilonHdr(0f, 0f, 0f)
        gm.minDisplayRatioForHdrTransition = 1f
        gm.displayRatioForFullHdr = ratio
        sdr.gainmap = gm
    }

    /** Whether this screen can show HDR (Android 14+ reports an HDR/SDR ratio). */
    fun screenSupportsHdr(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return context.display?.isHdrSdrRatioAvailable == true
    }

    fun displayIsHdr(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        @Suppress("DEPRECATION")
        return context.display?.isHdr == true
    }
}
