package com.pixellab.core.atlas

import com.pixellab.core.model.PixelFrame

/**
 * One sprite to pack: a unique name plus its pixels.
 *
 * @throws IllegalArgumentException if [name] is blank.
 */
data class AtlasInput(val name: String, val frame: PixelFrame) {
    init {
        require(name.isNotBlank()) { "atlas input name must not be blank" }
    }
}

/**
 * Where one sprite landed on the atlas canvas.
 *
 * When [rotated] is `true` the region stores the **swapped** dimensions
 * (`w == frame.height`, `h == frame.width`) and the pixels inside the region
 * are the source rotated 90 degrees clockwise — an atlas consumer rotating
 * the region 90 degrees counter-clockwise recovers the original sprite. The
 * packer itself never transforms the source [AtlasInput.frame] object.
 *
 * @property x region left edge in atlas pixels.
 * @property y region top edge in atlas pixels.
 * @property w region width in atlas pixels (swapped when [rotated]).
 * @property h region height in atlas pixels (swapped when [rotated]).
 * @property rotated whether the sprite was placed rotated (requires the
 *   packer's `allowRotation`).
 */
data class PackedRect(
    val name: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val rotated: Boolean,
)

/**
 * Packing heuristic selector for [AtlasPacker.pack].
 */
enum class PackHeuristic {
    /**
     * Shelf packing: inputs sorted by height (descending, ties by name) laid
     * out left-to-right in one row; the canvas height is the tallest input.
     * Simple, cache-friendly for uniform sprites, never rotates.
     */
    SHELF,

    /**
     * MaxRects with Best-Short-Side-Fit: a full free-rectangle
     * implementation (splitting every free rect the placement touches and
     * pruning contained ones) that produces tightly packed, overlap-free
     * layouts for mixed sprite sizes.
     */
    MAX_RECTS_BSSF,
}

/**
 * Rectangle bin packer for texture atlases.
 *
 * The canvas is **unbounded**: no maximum-dimension constraint is imposed
 * and packing therefore always succeeds — the canvas grows to whatever the
 * heuristic needs (guarding only the 31-bit / `IntArray` limits). Callers
 * that need a cap should pre-partition their inputs.
 *
 * ## Padding semantics
 *
 * Every input reserves a box of `(frame.width + 2 * padding) x
 * (frame.height + 2 * padding)`; its [PackedRect] is the content area
 * (frame-sized) inside that box. Two adjacent boxes leave a gap of
 * `2 * padding` between their content rects, and every content rect keeps at
 * least `padding` transparent pixels to the canvas edge.
 *
 * ## Rotation
 *
 * With `allowRotation` (honored by MAX_RECTS only — SHELF never rotates) a
 * sprite may be placed with swapped dimensions when that strictly improves
 * the BSSF score. Source pixels are then drawn rotated 90 CW into the region
 * (see [PackedRect]); the [AtlasInput.frame] object itself is never
 * transformed. Rotation is opportunistic: with this packer's generous
 * virtual bounds it fires only when a free rect genuinely fits the swapped
 * box better.
 *
 * ## Determinism
 *
 * Same inputs + parameters, byte-identical output: SHELF sorts by
 * `(height desc, name asc)`; MaxRects processes inputs in the given order,
 * scans free rects in list order, prefers the normal orientation on ties,
 * breaks score ties by (smaller short-side leftover, larger long-side
 * leftover, earlier free rect), and grows rightward at the current content
 * bound if fragmentation ever strands an item.
 */
object AtlasPacker {

    /**
     * The outcome of a successful pack.
     *
     * @property canvas the rasterized atlas: every input's pixels blitted at
     *   its region (rotated pixels for rotated regions), transparent
     *   elsewhere.
     * @property rects one [PackedRect] per placed sprite, in placement order
     *   (SHELF: sorted order; MAX_RECTS: input order).
     * @property width canvas width in pixels.
     * @property height canvas height in pixels.
     */
    data class PackResult(
        val canvas: PixelFrame,
        val rects: List<PackedRect>,
        val width: Int,
        val height: Int,
    ) {
        /** The rect packed for [name], or `null` when no such name exists. */
        fun find(name: String): PackedRect? = rects.firstOrNull { it.name == name }
    }

