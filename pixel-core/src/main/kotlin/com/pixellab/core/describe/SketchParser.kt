package com.pixellab.core.describe

import com.pixellab.core.model.PixelPoint

/**
 * Result of parsing a SKETCH document with [SketchParser].
 *
 * [pixels] and [argbs] are parallel lists holding only the non-transparent
 * cells of the grid, in row-major order: `pixels[i]` is the canvas-space
 * coordinate of the cell and `argbs[i]` its legend color.
 */
data class ParsedSketch(
    /** Grid width in cells (legend excluded). */
    val width: Int,
    /** Grid height in cells (legend excluded). */
    val height: Int,
    /** Non-transparent cell coordinates, row-major. */
    val pixels: List<PixelPoint>,
    /** Colors parallel to [pixels] (same size, same order). */
    val argbs: List<Int>,
) {
    /** Number of non-transparent cells. */
    val pointCount: Int get() = pixels.size

    override fun toString(): String =
        "ParsedSketch(${width}x${height}, $pointCount points)"
}

/**
 * Parser of the SKETCH text format produced by
 * [RegionReader.format]`(_, RegionFormat.SKETCH, _)` — the *write* half of
 * the vision round-trip (read a region → edit the text → draw it back).
 *
 * ## Format
 *
 * ```
 * a=#FFA300            ← legend: one char per color (char=#RRGGBB or
 * b=80FF00E4               char=#AARRGGBB; '.' is reserved for transparent)
 * ---                  ← separator between legend and grid
 * aabb
 * ..ab
 * ```
 *
 * Legend colors follow [ColorCensus.hex]: six hex digits mean opaque
 * (`#RRGGBB` gets `0xFF` alpha prepended), eight digits are a full
 * `#AARRGGBB` value. A legend entry may carry trailing commentary after
 * its hex (the empty-region placeholder line `.=#00000000 (empty region)`
 * round-trips through this rule).
 *
 * Grid rows must all be the same length. `.` cells are transparent and
 * skipped; any other cell must have a legend entry, otherwise the parser
 * rejects the document naming the row and column.
 *
 * ## Limits
 *
 * The parser refuses inputs that could blow up a draw call: grids beyond
 * [MAX_GRID_EDGE] cells on an edge, more than [MAX_LEGEND_ENTRIES] legend
 * entries, or more than [MAX_POINTS] non-transparent cells.
 *
 * Pure and deterministic: no I/O, no locale, no random state.
 */
object SketchParser {

    /** Hard cap on either grid edge (512x512 grid maximum). */
    const val MAX_GRID_EDGE: Int = 512

    /** Hard cap on non-transparent cells in one sketch. */
    const val MAX_POINTS: Int = 65_536

    /** Hard cap on legend entries. */
    const val MAX_LEGEND_ENTRIES: Int = 64

    /** Line separating the legend block from the grid. */
    private const val SEPARATOR = "---"

    /** Optional prefix a legend line may carry (`legend:` in hand-written docs). */
    private const val LEGEND_PREFIX = "legend:"

    /** Line terminators accepted on input (Windows, old-Mac and Unix). */
    private val LINE_BREAKS = Regex("\r\n|\r|\n")

    /**
     * Parses [text] into a [ParsedSketch].
     *
     * @throws IllegalArgumentException on any structural violation — missing
     *   separator, malformed/duplicate legend entries, ragged grid rows,
     *   grid cells with no legend entry, or any of the [MAX_GRID_EDGE] /
     *   [MAX_LEGEND_ENTRIES] / [MAX_POINTS] limits being exceeded. Messages
     *   name the offending line/row/column so an agent can fix the text.
     */
    fun parse(text: String): ParsedSketch {
        val lines = splitLines(text)
        require(lines.isNotEmpty()) { "sketch text is empty" }
        val separatorIndex = lines.indexOfFirst { it.trim() == SEPARATOR }
        require(separatorIndex >= 0) {
            "sketch is missing the '---' separator between legend and grid"
        }
        val legend = parseLegend(lines.subList(0, separatorIndex))
        val gridRows = parseGrid(lines.subList(separatorIndex + 1, lines.size))
        return collectPoints(legend, gridRows)
    }

    // ------------------------------------------------------------------
    // Legend
    // ------------------------------------------------------------------

