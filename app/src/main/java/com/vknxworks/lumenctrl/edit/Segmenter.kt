package com.vknxworks.lumenctrl.edit

import android.content.Context
import android.graphics.Bitmap
import com.vknxworks.lumenctrl.model.AiKind
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Offline AI masks. Two bundled TFLite models run on the phone's CPU:
 *  - deeplab_v3 (PASCAL VOC, 21 classes) for Subject / Background / Sky support
 *  - selfie_multiclass (background, hair, body skin, face skin, clothes, other) for people parts
 * Both expect RGB scaled to [-1, 1] (mean 127.5, std 127.5, read from the model metadata).
 * The coarse model output is upsampled and refined with a guided filter so edges
 * follow the photo. Sky uses Lumen's own detector (no model has a sky class).
 * Nothing here touches the network; the app has no internet permission.
 */
object Segmenter {
    class NothingFound(val kind: AiKind) : Exception("No ${kind.label.lowercase()} found in this photo")

    /** PASCAL VOC classes treated as a primary subject: animals, people, vehicles. */
    private val PRIMARY = setOf(1, 2, 3, 4, 6, 7, 8, 10, 12, 13, 14, 15, 17, 19)

    private const val DEEPLAB = "models/deeplab_v3.tflite"
    private const val SELFIE = "models/selfie_multiclass.tflite"
    /** Long edge of stored masks (source space). */
    const val MASK_SIZE = 1024

    private val interpreters = HashMap<String, Interpreter>()

    private fun interpreter(context: Context, asset: String): Interpreter = synchronized(interpreters) {
        interpreters.getOrPut(asset) {
            val fd = context.assets.openFd(asset)
            val buf: MappedByteBuffer = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            Interpreter(buf, Interpreter.Options().setNumThreads(4))
        }
    }

    /**
     * Per-class probabilities at model resolution: [h][w][classes] flattened.
     * The photo was letterboxed into the model input; (cx, cy, cw, ch) is where
     * it sits in this grid.
     */
    private class Probs(
        val w: Int, val h: Int, val classes: Int, val p: FloatArray,
        val cx: Float = 0f, val cy: Float = 0f, val cw: Float = w.toFloat(), val ch: Float = h.toFloat(),
    ) {
        fun at(x: Int, y: Int, c: Int) = p[(y * w + x) * classes + c]
        fun argmax(x: Int, y: Int): Int {
            val b = (y * w + x) * classes
            var best = 0
            for (k in 1 until classes) if (p[b + k] > p[b + best]) best = k
            return best
        }
        /** Mean winning-class probability: how sure the model is overall. */
        fun confidence(): Float {
            var s = 0.0
            for (i in 0 until w * h) { var m = 0f; for (k in 0 until classes) m = max(m, p[i * classes + k]); s += m }
            return (s / (w * h)).toFloat()
        }
    }

    /** Both bundled models declare mean 127.5 / std 127.5 in their metadata: input in [-1, 1]. */
    private fun infer(context: Context, asset: String, bmp: Bitmap): Probs {
        val pr = infer(context, asset, bmp, 0)
        // Debug aid (adb logcat -s LumenAI): which classes the model found. Stays on the device.
        val counts = IntArray(pr.classes)
        for (y in 0 until pr.h) for (x in 0 until pr.w) counts[pr.argmax(x, y)]++
        android.util.Log.d("LumenAI", "$asset out ${pr.w}x${pr.h}x${pr.classes} conf=${"%.3f".format(pr.confidence())} classes=" +
            counts.withIndex().filter { it.value > 0 }.joinToString { "${it.index}:${it.value}" })
        return pr
    }

