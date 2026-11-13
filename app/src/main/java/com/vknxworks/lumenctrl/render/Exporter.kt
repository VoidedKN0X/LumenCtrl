package com.vknxworks.lumenctrl.render

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30 as GL
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.res.ResourcesCompat
import androidx.exifinterface.media.ExifInterface
import com.vknxworks.lumenctrl.R
import com.vknxworks.lumenctrl.model.EditParams
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    JPEG("JPEG", "jpg", "image/jpeg"), PNG("PNG", "png", "image/png"), WEBP("WebP", "webp", "image/webp"),
}

enum class MetadataMode(val label: String) {
    NO_LOCATION("Keep, remove location"), ALL("Keep all"), NONE("Strip all"),
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 92,
    /** Long edge cap in pixels; null = full resolution (up to 6000 px). */
    val maxDim: Int? = null,
    val metadata: MetadataMode = MetadataMode.NO_LOCATION,
    val watermark: String? = null,
    /** Embed the edit settings in EXIF (Lumen's unsigned stand-in for content credentials). */
    val embedEditRecord: Boolean = true,
    /** Write Ultra HDR (JPEG + gain map) when the edit has HDR on. */
    val hdr: Boolean = true,
)

/**
 * Renders the edit off-screen in a private EGL context with the same shader as
 * the preview. Call from a background thread. Nothing here touches the network.
 */
object Exporter {
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    const val FULL_RES = 6000

