package com.vknxworks.lumenctrl.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object ImageLoader {
    private val thumbCache = LruCache<String, ImageBitmap>(150)

    /** Decodes [file] so its long edge is at most [maxDim], upright per EXIF, in ARGB_8888. */
    fun decode(file: File, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image format" }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.path, opts) ?: error("Could not decode image")

        val long = max(bmp.width, bmp.height)
        if (long > maxDim) {
            val s = maxDim / long.toFloat()
            val scaled = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * s).roundToInt().coerceAtLeast(1),
                (bmp.height * s).roundToInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        bmp = applyExifOrientation(file, bmp)
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle()
            bmp = copy
        }
        return bmp
    }

    /** Shows the rendered edit thumbnail if there is one, otherwise the original (or a video frame). */
    fun thumbnail(photo: Catalog.Photo): ImageBitmap? {
        val edited = photo.thumb.exists()
        val key = "${photo.id}:${if (edited) photo.thumb.lastModified() else 0}"
        thumbCache.get(key)?.let { return it }
        return runCatching {
            when {
                edited -> decode(photo.thumb, 384)
                photo.isVideo -> videoFrame(photo.original, 1000, 384, photo.meta.width, photo.meta.height)!!
                else -> decode(photo.original, 384)
            }.asImageBitmap()
        }.getOrNull()?.also { thumbCache.put(key, it) }
    }

    /** A photo, or a video's frame, ready for rendering. */
    fun decodeAny(photo: Catalog.Photo, maxDim: Int, videoTimeMs: Long = 1000): Bitmap =
        if (photo.isVideo) videoFrame(photo.original, videoTimeMs, maxDim, photo.meta.width, photo.meta.height)
            ?: error("Can't read a frame from this video")
        else decode(photo.original, maxDim)

    /**
     * One video frame, upright, long edge [maxDim]. Some Android versions return frames
     * in coded orientation; if the frame's shape disagrees with the stored upright size,
     * it is rotated to match.
     */
    fun videoFrame(file: File, timeMs: Long, maxDim: Int, uprightW: Int = 0, uprightH: Int = 0): Bitmap? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val raw = r.getFrameAtTime(timeMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            var bmp = raw
            val wantPortrait = uprightW in 1 until uprightH
            if (rot % 180 != 0 && uprightW > 0 && (bmp.width < bmp.height) != wantPortrait) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            }
            val long = max(bmp.width, bmp.height)
            if (long > maxDim) {
                val s = maxDim / long.toFloat()
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).roundToInt().coerceAtLeast(1), (bmp.height * s).roundToInt().coerceAtLeast(1), true)
            }
            if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }

    private fun applyExifOrientation(file: File, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }
}
