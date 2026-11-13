package com.vknxworks.lumenctrl.render

import android.graphics.Bitmap
import android.opengl.GLES30 as GL
import android.opengl.GLUtils
import com.vknxworks.lumenctrl.model.EditParams
import com.vknxworks.lumenctrl.model.MAX_MASKS
import com.vknxworks.lumenctrl.model.MAX_SPOTS
import com.vknxworks.lumenctrl.model.MaskAdj
import com.vknxworks.lumenctrl.model.Param
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Options for one draw call. */
data class DrawOptions(
    val before: Boolean = false,
    val flipY: Boolean = false,
    /** Show the whole frame (crop tool) instead of the cropped result. */
    val ignoreCrop: Boolean = false,
    /** Index of a mask to tint red, or -1. */
    val showMask: Int = -1,
    /** Letterbox / out-of-frame color, RGB 0..1. Plain floats so equality works. */
    val groundR: Float = 0.06f,
    val groundG: Float = 0.06f,
    val groundB: Float = 0.07f,
    /** Preview zoom (1 = fit) and pan in screen pixels, y down. Ignored by export. */
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** 0 = SDR image, 1 = HDR gain map (for Ultra HDR export / HDR view). */
    val outMode: Int = 0,
    /** Tint areas that go brighter than SDR white. */
    val vizHdr: Boolean = false,
)

/** Source gain map bound to a GL texture. */
class GainTexture(val id: Int, val info: SourceGain)

/** AI-denoised base bound to a GL texture. */
class DenoiseTexture(val id: Int, val w: Int, val h: Int) {
    companion object {
        fun upload(b: Bitmap) = DenoiseTexture(EditProgram.uploadTexture(b), b.width, b.height)
    }
}

/** The compiled develop shader, a full-screen quad and the curve LUT. One instance per GL context. */
class EditProgram {
    private var program = 0
    private var vbo = 0
    private var lutTex = 0
    private var lutSig: Any? = null
    private val locations = HashMap<String, Int>()
    private val packed = FloatArray(Param.PACKED_VEC4 * 4)
    private val maskBuf = FloatArray(MAX_MASKS * 5 * 4)
    private val spotBuf = FloatArray(MAX_SPOTS * 2 * 4)

