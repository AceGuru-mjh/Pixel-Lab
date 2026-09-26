package com.pixellab.core.describe

import com.pixellab.core.analysis.ComponentColorMode
import com.pixellab.core.analysis.Connectivity
import com.pixellab.core.analysis.ConnectedComponentOps
import com.pixellab.core.color.ColorNamer
import com.pixellab.core.model.PixelFrame

/**
 * Plain-language summary of one connected region ("blob").
 */
class BlobSummary(
    /** 1-based component label (matches the labeler's output order). */
    val id: Int,
    /** Pixel count of the component. */
    val area: Int,
    /** Bounding box as `x,y,w,h` (x,y = top-left, inclusive). */
    val boxX: Int,
    val boxY: Int,
    val boxW: Int,
    val boxH: Int,
    /** Dominant exact color of the component. */
    val dominantColor: Int,
    /** Nearest CSS name of [dominantColor]. */
    val dominantName: String,
    /** Enclosed transparent hole count. */
    val holes: Int,
    /** True when the component touches the canvas border. */
    val touchesBorder: Boolean,
    /** Fill ratio of the component inside its bounding box (0..1]. */
    val fillRatio: Double,
) {
    override fun toString(): String =
        "Blob#$id ${area}px at ($boxX,$boxY ${boxW}x$boxH) $dominantName"
}

/**
 * Coarse occupancy fingerprint: the frame divided into a macro grid of
 * fill ratios, each quantized to one of five levels.
 */
class OccupancyFingerprint(
    /** Macro-grid columns. */
    val cols: Int,
    /** Macro-grid rows. */
    val rows: Int,
    /** Quantized fill per macro cell, row-major; 0 = empty … 4 = solid. */
    val levels: IntArray,
    /** Character view of [levels] using `" ·:▒█"`. */
    val rowsAsText: List<String>,
)

/**
 * Result of a structural read of one frame.
 */
class StructureReport(
    /** Frame the report describes. */
    val frame: PixelFrame,
    /** Blob summaries sorted by descending area (labels preserved). */
    val blobs: List<BlobSummary>,
    /** Number of single-pixel components (isolated dots). */
    val isolatedPixels: Int,
    /** Bounding box of *all* visible content, or null when empty. */
    val contentBox: IntArray?,
    /** Macro occupancy grid. */
    val fingerprint: OccupancyFingerprint,
) {
    /** Largest blob summary, or null for an empty canvas. */
    val largest: BlobSummary? get() = blobs.firstOrNull()

    override fun toString(): String =
        "StructureReport(${blobs.size} blobs, $isolatedPixels isolated)"
}

/**
 * Structure reading for agent vision: turns a frame into a list of
 * "things on the canvas" (connected components with plain-language
 * summaries) plus a coarse occupancy fingerprint that survives being
 * quoted in a prompt.
 *
 * The heavy lifting (labeling, geometry) reuses
 * [ConnectedComponentOps]; this class adds the agent-facing semantics:
 * dominant color naming, isolated-dot census, content bounding box and
 * the macro grid. Deterministic, allocation-bounded by one labeling pass.
 */
object StructureAnalyzer {

    /** Macro-cell target count along the longer axis (grid stays ≤ 16×16). */
    const val FINGERPRINT_TARGET: Int = 12

    /** Level characters for the fingerprint text view. */
    const val FINGERPRINT_CHARS: String = " ·:▒█"

