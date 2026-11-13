package com.vknxworks.lumenctrl.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30 as GL
import android.opengl.GLSurfaceView
import android.view.Surface
import com.vknxworks.lumenctrl.model.EditParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Copies a video frame from its OES external texture (as MediaPlayer / MediaCodec
 * deliver it) into an upright RGBA texture with mipmaps, in the same row order as
 * photos, so the develop shader treats video frames exactly like photos.
 * The SurfaceTexture matrix already includes the video's rotation metadata.
 */
class VideoBlit {
    private var program = 0
    private var vbo = 0
    private var fbo = 0
    var texture = 0; private set
    var width = 0; private set
    var height = 0; private set

    fun init() {
        val vs = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            uniform mat4 u_st;
            out vec2 vTc;
            void main() {
                vec2 uv = aPos * 0.5 + 0.5;
                uv.y = 1.0 - uv.y;               // row 0 = top, like GLUtils-uploaded photos
                vTc = (u_st * vec4(uv, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()
        val fs = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            uniform samplerExternalOES u_oes;
            in vec2 vTc;
            out vec4 color;
            void main() { color = vec4(texture(u_oes, vTc).rgb, 1.0); }
        """.trimIndent()
        program = link(vs, fs)
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val ids = IntArray(1)
        GL.glGenBuffers(1, ids, 0); vbo = ids[0]
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, 32, buf, GL.GL_STATIC_DRAW)
        GL.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        texture = 0; width = 0; height = 0
    }

    fun createOesTexture(): Int {
        val ids = IntArray(1)
        GL.glGenTextures(1, ids, 0)
        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    /** Renders the current OES frame into the w×h target texture and rebuilds its mipmaps. */
    fun blit(oes: Int, stMatrix: FloatArray, w: Int, h: Int): Int {
        if (texture == 0 || w != width || h != height) {
            if (texture != 0) GL.glDeleteTextures(1, intArrayOf(texture), 0)
            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0); texture = ids[0]
            GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
            GL.glTexStorage2D(GL.GL_TEXTURE_2D, levels(w, h), GL.GL_RGBA8, w, h)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            width = w; height = h
        }
        val prevFbo = IntArray(1)
        GL.glGetIntegerv(GL.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, fbo)
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, texture, 0)
        GL.glViewport(0, 0, w, h)
        GL.glUseProgram(program)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oes)
        GL.glUniform1i(GL.glGetUniformLocation(program, "u_oes"), 0)
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(program, "u_st"), 1, false, stMatrix, 0)
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo)
        GL.glEnableVertexAttribArray(0)
        GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 0, 0)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
        GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, prevFbo[0])
        return texture
    }

    fun release() {
        if (program != 0) GL.glDeleteProgram(program)
        if (vbo != 0) GL.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (fbo != 0) GL.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (texture != 0) GL.glDeleteTextures(1, intArrayOf(texture), 0)
        program = 0; vbo = 0; fbo = 0; texture = 0
    }

    private fun levels(w: Int, h: Int): Int {
        var n = 1; var s = max(w, h)
        while (s > 1) { s /= 2; n++ }
        return n
    }

    companion object {
        private fun compile(type: Int, src: String): Int {
            val s = GL.glCreateShader(type)
            GL.glShaderSource(s, src); GL.glCompileShader(s)
            val ok = IntArray(1); GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Video shader: " + GL.glGetShaderInfoLog(s))
            return s
        }
        fun link(vs: String, fs: String): Int {
            val p = GL.glCreateProgram()
            GL.glAttachShader(p, compile(GL.GL_VERTEX_SHADER, vs))
            GL.glAttachShader(p, compile(GL.GL_FRAGMENT_SHADER, fs))
            GL.glLinkProgram(p)
            val ok = IntArray(1); GL.glGetProgramiv(p, GL.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Video link: " + GL.glGetProgramInfoLog(p))
            return p
        }

        /** Even working size for a frame: upright dims scaled to [maxEdge]. */
        fun workSize(uprightW: Int, uprightH: Int, maxEdge: Int): Pair<Int, Int> {
            val s = minOf(1f, maxEdge / max(uprightW, uprightH).toFloat())
            return ((uprightW * s / 2).roundToInt() * 2).coerceAtLeast(2) to ((uprightH * s / 2).roundToInt() * 2).coerceAtLeast(2)
        }
    }
}

/**
 * Live video preview: MediaPlayer renders into a SurfaceTexture on this GL thread;
 * each new frame is blitted and graded with the photo develop shader.
 */
class VideoRenderer(private val uprightW: Int, private val uprightH: Int) : GLSurfaceView.Renderer {
    @Volatile var params = EditParams()
    @Volatile var options = DrawOptions()
    /** Called (on the GL thread) when the Surface for MediaPlayer is ready. */
    var onSurface: ((Surface) -> Unit)? = null
    var requestRender: (() -> Unit)? = null

    private val program = EditProgram()
    private val blit = VideoBlit()
    private var oes = 0
    private var st: SurfaceTexture? = null
    private val matrix = FloatArray(16)
    private var viewW = 1
    private var viewH = 1
    @Volatile private var frameReady = false
    private var hasFrame = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program.init()
        blit.init()
        oes = blit.createOesTexture()
        st?.release()
        val tex = SurfaceTexture(oes)
        tex.setOnFrameAvailableListener { frameReady = true; requestRender?.invoke() }
        st = tex
        hasFrame = false
        onSurface?.invoke(Surface(tex))
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { viewW = width; viewH = height }

    override fun onDrawFrame(gl: GL10?) {
        val tex = st ?: return
        if (frameReady) {
            frameReady = false
            tex.updateTexImage()
            tex.getTransformMatrix(matrix)
            hasFrame = true
        }
        val opt = options
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, viewW, viewH)
        GL.glClearColor(opt.groundR, opt.groundG, opt.groundB, 1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        if (!hasFrame) return
        // Preview grades a ≤1280 px copy; export uses full size.
        val (w, h) = VideoBlit.workSize(uprightW, uprightH, 1280)
        val src = blit.blit(oes, matrix, w, h)
        val p = params
        val r = EditorRenderer.fitRect(viewW, viewH, Geometry.outputAspect(p, w, h, opt.ignoreCrop))
        GL.glViewport(r.x, r.y, r.w, r.h)
        program.draw(src, w, h, r.w, r.h, p, opt)
    }

    fun release() { st?.release(); st = null }
}
