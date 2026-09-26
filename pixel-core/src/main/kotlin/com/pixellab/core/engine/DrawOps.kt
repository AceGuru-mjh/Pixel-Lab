package com.pixellab.core.engine

import com.pixellab.core.model.PixelPoint
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure integer geometry for the pixel grid: Bresenham lines, rectangles,
 * midpoint circles and ellipses, and point-set outlines.
 *
 * Every function is deterministic and side-effect free. Output coordinates are
 * always non-negative: points falling into the negative quadrant are clipped
 * before construction (the [PixelPoint] constructor enforces the invariant).
 * Points past the right/bottom edges are still possible — [DrawOps] knows
 * nothing about canvas bounds; canvas writes clip them.
 *
 * Conventions: [rect] and [ellipse] address a `w x h` box whose top-left
 * corner is `(x, y)`; [circle] is center-based. The ellipse is the one
 * inscribed in its box: pixel-exact for odd `w`/`h`; for even `w`/`h` it is
 * one pixel wider/taller (the box cannot be split evenly around a center),
 * anchored at the box's top-left corner.
 */
object DrawOps {

    /**
     * Ellipse extent cap. Midpoint arithmetic squares the semi-axes twice;
     * 32768 keeps every intermediate comfortably inside Long range.
     */
    private const val MAX_ELLIPSE_EXTENT = 32768

    /**
     * Upper bound for a line's coordinate span. Bresenham walks one step per
     * unit of span; without this cap a line from -2e9 to +2e9 would iterate
     * ~4 billion times (and `abs(x1 - x0)` on Ints wraps to a bogus negative
     * span first). 262144 steps covers every sane pixel-art canvas diagonal
     * while keeping the walk constant-bounded.
     */
    private const val MAX_LINE_SPAN = 262_144

