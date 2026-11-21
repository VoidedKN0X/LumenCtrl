package com.vknxworks.lumenctrl.edit

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Object removal without a model: fills a hole with texture copied from the rest of the picture, the way
 * content-aware fill works. A coarse-to-fine pyramid; at every level PatchMatch (Barnes et al. 2009) finds, for each
 * 7×7 patch that touches the hole, the most similar patch fully outside it, and every hole pixel then takes the
 * average of what the overlapping patches propose (Wexler et al. 2007). Known pixels are never changed.
 *
 * Pure Kotlin on plain arrays (no Android types), so it can be tested on a computer. Run off the main thread; the
 * caller keeps the picture small (LumenCtrl works at up to about 640 px).
 */
object Inpaint {
    private const val R = 3                 // patch radius: 7×7 patches
    private const val MIN_LEVEL_SIZE = 24   // stop shrinking below this

    class Cancelled : Exception()

    /**
     * [argb] is w×h ARGB, [hole] marks the pixels to fill (true = fill). Returns a new ARGB array with the hole filled
     * and everything else unchanged. [isCancelled] is checked between steps.
     */
    fun fill(argb: IntArray, w: Int, h: Int, hole: BooleanArray, seed: Int = 7, isCancelled: () -> Boolean = { false }): IntArray {
        require(argb.size == w * h && hole.size == w * h)
        if (hole.none { it }) return argb.copyOf()
        val rnd = Random(seed)

        // Level 0 = full size; each next level is half as big.
        val imgs = ArrayList<FloatArray>()
        val masks = ArrayList<BooleanArray>()
        val ws = ArrayList<Int>(); val hs = ArrayList<Int>()
        var img = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            val c = argb[i]
            img[i * 3] = ((c shr 16) and 0xFF).toFloat(); img[i * 3 + 1] = ((c shr 8) and 0xFF).toFloat(); img[i * 3 + 2] = (c and 0xFF).toFloat()
        }
        imgs += img; masks += hole; ws += w; hs += h
        while (min(ws.last(), hs.last()) / 2 >= MIN_LEVEL_SIZE && holeExtent(masks.last(), ws.last(), hs.last()) > 2 * R + 1) {
            val (ni, nm) = down(imgs.last(), masks.last(), ws.last(), hs.last())
            imgs += ni; masks += nm; ws += ws.last() / 2; hs += hs.last() / 2
        }

        // Coarsest level: start from a smooth guess grown in from the hole's edge.
        var level = imgs.lastIndex
        var cur = imgs[level].copyOf()
        diffuse(cur, masks[level], ws[level], hs[level])
        var nnf: IntArray? = null
        while (true) {
            val lw = ws[level]; val lh = hs[level]; val m = masks[level]
            val emSteps = if (level == 0) 2 else 4
            for (step in 0 until emSteps) {
                if (isCancelled()) throw Cancelled()
                nnf = patchMatch(cur, m, lw, lh, nnf, rnd, iterations = if (level == 0) 3 else 5)
                vote(cur, m, lw, lh, nnf)
            }
            if (level == 0) break
            // Up one level: the known pixels come from the real picture, the hole from this level's result.
            val up = level - 1
            val uw = ws[up]; val uh = hs[up]
            val next = imgs[up].copyOf()
            val um = masks[up]
            for (y in 0 until uh) for (x in 0 until uw) {
                val i = y * uw + x
                if (!um[i]) continue
                val sx = min(x / 2, lw - 1); val sy = min(y / 2, lh - 1)
                val j = sy * lw + sx
                next[i * 3] = cur[j * 3]; next[i * 3 + 1] = cur[j * 3 + 1]; next[i * 3 + 2] = cur[j * 3 + 2]
            }
            nnf = upNnf(nnf!!, lw, lh, uw, uh)
            cur = next
            level = up
        }