    /** Char-to-color map built from the legend lines above the separator. */
    private fun parseLegend(rawLines: List<String>): Map<Char, Int> {
        val legend = LinkedHashMap<Char, Int>()
        for ((index, raw) in rawLines.withIndex()) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            val lower = line.lowercase()
            if (lower.startsWith(LEGEND_PREFIX)) {
                line = line.substring(LEGEND_PREFIX.length).trim()
            }
            val lineNumber = index + 1
            val eq = line.indexOf('=')
            require(eq == 1) {
                "legend line $lineNumber: expected 'C=#hex' (one char, '=' and a color), got '$raw'"
            }
            val ch = line[0]
            val colorText = line.substring(2).trim().split(Regex("\\s+"))[0]
            val color = parseLegendColor(colorText, lineNumber)
            if (ch == '.') {
                // '.' is the reserved transparent marker; a legend entry
                // mapping it to a *transparent* color is the writer's
                // empty-region placeholder and is simply ignored, while an
                // opaque mapping would contradict the grid semantics.
                require(color ushr 24 == 0) {
                    "legend line $lineNumber: '.' always means transparent and cannot map to an opaque color"
                }
                continue
            }
            require(legend.put(ch, color) == null) {
                "legend line $lineNumber: duplicate entry for '$ch' (was ${hexOf(legend[ch]!!)}, " +
                    "now ${hexOf(color)})"
            }
            require(legend.size <= MAX_LEGEND_ENTRIES) {
                "legend has ${legend.size} entries, beyond the $MAX_LEGEND_ENTRIES cap"
            }
        }
        return legend
    }

    /** `#RRGGBB` (padded to opaque) or `#AARRGGBB` (alpha kept verbatim). */
    private fun parseLegendColor(token: String, lineNumber: Int): Int {
        val digits = token.removePrefix("#").lowercase()
        val padded = when (digits.length) {
            6 -> "ff$digits"
            8 -> digits
            else -> throw IllegalArgumentException(
                "legend line $lineNumber: color '$token' must be #RRGGBB or #AARRGGBB",
            )
        }
        val value = padded.toLongOrNull(16)
            ?: throw IllegalArgumentException(
                "legend line $lineNumber: color '$token' contains non-hex characters",
            )
        // No alpha OR-ing here (unlike the MCP color params): the SKETCH
        // legend must round-trip semi-transparent colors byte-exact.
        return value.toInt()
    }

    // ------------------------------------------------------------------
    // Grid
    // ------------------------------------------------------------------

    /** Grid rows below the separator: uniform length, within the edge cap. */
    private fun parseGrid(rawRows: List<String>): List<String> {
        require(rawRows.isNotEmpty()) { "sketch has no grid rows after the '---' separator" }
        var end = rawRows.size
        while (end > 1 && rawRows[end - 1].isBlank()) end--
        val rows = rawRows.subList(0, end)
        // Rows are kept verbatim (no trailing trim): a stray space is NOT
        // '.' and must fail the legend lookup instead of silently vanishing.
        require(rows.isNotEmpty() && rows[0].isNotEmpty()) {
            "sketch has no grid rows after the '---' separator"
        }
        val width = rows[0].length
        for ((index, row) in rows.withIndex()) {
            require(row.length == width) {
                "grid row $index has ${row.length} cells, expected $width (rows must share one length)"
            }
        }
        val height = rows.size
        require(width in 1..MAX_GRID_EDGE && height in 1..MAX_GRID_EDGE) {
            "sketch grid is ${width}x${height}, beyond the ${MAX_GRID_EDGE}x${MAX_GRID_EDGE} cap"
        }
        return rows
    }

    // ------------------------------------------------------------------
    // Point collection
    // ------------------------------------------------------------------

    /** Folds the grid into the parallel point/color lists. */
    private fun collectPoints(legend: Map<Char, Int>, rows: List<String>): ParsedSketch {
        val pixels = ArrayList<PixelPoint>()
        val argbs = ArrayList<Int>()
        for ((y, row) in rows.withIndex()) {
            for ((x, cell) in row.withIndex()) {
                if (cell == '.') continue
                val color = legend[cell]
                    ?: throw IllegalArgumentException(
                        "grid row $y, col $x: '$cell' has no legend entry — add '$cell=#hex' " +
                            "or use '.' for transparent",
                    )
                if (color ushr 24 == 0) continue // legend-mapped but fully transparent
                pixels.add(PixelPoint(x, y))
                argbs.add(color)
                require(pixels.size <= MAX_POINTS) {
                    "sketch has more than $MAX_POINTS non-transparent cells"
                }
            }
        }
        return ParsedSketch(rows[0].length, rows.size, pixels, argbs)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Splits on CRLF/CR/LF and drops trailing blank lines. */
    private fun splitLines(text: String): List<String> {
        val raw = text.split(LINE_BREAKS)
        var end = raw.size
        while (end > 1 && raw[end - 1].isBlank()) end--
        return raw.subList(0, end)
    }

    /** `#RRGGBB`/`#AARRGGBB` rendering used in error messages. */
    private fun hexOf(argb: Int): String = ColorCensus.hex(argb)
}
