package com.pixellab.core.tools

import com.pixellab.core.model.PixelFrame

/**
 * Placement anchor for [TransformOps.scaleNearest]: which part of the
 * nearest-neighbor zoomed content the output window shows.
 */
enum class Anchor { TOP_LEFT, CENTER, BOTTOM_RIGHT }

/**
 * Frame-level geometric and morphological transformations for
 * [PixelFrame]s. Every operation is pure: inputs are never mutated and a
 * new frame (or the unchanged input instance for true no-ops) is returned.
 * All writes stay inside the frame bounds — no canvas clipping needed here.
 */
object TransformOps {

    /**
     * Rotates [frame] 90 degrees clockwise [times] times (cascading
     * [PixelFrame.rotated90Cw]; each quarter turn swaps width and height).
     * `times == 4` is a full 360 degree rotation and returns [frame] itself.
     *
     * @throws IllegalArgumentException when [times] is outside `1..4`.
     */
    fun rotate90(frame: PixelFrame, times: Int): PixelFrame {
        require(times in 1..4) { "times must be in [1, 4] (was $times)" }
        if (times == 4) return frame
        var out = frame
        repeat(times) { out = out.rotated90Cw() }
        return out
    }

    /**
     * Nearest-neighbor integer upscale of [frame] to `width*factor x
     * height*factor` — every input pixel maps onto a `factor x factor`
     * output block. (For a zoom that KEEPS the frame dimensions, crop the
     * result or use [com.pixellab.core.model.PixelFrame] windowing.)
     *
     * [anchor] is a reserved parameter kept for signature stability: with
     * integer upscaling the block mapping leaves no content to shift, so
     * every anchor value behaves identically. `factor == 1` is the identity
     * and returns [frame] itself.
     *
     * @throws IllegalArgumentException when [factor] is below 1, either
     *   scaled edge exceeds Int.MAX_VALUE, or the total pixel count
     *   (computed in Long — the old Int multiply wrapped negative for
     *   ~46341x46341 targets) exceeds the 268M budget.
     */
    fun scaleNearest(frame: PixelFrame, factor: Int, anchor: Anchor = Anchor.CENTER): PixelFrame {
        require(factor >= 1) { "factor must be >= 1 (was $factor)" }
        if (factor == 1) return frame
        // Integer upscaling maps every input pixel onto a factor x factor
        // output block, so the anchor cannot shift content: it is a reserved
        // parameter kept for signature stability with fractional scaling.
        val scaledWidth = frame.width.toLong() * factor
        val scaledHeight = frame.height.toLong() * factor
        require(scaledWidth <= Int.MAX_VALUE && scaledHeight <= Int.MAX_VALUE) {
            "scaled dimensions overflow: ${scaledWidth}x${scaledHeight}"
        }
        val outWidth = scaledWidth.toInt()
        val outHeight = scaledHeight.toInt()
        // Long-domain total: each EDGE passing Int.MAX does not make the
        // PRODUCT safe — `outWidth * outHeight` wrapped negative before.
        val outPixels = outWidth.toLong() * outHeight.toLong()
        require(outPixels <= 268_435_456L) {
            "scaled target ${outWidth}x${outHeight} ($outPixels px) exceeds the 268M pixel budget"
        }
        val out = IntArray(outPixels.toInt())
        var index = 0
        for (y in 0 until outHeight) {
            val sampleY = y / factor
            for (x in 0 until outWidth) {
                out[index++] = frame.pixels[sampleY * frame.width + x / factor]
            }
        }
        return PixelFrame.of(outWidth, outHeight, out)
    }

    /**
     * Morphological dilation of the **opaque** region (alpha != 0): each of
     * [iterations] passes grows the region one pixel into its 4-neighborhood,
     * painting newly covered pixels with [argb]. Existing pixels keep their
     * color; growth stops at the frame borders. Passes that cannot change
     * anything end the loop early (the converged frame is returned).
     *
     * @throws IllegalArgumentException when [iterations] is negative.
     */
    fun growRegion(frame: PixelFrame, argb: Int, iterations: Int): PixelFrame {
        require(iterations >= 0) { "iterations must be >= 0 (was $iterations)" }
        if (iterations == 0) return frame
        var current = frame
        repeat(iterations) {
            val mask = BooleanArray(current.pixels.size) { (current.pixels[it] ushr 24) != 0 }
            val next = current.copyPixels()
            var changed = false
            for (y in 0 until current.height) {
                for (x in 0 until current.width) {
                    val i = y * current.width + x
                    if (mask[i]) continue
                    val touches = (x > 0 && mask[i - 1]) ||
                        (x < current.width - 1 && mask[i + 1]) ||
                        (y > 0 && mask[i - current.width]) ||
                        (y < current.height - 1 && mask[i + current.width])
                    if (touches) {
                        next[i] = argb
                        changed = true
                    }
                }
            }
            if (!changed) return current
            current = PixelFrame.of(current.width, current.height, next)
        }
        return current
    }