    /**
     * Analyzes [frame]: components by opacity ([colorMode] switches to
     * same-color regions), the isolated-pixel census and the occupancy
     * fingerprint. [maxBlobs] caps the returned summary list (largest
     * first); the count reflects the true total.
     */
    fun analyze(
        frame: PixelFrame,
        connectivity: Connectivity = Connectivity.FOUR,
        colorMode: ComponentColorMode = ComponentColorMode.OPAQUE,
        maxBlobs: Int = 16,
    ): StructureReport {
        require(maxBlobs > 0) { "maxBlobs must be positive (was $maxBlobs)" }
        val result = ConnectedComponentOps.label(frame, connectivity, colorMode)
        val areaByLabel = HashMap<Int, Int>(result.count * 2)
        val colorByLabel = HashMap<Int, HashMap<Int, Int>>(result.count * 2)
        for (i in frame.pixels.indices) {
            val label = result.labels[i]
            if (label == 0) continue
            areaByLabel.merge(label, 1, Int::plus)
            val votes = colorByLabel.getOrPut(label) { HashMap(8) }
            votes.merge(frame.pixels[i], 1, Int::plus)
        }
        val summaries = result.blobs.map { blob ->
            val votes = colorByLabel[blob.id]
            val dominant = votes?.entries
                ?.maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key
                ?: 0
            BlobSummary(
                id = blob.id,
                area = blob.area,
                boxX = blob.minX,
                boxY = blob.minY,
                boxW = blob.width,
                boxH = blob.height,
                dominantColor = dominant,
                dominantName = if (dominant == 0) "transparent" else ColorNamer.nearestName(dominant).name,
                holes = blob.holes,
                touchesBorder = blob.touchesBorder,
                fillRatio = blob.fillRatio,
            )
        }.sortedWith(compareByDescending<BlobSummary> { it.area }.thenBy { it.id })
        val isolated = summaries.count { it.area == 1 }
        return StructureReport(
            frame = frame,
            blobs = summaries.take(maxBlobs),
            isolatedPixels = isolated,
            contentBox = contentBox(frame),
            fingerprint = fingerprint(frame),
        )
    }

    /**
     * Bounding box of all non-transparent pixels as `[x, y, w, h]`, or
     * null when the frame is fully transparent.
     */
    fun contentBox(frame: PixelFrame): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (y in 0 until frame.height) {
            val row = y * frame.width
            for (x in 0 until frame.width) {
                if (frame.pixels[row + x] ushr 24 != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (minX == Int.MAX_VALUE) return null
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /**
     * Macro occupancy grid: the frame is partitioned into a grid of at
     * most [FINGERPRINT_TARGET]×[FINGERPRINT_TARGET] cells (aligned to the
     * longer edge) and each cell's visible-pixel ratio is quantized to
     * five levels (0, >0–25%, 25–50%, 50–75%, 75%+).
     */
    fun fingerprint(frame: PixelFrame): OccupancyFingerprint {
        val long = maxOf(frame.width, frame.height)
        val cells = minOf(FINGERPRINT_TARGET, long)
        val cols = if (frame.width >= frame.height) cells else maxOf(1, frame.width * cells / frame.height)
        val rows = if (frame.width >= frame.height) maxOf(1, frame.height * cells / frame.width) else cells
        val levels = IntArray(cols * rows)
        val cellW = (frame.width + cols - 1) / cols
        val cellH = (frame.height + rows - 1) / rows
        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val x0 = gx * cellW
                val y0 = gy * cellH
                val x1 = minOf(frame.width, x0 + cellW)
                val y1 = minOf(frame.height, y0 + cellH)
                var visible = 0
                var total = 0
                for (y in y0 until y1) {
                    val row = y * frame.width
                    for (x in x0 until x1) {
                        if (frame.pixels[row + x] ushr 24 != 0) visible++
                        total++
                    }
                }
                val ratio = if (total == 0) 0.0 else visible.toDouble() / total
                levels[gy * cols + gx] = when {
                    visible == 0 -> 0
                    ratio <= 0.25 -> 1
                    ratio <= 0.5 -> 2
                    ratio <= 0.75 -> 3
                    else -> 4
                }
            }
        }
        val text = (0 until rows).map { y ->
            StringBuilder(cols).apply {
                for (x in 0 until cols) {
                    append(FINGERPRINT_CHARS[levels[y * cols + x]])
                }
            }.toString()
        }
        return OccupancyFingerprint(cols, rows, levels, text)
    }

    /**
     * One-line shape verdict for a blob, used by descriptions: "solid
     * block", "ring/outline" (enclosed holes win over fill ratio — a donut
     * with a thick rim is still a ring), "thin outline", "single dot",
     * "spread shape".
     */
    fun shapeVerdict(blob: BlobSummary): String = when {
        blob.area == 1 -> "single dot"
        blob.holes >= 1 -> "ring/outline with ${blob.holes} hole(s)"
        blob.fillRatio >= 0.85 -> "solid block"
        blob.fillRatio >= 0.55 -> "mostly solid"
        blob.fillRatio >= 0.3 -> "spread shape"
        else -> "thin outline"
    }
}
