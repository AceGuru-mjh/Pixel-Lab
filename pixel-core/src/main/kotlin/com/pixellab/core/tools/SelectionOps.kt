package com.pixellab.core.tools

import com.pixellab.core.engine.DrawOps
import com.pixellab.core.engine.FloodFill
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.PixelPoint

/**
 * An axis-aligned rectangular selection on a pixel canvas.
 *
 * Coordinates follow the [com.pixellab.core.engine.DrawOps.rect] box
 * convention: (`x`, `y`) is the top-left pixel, `w x h` the size, and the
 * covered pixels are the half-open ranges `x until x + w` / `y until y + h`.
 *
 * `x`/`y` may lie outside the canvas (selections dragged off the edge);
 * combine such selections with [inBounds] to clamp them back onto a
 * `width x height` grid. A selection with `w == 0` or `h == 0` is **empty**:
 * [contains] is always false and the pixel operations of [SelectionOps]
 * reject it. Widths and heights below zero are invalid.
 */
data class Selection(val x: Int, val y: Int, val w: Int, val h: Int) {

    init {
        require(w >= 0) { "selection width must be >= 0 (was $w)" }
        require(h >= 0) { "selection height must be >= 0 (was $h)" }
    }

    /** True when the selection covers no pixels at all. */
    val isEmpty: Boolean get() = w == 0 || h == 0

    /** First column past the right edge (exclusive). */
    val right: Int get() = x + w

    /** First row past the bottom edge (exclusive). */
    val bottom: Int get() = y + h

    /**
     * Selection shrunk by [d] on every side. Insetting never empties the
     * selection: when `2 * d` would consume the width/height, the box
     * collapses to the centered 1x1 core instead.
     *
     * @throws IllegalArgumentException when [d] is negative or absurdly
     *   large (`> 2^20`).
     */
    fun inset(d: Int): Selection {
        require(d >= 0) { "inset amount must be >= 0 (was $d)" }
        require(d <= MAX_DELTA) { "inset amount must be <= $MAX_DELTA (was $d)" }
        if (d == 0 || isEmpty) return this
        val left = (x + d).coerceAtMost(x + w / 2)
        val rightEdge = (x + w - d).coerceAtLeast(x + (w + 1) / 2)
        val top = (y + d).coerceAtMost(y + h / 2)
        val bottomEdge = (y + h - d).coerceAtLeast(y + (h + 1) / 2)
        return Selection(left, top, maxOf(1, rightEdge - left), maxOf(1, bottomEdge - top))
    }

    /**
     * Selection grown by [d] on every side; the box may then extend past the
     * canvas or into negative coordinates (clamp with [inBounds]).
     *
     * @throws IllegalArgumentException when [d] is negative or absurdly
     *   large (`> 2^20`).
     */
    fun expand(d: Int): Selection {
        require(d >= 0) { "expand amount must be >= 0 (was $d)" }
        require(d <= MAX_DELTA) { "expand amount must be <= $MAX_DELTA (was $d)" }
        if (d == 0) return this
        return Selection(x - d, y - d, w + 2 * d, h + 2 * d)
    }

    /**
     * Selection translated by (`dx`, `dy`) — may move off-canvas; clamp with
     * [inBounds] afterwards.
     *
     * @throws IllegalArgumentException when a delta exceeds `2^20`.
     */
    fun translate(dx: Int, dy: Int): Selection {
        require(dx in -MAX_DELTA..MAX_DELTA) { "dx must be in [-$MAX_DELTA, $MAX_DELTA] (was $dx)" }
        require(dy in -MAX_DELTA..MAX_DELTA) { "dy must be in [-$MAX_DELTA, $MAX_DELTA] (was $dy)" }
        return Selection(x + dx, y + dy, w, h)
    }

    /**
     * Bounding box of this selection and [other]. Empty selections
     * contribute nothing (the union of anything with an empty selection is
     * the other selection).
     */
    fun union(other: Selection): Selection {
        if (isEmpty) return other
        if (other.isEmpty) return this
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        val rightEdge = maxOf(right, other.right)
        val bottomEdge = maxOf(bottom, other.bottom)
        return Selection(left, top, rightEdge - left, bottomEdge - top)
    }

    /**
     * Geometric intersection with [other]; disjoint inputs yield an **empty**
     * selection (`w == 0` or `h == 0`). Use [intersects] to test first.
     */
    fun intersect(other: Selection): Selection {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val rightEdge = minOf(right, other.right)
        val bottomEdge = minOf(bottom, other.bottom)
        return Selection(left, top, maxOf(0, rightEdge - left), maxOf(0, bottomEdge - top))
    }

    /** True when this selection and [other] overlap in at least one pixel. */
    fun intersects(other: Selection): Boolean = !intersect(other).isEmpty

    /** True when pixel (`px`, `py`) is covered (half-open ranges). */
    fun contains(px: Int, py: Int): Boolean = px >= x && px < right && py >= y && py < bottom

