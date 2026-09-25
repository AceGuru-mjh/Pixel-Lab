package com.pixellab.core.tools

import com.pixellab.core.model.PixelPoint

/**
 * Symmetry modes available to the drawing tools.
 *
 *  * [HORIZONTAL] mirrors across the **vertical** center axis: every point
 *    gains a copy at `x' = width - 1 - x` (left/right mirrored pair).
 *  * [VERTICAL] mirrors across the **horizontal** center axis: every point
 *    gains a copy at `y' = height - 1 - y` (top/bottom mirrored pair).
 *  * [FOUR_WAY] combines both axes: each point yields the point itself, the
 *    x-mirror, the y-mirror and the double mirror.
 *  * [TILE] is for tileable sprites: every point gains four copies translated
 *    by one canvas modulus — `(+width, 0)`, `(-width, 0)`, `(0, +height)`,
 *    `(0, -height)`. This is a **modular translation, not a mirror**: strokes
 *    that run off one tile seam reappear from the opposite side instead of
 *    being flipped. Copies that land outside the canvas (or in the negative
 *    quadrant, which [PixelPoint] forbids) are dropped; canvas writes clip
 *    the rest, so the effect is most visible for strokes crossing tile
 *    seams.
 */
enum class SymmetryMode { OFF, HORIZONTAL, VERTICAL, FOUR_WAY, TILE }

/**
 * One symmetry guide line for UI overlay rendering (the "axis" that a mirror
 * mode flips across).
 *
 *  * [Kind.AXIS_V] is a vertical line at column [position]: the seam of an
 *    x-mirror ([SymmetryMode.HORIZONTAL]). For even canvas widths the seam
 *    sits **between** columns `position - 1` and `position`; for odd widths
 *    it passes **through** column `position` (which maps onto itself).
 *  * [Kind.AXIS_H] is a horizontal line at row [position], the seam of a
 *    y-mirror ([SymmetryMode.VERTICAL]), with the same even/odd convention.
 */
data class Guide(val kind: Kind, val position: Int) {
    /** Orientation of the guide line. */
    enum class Kind { AXIS_H, AXIS_V }
}

/**
 * Reflects stroke point sets across the symmetry axes of a fixed
 * `canvasWidth x canvasHeight` grid.
 *
 * The engine is stateless and deterministic: the output always starts with
 * the input points (order preserved) followed by their symmetric copies,
 * de-duplicated while preserving first occurrence. Reflections landing in the
 * negative quadrant are dropped — [PixelPoint] coordinates must be
 * non-negative — while points past the right/bottom edges are kept and left
 * to the canvas write path to clip.
 */
class SymmetryEngine(val canvasWidth: Int, val canvasHeight: Int) {

    init {
        require(canvasWidth > 0) { "canvasWidth must be positive (was $canvasWidth)" }
        require(canvasHeight > 0) { "canvasHeight must be positive (was $canvasHeight)" }
    }

    /**
     * Expands [points] with the copies implied by [mode] (see
     * [SymmetryMode] for the exact transforms). [SymmetryMode.OFF] returns
     * the input unchanged (as a new list).
     */
    fun reflectPoints(points: List<PixelPoint>, mode: SymmetryMode): List<PixelPoint> {
        when (mode) {
            SymmetryMode.OFF -> return points.toList()
            SymmetryMode.HORIZONTAL -> {
                val unique = LinkedHashSet<PixelPoint>(points)
                for (p in points) addClipped(unique, canvasWidth - 1 - p.x, p.y)
                return unique.toList()
            }
            SymmetryMode.VERTICAL -> {
                val unique = LinkedHashSet<PixelPoint>(points)
                for (p in points) addClipped(unique, p.x, canvasHeight - 1 - p.y)
                return unique.toList()
            }
            SymmetryMode.FOUR_WAY -> {
                val unique = LinkedHashSet<PixelPoint>(points)
                for (p in points) {
                    val mx = canvasWidth - 1 - p.x
                    val my = canvasHeight - 1 - p.y
                    addClipped(unique, mx, p.y)
                    addClipped(unique, p.x, my)
                    addClipped(unique, mx, my)
                }
                return unique.toList()
            }
            SymmetryMode.TILE -> {
                val unique = LinkedHashSet<PixelPoint>(points)
                for (p in points) {
                    addClipped(unique, p.x + canvasWidth, p.y)
                    addClipped(unique, p.x - canvasWidth, p.y)
                    addClipped(unique, p.x, p.y + canvasHeight)
                    addClipped(unique, p.x, p.y - canvasHeight)
                }
                return unique.toList()
            }
        }
    }

