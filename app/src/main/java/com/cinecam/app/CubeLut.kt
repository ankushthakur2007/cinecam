package com.cinecam.app

import kotlin.math.floor

data class CubeLut(
    val size: Int,
    val data: List<FloatArray>
) {
    init {
        require(size > 1) { "LUT size must be > 1" }
        require(data.size == size * size * size) { "Invalid LUT data count" }
    }

    fun approximateColorMatrix(): FloatArray {
        val origin = sample(0f, 0f, 0f)
        val red = sample(1f, 0f, 0f)
        val green = sample(0f, 1f, 0f)
        val blue = sample(0f, 0f, 1f)

        val rr = red[0] - origin[0]
        val rg = green[0] - origin[0]
        val rb = blue[0] - origin[0]

        val gr = red[1] - origin[1]
        val gg = green[1] - origin[1]
        val gb = blue[1] - origin[1]

        val br = red[2] - origin[2]
        val bg = green[2] - origin[2]
        val bb = blue[2] - origin[2]

        return floatArrayOf(
            rr, rg, rb, 0f, origin[0] * 255f,
            gr, gg, gb, 0f, origin[1] * 255f,
            br, bg, bb, 0f, origin[2] * 255f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val x = r.coerceIn(0f, 1f) * (size - 1)
        val y = g.coerceIn(0f, 1f) * (size - 1)
        val z = b.coerceIn(0f, 1f) * (size - 1)

        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val x1 = (x0 + 1).coerceAtMost(size - 1)
        val y1 = (y0 + 1).coerceAtMost(size - 1)
        val z1 = (z0 + 1).coerceAtMost(size - 1)

        val tx = x - x0
        val ty = y - y0
        val tz = z - z0

        val c000 = get(x0, y0, z0)
        val c100 = get(x1, y0, z0)
        val c010 = get(x0, y1, z0)
        val c110 = get(x1, y1, z0)
        val c001 = get(x0, y0, z1)
        val c101 = get(x1, y0, z1)
        val c011 = get(x0, y1, z1)
        val c111 = get(x1, y1, z1)

        val out = FloatArray(3)
        for (i in 0..2) {
            val c00 = c000[i] * (1 - tx) + c100[i] * tx
            val c10 = c010[i] * (1 - tx) + c110[i] * tx
            val c01 = c001[i] * (1 - tx) + c101[i] * tx
            val c11 = c011[i] * (1 - tx) + c111[i] * tx

            val c0 = c00 * (1 - ty) + c10 * ty
            val c1 = c01 * (1 - ty) + c11 * ty
            out[i] = c0 * (1 - tz) + c1 * tz
        }
        return out
    }

    private fun get(r: Int, g: Int, b: Int): FloatArray {
        val index = (b * size * size) + (g * size) + r
        return data[index]
    }

    companion object {
        fun parse(text: String): CubeLut {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()

            val sizeLine = lines.firstOrNull { it.startsWith("LUT_3D_SIZE") }
                ?: error("Missing LUT_3D_SIZE")
            val size = sizeLine.substringAfter("LUT_3D_SIZE").trim().toInt()

            val values = lines
                .filterNot {
                    it.startsWith("TITLE") ||
                        it.startsWith("LUT_3D_SIZE") ||
                        it.startsWith("DOMAIN_MIN") ||
                        it.startsWith("DOMAIN_MAX")
                }
                .map { line ->
                    val parts = line.split(Regex("\\s+"))
                    require(parts.size >= 3) { "Invalid LUT entry: $line" }
                    floatArrayOf(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat())
                }

            return CubeLut(size, values)
        }
    }
}
