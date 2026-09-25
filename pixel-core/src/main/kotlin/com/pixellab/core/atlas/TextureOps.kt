package com.pixellab.core.atlas

import com.pixellab.core.model.PixelFrame
import com.pixellab.core.tools.Anchor

/**
 * An integer rectangle in pixel space, used for crop windows, content bounds
 * and atlas regions. Coordinates and sizes are non-negative; a width/height
 * of zero is legal only as an "empty" marker (never inside [PixelFrame],
 * which requires positive dimensions).
 *
 * @throws IllegalArgumentException if any component is negative.
 */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    init {
        require(x >= 0 && y >= 0 && w >= 0 && h >= 0) {
            "Rect components must be non-negative (x=$x, y=$y, w=$w, h=$h)"
        }
    }

    /** Exclusive right edge (`x + w`). */
    val right: Int get() = x + w

    /** Exclusive bottom edge (`y + h`). */
    val bottom: Int get() = y + h

    /** `true` when the rectangle has zero area. */
    val isEmpty: Boolean get() = w == 0 || h == 0
}

/**
 * Frame-level texture plumbing for atlas construction: trimming, cropping,
 * padding, edge extrusion, canvas resizing and the canonical flips/rotations.
 *
 * Every operation is pure — inputs are never mutated, a new [PixelFrame] is
 * returned, and true no-ops return the input instance itself (the same
 * convention as [PixelFrame.scaledNearest] with `scale == 1`). The flips and
 * quarter rotations simply delegate to the existing [PixelFrame] primitives
 * so there is exactly one implementation of each transform in the codebase.
 *
 * The **opacity convention** follows [com.pixellab.core.tools.OutlineShading]
 * for content detection: a pixel is content when its alpha is `> 0`
 * (semi-transparent pixels count).
 */
object TextureOps {

