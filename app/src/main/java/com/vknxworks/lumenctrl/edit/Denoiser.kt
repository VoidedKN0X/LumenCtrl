package com.vknxworks.lumenctrl.edit

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline AI denoise (Lumen's version of Lightroom's AI Denoise). Runs Raspberry Pi's
 * BSD-2-Clause NAFNet / U-Net models (github.com/raspberrypi/AI_denoise) with LiteRT on
 * the phone's CPU. Same contract as their reference code: RGB in [0,1], square patches,
 * 16 px overlap, reflection padding, linear blending in overlaps.
 * Rows are finished band by band, so memory stays at a few patch rows, not a full float image.
 */
object Denoiser {
    enum class Model(val asset: String, val label: String) {
        QUALITY("models/nafnet_rgb_large.tflite", "Quality"),
        FAST("models/unet_rgb_fast.tflite", "Fast"),
    }

    class Cancelled : Exception("Cancelled")

    private const val OVERLAP = 16

    private fun interpreter(context: Context, m: Model): Interpreter {
        val fd = context.assets.openFd(m.asset)
        val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return Interpreter(buf, Interpreter.Options().setNumThreads(max(2, Runtime.getRuntime().availableProcessors() - 2)))
    }

    /** numpy 'reflect' padding index (edge pixel not repeated). */
    private fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        var k = i
        while (k < 0 || k >= n) k = if (k < 0) -k else 2 * n - 2 - k
        return k
    }

    fun denoise(
        context: Context, src: Bitmap, model: Model,
        onProgress: (Float) -> Unit, isCancelled: () -> Boolean,
    ): Bitmap {
        val itp = interpreter(context, model)
        try {
            val inT = itp.getInputTensor(0)
            val outT = itp.getOutputTensor(0)
            val p = inT.shape()[1]
            val quant = inT.dataType() == DataType.INT8
            val inQ = inT.quantizationParams(); val outQ = outT.quantizationParams()
            val w = src.width; val h = src.height
            val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }

            val stride = p - OVERLAP
            val nW = (w - p + stride - 1) / stride + 1
            val nH = (h - p + stride - 1) / stride + 1
            val wPad = (nW - 1) * stride + p

            val ramp = FloatArray(p) { if (it < OVERLAP) it / (OVERLAP - 1f) else 1f }
            val bytesPer = if (quant) 1 else 4
            val input = ByteBuffer.allocateDirect(p * p * 3 * bytesPer).order(ByteOrder.nativeOrder())
            val output = ByteBuffer.allocateDirect(p * p * 3 * bytesPer).order(ByteOrder.nativeOrder())
            // Rolling accumulator: rows [bandTop, bandTop + p) of the padded image.
            val acc = FloatArray(p * wPad * 3)
            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rowBuf = IntArray(p * w)
            val patch = FloatArray(p * p * 3)
            val sumIn = DoubleArray(3); val sumOut = DoubleArray(3)
            android.util.Log.d("LumenDenoise", "${m(model)} patch $p ${inT.dataType()} ${w}x$h → ${nW * nH} patches")
            val total = nW * nH
            var done = 0

            for (i in 0 until nH) {
                val top = i * stride
                for (j in 0 until nW) {
                    if (isCancelled()) throw Cancelled()
                    val left = j * stride
                    input.rewind()
                    sumIn.fill(0.0)
                    for (y in 0 until p) {
                        val sy = reflect(top + y, h)
                        for (x in 0 until p) {
                            val c = px[sy * w + reflect(left + x, w)]
                            val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
                            sumIn[0] += r; sumIn[1] += g; sumIn[2] += b
                            if (quant) {
                                input.put((r / inQ.scale + inQ.zeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                                input.put((g / inQ.scale + inQ.zeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                                input.put((b / inQ.scale + inQ.zeroPoint).roundToInt().coerceIn(-128, 127).toByte())
                            } else { input.putFloat(r); input.putFloat(g); input.putFloat(b) }
                        }
                    }
                    input.rewind(); output.rewind()
                    itp.run(input, output)
                    output.rewind()
                    sumOut.fill(0.0)
                    for (k in 0 until p * p * 3) {
                        val v = if (quant) (output.get().toInt() - outQ.zeroPoint) * outQ.scale else output.getFloat()
                        patch[k] = v
                        sumOut[k % 3] += v
                    }
                    // Noise is zero-mean, so the input patch mean is the true mean. Models with
                    // global attention (NAFNet) drift per patch; pinning the mean removes the
                    // tile-shaped tone and color shifts that would otherwise show.
                    val n = (p * p).toDouble()
                    val d0 = ((sumIn[0] - sumOut[0]) / n).toFloat()
                    val d1 = ((sumIn[1] - sumOut[1]) / n).toFloat()
                    val d2 = ((sumIn[2] - sumOut[2]) / n).toFloat()
                    for (y in 0 until p) {
                        var wy = 1f
                        if (i != 0) wy *= ramp[y]
                        if (i != nH - 1) wy *= ramp[p - 1 - y]
                        val rowBase = (y * wPad + left) * 3
                        for (x in 0 until p) {
                            var wt = wy
                            if (j != 0) wt *= ramp[x]
                            if (j != nW - 1) wt *= ramp[p - 1 - x]
                            val k = (y * p + x) * 3
                            val o = rowBase + x * 3
                            acc[o] += (patch[k] + d0) * wt
                            acc[o + 1] += (patch[k + 1] + d1) * wt
                            acc[o + 2] += (patch[k + 2] + d2) * wt
                        }
                    }
                    done++
                    onProgress(done / total.toFloat())
                }
                // Rows [top, top + stride) are final (the last band finishes all its rows).
                val finalRows = min(if (i == nH - 1) p else stride, h - top)
                for (y in 0 until finalRows) {
                    for (x in 0 until w) {
                        val o = (y * wPad + x) * 3
                        val r = (acc[o] * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val g = (acc[o + 1] * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val b = (acc[o + 2] * 255f + 0.5f).toInt().coerceIn(0, 255)
                        rowBuf[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                if (finalRows > 0) result.setPixels(rowBuf, 0, w, 0, top, w, finalRows)
                // Shift the overlap rows up; clear the rest for the next band.
                System.arraycopy(acc, stride * wPad * 3, acc, 0, (p - stride) * wPad * 3)
                java.util.Arrays.fill(acc, (p - stride) * wPad * 3, acc.size, 0f)
            }
            restoreLowFrequencies(px, result)
            return result
        } finally {
            itp.close()
        }
    }

    private const val BLOCK = 8

    /**
     * Keeps the model's fine detail but the original's coarse tones: adds back a heavily
     * smoothed (orig - model) difference. Noise averages away at this scale, so what's left
     * is model drift (banding, color casts), which this removes.
     */
    private fun restoreLowFrequencies(px: IntArray, result: Bitmap) {
        val w = result.width; val h = result.height
        val bw = (w + BLOCK - 1) / BLOCK; val bh = (h + BLOCK - 1) / BLOCK
        val sum = FloatArray(bw * bh * 3); val cnt = IntArray(bw * bh)
        val row = IntArray(w)
        for (y in 0 until h) {
            result.getPixels(row, 0, w, 0, y, w, 1)
            val by = y / BLOCK
            for (x in 0 until w) {
                val o = px[y * w + x]; val r = row[x]
                val b = by * bw + x / BLOCK
                sum[b * 3] += ((o shr 16) and 0xFF) - ((r shr 16) and 0xFF)
                sum[b * 3 + 1] += ((o shr 8) and 0xFF) - ((r shr 8) and 0xFF)
                sum[b * 3 + 2] += (o and 0xFF) - (r and 0xFF)
                cnt[b]++
            }
        }
        for (b in cnt.indices) for (c in 0 until 3) sum[b * 3 + c] /= cnt[b].toFloat()
        // Two separable box passes of radius 2 blocks ≈ Gaussian, sigma about 2 blocks (16 px).
        repeat(2) { boxBlur3(sum, bw, bh, 2) }
        for (y in 0 until h) {
            result.getPixels(row, 0, w, 0, y, w, 1)
            val fy = ((y + 0.5f) / BLOCK - 0.5f).coerceIn(0f, bh - 1f)
            val y0 = fy.toInt(); val y1 = min(y0 + 1, bh - 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / BLOCK - 0.5f).coerceIn(0f, bw - 1f)
                val x0 = fx.toInt(); val x1 = min(x0 + 1, bw - 1); val tx = fx - x0
                val r = row[x]
                var outPx = 0xFF shl 24
                for (c in 0 until 3) {
                    val a = sum[(y0 * bw + x0) * 3 + c] * (1 - tx) + sum[(y0 * bw + x1) * 3 + c] * tx
                    val bb = sum[(y1 * bw + x0) * 3 + c] * (1 - tx) + sum[(y1 * bw + x1) * 3 + c] * tx
                    val shift = 16 - 8 * c
                    val v = (((r shr shift) and 0xFF) + a * (1 - ty) + bb * ty + 0.5f).toInt().coerceIn(0, 255)
                    outPx = outPx or (v shl shift)
                }
                row[x] = outPx
            }
            result.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** In-place separable box blur of a 3-channel float grid, clamped edges. */
    private fun boxBlur3(g: FloatArray, w: Int, h: Int, r: Int) {
        val tmp = FloatArray(g.size)
        val n = 2 * r + 1f
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            var s = 0f
            for (k in -r..r) s += g[(y * w + (x + k).coerceIn(0, w - 1)) * 3 + c]
            tmp[(y * w + x) * 3 + c] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            var s = 0f
            for (k in -r..r) s += tmp[((y + k).coerceIn(0, h - 1) * w + x) * 3 + c]
            g[(y * w + x) * 3 + c] = s / n
        }
    }

    private fun m(model: Model) = model.name

    /** Long-edge cap for the denoised base (keeps time and memory reasonable). */
    const val MAX_EDGE = 4096

    /** The denoised base kept beside an original: `<id>.denoise.jpg`, upright, no metadata. */
    fun fileFor(original: java.io.File) = java.io.File(original.parentFile, original.nameWithoutExtension + ".denoise.jpg")
}
