package com.pixellab.core.tools

import com.pixellab.core.engine.DrawOps
import com.pixellab.core.model.PixelPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Advanced shape rasterizers beyond the basic [DrawOps] geometry: regular
 * polygons, stars, rounded rectangles, Bezier curves, arcs, scanline polygon
 * filling and thick polylines.
 *
 * Conventions shared by everything here:
 *
 *  * All functions are deterministic and side-effect free; output order is
 *    stable (typically walk order of the shape, first occurrence kept on
 *    de-duplication).
 *  * Angles are measured in degrees from the positive x-axis, increasing
 *    toward `+y` (screen-down, i.e. clockwise on screen).
 *  * Output points in the negative quadrant are dropped ([PixelPoint]
 *    invariant); points past the right/bottom edges are kept and clipped
 *    later by canvas writes, exactly like [DrawOps].
 *  * Sampled curves (Bezier, arcs) connect consecutive samples with
 *    Bresenham lines so no gaps appear at larger radii.
 */
object GeometryShapes {

    /** Hard cap on arc sampling steps, bounding memory for huge radii. */
    private const val MAX_ARC_STEPS = 100_000

    /**
     * Regular polygon outline: [sides] vertices on the circle of [radius]
     * around (`cx`, `cy`), the first vertex at [rotationDeg] and the rest
     * stepping by `360 / sides` degrees. Consecutive vertices (including the
     * closing edge) are connected with Bresenham lines; shared corner pixels
     * appear once.
     *
     * @throws IllegalArgumentException when [sides] is below 3 or [radius] is
     *   negative.
     */
    fun polygon(cx: Int, cy: Int, radius: Int, sides: Int, rotationDeg: Double = 0.0): List<PixelPoint> {
        require(sides >= 3) { "polygon needs at least 3 sides (was $sides)" }
        require(radius >= 0) { "polygon radius must be >= 0 (was $radius)" }
        val xs = IntArray(sides)
        val ys = IntArray(sides)
        for (i in 0 until sides) {
            val angle = Math.toRadians(rotationDeg + i * 360.0 / sides)
            xs[i] = (cx + radius * cos(angle)).roundToInt()
            ys[i] = (cy + radius * sin(angle)).roundToInt()
        }
        val out = LinkedHashSet<PixelPoint>()
        for (i in 0 until sides) {
            val j = (i + 1) % sides
            out.addAll(DrawOps.line(xs[i], ys[i], xs[j], ys[j]))
        }
        return out.toList()
    }

    /**
     * Star outline with [points] tips: [points] outer tips on the circle of
     * [outerRadius] alternate with [points] inner notches on the circle of
     * [innerRadius], phase-stepped by `180 / points` degrees (first tip at
     * angle 0 unless rotated via the caller's own transform). Consecutive
     * vertices are connected with Bresenham lines. An [innerRadius] of 0
     * degenerates to spokes meeting at the center; `inner >= outer` simply
     * inverts the star (notches outside), which is allowed.
     *
     * @throws IllegalArgumentException when [points] is below 2 or either
     *   radius is negative.
     */
    fun star(cx: Int, cy: Int, outerRadius: Int, innerRadius: Int, points: Int = 5): List<PixelPoint> {
        require(points >= 2) { "star needs at least 2 points (was $points)" }
        require(outerRadius >= 0) { "star outerRadius must be >= 0 (was $outerRadius)" }
        require(innerRadius >= 0) { "star innerRadius must be >= 0 (was $innerRadius)" }
        val count = points * 2
        val xs = IntArray(count)
        val ys = IntArray(count)
        for (i in 0 until count) {
            val radius = if (i % 2 == 0) outerRadius else innerRadius
            val angle = i * PI / points
            xs[i] = (cx + radius * cos(angle)).roundToInt()
            ys[i] = (cy + radius * sin(angle)).roundToInt()
        }
        val out = LinkedHashSet<PixelPoint>()
        for (i in 0 until count) {
            val j = (i + 1) % count
            out.addAll(DrawOps.line(xs[i], ys[i], xs[j], ys[j]))
        }
        return out.toList()
    }