    /** True when [point] is covered; enables `point in selection`. */
    operator fun contains(point: PixelPoint): Boolean = contains(point.x, point.y)

    /**
     * Clamps this selection onto the `width x height` canvas: the part
     * outside the canvas is cut away, and a selection lying fully outside
     * becomes empty.
     *
     * @throws IllegalArgumentException when `width` or `height` is not
     *   positive.
     */
    fun inBounds(width: Int, height: Int): Selection {
        require(width > 0) { "width must be positive (was $width)" }
        require(height > 0) { "height must be positive (was $height)" }
        return intersect(Selection(0, 0, width, height))
    }

    private companion object {
        /** Defensive bound for delta amounts, far beyond any pixel canvas. */
        private const val MAX_DELTA = 1 shl 20
    }
}

/**
 * Rectangular-selection operations on frames bound to one fixed
 * `frameWidth x frameHeight` canvas: extraction, moving, clipboard-style
 * copy/paste, in-place flips and rotation, marching-ants outlines and
 * flood-based magic selection.
 *
 * All operations are pure — frames are never mutated, results are new frames
 * (or the input instance for true no-ops). Every frame argument must match
 * the canvas dimensions the instance was constructed with.
 */
class SelectionOps(val frameWidth: Int, val frameHeight: Int) {

    init {
        require(frameWidth > 0) { "frameWidth must be positive (was $frameWidth)" }
        require(frameHeight > 0) { "frameHeight must be positive (was $frameHeight)" }
    }

    /**
     * Copies the selection's region out of [frame] as a standalone frame of
     * exactly `sel.w x sel.h` (off-canvas parts of the region become
     * transparent).
     *
     * @throws IllegalArgumentException when [frame] does not match the canvas
     *   dimensions or [sel] is empty.
     */
    fun extract(frame: PixelFrame, sel: Selection): PixelFrame {
        requireFrame(frame)
        require(!sel.isEmpty) { "cannot extract an empty selection" }
        return frame.region(sel.x, sel.y, sel.w, sel.h)
    }

    /**
     * Moves the selected pixels by (`dx`, `dy`): the region's content is
     * copied out, the original footprint is cleared to transparent and the
     * content is pasted at the shifted position. Content shifted out of the
     * canvas is lost; off-canvas parts of the selection are handled
     * gracefully. When source and destination footprints overlap, the pasted
     * content wins. A move that changes nothing returns [frame] itself.
     *
     * @throws IllegalArgumentException when [frame] does not match the canvas
     *   dimensions or [sel] is empty.
     */
    fun move(frame: PixelFrame, sel: Selection, dx: Int, dy: Int): PixelFrame {
        requireFrame(frame)
        require(!sel.isEmpty) { "cannot move an empty selection" }
        val patch = frame.region(sel.x, sel.y, sel.w, sel.h)
        val out = frame.copyPixels()
        val clearTop = maxOf(0, sel.y)
        val clearBottom = minOf(frameHeight, sel.bottom)
        val clearLeft = maxOf(0, sel.x)
        val clearRight = minOf(frameWidth, sel.right)
        for (y in clearTop until clearBottom) {
            for (x in clearLeft until clearRight) {
                out[y * frameWidth + x] = 0
            }
        }
        for (py in 0 until sel.h) {
            val ty = sel.y + dy + py
            if (ty < 0 || ty >= frameHeight) continue
            for (px in 0 until sel.w) {
                val tx = sel.x + dx + px
                if (tx < 0 || tx >= frameWidth) continue
                out[ty * frameWidth + tx] = patch.pixels[py * sel.w + px]
            }
        }
        if (out.contentEquals(frame.pixels)) return frame
        return PixelFrame.of(frameWidth, frameHeight, out)
    }

    /**
     * Clipboard-style copy: extracts the region (see [extract]) and returns
     * it together with its **footprint** selection anchored at the origin —
     * `Selection(0, 0, sel.w, sel.h)`. Pasting that pair with
     * [paste] at (`x`, `y`) restores the content at the top-left corner
     * (`x`, `y`).
     *
     * @throws IllegalArgumentException when [frame] does not match the canvas
     *   dimensions or [sel] is empty.
     */
    fun copy(frame: PixelFrame, sel: Selection): Pair<PixelFrame, Selection> {
        val patch = extract(frame, sel)
        return patch to Selection(0, 0, sel.w, sel.h)
    }

    /**
     * Pastes [patch] with its top-left corner at (`x`, `y`) onto a copy of
     * [target] (off-canvas parts clipped). Delegates to
     * [TransformOps.replaceRegion].
     *
     * @throws IllegalArgumentException when [target] does not match the
     *   canvas dimensions.
     */
    fun paste(target: PixelFrame, patch: PixelFrame, x: Int, y: Int): PixelFrame {
        requireFrame(target)
        return TransformOps.replaceRegion(target, patch, x, y)
    }

