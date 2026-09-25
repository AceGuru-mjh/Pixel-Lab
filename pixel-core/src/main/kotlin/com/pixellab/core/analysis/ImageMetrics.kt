package com.pixellab.core.analysis

import com.pixellab.core.model.PixelFrame

/**
 * Pixel-level difference statistics between two same-geometry rasters.
 *
 * Only pixels that are opaque in *both* rasters contribute to the error
 * metrics (MAE per channel, overall MAE, maximum channel delta); pixels
 * that differ in opacity are counted by [opacityMismatches] so
 * edge-sensitive comparisons stay meaningful.
 */
class DiffStats(
    /** Pixels compared (opaque in both frames). */
    val comparedPixels: Long,
    /** Pixels whose ARGB values differ at all (including alpha-only diffs). */
    val changedPixels: Long,
    /** Total pixels in either frame (`width * height`). */
    val totalPixels: Long,
    /** Mean absolute error of the red channel over compared pixels. */
    val maeR: Double,
    /** Mean absolute error of the green channel over compared pixels. */
    val maeG: Double,
    /** Mean absolute error of the blue channel over compared pixels. */
    val maeB: Double,
    /** Mean absolute error across R/G/B averaged per pixel. */
    val mae: Double,
    /** Largest per-channel absolute difference seen anywhere. */
    val maxChannelDelta: Int,
    /** Pixels where one frame is transparent and the other is not. */
    val opacityMismatches: Long,
) {
    /** Ratio of pixels with any difference (0..1). */
    val changedRatio: Double get() = if (totalPixels == 0L) 0.0 else changedPixels.toDouble() / totalPixels

    override fun toString(): String =
        "DiffStats(compared=$comparedPixels, changed=$changedPixels (${"%.2f".format(changedRatio * 100)}%), mae=${"%.3f".format(mae)})"
}

/**
 * Whole-image comparison metrics and visualization for [PixelFrame]s:
 * per-channel MAE, PSNR, a difference mask frame and the bounding box of
 * changed pixels.
 *
 * All operations are pure and allocation-light (one result frame for the
 * mask, one pass over each raster).
 */
object ImageMetrics {

    /** Marker color for differing pixels in [diffMask]: pure red. */
    private const val DIFF_COLOR = 0xFFFF0000.toInt()

    /** Color for matching opaque pixels in [diffMask]: pure green. */
    private const val SAME_COLOR = 0xFF00FF00.toInt()

    /**
     * Compares [a] and [b]. Frames must have identical geometry.
     *
     * The MAE family ignores pixels that are transparent in either frame;
     * those are tallied in [DiffStats.opacityMismatches] when their
     * opacity status differs.
     */
    fun compare(a: PixelFrame, b: PixelFrame): DiffStats {
        require(a.width == b.width && a.height == b.height) {
            "Geometry mismatch: ${a.width}x${a.height} vs ${b.width}x${b.height}"
        }
        val pa = a.pixels
        val pb = b.pixels
        var compared = 0L
        var changed = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var maxDelta = 0
        var opacityMismatch = 0L
        for (i in pa.indices) {
            val ca = pa[i]
            val cb = pb[i]
            val aOn = ca ushr 24 != 0
            val bOn = cb ushr 24 != 0
            if (ca == cb) {
                // Identical pixels still count toward the compared base so
                // MAE over identical frames is 0 rather than NaN.
                if (aOn) compared++
                continue
            }
            changed++
            if (aOn != bOn) {
                opacityMismatch++
                continue
            }
            if (!aOn) continue // both transparent but different padding bits
            compared++
            val dr = kotlin.math.abs((ca ushr 16 and 0xFF) - (cb ushr 16 and 0xFF))
            val dg = kotlin.math.abs((ca ushr 8 and 0xFF) - (cb ushr 8 and 0xFF))
            val db = kotlin.math.abs((ca and 0xFF) - (cb and 0xFF))
            sumR += dr
            sumG += dg
            sumB += db
            if (dr > maxDelta) maxDelta = dr
            if (dg > maxDelta) maxDelta = dg
            if (db > maxDelta) maxDelta = db
        }
        val total = pa.size.toLong()
        val cmp = if (compared == 0L) 0.0 else compared.toDouble()
        return DiffStats(
            comparedPixels = compared,
            changedPixels = changed,
            totalPixels = total,
            maeR = sumR / cmp,
            maeG = sumG / cmp,
            maeB = sumB / cmp,
            mae = (sumR + sumG + sumB) / (cmp * 3.0),
            maxChannelDelta = maxDelta,
            opacityMismatches = opacityMismatch,
        )
    }

