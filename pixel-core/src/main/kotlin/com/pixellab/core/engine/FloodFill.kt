package com.pixellab.core.engine

import kotlin.math.abs

/**
 * Scanline flood fill on flat row-major ARGB buffers.
 *
 * The fill walks maximal horizontal runs: from a seed it expands to the left
 * end of its run, then scans rightwards, filling the run and sowing the first
 * pixel of every open run on the two adjacent rows. Visited pixels are tracked
 * in a packed [LongArray] bit set, which keeps the tolerant path terminating
 * even when the replacement color still matches the target region.
 */
object FloodFill {

    /**
     * Flood-fills the 4-connected region containing (`x`, `y`) with
     * [replacement].
     *
     * With `tolerance == 0` a pixel belongs to the region when it equals the
     * seed pixel exactly. Otherwise a pixel belongs when every A/R/G/B
     * channel differs from the seed pixel by at most [tolerance] (alpha
     * included, i.e. the maximum per-channel difference is the predicate).
     *
     * @param frameWidth buffer width in pixels; must be positive.
     * @param frameHeight buffer height in pixels; must be positive.
     * @param pixels row-major ARGB buffer of size `frameWidth * frameHeight`;
     *   never mutated by this call.
     * @param x seed column.
     * @param y seed row.
     * @param replacement fill color.
     * @param tolerance maximum per-channel difference, `>= 0`.
     * @return a new buffer with the region filled, or the very same [pixels]
     *   instance when the seed is out of bounds or nothing would change.
     * @throws IllegalArgumentException when dimensions, buffer size or
     *   tolerance are invalid.
     */
    fun flood(
        frameWidth: Int,
        frameHeight: Int,
        pixels: IntArray,
        x: Int,
        y: Int,
        replacement: Int,
        tolerance: Int = 0,
    ): IntArray {
        require(frameWidth > 0) { "frameWidth must be positive (was $frameWidth)" }
        require(frameHeight > 0) { "frameHeight must be positive (was $frameHeight)" }
        // Long-domain guard: Int multiplication wraps on huge dims.
        require(pixels.size.toLong() == frameWidth.toLong() * frameHeight.toLong()) {
            "pixels size ${pixels.size} does not match ${frameWidth}x${frameHeight}"
        }
        require(tolerance >= 0) { "tolerance must be >= 0 (was $tolerance)" }
        if (x < 0 || y < 0 || x >= frameWidth || y >= frameHeight) return pixels
        val target = pixels[y * frameWidth + x]
        if (tolerance == 0 && target == replacement) return pixels

        val out = pixels.copyOf()
        val marks = LongArray((pixels.size + 63) ushr 6)
        val matches: (Int) -> Boolean = if (tolerance == 0) {
            { value -> value == target }
        } else {
            { value -> withinTolerance(value, target, tolerance) }
        }

        fun visited(index: Int): Boolean =
            (marks[index ushr 6] and (1L shl (index and 63))) != 0L

        fun visit(index: Int) {
            marks[index ushr 6] = marks[index ushr 6] or (1L shl (index and 63))
        }

        fun open(index: Int): Boolean = !visited(index) && matches(out[index])

        var changed = false
        val stack = IntStack(frameWidth.coerceAtLeast(16))
        stack.push(y * frameWidth + x)
        while (stack.size > 0) {
            val seed = stack.pop()
            if (!open(seed)) continue
            val row = seed / frameWidth
            val rowStart = row * frameWidth
            var left = seed - rowStart
            while (left > 0 && open(rowStart + left - 1)) left--
            var column = left
            while (column < frameWidth && open(rowStart + column)) {
                val index = rowStart + column
                out[index] = replacement
                if (pixels[index] != replacement) changed = true
                visit(index)
                if (row > 0) {
                    val above = index - frameWidth
                    if (open(above) && (column == left || !open(above - 1))) stack.push(above)
                }
                if (row < frameHeight - 1) {
                    val below = index + frameWidth
                    if (open(below) && (column == left || !open(below - 1))) stack.push(below)
                }
                column++
            }
        }
        return if (changed) out else pixels
    }

    /**
     * True when [value] differs from [target] by at most [tolerance] on every
     * A/R/G/B channel (alpha included). Tolerance 0 degenerates to exact
     * equality.
     */
    internal fun withinTolerance(value: Int, target: Int, tolerance: Int): Boolean {
        if (abs((value ushr 24) - (target ushr 24)) > tolerance) return false
        if (abs(((value shr 16) and 0xFF) - ((target shr 16) and 0xFF)) > tolerance) return false
        if (abs(((value shr 8) and 0xFF) - ((target shr 8) and 0xFF)) > tolerance) return false
        return abs((value and 0xFF) - (target and 0xFF)) <= tolerance
    }
}

/** Growable LIFO int stack; avoids boxing on the flood-fill worklist. */
private class IntStack(initialCapacity: Int) {
    private var data = IntArray(initialCapacity)
    private var top = 0

    /** Number of values on the stack. */
    val size: Int get() = top

    /** Pushes [value]; grows the backing array when full. */
    fun push(value: Int) {
        if (top == data.size) data = data.copyOf(data.size * 2)
        data[top++] = value
    }

    /** Pops the most recently pushed value; only valid when [size] > 0. */
    fun pop(): Int {
        top--
        return data[top]
    }
}
