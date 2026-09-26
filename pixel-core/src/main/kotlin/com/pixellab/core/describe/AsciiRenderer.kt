package com.pixellab.core.describe

import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Character-set style for [AsciiRenderer] output.
 */
enum class AsciiStyle {
    /**
     * Every distinct visible color gets its own character, assigned in
     * most-frequent-first order from `"ABCDEFGHIJKLMNOPQRSTUVWX"` (the 24
     * most frequent colors; rarer colors reuse characters round-robin).
     * Transparent pixels render as `.`. Best all-round mode for agents:
     * shape *and* color-region structure survive the round trip.
     */
    LETTERS,

    /**
     * Luminance ramp `" .:-=+*#%@"` — good for reading shapes and shading,
     * loses color identity. Transparent pixels render as spaces.
     */
    SHADES,

    /**
     * Two-row packing with Unicode half blocks (`▀` upper, `▄` lower,
     * `█` both, ` ` neither) — halves the line count for tall canvases
     * while preserving per-pixel resolution. Falls back to `#`/` `
     * when [AsciiRenderOptions.asciiOnly] is set.
     */
    BLOCKS,
}

/**
 * Tuning knobs for one [AsciiRenderer.render] pass.
 */
class AsciiRenderOptions(
    /** Target output width in characters; the frame is box-sampled down to it. */
    val maxWidth: Int = 64,
    /** Character for fully-transparent pixels (LETTERS mode). */
    val transparentChar: Char = '.',
    /** Force 7-bit ASCII output (BLOCKS mode degrades to `#`/space). */
    val asciiOnly: Boolean = false,
    /** Invert the SHADES ramp (dark canvas on light background). */
    val invertShades: Boolean = false,
) {
    init {
        require(maxWidth in 4..256) { "maxWidth must be in 4..256 (was $maxWidth)" }
    }
}

/**
 * One ASCII rendering result: the art itself plus everything an agent
 * needs to interpret it.
 */
class AsciiRender(
    /** The rendered art, one string per row (no trailing newlines). */
    val rows: List<String>,
    /** Canvas width the art represents (post-sampling canvas columns). */
    val width: Int,
    /** Canvas height the art represents (post-sampling canvas rows). */
    val height: Int,
    /** Horizontal sampling stride applied to the source frame (1 = none). */
    val strideX: Int,
    /** Vertical sampling stride applied to the source frame (1 = none). */
    val strideY: Int,
    /** Legend entries (char → color) in assignment order; LETTERS mode only. */
    val legend: List<AsciiLegendEntry>,
    /** The style that produced this render. */
    val style: AsciiStyle,
) {
    /** The art joined with newlines — the value MCP inlines into tool output. */
    val art: String get() = rows.joinToString("\n")

    override fun toString(): String = "AsciiRender(${width}x${height}, ${rows.size} rows, ${legend.size} legend)"
}

/**
 * One legend row: what one character means on the canvas.
 */
class AsciiLegendEntry(
    /** The character as it appears in the art. */
    val char: Char,
    /** Hex form of the color it stands for. */
    val hex: String,
    /** Nearest CSS name of the color. */
    val name: String,
    /** Number of sampled cells mapped to this character. */
    val cells: Int,
)

/**
 * Renders [PixelFrame]s as agent-readable ASCII art.
 *
 * This is the primary "eyes" of an MCP agent: a 16×16 sprite becomes 16
 * short lines it can quote, reason about and diff in its next prompt.
 * Three styles cover the trade-off space:
 *  * [AsciiStyle.LETTERS] — color-aware, the default for canvases with a
 *    modest distinct-color count (the pixel-art norm),
 *  * [AsciiStyle.SHADES] — shape-first view for busy canvases,
 *  * [AsciiStyle.BLOCKS] — compact two-rows-per-line view.
 *
 * Downscaling is box sampling (majority color per cell for LETTERS, mean
 * luminance for SHADES/BLOCKS), so art stays representative rather than
 * aliased. Rendering is pure and deterministic.
 */
object AsciiRenderer {

    /** LETTERS-mode alphabet; position 0 goes to the most frequent color. */
    const val LETTER_ALPHABET: String = "ABCDEFGHIJKLMNOPQRSTUVWX"

    /** Luminance ramp from darkest to brightest (SHADES mode). */
    const val SHADE_RAMP: String = " .:-=+*#%@"

