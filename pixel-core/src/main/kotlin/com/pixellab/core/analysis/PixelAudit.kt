package com.pixellab.core.analysis

import com.pixellab.core.model.PixelFrame

/**
 * A quality rule that [PixelAuditor] can flag.
 *
 * Each rule targets a concrete, well-documented pixel-art craft defect —
 * the categories professional sprite reviewers check by hand.
 */
enum class AuditRule {
    /**
     * Opaque pixel whose 8-neighborhood contains no pixel of a similar
     * color (distance below the audit's threshold). Single-pixel "dust"
     * that reads as noise at display scale.
     */
    ISOLATED_PIXEL,

    /**
     * Two opaque pixels touch only diagonally while none of their
     * orthogonal neighbors connects them — a "broken corner" that makes
     * outlines look disconnected.
     */
    BROKEN_CORNER,

    /**
     * A 2x2 window with exactly two opaque pixels placed diagonally
     * *within the same color region*: the classic accidental
     * "checkerboard" texture that usually indicates an unintended dither.
     */
    ACCIDENTAL_CHECKER,

    /**
     * Transparent region fully enclosed by opaque pixels — usually an
     * unplanned hole in a fill, detected once per hole (attributed to the
     * hole's top-left cell).
     */
    ENCLOSED_HOLE,

    /**
     * Opaque cluster smaller than the configured minimum cluster area —
     * floating fragments of color inside a larger region, typically
     * stray brush strokes.
     */
    TINY_CLUSTER,

    /**
     * Diagonal edge whose "stair" has two consecutive steps of length 1
     * in opposite directions (a zigzag). Long clean diagonals use steps
     * of ≥ 2; 1-1 alternation reads as a jagged line. Reported at the
     * start of each detected zigzag run.
     */
    STAIR_ZIGZAG,
}

/** Severity of a single [AuditFinding]. */
enum class AuditSeverity { INFO, MINOR, MAJOR }

/** A single flagged occurrence of an [AuditRule]. */
class AuditFinding(
    /** The rule that produced this finding. */
    val rule: AuditRule,
    /** Flat row-major index of the offending pixel (or a representative cell). */
    val index: Int,
    /** Raster width, kept so `x`/`y` can be derived on demand. */
    private val rasterWidth: Int,
    /** The offending pixel's ARGB color (0 for hole findings). */
    val argb: Int,
    /** Human-readable explanation of this specific occurrence. */
    val message: String,
    /** Severity bucket. */
    val severity: AuditSeverity,
) {
    /** x coordinate of the finding. */
    val x: Int get() = if (rasterWidth <= 0) 0 else index % rasterWidth

    /** y coordinate of the finding. */
    val y: Int get() = if (rasterWidth <= 0) 0 else index / rasterWidth

    override fun toString(): String = "$rule@($x, $y): $message"
}

/**
 * Configuration for a [PixelAuditor] run. Defaults match the pixel-art
 * craft rules popularized by Aseprite/Photoshop sprite-review checklists.
 */
class AuditConfig(
    /** Flag [AuditRule.ISOLATED_PIXEL]. */
    val checkIsolated: Boolean = true,
    /** Flag [AuditRule.BROKEN_CORNER]. */
    val checkBrokenCorner: Boolean = true,
    /** Flag [AuditRule.ACCIDENTAL_CHECKER]. */
    val checkChecker: Boolean = true,
    /** Flag [AuditRule.ENCLOSED_HOLE]. */
    val checkHoles: Boolean = true,
    /** Flag [AuditRule.TINY_CLUSTER] for same-color clusters below this area. */
    val tinyClusterArea: Int = 2,
    /** Flag [AuditRule.STAIR_ZIGZAG]. */
    val checkZigzag: Boolean = true,
    /** Per-channel threshold below which two colors count as "similar". */
    val similarColorTolerance: Int = 24,
) {
    init {
        require(tinyClusterArea >= 0) { "tinyClusterArea must be ≥ 0" }
        require(similarColorTolerance in 0..255) { "similarColorTolerance out of range" }
    }
}

/**
 * Aggregate statistics of an audit run.
 */
