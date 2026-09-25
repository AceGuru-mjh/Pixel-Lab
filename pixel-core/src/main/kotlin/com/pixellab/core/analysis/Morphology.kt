package com.pixellab.core.analysis

import com.pixellab.core.model.PixelFrame

/**
 * Shape of a morphological structuring element.
 */
enum class MorphShape {
    /** Full `n × n` square neighborhood. */
    SQUARE,

    /** Plus/cross neighborhood: center row + center column, `(2n − 1)` taps. */
    CROSS,
}

/**
 * An immutable structuring element for morphological operations, described
 * by a [shape] and an odd radius.
 *
 * Radius 0 = the identity element (operations degenerate to no-ops);
 * radius 1 = the classic 3x3 (or 5-tap cross) neighborhood.
 */
class StructElement(val shape: MorphShape, val radius: Int) {

    init {
        require(radius >= 0) { "Structuring element radius must be ≥ 0, got $radius" }
        require(radius <= 15) { "Structuring element radius must be ≤ 15, got $radius" }
    }

    /** Extent of the neighborhood in each axis (`radius` at most). */
    val extent: Int get() = radius

    /**
     * Offsets `(dx, dy)` covered by this element, relative to the center,
     * in row-major order. Flat IntArray with `[dx, dy, dx, dy, ...]` to
     * avoid per-tap object allocation.
     */
    val offsets: IntArray by lazy {
        when (shape) {
            MorphShape.SQUARE -> {
                val n = 2 * radius + 1
                IntArray(n * n * 2) { idx ->
                    val tap = idx / 2
                    val col = tap % n
                    val row = tap / n
                    if (idx % 2 == 0) col - radius else row - radius
                }
            }
            MorphShape.CROSS -> {
                val taps = 4 * radius + 1
                IntArray(taps * 2).also { arr ->
                    var k = 0
                    for (d in -radius..radius) {
                        arr[k++] = d; arr[k++] = 0
                        if (d != 0) { arr[k++] = 0; arr[k++] = d }
                    }
                }
            }
        }
    }

    /** Number of taps in the neighborhood. */
    val tapCount: Int get() = offsets.size / 2

    override fun toString(): String = "StructElement($shape, r=$radius)"

    companion object {
        /** The classic 3x3 square. */
        fun square3(): StructElement = StructElement(MorphShape.SQUARE, 1)

        /** The classic 3x3 cross (4-connected neighborhood). */
        fun cross3(): StructElement = StructElement(MorphShape.CROSS, 1)

        /** 5x5 square. */
        fun square5(): StructElement = StructElement(MorphShape.SQUARE, 2)
    }
}

/**
 * Binary morphology over sprite opacity, with color-carrying semantics.
 *
 * Pixel Lab treats morphology as an operation on the *alpha mask* of a
 * frame (a pixel is "on" when its alpha is non-zero):
 *
 *  * **dilate** — a pixel becomes on when any neighbor under the
 *    structuring element is on; its color is taken from the brightest
 *    (highest alpha) source pixel in its neighborhood, so dilation grows
 *    sprite shapes outward without inventing colors.
 *  * **erode** — a pixel stays on only when every neighbor under the
 *    element is on; it keeps its own color. Erosion peels boundary pixels.
 *  * **open** = erode → dilate: removes speckles smaller than the element
 *    while approximately preserving shape.
 *  * **close** = dilate → erode: fills holes and hairline gaps smaller
 *    than the element.
 *
 * All operations are pure functions returning new frames.
 */
object MorphologyOps {

