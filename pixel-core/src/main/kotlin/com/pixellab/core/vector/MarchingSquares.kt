package com.pixellab.core.vector

import com.pixellab.core.model.PixelFrame

/**
 * Marching-squares contour tracing over a binary pixel mask.
 *
 * The mask is sampled per pixel; contours are traced on the **corner
 * lattice** (integer points `(0..w, 0..h)`), so every vertex lands
 * exactly on pixel corners — pixel-perfect outlines with no smoothing.
 *
 * Algorithm: collect the four kinds of **directed boundary edges** (a
 * pixel inside with an outside neighbor contributes the shared lattice
 * segment, directed so that inside stays on the same hand), then link
 * edges into closed loops at their shared endpoints. At checkerboard
 * corners (degree 4 — two diagonal pixels touching) the linker turns
 * consistently, treating diagonals as **disconnected** (4-connected
 * semantics), which keeps the two loops separate and un-crossed.
 *
 * Output contract:
 *  * every contour is **closed** (first point == last point),
 *  * contours wind counter-clockwise for outer boundaries (positive
 *    shoelace area) and clockwise for holes,
 *  * [simplifyCollinear] drops points that lie mid-straight-run, leaving
 *    corner-only polylines (the default).
 */
object MarchingSquares {

    /** One traced contour: integer corner coordinates, closed ring. */
    class Contour(
        /** x coordinates of the ring; same size as [y]. */
        val x: IntArray,
        /** y coordinates of the ring. */
        val y: IntArray,
        /** True when the ring winds clockwise (a hole). */
        val isHole: Boolean,
    ) {
        /** Number of points (including the repeated closing point). */
        val size: Int get() = x.size

        override fun toString(): String =
            "Contour(points=${x.size}, hole=$isHole, start=(${x.firstOrNull()}, ${y.firstOrNull()}))"
    }