    /**
     * Morphological erosion of the opaque region (boundary peeling): each of
     * [iterations] passes removes every opaque pixel that lacks at least one
     * opaque 4-neighbor. Pixels on the frame border always lack an outside
     * neighbor, so content touching the canvas edge erodes from the edge too
     * (strict erosion; out-of-bounds counts as empty). Fully transparent
     * results converge early.
     *
     * @throws IllegalArgumentException when [iterations] is negative.
     */
    fun shrinkRegion(frame: PixelFrame, iterations: Int): PixelFrame {
        require(iterations >= 0) { "iterations must be >= 0 (was $iterations)" }
        if (iterations == 0) return frame
        var current = frame
        repeat(iterations) {
            val mask = BooleanArray(current.pixels.size) { (current.pixels[it] ushr 24) != 0 }
            val next = current.copyPixels()
            var changed = false
            for (y in 0 until current.height) {
                for (x in 0 until current.width) {
                    val i = y * current.width + x
                    if (!mask[i]) continue
                    val surrounded = (x > 0 && mask[i - 1]) &&
                        (x < current.width - 1 && mask[i + 1]) &&
                        (y > 0 && mask[i - current.width]) &&
                        (y < current.height - 1 && mask[i + current.width])
                    if (!surrounded) {
                        next[i] = 0
                        changed = true
                    }
                }
            }
            if (!changed) return current
            current = PixelFrame.of(current.width, current.height, next)
        }
        return current
    }

    /**
     * Extracts the `w x h` sub-frame at (`x`, `y`). Regions outside the
     * source become transparent, so negative origins and overhangs are
     * valid (unlike a hard clip, the result keeps the requested size).
     *
     * @throws IllegalArgumentException when `w` or `h` is below 1.
     */
    fun crop(frame: PixelFrame, x: Int, y: Int, w: Int, h: Int): PixelFrame {
        require(w >= 1) { "crop width must be >= 1 (was $w)" }
        require(h >= 1) { "crop height must be >= 1 (was $h)" }
        return frame.region(x, y, w, h)
    }

    /**
     * Surrounds [frame] with transparent padding: the result measures
     * `width + left + right x height + top + bottom` and contains the source
     * at offset (`left`, `top`).
     *
     * @throws IllegalArgumentException when any padding amount is negative.
     */
    fun pad(frame: PixelFrame, left: Int, top: Int, right: Int, bottom: Int): PixelFrame {
        require(left >= 0) { "left padding must be >= 0 (was $left)" }
        require(top >= 0) { "top padding must be >= 0 (was $top)" }
        require(right >= 0) { "right padding must be >= 0 (was $right)" }
        require(bottom >= 0) { "bottom padding must be >= 0 (was $bottom)" }
        if (left == 0 && top == 0 && right == 0 && bottom == 0) return frame
        val newWidth = frame.width + left + right
        val newHeight = frame.height + top + bottom
        return frame.transformed(newWidth, newHeight) { x, y -> frame[x - left, y - top] }
    }

    /**
     * Pastes [patch] with its top-left corner at (`x`, `y`) onto a copy of
     * [target]. Patch pixels outside the target bounds are clipped; target
     * pixels not covered by the patch are untouched. When nothing changes
     * (the patch is fully clipped or identical), [target] itself is
     * returned.
     */
    fun replaceRegion(target: PixelFrame, patch: PixelFrame, x: Int, y: Int): PixelFrame {
        val out = target.copyPixels()
        var changed = false
        for (py in 0 until patch.height) {
            val ty = y + py
            if (ty < 0 || ty >= target.height) continue
            for (px in 0 until patch.width) {
                val tx = x + px
                if (tx < 0 || tx >= target.width) continue
                val index = ty * target.width + tx
                val value = patch.pixels[py * patch.width + px]
                out[index] = value
                if (value != target.pixels[index]) changed = true
            }
        }
        if (!changed) return target
        return PixelFrame.of(target.width, target.height, out)
    }

    /**
     * Masks [target] by [mask]: pixels under an opaque (alpha != 0) mask
     * pixel keep their color, all others become transparent. Both frames
     * must have identical dimensions.
     *
     * @throws IllegalArgumentException when the frame dimensions differ.
     */
    fun maskBy(target: PixelFrame, mask: PixelFrame): PixelFrame {
        require(target.width == mask.width && target.height == mask.height) {
            "maskBy frames must match: target ${target.width}x${target.height}, " +
                "mask ${mask.width}x${mask.height}"
        }
        val out = IntArray(target.pixels.size)
        var changed = false
        for (i in out.indices) {
            if (mask.pixels[i] ushr 24 != 0) {
                out[i] = target.pixels[i]
            } else if (target.pixels[i] != 0) {
                changed = true
            }
        }
        if (!changed) return target
        return PixelFrame.of(target.width, target.height, out)
    }
}
