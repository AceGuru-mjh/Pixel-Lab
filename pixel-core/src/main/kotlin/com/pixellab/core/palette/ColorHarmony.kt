package com.pixellab.core.palette

import com.pixellab.core.model.Palette
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Color-harmony and ramp tools built on CIELAB (see [LabColor]).
 *
 * Classic color-wheel harmonies (complementary, triadic, …) are defined as
 * hue rotations. This object approximates them in Lab space: `L*` encodes
 * brightness, while the `a*`/`b*` plane encodes color opponent axes, so a
 * rotation in that plane (`a' = a·cosθ − b·sinθ`, `b' = a·sinθ + b·cosθ`,
 * see [rotateLab]) keeps lightness fixed and spins the hue/chroma vector —
 * perceptually far closer to what artists mean by "rotate the hue" than an
 * HSV twist, and it degrades gracefully for grays (rotation of a near-zero
 * `a*`/`b*` vector stays near-gray instead of hallucinating saturation).
 *
 * ## Gamut handling
 *
 * Saturated colors rotated in Lab frequently land outside the sRGB gamut.
 * Plain channel clipping would silently shift lightness, breaking the
 * `L*`-preserving contract of the harmony functions — so [rotateLab]
 * instead scales the rotated `a*`/`b*` vector back into gamut (a binary
 * search on the chroma scale factor, see the private `projectToGamut`).
 * Lightness is thereby preserved exactly (up to 8-bit output rounding);
 * only chroma is sacrificed, which is the honest trade-off sRGB forces.
 * The ladder/ramp builders ([monochromatic], [gradient], [luminanceRamp])
 * follow their formulas literally and render out-of-gamut rungs through
 * [LabColor.toArgb]'s channel clipping instead — each documents this.
 *
 * Every function is pure and deterministic: same inputs, same outputs, no
 * state, no I/O. Colors are opaque ARGB ints; alpha is ignored on input and
 * forced opaque on output.
 */
object ColorHarmony {

    /** Suggested outline for light fills: near-black with a cool bias. */
    private val OUTLINE_DARK: Int = 0xFF0E0E12.toInt()

    /** Suggested outline for dark fills: near-white with a warm bias. */
    private val OUTLINE_LIGHT: Int = 0xFFF5F1E8.toInt()

    /** Lab lightness threshold separating "dark" from "light" fills. */
    private const val LIGHTNESS_CUTOFF = 50.0

    // ------------------------------------------------------------------
    // Rotations (the harmony primitives)
    // ------------------------------------------------------------------

    /**
     * Rotates [base] by [degrees] in the CIELAB `a*`/`b*` plane, holding
     * `L*` constant. This is the core primitive behind
     * [complementary], [analogous], [triadic], [splitComplementary] and
     * [tetradic]:
     *
     * ```
     * θ = degrees · π / 180
     * a' = a·cosθ − b·sinθ
     * b' = a·sinθ + b·cosθ
     * ```
     *
     * When the rotated color falls outside the sRGB gamut (common for
     * saturated bases), the `a'`/`b'` vector is scaled toward the gray axis
     * just enough to re-enter it — a chroma-only concession that keeps the
     * lightness contract intact (see the object-level "Gamut handling"
     * note). The result is always an opaque in-gamut color.
     *
     * Rotating by any multiple of 360° returns [base] bit-exactly (the Lab
     * round trip is bypassed).
     *
     * @throws IllegalArgumentException if [degrees] is NaN or infinite.
     */
    fun rotateLab(base: Int, degrees: Double): Int {
        require(degrees.isFinite()) { "degrees must be finite, was $degrees" }
        if (degrees % 360.0 == 0.0) return base
        val lab = LabColor.fromArgb(base)
        val rad = degrees * PI / 180.0
        val cosT = cos(rad)
        val sinT = sin(rad)
        val a2 = lab.a * cosT - lab.b * sinT
        val b2 = lab.a * sinT + lab.b * cosT
        return projectToGamut(l = lab.l, a = a2, b = b2)
    }

    /**
     * Complementary scheme: `[base, rotateLab(base, 180°)]` — the base plus
     * its opposite on the Lab hue wheel. Lightness is preserved, so the
     * pair keeps a balanced visual weight; only the hue/chroma direction
     * flips.
     */
    fun complementary(base: Int): List<Int> = listOf(base, rotateLab(base, 180.0))