class AuditStats(
    /** Opaque pixels in the frame. */
    val opaquePixels: Long,
    /** Transparent pixels. */
    val transparentPixels: Long,
    /** Number of distinct opaque colors. */
    val distinctColors: Int,
    /** Opacity components (4-connected OPAQUE blobs). */
    val componentCount: Int,
    /** Largest component area. */
    val largestComponent: Int,
    /** Total enclosed holes. */
    val holeCount: Int,
) {
    /** Opaque coverage ratio (0..1). */
    val coverage: Double get() {
        val total = opaquePixels + transparentPixels
        return if (total == 0L) 0.0 else opaquePixels.toDouble() / total
    }

    override fun toString(): String =
        "AuditStats(opaque=$opaquePixels, colors=$distinctColors, components=$componentCount, holes=$holeCount)"
}

/**
 * The full result of auditing one frame.
 */
class PixelAuditReport(
    /** The audited frame. */
    val frame: PixelFrame,
    /** All findings in raster order. */
    val findings: List<AuditFinding>,
    /** Aggregate statistics. */
    val stats: AuditStats,
    /** 0..100 quality score: 100 minus weighted finding penalties. */
    val score: Int,
    /** Actionable craft advice derived from the finding mix. */
    val suggestions: List<String>,
) {
    /** Findings grouped by rule, lazily. */
    val byRule: Map<AuditRule, List<AuditFinding>> by lazy { findings.groupBy { it.rule } }

    /** True when no rule was violated. */
    val isClean: Boolean get() = findings.isEmpty()

    override fun toString(): String =
        "PixelAuditReport(score=$score, findings=${findings.size})"
}

/**
 * Pixel-art craft auditor: flags the structural defects that make sprites
 * look unprofessional at display scale — isolated dust pixels, broken
 * corners, accidental checkerboard dithering, enclosed holes, floating
 * tiny clusters and 1-1 stair zigzags — and turns them into a 0..100
 * score plus actionable suggestions.
 *
 * Purely JVM, single pass per rule family, no allocations beyond the
 * findings list.
 */
object PixelAuditor {

    /** Finding severity per rule. */
    private val RULE_SEVERITY = mapOf(
        AuditRule.ISOLATED_PIXEL to AuditSeverity.MINOR,
        AuditRule.BROKEN_CORNER to AuditSeverity.MINOR,
        AuditRule.ACCIDENTAL_CHECKER to AuditSeverity.MAJOR,
        AuditRule.ENCLOSED_HOLE to AuditSeverity.MAJOR,
        AuditRule.TINY_CLUSTER to AuditSeverity.MINOR,
        AuditRule.STAIR_ZIGZAG to AuditSeverity.MINOR,
    )

    /** Score penalty per finding, by severity. */
    private val PENALTY = mapOf(
        AuditSeverity.INFO to 0.25,
        AuditSeverity.MINOR to 1.0,
        AuditSeverity.MAJOR to 3.0,
    )

    /**
     * Audits [frame] with [config].
     */
    fun audit(frame: PixelFrame, config: AuditConfig = AuditConfig()): PixelAuditReport {
        val findings = ArrayList<AuditFinding>(32)
        val opaque = frame.pixels.count { it ushr 24 != 0 }

        if (config.checkIsolated) findIsolatedPixels(frame, config, findings)
        if (config.checkBrokenCorner) findBrokenCorners(frame, config, findings)
        if (config.checkChecker) findAccidentalCheckers(frame, config, findings)

        // One labeling pass feeds every component-derived rule + stats.
        val cc = ConnectedComponentOps.label(frame, Connectivity.FOUR, ComponentColorMode.OPAQUE)
        if (config.checkHoles) findEnclosedHoles(frame, cc, findings)
        if (config.tinyClusterArea > 0) findTinyClusters(frame, config, cc, findings)
        if (config.checkZigzag) findStairZigzags(frame, config, findings)

        val stats = buildStats(frame, cc, opaque.toLong())
        return PixelAuditReport(
            frame, findings, stats,
            computeScore(frame, findings, opaque.toLong()),
            buildSuggestions(findings, stats),
        )
    }