    /** [maxOut] caps the long edge of the result; null means source resolution. */
    fun render(
        source: Bitmap, params: EditParams, maxOut: Int? = null, aiAtlas: Bitmap? = null,
        gain: SourceGain? = null,
        /** Attach an Ultra HDR gain map when the edit has HDR on (Android 14+). */
        withGainmap: Boolean = false,
        /** AI-denoised base (see Denoiser), used when the edit's AI Denoise amount is above 0. */
        denoised: Bitmap? = null,
    ): Bitmap {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE,
            ), 0, configs, 0, 1, count, 0,
        )
        check(count[0] > 0) { "No EGL config for OpenGL ES 3" }
        val config = configs[0]
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        val surface = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        val program = EditProgram()
        var srcTex = 0
        var outTex = 0
        var fbo = 0
        var aiTex = 0
        var gainTex: GainTexture? = null
        var dnTex: DenoiseTexture? = null
        try {
            val maxTexArr = IntArray(1)
            GL.glGetIntegerv(GL.GL_MAX_TEXTURE_SIZE, maxTexArr, 0)
            val maxTex = maxTexArr[0]

            var src = source
            if (max(src.width, src.height) > maxTex) {
                val s = maxTex / max(src.width, src.height).toFloat()
                src = Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
            }

            program.init()
            srcTex = EditProgram.uploadTexture(src)
            if (aiAtlas != null) aiTex = EditProgram.uploadMaskTexture(aiAtlas)
            if (gain != null) gainTex = GainTexture(EditProgram.uploadMaskTexture(gain.contents), gain)
            if (denoised != null && !denoised.isRecycled && params[com.vknxworks.lumenctrl.model.Param.AI_DENOISE] > 0f) {
                dnTex = DenoiseTexture.upload(denoised)
            }

            // Output size: cropped frame at source pixel density.
            val aspect = Geometry.outputAspect(params, src.width, src.height, ignoreCrop = false)
            val (ow, oh) = Geometry.orientedSize(src.width, src.height, params.quarterTurns)
            val frameH = oh * params.crop.h
            var outH = frameH
            var outW = outH * aspect
            if (outW > ow * params.crop.w * 1.01f) { outW = ow * params.crop.w; outH = outW / aspect }
            val cap = min(maxOut ?: Int.MAX_VALUE, maxTex).toFloat()
            if (max(outW, outH) > cap) {
                val s = cap / max(outW, outH)
                outW *= s; outH *= s
            }
            val w = outW.roundToInt().coerceAtLeast(1)
            val h = outH.roundToInt().coerceAtLeast(1)

            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0); outTex = ids[0]
            GL.glBindTexture(GL.GL_TEXTURE_2D, outTex)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, w, h, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
            GL.glGenFramebuffers(1, ids, 0); fbo = ids[0]
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, outTex, 0)
            check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete" }

            GL.glViewport(0, 0, w, h)
            // flipY so glReadPixels' bottom-up rows come out top-down in the Bitmap.
            program.draw(srcTex, src.width, src.height, w, h, params, DrawOptions(flipY = true), aiTex, gainTex, dnTex)

            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GL.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.copyPixelsFromBuffer(buf)

            if (withGainmap && Hdr.supported && params[com.vknxworks.lumenctrl.model.Param.HDR_ON] >= 0.5f) {
                // Second pass: the gain map (log2 boost / HDR limit) at the same size.
                program.draw(srcTex, src.width, src.height, w, h, params, DrawOptions(flipY = true, outMode = 1), aiTex, gainTex, dnTex)
                buf.rewind()
                GL.glReadPixels(0, 0, w, h, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buf)
                buf.rewind()
                val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                full.copyPixelsFromBuffer(buf)
                // Gain maps are smooth; half resolution keeps files small.
                val gm = Bitmap.createScaledBitmap(full, (w / 2).coerceAtLeast(1), (h / 2).coerceAtLeast(1), true)
                if (gm !== full) full.recycle()
                Hdr.attachGainmap(out, gm, params[com.vknxworks.lumenctrl.model.Param.HDR_LIMIT])
            }
            if (src !== source) src.recycle()
            return out
        } finally {
            if (fbo != 0) GL.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (outTex != 0) GL.glDeleteTextures(1, intArrayOf(outTex), 0)
            if (srcTex != 0) GL.glDeleteTextures(1, intArrayOf(srcTex), 0)
            if (aiTex != 0) GL.glDeleteTextures(1, intArrayOf(aiTex), 0)
            gainTex?.let { GL.glDeleteTextures(1, intArrayOf(it.id), 0) }
            dnTex?.let { GL.glDeleteTextures(1, intArrayOf(it.id), 0) }
            program.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            // No eglTerminate: the display is shared with the live preview's GLSurfaceView.
        }
    }

    /** The photo's AI-denoised base at up to [maxDim], when the edit uses it and it exists. */
    fun loadDenoised(original: File, params: EditParams, maxDim: Int): Bitmap? {
        if (params[com.vknxworks.lumenctrl.model.Param.AI_DENOISE] <= 0f) return null
        val f = com.vknxworks.lumenctrl.edit.Denoiser.fileFor(original)
        if (!f.exists()) return null
        return runCatching { com.vknxworks.lumenctrl.data.ImageLoader.decode(f, maxDim) }.getOrNull()
    }

    /** Renders, encodes and applies the metadata policy. Returns the finished file in [outDir]. */
    fun renderToFile(
        context: Context, original: File, params: EditParams, options: ExportOptions,
        outDir: File, baseName: String, aiAtlas: Bitmap? = null,
    ): File {
        val src = com.vknxworks.lumenctrl.data.ImageLoader.decode(original, options.maxDim?.coerceAtMost(FULL_RES) ?: FULL_RES)
        val ultraHdr = options.hdr && options.format == ExportFormat.JPEG
        val dn = loadDenoised(original, params, max(src.width, src.height))
        val out = render(src, params, options.maxDim, aiAtlas, Hdr.readSourceGain(original), withGainmap = ultraHdr, denoised = dn)
        src.recycle()
        dn?.recycle()
        options.watermark?.takeIf { it.isNotBlank() }?.let { drawWatermark(context, out, it) }
        outDir.mkdirs()
        val file = File(outDir, "$baseName.${options.format.ext}")
        file.outputStream().use { stream ->
            val fmt = when (options.format) {
                ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                ExportFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) {
                    if (options.quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                } else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            out.compress(fmt, options.quality.coerceIn(1, 100), stream)
        }
        out.recycle()
        applyMetadata(original, file, params, options)
        return file
    }

    private val COPY_TAGS = listOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
    )
    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    private fun applyMetadata(original: File, file: File, params: EditParams, options: ExportOptions) {
        runCatching {
            val dst = ExifInterface(file.path)
            if (options.metadata != MetadataMode.NONE) {
                val src = ExifInterface(original.path)
                val tags = if (options.metadata == MetadataMode.ALL) COPY_TAGS + GPS_TAGS else COPY_TAGS
                tags.forEach { t -> src.getAttribute(t)?.let { dst.setAttribute(t, it) } }
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "LumenCtrl")
            if (options.embedEditRecord) {
                dst.setAttribute(ExifInterface.TAG_USER_COMMENT, "lumen-edit:" + params.toJson().replace("\n", "").replace("  ", ""))
            }
            dst.saveAttributes()
        }
    }

    private fun drawWatermark(context: Context, bmp: Bitmap, text: String) {
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textSize = bmp.height * 0.028f
            typeface = runCatching { ResourcesCompat.getFont(context, R.font.space_grotesk) }.getOrNull() ?: Typeface.DEFAULT_BOLD
            setShadowLayer(bmp.height * 0.004f, 0f, 0f, Color.argb(160, 0, 0, 0))
        }
        val margin = bmp.height * 0.03f
        canvas.drawText(text, bmp.width - margin - paint.measureText(text), bmp.height - margin, paint)
    }

    /** Copies a finished file into Pictures/Lumen through MediaStore (no storage permission needed). */
    fun saveToGallery(context: Context, file: File, mime: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Lumen")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return uri
    }

    /** Saves a bitmap to Pictures/Lumen as JPEG (used by the collage maker). */
    fun saveBitmapToGallery(context: Context, bitmap: Bitmap, name: String): Uri {
        val tmp = File(context.cacheDir, "$name.jpg")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        return saveToGallery(context, tmp, "image/jpeg").also { tmp.delete() }
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 85) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