    /**
     * Analogous scheme: a ladder of `[count] + 1` colors evenly spaced
     * across [totalSpreadDeg], centered on [base].
     *
     * Step i (0..count) is rotated by `(i − count/2) · spread/count`
     * degrees, so the scheme spans exactly [totalSpreadDeg] from
     * `−spread/2` to `+spread/2`. With the defaults (`count = 2`,
     * `spread = 60°`) the result is the classic three-run analogous
     * scheme `[base−30°, base, base+30°]` (the middle rung is the untouched
     * base itself). Even counts place [base] on the center rung; odd
     * counts bracket it without containing it.
     *
     * @throws IllegalArgumentException if [count] < 1 or
     *         [totalSpreadDeg] is not positive.
     */
    fun analogous(base: Int, count: Int = 2, totalSpreadDeg: Double = 60.0): List<Int> {
        require(count >= 1) { "count must be at least 1, was $count" }
        require(totalSpreadDeg > 0.0) { "totalSpreadDeg must be positive, was $totalSpreadDeg" }
        val step = totalSpreadDeg / count
        return (0..count).map { i ->
            val offset = (i - count / 2.0) * step
            if (offset == 0.0) base else rotateLab(base, offset)
        }
    }

    /**
     * Triadic scheme: `[base, +120°, +240°]` — three colors spaced a third
     * of the Lab hue wheel apart, all sharing the base's lightness.
     */
    fun triadic(base: Int): List<Int> =
        listOf(base, rotateLab(base, 120.0), rotateLab(base, 240.0))

    /**
     * Split-complementary scheme: `[base, +150°, +210°]` — instead of the
     * direct 180° complement, the two colors flanking it by ±30°. Keeps
     * the contrast of a complementary pair with less chroma clash.
     */
    fun splitComplementary(base: Int): List<Int> =
        listOf(base, rotateLab(base, 150.0), rotateLab(base, 210.0))

    /**
     * Tetradic (square) scheme: `[base, +90°, +180°, +270°]` — four colors
     * at quarter-turn intervals of the Lab hue wheel. The 180° member is
     * the base's complement, so the scheme contains two complementary
     * pairs.
     */
    fun tetradic(base: Int): List<Int> =
        listOf(base, rotateLab(base, 90.0), rotateLab(base, 180.0), rotateLab(base, 270.0))

    // ------------------------------------------------------------------
    // Luminance-based schemes
    // ------------------------------------------------------------------

    /**
     * Monochromatic scheme: [count] shades sharing [base]'s `a*`/`b*`
     * (hue and chroma) with `L*` laddered evenly across the full
     * `[0, 100]` range — rung i sits at `L* = 100·i/(count−1)`, clamped
     * into `[0, 100]`.
     *
     * The base color itself is a member only when its own `L*` happens to
     * land on a rung; the ladder deliberately ignores the base's lightness
     * so the ramp always spans black-to-white. Because `a*`/`b*` are held
     * at full strength, extreme rungs (near `L* = 0` or `100` with nonzero
     * chroma) can leave the sRGB gamut; they render through channel
     * clipping, so their measured lightness may undershoot the ideal rung.
     * Use [luminanceRamp] when you need endpoints that are truly black and
     * white.
     *
     * @throws IllegalArgumentException if [count] < 1.
     */
    fun monochromatic(base: Int, count: Int): List<Int> {
        require(count >= 1) { "count must be at least 1, was $count" }
        if (count == 1) return listOf(base)
        val lab = LabColor.fromArgb(base)
        return (0 until count).map { i ->
            val l = (100.0 * i / (count - 1)).coerceIn(0.0, 100.0)
            LabColor.toArgb(LabColor.Lab(l = l, a = lab.a, b = lab.b))
        }
    }

