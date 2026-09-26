package com.pixellab.core.describe

import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame
import kotlin.math.max
import kotlin.math.min

/**
 * Output format of a [RegionReader.read] pass.
 */
enum class RegionFormat {
    /** Grid of `#RRGGBB` / `#AARRGGBB` strings, row-major. */
    HEX,

    /**
     * Grid of palette indices (after Lab nearest-match snapping); cells that
     * snap to no palette entry within tolerance report `-1`.
     */
    PALETTE_INDEX,

    /**
     * One line per row in run-length form `count:hex`, e.g.
     * `"4:#87ceeb,2:#ff0000"` — compact for wide uniform strips.
     */
    RLE,

    /**
     * Chars per [AsciiRenderer]'s LETTERS mapping plus a legend — the exact
     * input format [com.pixellab.core.skill.SketchParser] accepts, so an
     * agent can read a region, tweak the text and draw it back.
     */
    SKETCH,
}

/**
 * A clamped rectangular region request. Coordinates may exceed the frame;
 * out-of-frame cells read as transparent (`0x00000000`).
 */
class RegionRequest(
    /** Left edge of the region (may be negative; clamped on read). */
    val x: Int,
    /** Top edge of the region (may be negative; clamped on read). */
    val y: Int,
    /** Region width, ≥ 1. */
    val width: Int,
    /** Region height, ≥ 1. */
    val height: Int,
) {
    init {
        require(width > 0 && height > 0) { "Region size must be positive (w=$width, h=$height)" }
    }
}

/**
 * One region read result: raw cells plus the requested formatted view.
 */
class RegionRead(
    /** The request the read satisfied. */
    val request: RegionRequest,
    /** Frame the region was read from. */
    val frame: PixelFrame,
    /** Effective clipped bounds inside the frame (`width × height` cells). */
    val clippedX: Int,
    val clippedY: Int,
    val clippedWidth: Int,
    val clippedHeight: Int,
    /** Raw ARGB cells of the *clipped* region, row-major. */
    val cells: IntArray,
    /** Number of cells outside the frame (rendered transparent). */
    val outOfFrameCells: Int,
) {
    /** Cell count of the clipped region. */
    val cellCount: Int get() = clippedWidth * clippedHeight

    override fun toString(): String =
        "RegionRead(clipped ${clippedWidth}x${clippedHeight} at $clippedX,$clippedY)"
}

/**
 * Reads rectangular regions of a [PixelFrame] back as agent-consumable data.
 *
 * Drawing tools push pixels in; this pulls them out. The four [RegionFormat]s
 * cover the read-back patterns agents actually need: exact hex grids for
 * verification, palette-index grids for tile/palette work, RLE for uniform
 * strips, and the SKETCH format that round-trips through the sketch parser
 * (read → edit text → draw).
 *
 * Reads never allocate proportional to the *requested* size — only the
 * clipped intersection with the frame is materialized, and a hard cell cap
 * ([MAX_CELLS]) protects the MCP channel from accidental 8K×8K dumps.
 */
object RegionReader {

    /** Hard cap on materialized cells per read (64K — 256×256). */
    const val MAX_CELLS: Int = 65_536

    /**
     * Reads the region of [frame] described by [request], returning the
     * clipped cells plus structured metadata. Out-of-frame cells count
     * toward [RegionRead.outOfFrameCells] and read as transparent.
     *
     * @throws IllegalArgumentException when the clipped region would exceed
     *   [MAX_CELLS] cells; narrow the request or pre-downscale.
     */
    fun read(frame: PixelFrame, request: RegionRequest): RegionRead {
        val x0 = max(0, request.x)
        val y0 = max(0, request.y)
        val x1 = min(frame.width, request.x + request.width)
        val y1 = min(frame.height, request.y + request.height)
        val clippedW = max(0, x1 - x0)
        val clippedH = max(0, y1 - y0)
        if (clippedW == 0 || clippedH == 0) {
            return RegionRead(request, frame, x0, y0, 0, 0, IntArray(0), request.width * request.height)
        }
        val cellCount = clippedW.toLong() * clippedH.toLong()
        require(cellCount <= MAX_CELLS) {
            "Region $clippedW×$clippedH is $cellCount cells, beyond the $MAX_CELLS-cell read cap"
        }
        val cells = IntArray(cellCount.toInt())
        var cursor = 0
        for (y in y0 until y1) {
            val row = y * frame.width
            System.arraycopy(frame.pixels, row + x0, cells, cursor, clippedW)
            cursor += clippedW
        }
        val outOfFrame = request.width * request.height - clippedW * clippedH
        return RegionRead(request, frame, x0, y0, clippedW, clippedH, cells, outOfFrame)
    }

