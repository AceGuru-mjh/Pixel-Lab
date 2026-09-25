package com.pixellab.core.tools

import com.pixellab.core.model.PixelFrame
import com.pixellab.core.palette.LabColor

/**
 * How [OutlineShading.outline] picks the pixels that receive the outline
 * color.
 */
enum class OutlineMode {
    /**
     * Draw *outside* the shape: transparent pixels that touch an opaque
     * pixel are painted. Existing opaque pixels are never overwritten, so
     * the silhouette grows by one pixel.
     */
    OUTER,

    /**
     * Draw *inside* the shape: opaque pixels that touch a transparent pixel
     * are recolored. The silhouette is preserved and the border pixels are
     * replaced, which is the classic "hard inner outline" look.
     */
    INNER,
}

/**
 * Neighborhood definition used by the outline and shading passes.
 *
 *  * [FOUR] — up/down/left/right (Manhattan / von-Neumann neighborhood).
 *  * [EIGHT] — the 4-neighborhood plus the diagonals (Moore neighborhood).
 */
enum class Connectivity { FOUR, EIGHT }

/**
 * Outline, directional shading and palette-ramp helpers for
 * [PixelFrame]s — the "finishing pass" toolbox of pixel art.
 *
 * Every function is pure: frame in, frame out, no mutation of the input,
 * fully deterministic (no RNG, no time, no environment). The opacity
 * convention matches the convert pipeline: a pixel is **opaque** when its
 * alpha is `>= 0x80` and **transparent** otherwise (semi-transparent pixels
 * count as transparent, which keeps outlines from growing out of ghosting).
 *
 * All color math that mixes two colors happens in sRGB with the standard
 * source-over alpha compositing formula:
 * ```
 * outA = sa + da·(1 - sa)
 * outC = (sc·sa + dc·da·(1 - sa)) / outA      (per channel, 0 when outA = 0)
 * ```
 */
object OutlineShading {

    /** Alpha threshold at/above which a pixel counts as opaque. */
    private const val OPAQUE_MIN: Int = 0x80

    // ------------------------------------------------------------------
    // Outline
    // ------------------------------------------------------------------