    /**
     * A perceptual gradient of [steps] colors from [from] to [to], linear
     * in CIELAB: each rung interpolates `L*`, `a*` and `b*` at
     * `t = i/(steps−1)` and converts back to ARGB. Lab interpolation avoids
     * the muddy midpoints of straight RGB lerp.
     *
     * With [steps] = 1 the result is `[from]`; with 2 it is `[from, to]`.
     *
     * @throws IllegalArgumentException if [steps] < 1.
     */
    fun gradient(from: Int, to: Int, steps: Int): List<Int> {
        require(steps >= 1) { "steps must be at least 1, was $steps" }
        if (steps == 1) return listOf(from)
        val a = LabColor.fromArgb(from)
        val b = LabColor.fromArgb(to)
        return (0 until steps).map { i ->
            val t = i.toDouble() / (steps - 1)
            LabColor.toArgb(
                LabColor.Lab(
                    l = a.l + (b.l - a.l) * t,
                    a = a.a + (b.a - a.a) * t,
                    b = a.b + (b.b - a.b) * t,
                )
            )
        }
    }

    /**
     * A gradient snapped to [palette]: first builds
     * [gradient](`from → to, steps`), then maps every rung to the palette
     * entry closest in CIELAB distance ([Palette.findClosest]), so ties
     * resolve to the lowest index and the output is deterministic.
     *
     * The name is historical: the snapping step is what a dithering
     * renderer would approximate with error diffusion; here the mapping is
     * plain nearest-neighbor. The result always has exactly [steps] colors,
     * each one an entry of [palette] (with repeats when the palette is
     * coarser than the ramp).
     *
     * @throws IllegalArgumentException if [steps] < 1.
     */
    fun ditheredGradient(from: Int, to: Int, steps: Int, palette: Palette): List<Int> =
        gradient(from, to, steps).map { color -> palette.colors[palette.findClosest(color)] }

    /**
     * A luminance band through [base] extended all the way to black and
     * white: [steps] shades where the rungs below the base interpolate
     * linearly (in Lab) from [base] toward pure black, and the rungs above
     * interpolate from [base] toward pure white.
     *
     * With `low = (steps−1)/2` rungs below and `high = steps−1−low` rungs
     * above the base:
     *
     * * dark rung j (j = low … 1): `t = j/low`, `L* = L₀·(1−t)`,
     *   `a* = a₀·(1−t)`, `b* = b₀·(1−t)` — at `j = low` the rung is exactly
     *   `#000000`;
     * * the base itself, unchanged;
     * * light rung j (j = 1 … high): `t = j/high`,
     *   `L* = L₀ + (100−L₀)·t`, `a* = a₀·(1−t)`, `b* = b₀·(1−t)` — at
     *   `j = high` the rung is exactly `#FFFFFF`.
     *
     * Chroma tapers toward both extremes — the natural way value bands
     * behave in pixel art (shadows and highlights desaturate) — so unlike
     * [monochromatic] the endpoints are true black and white, and unlike
     * that ladder the band is anchored at the base's own lightness:
     * `steps = 3` yields exactly `[black, base, white]`. For even [steps]
     * the leftover rung is allocated to the light side (`steps = 2` gives
     * `[base, white]`).
     *
     * @throws IllegalArgumentException if [steps] < 1.
     */
    fun luminanceRamp(base: Int, steps: Int): List<Int> {
        require(steps >= 1) { "steps must be at least 1, was $steps" }
        if (steps == 1) return listOf(base)
        val lab = LabColor.fromArgb(base)
        val lowCount = (steps - 1) / 2
        val highCount = steps - 1 - lowCount
        val ramp = ArrayList<Int>(steps)
        for (j in lowCount downTo 1) {
            val t = j.toDouble() / lowCount
            ramp.add(
                LabColor.toArgb(
                    LabColor.Lab(
                        l = lab.l * (1.0 - t),
                        a = lab.a * (1.0 - t),
                        b = lab.b * (1.0 - t),
                    )
                )
            )
        }
        ramp.add(base)
        for (j in 1..highCount) {
            val t = j.toDouble() / highCount
            ramp.add(
                LabColor.toArgb(
                    LabColor.Lab(
                        l = lab.l + (100.0 - lab.l) * t,
                        a = lab.a * (1.0 - t),
                        b = lab.b * (1.0 - t),
                    )
                )
            )
        }
        return ramp
    }

    // ------------------------------------------------------------------
    // Measurement & helpers
    // ------------------------------------------------------------------

