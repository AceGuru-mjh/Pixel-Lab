package com.pixellab.core.gen

import com.pixellab.core.model.MutablePixelFrame
import com.pixellab.core.model.PixelFrame
import kotlin.math.cos
import kotlin.math.sin

/**
 * Procedural pixel sprites: trees, rocks, gems and ships from small,
 * fully-defaulted parameter records.
 *
 * Design rules shared by every generator:
 *
 *  * **Determinism** — all randomness flows through [SeededRng] seeded from
 *    the spec's [seed][TreeSpec.seed]; the same spec always yields the same
 *    frame, byte for byte, on every platform.
 *  * **Immutability** — output is a fresh [PixelFrame]; inputs are never
 *    touched.
 *  * **Fail-fast validation** — specs that cannot possibly render
 *    (tiny canvases, malformed ramps) throw [IllegalArgumentException]
 *    from `require` with the offending value in the message.
 *  * **Silhouette conventions** — sprites sit on transparent backgrounds;
 *    opaque pixels use `alpha = 0xFF` throughout. The opacity threshold of
 *    the pipeline (`alpha >= 0x80`) therefore always classifies sprite
 *    pixels as opaque.
 *
 * The generators lean on two shared micro-idioms: linear channel mixing
 * (`mix` — used for faceting and top-lighting) and 50% darkening for
 * outline passes (`channel shr 1`, which keeps alpha intact).
 */
object SpriteGen {

    // ------------------------------------------------------------------
    // Tree
    // ------------------------------------------------------------------

    /**
     * Parameters of [tree].
     *
     * @property width canvas width, `>= 12`.
     * @property height canvas height, `>= 12`.
     * @property trunkColor ARGB color of the trunk column.
     * @property leafRamp canopy colors **dark → mid → light** (exactly 3
     *   entries); vertical position in the canopy selects the entry.
     * @property seed deterministic generation seed.
     */
    data class TreeSpec(
        val width: Int = 16,
        val height: Int = 16,
        val trunkColor: Int = 0xFF6B4226.toInt(),
        val leafRamp: IntArray = intArrayOf(
            0xFF1E7A2E.toInt(),
            0xFF38B04A.toInt(),
            0xFF7BD36B.toInt(),
        ),
        val seed: Long = 0L,
    )

