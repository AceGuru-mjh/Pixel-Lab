package com.pixellab.core.describe

import com.pixellab.core.color.ColorNamer
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame

/**
 * Tuning knobs for [FrameDescriber.describe].
 */
class DescribeOptions(
    /** Include the color census section. */
    val includeColors: Boolean = true,
    /** Include the structure (blob) section. */
    val includeStructure: Boolean = true,
    /** Include the symmetry section. */
    val includeSymmetry: Boolean = true,
    /** Include the ASCII fingerprint rows. */
    val includeFingerprint: Boolean = true,
    /** Maximum colors listed in the census section. */
    val maxColors: Int = 8,
    /** Maximum blobs listed in the structure section. */
    val maxBlobs: Int = 5,
) {
    init {
        require(maxColors in 1..32) { "maxColors must be in 1..32 (was $maxColors)" }
        require(maxBlobs in 1..32) { "maxBlobs must be in 1..32 (was $maxBlobs)" }
    }
}

/**
 * Structured natural-language description of one frame, assembled from the
 * census, structure and symmetry analyzers. [text] is the multi-line
 * prose block agents consume directly; the typed sections back tool output.
 */
class FrameDescription(
    /** The described frame. */
    val frame: PixelFrame,
    /** Full multi-line prose description. */
    val text: String,
    /** Census section (null when [DescribeOptions.includeColors] was off). */
    val census: ColorCensusResult?,
    /** Structure section (null when [DescribeOptions.includeStructure] was off). */
    val structure: StructureReport?,
    /** Symmetry section (null when [DescribeOptions.includeSymmetry] was off). */
    val symmetry: SymmetryReport?,
    /** Palette coverage (only when a palette was supplied). */
    val coverage: PaletteCoverage?,
    /** One-line headline: `"16x16, 41% occupied, 4 colors"`. */
    val headline: String,
)

/**
 * Composes the agent-facing natural-language description of a frame.
 *
 * The output reads like a peer artist describing a canvas over chat:
 *
 * ```
 * 16x16 canvas, 41% occupied, 4 colors.
 * Colors: sky blue #87ceeb (104 px), red #ff0000 (38 px), …
 * Structure: 3 regions; largest 96 px at (4,6) 9x9, mostly sky blue —
 * solid block. Content spans (2,3)-(14,13). 1 isolated dot at (12,2).
 * Symmetry: left-right symmetric.
 * Layout fingerprint (16 cols × 16 rows):
 * ····▒▒▒▒····
 * …
 * ```
 *
 * Every sentence is derived from the underlying analyzers — the describer
 * adds no judgment they did not compute. Deterministic, allocation-bounded
 * by one pass per enabled section.
 */
object FrameDescriber {

    /**
     * Describes [frame] with the given [options]; [palette] adds a
     * coverage section when non-null.
     */
    fun describe(
        frame: PixelFrame,
        options: DescribeOptions = DescribeOptions(),
        palette: Palette? = null,
    ): FrameDescription {
        val census = if (options.includeColors) ColorCensus.census(frame) else null
        val coverage = if (census != null && palette != null) ColorCensus.coverage(census, palette) else null
        val structure = if (options.includeStructure) StructureAnalyzer.analyze(frame, maxBlobs = options.maxBlobs) else null
        val symmetry = if (options.includeSymmetry) SymmetryAnalyzer.analyze(frame) else null

        val headline = headlineOf(frame, census)
        val sections = ArrayList<String>(6)
        sections.add(headline)
        census?.let { sections.add(colorsSection(it, options.maxColors)) }
        coverage?.let { sections.add(coverageSection(it)) }
        structure?.let { sections.add(structureSection(it)) }
        symmetry?.let { sections.add("Symmetry: " + SymmetryAnalyzer.summarize(it) + ".") }
        if (options.includeFingerprint && structure != null) {
            sections.add(fingerprintSection(structure))
        }
        return FrameDescription(
            frame = frame,
            text = sections.joinToString("\n"),
            census = census,
            structure = structure,
            symmetry = symmetry,
            coverage = coverage,
            headline = headline,
        )
    }