    private fun infer(context: Context, asset: String, bmp: Bitmap, norm: Int): Probs {
        val itp = interpreter(context, asset)
        synchronized(itp) {
            val inShape = itp.getInputTensor(0).shape() // [1, H, W, 3]
            val ih = inShape[1]; val iw = inShape[2]
            // Letterbox: keep the photo's proportions, pad with mid-gray (0 after normalization).
            val s = min(iw / bmp.width.toFloat(), ih / bmp.height.toFloat())
            val cw = (bmp.width * s).roundToInt().coerceIn(1, iw)
            val chh = (bmp.height * s).roundToInt().coerceIn(1, ih)
            val ox = (iw - cw) / 2; val oy = (ih - chh) / 2
            val boxed = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(boxed).apply {
                drawColor(android.graphics.Color.rgb(128, 128, 128))
                drawBitmap(bmp, null, android.graphics.Rect(ox, oy, ox + cw, oy + chh), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            val px = IntArray(iw * ih)
            boxed.getPixels(px, 0, iw, 0, 0, iw, ih)
            boxed.recycle()
            val input = ByteBuffer.allocateDirect(iw * ih * 3 * 4).order(ByteOrder.nativeOrder())
            val (mean, std) = if (norm == 1) 0f to 255f else 127.5f to 127.5f
            for (c in px) {
                input.putFloat((((c shr 16) and 0xFF) - mean) / std)
                input.putFloat((((c shr 8) and 0xFF) - mean) / std)
                input.putFloat(((c and 0xFF) - mean) / std)
            }
            input.rewind()
            val outShape = itp.getOutputTensor(0).shape() // [1, H, W, C]
            val oh = outShape[1]; val ow = outShape[2]; val oc = outShape[3]
            val output = ByteBuffer.allocateDirect(oh * ow * oc * 4).order(ByteOrder.nativeOrder())
            itp.run(input, output)
            output.rewind()
            val raw = FloatArray(oh * ow * oc)
            output.asFloatBuffer().get(raw)
            // Some models emit probabilities, some logits: softmax unless rows already sum to ~1.
            var sum0 = 0f; var nonNeg = true
            for (k in 0 until oc) { sum0 += raw[k]; if (raw[k] < 0f) nonNeg = false }
            if (!(nonNeg && sum0 in 0.98f..1.02f)) {
                for (i in 0 until oh * ow) {
                    val b = i * oc
                    var m = raw[b]; for (k in 1 until oc) m = max(m, raw[b + k])
                    var s = 0f
                    for (k in 0 until oc) { val e = exp(raw[b + k] - m); raw[b + k] = e; s += e }
                    for (k in 0 until oc) raw[b + k] /= s
                }
            }
            val kx = ow / iw.toFloat(); val ky = oh / ih.toFloat()
            return Probs(ow, oh, oc, raw, ox * kx, oy * ky, cw * kx, chh * ky)
        }
    }

    /** Produces a refined mask (0..1 gray in R=G=B) at [MASK_SIZE], aligned with [bmp] (source space). */
    fun segment(context: Context, bmp: Bitmap, kind: AiKind): Bitmap {
        val s = MASK_SIZE / max(bmp.width, bmp.height).toFloat()
        val mw = (bmp.width * min(s, 1f)).roundToInt().coerceAtLeast(1)
        val mh = (bmp.height * min(s, 1f)).roundToInt().coerceAtLeast(1)
        val guideBmp = Bitmap.createScaledBitmap(bmp, mw, mh, true)
        val gpx = IntArray(mw * mh)
        guideBmp.getPixels(gpx, 0, mw, 0, 0, mw, mh)
        if (guideBmp !== bmp) guideBmp.recycle()
        val guide = FloatArray(mw * mh) { i ->
            val c = gpx[i]; (((c shr 16) and 0xFF) * 0.299f + ((c shr 8) and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f
        }

        // Selections use each pixel's winning class (crisp, like Lightroom's Select Subject);
        // the guided filter then turns the blocky model grid into soft, photo-aligned edges.
        fun hit(b: Boolean) = if (b) 1f else 0f
        val coarse: FloatArray = when (kind) {
            AiKind.SUBJECT, AiKind.BACKGROUND -> {
                val pr = infer(context, DEEPLAB, bmp)
                // Prefer living things and vehicles when present; furniture/screens only as a fallback.
                var primary = 0
                for (y in 0 until pr.h) for (x in 0 until pr.w) if (pr.argmax(x, y) in PRIMARY) primary++
                val usePrimary = primary > pr.w * pr.h / 200
                val m = sampleUp(pr, mw, mh) { x, y ->
                    val c = pr.argmax(x, y)
                    hit(if (usePrimary) c in PRIMARY else c != 0)
                }
                if (kind == AiKind.BACKGROUND) FloatArray(m.size) { 1f - m[it] } else m
            }
            AiKind.PEOPLE -> { val pr = infer(context, SELFIE, bmp); sampleUp(pr, mw, mh) { x, y -> hit(pr.argmax(x, y) != 0) } }
            AiKind.HAIR -> { val pr = infer(context, SELFIE, bmp); sampleUp(pr, mw, mh) { x, y -> hit(pr.argmax(x, y) == 1) } }
            AiKind.BODY -> { val pr = infer(context, SELFIE, bmp); sampleUp(pr, mw, mh) { x, y -> hit(pr.argmax(x, y) == 2) } }
            AiKind.FACE -> { val pr = infer(context, SELFIE, bmp); sampleUp(pr, mw, mh) { x, y -> hit(pr.argmax(x, y) == 3) } }
            AiKind.CLOTHES -> { val pr = infer(context, SELFIE, bmp); sampleUp(pr, mw, mh) { x, y -> hit(pr.argmax(x, y) == 4) } }
            AiKind.SKY -> {
                val objects = infer(context, DEEPLAB, bmp)
                val notObject = sampleUp(objects, mw, mh) { x, y -> hit(objects.argmax(x, y) == 0) }
                val sky = skyLikelihood(gpx, mw, mh)
                FloatArray(sky.size) { sky[it] * notObject[it] }
            }
        }
        val coverage = coarse.average()
        if (kind != AiKind.BACKGROUND && coverage < 0.002) throw NothingFound(kind)
        val refined = guidedFilter(guide, coarse, mw, mh, radius = max(4, mw / 128), eps = 1e-3f)
        val out = IntArray(mw * mh) { i ->
            val v = (refined[i].coerceIn(0f, 1f) * 255f).roundToInt()
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(out, mw, mh, Bitmap.Config.ARGB_8888)
    }

    /** Bilinear upsample of a per-pixel value function from model grid to mask size. */
    private inline fun sampleUp(pr: Probs, w: Int, h: Int, f: (Int, Int) -> Float): FloatArray {
        val grid = FloatArray(pr.w * pr.h) { i -> f(i % pr.w, i / pr.w) }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val gy = (pr.cy + (y + 0.5f) / h * pr.ch - 0.5f).coerceIn(0f, pr.h - 1f)
            val y0 = gy.toInt(); val y1 = min(y0 + 1, pr.h - 1); val ty = gy - y0
            for (x in 0 until w) {
                val gx = (pr.cx + (x + 0.5f) / w * pr.cw - 0.5f).coerceIn(0f, pr.w - 1f)
                val x0 = gx.toInt(); val x1 = min(x0 + 1, pr.w - 1); val tx = gx - x0
                val a = grid[y0 * pr.w + x0] * (1 - tx) + grid[y0 * pr.w + x1] * tx
                val b = grid[y1 * pr.w + x0] * (1 - tx) + grid[y1 * pr.w + x1] * tx
                out[y * w + x] = a * (1 - ty) + b * ty
            }
        }
        return out
    }

    /**
     * Lumen's sky detector: smooth, bright-or-blue pixels connected to the top
     * edge, grown by flood fill, then softened. No model involved.
     */
    private fun skyLikelihood(px: IntArray, w: Int, h: Int): FloatArray {
        val lum = FloatArray(w * h); val blue = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
            blue[i] = b - max(r, g * 0.9f)
        }
        val grad = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = lum[i + 1] - lum[i - 1]; val gy = lum[i + w] - lum[i - w]
            grad[i] = sqrt(gx * gx + gy * gy)
        }
        val smoothGrad = boxBlur(grad, w, h, max(2, w / 200))
        val cand = BooleanArray(w * h) { i ->
            val y = i / w
            val pos = 1f - y / h.toFloat()
            smoothGrad[i] < 0.035f && (lum[i] > 0.45f || blue[i] > 0.05f) && pos > 0.15f
        }
        // Flood from the top row through candidate pixels.
        val sky = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        for (x in 0 until w) if (cand[x]) { sky[x] = true; stack.addLast(x) }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast(); val x = i % w; val y = i / w
            for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                if (n >= 0 && !sky[n] && cand[n]) { sky[n] = true; stack.addLast(n) }
            }
        }
        // Reject warm-tinted regions: a lamp-lit wall or ceiling is smooth and bright too,
        // but sky (blue or overcast) is neutral-to-cool.
        var n = 0; var blueSum = 0.0; var warmSum = 0.0
        for (i in sky.indices) if (sky[i]) {
            n++
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            blueSum += blue[i]; warmSum += r - b
        }
        if (n == 0 || (warmSum / n > 0.06 && blueSum / n < 0.0)) return FloatArray(w * h)
        return boxBlur(FloatArray(w * h) { if (sky[it]) 1f else 0f }, w, h, max(2, w / 160))
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val s = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                out[y * w + x] = (s / ((x1 - x0) * (y1 - y0))).toFloat()
            }
        }
        return out
    }

    /** He et al. guided filter: transfers edges from [guide] into the coarse mask [p]. */
    private fun guidedFilter(guide: FloatArray, p: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val meanI = boxBlur(guide, w, h, radius)
        val meanP = boxBlur(p, w, h, radius)
        val corrI = boxBlur(FloatArray(guide.size) { guide[it] * guide[it] }, w, h, radius)
        val corrIP = boxBlur(FloatArray(guide.size) { guide[it] * p[it] }, w, h, radius)
        val a = FloatArray(guide.size); val b = FloatArray(guide.size)
        for (i in guide.indices) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val covIP = corrIP[i] - meanI[i] * meanP[i]
            a[i] = covIP / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxBlur(a, w, h, radius)
        val meanB = boxBlur(b, w, h, radius)
        return FloatArray(guide.size) { meanA[it] * guide[it] + meanB[it] }
    }
}

/**
 * Packs up to three AI masks into one RGB texture (R, G, B channels), in the
 * order AI masks appear in the edit. Alpha stays opaque so Android's
 * premultiplication can't alter the channels.
 */
object AiAtlas {
    const val MAX_AI = 3

    fun build(masks: List<com.vknxworks.lumenctrl.model.LocalMask>, bitmaps: Map<String, Bitmap>): Bitmap? {
        val ai = masks.filter { it.type == com.vknxworks.lumenctrl.model.MaskType.AI }.take(MAX_AI)
        val first = ai.firstNotNullOfOrNull { bitmaps[it.id] } ?: return null
        val w = first.width; val h = first.height
        val out = IntArray(w * h) { 0xFF shl 24 }
        val tmp = IntArray(w * h)
        ai.forEachIndexed { ch, m ->
            var bmp = bitmaps[m.id] ?: return@forEachIndexed
            if (bmp.width != w || bmp.height != h) bmp = Bitmap.createScaledBitmap(bmp, w, h, true)
            bmp.getPixels(tmp, 0, w, 0, 0, w, h)
            val shift = 16 - 8 * ch
            for (i in tmp.indices) out[i] = out[i] or (((tmp[i] shr 16) and 0xFF) shl shift)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
