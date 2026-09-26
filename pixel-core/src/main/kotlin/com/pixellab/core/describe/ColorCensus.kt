package com.pixellab.core.describe

import com.pixellab.core.color.ColorNamer
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame

/**
 * One exact-ARGB color occurrence tally produced by [ColorCensus].
 *
 * The [name] is the CSS nearest-match label (via [ColorNamer]); [family]
 * buckets the color into a coarse perceptual group used by natural-language
 * descriptions ("mostly warm reds and oranges").
 */
class ColorTally(
    /** The exact ARGB pixel value this row counts. */
    val argb: Int,
    /** Number of pixels with exactly this value. */
    val count: Int,
    /** Nearest CSS color name (transparent pixels get `"transparent"`). */
    val name: String,
    /** Coarse perceptual family (e.g. `"red"`, `"cyan"`, `"gray"`). */
    val family: String,
) {
    /** `#RRGGBB` hex form (alpha omitted when opaque; `#AARRGGBB` otherwise). */
    val hex: String get() = ColorCensus.hex(argb)

    override fun toString(): String = "$hex x$count ($name)"
}

/**
 * Aggregate color-family summary ("red": 34 px across 2 shades).
 */
class FamilyTally(
    /** Family label, one of [ColorCensus.FAMILIES]. */
    val family: String,
    /** Total pixels in the family. */
    val pixels: Int,
    /** Number of distinct exact shades contributing. */
    val shades: Int,
) {
    override fun toString(): String = "$family=$pixels px / $shades shades"
}

/**
 * Result of a full color census over one frame.
 */
class ColorCensusResult(
    /** Frame the census ran on. */
    val frame: PixelFrame,
    /** Exact-color tallies sorted by descending count, then ascending ARGB. */
    val tallies: List<ColorTally>,
    /** Family aggregates sorted by descending pixels. */
    val families: List<FamilyTally>,
    /** Number of fully-transparent pixels (alpha == 0). */
    val transparentPixels: Int,
    /** Number of semi-transparent pixels (0 < alpha < 255). */
    val semiTransparentPixels: Int,
    /** Number of distinct exact colors (transparent excluded). */
    val uniqueColors: Int,
) {
    /** Total pixels of the frame. */
    val total: Int get() = frame.pixelCount

    /** Opaque + semi-transparent pixel count (everything visible). */
    val visiblePixels: Int get() = total - transparentPixels

    /** Share of visible pixels, 0.0..1.0. */
    val occupancy: Double get() = if (total == 0) 0.0 else visiblePixels.toDouble() / total

    /** The most frequent visible color, or null for a fully transparent frame. */
    val dominant: ColorTally? get() = tallies.firstOrNull()

    override fun toString(): String =
        "ColorCensus(${uniqueColors} colors, ${(occupancy * 100).toInt()}% occupied)"
}

/**
 * Color census for pixel-art canvases: exact-ARGB histogram with CSS
 * nearest-match naming, coarse family bucketing and transparency accounting.
 *
 * This is the "what colors am I using" half of agent vision. It is pure and
 * deterministic: two identical frames always produce identical censuses.
 * Semi-transparent pixels are tallied exactly like opaque ones (their alpha
 * is reported via [ColorCensusResult.semiTransparentPixels]) because the
 * census describes storage, not compositing.
 */
object ColorCensus {

    /** Maximum distinct colors kept in [census] output; the tail is summarized by family. */
    const val MAX_TALLIES: Int = 64

    /** Coarse hue/lightness families used for bucketed summaries. */
    val FAMILIES: List<String> = listOf(
        "red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta",
        "brown", "pink", "gray", "black", "white", "transparent",
    )

    /**
     * Runs the census over [frame]. Transparent pixels (alpha == 0) are
     * excluded from the tally list but reported through
     * [ColorCensusResult.transparentPixels]; every other pixel — including
     * semi-transparent ones — contributes its exact ARGB value.
     */
    fun census(frame: PixelFrame): ColorCensusResult {
        val counts = HashMap<Int, Int>(minOf(256, frame.pixelCount.coerceAtLeast(1)))
        var transparent = 0
        var semi = 0
        for (p in frame.pixels) {
            val a = p ushr 24
            if (a == 0) {
                transparent++
                continue
            }
            if (a != 0xFF) semi++
            counts.merge(p, 1, Int::plus)
        }
        val tallies = counts.entries
            .map { (argb, count) ->
                ColorTally(argb, count, ColorNamer.nearestName(argb).name, familyOf(argb))
            }
            .sortedWith(compareByDescending<ColorTally> { it.count }.thenBy { it.argb })
        val familyAgg = LinkedHashMap<String, IntArray>() // [pixels, shades]
        for (t in tallies) {
            val agg = familyAgg.getOrPut(t.family) { IntArray(2) }
            agg[0] += t.count
            agg[1] += 1
        }
        val families = familyAgg.entries
            .map { (family, agg) -> FamilyTally(family, agg[0], agg[1]) }
            .sortedWith(compareByDescending<FamilyTally> { it.pixels }.thenBy { it.family })
        return ColorCensusResult(
            frame = frame,
            tallies = tallies.take(MAX_TALLIES),
            families = families,
            transparentPixels = transparent,
            semiTransparentPixels = semi,
            uniqueColors = tallies.size,
        )
    }