    /** Internal free-rectangle for the MaxRects sweep. */
    private class FreeRect(val x: Int, val y: Int, val w: Int, val h: Int)

    /** Internal placement record. */
    private class Placement(val x: Int, val y: Int, val w: Int, val h: Int, val rotated: Boolean)

    /**
     * Packs [inputs] onto one atlas canvas.
     *
     * @param inputs sprites to pack; at least one, names unique.
     * @param heuristic [PackHeuristic.SHELF] or [PackHeuristic.MAX_RECTS_BSSF]
     *   (default).
     * @param padding transparent gap around every sprite, `>= 0` (see the
     *   padding semantics in the class docs).
     * @param allowRotation permit 90-degree-rotated placements (MaxRects
     *   only); never default.
     * @param powerOfTwo round the final canvas dimensions up to the next
     *   power of two (positions are unchanged — only the canvas grows).
     * @return the packed atlas (always succeeds — the canvas is unbounded).
     * @throws IllegalArgumentException if [inputs] is empty, names repeat,
     *   [padding] is negative, or the canvas exceeds the 31-bit / `IntArray`
     *   limits.
     */
    fun pack(
        inputs: List<AtlasInput>,
        heuristic: PackHeuristic = PackHeuristic.MAX_RECTS_BSSF,
        padding: Int = 0,
        allowRotation: Boolean = false,
        powerOfTwo: Boolean = false,
    ): PackResult {
        require(inputs.isNotEmpty()) { "atlas needs at least one input" }
        require(padding >= 0) { "padding must be >= 0 (was $padding)" }
        val seen = HashSet<String>()
        for (input in inputs) {
            require(seen.add(input.name)) { "duplicate atlas input name '${input.name}'" }
        }
        return when (heuristic) {
            PackHeuristic.SHELF -> packShelf(inputs, padding, powerOfTwo)
            PackHeuristic.MAX_RECTS_BSSF -> packMaxRects(inputs, padding, allowRotation, powerOfTwo)
        }
    }

    // ------------------------------------------------------------------
    // SHELF
    // ------------------------------------------------------------------

    /**
     * Shelf packing: one row, sorted by frame height descending then name.
     * Unbounded width means the shelf never wraps — the row-break logic of
     * bounded shelf packers degenerates to a single shelf, documented.
     */
    private fun packShelf(inputs: List<AtlasInput>, padding: Int, powerOfTwo: Boolean): PackResult {
        val sorted = inputs.sortedWith(
            compareByDescending<AtlasInput> { it.frame.height }.thenBy { it.name },
        )
        var boxX = 0L
        var boxH = 0L
        val placements = HashMap<String, Placement>(inputs.size)
        val order = ArrayList<AtlasInput>(inputs.size)
        for (input in sorted) {
            val boxW = input.frame.width + 2 * padding
            val boxHeight = input.frame.height + 2 * padding
            placements[input.name] = Placement(boxX.toInt(), 0, boxW, boxHeight, false)
            order.add(input)
            boxX += boxW
            if (boxHeight > boxH) boxH = boxHeight.toLong()
        }
        require(boxX in 1..Int.MAX_VALUE.toLong() && boxH in 1..Int.MAX_VALUE.toLong()) {
            "shelf atlas ${boxX}x${boxH} exceeds the 31-bit dimension limit"
        }
        var width = boxX.toInt()
        var height = boxH.toInt()
        if (powerOfTwo) {
            width = nextPowerOfTwo(width)
            height = nextPowerOfTwo(height)
        }
        val rects = order.map { input ->
            val p = placements[input.name]!!
            PackedRect(input.name, p.x + padding, p.y + padding, input.frame.width, input.frame.height, false)
        }
        return rasterize(inputs, rects, width, height)
    }

    // ------------------------------------------------------------------
    // MaxRects BSSF
    // ------------------------------------------------------------------