    /**
     * Orders [colors] by ascending CIELAB lightness `L*`
     * ([LabColor.lightness]). The sort is stable: colors of equal lightness
     * keep their input order, so re-sorting a sorted list is a no-op.
     */
    fun sortByLuminance(colors: List<Int>): List<Int> =
        colors.sortedBy { LabColor.lightness(it) }

    /**
     * WCAG contrast ratio of [a] against [b]:
     *
     * ```
     * L = 0.2126·R' + 0.7152·G' + 0.0722·B'
     *   with c' = c/255, linearized as
     *   c' ≤ 0.03928 ? c'/12.92 : ((c'+0.055)/1.055)^2.4
     * ratio = (Lmax + 0.05) / (Lmin + 0.05)
     * ```
     *
     * The result lies in `[1, 21]`: 1 means identical colors, 21 is black
     * vs white. Alpha is ignored. WCAG's 0.03928 knee (deliberately kept
     * for spec parity) differs only cosmetically from the sRGB 0.04045
     * used elsewhere in [LabColor].
     */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Suggests an outline (border/edge) color for a sprite filled with
     * [fill]: pixel-art outlines read best when they sit near the value
     * extremes, so the suggestion is [OUTLINE_DARK] (near-black, cool
     * `0x0E0E12`) when the fill's Lab lightness is at least
     * [LIGHTNESS_CUTOFF] (`L* ≥ 50`), and [OUTLINE_LIGHT] (near-white,
     * warm `0xF5F1E8`) below it. Both constants dodge pure `#000000`/
     * `#FFFFFF` to keep anti-aliased edges from disappearing against
     * background extremes.
     */
    fun suggestOutlineColor(fill: Int): Int =
        if (LabColor.lightness(fill) >= LIGHTNESS_CUTOFF) OUTLINE_DARK else OUTLINE_LIGHT

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /** Pi as a Double (avoids `java.lang.Math` in pure-Kotlin code). */
    private const val PI = 3.141592653589793

    /** Round-trip ΔE below which a Lab color counts as inside the sRGB gamut. */
    private const val IN_GAMUT_TOLERANCE = 1.0

    /** Iterations of the chroma-scale binary search (precision 2^-24). */
    private const val GAMUT_SEARCH_STEPS = 24

    /**
     * Projects the Lab color `(l, a, b)` into the sRGB gamut while keeping
     * `L* = l` untouched: if the color survives the [LabColor] round trip
     * it is returned as-is; otherwise the `a*`/`b*` vector is scaled by the
     * largest factor `k ∈ [0, 1]` (binary search, gray axis at `k = 0` is
     * always in gamut) that lands back inside. Lightness is preserved
     * exactly; only chroma yields.
     */
    private fun projectToGamut(l: Double, a: Double, b: Double): Int {
        val requested = LabColor.Lab(l = l, a = a, b = b)
        if (inGamut(requested)) return LabColor.toArgb(requested)
        var lo = 0.0
        var hi = 1.0
        repeat(GAMUT_SEARCH_STEPS) {
            val mid = (lo + hi) / 2.0
            if (inGamut(LabColor.Lab(l = l, a = a * mid, b = b * mid))) lo = mid else hi = mid
        }
        return LabColor.toArgb(LabColor.Lab(l = l, a = a * lo, b = b * lo))
    }

    /**
     * True when [lab] is (essentially) representable in sRGB: converting it
     * to ARGB and back reproduces it within [IN_GAMUT_TOLERANCE] ΔE. The
     * tolerance also absorbs the ≤1-LSB quantization of in-gamut colors.
     */
    private fun inGamut(lab: LabColor.Lab): Boolean =
        LabColor.distance(lab, LabColor.fromArgb(LabColor.toArgb(lab))) <= IN_GAMUT_TOLERANCE

    /**
     * WCAG 2 relative luminance of [argb]: linearized sRGB channels
     * combined with the Rec. 709 primaries' coefficients.
     */
    private fun relativeLuminance(argb: Int): Double {
        val r = wcagChannel(((argb shr 16) and 0xFF) / 255.0)
        val g = wcagChannel(((argb shr 8) and 0xFF) / 255.0)
        val b = wcagChannel((argb and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** WCAG channel linearization (knee at 0.03928). */
    private fun wcagChannel(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}