    /**
     * Rounded rectangle boundary (outline only, not filled) with top-left
     * corner (`x`, `y`), size `w x h` and [cornerRadius]-pixel quarter-circle
     * corners (radius 0 degenerates to a plain [DrawOps.rect] outline).
     * The result walks the boundary clockwise: top edge left-to-right,
     * top-right corner arc, right edge top-to-bottom, bottom-right arc,
     * bottom edge right-to-left, bottom-left arc, left edge bottom-to-top,
     * top-left arc — matching [DrawOps.rect]'s outline order, with arc points
     * sorted along the walk.
     *
     * @throws IllegalArgumentException when `w` or `h` is below 1,
     *   [cornerRadius] is negative or exceeds `min(w, h) / 2`.
     */
    fun roundedRect(x: Int, y: Int, w: Int, h: Int, cornerRadius: Int): List<PixelPoint> {
        require(w >= 1) { "roundedRect width must be >= 1 (was $w)" }
        require(h >= 1) { "roundedRect height must be >= 1 (was $h)" }
        require(cornerRadius >= 0) { "cornerRadius must be >= 0 (was $cornerRadius)" }
        val maxRadius = minOf(w, h) / 2
        require(cornerRadius <= maxRadius) {
            "cornerRadius $cornerRadius exceeds the maximum $maxRadius for ${w}x$h}"
        }
        if (cornerRadius == 0) return DrawOps.rect(x, y, w, h, false)
        val r = cornerRadius
        val out = LinkedHashSet<PixelPoint>()
        // Top edge (left to right).
        for (column in x + r until x + w - r) addClipped(out, column, y)
        // Top-right corner arc: from the top point to the right point.
        addQuarterArc(out, x + w - 1 - r, y + r, r, dxSign = 1, dySign = -1, horizontalGrows = true)
        // Right edge (top to bottom).
        for (row in y + r + 1 until y + h - 1 - r) addClipped(out, x + w - 1, row)
        // Bottom-right corner arc: from the right point to the bottom point.
        addQuarterArc(out, x + w - 1 - r, y + h - 1 - r, r, dxSign = 1, dySign = 1, horizontalGrows = false)
        // Bottom edge (right to left).
        for (column in x + w - 2 - r downTo x + r) addClipped(out, column, y + h - 1)
        // Bottom-left corner arc: from the bottom point to the left point.
        addQuarterArc(out, x + r, y + h - 1 - r, r, dxSign = -1, dySign = 1, horizontalGrows = false)
        // Left edge (bottom to top).
        for (row in y + h - 2 - r downTo y + r + 1) addClipped(out, x, row)
        // Top-left corner arc: from the left point to the top point.
        addQuarterArc(out, x + r, y + r, r, dxSign = -1, dySign = -1, horizontalGrows = true)
        return out.toList()
    }