    /**
     * MaxRects with Best-Short-Side-Fit. The free-rect sweep starts from a
     * virtual canvas of `(sum of box widths) x (sum of box heights)` — roomy
     * enough that every box fits — and the final canvas is trimmed to the
     * bounding box of the placements. See the class docs for the tie-breaks.
     */
    private fun packMaxRects(
        inputs: List<AtlasInput>,
        padding: Int,
        allowRotation: Boolean,
        powerOfTwo: Boolean,
    ): PackResult {
        var virtualW = 0L
        var virtualH = 0L
        for (input in inputs) {
            virtualW += input.frame.width.toLong() + 2L * padding
            virtualH += input.frame.height.toLong() + 2L * padding
        }
        require(virtualW in 1..Int.MAX_VALUE.toLong() && virtualH in 1..Int.MAX_VALUE.toLong()) {
            "atlas needs a virtual canvas of ${virtualW}x${virtualH}, beyond the 31-bit limit"
        }
        val free = ArrayList<FreeRect>()
        free.add(FreeRect(0, 0, virtualW.toInt(), virtualH.toInt()))

        val placements = arrayOfNulls<Placement>(inputs.size)
        var maxRight = 0L

        for (i in inputs.indices) {
            val input = inputs[i]
            val boxW = input.frame.width + 2 * padding
            val boxH = input.frame.height + 2 * padding
            val best = findBestPlacement(free, boxW, boxH, allowRotation)
            val x: Int
            val y: Int
            if (best.found) {
                x = best.x
                y = best.y
            } else {
                // Grow rightward: the strip beyond every placement is free
                // by construction, and the virtual sum bounds cover it.
                x = maxRight.toInt()
                y = 0
            }
            val w = if (best.rotated) boxH else boxW
            val h = if (best.rotated) boxW else boxH
            placements[i] = Placement(x, y, w, h, best.rotated)
            if (x.toLong() + w > maxRight) maxRight = x.toLong() + w
            splitFreeRects(free, x, y, w, h)
            pruneContained(free)
        }

        var boundsRight = 0L
        var boundsBottom = 0L
        for (p in placements) {
            val placement = p!!
            if (placement.x.toLong() + placement.w > boundsRight) boundsRight = placement.x.toLong() + placement.w
            if (placement.y.toLong() + placement.h > boundsBottom) boundsBottom = placement.y.toLong() + placement.h
        }
        require(boundsRight in 1..Int.MAX_VALUE.toLong() && boundsBottom in 1..Int.MAX_VALUE.toLong()) {
            "max-rects atlas ${boundsRight}x${boundsBottom} exceeds the 31-bit dimension limit"
        }
        var width = boundsRight.toInt()
        var height = boundsBottom.toInt()
        if (powerOfTwo) {
            width = nextPowerOfTwo(width)
            height = nextPowerOfTwo(height)
        }
        val rects = inputs.mapIndexed { i, input ->
            val p = placements[i]!!
            PackedRect(
                input.name,
                p.x + padding,
                p.y + padding,
                if (p.rotated) input.frame.height else input.frame.width,
                if (p.rotated) input.frame.width else input.frame.height,
                p.rotated,
            )
        }
        return rasterize(inputs, rects, width, height)
    }

    /**
     * Scans [free] for the best short-side fit of a `boxW x boxH` box (and,
     * with [allowRotation], its swapped twin). Returns
     * `(found, x, y, rotated)`; ties prefer normal orientation and earlier
     * free rects (see the class docs).
     */
    private fun findBestPlacement(
        free: List<FreeRect>,
        boxW: Int,
        boxH: Int,
        allowRotation: Boolean,
    ): Quad {
        var best = false
        var bestX = 0
        var bestY = 0
        var bestRotated = false
        var bestShort = Int.MAX_VALUE
        var bestLong = Int.MIN_VALUE
        for (rect in free) {
            // Normal orientation.
            if (boxW <= rect.w && boxH <= rect.h) {
                val leftoverW = rect.w - boxW
                val leftoverH = rect.h - boxH
                val short = minOf(leftoverW, leftoverH)
                val long = maxOf(leftoverW, leftoverH)
                if (short < bestShort || (short == bestShort && long > bestLong)) {
                    best = true
                    bestX = rect.x
                    bestY = rect.y
                    bestRotated = false
                    bestShort = short
                    bestLong = long
                }
            }
            if (allowRotation && boxH <= rect.w && boxW <= rect.h) {
                val leftoverW = rect.w - boxH
                val leftoverH = rect.h - boxW
                val short = minOf(leftoverW, leftoverH)
                val long = maxOf(leftoverW, leftoverH)
                if (short < bestShort || (short == bestShort && long > bestLong)) {
                    best = true
                    bestX = rect.x
                    bestY = rect.y
                    bestRotated = true
                    bestShort = short
                    bestLong = long
                }
            }
        }
        return Quad(best, bestX, bestY, bestRotated)
    }