    /**
     * Renders [frame] with the given [style] and [options]. When [palette]
     * is non-null and the style is [AsciiStyle.LETTERS], colors snap to the
     * palette first (Lab nearest match) so legend characters line up with
     * palette entries and re-feeding the art to a sketch tool round-trips.
     */
    fun render(
        frame: PixelFrame,
        style: AsciiStyle = AsciiStyle.LETTERS,
        options: AsciiRenderOptions = AsciiRenderOptions(),
        palette: Palette? = null,
    ): AsciiRender {
        val strideX = max(1, (frame.width + options.maxWidth - 1) / options.maxWidth)
        val strideY = strideX // square sampling cells keep aspect ratio honest
        val sampledW = (frame.width + strideX - 1) / strideX
        val sampledH = (frame.height + strideY - 1) / strideY

        return when (style) {
            AsciiStyle.LETTERS -> renderLetters(frame, options, palette, strideX, strideY, sampledW, sampledH)
            AsciiStyle.SHADES -> renderShades(frame, options, strideX, strideY, sampledW, sampledH)
            AsciiStyle.BLOCKS -> renderBlocks(frame, options, strideX, strideY, sampledW, sampledH)
        }
    }

    // ── LETTERS ─────────────────────────────────────────────────────────────

    private fun renderLetters(
        frame: PixelFrame,
        options: AsciiRenderOptions,
        palette: Palette?,
        strideX: Int,
        strideY: Int,
        sampledW: Int,
        sampledH: Int,
    ): AsciiRender {
        // Box-sample: each cell is the majority non-transparent color (ties
        // broken by lower ARGB for determinism); fully-transparent cells
        // stay transparent.
        val cells = sampleMajority(frame, strideX, strideY, sampledW, sampledH)
        val counts = HashMap<Int, Int>(32)
        for (c in cells) if (c != 0) counts.merge(c, 1, Int::plus)
        val ordered = counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        val charOf = HashMap<Int, Char>(ordered.size)
        val legend = ArrayList<AsciiLegendEntry>(ordered.size)
        for ((rank, color) in ordered.withIndex()) {
            val ch = if (rank < LETTER_ALPHABET.length) {
                LETTER_ALPHABET[rank]
            } else {
                // More than 24 distinct sampled colors: recycle, but legend
                // stays truthful by listing the dominant mapping only.
                LETTER_ALPHABET[rank % LETTER_ALPHABET.length]
            }
            charOf[color] = ch
            // Legend colors may snap to the palette (Lab nearest, ΔE ≤ 24)
            // so characters line up with palette entries; unsnapped colors
            // keep their exact canvas hex. Character assignment itself is
            // independent of snapping.
            val legendColor = palette?.let { snapToPalette(color, it) } ?: color
            legend.add(
                AsciiLegendEntry(
                    char = ch,
                    hex = ColorCensus.hex(legendColor),
                    name = if (legendColor != color) {
                        paletteLabel(legendColor, palette!!)
                    } else {
                        com.pixellab.core.color.ColorNamer.nearestName(color).name
                    },
                    cells = counts[color] ?: 0,
                ),
            )
        }
        val rows = ArrayList<String>(sampledH)
        for (y in 0 until sampledH) {
            val sb = StringBuilder(sampledW)
            for (x in 0 until sampledW) {
                val c = cells[y * sampledW + x]
                sb.append(if (c == 0) options.transparentChar else charOf[c] ?: '?')
            }
            rows.add(sb.toString())
        }
        return AsciiRender(rows, sampledW, sampledH, strideX, strideY, legend, AsciiStyle.LETTERS)
    }

    // ── SHADES ──────────────────────────────────────────────────────────────

    private fun renderShades(
        frame: PixelFrame,
        options: AsciiRenderOptions,
        strideX: Int,
        strideY: Int,
        sampledW: Int,
        sampledH: Int,
    ): AsciiRender {
        val rows = ArrayList<String>(sampledH)
        for (y in 0 until sampledH) {
            val sb = StringBuilder(sampledW)
            for (x in 0 until sampledW) {
                val lum = meanLuminance(frame, x * strideX, y * strideY, strideX, strideY)
                sb.append(shadeOf(lum, options))
            }
            rows.add(sb.toString())
        }
        return AsciiRender(rows, sampledW, sampledH, strideX, strideY, emptyList(), AsciiStyle.SHADES)
    }

    // ── BLOCKS ──────────────────────────────────────────────────────────────

