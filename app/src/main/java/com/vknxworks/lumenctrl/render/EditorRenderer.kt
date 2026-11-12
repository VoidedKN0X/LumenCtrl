package com.vknxworks.lumenctrl.render

import android.graphics.Bitmap
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import com.vknxworks.lumenctrl.model.EditParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.roundToInt

// Set true to log GPU time per preview frame (tag LumenGL). Measured on a Mali GPU,
// Android 17: about 3.8 ms average for a 1165x1553 preview of a 1536x2048 texture
// with the original single-pass shader.
private const val PROFILE = false

/** 64-bin histograms of the rendered result, per channel and luminance. */
class Histogram(val r: IntArray, val g: IntArray, val b: IntArray, val l: IntArray)

/**
 * Live preview renderer. The UI thread swaps [params]/[options] and calls
 * requestRender(); the GL thread redraws the preview from the untouched
 * source texture each time (Lightroom's equivalent is ICBRenderAsync).
 */
class EditorRenderer : GLSurfaceView.Renderer {
    @Volatile var params = EditParams()
    @Volatile var options = DrawOptions()
    /** Called on the GL thread after each frame when set. */
    @Volatile var onHistogram: ((Histogram) -> Unit)? = null

    private val lock = Any()
    private var pendingBitmap: Bitmap? = null
    private var needsUpload = false

    private val program = EditProgram()
    private var texture = 0
    private var texW = 0
    private var texH = 0
    private var viewW = 1
    private var viewH = 1

    private var histFbo = 0
    private var histTex = 0
    private val histSize = 96
    private val histBuf = ByteBuffer.allocateDirect(96 * 96 * 4).order(ByteOrder.nativeOrder())

    fun setImage(bitmap: Bitmap) = synchronized(lock) {
        pendingBitmap = bitmap
        needsUpload = true
    }

    private var pendingAtlas: Bitmap? = null
    private var atlasDirty = false
    private var aiTex = 0

    /** AI mask atlas (AiAtlas.build) for the current edit, or null. */
    fun setAiAtlas(bitmap: Bitmap?) = synchronized(lock) {
        pendingAtlas = bitmap
        atlasDirty = true
    }

    private var pendingGain: SourceGain? = null
    private var gainDirty = false
    private var gainTex: GainTexture? = null

    /** The photo's own Ultra HDR gain map, or null for SDR photos. */
    fun setSourceGain(g: SourceGain?) = synchronized(lock) {
        pendingGain = g
        gainDirty = true
    }

    private var pendingDn: Bitmap? = null
    private var dnDirty = false
    private var dnTex: DenoiseTexture? = null

    /** The photo's AI-denoised base, or null. */
    fun setDenoised(b: Bitmap?) = synchronized(lock) {
        pendingDn = b
        dnDirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Also runs after the context is lost, so rebuild everything.
        program.init()
        texture = 0
        histFbo = 0
        aiTex = 0
        gainTex = null
        dnTex = null
        synchronized(lock) { needsUpload = pendingBitmap != null; atlasDirty = true; gainDirty = true; dnDirty = true }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(lock) {
            if (needsUpload) {
                pendingBitmap?.takeIf { !it.isRecycled }?.let {
                    if (texture != 0) GL.glDeleteTextures(1, intArrayOf(texture), 0)
                    texture = EditProgram.uploadTexture(it)
                    texW = it.width
                    texH = it.height
                }
                needsUpload = false
            }
            if (atlasDirty) {
                if (aiTex != 0) { GL.glDeleteTextures(1, intArrayOf(aiTex), 0); aiTex = 0 }
                pendingAtlas?.takeIf { !it.isRecycled }?.let { aiTex = EditProgram.uploadMaskTexture(it) }
                atlasDirty = false
            }
            if (gainDirty) {
                gainTex?.let { GL.glDeleteTextures(1, intArrayOf(it.id), 0) }
                gainTex = pendingGain?.let { g -> GainTexture(EditProgram.uploadMaskTexture(g.contents), g) }
                gainDirty = false
            }
            if (dnDirty) {
                dnTex?.let { GL.glDeleteTextures(1, intArrayOf(it.id), 0) }
                dnTex = pendingDn?.takeIf { !it.isRecycled }?.let { DenoiseTexture.upload(it) }
                dnDirty = false
            }
        }
        val opt = options
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, viewW, viewH)
        GL.glClearColor(opt.groundR, opt.groundG, opt.groundB, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        if (texture == 0) return

        val p = params
        val (fx, fy, fw, fh) = fitRect(viewW, viewH, Geometry.outputAspect(p, texW, texH, opt.ignoreCrop))
        // Zoom/pan just enlarges and shifts the viewport; GL clips to the surface.
        val w = (fw * opt.zoom).roundToInt().coerceAtLeast(1)
        val h = (fh * opt.zoom).roundToInt().coerceAtLeast(1)
        val x = (fx + (fw - w) / 2f + opt.panX).roundToInt()
        val y = (fy + (fh - h) / 2f - opt.panY).roundToInt()
        GL.glViewport(x, y, w, h)
        val t0 = System.nanoTime()
        program.draw(texture, texW, texH, w, h, p, opt, aiTex, gainTex, dnTex)
        if (PROFILE) {
            GL.glFinish()
            android.util.Log.d("LumenGL", "frame ${w}x$h tex ${texW}x$texH: ${(System.nanoTime() - t0) / 1_000_000.0} ms")
        }
        onHistogram?.let { cb -> cb(computeHistogram(p, opt)) }
    }

    private fun computeHistogram(p: EditParams, opt: DrawOptions): Histogram {
        if (histFbo == 0) {
            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0); histTex = ids[0]
            GL.glBindTexture(GL.GL_TEXTURE_2D, histTex)
            GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, histSize, histSize, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
            GL.glGenFramebuffers(1, ids, 0); histFbo = ids[0]
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, histFbo)
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, histTex, 0)
        }
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, histFbo)
        GL.glViewport(0, 0, histSize, histSize)
        program.draw(texture, texW, texH, histSize, histSize, p, opt.copy(showMask = -1, ignoreCrop = false, zoom = 1f, panX = 0f, panY = 0f, vizHdr = false), aiTex, gainTex, dnTex)
        histBuf.rewind()
        GL.glReadPixels(0, 0, histSize, histSize, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, histBuf)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        val r = IntArray(64); val g = IntArray(64); val b = IntArray(64); val l = IntArray(64)
        for (i in 0 until histSize * histSize) {
            val rv = histBuf.get(i * 4).toInt() and 0xFF
            val gv = histBuf.get(i * 4 + 1).toInt() and 0xFF
            val bv = histBuf.get(i * 4 + 2).toInt() and 0xFF
            r[rv shr 2]++; g[gv shr 2]++; b[bv shr 2]++
            l[((rv * 54 + gv * 183 + bv * 19) shr 8) shr 2]++
        }
        return Histogram(r, g, b, l)
    }

    companion object {
        data class Rect4(val x: Int, val y: Int, val w: Int, val h: Int)

        /** Largest rect of [aspect] centred in the view (GL coordinates, origin bottom-left). */
        fun fitRect(viewW: Int, viewH: Int, aspect: Float): Rect4 {
            val scale = min(viewW / aspect, viewH.toFloat())
            val w = (scale * aspect).roundToInt().coerceAtLeast(1)
            val h = scale.roundToInt().coerceAtLeast(1)
            return Rect4((viewW - w) / 2, (viewH - h) / 2, w, h)
        }
    }
}