    /**
     * A top-lit tree: vertical trunk plus a seeded elliptical canopy.
     *
     * Layout (all coordinates deterministic given the seed):
     *  * **Trunk** — a `max(1, width/6)`-wide column centered on
     *    `width/2`, rising from the bottom row for `max(3, height·2/5)`
     *    rows (~40% of the height), painted [TreeSpec.trunkColor] *after*
     *    the canopy so it overlaps the canopy's lower fringe cleanly.
     *  * **Canopy** — an ellipse centered at `(width/2, height·7/20)` with
     *    radii `width/3` and `height/3`, each jittered independently by
     *    `-1..+1` pixels from the spec's [SeededRng].
     *  * **Leaf shading** — canopy vertical position picks the ramp entry:
     *    top third light `leafRamp[2]`, middle third mid `leafRamp[1]`,
     *    bottom third dark `leafRamp[0]` (light comes from above).
     *  * **Outline** — canopy pixels that touch a transparent 4-neighbor
     *    are darkened 50% (each RGB channel halved, alpha kept), a 1px
     *    rim that reads as self-shadow.
     *
     * @throws IllegalArgumentException when the canvas is smaller than 12x12
     *   or the ramp does not carry exactly 3 colors.
     */
    fun tree(spec: TreeSpec): PixelFrame {
        require(spec.width >= 12) { "tree needs width >= 12 (was ${spec.width})" }
        require(spec.height >= 12) { "tree needs height >= 12 (was ${spec.height})" }
        require(spec.leafRamp.size == 3) {
            "leafRamp must hold exactly 3 colors (dark, mid, light); was ${spec.leafRamp.size}"
        }
        val rng = SeededRng(spec.seed)

        // --- canopy -----------------------------------------------------
        val cx = spec.width / 2
        val cy = spec.height * 7 / 20
        val rx = (spec.width / 3 + rng.nextInt(-1, 1)).coerceAtLeast(1)
        val ry = (spec.height / 3 + rng.nextInt(-1, 1)).coerceAtLeast(1)
        val canopy = MutablePixelFrame(spec.width, spec.height)
        val top = (cy - ry).toFloat()
        val span = (2 * ry).toFloat()
        for (y in 0 until spec.height) {
            for (x in 0 until spec.width) {
                val dxn = (x - cx).toFloat() / rx
                val dyn = (y - cy).toFloat() / ry
                if (dxn * dxn + dyn * dyn <= 1f) {
                    val t = if (span > 0f) ((y - top) / span).coerceIn(0f, 1f) else 0.5f
                    val leaf = when {
                        t < 1f / 3f -> spec.leafRamp[2]
                        t < 2f / 3f -> spec.leafRamp[1]
                        else -> spec.leafRamp[0]
                    }
                    canopy[x, y] = leaf
                }
            }
        }

        // --- canopy self-shadow outline (4-connectivity) -----------------
        val shaded = canopy.toImmutable()
        val out = shaded.copyPixels()
        for (y in 0 until spec.height) {
            for (x in 0 until spec.width) {
                if ((shaded[x, y] ushr 24) == 0) continue
                val touchesTransparent =
                    (shaded[x - 1, y] ushr 24) == 0 || (shaded[x + 1, y] ushr 24) == 0 ||
                        (shaded[x, y - 1] ushr 24) == 0 || (shaded[x, y + 1] ushr 24) == 0
                if (touchesTransparent) {
                    out[y * spec.width + x] = darken(shaded[x, y])
                }
            }
        }

        // --- trunk (drawn last, overlaps the canopy fringe) --------------
        val trunkW = (spec.width / 6).coerceAtLeast(1)
        val trunkH = (spec.height * 2 / 5).coerceAtLeast(3)
        val trunkX0 = (spec.width - trunkW) / 2
        val trunkTop = spec.height - trunkH
        for (y in trunkTop until spec.height) {
            for (x in trunkX0 until trunkX0 + trunkW) {
                out[y * spec.width + x] = spec.trunkColor
            }
        }
        return PixelFrame.of(spec.width, spec.height, out)
    }

    // ------------------------------------------------------------------
    // Rock
    // ------------------------------------------------------------------

    /**
     * Parameters of [rock].
     *
     * @property width canvas width, `>= 6`.
     * @property height canvas height, `>= 6`.
     * @property baseColor flat body color of the rock face.
     * @property highlightColor blend target for the upper third.
     * @property shadowColor blend target for the lower third.
     * @property seed deterministic generation seed (vertex count/jitter).
     */
    data class RockSpec(
        val width: Int = 16,
        val height: Int = 16,
        val baseColor: Int = 0xFF8A8A93.toInt(),
        val highlightColor: Int = 0xFFC9C9D4.toInt(),
        val shadowColor: Int = 0xFF4F4F58.toInt(),
        val seed: Long = 0L,
    )