    private fun renderBlocks(
        frame: PixelFrame,
        options: AsciiRenderOptions,
        strideX: Int,
        strideY: Int,
        sampledW: Int,
        sampledH: Int,
    ): AsciiRender {
        // Half-block mode is a *shape* view: a sampled cell counts as filled
        // when the majority of its pixels are visible (alpha != 0) — a
        // luminance threshold would misclassify black pixels as empty.
        val rows = ArrayList<String>((sampledH + 1) / 2)
        var y = 0
        while (y < sampledH) {
            val sb = StringBuilder(sampledW)
            for (x in 0 until sampledW) {
                val top = majorityVisible(frame, x * strideX, y * strideY, strideX, strideY)
                val bottom = if (y + 1 < sampledH) {
                    majorityVisible(frame, x * strideX, (y + 1) * strideY, strideX, strideY)
                } else {
                    false
                }
                sb.append(blockOf(top, bottom, options))
            }
            rows.add(sb.toString())
            y += 2
        }
        return AsciiRender(rows, sampledW, sampledH, strideX, strideY, emptyList(), AsciiStyle.BLOCKS)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Majority non-transparent color per sampled cell; 0 when fully transparent. */
    internal fun sampleMajority(
        frame: PixelFrame,
        strideX: Int,
        strideY: Int,
        sampledW: Int,
        sampledH: Int,
    ): IntArray {
        val cells = IntArray(sampledW * sampledH)
        for (cy in 0 until sampledH) {
            for (cx in 0 until sampledW) {
                val x0 = cx * strideX
                val y0 = cy * strideY
                val x1 = min(frame.width, x0 + strideX)
                val y1 = min(frame.height, y0 + strideY)
                val votes = HashMap<Int, Int>(8)
                for (y in y0 until y1) {
                    val row = y * frame.width
                    for (x in x0 until x1) {
                        val p = frame.pixels[row + x]
                        if (p ushr 24 != 0) votes.merge(p, 1, Int::plus)
                    }
                }
                cells[cy * sampledW + cx] = votes.entries
                    .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key ?: 0
            }
        }
        return cells
    }

    /**
     * Mean rec-601 luminance (0..255) of one sampling box over its *visible*
     * pixels. A box with no visible pixels returns **-1**, the transparent
     * sentinel [shadeOf] renders as `' '`.
     */
    private fun meanLuminance(frame: PixelFrame, x0: Int, y0: Int, strideX: Int, strideY: Int): Double {
        val x1 = min(frame.width, x0 + strideX)
        val y1 = min(frame.height, y0 + strideY)
        var sum = 0L
        var n = 0
        for (y in y0 until y1) {
            val row = y * frame.width
            for (x in x0 until x1) {
                val p = frame.pixels[row + x]
                if (p ushr 24 != 0) {
                    sum += (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    n++
                }
            }
        }
        return if (n == 0) -1.0 else sum.toDouble() / n
    }

    /** True when the majority of a box's pixels are visible (alpha != 0). */
    private fun majorityVisible(frame: PixelFrame, x0: Int, y0: Int, strideX: Int, strideY: Int): Boolean {
        val x1 = min(frame.width, x0 + strideX)
        val y1 = min(frame.height, y0 + strideY)
        var visible = 0
        var total = 0
        for (y in y0 until y1) {
            val row = y * frame.width
            for (x in x0 until x1) {
                if (frame.pixels[row + x] ushr 24 != 0) visible++
                total++
            }
        }
        return total > 0 && visible * 2 >= total
    }

    /**
     * Ramp character for a luminance value. A **negative** luminance marks
     * a fully-transparent cell and renders as `' '`; visible colors map to
     * ramp slots 1..9 (`.` darkest … `%` brightest) so black stays
     * distinguishable from transparent.
     */
    internal fun shadeOf(luminance: Double, options: AsciiRenderOptions): Char {
        if (luminance < 0) return ' '
        val t = (luminance / 255.0).let { if (options.invertShades) 1.0 - it else it }
        val idx = (1 + t * (SHADE_RAMP.length - 2)).roundToInt().coerceIn(1, SHADE_RAMP.length - 1)
        return SHADE_RAMP[idx]
    }

    /** Half-block character for a top/bottom visibility pair. */
    internal fun blockOf(topVisible: Boolean, bottomVisible: Boolean, options: AsciiRenderOptions): Char {
        if (options.asciiOnly) {
            return when {
                topVisible && bottomVisible -> '#'
                topVisible -> '^'
                bottomVisible -> '_'
                else -> ' '
            }
        }
        return when {
            topVisible && bottomVisible -> '█'
            topVisible -> '▀'
            bottomVisible -> '▄'
            else -> ' '
        }
    }

    /** Lab-nearest palette entry for [argb], or null when [palette] is null. */
    internal fun snapToPalette(argb: Int, palette: Palette): Int? {
        var best = palette.colors[0]
        var bestDist = Double.MAX_VALUE
        for (c in palette.colors) {
            val d = LabDistanceCache.distance(argb, c)
            if (d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return if (bestDist <= 24.0) best else null
    }

    private fun paletteLabel(argb: Int, palette: Palette): String {
        val idx = palette.colors.indexOfFirst { it == argb }
        val base = com.pixellab.core.color.ColorNamer.nearestName(argb).name
        return if (idx >= 0) "$base [${palette.id}#$idx]" else base
    }

}