    fun init() {
        program = link(Shaders.VERTEX, Shaders.FRAGMENT)
        locations.clear()
        lutSig = null
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        buf.position(0)
        val ids = IntArray(1)
        GL.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo)
        GL.glBufferData(GL.GL_ARRAY_BUFFER, quad.size * 4, buf, GL.GL_STATIC_DRAW)
        GL.glGenTextures(1, ids, 0)
        lutTex = ids[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, lutTex)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        uploadLut(CurveLut.build(EditParams()))
        // 1x1 black stand-in when a photo has no AI masks.
        GL.glGenTextures(1, ids, 0)
        blackTex = ids[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D, blackTex)
        val black = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).put(byteArrayOf(0, 0, 0, -1))
        black.position(0)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, 1, 1, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, black)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_NEAREST)
        GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_NEAREST)
    }

    private var blackTex = 0

    private fun uploadLut(bytes: ByteArray) {
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        buf.position(0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, lutTex)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA8, 256, 1, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, buf)
    }

    /** Draws into the current viewport, which must be [outW]x[outH]. */
    fun draw(
        texture: Int, texW: Int, texH: Int, outW: Int, outH: Int, params: EditParams,
        opt: DrawOptions = DrawOptions(),
        /** RGB atlas of AI masks (see AiAtlas), or 0. */
        aiTexture: Int = 0,
        /** The source photo's Ultra HDR gain map, if it has one. */
        gain: GainTexture? = null,
        /** AI-denoised base (mipmapped), or null. */
        denoised: DenoiseTexture? = null,
    ) {
        val curveOn = CurveLut.isActive(params)
        if (curveOn) {
            val sig = CurveLut.signature(params)
            if (sig != lutSig) { uploadLut(CurveLut.build(params)); lutSig = sig }
        }

        GL.glUseProgram(program)
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glBindTexture(GL.GL_TEXTURE_2D, texture)
        GL.glUniform1i(loc("u_tex"), 0)
        GL.glActiveTexture(GL.GL_TEXTURE1)
        GL.glBindTexture(GL.GL_TEXTURE_2D, lutTex)
        GL.glUniform1i(loc("u_lut"), 1)
        GL.glActiveTexture(GL.GL_TEXTURE2)
        GL.glBindTexture(GL.GL_TEXTURE_2D, if (aiTexture != 0) aiTexture else blackTex)
        GL.glUniform1i(loc("u_ai"), 2)
        GL.glActiveTexture(GL.GL_TEXTURE3)
        GL.glBindTexture(GL.GL_TEXTURE_2D, gain?.id ?: blackTex)
        GL.glUniform1i(loc("u_gain"), 3)
        GL.glActiveTexture(GL.GL_TEXTURE4)
        GL.glBindTexture(GL.GL_TEXTURE_2D, denoised?.id ?: blackTex)
        GL.glUniform1i(loc("u_dn"), 4)
        GL.glUniform1i(loc("u_dnOn"), if (denoised != null) 1 else 0)
        if (denoised != null) {
            val ratio = maxOf(texW, texH).toFloat() / maxOf(denoised.w, denoised.h)
            GL.glUniform1f(loc("u_dnLod"), (Math.log(ratio.toDouble()) / Math.log(2.0)).toFloat())
        }
        GL.glActiveTexture(GL.GL_TEXTURE0)
        GL.glUniform1i(loc("u_gainOn"), if (gain != null) 1 else 0)
        if (gain != null) {
            GL.glUniform3f(loc("u_gainParams"), gain.info.logMin, gain.info.logMax, gain.info.gamma)
            GL.glUniform2f(loc("u_gainEps"), gain.info.epsSdr, gain.info.epsHdr)
        }
        GL.glUniform1i(loc("u_outMode"), opt.outMode)
        GL.glUniform1i(loc("u_vizHdr"), if (opt.vizHdr) 1 else 0)

        GL.glUniform2f(loc("u_texSize"), texW.toFloat(), texH.toFloat())
        GL.glUniform2f(loc("u_outSize"), outW.toFloat(), outH.toFloat())
        GL.glUniformMatrix3fv(loc("u_H"), 1, true, Geometry.outputToSource(params, texW, texH, opt.ignoreCrop), 0)
        GL.glUniform1f(loc("u_before"), if (opt.before) 1f else 0f)
        GL.glUniform1f(loc("u_flipY"), if (opt.flipY) 1f else 0f)
        GL.glUniform3f(loc("u_ground"), opt.groundR, opt.groundG, opt.groundB)
        GL.glUniform1i(loc("u_curveOn"), if (curveOn) 1 else 0)
        GL.glUniform1i(loc("u_mixOn"), if (MIXER.any { params[it] != 0f }) 1 else 0)

        packed.fill(0f)
        Param.entries.forEachIndexed { i, p -> packed[i] = params[p] }
        GL.glUniform4fv(loc("u_p"), Param.PACKED_VEC4, packed, 0)

        val masks = params.masks.take(MAX_MASKS)
        maskBuf.fill(0f)
        var aiChannel = 0
        masks.forEachIndexed { i, m ->
            val b = i * 20
            maskBuf[b] = m.x1; maskBuf[b + 1] = m.y1; maskBuf[b + 2] = m.x2; maskBuf[b + 3] = m.y2
            if (m.type == com.vknxworks.lumenctrl.model.MaskType.AI) {
                // AI masks read their own channel of the atlas; beyond 3 they read black.
                maskBuf[b] = if (aiChannel < 3 && aiTexture != 0) aiChannel.toFloat() else 0f
                if (aiChannel >= 3 || aiTexture == 0) maskBuf[b + 7] = 0f
                aiChannel++
            }
            maskBuf[b + 4] = m.type.ordinal.toFloat(); maskBuf[b + 5] = m.feather
            maskBuf[b + 6] = if (m.invert) 1f else 0f
            MaskAdj.entries.forEachIndexed { k, adj -> maskBuf[b + 8 + k] = m[adj] }
        }
        GL.glUniform1i(loc("u_maskCount"), masks.size)
        GL.glUniform4fv(loc("u_mask"), MAX_MASKS * 5, maskBuf, 0)
        GL.glUniform1i(loc("u_showMask"), opt.showMask)

        val spots = params.spots.take(MAX_SPOTS)
        spotBuf.fill(0f)
        spots.forEachIndexed { i, s ->
            val b = i * 8
            spotBuf[b] = s.tx; spotBuf[b + 1] = s.ty; spotBuf[b + 2] = s.sx; spotBuf[b + 3] = s.sy
            spotBuf[b + 4] = s.radius; spotBuf[b + 5] = s.feather; spotBuf[b + 6] = s.opacity
            spotBuf[b + 7] = if (s.clone) 1f else 0f
        }
        GL.glUniform1i(loc("u_spotCount"), spots.size)
        GL.glUniform4fv(loc("u_spot"), MAX_SPOTS * 2, spotBuf, 0)

        GL.glBindBuffer(GL.GL_ARRAY_BUFFER, vbo)
        GL.glEnableVertexAttribArray(0)
        GL.glVertexAttribPointer(0, 2, GL.GL_FLOAT, false, 0, 0)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        if (program != 0) GL.glDeleteProgram(program)
        if (vbo != 0) GL.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (lutTex != 0) GL.glDeleteTextures(1, intArrayOf(lutTex), 0)
        if (blackTex != 0) GL.glDeleteTextures(1, intArrayOf(blackTex), 0)
        program = 0; vbo = 0; lutTex = 0; blackTex = 0
    }

    // glGetUniformLocation returns -1 for uniforms the compiler optimized out; glUniform* ignores -1.
    private fun loc(name: String) = locations.getOrPut(name) { GL.glGetUniformLocation(program, name) }

    companion object {
        private val MIXER = Param.entries.filter { it.group == com.vknxworks.lumenctrl.model.Group.MIXER }

        /** Mask atlas: linear filtering, no mips. */
        fun uploadMaskTexture(bitmap: Bitmap): Int {
            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0)
            GL.glBindTexture(GL.GL_TEXTURE_2D, ids[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
            return ids[0]
        }

        /** Uploads with a full mip chain, which the shader samples for blurs and detail bands. */
        fun uploadTexture(bitmap: Bitmap): Int {
            val ids = IntArray(1)
            GL.glGenTextures(1, ids, 0)
            GL.glBindTexture(GL.GL_TEXTURE_2D, ids[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR_MIPMAP_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
            GL.glGenerateMipmap(GL.GL_TEXTURE_2D)
            return ids[0]
        }

        private fun compile(type: Int, src: String): Int {
            val s = GL.glCreateShader(type)
            GL.glShaderSource(s, src)
            GL.glCompileShader(s)
            val ok = IntArray(1)
            GL.glGetShaderiv(s, GL.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GL.glGetShaderInfoLog(s)
                GL.glDeleteShader(s)
                throw RuntimeException("Shader compile failed: $log")
            }
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val v = compile(GL.GL_VERTEX_SHADER, vs)
            val f = compile(GL.GL_FRAGMENT_SHADER, fs)
            val p = GL.glCreateProgram()
            GL.glAttachShader(p, v)
            GL.glAttachShader(p, f)
            GL.glLinkProgram(p)
            GL.glDeleteShader(v)
            GL.glDeleteShader(f)
            val ok = IntArray(1)
            GL.glGetProgramiv(p, GL.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GL.glGetProgramInfoLog(p)
                GL.glDeleteProgram(p)
                throw RuntimeException("Program link failed: $log")
            }
            return p
        }
    }
}