    /**
     * A jittered-polygon boulder, top-lit in three vertical bands.
     *
     *  * **Silhouette** — `6..8` vertices (count drawn from the spec's
     *    [SeededRng]) distributed around an ellipse of radii
     *    `width/2 - 1` x `height/2 - 1`; each vertex angle is perturbed by
     *    up to ±¼ of the angular step and each radius by ±20%, then the
     *    polygon is scanline-filled with the even-odd rule at pixel
     *    centers.
     *  * **Banding** — relative to the *filled extent* `[minY, maxY]`: the
     *    upper third is a 50/50 mix of [RockSpec.baseColor] toward
     *    [RockSpec.highlightColor], the lower third toward
     *    [RockSpec.shadowColor], the middle band is the flat base color.
     *
     * @throws IllegalArgumentException when the canvas is smaller than 6x6.
     */
    fun rock(spec: RockSpec): PixelFrame {
        require(spec.width >= 6) { "rock needs width >= 6 (was ${spec.width})" }
        require(spec.height >= 6) { "rock needs height >= 6 (was ${spec.height})" }
        val rng = SeededRng(spec.seed)
        val cx = (spec.width - 1) / 2f
        val cy = (spec.height - 1) / 2f
        val rx = spec.width / 2f - 1f
        val ry = spec.height / 2f - 1f
        val vertexCount = 6 + rng.nextInt(0, 2)
        val vertices = ArrayList<Pair<Float, Float>>(vertexCount)
        val step = (2.0 * Math.PI / vertexCount).toFloat()
        for (i in 0 until vertexCount) {
            val angle = i * step + (rng.nextFloat() - 0.5f) * step * 0.5f
            val radiusFactor = 1f + (rng.nextFloat() * 2f - 1f) * 0.2f
            vertices.add(
                Pair(
                    cx + rx * radiusFactor * cos(angle),
                    cy + ry * radiusFactor * sin(angle),
                ),
            )
        }
        val filled = fillPolygonEvenOdd(spec.width, spec.height, vertices)
        var minY = Int.MAX_VALUE
        var maxY = -1
        for (y in 0 until spec.height) {
            if ((0 until spec.width).any { filled[y * spec.width + it] }) {
                if (y < minY) minY = y
                maxY = y
            }
        }
        if (minY == Int.MAX_VALUE) {
            // The jitter collapsed the polygon: draw the base-color ellipse fallback.
            minY = 0
            maxY = spec.height - 1
            for (y in 0 until spec.height) {
                for (x in 0 until spec.width) {
                    val dxn = (x - cx) / (rx * 0.5f)
                    val dyn = (y - cy) / (ry * 0.5f)
                    filled[y * spec.width + x] = dxn * dxn + dyn * dyn <= 1f
                }
            }
        }
        val extent = (maxY - minY).coerceAtLeast(1)
        val out = IntArray(spec.width * spec.height)
        for (y in 0 until spec.height) {
            for (x in 0 until spec.width) {
                val i = y * spec.width + x
                if (!filled[i]) continue
                out[i] = when {
                    y - minY <= extent / 3 -> mix(spec.baseColor, spec.highlightColor, 0.5f)
                    maxY - y <= extent / 3 -> mix(spec.baseColor, spec.shadowColor, 0.5f)
                    else -> spec.baseColor
                }
            }
        }
        return PixelFrame.of(spec.width, spec.height, out)
    }

    // ------------------------------------------------------------------
    // Gem
    // ------------------------------------------------------------------

    /**
     * Parameters of [gem].
     *
     * @property width canvas width, `>= 6`.
     * @property height canvas height, `>= 6`.
     * @property gemColor body color of the stone.
     * @property sparkleColor bright sparkle pixel color.
     * @property seed deterministic generation seed (sparkle placement).
     */
    data class GemSpec(
        val width: Int = 16,
        val height: Int = 16,
        val gemColor: Int = 0xFF2CE8F5.toInt(),
        val sparkleColor: Int = 0xFFFFFFFF.toInt(),
        val seed: Long = 0L,
    )