        val out = argb.copyOf()
        for (i in 0 until w * h) if (hole[i]) {
            val r = cur[i * 3].toInt().coerceIn(0, 255); val g = cur[i * 3 + 1].toInt().coerceIn(0, 255); val b = cur[i * 3 + 2].toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** Largest side of the hole's bounding box. */
    private fun holeExtent(m: BooleanArray, w: Int, h: Int): Int {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (m[y * w + x]) { x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
        return if (x1 < 0) 0 else max(x1 - x0, y1 - y0) + 1
    }

    /** Half size: average of known pixels; a pixel is a hole if any of its four was. */
    private fun down(img: FloatArray, m: BooleanArray, w: Int, h: Int): Pair<FloatArray, BooleanArray> {
        val nw = w / 2; val nh = h / 2
        val o = FloatArray(nw * nh * 3); val om = BooleanArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            var r = 0f; var g = 0f; var b = 0f; var n = 0; var anyHole = false
            for (dy in 0..1) for (dx in 0..1) {
                val i = (2 * y + dy) * w + 2 * x + dx
                if (m[i]) { anyHole = true; continue }
                r += img[i * 3]; g += img[i * 3 + 1]; b += img[i * 3 + 2]; n++
            }
            val j = y * nw + x
            om[j] = anyHole
            if (n > 0) { o[j * 3] = r / n; o[j * 3 + 1] = g / n; o[j * 3 + 2] = b / n }
        }
        return o to om
    }

    /** Fills the hole layer by layer from its edge with the mean of already-known neighbours. */
    private fun diffuse(img: FloatArray, m: BooleanArray, w: Int, h: Int) {
        val known = BooleanArray(w * h) { !m[it] }
        var remaining = m.count { it }
        if (remaining == w * h) { img.fill(128f); return }
        while (remaining > 0) {
            val fillNow = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (known[i]) continue
                var r = 0f; var g = 0f; var b = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (!known[j]) continue
                    r += img[j * 3]; g += img[j * 3 + 1]; b += img[j * 3 + 2]; n++
                }
                if (n > 0) { img[i * 3] = r / n; img[i * 3 + 1] = g / n; img[i * 3 + 2] = b / n; fillNow += i }
            }
            if (fillNow.isEmpty()) break
            fillNow.forEach { known[it] = true }
            remaining -= fillNow.size
        }
    }

    /** Pixels whose patch fits in the picture and contains no hole pixel: the places patches may be copied from. */
    private fun validSources(m: BooleanArray, w: Int, h: Int): BooleanArray {
        // Summed-area table of the hole.
        val sat = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                if (m[y * w + x]) row++
                sat[(y + 1) * (w + 1) + x + 1] = sat[y * (w + 1) + x + 1] + row
            }
        }
        val v = BooleanArray(w * h)
        for (y in R until h - R) for (x in R until w - R) {
            val x0 = x - R; val y0 = y - R; val x1 = x + R + 1; val y1 = y + R + 1
            val holes = sat[y1 * (w + 1) + x1] - sat[y0 * (w + 1) + x1] - sat[y1 * (w + 1) + x0] + sat[y0 * (w + 1) + x0]
            v[y * w + x] = holes == 0
        }
        return v
    }

    /** Targets: patch centres whose patch touches the hole. */
    private fun targets(m: BooleanArray, w: Int, h: Int): IntArray {
        val t = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (m[y * w + x]) {
            for (yy in max(0, y - R)..min(h - 1, y + R)) for (xx in max(0, x - R)..min(w - 1, x + R)) t[yy * w + xx] = true
        }
        return t.indices.filter { t[it] }.toIntArray()
    }

    /** Patch distance (sum of squared differences), stopping early once it passes [best]. Off-picture pixels are skipped. */
    private fun dist(img: FloatArray, w: Int, h: Int, tx: Int, ty: Int, sx: Int, sy: Int, best: Float): Float {
        var d = 0f
        for (dy in -R..R) {
            val y = ty + dy
            if (y < 0 || y >= h) continue
            val srow = (sy + dy) * w
            val trow = y * w
            for (dx in -R..R) {
                val x = tx + dx
                if (x < 0 || x >= w) continue
                val a = (trow + x) * 3
                val b = (srow + sx + dx) * 3
                val dr = img[a] - img[b]; val dg = img[a + 1] - img[b + 1]; val db = img[a + 2] - img[b + 2]
                d += dr * dr + dg * dg + db * db
            }
            if (d >= best) return d
        }
        return d
    }

    /**
     * Nearest-neighbour field for every target: index of the source centre. Starts from [prev] where it is still
     * valid, else from random sources; then propagation and random search.
     */
    private fun patchMatch(img: FloatArray, m: BooleanArray, w: Int, h: Int, prev: IntArray?, rnd: Random, iterations: Int): IntArray {
        val valid = validSources(m, w, h)
        val sources = valid.indices.filter { valid[it] }.toIntArray()
        val nnf = IntArray(w * h) { -1 }
        if (sources.isEmpty()) return nnf
        val tg = targets(m, w, h)
        val cost = FloatArray(w * h) { Float.MAX_VALUE }
        for (t in tg) {
            val p = prev?.getOrNull(t)?.takeIf { it >= 0 && valid[it] } ?: sources[rnd.nextInt(sources.size)]
            nnf[t] = p
            cost[t] = dist(img, w, h, t % w, t / w, p % w, p / w, Float.MAX_VALUE)
        }
        val isTarget = BooleanArray(w * h).also { b -> tg.forEach { b[it] = true } }
        val maxRadius = max(w, h)
        for (it in 0 until iterations) {
            val forward = it % 2 == 0
            val order = if (forward) tg.indices else tg.indices.reversed()
            val step = if (forward) 1 else -1
            for (k in order) {
                val t = tg[k]
                val tx = t % w; val ty = t / w
                var best = nnf[t]; var bestCost = cost[t]
                // Propagation: shift the neighbour's match by one pixel.
                for ((nx, ny) in arrayOf(tx - step to ty, tx to ty - step)) {
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = ny * w + nx
                    if (!isTarget[n] || nnf[n] < 0) continue
                    val cx = nnf[n] % w + (tx - nx); val cy = nnf[n] / w + (ty - ny)
                    if (cx < 0 || cy < 0 || cx >= w || cy >= h) continue
                    val c = cy * w + cx
                    if (!valid[c] || c == best) continue
                    val d = dist(img, w, h, tx, ty, cx, cy, bestCost)
                    if (d < bestCost) { bestCost = d; best = c }
                }
                // Random search in shrinking windows around the current best.
                var radius = maxRadius
                while (radius >= 1) {
                    val cx = (best % w + rnd.nextInt(-radius, radius + 1)).coerceIn(0, w - 1)
                    val cy = (best / w + rnd.nextInt(-radius, radius + 1)).coerceIn(0, h - 1)
                    val c = cy * w + cx
                    if (valid[c] && c != best) {
                        val d = dist(img, w, h, tx, ty, cx, cy, bestCost)
                        if (d < bestCost) { bestCost = d; best = c }
                    }
                    radius /= 2
                }
                nnf[t] = best; cost[t] = bestCost
            }
        }
        return nnf
    }

    /** Each hole pixel becomes the weighted mean of what every overlapping patch's match says it should be. */
    private fun vote(img: FloatArray, m: BooleanArray, w: Int, h: Int, nnf: IntArray) {
        val acc = FloatArray(w * h * 3)
        val wsum = FloatArray(w * h)
        for (t in nnf.indices) {
            val s = nnf[t]
            if (s < 0) continue
            val tx = t % w; val ty = t / w; val sx = s % w; val sy = s / w
            for (dy in -R..R) {
                val y = ty + dy
                if (y < 0 || y >= h) continue
                for (dx in -R..R) {
                    val x = tx + dx
                    if (x < 0 || x >= w) continue
                    val i = y * w + x
                    if (!m[i]) continue
                    val j = ((sy + dy) * w + sx + dx) * 3
                    // Patch centres count more than their edges, which keeps edges crisp.
                    val wt = 1f / (1f + dx * dx + dy * dy)
                    acc[i * 3] += img[j] * wt; acc[i * 3 + 1] += img[j + 1] * wt; acc[i * 3 + 2] += img[j + 2] * wt
                    wsum[i] += wt
                }
            }
        }
        for (i in 0 until w * h) if (m[i] && wsum[i] > 0f) {
            img[i * 3] = acc[i * 3] / wsum[i]; img[i * 3 + 1] = acc[i * 3 + 1] / wsum[i]; img[i * 3 + 2] = acc[i * 3 + 2] / wsum[i]
        }
    }

    /** Doubles a field's resolution: each target takes its parent's match, scaled. */
    private fun upNnf(nnf: IntArray, w: Int, h: Int, uw: Int, uh: Int): IntArray {
        val out = IntArray(uw * uh) { -1 }
        for (y in 0 until uh) for (x in 0 until uw) {
            val px = min(x / 2, w - 1); val py = min(y / 2, h - 1)
            val s = nnf[py * w + px]
            if (s < 0) continue
            val sx = (s % w) * 2 + (x % 2); val sy = (s / w) * 2 + (y % 2)
            if (sx < uw && sy < uh) out[y * uw + x] = sy * uw + sx
        }
        return out
    }
}
