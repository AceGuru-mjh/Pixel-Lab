package com.pixellab.core.color

import kotlin.math.pow

/**
 * WCAG 2.x contrast analysis for palettes and sprite colors.
 *
 * The contrast ratio of two colors is
 * `(L1 + 0.05) / (L2 + 0.05)` where `L1 ≥ L2` are the WCAG *relative
 * luminance* values (linearized sRGB with the Rec.709 weights).
 * The WCAG 2.1 thresholds:
 *
 * | Level | Normal text | Large text / UI |
 * |-------|-------------|-----------------|
 * | AA    | 4.5 : 1     | 3 : 1           |
 * | AAA   | 7 : 1       | 4.5 : 1         |
 */
object ContrastAudit {

    /** WCAG conformance level. */
    enum class Level(val normalTextRatio: Double, val largeTextRatio: Double) {
        AA(4.5, 3.0),
        AAA(7.0, 4.5),
    }

    /**
     * WCAG relative luminance of an ARGB pixel (0..1, linear).
     * Transparent pixels are treated as black (luminance 0) — the
     * convention used when text sits on unknown backdrops.
     */
    fun relativeLuminance(argb: Int): Double {
        val a = argb ushr 24
        if (a == 0) return 0.0
        val r = linearize(argb ushr 16 and 0xFF)
        val g = linearize(argb ushr 8 and 0xFF)
        val b = linearize(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Contrast ratio between two colors (≥ 1.0; identical colors → 1.0,
     * black vs white → 21.0).
     */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Whether the pair meets [level] for [large] (large text / UI
     * components) or normal text.
     */
    fun passes(a: Int, b: Int, level: Level = Level.AA, large: Boolean = false): Boolean {
        val threshold = if (large) level.largeTextRatio else level.normalTextRatio
        return contrastRatio(a, b) >= threshold
    }

    /**
     * The lowest level the pair satisfies (or null below AA for normal
     * text AND below AA for large text — i.e. strictly unusable).
     */
    fun conformance(a: Int, b: Int, large: Boolean = false): Level? {
        val ratio = contrastRatio(a, b)
        val threshold = if (large) Level.AA.largeTextRatio else Level.AA.normalTextRatio
        val aaaThreshold = if (large) Level.AAA.largeTextRatio else Level.AAA.normalTextRatio
        return when {
            ratio >= aaaThreshold -> Level.AAA
            ratio >= threshold -> Level.AA
            else -> null
        }
    }

    /**
     * Audits a palette's internal contrast matrix: every unordered pair,
     * sorted worst-first (smallest ratio first) so the top of the list is
     * the most confusable pair — exactly what a designer needs to see.
     *
     * Pairs below [Level.AA] for normal text are flagged in [report]'s
     * failing set.
     */
    fun auditPalette(colors: IntArray, level: Level = Level.AA): PaletteContrastReport {
        require(colors.isNotEmpty()) { "Palette must not be empty" }
        val pairs = ArrayList<PairContrast>(colors.size * (colors.size - 1) / 2)
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) {
                val ratio = contrastRatio(colors[i], colors[j])
                pairs.add(PairContrast(i, j, colors[i], colors[j], ratio))
            }
        }
        pairs.sortBy { it.ratio }
        val threshold = level.normalTextRatio
        val failing = pairs.filter { it.ratio < threshold }
        return PaletteContrastReport(pairs, failing, colors.size, level)
    }

    /**
     * Suggests a replacement for [color] that reaches at least
     * [targetRatio] against [against], staying as close as possible to
     * the original color.
     *
     * Strategy: binary-search the lightness shift (applied in linear
     * space to all channels uniformly) in the direction away from
     * [against]'s luminance. Preserves hue exactly, so a brand color
     * keeps its identity while becoming readable.
     *
     * Returns null when even pure white/black cannot reach the target
     * (impossible for the standard 21:1 ceiling but guarded anyway).
     */
    fun suggestAccessible(color: Int, against: Int, targetRatio: Double = Level.AA.normalTextRatio): Int? {
        if (contrastRatio(color, against) >= targetRatio) return color
        val againstLum = relativeLuminance(against)
        // Which direction is "away"? Shift toward the extreme luminance
        // opposite to the backdrop.
        val goLighter = againstLum < 0.5
        val a = color ushr 24
        var lo = 0.0
        var hi = 1.0
        var best: Int? = null
        repeat(24) {
            val mid = (lo + hi) / 2.0
            val candidate = shiftLuminance(color, mid, goLighter)
            if (contrastRatio(candidate, against) >= targetRatio) {
                best = candidate
                hi = mid // try a smaller shift first
            } else {
                lo = mid
            }
        }
        if (best != null) return best
        // Fall back to the extreme end.
        val extreme = if (goLighter) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        return if (contrastRatio(extreme, against) >= targetRatio) extreme else null
    }

    /**
     * Shifts a color's luminance by mixing toward white (`towardLight`)
     * or black with fraction `f` in linear space.
     */
    private fun shiftLuminance(argb: Int, f: Double, towardLight: Boolean): Int {
        val a = argb ushr 24
        val r = linearize(argb ushr 16 and 0xFF)
        val g = linearize(argb ushr 8 and 0xFF)
        val b = linearize(argb and 0xFF)
        val t = f.coerceIn(0.0, 1.0)
        val mix = { c: Double -> if (towardLight) c + (1.0 - c) * t else c * (1.0 - t) }
        return a shl 24 or
            (delinearize(mix(r)) shl 16) or
            (delinearize(mix(g)) shl 8) or
            delinearize(mix(b))
    }

    private fun linearize(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun delinearize(v: Double): Int {
        val c = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055
        return (c * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }
}

/** One audited color pair. */
class PairContrast(
    val indexA: Int,
    val indexB: Int,
    val colorA: Int,
    val colorB: Int,
    /** Contrast ratio, ≥ 1.0. */
    val ratio: Double,
) {
    override fun toString(): String =
        "Pair(#%06X vs #%06X) = ${"%.2f".format(ratio)}:1".format(colorA and 0xFFFFFF, colorB and 0xFFFFFF)
}

/** Full palette audit result. */
class PaletteContrastReport(
    /** All pairs, sorted ascending by ratio (most confusable first). */
    val pairs: List<PairContrast>,
    /** Pairs below the audit level's normal-text threshold. */
    val failing: List<PairContrast>,
    val paletteSize: Int,
    val level: ContrastAudit.Level,
) {
    /** True when every pair passes. */
    val allPass: Boolean get() = failing.isEmpty()

    /** The most confusable pair, or null for a single-color palette. */
    val worstPair: PairContrast? get() = pairs.firstOrNull()

    override fun toString(): String =
        "PaletteContrastReport(size=$paletteSize, failing=${failing.size}/${pairs.size}, worst=${worstPair?.let { "${"%.2f".format(it.ratio)}:1" } ?: "n/a"})"
}