    /**
     * A faceted octagonal gemstone with a bright center line and sparkles.
     *
     *  * **Silhouette** — a chamfered rectangle (octagon): a pixel is
     *    inside when `|dx|/rx <= 1`, `|dy|/ry <= 1` **and**
     *    `|dx|/rx + |dy|/ry <= 1.5` around the canvas center with radii
     *    `rx = width/2 - 1`, `ry = height/2 - 1`.
     *  * **Faceting** — the near-center column (`|dx| <= max(1, rx/4)`) is
     *    mixed 35% toward white (the bright table facet line); the rim
     *    (`|dx|/rx + |dy|/ry >= 1.2`) is blended 30% toward black (the cut
     *    edges); everything else is the flat [GemSpec.gemColor].
     *  * **Sparkles** — `1..2` pixels of [GemSpec.sparkleColor] in the
     *    stone's upper half, positions drawn from the [SeededRng]; a
     *    bounded candidate search falls back to the topmost inside pixel
     *    so a sparkle always exists.
     *
     * @throws IllegalArgumentException when the canvas is smaller than 6x6.
     */
    fun gem(spec: GemSpec): PixelFrame {
        require(spec.width >= 6) { "gem needs width >= 6 (was ${spec.width})" }
        require(spec.height >= 6) { "gem needs height >= 6 (was ${spec.height})" }
        val rng = SeededRng(spec.seed)
        val cx = (spec.width - 1) / 2f
        val cy = (spec.height - 1) / 2f
        val rx = spec.width / 2f - 1f
        val ry = spec.height / 2f - 1f
        val inside: (Int, Int) -> Boolean = { x, y ->
            val a = kotlin.math.abs(x - cx) / rx
            val b = kotlin.math.abs(y - cy) / ry
            a <= 1f && b <= 1f && a + b <= 1.5f
        }
        val brightHalfWidth = (rx / 4f).toInt().coerceAtLeast(1)
        val out = IntArray(spec.width * spec.height)
        for (y in 0 until spec.height) {
            for (x in 0 until spec.width) {
                if (!inside(x, y)) continue
                val a = kotlin.math.abs(x - cx) / rx
                val b = kotlin.math.abs(y - cy) / ry
                out[y * spec.width + x] = when {
                    kotlin.math.abs(x - cx) <= brightHalfWidth ->
                        mix(spec.gemColor, 0xFFFFFFFF.toInt(), 0.35f)
                    a + b >= 1.2f -> mix(spec.gemColor, 0xFF000000.toInt(), 0.30f)
                    else -> spec.gemColor
                }
            }
        }
        // Sparkles: 1..2 pixels in the upper half of the stone.
        val sparkles = 1 + rng.nextInt(0, 1)
        for (k in 0 until sparkles) {
            var placed = false
            search@ for (attempt in 0 until 8) {
                val px = rng.nextInt((cx - rx).toInt().coerceAtLeast(0), (cx + rx).toInt().coerceAtMost(spec.width - 1))
                val py = rng.nextInt((cy - ry).toInt().coerceAtLeast(0), cy.toInt().coerceAtMost(spec.height - 1))
                if (inside(px, py)) {
                    out[py * spec.width + px] = spec.sparkleColor
                    placed = true
                    break@search
                }
            }
            if (!placed) {
                // Deterministic fallback: the topmost inside pixel of the center column.
                val columnX = cx.toInt()
                for (y in 0 until spec.height) {
                    if (inside(columnX, y)) {
                        out[y * spec.width + columnX] = spec.sparkleColor
                        break
                    }
                }
            }
        }
        return PixelFrame.of(spec.width, spec.height, out)
    }

    // ------------------------------------------------------------------
    // Ship
    // ------------------------------------------------------------------

    /**
     * Parameters of [ship].
     *
     * @property width canvas width, `>= 8` (even widths tile the mirror
     *   seam exactly; odd widths keep a center column).
     * @property height canvas height, `>= 6`.
     * @property hullColor body color of the hull.
     * @property cockpitColor cockpit canopy color near the nose.
     * @property engineColor exhaust nozzle color at the tail.
     * @property seed deterministic generation seed (hull width jitter).
     */
    data class ShipSpec(
        val width: Int = 16,
        val height: Int = 12,
        val hullColor: Int = 0xFFB13E53.toInt(),
        val cockpitColor: Int = 0xFF41A6F6.toInt(),
        val engineColor: Int = 0xFFFFCD75.toInt(),
        val seed: Long = 0L,
    )