    /** `"16x16 canvas, 41% occupied, 4 colors."` (census-free when null). */
    internal fun headlineOf(frame: PixelFrame, census: ColorCensusResult?): String {
        val size = "${frame.width}x${frame.height} canvas"
        if (census == null) return "$size."
        val pct = (census.occupancy * 100).toInt()
        val colors = if (census.uniqueColors == 1) "1 color" else "${census.uniqueColors} colors"
        val semi = if (census.semiTransparentPixels > 0) ", ${census.semiTransparentPixels} semi-transparent px" else ""
        return "$size, $pct% occupied, $colors$semi."
    }

    /** Colors section prose. */
    internal fun colorsSection(census: ColorCensusResult, maxColors: Int): String {
        if (census.tallies.isEmpty()) {
            return "Colors: fully transparent (nothing drawn)."
        }
        val lines = census.tallies.take(maxColors).joinToString(", ") { t ->
            "${t.name} ${t.hex} (${t.count} px)"
        }
        val more = if (census.uniqueColors > maxColors) {
            ", +${census.uniqueColors - maxColors} more"
        } else {
            ""
        }
        val families = census.families.take(3).joinToString("/") { it.family }
        return "Colors: $lines$more — families: $families."
    }

    /** Palette coverage section prose. */
    internal fun coverageSection(coverage: PaletteCoverage): String {
        val base = "Palette ${coverage.paletteId}: ${coverage.usedEntries}/${coverage.paletteSize} entries used"
        return if (coverage.orphanShades == 0) {
            "$base, no off-palette colors."
        } else {
            "$base, ${coverage.orphanShades} off-palette shade(s): ${coverage.orphanExamples.joinToString(", ")}"
        } + "."
    }

    /** Structure section prose (report blobs are pre-truncated by analyze). */
    internal fun structureSection(report: StructureReport): String {
        val sb = StringBuilder(160)
        sb.append("Structure: ")
        if (report.blobs.isEmpty()) {
            sb.append("no visible content.")
            return sb.toString()
        }
        sb.append("${report.blobs.size} region(s)")
        val largest = report.largest
        if (largest != null) {
            sb.append("; largest ${largest.area} px at (${largest.boxX},${largest.boxY}) ${largest.boxW}x${largest.boxH}, ")
            sb.append("mostly ${largest.dominantName} — ${StructureAnalyzer.shapeVerdict(largest)}")
        }
        report.contentBox?.let { box ->
            sb.append(". Content spans (${box[0]},${box[1]})-(${box[0] + box[2] - 1},${box[1] + box[3] - 1})")
        }
        if (report.isolatedPixels > 0) {
            sb.append(". ${report.isolatedPixels} isolated dot(s)")
        }
        sb.append('.')
        return sb.toString()
    }

    /** Fingerprint section with the macro grid in a code block. */
    internal fun fingerprintSection(report: StructureReport): String {
        val fp = report.fingerprint
        val body = fp.rowsAsText.joinToString("\n") { "  $it" }
        return "Layout fingerprint (${fp.cols}x${fp.rows}, ' ' empty … '█' solid):\n$body"
    }

    /**
     * Short "what is at this location" sentence for a single point read —
     * used by tools that answer "what color is at (x, y)?" with context.
     */
    fun describePixel(frame: PixelFrame, x: Int, y: Int): String {
        if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) {
            return "($x,$y) is outside the ${frame.width}x${frame.height} canvas"
        }
        val argb = frame.pixels[y * frame.width + x]
        val a = argb ushr 24
        if (a == 0) return "($x,$y) is transparent"
        if (a != 0xFF) {
            return "($x,$y) is ${ColorCensus.hex(argb)} (${ColorNamer.nearestName(argb).name}, alpha $a/255)"
        }
        return "($x,$y) is ${ColorCensus.hex(argb)} (${ColorNamer.nearestName(argb).name})"
    }
}
