package com.pixellab.core.tools

import com.pixellab.core.engine.DrawOps
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.palette.LabColor
import java.util.Random

/**
 * Shape of a brush stamp. Every shape covers an `size x size` box anchored so
 * that the stamp center (`x`, `y`) sits at column `x - (size - 1) / 2` of the
 * box (integer division, matching [DrawOps.thickLine]'s zero-length tap):
 * odd sizes are perfectly centered, even sizes extend one pixel to the
 * bottom-right of the center.
 *
 *  * [SQUARE]: the full box.
 *  * [CIRCLE]: a solid disk of diameter `size` — a pixel is part of the stamp
 *    when `4 * (dx^2 + dy^2) <= size^2` (pixel centers within radius
 *    `size / 2`; pure integer test, e.g. size 5 cuts the box corners).
 *  * [CROSS]: a plus mask — the box row and column passing through the stamp
 *    center (the middle row/column for odd sizes).
 *  * [DIAGONAL]: an X mask — the box points with `|dx| == |dy|` around the
 *    stamp center.
 *  * [NOISE]: the box sampled with [BrushSpec.noiseDensity] probability per
 *    pixel from the engine's deterministic RNG.
 */
enum class BrushShape { SQUARE, CIRCLE, CROSS, DIAGONAL, NOISE }

/**
 * Immutable description of one brush. Validated at construction so every
 * consumer ([BrushEngine] included) can rely on the invariants.
 *
 * @throws IllegalArgumentException when [size] is outside `1..32` or
 *   [noiseDensity] is outside `[0, 1]` or NaN.
 */
data class BrushSpec(
    val shape: BrushShape = BrushShape.SQUARE,
    val size: Int = 1,
    val noiseDensity: Double = 0.5,
) {
    init {
        require(size in 1..32) { "Brush size must be in [1, 32] (was $size)" }
        require(!noiseDensity.isNaN() && noiseDensity in 0.0..1.0) {
            "noiseDensity must be in [0, 1] (was $noiseDensity)"
        }
    }
}

/** Direction of perceptual lightness shading, see [BrushEngine.shadingColor]. */
enum class ShadingDirection { LIGHTEN, DARKEN }

/**
 * Deterministic brush rasterizer: stamps, pixel-perfect strokes and
 * Lab-based shading colors.
 *
 * Randomness (only used by [BrushShape.NOISE] stamps) comes from a
 * `java.util.Random` seeded with [seed]; the RNG state **advances with every
 * noise stamp**, so a long stroke produces varied but fully reproducible
 * speckle: replaying the same call sequence from a fresh (or [reset]) engine
 * reproduces identical output. Call [reset] to restore the initial state.
 *
 * The engine is pure geometry — it knows no canvas bounds. Points landing in
 * the negative quadrant are dropped ([PixelPoint] invariant); points past the
 * right/bottom edges are returned and clipped later by canvas writes.
 */
class BrushEngine(val seed: Long = 0x504958L) {

    private var random: Random = Random(seed)

    /**
     * Rasterizes the brush stamp centered on (`x`, `y`). Output is in
     * row-major box order and contains each grid point at most once.
     *
     * @param x stamp center column (any Int; the box is clipped at 0).
     * @param y stamp center row (any Int; the box is clipped at 0).
     * @throws IllegalArgumentException only via an invalid [BrushSpec]
     *   (guarded at [BrushSpec] construction).
     */
    fun stamp(x: Int, y: Int, spec: BrushSpec): List<PixelPoint> {
        val size = spec.size
        val left = x - (size - 1) / 2
        val top = y - (size - 1) / 2
        when (spec.shape) {
            BrushShape.SQUARE -> return DrawOps.rect(left, top, size, size, true)
            BrushShape.CIRCLE -> {
                val out = ArrayList<PixelPoint>(size * size)
                val threshold = size.toLong() * size
                for (py in 0 until size) {
                    val dy = top + py - y
                    for (px in 0 until size) {
                        val dx = left + px - x
                        if (4L * (dx.toLong() * dx + dy.toLong() * dy) <= threshold) {
                            addClipped(out, left + px, top + py)
                        }
                    }
                }
                return out
            }
            BrushShape.CROSS -> {
                val out = ArrayList<PixelPoint>(2 * size)
                for (px in 0 until size) addClipped(out, left + px, y)
                for (py in 0 until size) {
                    if (top + py != y) addClipped(out, x, top + py)
                }
                return out
            }
            BrushShape.DIAGONAL -> {
                val out = ArrayList<PixelPoint>(2 * size)
                for (py in 0 until size) {
                    val dy = top + py - y
                    for (px in 0 until size) {
                        val dx = left + px - x
                        if (abs(dx) == abs(dy)) addClipped(out, left + px, top + py)
                    }
                }
                return out
            }
            BrushShape.NOISE -> {
                val out = ArrayList<PixelPoint>(size * size)
                for (py in 0 until size) {
                    for (px in 0 until size) {
                        if (random.nextDouble() < spec.noiseDensity) {
                            addClipped(out, left + px, top + py)
                        }
                    }
                }
                return out
            }
        }
    }

