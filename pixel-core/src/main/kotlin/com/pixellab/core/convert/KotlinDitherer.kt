package com.pixellab.core.convert

import com.pixellab.core.palette.LabColor

/** Opaque alpha bits (bit pattern `0xFF000000`), shared inside this file. */
private const val OPAQUE: Int = -0x1000000

/** Mask isolating the 24-bit RGB payload of a packed ARGB pixel. */
private const val RGB_MASK: Int = 0xFFFFFF

/**
 * Deterministic pure-JVM dithering — the reference implementation behind
 * [Ditherer] and the behavior the native bridge mirrors.
 *
 * Semantics:
 * - Intensity is clamped to `[0, 1]`; NaN is treated as 0.
 * - Pixels with `alpha < 0x80` are transparent and copied verbatim; opaque
 *   pixels map to the nearest palette color in Lab distance (ties resolve to
 *   the lowest palette index) while keeping their original alpha.
 * - Error diffusion runs left-to-right (no serpentine) over a three-row
 *   rolling buffer; error shares aimed out of bounds or at transparent
 *   pixels are dropped; the diffused error is scaled by the intensity first.
 * - Ordered modes perturb each channel before the nearest-color lookup: Bayer
 *   thresholds are `(matrix value + 0.5) / n^2` scaled by `63.75`, the
 *   checkerboard alternates `± intensity * 32`; perturbed channels are
 *   truncated toward zero and clamped to `0..255`.
 * - Malformed input (non-positive dimensions, mismatched array length or an
 *   empty palette) returns a copy of the input.
 */
internal object KotlinDitherer {

    /** Bayer perturbation span: `(threshold - 0.5) * 63.75` at full intensity. */
    private const val BAYER_SPAN = 63.75

    /** Checkerboard perturbation magnitude per channel at full intensity. */
    private const val CHECKER_SPAN = 32.0

    /** Floyd-Steinberg kernel: right, below-left, below, below-right. */
    private val FS_OFFSETS = intArrayOf(1, 0, -1, 1, 0, 1, 1, 1)
    private val FS_WEIGHTS = floatArrayOf(7f / 16f, 3f / 16f, 5f / 16f, 1f / 16f)

    /** Atkinson kernel: six neighbors, each carrying 1/8 of the error. */
    private val ATKINSON_OFFSETS = intArrayOf(1, 0, 2, 0, -1, 1, 0, 1, 1, 1, 0, 2)
    private val ATKINSON_WEIGHTS = floatArrayOf(1f / 8f, 1f / 8f, 1f / 8f, 1f / 8f, 1f / 8f, 1f / 8f)

    /** Ordered 2x2 Bayer matrix. */
    private val BAYER_2X2 = intArrayOf(0, 2, 3, 1)

    /** Ordered 4x4 Bayer matrix. */
    private val BAYER_4X4 = intArrayOf(
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5,
    )

    /** Ordered 8x8 Bayer matrix. */
    private val BAYER_8X8 = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /**
     * Dithers [pixels] (row-major, `width * height`) onto [palette] with
     * [algorithm] and [intensity]. Transparent pixels pass through; opaque
     * pixels map to their nearest palette color keeping the original alpha.
     */
    fun dither(
        pixels: IntArray, width: Int, height: Int,
        palette: IntArray, algorithm: DitherAlgorithm, intensity: Float,
    ): IntArray {
        if (width <= 0 || height <= 0 || width.toLong() * height != pixels.size.toLong() || palette.isEmpty()) {
            return pixels.copyOf()
        }
        val strength = sanitizeIntensity(intensity)
        val labPalette = Array(palette.size) { LabColor.fromArgb(palette[it]) }
        return when (algorithm) {
            DitherAlgorithm.NONE -> mapNearest(pixels, palette, labPalette)
            DitherAlgorithm.FLOYD_STEINBERG ->
                errorDiffusion(pixels, width, height, palette, labPalette, strength, FS_OFFSETS, FS_WEIGHTS)
            DitherAlgorithm.ATKINSON ->
                errorDiffusion(pixels, width, height, palette, labPalette, strength, ATKINSON_OFFSETS, ATKINSON_WEIGHTS)
            DitherAlgorithm.BAYER_2X2 ->
                perturbed(pixels, width, height, palette, labPalette, bayerPerturbation(BAYER_2X2, 2, strength))
            DitherAlgorithm.BAYER_4X4 ->
                perturbed(pixels, width, height, palette, labPalette, bayerPerturbation(BAYER_4X4, 4, strength))
            DitherAlgorithm.BAYER_8X8 ->
                perturbed(pixels, width, height, palette, labPalette, bayerPerturbation(BAYER_8X8, 8, strength))
            DitherAlgorithm.CHECKERBOARD ->
                perturbed(pixels, width, height, palette, labPalette, checkerPerturbation(strength))
        }
    }

    /** Clamps [intensity] to `[0, 1]`, mapping NaN to 0. */
    private fun sanitizeIntensity(intensity: Float): Float =
        if (intensity.isNaN()) 0f else intensity.coerceIn(0f, 1f)