    /** Bresenham integer line from (`x0`, `y0`) to (`x1`, `y1`), all octants. */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int): List<PixelPoint> {
        // Long-domain: `abs(x1 - x0)` on Ints wraps for endpoints ~2e9 apart.
        val spanX = abs(x1.toLong() - x0.toLong())
        val spanY = abs(y1.toLong() - y0.toLong())
        require(spanX <= MAX_LINE_SPAN && spanY <= MAX_LINE_SPAN) {
            "line span (${x0},$y0)→(${x1},$y1) exceeds the $MAX_LINE_SPAN step limit"
        }
        val out = ArrayList<PixelPoint>()
        var x = x0
        var y = y0
        val dx = spanX
        val dy = -spanY
        val stepX = if (x0 < x1) 1 else -1
        val stepY = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            addClipped(out, x, y)
            if (x == x1 && y == y1) break
            val doubled = err * 2
            if (doubled >= dy) {
                err += dy
                x += stepX
            }
            if (doubled <= dx) {
                err += dx
                y += stepY
            }
        }
        return out
    }

    /**
     * Line of the given [thickness]: `thickness` parallel copies of the base
     * Bresenham line, offset perpendicular to the line direction, merged and
     * de-duplicated while preserving first-occurrence order. A zero-length
     * segment (a brush tap) stamps a centered `thickness x thickness` square.
     *
     * @throws IllegalArgumentException if [thickness] is below 1.
     */
    fun thickLine(x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int): List<PixelPoint> {
        require(thickness >= 1) { "thickness must be >= 1 (was $thickness)" }
        val deltaX = x1 - x0
        val deltaY = y1 - y0
        if (deltaX == 0 && deltaY == 0) {
            val half = (thickness - 1) / 2
            return rect(x0 - half, y0 - half, thickness, thickness, true)
        }
        val length = sqrt((deltaX.toLong() * deltaX + deltaY.toLong() * deltaY).toDouble())
        val half = (thickness - 1) / 2
        val unique = LinkedHashSet<PixelPoint>()
        for (i in 0 until thickness) {
            val offset = (i - half).toDouble()
            val offsetX = (offset * -deltaY / length).roundToInt()
            val offsetY = (offset * deltaX / length).roundToInt()
            unique.addAll(line(x0 + offsetX, y0 + offsetY, x1 + offsetX, y1 + offsetY))
        }
        return unique.toList()
    }

    /**
     * Axis-aligned rectangle with top-left corner (`x`, `y`) and size
     * `w x h`. Filled rectangles emit rows top-to-bottom, each left-to-right
     * (row-major). Outlines walk the four edges clockwise: top left-to-right,
     * right top-to-bottom, bottom right-to-left, left bottom-to-top, visiting
     * every corner exactly once. Degenerate 1-wide/1-tall boxes emit the
     * single column/row.
     *
     * @throws IllegalArgumentException if [w] or [h] is below 1.
     */
    fun rect(x: Int, y: Int, w: Int, h: Int, filled: Boolean): List<PixelPoint> {
        require(w >= 1) { "rect width must be >= 1 (was $w)" }
        require(h >= 1) { "rect height must be >= 1 (was $h)" }
        val out = ArrayList<PixelPoint>()
        if (filled) {
            val firstColumn = maxOf(x, 0)
            val firstRow = maxOf(y, 0)
            for (row in firstRow until y + h) {
                for (column in firstColumn until x + w) {
                    out.add(PixelPoint(column, row))
                }
            }
            return out
        }
        if (w == 1 && h == 1) {
            addClipped(out, x, y)
            return out
        }
        if (w == 1) {
            for (row in y until y + h) addClipped(out, x, row)
            return out
        }
        if (h == 1) {
            for (column in x until x + w) addClipped(out, column, y)
            return out
        }
        for (column in x until x + w) addClipped(out, column, y)
        for (row in y + 1 until y + h) addClipped(out, x + w - 1, row)
        for (column in x + w - 2 downTo x) addClipped(out, column, y + h - 1)
        for (row in y + h - 2 downTo y + 1) addClipped(out, x, row)
        return out
    }

    /**
     * Midpoint circle around center (`cx`, `cy`) with radius [r]
     * (`r == 0` is the center point). The outline uses 8-way symmetry over
     * the first-octant decision sequence. Filled circles derive one
     * horizontal span per boundary row (`min x .. max x`); since a circle is
     * horizontally convex, the result has no holes for any `r >= 1`.
     *
     * @throws IllegalArgumentException if [r] is negative.
     */
    fun circle(cx: Int, cy: Int, r: Int, filled: Boolean): List<PixelPoint> {
        require(r >= 0) { "circle radius must be >= 0 (was $r)" }
        val boundary = LinkedHashSet<PixelPoint>()
        var octantX = 0
        var octantY = r
        var decision = 1 - r
        while (octantX <= octantY) {
            addOctantPoints(boundary, cx, cy, octantX, octantY)
            if (decision < 0) {
                decision += 2 * octantX + 3
            } else {
                decision += 2 * (octantX - octantY) + 5
                octantY--
            }
            octantX++
        }
        if (filled) return spanFill(boundary)
        return boundary.toList()
    }

    /**
     * Midpoint ellipse inscribed in the `w x h` box at (`x`, `y`). The center
     * is `(x + w / 2, y + h / 2)` and the semi-axes are `w / 2`, `h / 2`, so
     * the rasterized shape always touches the box's top-left corner and is
     * pixel-exact for odd extents. Degenerate 1-wide/1-tall boxes collapse to
     * a vertical/horizontal segment covering the box. Filled ellipses use the
     * same per-row span derivation as [circle] (no holes).
     *
     * @throws IllegalArgumentException if [w] or [h] is outside `[1, 32768]`.
     */
    fun ellipse(x: Int, y: Int, w: Int, h: Int, filled: Boolean): List<PixelPoint> {
        require(w in 1..MAX_ELLIPSE_EXTENT) { "ellipse width must be in [1, $MAX_ELLIPSE_EXTENT] (was $w)" }
        require(h in 1..MAX_ELLIPSE_EXTENT) { "ellipse height must be in [1, $MAX_ELLIPSE_EXTENT] (was $h)" }
        if (w == 1) {
            val out = ArrayList<PixelPoint>(h)
            for (row in y until y + h) addClipped(out, x, row)
            return out
        }
        if (h == 1) {
            val out = ArrayList<PixelPoint>(w)
            for (column in x until x + w) addClipped(out, column, y)
            return out
        }
        val cx = x + w / 2
        val cy = y + h / 2
        val rx = w / 2
        val ry = h / 2
        val rx2 = rx.toLong() * rx
        val ry2 = ry.toLong() * ry
        val boundary = LinkedHashSet<PixelPoint>()
        var px = 0L
        var py = ry.toLong()
        // Region 1 (|slope| < 1): east/southeast steps from the top vertex.
        var decision = 4 * ry2 - 4 * rx2 * ry + rx2
        var stepX = 2 * ry2 * px
        var stepY = 2 * rx2 * py
        while (stepX < stepY) {
            addQuadrantPoints(boundary, cx, cy, px.toInt(), py.toInt())
            px++
            stepX += 2 * ry2
            if (decision < 0) {
                decision += 4 * (stepX + ry2)
            } else {
                py--
                stepY -= 2 * rx2
                decision += 4 * (stepX + ry2 - stepY)
            }
        }
        // Region 2 (|slope| >= 1): south/southeast steps to the right vertex.
        decision = ry2 * (2 * px + 1) * (2 * px + 1) +
            4 * rx2 * (py - 1) * (py - 1) - 4 * rx2 * ry2
        while (py > 0) {
            addQuadrantPoints(boundary, cx, cy, px.toInt(), py.toInt())
            if (decision > 0) {
                py--
                stepY -= 2 * rx2
                decision += 4 * rx2 - 4 * stepY
            } else {
                px++
                stepX += 2 * ry2
                py--
                stepY -= 2 * rx2
                decision += 4 * stepX + 4 * rx2 - 4 * stepY
            }
        }
        addQuadrantPoints(boundary, cx, cy, px.toInt(), 0)
        if (filled) return spanFill(boundary)
        return boundary.toList()
    }

    /**
     * 4-neighbor outline of a point set: every grid point that is **not** in
     * [points] but shares an edge with at least one member. The input is
     * scanned in `(y, x)` order and neighbors probed up/down/left/right, so
     * the result is deterministic regardless of the set implementation.
     * Points in the negative quadrant (e.g. left of a shape at column 0) are
     * clipped away.
     */
    fun outline(points: Set<PixelPoint>): List<PixelPoint> {
        if (points.isEmpty()) return emptyList()
        val ordered = points.sortedWith(compareBy({ it.y }, { it.x }))
        val ring = LinkedHashSet<PixelPoint>()
        for (point in ordered) {
            addRingPoint(ring, points, point.x, point.y - 1)
            addRingPoint(ring, points, point.x, point.y + 1)
            addRingPoint(ring, points, point.x - 1, point.y)
            addRingPoint(ring, points, point.x + 1, point.y)
        }
        return ring.toList()
    }

    // ---- internals ----------------------------------------------------------

    /** Adds (`x`, `y`) to [out] when both coordinates are non-negative. */
    private fun addClipped(out: MutableCollection<PixelPoint>, x: Int, y: Int) {
        if (x >= 0 && y >= 0) out.add(PixelPoint(x, y))
    }

    /** Expands one first-octant offset into 8 symmetric circle points. */
    private fun addOctantPoints(out: MutableCollection<PixelPoint>, cx: Int, cy: Int, dx: Int, dy: Int) {
        addClipped(out, cx + dx, cy + dy)
        addClipped(out, cx - dx, cy + dy)
        addClipped(out, cx + dx, cy - dy)
        addClipped(out, cx - dx, cy - dy)
        addClipped(out, cx + dy, cy + dx)
        addClipped(out, cx - dy, cy + dx)
        addClipped(out, cx + dy, cy - dx)
        addClipped(out, cx - dy, cy - dx)
    }

    /** Expands one first-quadrant offset into 4 symmetric ellipse points. */
    private fun addQuadrantPoints(out: MutableCollection<PixelPoint>, cx: Int, cy: Int, dx: Int, dy: Int) {
        addClipped(out, cx + dx, cy + dy)
        addClipped(out, cx - dx, cy + dy)
        addClipped(out, cx + dx, cy - dy)
        addClipped(out, cx - dx, cy - dy)
    }

    /** Adds (`x`, `y`) to [ring] when it is non-negative and outside [source]. */
    private fun addRingPoint(ring: MutableSet<PixelPoint>, source: Set<PixelPoint>, x: Int, y: Int) {
        if (x < 0 || y < 0) return
        val candidate = PixelPoint(x, y)
        if (candidate !in source) ring.add(candidate)
    }

    /**
     * Derives one horizontal span per boundary row (`min x .. max x`), rows
     * top-to-bottom, each left-to-right. Circle and ellipse boundaries are
     * horizontally convex and cover every row of the shape, so this fills
     * without holes.
     */
    private fun spanFill(boundary: Collection<PixelPoint>): List<PixelPoint> {
        if (boundary.isEmpty()) return emptyList()
        val rows = TreeMap<Int, IntArray>()
        for (point in boundary) {
            val extent = rows[point.y]
            if (extent == null) {
                rows[point.y] = intArrayOf(point.x, point.x)
            } else {
                if (point.x < extent[0]) extent[0] = point.x
                if (point.x > extent[1]) extent[1] = point.x
            }
        }
        val out = ArrayList<PixelPoint>()
        for ((row, extent) in rows) {
            for (column in extent[0]..extent[1]) out.add(PixelPoint(column, row))
        }
        return out
    }
}