    /**
     * A mirror-symmetric top-down spaceship: pointed nose on the vertical
     * center seam, twin exhaust nozzles at the tail, dark rim outline.
     *
     * The task contract fixes **horizontal mirror symmetry**: the left half
     * (`x < width/2`) is generated and then copied mirrored onto the right
     * half by [mirrorHorizontal], so the result always satisfies
     * `mirrorHorizontal(ship) == ship`. (A strictly side-view ship with
     * "nose right, engines left" cannot satisfy that contract, so the
     * generator renders the equivalent top-down silhouette: nose at the top
     * seam, engines at the bottom corners — and the symmetric twin engine
     * columns replace the single left-edge column of a side view.)
     *
     * Structure (left half only, `seamX = width/2 - 1`):
     *  * **Hull** — a half-width profile `hw(y)` along the seam:
     *    grows `1 → maxHalf` over the top third (the nose), holds
     *    `maxHalf` over the body, shrinks `maxHalf → tailHalf` over the
     *    bottom quarter; `maxHalf = width/4` jittered `+0..+1` by the
     *    [SeededRng], `tailHalf = max(1, maxHalf/3)`. Pixels
     *    `seamX - hw(y) .. seamX` are [ShipSpec.hullColor].
     *  * **Cockpit** — a half-ellipse centered on the seam at `height/4`
     *    with radii `max(1, maxHalf/2)` x `max(1, height/6)`, filled
     *    [ShipSpec.cockpitColor]; after mirroring it reads as a full
     *    cockpit bubble near the nose.
     *  * **Engines** — a 2-pixel-tall nozzle column of
     *    [ShipSpec.engineColor] at the hull's bottom-left corner
     *    (`x = seamX - tailHalf`), mirrored to the bottom-right corner.
     *  * **Outline** — after mirroring, opaque pixels with a transparent
     *    4-neighbor are darkened 50% (mirror-invariant operation).
     *
     * @throws IllegalArgumentException when the canvas is smaller than 8x6.
     */
    fun ship(spec: ShipSpec): PixelFrame {
        require(spec.width >= 8) { "ship needs width >= 8 (was ${spec.width})" }
        require(spec.height >= 6) { "ship needs height >= 6 (was ${spec.height})" }
        val rng = SeededRng(spec.seed)
        val seamX = spec.width / 2 - 1
        val maxHalf = spec.width / 4 + rng.nextInt(0, 1)
        val tailHalf = (maxHalf / 3).coerceAtLeast(1)
        val noseLen = (spec.height / 3).coerceAtLeast(1)
        val tailLen = (spec.height / 4).coerceAtLeast(1)

        fun hullHalfWidth(y: Int): Int {
            return when {
                y < noseLen -> {
                    val t = y.toFloat() / noseLen
                    (1f + (maxHalf - 1) * t).toInt().coerceAtLeast(1)
                }
                y < spec.height - tailLen -> maxHalf
                else -> {
                    val t = (y - (spec.height - tailLen)).toFloat() / tailLen
                    (maxHalf + (tailHalf - maxHalf) * t).toInt().coerceAtLeast(1)
                }
            }
        }

        // --- left half --------------------------------------------------
        val canvas = MutablePixelFrame(spec.width, spec.height)
        for (y in 0 until spec.height) {
            val hw = hullHalfWidth(y)
            for (x in (seamX - hw)..seamX) {
                canvas[x, y] = spec.hullColor
            }
        }
        // Cockpit half-ellipse on the seam, near the nose.
        val cockpitCy = (spec.height / 4).coerceAtLeast(1)
        val cockpitRx = (maxHalf / 2).coerceAtLeast(1)
        val cockpitRy = (spec.height / 6).coerceAtLeast(1)
        for (y in (cockpitCy - cockpitRy)..(cockpitCy + cockpitRy)) {
            for (x in (seamX - cockpitRx)..seamX) {
                val dxn = (x - seamX).toFloat() / cockpitRx
                val dyn = (y - cockpitCy).toFloat() / cockpitRy
                if (dxn * dxn + dyn * dyn <= 1f) {
                    canvas[x, y] = spec.cockpitColor
                }
            }
        }
        // Twin engine nozzles at the tail corners (left one; mirror adds the right).
        val engineX = (seamX - tailHalf).coerceAtLeast(0)
        canvas[engineX, spec.height - 2] = spec.engineColor
        canvas[engineX, spec.height - 1] = spec.engineColor

        // --- mirror + outline -------------------------------------------
        val mirrored = mirrorHorizontal(canvas.toImmutable())
        val out = mirrored.copyPixels()
        for (y in 0 until spec.height) {
            for (x in 0 until spec.width) {
                if ((mirrored[x, y] ushr 24) == 0) continue
                val touchesTransparent =
                    (mirrored[x - 1, y] ushr 24) == 0 || (mirrored[x + 1, y] ushr 24) == 0 ||
                        (mirrored[x, y - 1] ushr 24) == 0 || (mirrored[x, y + 1] ushr 24) == 0
                if (touchesTransparent) {
                    out[y * spec.width + x] = darken(mirrored[x, y])
                }
            }
        }
        return PixelFrame.of(spec.width, spec.height, out)
    }

    // ------------------------------------------------------------------
    // Frame-level helpers
    // ------------------------------------------------------------------