    /**
     * Grows opaque regions outward by [element].
     *
     * A transparent pixel adjacent (per the element) to opaque material
     * becomes a copy of the opaque neighbor with the highest alpha; ties
     * break toward the first tap in element order. Opaque pixels are
     * unchanged.
     */
    fun dilate(frame: PixelFrame, element: StructElement = StructElement.square3()): PixelFrame {
        if (element.radius == 0) return frame
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = src.copyOf()
        val offs = element.offsets
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (src[y * w + x] ushr 24 != 0) continue
                var best = 0
                var bestAlpha = 0
                var k = 0
                while (k < offs.size) {
                    val nx = x + offs[k]
                    val ny = y + offs[k + 1]
                    k += 2
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val c = src[ny * w + nx]
                    val a = c ushr 24
                    if (a > bestAlpha) {
                        bestAlpha = a
                        best = c
                    }
                }
                if (bestAlpha > 0) out[y * w + x] = best
            }
        }
        return PixelFrame.of(w, h, out)
    }

    /**
     * Shrinks opaque regions inward by [element]: a pixel survives only
     * when the entire element neighborhood is opaque (out-of-bounds
     * counts as transparent, so shapes erode from the canvas edge too).
     * Surviving pixels keep their color.
     */
    fun erode(frame: PixelFrame, element: StructElement = StructElement.square3()): PixelFrame {
        if (element.radius == 0) return frame
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = IntArray(w * h)
        val offs = element.offsets
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = src[y * w + x]
                if (c ushr 24 == 0) continue
                var allOn = true
                var k = 0
                while (k < offs.size) {
                    val nx = x + offs[k]
                    val ny = y + offs[k + 1]
                    k += 2
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                        allOn = false; break
                    }
                    if (src[ny * w + nx] ushr 24 == 0) {
                        allOn = false; break
                    }
                }
                if (allOn) out[y * w + x] = c
            }
        }
        return PixelFrame.of(w, h, out)
    }

    /** Opening: [erode] then [dilate]. Removes speckle noise. */
    fun open(frame: PixelFrame, element: StructElement = StructElement.square3()): PixelFrame =
        dilate(erode(frame, element), element)

    /** Closing: [dilate] then [erode]. Fills pinholes and hairline gaps. */
    fun close(frame: PixelFrame, element: StructElement = StructElement.square3()): PixelFrame =
        erode(dilate(frame, element), element)

    /**
     * Removes "loner" opaque pixels whose 4-connected neighborhood is
     * entirely transparent — the classic single-pixel dust cleanup.
     *
     * Equivalent to [open] with a [StructElement.cross3] for isolated
     * pixels only, but O(1) per pixel without a second dilate pass, and it
     * additionally removes fully-isolated pixels on even 8-connected terms
     * when [diagonalCounts] is false (a diagonal neighbor then counts as
     * "not alone" only when it is orthogonally adjacent).
     */
    fun removeIsolatedPixels(frame: PixelFrame, diagonalCounts: Boolean = false): PixelFrame {
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = src.copyOf()
        var changed = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = src[y * w + x]
                if (c ushr 24 == 0) continue
                var neighbors = 0
                if (y > 0 && src[(y - 1) * w + x] ushr 24 != 0) neighbors++
                if (y < h - 1 && src[(y + 1) * w + x] ushr 24 != 0) neighbors++
                if (x > 0 && src[y * w + x - 1] ushr 24 != 0) neighbors++
                if (x < w - 1 && src[y * w + x + 1] ushr 24 != 0) neighbors++
                if (neighbors == 0 && !diagonalCounts) {
                    // Also check diagonals: only truly lone pixels die.
                    var diag = 0
                    if (x > 0 && y > 0 && src[(y - 1) * w + x - 1] ushr 24 != 0) diag++
                    if (x < w - 1 && y > 0 && src[(y - 1) * w + x + 1] ushr 24 != 0) diag++
                    if (x > 0 && y < h - 1 && src[(y + 1) * w + x - 1] ushr 24 != 0) diag++
                    if (x < w - 1 && y < h - 1 && src[(y + 1) * w + x + 1] ushr 24 != 0) diag++
                    if (diag == 0) {
                        out[y * w + x] = 0
                        changed = true
                    }
                } else if (neighbors == 0 && diagonalCounts) {
                    out[y * w + x] = 0
                    changed = true
                }
            }
        }
        return if (changed) PixelFrame.of(w, h, out) else frame
    }

    /**
     * One-call sprite cleanup preset tuned for hand-drawn pixel art:
     *
     * 1. remove isolated dust pixels (4-connected),
     * 2. close 3x3 to heal hairline cracks and pinholes,
     * 3. open 3x3-cross to strip 1-pixel protrusions ("spurs").
     *
     * Returns the input reference when nothing needed cleaning.
     */
    fun cleanupSprite(frame: PixelFrame): PixelFrame {
        var out = removeIsolatedPixels(frame)
        out = close(out, StructElement.square3())
        out = open(out, StructElement.cross3())
        return out
    }

    /**
     * Outline extraction via erosion: pixels that are opaque in [frame]
     * but transparent in its erosion are the boundary. The boundary keeps
     * its original color; alpha is preserved. This complements the
     * color-aware outline tools in `com.pixellab.core.tools` with a purely
     * mask-based variant.
     */
    fun outlineMask(frame: PixelFrame, element: StructElement = StructElement.square3()): PixelFrame {
        val eroded = erode(frame, element)
        val w = frame.width
        val h = frame.height
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            if (frame.pixels[i] ushr 24 != 0 && eroded.pixels[i] ushr 24 == 0) {
                out[i] = frame.pixels[i]
            }
        }
        return PixelFrame.of(w, h, out)
    }

    /**
     * Fills enclosed transparent holes with the majority color of their
     * surrounding boundary. A "hole" is a transparent 4-connected region
     * that does not touch the canvas edge. Uses one pass of flood labeling
     * over the transparent mask; each interior region adopts the most
     * frequent opaque color among its 4-neighbors.
     *
     * This is the morphological "close" for arbitrarily large holes that
     * closing cannot reach, with the color semantics pixel artists expect.
     */
    fun fillEnclosedHoles(frame: PixelFrame): PixelFrame {
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = src.copyOf()
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        // 1. Flood the outside transparency from every border cell.
        for (x in 0 until w) {
            markOutside(src, visited, queue, w, h, x, 0)
            markOutside(src, visited, queue, w, h, x, h - 1)
        }
        for (y in 0 until h) {
            markOutside(src, visited, queue, w, h, 0, y)
            markOutside(src, visited, queue, w, h, w - 1, y)
        }
        // 2. Remaining unvisited transparent cells are enclosed holes.
        for (i in 0 until w * h) {
            if (visited[i] || src[i] ushr 24 != 0) continue
            val hole = collectHole(src, visited, queue, w, h, i)
            if (hole.isEmpty()) continue
            val color = majorityNeighborColor(src, w, h, hole) ?: continue
            for (idx in hole) out[idx] = color
        }
        return PixelFrame.of(w, h, out)
    }

    private fun markOutside(src: IntArray, visited: BooleanArray, queue: IntArray, w: Int, h: Int, x0: Int, y0: Int) {
        val start = y0 * w + x0
        if (visited[start] || src[start] ushr 24 != 0) return
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            val y = idx / w
            if (x > 0 && !visited[idx - 1] && src[idx - 1] ushr 24 == 0) {
                visited[idx - 1] = true; queue[tail++] = idx - 1
            }
            if (x < w - 1 && !visited[idx + 1] && src[idx + 1] ushr 24 == 0) {
                visited[idx + 1] = true; queue[tail++] = idx + 1
            }
            if (y > 0 && !visited[idx - w] && src[idx - w] ushr 24 == 0) {
                visited[idx - w] = true; queue[tail++] = idx - w
            }
            if (y < h - 1 && !visited[idx + w] && src[idx + w] ushr 24 == 0) {
                visited[idx + w] = true; queue[tail++] = idx + w
            }
        }
    }

    private fun collectHole(src: IntArray, visited: BooleanArray, queue: IntArray, w: Int, h: Int, start: Int): IntArray {
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        val cells = ArrayList<Int>(16)
        while (head < tail) {
            val idx = queue[head++]
            cells.add(idx)
            val x = idx % w
            val y = idx / w
            if (x > 0 && !visited[idx - 1] && src[idx - 1] ushr 24 == 0) {
                visited[idx - 1] = true; queue[tail++] = idx - 1
            }
            if (x < w - 1 && !visited[idx + 1] && src[idx + 1] ushr 24 == 0) {
                visited[idx + 1] = true; queue[tail++] = idx + 1
            }
            if (y > 0 && !visited[idx - w] && src[idx - w] ushr 24 == 0) {
                visited[idx - w] = true; queue[tail++] = idx - w
            }
            if (y < h - 1 && !visited[idx + w] && src[idx + w] ushr 24 == 0) {
                visited[idx + w] = true; queue[tail++] = idx + w
            }
        }
        return cells.toIntArray()
    }

    /** Most frequent color among the 4-neighbors of [hole] cells. */
    private fun majorityNeighborColor(src: IntArray, w: Int, h: Int, hole: IntArray): Int? {
        val counts = HashMap<Int, Int>(16)
        for (idx in hole) {
            val x = idx % w
            val y = idx / w
            if (x > 0 && src[idx - 1] ushr 24 != 0) counts.merge(src[idx - 1], 1, Int::plus)
            if (x < w - 1 && src[idx + 1] ushr 24 != 0) counts.merge(src[idx + 1], 1, Int::plus)
            if (y > 0 && src[idx - w] ushr 24 != 0) counts.merge(src[idx - w], 1, Int::plus)
            if (y < h - 1 && src[idx + w] ushr 24 != 0) counts.merge(src[idx + w], 1, Int::plus)
        }
        if (counts.isEmpty()) return null
        return counts.entries.maxWith(compareBy({ it.value }, { it.key })).key
    }
}