    /**
     * Mirrors the selected region of [frame] left-right **in place**: the
     * patch is flipped and pasted back at the selection's position.
     *
     * @throws IllegalArgumentException when [frame] does not match the canvas
     *   dimensions or [sel] is empty.
     */
    fun flipSelectionH(frame: PixelFrame, sel: Selection): PixelFrame {
        requireFrame(frame)
        require(!sel.isEmpty) { "cannot flip an empty selection" }
        val patch = frame.region(sel.x, sel.y, sel.w, sel.h).mirroredHorizontally()
        return TransformOps.replaceRegion(frame, patch, sel.x, sel.y)
    }

    /**
     * Mirrors the selected region of [frame] top-bottom **in place**.
     *
     * @throws IllegalArgumentException when [frame] does not match the canvas
     *   dimensions or [sel] is empty.
     */
    fun flipSelectionV(frame: PixelFrame, sel: Selection): PixelFrame {
        requireFrame(frame)
        require(!sel.isEmpty) { "cannot flip an empty selection" }
        val patch = frame.region(sel.x, sel.y, sel.w, sel.h).mirroredVertically()
        return TransformOps.replaceRegion(frame, patch, sel.x, sel.y)
    }

    /**
     * Rotates the selected region of [frame] by 90 degrees clockwise **in
     * place**; only square selections can rotate within their own footprint.
     *
     * @throws IllegalArgumentException when [frame] does not match the canvas
     *   dimensions, [sel] is empty or not square.
     */
    fun rotateSelection90(frame: PixelFrame, sel: Selection): PixelFrame {
        requireFrame(frame)
        require(!sel.isEmpty) { "cannot rotate an empty selection" }
        require(sel.w == sel.h) {
            "rotateSelection90 requires a square selection (was ${sel.w}x${sel.h})"
        }
        val patch = frame.region(sel.x, sel.y, sel.w, sel.h).rotated90Cw()
        return TransformOps.replaceRegion(frame, patch, sel.x, sel.y)
    }

    /**
     * Boundary points of [sel] in clockwise marching order (top edge
     * left-to-right, right edge top-to-bottom, bottom edge right-to-left,
     * left edge bottom-to-top; corners visited once) — ready for UI
     * marching-ants rendering. Points in the negative quadrant are dropped
     * ([PixelPoint] invariant); points past the right/bottom canvas edges
     * are kept so off-canvas selection edges stay visible. Empty selections
     * yield an empty list.
     */
    fun outlineSelection(sel: Selection): List<PixelPoint> {
        if (sel.isEmpty) return emptyList()
        return DrawOps.rect(sel.x, sel.y, sel.w, sel.h, false)
    }

    /**
     * Magic-wand selection: flood-fills (4-connected) from the seed pixel
     * (`x`, `y`) over [frame] using the **same tolerance semantics as
     * [com.pixellab.core.engine.FloodFill]** — a pixel joins the region when
     * every A/R/G/B channel (alpha included) differs from the seed pixel by
     * at most [tolerance] — and returns the region's **bounding box**.
     *
     * Approximate semantics: the result is the axis-aligned bounding box of
     * the flood region, so pixels inside the box but outside the region are
     * included — it is a rectangular approximation, not the exact region
     * outline.
     *
     * @throws IllegalArgumentException when [frame] does not match the canvas
     *   dimensions, [tolerance] is negative or the seed is outside the
     *   canvas.
     */
    fun magicSelect(frame: PixelFrame, x: Int, y: Int, tolerance: Int): Selection {
        requireFrame(frame)
        require(tolerance >= 0) { "tolerance must be >= 0 (was $tolerance)" }
        require(x in 0 until frameWidth && y in 0 until frameHeight) {
            "magicSelect seed ($x, $y) is outside the ${frameWidth}x${frameHeight} canvas"
        }
        val target = frame.pixels[y * frameWidth + x]
        val matches: (Int) -> Boolean = if (tolerance == 0) {
            { value -> value == target }
        } else {
            { value -> FloodFill.withinTolerance(value, target, tolerance) }
        }

        val visited = BooleanArray(frameWidth * frameHeight)
        var stack = IntArray(64)
        var top = 0

        fun push(index: Int) {
            if (top == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[top++] = index
        }

        fun probe(index: Int) {
            if (!visited[index] && matches(frame.pixels[index])) {
                visited[index] = true
                push(index)
            }
        }

        val seed = y * frameWidth + x
        visited[seed] = true
        push(seed)
        var minX = x
        var maxX = x
        var minY = y
        var maxY = y
        while (top > 0) {
            val index = stack[--top]
            val px = index % frameWidth
            val py = index / frameWidth
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
            if (px > 0) probe(index - 1)
            if (px < frameWidth - 1) probe(index + 1)
            if (py > 0) probe(index - frameWidth)
            if (py < frameHeight - 1) probe(index + frameWidth)
        }
        return Selection(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** Rejects frames whose dimensions do not match this canvas. */
    private fun requireFrame(frame: PixelFrame) {
        require(frame.width == frameWidth && frame.height == frameHeight) {
            "frame ${frame.width}x${frame.height} does not match the " +
                "${frameWidth}x${frameHeight} canvas"
        }
    }
}
