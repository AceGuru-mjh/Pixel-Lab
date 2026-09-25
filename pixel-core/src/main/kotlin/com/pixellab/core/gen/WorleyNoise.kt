package com.pixellab.core.gen

import com.pixellab.core.model.PixelFrame
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Worley (cellular) noise: the classic distance-to-nearest-feature-point
 * field, in the seed-deterministic, grid-hash formulation.
 *
 * Feature points live one-per-cell in a virtual jittered grid (no global
 * state, no allocation per sample beyond small reused arrays). The
 * canonical outputs are:
 *
 *  * **F1** — distance to the nearest point: organic blobs with dark
 *    cell centers (the classic Voronoi look),
 *  * **F2** — distance to the *second*-nearest point: ridged, vein-like
 *    structures,
 *  * **F2 − F1** — near zero inside cells, spiking at borders: crisp
 *    cell *edges*, ideal for pixel-art cracks, cobblestones and shatter
 *    patterns.
 *
 * The same seed always produces the same field; neighboring seeds
 * produce uncorrelated fields (SplitMix64 hashing).
 */
class WorleyNoise(seed: Long) {

    private val seed: Long = if (seed == 0L) -0x61C8864680B583EBL else seed

    /**
     * The cellular quantity to evaluate per sample.
     */
    enum class Feature {
        /** Distance to the nearest feature point (F1). */
        NEAREST,

        /** Distance to the second-nearest feature point (F2). */
        SECOND,

        /** F2 − F1: ~0 inside cells, peaks at borders. */
        BORDER,

        /** 1 − F1 normalized to the cell scale: bright centers, dark edges. */
        CELL,
    }

    /**
     * Evaluates the [feature] field at continuous coordinates `(x, y)`.
     *
     * Distances use the [WorleyNoise] grid scale of one cell per unit;
     * pass world coordinates divided by your desired cell size. The
     * returned value is roughly bounded by `sqrt(2)` for [Feature.NEAREST]
     * and friends; [Feature.CELL] is normalized to `[0, 1]`.
     */
    fun evaluate(x: Double, y: Double, feature: Feature = Feature.NEAREST): Double {
        val cellX = floor(x)
        val cellY = floor(y)
        var f1 = Double.MAX_VALUE
        var f2 = Double.MAX_VALUE
        // Search the 3x3 neighborhood of cells (jitter ≤ 1 keeps every
        // relevant point within one cell of the sample).
        for (dy in -1..1) {
            for (dx in -1..1) {
                val px = cellX + dx + jitterX(cellX + dx, cellY + dy)
                val py = cellY + dy + jitterY(cellX + dx, cellY + dy)
                val ddx = px - x
                val ddy = py - y
                val d = sqrt(ddx * ddx + ddy * ddy)
                if (d < f1) {
                    f2 = f1
                    f1 = d
                } else if (d < f2) {
                    f2 = d
                }
            }
        }
        return when (feature) {
            Feature.NEAREST -> f1
            Feature.SECOND -> f2
            Feature.BORDER -> f2 - f1
            Feature.CELL -> (1.0 - f1).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Renders the [feature] field over a `width x height` grid at
     * [cellSize] world units per cell, mapping the value to 8-bit luma
     * (opaque grayscale). [contrast] > 1 steepens the falloff
     * (value^contrast); values are normalized to `[0, 1]` before the
     * power curve.
     */
    fun render(
        width: Int,
        height: Int,
        cellSize: Double = 8.0,
        feature: Feature = Feature.NEAREST,
        contrast: Double = 1.0,
        seedX: Double = 0.0,
        seedY: Double = 0.0,
    ): PixelFrame {
        require(width > 0 && height > 0) { "Positive dimensions required" }
        require(cellSize > 0.0) { "cellSize must be positive" }
        require(contrast > 0.0) { "contrast must be positive" }
        val out = IntArray(width * height)
        // First pass: raw values + running max for normalization.
        val raw = DoubleArray(width * height)
        var maxV = 0.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = evaluate((x + 0.5) / cellSize + seedX, (y + 0.5) / cellSize + seedY, feature)
                raw[y * width + x] = v
                if (v > maxV) maxV = v
            }
        }
        val scale = if (maxV <= 0.0) 1.0 else 1.0 / maxV
        for (i in raw.indices) {
            val t = (raw[i] * scale).coerceIn(0.0, 1.0)
            val shaped = if (contrast == 1.0) t else Math.pow(t, contrast)
            val luma = (shaped * 255.0 + 0.5).toInt().coerceIn(0, 255)
            out[i] = 0xFF shl 24 or (luma shl 16) or (luma shl 8) or luma
        }
        return PixelFrame.of(width, height, out)
    }

    /**
     * Integer hash of a grid cell to a jitter offset in `[0, 1)` for x.
     * Public for tests and for callers composing their own cellular rules.
     */
    fun jitterX(cx: Double, cy: Double): Double {
        var h = (cx.toRawBits().toLong() * 0x1D8E4A1B0F9E5L) xor (cy.toRawBits().toLong() * 0x8ACE2C55115D9L) xor this.seed
        h = h xor (h ushr 29)
        h *= -0x40A7B892E3B11A47L // 0xBF58476D1CE4E5B9 as signed
        h = h xor (h ushr 32)
        return ((h ushr 11).toDouble() / (1L shl 53).toDouble()).coerceIn(0.0, 0.9999999999)
    }

    /** Y counterpart of [jitterX] with different mixing constants. */
    fun jitterY(cx: Double, cy: Double): Double {
        var h = (cx.toRawBits().toLong() * 0x2F1E36D6A0C4L) xor (cy.toRawBits().toLong() * 0x71B5EC1A7F3D8L) xor (this.seed xor -0x5A5A5A5A5A5A5A5BL)
        h = h xor (h ushr 31)
        h *= 0x9E3779B97F4A7C1L and Long.MAX_VALUE // stays positive
        h = h xor (h ushr 27)
        return ((h ushr 11).toDouble() / (1L shl 53).toDouble()).coerceIn(0.0, 0.9999999999)
    }
}
