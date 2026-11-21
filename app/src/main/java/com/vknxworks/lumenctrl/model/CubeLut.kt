package com.vknxworks.lumenctrl.model

/**
 * A 3D colour lookup table read from a `.cube` file (the Adobe / Resolve text format most colour-grading tools
 * export). Only 3D tables are supported. [data] holds size³ RGB triples with red changing fastest, then green, then
 * blue, exactly as in the file. Inputs are mapped from [domainMin]..[domainMax] to the table.
 */
class CubeLut(
    val title: String?,
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val data: FloatArray,
) {
    class Invalid(message: String) : Exception(message)

    companion object {
        const val MIN_SIZE = 2
        /** 96³ is far beyond what any common LUT uses (17, 32, 33, 64, 65) and keeps memory bounded. */
        const val MAX_SIZE = 96

        /** Reads a `.cube` file. Throws [Invalid] with a short reason when it isn't a usable 3D LUT. */
        fun parse(text: String): CubeLut {
            var title: String? = null
            var size = 0
            val min = floatArrayOf(0f, 0f, 0f)
            val max = floatArrayOf(1f, 1f, 1f)
            var data: FloatArray? = null
            var n = 0
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val first = line[0]
                if (first.isDigit() || first == '-' || first == '+' || first == '.') {
                    val d = data ?: throw Invalid("data before LUT_3D_SIZE")
                    val parts = line.split(WS)
                    if (parts.size < 3) throw Invalid("bad data line")
                    if (n + 3 > d.size) throw Invalid("too many data lines")
                    for (k in 0 until 3) {
                        val v = parts[k].toFloatOrNull() ?: throw Invalid("bad number")
                        if (!v.isFinite()) throw Invalid("bad number")
                        d[n++] = v
                    }
                    continue
                }
                val key = line.substringBefore(' ').substringBefore('\t').uppercase()
                val rest = line.substring(key.length).trim()
                when (key) {
                    "TITLE" -> title = rest.trim('"').take(60).ifBlank { null }
                    "LUT_1D_SIZE" -> throw Invalid("1D LUTs aren't supported")
                    "LUT_3D_SIZE" -> {
                        if (data != null) throw Invalid("LUT_3D_SIZE given twice")
                        size = rest.toIntOrNull() ?: throw Invalid("bad LUT_3D_SIZE")
                        if (size !in MIN_SIZE..MAX_SIZE) throw Invalid("size $size is out of range")
                        data = FloatArray(size * size * size * 3)
                    }
                    "DOMAIN_MIN", "DOMAIN_MAX" -> {
                        val v = rest.split(WS).mapNotNull { it.toFloatOrNull() }
                        if (v.size != 3 || v.any { !it.isFinite() }) throw Invalid("bad $key")
                        v.forEachIndexed { i, x -> (if (key == "DOMAIN_MIN") min else max)[i] = x }
                    }
                    // Resolve's variant of the domain for 3D tables.
                    "LUT_3D_INPUT_RANGE" -> {
                        val v = rest.split(WS).mapNotNull { it.toFloatOrNull() }
                        if (v.size != 2 || v.any { !it.isFinite() }) throw Invalid("bad $key")
                        min.fill(v[0]); max.fill(v[1])
                    }
                    // Other keywords (LUT_1D_INPUT_RANGE in combined files, vendor extras) don't change a 3D table.
                    else -> Unit
                }
            }
            val d = data ?: throw Invalid("no LUT_3D_SIZE")
            if (n != d.size) throw Invalid("expected ${d.size / 3} entries, found ${n / 3}")
            for (i in 0 until 3) if (max[i] <= min[i]) throw Invalid("empty domain")
            return CubeLut(title, size, min, max, d)
        }

        private val WS = Regex("\\s+")
    }
}