    /**
     * Draws a one-pixel outline around (or inside) the opaque content of
     * [frame].
     *
     * * [OutlineMode.OUTER]: every transparent pixel with at least one
     *   opaque neighbor (per [connectivity]) becomes [color]. Opaque pixels
     *   are never touched — an OUTER pass is strictly additive.
     * * [OutlineMode.INNER]: every opaque pixel with at least one
     *   transparent neighbor (per [connectivity]) is recolored to [color].
     *   Transparent pixels are never touched.
     *
     * Worked example — 5x5 frame, red center pixel, FOUR connectivity,
     * OUTER mode with a black outline color:
     * ```
     * . . . . .        . . k . .
     * . . . . .        . k k k .
     * . . R . .   →    k k R k k     (exactly the 4 orthogonal neighbors)
     * . . . . .        . k k k .
     * . . . . .        . . k . .
     * ```
     * With EIGHT connectivity the 4 diagonal cells around the center are
     * outlined as well (8 pixels total). The same input in INNER mode
     * recolors only the red center: it is opaque and all its neighbors are
     * transparent.
     *
     * @param frame source frame; not mutated.
     * @param color ARGB outline color (its alpha is used verbatim).
     * @param mode OUTER grows the silhouette, INNER repaints its border.
     * @param connectivity FOUR or EIGHT neighbor test.
     * @return a new frame; the input instance itself when nothing changed.
     */
    fun outline(
        frame: PixelFrame,
        color: Int,
        mode: OutlineMode = OutlineMode.OUTER,
        connectivity: Connectivity = Connectivity.EIGHT,
    ): PixelFrame {
        val opaque = opacityMask(frame)
        val hit = BooleanArray(frame.pixels.size)
        var any = false
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val i = y * frame.width + x
                val want = when (mode) {
                    OutlineMode.OUTER -> !opaque[i] && hasNeighbor(opaque, frame.width, frame.height, x, y, connectivity)
                    OutlineMode.INNER -> opaque[i] && hasNeighborGap(opaque, frame.width, frame.height, x, y, connectivity)
                }
                if (want) {
                    hit[i] = true
                    any = true
                }
            }
        }
        if (!any) return frame
        val out = frame.copyPixels()
        for (i in hit.indices) if (hit[i]) out[i] = color
        return PixelFrame.of(frame.width, frame.height, out)
    }

    /**
     * Selective outline: paints the outline color only around pixels whose
     * color exactly equals [maskColor] (full 32-bit ARGB comparison), leaving
     * the rest of the frame untouched.
     *
     * * [OutlineMode.OUTER]: transparent pixels with at least one
     *   [maskColor]-colored neighbor (per [connectivity]) become
     *   [outlineColor].
     * * [OutlineMode.INNER]: pixels colored [maskColor] with at least one
     *   transparent neighbor (per [connectivity]) are recolored to
     *   [outlineColor].
     *
     * Typical use: a sprite where only the skin or the eyes should carry an
     * outline, not the whole silhouette. Worked example — a 3x1 strip
     * `[A B B]` with `maskColor = A`, `connectivity = FOUR`, OUTER mode:
     * only the transparent cells adjacent to the left `A` pixel get the
     * outline (nothing next to the `B` run).
     *
     * @param frame source frame; not mutated.
     * @param maskColor the exact ARGB color whose island receives the outline.
     * @param outlineColor ARGB color painted around/onto the masked pixels.
     * @param mode OUTER (additive ring) or INNER (recolored border).
     * @param connectivity FOUR or EIGHT neighbor test.
     * @return a new frame, or the input instance when nothing matched.
     */
    fun selectiveOutline(
        frame: PixelFrame,
        maskColor: Int,
        outlineColor: Int,
        mode: OutlineMode = OutlineMode.OUTER,
        connectivity: Connectivity = Connectivity.EIGHT,
    ): PixelFrame {
        val masked = BooleanArray(frame.pixels.size) { frame.pixels[it] == maskColor }
        val transparent = BooleanArray(frame.pixels.size) { (frame.pixels[it] ushr 24) < OPAQUE_MIN }
        val out = frame.copyPixels()
        var any = false
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val i = y * frame.width + x
                val want = when (mode) {
                    OutlineMode.OUTER ->
                        transparent[i] && hasNeighbor(masked, frame.width, frame.height, x, y, connectivity)
                    OutlineMode.INNER ->
                        masked[i] && hasNeighborGap(transparent, frame.width, frame.height, x, y, connectivity)
                }
                if (want) {
                    out[i] = outlineColor
                    any = true
                }
            }
        }
        return if (any) PixelFrame.of(frame.width, frame.height, out) else frame
    }

    // ------------------------------------------------------------------
    // Directional shading
    // ------------------------------------------------------------------

    /**
     * Cheap directional shading pass: opaque pixels facing the light are
     * tinted toward [lightColor], pixels facing away toward [darkColor].
     *
     * ## The model (documented contract)
     *
     * Opacity is treated as a binary height field `h(x, y)` (1 = opaque,
     * 0 = transparent, out-of-bounds = 0). Per opaque pixel the *screen-space
     * gradient* is estimated with central differences:
     * ```
     * gx = (h(x+1, y) - h(x-1, y)) / 2        gy = (h(x, y+1) - h(x, y-1)) / 2
     * N  = (-gx, -gy)                          (points from filled toward open space)
     * ```
     * Interior pixels of a solid region have `N = 0` and stay unshaded; edge
     * pixels have `|N| ∈ (0, 0.707]`. The normalized *facing factor* is the
     * cosine between N and the unit vector pointing from the scene toward
     * the light source:
     * ```
     * facing = (N · S) / |N|        in [-1, 1]
     * ```
     * [lightAngleDeg] is a compass bearing for S: 0° = up, 90° = right,
     * 180° = down, 270° = left, increasing clockwise — the default **315°
     * puts the light source at the top-left**, the classic pixel-art
     * convention. `S = (sin θ, -cos θ)` in screen coordinates (y grows
     * downward).
     *
     * Pixels with `facing > 0` get [lightColor] blended over them with alpha
     * `round(strength · facing)`; pixels with `facing < 0` get [darkColor]
     * with alpha `round(strength · |facing|)`; `facing == 0` is untouched.
     * The alpha channels of [lightColor]/[darkColor] are ignored — the
     * blend alpha comes from [strength] and the facing factor (the defaults
     * use `0x80…` colors purely so the color literals carry the intended
     * RGB payload). Blending is source-over in sRGB; the original pixel
     * alpha is preserved for opaque destinations.
     *
     * Worked example — a 1x3 column of gray pixels at the left edge of a
     * canvas with open space to the left and solid pixels to the right:
     * the left-edge pixel has `N = (-0.5, 0)`, so with the default 315°
     * light `facing = +0.707` and it receives `strength·0.707 ≈ 91` alpha
     * of the light color, while its right-side neighbor (`N = (+0.5, 0)`)
     * receives the dark blend instead.
     *
     * @param frame source frame; not mutated.
     * @param lightAngleDeg compass bearing of the light source (0 = up, 90 =
     *   right, clockwise); must be finite.
     * @param darkColor RGB payload blended onto back-facing pixels.
     * @param lightColor RGB payload blended onto light-facing pixels.
     * @param strength maximum blend alpha in `0..255` (reached at |facing| = 1).
     */
    fun shade(
        frame: PixelFrame,
        lightAngleDeg: Float = 315f,
        darkColor: Int = 0x80101010.toInt(),
        lightColor: Int = 0x80FFFFFF.toInt(),
        strength: Int = 128,
    ): PixelFrame {
        require(lightAngleDeg.isFinite()) { "lightAngleDeg must be finite (was $lightAngleDeg)" }
        require(strength in 0..255) { "strength must be in [0, 255] (was $strength)" }
        val radians = Math.toRadians(lightAngleDeg.toDouble())
        // Unit vector from the scene toward the light source (screen coords, y down).
        val sx = Math.sin(radians).toFloat()
        val sy = (-Math.cos(radians)).toFloat()
        val opaque = opacityMask(frame)
        val lightRgb = lightColor and 0xFFFFFF
        val darkRgb = darkColor and 0xFFFFFF
        val out = frame.copyPixels()
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val i = y * frame.width + x
                if (!opaque[i]) continue
                val gx = (maskValue(opaque, frame.width, frame.height, x + 1, y) -
                    maskValue(opaque, frame.width, frame.height, x - 1, y)) * 0.5f
                val gy = (maskValue(opaque, frame.width, frame.height, x, y + 1) -
                    maskValue(opaque, frame.width, frame.height, x, y - 1)) * 0.5f
                val nx = -gx
                val ny = -gy
                val len = Math.sqrt((nx * nx + ny * ny).toDouble()).toFloat()
                if (len <= 0f) continue
                val facing = (nx * sx + ny * sy) / len
                if (facing == 0f) continue
                val overlay = if (facing > 0f) lightRgb else darkRgb
                val alpha = (strength * Math.abs(facing.toDouble())).toInt().coerceIn(0, 255)
                if (alpha == 0) continue
                out[i] = blendOver(overlay, alpha, out[i])
            }
        }
        return PixelFrame.of(frame.width, frame.height, out)
    }

    // ------------------------------------------------------------------
    // Ramp
    // ------------------------------------------------------------------

    /**
     * Builds a color ramp by interpolating **CIELAB lightness** while
     * keeping the base color's `a*`/`b*` chroma untouched: hue and saturation
     * stay constant, only brightness moves — perceptually what an artist
     * means by "a ramp of this color".
     *
     * The ramp spans `L* - darken` (first color) through the base `L*` to
     * `L* + lighten` (last color), both endpoints clamped to `[0, 100]`.
     * With `steps == 5`, `lighten = darken = 20` and a mid gray base the
     * result is 5 evenly spaced lightness steps with the darkest first:
     *
     * ```
     * ramp(0xFF808080, steps = 5, lighten = 20, darken = 20)
     *   → [L≈33.6, L≈43.6, L≈53.6, L≈63.6, L≈73.6]   (a*, b* unchanged)
     * ```
     *
     * The endpoints are simply the interpolation bounds: when an offset is
     * zero the corresponding end collapses *onto* the base lightness (the
     * base is still included), and when both offsets are zero every entry is
     * the base color itself. The alpha channel of [baseColor] is copied to
     * every ramp entry.
     *
     * @param baseColor ARGB anchor color (its Lab a/b is preserved).
     * @param steps total number of colors; `>= 2`, else the list is empty.
     * @param lighten lightness added to the base at the bright end, `>= 0`.
     * @param darken lightness subtracted at the dark end, `>= 0`.
     * @return [steps] colors, dark to light, each rebuilt via
     *   [LabColor.toArgb] (rounding may shift a channel by ±1).
     */
    fun ramp(baseColor: Int, steps: Int, lighten: Int = 0, darken: Int = 0): List<Int> {
        require(lighten >= 0) { "lighten must be >= 0 (was $lighten)" }
        require(darken >= 0) { "darken must be >= 0 (was $darken)" }
        if (steps < 2) return emptyList()
        val base = LabColor.fromArgb(baseColor)
        val low = (base.l - darken).coerceIn(0.0, 100.0)
        val high = (base.l + lighten).coerceIn(0.0, 100.0)
        val alpha = baseColor and 0xFF000000.toInt()
        val out = ArrayList<Int>(steps)
        for (i in 0 until steps) {
            val t = i.toDouble() / (steps - 1)
            val l = low + (high - low) * t
            val rgb = LabColor.toArgb(LabColor.Lab(l, base.a, base.b)) and 0xFFFFFF
            out.add(alpha or rgb)
        }
        return out
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Boolean opacity mask: `true` where alpha >= 0x80. */
    private fun opacityMask(frame: PixelFrame): BooleanArray =
        BooleanArray(frame.pixels.size) { (frame.pixels[it] ushr 24) >= OPAQUE_MIN }

    /** Reads a boolean mask at (x, y); out of bounds reads as false. */
    private fun maskAt(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Boolean =
        x >= 0 && y >= 0 && x < width && y < height && mask[y * width + x]

    /** Reads a boolean mask as occupancy `1`/`0`; out of bounds reads as 0. */
    private fun maskValue(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Int =
        if (maskAt(mask, width, height, x, y)) 1 else 0

    /** True when any neighbor (per [Connectivity]) of (x, y) is `true` in [mask]. */
    private fun hasNeighbor(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int, c: Connectivity): Boolean {
        if (maskAt(mask, width, height, x - 1, y) || maskAt(mask, width, height, x + 1, y)) return true
        if (maskAt(mask, width, height, x, y - 1) || maskAt(mask, width, height, x, y + 1)) return true
        if (c == Connectivity.EIGHT) {
            if (maskAt(mask, width, height, x - 1, y - 1) || maskAt(mask, width, height, x + 1, y - 1)) return true
            if (maskAt(mask, width, height, x - 1, y + 1) || maskAt(mask, width, height, x + 1, y + 1)) return true
        }
        return false
    }

    /** True when any neighbor (per [Connectivity]) of (x, y) is `false` in [mask]. */
    private fun hasNeighborGap(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int, c: Connectivity): Boolean {
        if (!maskAt(mask, width, height, x - 1, y) || !maskAt(mask, width, height, x + 1, y)) return true
        if (!maskAt(mask, width, height, x, y - 1) || !maskAt(mask, width, height, x, y + 1)) return true
        if (c == Connectivity.EIGHT) {
            if (!maskAt(mask, width, height, x - 1, y - 1) || !maskAt(mask, width, height, x + 1, y - 1)) return true
            if (!maskAt(mask, width, height, x - 1, y + 1) || !maskAt(mask, width, height, x + 1, y + 1)) return true
        }
        return false
    }

    /**
     * Source-over compositing of an overlay color (RGB payload, explicit
     * [alpha]) onto [dst] in sRGB; the destination alpha is preserved when
     * the destination is opaque.
     */
    private fun blendOver(overlayRgb: Int, alpha: Int, dst: Int): Int {
        val da = (dst ushr 24) and 0xFF
        val outA = alpha + da * (255 - alpha) / 255
        if (outA == 0) return 0
        fun mix(sc: Int, dc: Int): Int = (sc * alpha + dc * da * (255 - alpha) / 255) / outA
        val r = mix((overlayRgb shr 16) and 0xFF, (dst shr 16) and 0xFF)
        val g = mix((overlayRgb shr 8) and 0xFF, (dst shr 8) and 0xFF)
        val b = mix(overlayRgb and 0xFF, dst and 0xFF)
        return (outA shl 24) or (r shl 16) or (g shl 8) or b
    }
}