    /**
     * Enforces horizontal mirror symmetry: the left half (`x < width/2`) is
     * kept verbatim and copied mirrored onto the right half
     * (`out(x, y) = in(width - 1 - x, y)` for `x >= width/2`); odd widths
     * keep their true center column. The operation is an involution:
     * `mirrorHorizontal(mirrorHorizontal(f)) == mirrorHorizontal(f)`, and a
     * frame is left-right symmetric exactly when
     * `mirrorHorizontal(f) == f`.
     */
    fun mirrorHorizontal(frame: PixelFrame): PixelFrame {
        val half = frame.width / 2
        val out = frame.copyPixels()
        for (y in 0 until frame.height) {
            val row = y * frame.width
            for (x in 0 until half) {
                out[row + frame.width - 1 - x] = frame.pixels[row + x]
            }
        }
        return PixelFrame.of(frame.width, frame.height, out)
    }

    /**
     * Flattens a sprite to a monochrome silhouette: every pixel with a
     * non-zero alpha becomes [color] (full replacement, including the alpha
     * of [color]); fully transparent pixels stay fully transparent. Useful
     * for shadow blobs, stencils and hitbox previews.
     */
    fun silhouette(frame: PixelFrame, color: Int): PixelFrame =
        frame.map { argb -> if ((argb ushr 24) != 0) color else argb }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Linear per-channel mix of two opaque ARGB colors: the alpha of
     * [target] wins (`t = 0` → [base], `t = 1` → [target]); used for
     * faceting and top-lighting.
     */
    private fun mix(base: Int, target: Int, t: Float): Int {
        val r = ((base shr 16) and 0xFF) + (((target shr 16) and 0xFF) - ((base shr 16) and 0xFF)) * t
        val g = ((base shr 8) and 0xFF) + (((target shr 8) and 0xFF) - ((base shr 8) and 0xFF)) * t
        val b = (base and 0xFF) + (((target) and 0xFF) - ((base) and 0xFF)) * t
        return (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
    }

    /** 50% darkening of an ARGB pixel: every RGB channel halved, alpha kept. */
    private fun darken(argb: Int): Int {
        val a = argb and 0xFF000000.toInt()
        return a or (((argb shr 16) and 0xFF shr 1) shl 16) or
            ((((argb shr 8) and 0xFF) shr 1) shl 8) or (((argb) and 0xFF) shr 1)
    }

    /**
     * Even-odd scanline polygon fill at pixel centers: for each row the
     * polygon edges crossing the line `y + 0.5` are collected, sorted, and
     * the span between each crossing *pair* is filled where the pixel
     * center `x + 0.5` lies inside the span. Degenerate (horizontal) edges
     * never cross anything and are skipped.
     */
    private fun fillPolygonEvenOdd(width: Int, height: Int, vertices: List<Pair<Float, Float>>): BooleanArray {
        val out = BooleanArray(width * height)
        if (vertices.size < 3) return out
        val ys = FloatArray(height) { it + 0.5f }
        val crossings = ArrayList<Float>(vertices.size)
        for (y in 0 until height) {
            val line = ys[y]
            crossings.clear()
            for (i in vertices.indices) {
                val a = vertices[i]
                val b = vertices[(i + 1) % vertices.size]
                val yLo = minOf(a.second, b.second)
                val yHi = maxOf(a.second, b.second)
                if (line < yLo || line >= yHi || yLo == yHi) continue
                // x at the crossing of edge (a, b) with the horizontal line.
                val t = (line - a.second) / (b.second - a.second)
                crossings.add(a.first + (b.first - a.first) * t)
            }
            if (crossings.size < 2) continue
            crossings.sort()
            var i = 0
            while (i + 1 < crossings.size) {
                val xStart = crossings[i]
                val xEnd = crossings[i + 1]
                i += 2
                // Pixel centers x + 0.5 inside [xStart, xEnd].
                var px = kotlin.math.ceil(xStart - 0.5f).toInt()
                val pxEnd = kotlin.math.floor(xEnd - 0.5f).toInt()
                while (px <= pxEnd) {
                    if (px in 0 until width) out[y * width + px] = true
                    px++
                }
            }
        }
        return out
    }
}