    /**
     * Rasterizes a whole stroke: consecutive [points] are connected with
     * Bresenham lines between the stamp centers, the brush is stamped at
     * every centerline pixel, and all stamps are merged into one
     * de-duplicated list preserving first occurrence. A single-point stroke
     * is a tap (one stamp); an empty input yields an empty list.
     *
     * When [pixelPerfect] is true the centerline is **de-stepped** before
     * stamping (classic pixel-perfect thinning): walking the centerline in
     * order, a candidate pixel is dropped when the pixels already accepted in
     * this stroke contain **both** a horizontal neighbor (left or right)
     * **and** a vertical neighbor (up or down) of it. Such a pixel would
     * complete a 2x2 block — the "thick corner" artifact of freehand
     * diagonals — so dropping it keeps diagonal strokes one pixel wide while
     * straight lines (which never have both a horizontal and a vertical
     * accepted neighbor at once) pass through untouched. Example: for the
     * path `(0,0) -> (1,0) -> (1,1) -> (0,1)` the last point is dropped
     * because its left neighbor `(1,1)` and its up neighbor `(0,0)` are both
     * already accepted, so no 2x2 block forms.
     *
     * [BrushShape.NOISE] strokes consume RNG draws for every stamped box, in
     * centerline order — see the class KDoc for replay semantics.
     */
    fun stroke(points: List<PixelPoint>, spec: BrushSpec, pixelPerfect: Boolean): List<PixelPoint> {
        if (points.isEmpty()) return emptyList()
        val centers = LinkedHashSet<PixelPoint>()
        if (points.size == 1) {
            centers.add(points[0])
        } else {
            for (i in 0 until points.size - 1) {
                centers.addAll(
                    DrawOps.line(points[i].x, points[i].y, points[i + 1].x, points[i + 1].y),
                )
            }
        }
        val path = if (pixelPerfect) deStep(centers.toList()) else centers.toList()
        val out = LinkedHashSet<PixelPoint>()
        for (center in path) out.addAll(stamp(center.x, center.y, spec))
        return out.toList()
    }

    /**
     * Perceptual shading color: shifts the CIELAB lightness `L*` of [base] by
     * `steps * 8` ([ShadingDirection.LIGHTEN] increases, [DARKEN] decreases),
     * clamps to the `[0, 100]` range, converts back to sRGB and re-attaches
     * the **original alpha** of [base] (so shading never reveals transparent
     * pixels, and translucent ones stay translucent).
     *
     * The clamping is a round trip: when the shift is fully absorbed by the
     * clamp (`L*` already at the boundary, or [steps] is 0), the original
     * pixel is returned unchanged to avoid Lab quantization drift.
     *
     * @throws IllegalArgumentException when [steps] is negative.
     */
    fun shadingColor(base: Int, direction: ShadingDirection, steps: Int): Int {
        require(steps >= 0) { "steps must be >= 0 (was $steps)" }
        if (steps == 0) return base
        val lab = LabColor.fromArgb(base)
        val delta = steps * 8.0
        val l = when (direction) {
            ShadingDirection.LIGHTEN -> (lab.l + delta).coerceAtMost(100.0)
            ShadingDirection.DARKEN -> (lab.l - delta).coerceAtLeast(0.0)
        }
        if (l == lab.l) return base
        val shifted = LabColor.toArgb(LabColor.Lab(l, lab.a, lab.b))
        return (base and (0xFF shl 24)) or (shifted and 0x00FFFFFF)
    }

    /** Restores the RNG to its initial seeded state for exact replay. */
    fun reset() {
        random = Random(seed)
    }

    /**
     * Pixel-perfect de-step over an ordered centerline; see [stroke] for the
     * exact neighbor rule.
     */
    private fun deStep(centers: List<PixelPoint>): List<PixelPoint> {
        val accepted = LinkedHashSet<PixelPoint>()
        for (c in centers) {
            val hasHorizontalNeighbor =
                (c.x > 0 && PixelPoint(c.x - 1, c.y) in accepted) ||
                    PixelPoint(c.x + 1, c.y) in accepted
            val hasVerticalNeighbor =
                (c.y > 0 && PixelPoint(c.x, c.y - 1) in accepted) ||
                    PixelPoint(c.x, c.y + 1) in accepted
            if (hasHorizontalNeighbor && hasVerticalNeighbor) continue
            accepted.add(c)
        }
        return accepted.toList()
    }

    /** Adds (`x`, `y`) to [out] when both coordinates are non-negative. */
    private fun addClipped(out: MutableCollection<PixelPoint>, x: Int, y: Int) {
        if (x >= 0 && y >= 0) out.add(PixelPoint(x, y))
    }

    /** Integer absolute value without depending on kotlin.math. */
    private fun abs(v: Int): Int = if (v >= 0) v else -v
}
