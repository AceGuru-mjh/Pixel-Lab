package com.pixellab.core.transform

import com.pixellab.core.model.PixelFrame

/**
 * Rotation facade: exact quarter turns delegate to [PixelFrame]'s own
 * lossless operations; arbitrary angles go through [RotSprite] (or a
 * plain nearest-neighbor sampler for callers that prefer it).
 */
object RotateOps {

    /**
     * Rotates [frame] by [degrees] clockwise.
     *
     * * Integer multiples of 90° use [PixelFrame.rotated90Cw]/[rotated180]/
     *   [rotated90Ccw] — bit-exact, no canvas growth.
     * * Every other angle uses the three-shear RotSprite decomposition.
     *
     * @param bounds canvas policy for non-quarter angles
     *   ([RotateBoundsMode.EXPAND] by default).
     */
    fun rotate(frame: PixelFrame, degrees: Double, bounds: RotateBoundsMode = RotateBoundsMode.EXPAND): PixelFrame =
        RotSprite.rotate(frame, degrees, bounds)

    /**
     * Classic nearest-neighbor rotation: samples the rotated grid
     * directly (`dst(x,y) = src(center + rotate((x,y) − center))`).
     * Cheaper than RotSprite and completely unbiased, but produces the
     * well-known irregular stair artifacts on diagonal edges — kept for
     * pipelines that want the "raw" look or a comparison baseline.
     */
    fun rotateNearest(frame: PixelFrame, degrees: Double, bounds: RotateBoundsMode = RotateBoundsMode.EXPAND): PixelFrame {
        require(degrees.isFinite()) { "degrees must be finite" }
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
        if (kotlin.math.abs(residual) < 1e-9) {
            return if (bounds == RotateBoundsMode.CROP) centerCrop(current, frame.width, frame.height) else current
        }
        val rad = Math.toRadians(residual)
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        val w = current.width
        val h = current.height
        // Expanded canvas: bounding box of rotated corners.
        val cornersX = doubleArrayOf(
            kotlin.math.abs(w / 2.0 * cos + h / 2.0 * sin),
            kotlin.math.abs(w / 2.0 * cos - h / 2.0 * sin),
        )
        val cornersY = doubleArrayOf(
            kotlin.math.abs(w / 2.0 * sin + h / 2.0 * cos),
            kotlin.math.abs(w / 2.0 * sin - h / 2.0 * cos),
        )
        val newW = (2 * cornersX.max() + 0.5).toInt().coerceAtLeast(1)
        val newH = (2 * cornersY.max() + 0.5).toInt().coerceAtLeast(1)
        val cx = w / 2.0
        val cy = h / 2.0
        val outW = if (bounds == RotateBoundsMode.EXPAND) newW else w
        val outH = if (bounds == RotateBoundsMode.EXPAND) newH else h
        val ocx = outW / 2.0
        val ocy = outH / 2.0
        return current.transformed(outW, outH) { x, y ->
            val dx = x + 0.5 - ocx
            val dy = y + 0.5 - ocy
            // Inverse rotation: rotate destination vector back by -residual
            // (destination→source mapping keeps sampling exact).
            val sx = dx * cos + dy * sin + cx
            val sy = -dx * sin + dy * cos + cy
            val isx = kotlin.math.floor(sx).toInt()
            val isy = kotlin.math.floor(sy).toInt()
            if (isx in 0 until w && isy in 0 until h) current.pixels[isy * w + isx] else 0
        }
    }

    /**
     * Convenience: rotate [frame] by [degrees] and keep the original
     * canvas geometry ([RotateBoundsMode.CROP]).
     */
    fun rotateInPlace(frame: PixelFrame, degrees: Double): PixelFrame =
        RotSprite.rotate(frame, degrees, RotateBoundsMode.CROP)

    private fun centerCrop(frame: PixelFrame, newW: Int, newH: Int): PixelFrame {
        if (frame.width == newW && frame.height == newH) return frame
        val x0 = (frame.width - newW) / 2
        val y0 = (frame.height - newH) / 2
        return frame.region(x0, y0, newW, newH)
    }
}