    /**
     * Peak signal-to-noise ratio in decibels over the compared (doubly
     * opaque) pixels.
     *
     * PSNR uses the MSE of all three channels jointly with the classic
     * `MAX = 255` reference. Identical frames return [Double.POSITIVE_INFINITY];
     * frames with nothing to compare return NaN (treat as "undefined").
     */
    fun psnr(a: PixelFrame, b: PixelFrame): Double {
        val stats = compare(a, b)
        if (stats.comparedPixels == 0L) return Double.NaN
        val pa = a.pixels
        val pb = b.pixels
        var sq = 0.0
        var count = 0L
        for (i in pa.indices) {
            if (pa[i] ushr 24 == 0 || pb[i] ushr 24 == 0) continue
            if (pa[i] == pb[i]) { count++; continue }
            val dr = (pa[i] ushr 16 and 0xFF) - (pb[i] ushr 16 and 0xFF)
            val dg = (pa[i] ushr 8 and 0xFF) - (pb[i] ushr 8 and 0xFF)
            val db = (pa[i] and 0xFF) - (pb[i] and 0xFF)
            sq += (dr * dr + dg * dg + db * db).toDouble()
            count++
        }
        if (sq == 0.0) return Double.POSITIVE_INFINITY
        val mse = sq / (count * 3.0)
        return 10.0 * kotlin.math.log10(255.0 * 255.0 / mse)
    }

    /**
     * Builds a visualization mask: differing pixels render [DIFF_COLOR],
     * matching opaque pixels [SAME_COLOR], and pixels transparent in
     * either frame stay transparent. [tolerance] (0..255) absorbs small
     * per-channel noise before a pixel counts as differing.
     */
    fun diffMask(a: PixelFrame, b: PixelFrame, tolerance: Int = 0): PixelFrame {
        require(tolerance in 0..255) { "tolerance out of range: $tolerance" }
        require(a.width == b.width && a.height == b.height) { "Geometry mismatch" }
        val pa = a.pixels
        val pb = b.pixels
        val out = IntArray(pa.size)
        for (i in pa.indices) {
            val ca = pa[i]
            val cb = pb[i]
            val aOn = ca ushr 24 != 0
            val bOn = cb ushr 24 != 0
            when {
                !aOn && !bOn -> out[i] = 0
                aOn && bOn -> {
                    val dr = kotlin.math.abs((ca ushr 16 and 0xFF) - (cb ushr 16 and 0xFF))
                    val dg = kotlin.math.abs((ca ushr 8 and 0xFF) - (cb ushr 8 and 0xFF))
                    val db = kotlin.math.abs((ca and 0xFF) - (cb and 0xFF))
                    out[i] = if (maxOf(dr, dg, db) > tolerance) DIFF_COLOR else SAME_COLOR
                }
                else -> out[i] = DIFF_COLOR // opacity mismatch counts as a diff
            }
        }
        return PixelFrame.of(a.width, a.height, out)
    }

    /**
     * Axis-aligned bounding box of all pixels that differ by more than
     * [tolerance], or null when the frames agree within tolerance.
     * Coordinates are inclusive on the low side, exclusive on the high
     * side (`(x0, y0, x1, y1)` usable as a crop rect).
     */
    fun changedBounds(a: PixelFrame, b: PixelFrame, tolerance: Int = 0): IntArray? {
        require(tolerance in 0..255) { "tolerance out of range: $tolerance" }
        require(a.width == b.width && a.height == b.height) { "Geometry mismatch" }
        val pa = a.pixels
        val pb = b.pixels
        val w = a.width
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (i in pa.indices) {
            if (pa[i] == pb[i]) continue
            if (!differs(pa[i], pb[i], tolerance)) continue
            val x = i % w
            val y = i / w
            if (x < minX) minX = x
            if (x + 1 > maxX) maxX = x + 1
            if (y < minY) minY = y
            if (y + 1 > maxY) maxY = y + 1
        }
        if (minX == Int.MAX_VALUE) return null
        return intArrayOf(minX, minY, maxX, maxY)
    }

    private fun differs(ca: Int, cb: Int, tolerance: Int): Boolean {
        val aOn = ca ushr 24 != 0
        val bOn = cb ushr 24 != 0
        if (aOn != bOn) return true
        if (!aOn) return false
        val dr = kotlin.math.abs((ca ushr 16 and 0xFF) - (cb ushr 16 and 0xFF))
        val dg = kotlin.math.abs((ca ushr 8 and 0xFF) - (cb ushr 8 and 0xFF))
        val db = kotlin.math.abs((ca and 0xFF) - (cb and 0xFF))
        return maxOf(dr, dg, db) > tolerance
    }
}