    /**
     * Color-aware variant of [reflectPoints]: each reflected copy keeps the
     * color of the point it came from. When several points map to the same
     * grid position (the original and a mirror, or two mirrors overlapping),
     * the **first occurrence wins** deterministically — originals take
     * precedence over mirrors, and mirrors keep the order of [points].
     *
     * @throws IllegalArgumentException when [points] and [colors] differ in
     *   size.
     */
    fun reflectColorPoints(
        points: List<PixelPoint>,
        colors: List<Int>,
        mode: SymmetryMode,
    ): Pair<List<PixelPoint>, List<Int>> {
        require(points.size == colors.size) {
            "points (${points.size}) and colors (${colors.size}) sizes differ"
        }
        val mapped = LinkedHashMap<PixelPoint, Int>(points.size * 2)
        for (i in points.indices) if (!mapped.containsKey(points[i])) mapped[points[i]] = colors[i]
        when (mode) {
            SymmetryMode.OFF -> Unit
            SymmetryMode.HORIZONTAL -> {
                for (i in points.indices) {
                    val p = points[i]
                    val mirrorX = canvasWidth - 1 - p.x
                    if (mirrorX >= 0) putIfAbsent(mapped, mirrorX, p.y, colors[i])
                }
            }
            SymmetryMode.VERTICAL -> {
                for (i in points.indices) {
                    val p = points[i]
                    val mirrorY = canvasHeight - 1 - p.y
                    if (mirrorY >= 0) putIfAbsent(mapped, p.x, mirrorY, colors[i])
                }
            }
            SymmetryMode.FOUR_WAY -> {
                for (i in points.indices) {
                    val p = points[i]
                    val mx = canvasWidth - 1 - p.x
                    val my = canvasHeight - 1 - p.y
                    putIfAbsent(mapped, mx, p.y, colors[i])
                    putIfAbsent(mapped, p.x, my, colors[i])
                    putIfAbsent(mapped, mx, my, colors[i])
                }
            }
            SymmetryMode.TILE -> {
                for (i in points.indices) {
                    val p = points[i]
                    putIfAbsent(mapped, p.x + canvasWidth, p.y, colors[i])
                    putIfAbsent(mapped, p.x - canvasWidth, p.y, colors[i])
                    putIfAbsent(mapped, p.x, p.y + canvasHeight, colors[i])
                    putIfAbsent(mapped, p.x, p.y - canvasHeight, colors[i])
                }
            }
        }
        return Pair(mapped.keys.toList(), mapped.values.toList())
    }

    /**
     * The guide lines to draw for [mode] (marching-ants style axis overlay).
     * [SymmetryMode.OFF] and [SymmetryMode.TILE] have no mirror axes and
     * return an empty list; [SymmetryMode.HORIZONTAL] returns the vertical
     * seam, [SymmetryMode.VERTICAL] the horizontal seam, and
     * [SymmetryMode.FOUR_WAY] both. See [Guide] for position semantics.
     */
    fun guides(mode: SymmetryMode): List<Guide> = when (mode) {
        SymmetryMode.OFF -> emptyList()
        SymmetryMode.HORIZONTAL -> listOf(Guide(Guide.Kind.AXIS_V, canvasWidth / 2))
        SymmetryMode.VERTICAL -> listOf(Guide(Guide.Kind.AXIS_H, canvasHeight / 2))
        SymmetryMode.FOUR_WAY -> listOf(
            Guide(Guide.Kind.AXIS_V, canvasWidth / 2),
            Guide(Guide.Kind.AXIS_H, canvasHeight / 2),
        )
        SymmetryMode.TILE -> emptyList()
    }

    /** Adds (`x`, `y`) to [out] when both coordinates are non-negative. */
    private fun addClipped(out: MutableCollection<PixelPoint>, x: Int, y: Int) {
        if (x >= 0 && y >= 0) out.add(PixelPoint(x, y))
    }

    /** [reflectColorPoints] helper: first-occurrence insertion with clipping. */
    private fun putIfAbsent(
        mapped: LinkedHashMap<PixelPoint, Int>,
        x: Int,
        y: Int,
        color: Int,
    ) {
        if (x < 0 || y < 0) return
        val key = PixelPoint(x, y)
        if (!mapped.containsKey(key)) mapped[key] = color
    }
}
