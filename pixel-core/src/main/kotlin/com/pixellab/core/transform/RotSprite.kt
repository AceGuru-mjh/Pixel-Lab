package com.pixellab.core.transform

import com.pixellab.core.model.PixelFrame
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * How the output canvas of an arbitrary-angle rotation relates to the
 * input geometry.
 */
enum class RotateBoundsMode {
    /**
     * The canvas grows to the axis-aligned bounding box of the rotated
     * sprite, so nothing is clipped. The sprite center stays at the
     * canvas center (rounded to integers when the bounding box has odd
     * extents — the residue lands bottom-right, matching [PixelFrame]
     * conventions).
     */
    EXPAND,

    /** The output keeps the input's exact geometry; corners rotate out and clip. */
    CROP,
}

/**
 * RotSprite: pixel-art-safe arbitrary-angle rotation via three successive
 * axis-aligned shears.
 *
 * Classic nearest-neighbor rotation samples the rotated grid directly and
 * produces irregular "stair" artifacts plus double-width rows; bilinear
 * rotation smears every pixel into a blur. RotSprite — the approach used
 * by Aseprite and the SpriteSheet rotation literature — decomposes the
 * rotation into **three 1-D shears** (two horizontal, one vertical):
 *
 * ```
 * shearX(-tan(θ/2))  →  shearY(+sin(θ))  →  shearX(-tan(θ/2))
 * ```
 *
 * Each shear shifts whole pixel *rows* (or columns) by an integer amount
 * derived from a fractional accumulator, so pixels never blend and edges
 * stay hard; the accumulator timing distributes the rounding error
 * evenly along the row, which is what makes long diagonals come out
 * smooth instead of staircase-lumpy.
 *
 * All operations are pure; the input frame is never mutated.
 */
object RotSprite {

    /**
     * Rotates [frame] by [degrees] (clockwise, any real value) using the
     * three-shear decomposition.
     *
     * @param bounds canvas policy: [RotateBoundsMode.EXPAND] grows the
     *   canvas to fit the rotated sprite, [RotateBoundsMode.CROP] keeps
     *   the original canvas size.
     */
    fun rotate(frame: PixelFrame, degrees: Double, bounds: RotateBoundsMode = RotateBoundsMode.EXPAND): PixelFrame {
        require(degrees.isFinite()) { "degrees must be finite, was $degrees" }
        // Normalize to (-180, 180]: rotating by 90-multiples exactly is
        // PixelFrame's specialty and must never pay shear-rounding noise.
        var theta = degrees % 360.0
        if (theta > 180.0) theta -= 360.0
        if (theta <= -180.0) theta += 360.0
        val quarter = Math.round(theta / 90.0).toInt()
        val residual = theta - quarter * 90.0
        var current = frame
        when (((quarter % 4) + 4) % 4) {
            1 -> current = current.rotated90Cw()
            2 -> current = current.rotated180()
            3 -> current = current.rotated90Ccw()
            else -> {}
        }
        if (abs(residual) < 1e-9) {
            return if (bounds == RotateBoundsMode.CROP) centerCrop(current, frame.width, frame.height) else current
        }
        val rad = Math.toRadians(residual)
        val t = tan(rad / 2.0)
        val s = sin(rad)
        val sheared = shearX(shearY(shearX(current, -t), s), -t)
        return if (bounds == RotateBoundsMode.CROP) centerCrop(sheared, frame.width, frame.height) else sheared
    }

    /**
     * Horizontal shear: row `y` shifts right by `y * factor` pixels (left
     * for negative factors). The canvas widens to `[ceil(width + |offset|),
     * height]` so no column is lost; the row image is centered vertically
     * unchanged.
     *
     * The per-row shift is `round(y * factor)` — one integer move per row —
     * which keeps every row's pixels contiguous and hard-edged.
     */
    fun shearX(frame: PixelFrame, factor: Double): PixelFrame {
        if (abs(factor) < 1e-12 || frame.pixelCount == 0) return frame
        val w = frame.width
        val h = frame.height
        // Max |shift| over all rows.
        val minShift = floor((h - 1) * factor).toInt().coerceAtMost(0)
        val maxShift = ceil((h - 1) * factor).toInt().coerceAtLeast(0)
        val newW = w + (maxShift - minShift)
        if (newW == w) return frame
        val out = IntArray(newW * h)
        for (y in 0 until h) {
            val shift = (y * factor).roundToInt()
            val dstX = shift - minShift // ≥ 0 by construction
            val rowSrc = y * w
            val rowDst = y * newW
            // Clear the row, then blit the source row at dstX.
            var x = 0
            while (x < newW) { out[rowDst + x] = 0; x++ }
            System.arraycopy(frame.pixels, rowSrc, out, rowDst + dstX, w)
        }
        return PixelFrame.of(newW, h, out)
    }

    /**
     * Vertical shear: column `x` shifts down by `x * factor` pixels. The
     * canvas grows taller to fit; columns move as intact vertical runs.
     */
    fun shearY(frame: PixelFrame, factor: Double): PixelFrame {
        if (abs(factor) < 1e-12 || frame.pixelCount == 0) return frame
        val w = frame.width
        val h = frame.height
        val minShift = floor((w - 1) * factor).toInt().coerceAtMost(0)
        val maxShift = ceil((w - 1) * factor).toInt().coerceAtLeast(0)
        val newH = h + (maxShift - minShift)
        if (newH == h) return frame
        val out = IntArray(w * newH)
        for (x in 0 until w) {
            val shift = (x * factor).roundToInt()
            val dstY = shift - minShift
            for (y in 0 until h) {
                out[(dstY + y) * w + x] = frame.pixels[y * w + x]
            }
        }
        return PixelFrame.of(w, newH, out)
    }

    /**
     * Crops [frame] to [newW] x [newH] keeping the visual center aligned:
     * when the size difference is odd the extra pixel is removed from the
     * bottom/right, matching [PixelFrame.region] semantics.
     */
    private fun centerCrop(frame: PixelFrame, newW: Int, newH: Int): PixelFrame {
        if (frame.width == newW && frame.height == newH) return frame
        val x0 = (frame.width - newW) / 2
        val y0 = (frame.height - newH) / 2
        return frame.region(x0, y0, newW, newH)
    }

    /**
     * The axis-aligned bounding box of a `w x h` rectangle rotated by
     * [degrees], as `[newW, newH]`. This is what [RotateBoundsMode.EXPAND]
     * produces (up to integer rounding inside the shears, which may add
     * at most one extra row/column of padding).
     */
    fun expandedBounds(width: Int, height: Int, degrees: Double): IntArray {
        require(width > 0 && height > 0) { "Positive dimensions required" }
        var theta = degrees % 360.0
        if (theta > 180.0) theta -= 360.0
        if (theta <= -180.0) theta += 360.0
        val rad = Math.toRadians(abs(theta))
        val c = abs(cos(rad))
        val s = abs(sin(rad))
        val newW = ceil(width * c + height * s).toInt()
        val newH = ceil(width * s + height * c).toInt()
        return intArrayOf(newW, newH)
    }
}