    /**
     * Classic MaxRects split: every free rect that intersects the placed
     * `x, y, w, h` box is replaced by the up-to-four non-degenerate
     * remainder rects around it. Free rects may overlap each other — that is
     * the MaxRects invariant — but no free rect ever intersects a placement.
     */
    private fun splitFreeRects(free: MutableList<FreeRect>, x: Int, y: Int, w: Int, h: Int) {
        var i = 0
        while (i < free.size) {
            val f = free[i]
            val overlaps = x < f.x + f.w && x + w > f.x && y < f.y + f.h && y + h > f.y
            if (!overlaps) {
                i++
                continue
            }
            free.removeAt(i)
            val right = x + w
            val bottom = y + h
            if (x > f.x) free.add(i, FreeRect(f.x, f.y, x - f.x, f.h))
            if (right < f.x + f.w) free.add(i, FreeRect(right, f.y, f.x + f.w - right, f.h))
            if (y > f.y) free.add(i, FreeRect(f.x, f.y, f.w, y - f.y))
            if (bottom < f.y + f.h) free.add(i, FreeRect(f.x, bottom, f.w, f.y + f.h - bottom))
            // All splits were inserted at i — nothing else to skip.
        }
    }

    /** Removes free rects fully contained in another (exact duplicates keep one). */
    private fun pruneContained(free: MutableList<FreeRect>) {
        var i = 0
        outer@ while (i < free.size) {
            val a = free[i]
            for (j in free.indices) {
                if (j == i) continue
                val b = free[j]
                if (b.x <= a.x && b.y <= a.y && b.x + b.w >= a.x + a.w && b.y + b.h >= a.y + a.h) {
                    free.removeAt(i)
                    continue@outer
                }
            }
            i++
        }
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /** Blits every input's pixels at its rect; rotated ones rotate 90 CW. */
    private fun rasterize(inputs: List<AtlasInput>, rects: List<PackedRect>, width: Int, height: Int): PackResult {
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount in 1..Int.MAX_VALUE.toLong()) {
            "atlas canvas needs $pixelCount pixels, beyond the IntArray limit"
        }
        val out = IntArray(pixelCount.toInt())
        val byName = HashMap<String, AtlasInput>(inputs.size)
        for (input in inputs) byName[input.name] = input
        for (rect in rects) {
            val frame = byName[rect.name]!!.frame
            val src = if (rect.rotated) frame.rotated90Cw() else frame
            val srcW = src.width
            for (y in 0 until src.height) {
                val targetY = rect.y + y
                if (targetY < 0 || targetY >= height) continue
                for (x in 0 until srcW) {
                    val targetX = rect.x + x
                    if (targetX < 0 || targetX >= width) continue
                    out[targetY * width + targetX] = src.pixels[y * srcW + x]
                }
            }
        }
        return PackResult(PixelFrame.of(width, height, out), rects, width, height)
    }

    /** Next power of two `>= v` (1 for `v <= 1`). */
    private fun nextPowerOfTwo(v: Int): Int {
        var p = 1
        while (p < v && p < 1 shl 30) p = p shl 1
        return p
    }

    /** Tiny result holder for [findBestPlacement]. */
    private class Quad(val found: Boolean, val x: Int, val y: Int, val rotated: Boolean)
}