    /**
     * Palette-coverage companion to [census]: for every palette entry reports
     * how many distinct canvas colors snapped to it (Lab ΔE ≤ [tolerance])
     * and counts canvas colors that fell outside the palette entirely.
     */
    fun coverage(result: ColorCensusResult, palette: Palette, tolerance: Double = 10.0): PaletteCoverage {
        require(tolerance >= 0.0) { "tolerance must be >= 0 (was $tolerance)" }
        val perEntryShades = IntArray(palette.size)
        val perEntryPixels = IntArray(palette.size)
        var orphanShades = 0
        val orphanHexes = ArrayList<String>(8)
        for (t in result.tallies) {
            var best = -1
            var bestDist = Double.MAX_VALUE
            for (i in palette.colors.indices) {
                val d = LabDistanceCache.distance(t.argb, palette.colors[i])
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
            if (best >= 0 && bestDist <= tolerance) {
                perEntryShades[best] += 1
                perEntryPixels[best] += t.count
            } else {
                orphanShades += 1
                if (orphanHexes.size < 8) orphanHexes.add(t.hex)
            }
        }
        return PaletteCoverage(
            paletteId = palette.id,
            paletteSize = palette.size,
            matchedShades = perEntryShades,
            matchedPixels = perEntryPixels,
            usedEntries = perEntryShades.count { it > 0 },
            orphanShades = orphanShades,
            orphanExamples = orphanHexes,
        )
    }

    /**
     * Classifies an ARGB pixel into one coarse family. Transparent pixels
     * (alpha == 0) are `"transparent"`; low-chroma colors split into
     * black/white/gray by lightness; everything else buckets by hue with
     * calibrated cut points (orange sits 20..45°, brown is dark orange,
     * pink is light red / low-saturation magenta).
     */
    fun familyOf(argb: Int): String {
        val a = argb ushr 24
        if (a == 0) return "transparent"
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2.0
        val chroma = max - min
        if (chroma <= 16) {
            return when {
                l < 48 -> "black"
                l >= 216 -> "white"
                else -> "gray"
            }
        }
        val hue = hueDegrees(r, g, b, max, chroma)
        val sat = chroma.toDouble() / max
        return when {
            hue < 12 -> if (l < 110 && sat < 0.75) "brown" else "red"
            hue < 46 -> if (l < 110) "brown" else "orange"
            hue < 66 -> "yellow"
            hue < 160 -> "green"
            hue < 200 -> "cyan"
            hue < 258 -> "blue"
            hue < 300 -> if (l >= 170 && sat < 0.6) "pink" else "purple"
            hue < 348 -> if (l >= 170) "pink" else "magenta"
            else -> if (l < 110 && sat < 0.75) "brown" else "red"
        }
    }

    /**
     * Compact hex label: `#RRGGBB` for opaque pixels, 8-digit
     * `#AARRGGBB` otherwise (transparent black stays distinguishable from
     * opaque black).
     */
    fun hex(argb: Int): String {
        val a = argb ushr 24
        return if (a == 0xFF) {
            "#%06x".format(argb and 0xFFFFFF)
        } else {
            "#%08x".format(argb)
        }
    }

    /** Hue in degrees (0..360) for an RGB triple with precomputed max/chroma. */
    internal fun hueDegrees(r: Int, g: Int, b: Int, max: Int, chroma: Int): Double {
        val segment = when (max) {
            r -> ((g - b).toDouble() / chroma).let { if (it < 0) it + 6 else it }
            g -> (b - r).toDouble() / chroma + 2
            else -> (r - g).toDouble() / chroma + 4
        }
        return 60.0 * segment
    }
}

/**
 * How well a frame's colors snap onto a palette (see [ColorCensus.coverage]).
 */
class PaletteCoverage(
    /** Palette id the coverage was computed against. */
    val paletteId: String,
    /** Number of entries in the palette. */
    val paletteSize: Int,
    /** Distinct canvas shades snapped per palette entry (parallel to entry order). */
    val matchedShades: IntArray,
    /** Total pixels snapped per palette entry. */
    val matchedPixels: IntArray,
    /** Number of palette entries that matched at least one shade. */
    val usedEntries: Int,
    /** Canvas shades that matched no palette entry within tolerance. */
    val orphanShades: Int,
    /** Up to 8 example orphan hex values, most-frequent first. */
    val orphanExamples: List<String>,
) {
    override fun toString(): String =
        "PaletteCoverage($paletteId: $usedEntries/$paletteSize entries used, $orphanShades orphans)"
}

/**
 * Memoized Lab distance: census + coverage ask for the same color pairs
 * repeatedly on pixel-art canvases, where few distinct colors dominate.
 * Bounded by the distinct-color pair count (≤ 64² after tally truncation).
 */
internal object LabDistanceCache {
    private const val MAX_ENTRIES = 16_384

    private val cache = HashMap<Long, Double>(1024)

    fun distance(a: Int, b: Int): Double {
        if (a == b) return 0.0
        val key = (a.toLong() and 0xFFFFFFFFL) shl 32 or (b.toLong() and 0xFFFFFFFFL)
        synchronized(cache) {
            cache[key]?.let { return it }
            val d = com.pixellab.core.palette.LabColor.distance(a, b)
            if (cache.size < MAX_ENTRIES) cache[key] = d
            return d
        }
    }

    /** Test hook: drops the memo table. */
    fun clear() = synchronized(cache) { cache.clear() }
}