    /**
     * Finds the tight bounding box of all pixels with alpha `> 0`.
     *
     * @param frame the frame to scan; not mutated.
     * @return the content [Rect], or `null` when the frame is (fully)
     *   transparent — there is no content to bound.
     */
    fun contentBounds(frame: PixelFrame): Rect? {
        val pixels = frame.pixels
        var minX = -1
        var minY = -1
        var maxX = -1
        var maxY = -1
        for (y in 0 until frame.height) {
            val row = y * frame.width
            for (x in 0 until frame.width) {
                if (pixels[row + x] ushr 24 != 0) {
                    if (minX < 0 || x < minX) minX = x
                    if (y < minY || minY < 0) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (minX < 0) return null
        return Rect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /**
     * Crops [frame] to the tight bounds of its opaque content, keeping at
     * least [minimum] pixels of transparent context on every side (the
     * window is expanded outward by [minimum] and clamped to the frame).
     *
     * `minimum == 0` is the tight crop. A fully transparent frame has no
     * anchor to expand from, so the result is a blank 1x1 frame (documented
     * compromise — [PixelFrame] requires positive dimensions).
     *
     * @param minimum transparent slack kept around the content, `>= 0`.
     * @throws IllegalArgumentException if [minimum] is negative.
     */
    fun trimTransparent(frame: PixelFrame, minimum: Int = 0): PixelFrame {
        require(minimum >= 0) { "minimum must be >= 0 (was $minimum)" }
        val bounds = contentBounds(frame) ?: return PixelFrame.blank(1, 1)
        val x = maxOf(0, bounds.x - minimum)
        val y = maxOf(0, bounds.y - minimum)
        val right = minOf(frame.width, bounds.right + minimum)
        val bottom = minOf(frame.height, bounds.bottom + minimum)
        return crop(frame, Rect(x, y, right - x, bottom - y))
    }

    /**
     * Crops [frame] to [rect], clipped to the frame bounds. Pixels of the
     * target outside the frame are transparent; an intersection of zero
     * width/height yields a blank 1x1 frame (again: [PixelFrame] requires
     * positive dimensions).
     *
     * @param rect the crop window in frame coordinates.
     */
    fun crop(frame: PixelFrame, rect: Rect): PixelFrame {
        val x0 = maxOf(rect.x, 0)
        val y0 = maxOf(rect.y, 0)
        val x1 = minOf(rect.right, frame.width)
        val y1 = minOf(rect.bottom, frame.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return PixelFrame.blank(1, 1)
        val out = IntArray(w * h)
        val src = frame.pixels
        for (y in 0 until h) {
            System.arraycopy(src, (y0 + y) * frame.width + x0, out, y * w, w)
        }
        return PixelFrame.of(w, h, out)
    }

    /**
     * Adds a fully transparent border of [padding] pixels on all four sides.
     * `padding == 0` returns [frame] itself.
     *
     * @throws IllegalArgumentException if [padding] is negative.
     */
    fun pad(frame: PixelFrame, padding: Int): PixelFrame {
        require(padding >= 0) { "padding must be >= 0 (was $padding)" }
        if (padding == 0) return frame
        val w = frame.width + 2 * padding
        val h = frame.height + 2 * padding
        require(w > 0 && h > 0) { "padded frame exceeds dimension limits" }
        val out = IntArray(w * h)
        val src = frame.pixels
        for (y in 0 until frame.height) {
            System.arraycopy(src, y * frame.width, out, (padding + y) * w + padding, frame.width)
        }
        return PixelFrame.of(w, h, out)
    }

    /**
     * Replicates the edge pixels outward by [amount] pixels on all sides
     * (the standard "extrude"/"bleed" used to fight texture atlas sampling
     * seams: the halo lets bilinear filtering at region edges sample real
     * color instead of the neighboring sprite).
     *
     * Output pixel (`x`, `y`) samples the source at
     * `(clamp(x - amount, 0, w-1), clamp(y - amount, 0, h-1))`, so the four
     * corners of the result equal the four corners of the source and edges
     * stretch as straight bands. `amount == 0` returns [frame] itself.
     *
     * @throws IllegalArgumentException if [amount] is negative.
     */
    fun extrudeEdges(frame: PixelFrame, amount: Int): PixelFrame {
        require(amount >= 0) { "amount must be >= 0 (was $amount)" }
        if (amount == 0) return frame
        val w = frame.width
        val h = frame.height
        val outW = w + 2 * amount
        val outH = h + 2 * amount
        val src = frame.pixels
        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            val sy = (y - amount).coerceIn(0, h - 1)
            for (x in 0 until outW) {
                val sx = (x - amount).coerceIn(0, w - 1)
                out[y * outW + x] = src[sy * w + sx]
            }
        }
        return PixelFrame.of(outW, outH, out)
    }

    /**
     * Places [frame] on a transparent `w x h` canvas anchored per [anchor]:
     *
     *  * [Anchor.TOP_LEFT] — content at `(0, 0)`;
     *  * [Anchor.CENTER] — at `((w - frame.width) / 2, (h - frame.height) / 2)`
     *    (floor division, so odd slack favors the top-left);
     *  * [Anchor.BOTTOM_RIGHT] — at `(w - frame.width, h - frame.height)`.
     *
     * Content larger than the canvas is clipped (negative offsets are legal
     * and simply cut the overflowing side); the destination stays transparent
     * wherever no source pixel lands.
     *
     * @throws IllegalArgumentException if `w`/`h` is below 1.
     */
    fun resizeCanvas(frame: PixelFrame, w: Int, h: Int, anchor: Anchor = Anchor.CENTER): PixelFrame {
        require(w >= 1 && h >= 1) { "canvas dimensions must be >= 1 (w=$w, h=$h)" }
        val dx = when (anchor) {
            Anchor.TOP_LEFT -> 0
            Anchor.CENTER -> (w - frame.width) / 2
            Anchor.BOTTOM_RIGHT -> w - frame.width
        }
        val dy = when (anchor) {
            Anchor.TOP_LEFT -> 0
            Anchor.CENTER -> (h - frame.height) / 2
            Anchor.BOTTOM_RIGHT -> h - frame.height
        }
        val src = frame.pixels
        val out = IntArray(w * h)
        for (y in 0 until frame.height) {
            val py = y + dy
            if (py < 0 || py >= h) continue
            for (x in 0 until frame.width) {
                val px = x + dx
                if (px < 0 || px >= w) continue
                out[py * w + px] = src[y * frame.width + x]
            }
        }
        return PixelFrame.of(w, h, out)
    }

    /** Mirrors left-right — delegates to [PixelFrame.mirroredHorizontally]. */
    fun flipH(frame: PixelFrame): PixelFrame = frame.mirroredHorizontally()

    /** Mirrors top-bottom — delegates to [PixelFrame.mirroredVertically]. */
    fun flipV(frame: PixelFrame): PixelFrame = frame.mirroredVertically()

    /** Rotates 90 degrees clockwise — delegates to [PixelFrame.rotated90Cw]. */
    fun rotate90Cw(frame: PixelFrame): PixelFrame = frame.rotated90Cw()

    /** Rotates 90 degrees counter-clockwise — delegates to [PixelFrame.rotated90Ccw]. */
    fun rotate90Ccw(frame: PixelFrame): PixelFrame = frame.rotated90Ccw()
}
