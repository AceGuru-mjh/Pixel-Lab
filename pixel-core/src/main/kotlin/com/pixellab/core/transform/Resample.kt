package com.pixellab.core.transform

import com.pixellab.core.model.PixelFrame
import kotlin.math.floor

/**
 * Pixel-safe resampling: half-pixel-centered nearest neighbor, box-filter
 * area averaging and mipmap chains.
 *
 * The naive `dst(x, y) = src(floor(x · srcW / dstW))` mapping is biased:
 * it samples the top-left corner of each destination cell and visibly
 * shifts the image when downscaling. This implementation uses the
 * **half-pixel center convention** — every destination cell is treated
 * as covering `[x / ratio, (x + 1) / ratio)` and samples at its center —
 * which is what every mainstream resampler (PIL, OpenCV INTER_NEAREST,
 * Skia) actually does.
 *
 * The box filter accumulates **premultiplied color** over the covered
 * source area and un-premultiplies at the end, so downscaling sprites
 * with transparent padding never bleeds black into the edges.
 */
object Resample {

    /**
     * Nearest-neighbor rescale to [newWidth] x [newHeight] with
     * half-pixel-center mapping. Enlargement picks the nearest source
     * pixel per destination cell; reduction picks the source pixel under
     * each destination cell center (nearest-of-center, not area — for
     * area-averaged reduction use [boxResample]).
     */
    fun nearest(frame: PixelFrame, newWidth: Int, newHeight: Int): PixelFrame {
        require(newWidth > 0 && newHeight > 0) { "Target dimensions must be positive" }
        if (frame.width == newWidth && frame.height == newHeight) return frame
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = IntArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            // Half-pixel center: (y + 0.5) * h / newHeight, floored.
            val sy = floor((y + 0.5) * h / newHeight).toInt().coerceIn(0, h - 1)
            for (x in 0 until newWidth) {
                val sx = floor((x + 0.5) * w / newWidth).toInt().coerceIn(0, w - 1)
                out[y * newWidth + x] = src[sy * w + sx]
            }
        }
        return PixelFrame.of(newWidth, newHeight, out)
    }

    /**
     * Box-filter resample: each destination cell averages every source
     * pixel its area covers, in premultiplied space.
     *
     * Enlargement averages the (at most) few source pixels in the cell
     * — usually exactly one, degenerating to nearest for integer
     * factors; reduction averages all covered pixels, giving correct
     * anti-aliased minification for photo-to-pixel conversion pipelines.
     */
    fun boxResample(frame: PixelFrame, newWidth: Int, newHeight: Int): PixelFrame {
        require(newWidth > 0 && newHeight > 0) { "Target dimensions must be positive" }
        if (frame.width == newWidth && frame.height == newHeight) return frame
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = IntArray(newWidth * newHeight)
        val xRatio = w.toDouble() / newWidth
        val yRatio = h.toDouble() / newHeight
        for (y in 0 until newHeight) {
            val y0f = y * yRatio
            val y1f = (y + 1) * yRatio
            val sy0 = floor(y0f).toInt().coerceIn(0, h)
            val sy1 = kotlin.math.ceil(y1f).toInt().coerceIn(0, h)
            for (x in 0 until newWidth) {
                val x0f = x * xRatio
                val x1f = (x + 1) * xRatio
                val sx0 = floor(x0f).toInt().coerceIn(0, w)
                val sx1 = kotlin.math.ceil(x1f).toInt().coerceIn(0, w)
                if (sx1 <= sx0 || sy1 <= sy0) {
                    // Degenerate cell (ratio < 1 with extreme rounding):
                    // fall back to the center sample.
                    val sx = floor((x + 0.5) * xRatio).toInt().coerceIn(0, w - 1)
                    val sy = floor((y + 0.5) * yRatio).toInt().coerceIn(0, h - 1)
                    out[y * newWidth + x] = src[sy * w + sx]
                    continue
                }
                var accA = 0L
                var accR = 0L
                var accG = 0L
                var accB = 0L
                var count = 0
                for (sy in sy0 until sy1) {
                    // Vertical coverage weight of this row inside the cell.
                    val rowIn = kotlin.math.min(y1f, (sy + 1).toDouble()) -
                        kotlin.math.max(y0f, sy.toDouble())
                    if (rowIn <= 0.0) continue
                    for (sx in sx0 until sx1) {
                        val colIn = kotlin.math.min(x1f, (sx + 1).toDouble()) -
                            kotlin.math.max(x0f, sx.toDouble())
                        if (colIn <= 0.0) continue
                        val weight = rowIn * colIn
                        val p = src[sy * w + sx]
                        val a = p ushr 24
                        accA += (a * weight).toLong()
                        accR += ((p ushr 16 and 0xFF) * a / 255.0 * weight).toLong()
                        accG += ((p ushr 8 and 0xFF) * a / 255.0 * weight).toLong()
                        accB += ((p and 0xFF) * a / 255.0 * weight).toLong()
                        count++
                        if (count > 255) break // pathological safety valve
                    }
                }
                out[y * newWidth + x] = if (count == 0) 0 else packAverage(accA, accR, accG, accB, count)
            }
        }
        return PixelFrame.of(newWidth, newHeight, out)
    }

    /**
     * Builds a mipmap chain: the full-resolution frame followed by each
     * successive half-size (rounded **down**, minimum 1px) box-filtered
     * level, stopping at [maxLevels] total entries (default: down to
     * 1px in either axis or 9 levels, whichever comes first).
     */
    fun mipmapChain(frame: PixelFrame, maxLevels: Int = 9): List<PixelFrame> {
        require(maxLevels >= 1) { "maxLevels must be ≥ 1" }
        val chain = ArrayList<PixelFrame>(maxLevels)
        var current = frame
        chain.add(current)
        while (chain.size < maxLevels) {
            val nextW = current.width / 2
            val nextH = current.height / 2
            if (nextW < 1 || nextH < 1) break
            current = boxResample(current, nextW, nextH)
            chain.add(current)
        }
        return chain
    }

    /**
     * Integer-factor area average downscale (fast path for 2x/3x/4x…):
     * averages each `factor x factor` block in premultiplied space.
     * Equivalent to [boxResample] for integer factors but without the
     * coverage-weight bookkeeping.
     */
    fun downscaleByInt(frame: PixelFrame, factor: Int): PixelFrame {
        require(factor > 1) { "factor must be > 1, got $factor" }
        val w = frame.width
        val h = frame.height
        val newW = w / factor
        val newH = h / factor
        if (newW == 0 || newH == 0) {
            throw IllegalArgumentException("frame ${w}x${h} too small for /$factor")
        }
        val src = frame.pixels
        val out = IntArray(newW * newH)
        val area = factor * factor
        for (y in 0 until newH) {
            for (x in 0 until newW) {
                var accA = 0L
                var accR = 0L
                var accG = 0L
                var accB = 0L
                for (dy in 0 until factor) {
                    val row = (y * factor + dy) * w + x * factor
                    for (dx in 0 until factor) {
                        val p = src[row + dx]
                        val a = p ushr 24
                        accA += a
                        accR += (p ushr 16 and 0xFF).toLong() * a / 255
                        accG += (p ushr 8 and 0xFF).toLong() * a / 255
                        accB += (p and 0xFF).toLong() * a / 255
                    }
                }
                out[y * newW + x] = packAverage(accA, accR, accG, accB, area)
            }
        }
        return PixelFrame.of(newW, newH, out)
    }

    /** Packs accumulated premultiplied averages back into straight ARGB. */
    private fun packAverage(accA: Long, accR: Long, accG: Long, accB: Long, count: Int): Int {
        if (count == 0 || accA <= 0) return 0
        val a = ((accA + count / 2) / count).toInt().coerceIn(0, 255)
        if (a == 0) return 0
        // Un-premultiply: channel sum was premultiplied by per-pixel alpha
        // AND the weight; divide by the accumulated alpha instead of the
        // plain count for exactness.
        val fa = accA.toDouble()
        val ur = ((accR / fa * 255.0).toInt()).coerceIn(0, 255)
        val ug = ((accG / fa * 255.0).toInt()).coerceIn(0, 255)
        val ub = ((accB / fa * 255.0).toInt()).coerceIn(0, 255)
        return a shl 24 or (ur shl 16) or (ug shl 8) or ub
    }
}