    /**
     * Traces every contour of the mask defined by [isInside] over a
     * `width x height` raster. Out-of-bounds samples are outside, so
     * sprite edges close automatically.
     */
    fun trace(
        width: Int,
        height: Int,
        isInside: (x: Int, y: Int) -> Boolean,
        simplifyCollinear: Boolean = true,
    ): List<Contour> {
        require(width > 0 && height > 0) { "Positive dimensions required" }
        val inMask = BooleanArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                inMask[y * width + x] = isInside(x, y)
            }
        }
        fun inside(x: Int, y: Int): Boolean =
            x in 0 until width && y in 0 until height && inMask[y * width + x]

        // ── 1. Collect directed boundary edges on the lattice ──────────
        // Directed so that walking keeps the inside on the LEFT in screen
        // coordinates (y grows downward): that yields counter-clockwise
        // outer rings (positive shoelace).
        // Key: (x, y) lattice point; value: list of outgoing edge keys.
        val outgoing = HashMap<Long, ArrayList<Int>>(width * height * 2)
        // Edge encoding: start point (x, y) + direction (dx:1, dy:2).
        // We store each edge as "from(x,y) → to(x,y)" pair keys.
        fun addEdge(x0: Int, y0: Int, x1: Int, y1: Int) {
            val key = (x0.toLong() shl 32) or y0.toLong()
            outgoing.getOrPut(key) { ArrayList(4) }.add(
                // encode target as int (lattice is ≤ 64k points in practice)
                (x1 shl 16) or (y1 and 0xFFFF),
            )
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!inMask[y * width + x]) continue
                // Direction convention (verified by the unit tests): the
                // outer ring of a lone pixel walks (0,0)→(1,0)→(1,1)→(0,1),
                // giving a positive shoelace area — counter-clockwise
                // outer rings and clockwise holes.
                if (!inside(x, y - 1)) addEdge(x, y, x + 1, y)
                if (!inside(x, y + 1)) addEdge(x + 1, y + 1, x, y + 1)
                if (!inside(x - 1, y)) addEdge(x, y + 1, x, y)
                if (!inside(x + 1, y)) addEdge(x + 1, y, x + 1, y + 1)
            }
        }
        if (outgoing.isEmpty()) return emptyList()

        // ── 2. Link edges into closed loops ─────────────────────────────
        // Consumed edges are removed from the map; degree-4 corners
        // (checkerboards) resolve by picking the continuation that turns
        // relative to the arrival direction — consistent turning keeps
        // diagonal loops disjoint (4-connected semantics).
        val contours = ArrayList<Contour>(8)
        while (outgoing.isNotEmpty()) {
            val startKey = outgoing.keys.first()
            val startX = (startKey shr 32).toInt()
            val startY = startKey.toInt()
            val ptsX = ArrayList<Int>(64)
            val ptsY = ArrayList<Int>(64)
            var cx = startX
            var cy = startY
            var prevDx = 0
            var prevDy = 0
            var guard = 0
            val maxSteps = 8 * (width + 1) * (height + 1) + 16
            while (true) {
                ptsX.add(cx)
                ptsY.add(cy)
                val list = outgoing[(cx.toLong() shl 32) or cy.toLong()]
                if (list == null || list.isEmpty()) break // open chain: bail
                // Pick the continuation: prefer turning right relative to
                // the arrival direction (separates checkerboard diagonals).
                var chosen = 0
                if (list.size > 1 && (prevDx != 0 || prevDy != 0)) {
                    var bestScore = -1
                    for (i in list.indices) {
                        val enc = list[i]
                        val nx = enc ushr 16
                        val ny = enc and 0xFFFF
                        val dx = nx - cx
                        val dy = ny - cy
                        // Cross product of arrival × candidate: >0 = right
                        // turn (screen coords), <0 = left, 0 = straight.
                        val cross = prevDx * dy - prevDy * dx
                        val score = if (cross > 0) 2 else if (cross == 0) 1 else 0
                        if (score > bestScore) {
                            bestScore = score
                            chosen = i
                        }
                    }
                }
                val enc = list.removeAt(chosen)
                if (list.isEmpty()) {
                    outgoing.remove((cx.toLong() shl 32) or cy.toLong())
                }
                val nx = enc ushr 16
                val ny = enc and 0xFFFF
                prevDx = nx - cx
                prevDy = ny - cy
                cx = nx
                cy = ny
                if (cx == startX && cy == startY) break // loop closed
                guard++
                if (guard > maxSteps) return emptyList() // safety valve
            }
            // Loop closed: drop the duplicated starting point (the walk
            // re-visits it, and we close during simplification).
            var ringX = ptsX
            var ringY = ptsY
            if (ringX.size >= 2 && ringX.last() == ringX.first() && ringY.last() == ringY.first()) {
                ringX = ArrayList(ringX.subList(0, ringX.size - 1))
                ringY = ArrayList(ringY.subList(0, ringY.size - 1))
            }
            if (ringX.size < 3) continue
            if (simplifyCollinear) {
                val sx = ArrayList<Int>(ringX.size / 2 + 4)
                val sy = ArrayList<Int>(ringY.size / 2 + 4)
                for (i in ringX.indices) {
                    val prev = (i - 1 + ringX.size) % ringX.size
                    val next = (i + 1) % ringX.size
                    val straight = (ringX[prev] == ringX[i] && ringX[i] == ringX[next]) ||
                        (ringY[prev] == ringY[i] && ringY[i] == ringY[next])
                    if (!straight) {
                        sx.add(ringX[i])
                        sy.add(ringY[i])
                    }
                }
                if (sx.size >= 3) {
                    ringX = sx
                    ringY = sy
                }
            }
            // Close the ring explicitly.
            ringX.add(ringX.first())
            ringY.add(ringY.first())
            val isHole = windingIsClockwise(ringX, ringY)
            contours.add(Contour(ringX.toIntArray(), ringY.toIntArray(), isHole))
        }
        return contours
    }

    /** Shoelace sign: clockwise rings have negative area (y-down). */
    private fun windingIsClockwise(xs: List<Int>, ys: List<Int>): Boolean {
        var area = 0L
        for (i in 0 until xs.size - 1) {
            area += xs[i].toLong() * ys[i + 1] - xs[i + 1].toLong() * ys[i]
        }
        return area < 0
    }

    /**
     * Traces the contours of a frame's opacity mask (non-transparent
     * pixels are inside).
     */
    fun traceOpacity(frame: PixelFrame, simplifyCollinear: Boolean = true): List<Contour> =
        trace(frame.width, frame.height, { x, y -> frame[x, y] ushr 24 != 0 }, simplifyCollinear)

    /**
     * Traces the contours of a specific color's region (per-channel
     * [tolerance] allowed).
     */
    fun traceColor(frame: PixelFrame, argb: Int, tolerance: Int = 0, simplifyCollinear: Boolean = true): List<Contour> {
        require(tolerance in 0..255) { "tolerance out of range" }
        return trace(frame.width, frame.height, { x, y ->
            val c = frame[x, y]
            if (c ushr 24 == 0) false
            else {
                val dr = kotlin.math.abs((c ushr 16 and 0xFF) - (argb ushr 16 and 0xFF))
                val dg = kotlin.math.abs((c ushr 8 and 0xFF) - (argb ushr 8 and 0xFF))
                val db = kotlin.math.abs((c and 0xFF) - (argb and 0xFF))
                maxOf(dr, dg, db) <= tolerance
            }
        }, simplifyCollinear)
    }
}