    /** Straight Lab nearest-neighbor mapping (the `NONE` mode). */
    private fun mapNearest(pixels: IntArray, palette: IntArray, labPalette: Array<LabColor.Lab>): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i] = if ((p ushr 24) >= 0x80) snapToPalette(p, palette, labPalette) else p
        }
        return out
    }

    /**
     * Floyd-Steinberg / Atkinson error diffusion: left-to-right rows over a
     * three-row rolling buffer; error shares leaving the raster or landing on
     * transparent pixels are dropped.
     */
    private fun errorDiffusion(
        pixels: IntArray, width: Int, height: Int,
        palette: IntArray, labPalette: Array<LabColor.Lab>,
        strength: Float, offsets: IntArray, weights: FloatArray,
    ): IntArray {
        val out = IntArray(pixels.size)
        val rows = Array(3) { FloatArray(width * 3) }
        for (y in 0 until height) {
            val current = y % 3
            val next = (y + 1) % 3
            val after = (y + 2) % 3
            rows[after].fill(0f)
            for (x in 0 until width) {
                val i = y * width + x
                val p = pixels[i]
                if ((p ushr 24) < 0x80) {
                    out[i] = p
                    continue
                }
                // Working color = original plus accumulated error, clamped.
                var workR = ((p shr 16) and 0xFF) + rows[current][x * 3]
                var workG = ((p shr 8) and 0xFF) + rows[current][x * 3 + 1]
                var workB = (p and 0xFF) + rows[current][x * 3 + 2]
                workR = workR.coerceIn(0f, 255f)
                workG = workG.coerceIn(0f, 255f)
                workB = workB.coerceIn(0f, 255f)
                val chosen = nearestIndex(
                    LabColor.fromArgb(
                        OPAQUE or (toByte(workR) shl 16) or (toByte(workG) shl 8) or toByte(workB),
                    ),
                    labPalette,
                )
                val pr = (palette[chosen] shr 16) and 0xFF
                val pg = (palette[chosen] shr 8) and 0xFF
                val pb = palette[chosen] and 0xFF
                out[i] = (p and OPAQUE) or (pr shl 16) or (pg shl 8) or pb
                // Residual error scaled by intensity, diffused forward.
                val errorR = (workR - pr) * strength
                val errorG = (workG - pg) * strength
                val errorB = (workB - pb) * strength
                for (k in offsets.indices step 2) {
                    val tx = x + offsets[k]
                    val ty = y + offsets[k + 1]
                    if (tx < 0 || tx >= width || ty >= height) continue
                    if ((pixels[ty * width + tx] ushr 24) < 0x80) continue
                    val row = when (ty - y) {
                        0 -> rows[current]
                        1 -> rows[next]
                        else -> rows[after]
                    }
                    val w = weights[k / 2]
                    row[tx * 3] += errorR * w
                    row[tx * 3 + 1] += errorG * w
                    row[tx * 3 + 2] += errorB * w
                }
            }
        }
        return out
    }

    /**
     * Ordered modes: each opaque pixel is perturbed channel-wise by
     * [perturbationAt] (rounded toward zero and clamped) before the Lab
     * nearest-neighbor lookup.
     */
    private fun perturbed(
        pixels: IntArray, width: Int, height: Int,
        palette: IntArray, labPalette: Array<LabColor.Lab>,
        perturbationAt: (x: Int, y: Int) -> Double,
    ): IntArray {
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val p = pixels[i]
                if ((p ushr 24) < 0x80) {
                    out[i] = p
                    continue
                }
                val perturbation = perturbationAt(x, y)
                val r = perturbedChannel((p shr 16) and 0xFF, perturbation)
                val g = perturbedChannel((p shr 8) and 0xFF, perturbation)
                val b = perturbedChannel(p and 0xFF, perturbation)
                val chosen = nearestIndex(
                    LabColor.fromArgb(OPAQUE or (r shl 16) or (g shl 8) or b),
                    labPalette,
                )
                out[i] = (p and OPAQUE) or (palette[chosen] and RGB_MASK)
            }
        }
        return out
    }

    /**
     * Bayer perturbation: threshold `(value + 0.5) / n^2` mapped to
     * `intensity * (threshold - 0.5) * 63.75`.
     */
    private fun bayerPerturbation(matrix: IntArray, dimension: Int, strength: Float): (Int, Int) -> Double {
        val cells = dimension * dimension
        val scale = strength.toDouble()
        return { x, y ->
            val threshold = (matrix[(y % dimension) * dimension + (x % dimension)] + 0.5) / cells
            scale * (threshold - 0.5) * BAYER_SPAN
        }
    }

    /** Checkerboard perturbation: `+intensity * 32` on even `(x + y)`, `-` on odd. */
    private fun checkerPerturbation(strength: Float): (Int, Int) -> Double {
        val amount = strength.toDouble() * CHECKER_SPAN
        return { x, y -> if ((x + y) % 2 == 0) amount else -amount }
    }

    /** Nearest palette color of [p] in Lab distance, keeping [p]'s alpha. */
    private fun snapToPalette(p: Int, palette: IntArray, labPalette: Array<LabColor.Lab>): Int {
        val best = nearestIndex(LabColor.fromArgb(p), labPalette)
        return (palette[best] and RGB_MASK) or (p and OPAQUE)
    }

    /** Index of the palette entry closest to [lab]; ties keep the lowest index. */
    private fun nearestIndex(lab: LabColor.Lab, labPalette: Array<LabColor.Lab>): Int {
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (j in labPalette.indices) {
            val other = labPalette[j]
            val dl = lab.l - other.l
            val da = lab.a - other.a
            val db = lab.b - other.b
            val d = dl * dl + da * da + db * db
            if (d < bestDistance) {
                bestDistance = d
                best = j
            }
        }
        return best
    }

    /** Rounds a clamped working channel half up to `0..255`. */
    private fun toByte(value: Float): Int = (value + 0.5f).toInt().coerceIn(0, 255)

    /** Adds [perturbation] to [channel], truncating toward zero and clamping. */
    private fun perturbedChannel(channel: Int, perturbation: Double): Int =
        (channel + perturbation).toInt().coerceIn(0, 255)
}