    /**
     * Quadratic Bezier curve from [p0] to [p2] with control point [p1],
     * sampled at [steps] uniform `t` intervals (t = 0..1, endpoints
     * included), rounded to the nearest pixel and de-duplicated preserving
     * first occurrence.
     *
     * @throws IllegalArgumentException when [steps] is below 1.
     */
    fun quadraticBezier(p0: PixelPoint, p1: PixelPoint, p2: PixelPoint, steps: Int = 32): List<PixelPoint> {
        require(steps >= 1) { "steps must be >= 1 (was $steps)" }
        val out = LinkedHashSet<PixelPoint>()
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val u = 1.0 - t
            val px = u * u * p0.x + 2.0 * u * t * p1.x + t * t * p2.x
            val py = u * u * p0.y + 2.0 * u * t * p1.y + t * t * p2.y
            addClipped(out, px.roundToInt(), py.roundToInt())
        }
        return out.toList()
    }

    /**
     * Cubic Bezier curve from [p0] to [p3] with control points [p1] and
     * [p2], sampled at [steps] uniform `t` intervals, rounded and
     * de-duplicated preserving first occurrence.
     *
     * @throws IllegalArgumentException when [steps] is below 1.
     */
    fun cubicBezier(
        p0: PixelPoint,
        p1: PixelPoint,
        p2: PixelPoint,
        p3: PixelPoint,
        steps: Int = 48,
    ): List<PixelPoint> {
        require(steps >= 1) { "steps must be >= 1 (was $steps)" }
        val out = LinkedHashSet<PixelPoint>()
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val u = 1.0 - t
            val b0 = u * u * u
            val b1 = 3.0 * u * u * t
            val b2 = 3.0 * u * t * t
            val b3 = t * t * t
            val px = b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x
            val py = b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y
            addClipped(out, px.roundToInt(), py.roundToInt())
        }
        return out.toList()
    }

    /**
     * Circular arc around (`cx`, `cy`) with [radius], starting at
     * [startDeg] and sweeping [sweepDeg] degrees (positive sweeps clockwise
     * on screen, i.e. toward `+y`; negative sweeps counter-clockwise; a sweep
     * of exactly 0 yields an empty list). Consecutive samples are connected
     * with Bresenham lines; the sampling density is chosen so chord length
     * stays around one pixel (capped at [MAX_ARC_STEPS] total samples for
     * extreme radii, which may leave gaps — pixel-art radii are unaffected).
     *
     * @throws IllegalArgumentException when [radius] is negative.
     */
    fun arc(cx: Int, cy: Int, radius: Int, startDeg: Double, sweepDeg: Double): List<PixelPoint> {
        require(radius >= 0) { "arc radius must be >= 0 (was $radius)" }
        if (sweepDeg == 0.0) return emptyList()
        val degreesPerStep = minOf(4.0, 57.29577951308232 / maxOf(radius, 1))
        val steps = ceil(abs(sweepDeg) / degreesPerStep).toInt()
            .coerceAtLeast(1)
            .coerceAtMost(MAX_ARC_STEPS)
        val out = LinkedHashSet<PixelPoint>()
        var previousX = 0
        var previousY = 0
        for (i in 0..steps) {
            val angle = Math.toRadians(startDeg + sweepDeg * i / steps)
            val sx = (cx + radius * cos(angle)).roundToInt()
            val sy = (cy + radius * sin(angle)).roundToInt()
            out.addAll(if (i == 0) DrawOps.line(sx, sy, sx, sy) else DrawOps.line(previousX, previousY, sx, sy))
            previousX = sx
            previousY = sy
        }
        return out.toList()
    }

    /**
     * Fills the closed polygon given by [vertices] (any vertex order; the
     * chain closes from the last vertex back to the first) inside the
     * `width x height` clip rectangle, using the classic **scanline
     * even-odd fill with an edge table and an active edge table**:
     *
     *  1. Edges are built from consecutive vertex pairs (horizontal edges are
     *     dropped — they can never cross a scanline at pixel-center height).
     *     Each edge records its `ymin`, `ymax`, the x at `ymin` and the
     *     per-row slope `dx / dy`, forming the edge table sorted by `ymin`.
     *  2. For every canvas row the active edge table is refreshed: edges
     *     whose span has ended are dropped, edges starting at or above the
     *     row are admitted with their current x, and the table is sorted by
     *     x.
     *  3. Intersections are paired left-to-right and each pair `[xL, xR)`
     *     fills the pixel columns whose **centers** (`c + 0.5`) fall inside —
     *     `c` from `ceil(xL - 0.5)` to `ceil(xR - 0.5) - 1` — the same
     *     half-open convention as [DrawOps.rect] boxes, so a polygon with
     *     vertices `(0,0)-(4,0)-(4,4)-(0,4)` fills exactly the 4x4 block.
     *  4. Scanlines are evaluated at pixel-center height (`row + 0.5`), so
     *     vertices are never hit exactly and crossings are guaranteed to pair
     *     up; a defensive trailing unpaired edge (impossible for closed
     *     non-degenerate input) is ignored.
     *
     * Output rows are emitted top-to-bottom, columns left-to-right.
     * Self-intersecting polygons follow the even-odd rule (crossings toggle).
     *
     * @throws IllegalArgumentException when [vertices] has fewer than 3
     *   points or `width`/`height` is not positive.
     */
    fun fillPolygon(vertices: List<PixelPoint>, width: Int, height: Int): List<PixelPoint> {
        require(vertices.size >= 3) { "fillPolygon needs at least 3 vertices (was ${vertices.size})" }
        require(width > 0) { "width must be positive (was $width)" }
        require(height > 0) { "height must be positive (was $height)" }

        val edgeTable = ArrayList<Edge>(vertices.size)
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            if (a.y == b.y) continue
            val top = if (a.y < b.y) a else b
            val bottom = if (a.y < b.y) b else a
            edgeTable.add(
                Edge(
                    ymin = top.y,
                    ymax = bottom.y,
                    xAtYmin = top.x.toDouble(),
                    slopePerRow = (bottom.x - top.x).toDouble() / (bottom.y - top.y),
                ),
            )
        }
        if (edgeTable.isEmpty()) return emptyList()
        edgeTable.sortBy { it.ymin }

        val out = ArrayList<PixelPoint>()
        val active = ArrayList<ActiveEdge>()
        var pointer = 0
        for (row in 0 until height) {
            for (i in active.indices.reversed()) {
                if (active[i].ymax <= row) active.removeAt(i)
            }
            while (pointer < edgeTable.size && edgeTable[pointer].ymin <= row) {
                val edge = edgeTable[pointer++]
                active.add(
                    ActiveEdge(
                        x = edge.xAtYmin + (row + 0.5 - edge.ymin) * edge.slopePerRow,
                        slopePerRow = edge.slopePerRow,
                        ymax = edge.ymax,
                    ),
                )
            }
            if (active.size >= 2) {
                active.sortBy { it.x }
                var k = 0
                while (k + 1 < active.size) {
                    val xLeft = active[k].x
                    val xRight = active[k + 1].x
                    val firstColumn = ceil(xLeft - 0.5).toInt()
                    val lastColumn = ceil(xRight - 0.5).toInt() - 1
                    var column = maxOf(0, firstColumn)
                    val columnLimit = minOf(width - 1, lastColumn)
                    while (column <= columnLimit) {
                        out.add(PixelPoint(column, row))
                        column++
                    }
                    k += 2
                }
            }
            for (edge in active) edge.x += edge.slopePerRow
        }
        return out
    }

    /**
     * Thick polyline: consecutive [points] are connected with
     * [DrawOps.thickLine] segments of [thickness]; a single-point input
     * stamps the centered `thickness x thickness` dot (same tap semantics as
     * [DrawOps.thickLine]); an empty input yields an empty list. All
     * segments are merged into one de-duplicated list preserving first
     * occurrence.
     *
     * @throws IllegalArgumentException when [thickness] is below 1.
     */
    fun thickPolyline(points: List<PixelPoint>, thickness: Int): List<PixelPoint> {
        require(thickness >= 1) { "thickness must be >= 1 (was $thickness)" }
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) {
            val only = points[0]
            return DrawOps.thickLine(only.x, only.y, only.x, only.y, thickness)
        }
        val unique = LinkedHashSet<PixelPoint>()
        for (i in 0 until points.size - 1) {
            unique.addAll(DrawOps.thickLine(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y, thickness))
        }
        return unique.toList()
    }

    // ---- internals ----------------------------------------------------------

    /** One polygon edge in the edge table (immutable scanline parameters). */
    private class Edge(val ymin: Int, val ymax: Int, val xAtYmin: Double, val slopePerRow: Double)

    /** One edge while it lives in the active edge table (x advances per row). */
    private class ActiveEdge(var x: Double, val slopePerRow: Double, val ymax: Int)

    /**
     * Emits one quarter-circle arc of [radius] around (`cx`, `cy`), filtered
     * to the quadrant selected by [dxSign]/[dySign] (a point is kept when
     * `(dx * dxSign) >= 0 && (dy * dySign) >= 0`) and sorted along the walk
     * (by `dx` ascending when [horizontalGrows] is true, descending
     * otherwise — the quarter arc is a monotone graph over `dx`, so this is
     * a valid along-arc order).
     */
    private fun addQuarterArc(
        out: LinkedHashSet<PixelPoint>,
        cx: Int,
        cy: Int,
        radius: Int,
        dxSign: Int,
        dySign: Int,
        horizontalGrows: Boolean,
    ) {
        val selected = DrawOps.circle(cx, cy, radius, false).filter { point ->
            val dx = (point.x - cx) * dxSign
            val dy = (point.y - cy) * dySign
            dx >= 0 && dy >= 0
        }
        val ordered = if (horizontalGrows) {
            selected.sortedBy { it.x }
        } else {
            selected.sortedByDescending { it.x }
        }
        for (point in ordered) out.add(point)
    }

    /** Adds (`x`, `y`) to [out] when both coordinates are non-negative. */
    private fun addClipped(out: MutableCollection<PixelPoint>, x: Int, y: Int) {
        if (x >= 0 && y >= 0) out.add(PixelPoint(x, y))
    }
}