    /**
     * Formats a [read] into the requested [format]. Palette-dependent formats
     * (PALETTE_INDEX, SKETCH legend snapping) need [palette]; passing null
     * there throws [IllegalArgumentException].
     */
    fun format(read: RegionRead, format: RegionFormat, palette: Palette? = null): String {
        return when (format) {
            RegionFormat.HEX -> formatHex(read)
            RegionFormat.PALETTE_INDEX -> formatPaletteIndex(read, palette)
            RegionFormat.RLE -> formatRle(read)
            RegionFormat.SKETCH -> formatSketch(read, palette)
        }
    }

    /** `#RRGGBB` grid, one space-separated row per line. */
    internal fun formatHex(read: RegionRead): String {
        if (read.cellCount == 0) return "(region entirely outside the frame)"
        val sb = StringBuilder(read.cellCount * 9)
        for (y in 0 until read.clippedHeight) {
            for (x in 0 until read.clippedWidth) {
                if (x > 0) sb.append(' ')
                sb.append(ColorCensus.hex(read.cells[y * read.clippedWidth + x]))
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** Palette-index grid; `-1` marks cells that snap to no entry. */
    internal fun formatPaletteIndex(read: RegionRead, palette: Palette?): String {
        requireNotNull(palette) { "palette is required for PALETTE_INDEX reads" }
        if (read.cellCount == 0) return "(region entirely outside the frame)"
        val sb = StringBuilder(read.cellCount * 3)
        for (y in 0 until read.clippedHeight) {
            for (x in 0 until read.clippedWidth) {
                if (x > 0) sb.append(' ')
                val cell = read.cells[y * read.clippedWidth + x]
                sb.append(if (cell ushr 24 == 0) "." else paletteIndexOf(cell, palette!!).toString())
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** One `count:hex` run per contiguous color span, one line per row. */
    internal fun formatRle(read: RegionRead): String {
        if (read.cellCount == 0) return "(region entirely outside the frame)"
        val sb = StringBuilder(read.clippedHeight * 24)
        for (y in 0 until read.clippedHeight) {
            val row = y * read.clippedWidth
            var runStart = 0
            while (runStart < read.clippedWidth) {
                val color = read.cells[row + runStart]
                var runEnd = runStart + 1
                while (runEnd < read.clippedWidth && read.cells[row + runEnd] == color) runEnd++
                if (runStart > 0) sb.append(',')
                sb.append(runEnd - runStart).append(':').append(ColorCensus.hex(color))
                runStart = runEnd
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /**
     * SKETCH format: `legend:` header lines (`char=#hex`) followed by the
     * char grid, ready to be edited and fed to the sketch parser.
     */
    internal fun formatSketch(read: RegionRead, palette: Palette?): String {
        if (read.cellCount == 0) return "(region entirely outside the frame)"
        // Assign chars most-frequent-first (transparent is always '.').
        val counts = HashMap<Int, Int>(32)
        for (c in read.cells) if (c ushr 24 != 0) counts.merge(c, 1, Int::plus)
        val ordered = counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        val alphabet = AsciiRenderer.LETTER_ALPHABET
        val charOf = HashMap<Int, Char>(ordered.size)
        val legend = ArrayList<String>(ordered.size)
        for ((rank, color) in ordered.withIndex()) {
            val ch = alphabet[rank % alphabet.length]
            charOf[color] = ch
            val display = palette?.let { AsciiRenderer.snapToPalette(color, it) } ?: color
            legend.add("$ch=${ColorCensus.hex(display)}")
        }
        val sb = StringBuilder(read.cellCount + legend.size * 12)
        for (line in legend) sb.append(line).append('\n')
        if (legend.isEmpty()) sb.append(".=#00000000 (empty region)\n")
        sb.append("---\n")
        for (y in 0 until read.clippedHeight) {
            for (x in 0 until read.clippedWidth) {
                val cell = read.cells[y * read.clippedWidth + x]
                sb.append(if (cell ushr 24 == 0) '.' else charOf[cell] ?: '?')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** Lab-nearest palette index of [argb], or -1 when nothing is within ΔE 24. */
    internal fun paletteIndexOf(argb: Int, palette: Palette): Int {
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (i in palette.colors.indices) {
            val d = LabDistanceCache.distance(argb, palette.colors[i])
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (bestDist <= 24.0) best else -1
    }
}