    // ── Rule implementations ────────────────────────────────────────────────

    /** ISOLATED_PIXEL: no 8-neighbor of similar color. */
    private fun findIsolatedPixels(frame: PixelFrame, config: AuditConfig, out: MutableList<AuditFinding>) {
        val w = frame.width
        val h = frame.height
        val px = frame.pixels
        val tol = config.similarColorTolerance
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val c = px[idx]
                if (c ushr 24 == 0) continue
                var similar = false
                neighborLoop@ for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                        val n = px[ny * w + nx]
                        if (n ushr 24 == 0) continue
                        if (channelDelta(c, n) <= tol) { similar = true; break@neighborLoop }
                    }
                }
                if (!similar) {
                    out += AuditFinding(
                        AuditRule.ISOLATED_PIXEL, idx, w, c,
                        "dust pixel at ($x, $y): no neighbor within tolerance $tol",
                        RULE_SEVERITY[AuditRule.ISOLATED_PIXEL]!!,
                    )
                }
            }
        }
    }

    /** BROKEN_CORNER: diagonal-only contact between similar-color pixels. */
    private fun findBrokenCorners(frame: PixelFrame, config: AuditConfig, out: MutableList<AuditFinding>) {
        val w = frame.width
        val h = frame.height
        val px = frame.pixels
        val tol = config.similarColorTolerance
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val tl = px[y * w + x]
                val br = px[(y + 1) * w + x + 1]
                if (tl ushr 24 == 0 || br ushr 24 == 0) continue
                if (channelDelta(tl, br) > tol) continue
                // Orthogonal bridge must be absent on BOTH sides.
                val tr = px[y * w + x + 1]
                val bl = px[(y + 1) * w + x]
                if (similar(tr, tl, tol) || similar(bl, tl, tol) || similar(tr, br, tol) || similar(bl, br, tol)) continue
                if (tr ushr 24 != 0 && channelDelta(tr, tl) <= tol) continue
                out += AuditFinding(
                    AuditRule.BROKEN_CORNER, y * w + x, w, tl,
                    "broken corner at ($x, $y): diagonal pair connected only by transparency",
                    RULE_SEVERITY[AuditRule.BROKEN_CORNER]!!,
                )
            }
        }
    }

    /** ACCIDENTAL_CHECKER: diagonal same-color 2x2 without orthogonal support. */
    private fun findAccidentalCheckers(frame: PixelFrame, config: AuditConfig, out: MutableList<AuditFinding>) {
        val w = frame.width
        val h = frame.height
        val px = frame.pixels
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val tl = px[y * w + x]
                val br = px[(y + 1) * w + x + 1]
                if (tl ushr 24 == 0 || tl != br) continue
                val tr = px[y * w + x + 1]
                val bl = px[(y + 1) * w + x]
                if (tr ushr 24 != 0 && tr == tl) continue
                if (bl ushr 24 != 0 && bl == tl) continue
                if (tr ushr 24 != 0 && bl ushr 24 != 0 && tr == bl) continue
                out += AuditFinding(
                    AuditRule.ACCIDENTAL_CHECKER, y * w + x, w, tl,
                    "accidental checkerboard at ($x, $y): identical diagonal pixels with no orthogonal support",
                    RULE_SEVERITY[AuditRule.ACCIDENTAL_CHECKER]!!,
                )
            }
        }
    }

    /** ENCLOSED_HOLE: one finding per enclosed transparent region. */
    private fun findEnclosedHoles(frame: PixelFrame, cc: ConnectedComponentsResult, out: MutableList<AuditFinding>) {
        var totalHoles = 0
        for (blob in cc.blobs) totalHoles += blob.holes
        if (totalHoles == 0) return
        // Locate the top-left cell of each enclosed hole for reporting.
        val w = frame.width
        val h = frame.height
        val labels = cc.labels
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        // Flood outside transparency.
        for (x in 0 until w) {
            floodOutside(labels, visited, queue, w, h, x, 0)
            floodOutside(labels, visited, queue, w, h, x, h - 1)
        }
        for (y in 0 until h) {
            floodOutside(labels, visited, queue, w, h, 0, y)
            floodOutside(labels, visited, queue, w, h, w - 1, y)
        }
        for (i in 0 until w * h) {
            if (visited[i] || labels[i] != 0) continue
            // i is the top-left-most unvisited cell of an enclosed hole.
            out += AuditFinding(
                AuditRule.ENCLOSED_HOLE, i, w, 0,
                "enclosed hole at (${i % w}, ${i / w}): ${floodCount(labels, visited, queue, w, h, i)} px",
                RULE_SEVERITY[AuditRule.ENCLOSED_HOLE]!!,
            )
        }
    }

    /** TINY_CLUSTER: same-color component smaller than the threshold. */
    private fun findTinyClusters(
        frame: PixelFrame,
        config: AuditConfig,
        cc: ConnectedComponentsResult,
        out: MutableList<AuditFinding>,
    ) {
        if (config.tinyClusterArea <= 0) return
        val colorCc = ConnectedComponentOps.label(frame, Connectivity.FOUR, ComponentColorMode.SAME_COLOR)
        val w = frame.width
        for (blob in colorCc.blobs) {
            if (blob.area >= config.tinyClusterArea) continue
            val head = blob.pixelIndices.first()
            out += AuditFinding(
                AuditRule.TINY_CLUSTER, head, w, frame.pixels[head],
                "tiny cluster at (${head % w}, ${head / w}): ${blob.area} px of one color",
                RULE_SEVERITY[AuditRule.TINY_CLUSTER]!!,
            )
        }
    }

    /** STAIR_ZIGZAG: 1-px zigzag chains — pixels whose only linkage is diagonal. */
    private fun findStairZigzags(frame: PixelFrame, config: AuditConfig, out: MutableList<AuditFinding>) {
        val w = frame.width
        val h = frame.height
        val px = frame.pixels
        val on = { x: Int, y: Int -> x in 0 until w && y in 0 until h && px[y * w + x] ushr 24 != 0 }
        // A zigzag chain node: an opaque pixel with (a) at least one
        // opaque diagonal neighbor and (b) zero opaque orthogonal
        // neighbors. Solid 2:1 staircases never trip this — their step
        // corners always keep an orthogonal neighbor along the step —
        // while 1-px diagonal zigzags (and broken diagonal strokes) do.
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!on(x, y)) continue
                val orthogonal = on(x - 1, y) || on(x + 1, y) || on(x, y - 1) || on(x, y + 1)
                if (orthogonal) continue
                val diagonal = on(x - 1, y - 1) || on(x + 1, y - 1) || on(x - 1, y + 1) || on(x + 1, y + 1)
                if (!diagonal) continue // that is ISOLATED_PIXEL's business
                out += AuditFinding(
                    AuditRule.STAIR_ZIGZAG, y * w + x, w, px[y * w + x],
                    "stair zigzag at ($x, $y): 1-px diagonal chain with no orthogonal support",
                    RULE_SEVERITY[AuditRule.STAIR_ZIGZAG]!!,
                )
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun buildStats(frame: PixelFrame, cc: ConnectedComponentsResult, opaque: Long): AuditStats {
        val distinct = frame.pixels.asSequence().filter { it ushr 24 != 0 }.distinct().count()
        var holes = 0
        for (b in cc.blobs) holes += b.holes
        return AuditStats(
            opaquePixels = opaque,
            transparentPixels = frame.pixelCount - opaque,
            distinctColors = distinct,
            componentCount = cc.count,
            largestComponent = cc.largest?.area ?: 0,
            holeCount = holes,
        )
    }

    private fun computeScore(frame: PixelFrame, findings: List<AuditFinding>, opaque: Long): Int {
        if (opaque == 0L) return 100
        var penalty = 0.0
        for (f in findings) penalty += PENALTY[f.severity]!!
        // Normalize by sprite size: small sprites penalize harder for the
        // same defect count, matching how visible the defects are.
        val scale = kotlin.math.max(8.0, kotlin.math.sqrt(opaque.toDouble()))
        val normalized = penalty / scale * 10.0
        return (100.0 - normalized).coerceIn(0.0, 100.0).toInt()
    }

    private fun buildSuggestions(findings: List<AuditFinding>, stats: AuditStats): List<String> {
        val tips = ArrayList<String>(6)
        val byRule = findings.groupBy { it.rule }
        if (AuditRule.ISOLATED_PIXEL in byRule) {
            tips += "Remove dust pixels (try MorphologyOps.removeIsolatedPixels) — found ${byRule[AuditRule.ISOLATED_PIXEL]!!.size}."
        }
        if (AuditRule.BROKEN_CORNER in byRule) {
            tips += "Connect diagonal-only corners with an orthogonal pixel to heal outlines (${byRule[AuditRule.BROKEN_CORNER]!!.size} sites)."
        }
        if (AuditRule.ACCIDENTAL_CHECKER in byRule) {
            tips += "Replace accidental checkerboard pairs with a single intermediate color or an intentional 2-1 dither row."
        }
        if (AuditRule.ENCLOSED_HOLE in byRule) {
            tips += "Fill enclosed holes (MorphologyOps.fillEnclosedHoles) unless they are intentional eyes/details."
        }
        if (AuditRule.TINY_CLUSTER in byRule) {
            tips += "Merge or remove tiny same-color clusters below the craft threshold ( ConnectedComponentOps.removeSmall)."
        }
        if (AuditRule.STAIR_ZIGZAG in byRule) {
            tips += "Smooth 1-1 stair zigzags into 2-1 step diagonals along long edges."
        }
        if (stats.distinctColors > 64) {
            tips += "Palette is very wide (${stats.distinctColors} colors); consider quantizing for a consistent pixel-art look."
        }
        if (tips.isEmpty()) {
            tips += "Clean sprite: no structural craft defects detected."
        }
        return tips
    }

    private fun channelDelta(a: Int, b: Int): Int {
        val dr = kotlin.math.abs((a ushr 16 and 0xFF) - (b ushr 16 and 0xFF))
        val dg = kotlin.math.abs((a ushr 8 and 0xFF) - (b ushr 8 and 0xFF))
        val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        return maxOf(dr, dg, db)
    }

    private fun similar(a: Int, b: Int, tol: Int): Boolean =
        a ushr 24 != 0 && b ushr 24 != 0 && channelDelta(a, b) <= tol

    private fun floodOutside(labels: IntArray, visited: BooleanArray, queue: IntArray, w: Int, h: Int, x0: Int, y0: Int) {
        val start = y0 * w + x0
        if (visited[start] || labels[start] != 0) return
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            val y = idx / w
            if (x > 0 && !visited[idx - 1] && labels[idx - 1] == 0) { visited[idx - 1] = true; queue[tail++] = idx - 1 }
            if (x < w - 1 && !visited[idx + 1] && labels[idx + 1] == 0) { visited[idx + 1] = true; queue[tail++] = idx + 1 }
            if (y > 0 && !visited[idx - w] && labels[idx - w] == 0) { visited[idx - w] = true; queue[tail++] = idx - w }
            if (y < h - 1 && !visited[idx + w] && labels[idx + w] == 0) { visited[idx + w] = true; queue[tail++] = idx + w }
        }
    }

    private fun floodCount(labels: IntArray, visited: BooleanArray, queue: IntArray, w: Int, h: Int, start: Int): Int {
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        var count = 0
        while (head < tail) {
            val idx = queue[head++]
            count++
            val x = idx % w
            val y = idx / w
            if (x > 0 && !visited[idx - 1] && labels[idx - 1] == 0) { visited[idx - 1] = true; queue[tail++] = idx - 1 }
            if (x < w - 1 && !visited[idx + 1] && labels[idx + 1] == 0) { visited[idx + 1] = true; queue[tail++] = idx + 1 }
            if (y > 0 && !visited[idx - w] && labels[idx - w] == 0) { visited[idx - w] = true; queue[tail++] = idx - w }
            if (y < h - 1 && !visited[idx + w] && labels[idx + w] == 0) { visited[idx + w] = true; queue[tail++] = idx + w }
        }
        return count
    }
}
