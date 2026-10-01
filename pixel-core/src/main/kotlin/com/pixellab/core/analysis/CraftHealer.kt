package com.pixellab.core.analysis

import com.pixellab.core.model.PixelFrame

/**
 * Result of one [CraftHealer] repair pass.
 *
 * The report is deliberately audit-shaped: `before`/`after` carry the same
 * [PixelAuditReport] the read-only auditor produces, so hosts can diff the
 * two with the code they already have. `fixed` maps each repaired rule to
 * the number of defect sites it healed.
 */
class HealReport(
    /** The frame after all repair passes. */
    val frame: PixelFrame,
    /** Audit of the input frame (identical to running [PixelAuditor.audit] on it). */
    val before: PixelAuditReport,
    /** Audit of [frame] after repairs. */
    val after: PixelAuditReport,
    /** Repaired sites per rule (only rules with at least one repair appear). */
    val fixed: Map<AuditRule, Int>,
    /** True when every requested fix was applied and no requested fix was a no-op. */
    val allApplied: Boolean,
) {
    /** `after.score - before.score`; positive when the heal raised quality (the score is 0-100, higher is better). */
    val scoreDelta: Int get() = after.score - before.score

    /** Total number of defect sites repaired across all rules. */
    val totalFixed: Int get() = fixed.values.sum()

    override fun toString(): String =
        "HealReport(score ${before.score} -> ${after.score}, fixed=$totalFixed, rules=${fixed.keys})"
}

/**
 * Audit-driven craft repair: closes the [PixelAuditor] loop.
 *
 * The auditor tells an agent *what* is wrong with a sprite (dust pixels,
 * broken corners, accidental checkerboards, enclosed holes, floating tiny
 * clusters, 1-px zigzag chains) and *suggests* fixes; until now every fix
 * had to be driven by hand through morphology calls with hand-picked
 * arguments. [HealReport] is the missing one-call bridge: it re-runs the
 * audit, applies the **targeted inverse** of each flagged rule — touching
 * only the flagged pixels, never the whole raster — and re-audits so the
 * caller sees the score move.
 *
 * Repair semantics per rule (conservative by design):
 *  * [AuditRule.ISOLATED_PIXEL] — the dust pixel is cleared to transparent.
 *  * [AuditRule.ENCLOSED_HOLE] — the hole is filled with the majority
 *    color of its 4-neighbor boundary (same color semantics as
 *    [MorphologyOps.fillEnclosedHoles], but restricted to audited holes;
 *    intentional eyes/details can be kept with [HealOptions.fixHoles]
 *    = false).
 *  * [AuditRule.TINY_CLUSTER] — same-color components smaller than
 *    [AuditConfig.tinyClusterArea] are cleared (the exact removal the
 *    audit's own suggestion names, without a global [ConnectedComponentOps.removeSmall]).
 *  * [AuditRule.ACCIDENTAL_CHECKER] — each flagged diagonal pair gets an
 *    orthogonal support pixel: the checker's color is painted into the
 *    best (most-connected) transparent orthogonal neighbor of the pair.
 *    This *adds* a pixel instead of deleting art — the fix an author
 *    usually means by "I did not intend that dither".
 *  * [AuditRule.BROKEN_CORNER] — same support-pixel heal as the checker:
 *    one transparent orthogonal cell bridging the diagonal pair becomes
 *    the pair's color.
 *  * [AuditRule.STAIR_ZIGZAG] — left to the caller: clean 2:1 stairs are
 *    an aesthetic judgment (which diagonal to flatten), so the healer
 *    never rewrites edges automatically; the report simply carries the
 *    still-open count in `after`.
 *
 * Everything is deterministic: no randomness, stable iteration in raster
 * order, and repairs apply in a fixed pipeline order (dust → tiny
 * clusters → holes → corners → checkers) so the same input always yields
 * the same output bytes. The input frame is never mutated.
 *
 * ```kotlin
 * val report = CraftHealer.heal(frame)
 * if (report.scoreDelta > 0) use(report.frame) else keep(frame)
 * ```
 *
 * The score is a 0-100 quality grade (higher = better, see
 * [PixelAuditReport.score]), so a successful heal has a *positive*
 * [HealReport.scoreDelta].
 */
object CraftHealer {

    /**
     * Per-rule switches for a heal pass. Defaults fix everything the
     * auditor flags except [AuditRule.STAIR_ZIGZAG] (never auto-fixed).
     */
    class HealOptions(
        /** Repair [AuditRule.ISOLATED_PIXEL] sites. */
        val fixIsolated: Boolean = true,
        /** Repair [AuditRule.TINY_CLUSTER] sites. */
        val fixTinyClusters: Boolean = true,
        /** Repair [AuditRule.ENCLOSED_HOLE] sites. */
        val fixHoles: Boolean = true,
        /** Repair [AuditRule.BROKEN_CORNER] sites. */
        val fixBrokenCorners: Boolean = true,
        /** Repair [AuditRule.ACCIDENTAL_CHECKER] sites. */
        val fixCheckers: Boolean = true,
        /**
         * Accept pixels changed by the heal only when the re-audit score
         * does not drop. When true (default), a net-negative pass is
         * rolled back to the input frame and reported with
         * [HealReport.allApplied] = false.
         */
        val requireNoRegression: Boolean = true,
    ) {
        /** Default options instance (every fixable rule on, regression guard on). */
        companion object {
            /** Shared immutable default. */
            val DEFAULT = HealOptions()
        }
    }

    /**
     * Audits [frame], applies the enabled repairs and re-audits.
     *
     * @param frame frame to heal; never mutated.
     * @param config audit configuration shared by both passes (the same
     *   tolerance that flagged a defect is used to verify the repair).
     * @param options per-rule switches; see [HealOptions].
     * @return a [HealReport] with the healed frame and both audits.
     */
    fun heal(
        frame: PixelFrame,
        config: AuditConfig = AuditConfig(),
        options: HealOptions = HealOptions.DEFAULT,
    ): HealReport {
        val before = PixelAuditor.audit(frame, config)
        if (before.isClean) {
            return HealReport(frame, before, before, emptyMap(), allApplied = true)
        }

        var pixels = frame.pixels.copyOf()
        val fixed = HashMap<AuditRule, Int>()
        val w = frame.width
        val h = frame.height

        // -- 1. dust: clear isolated pixels (heal in place on the work
        //       array; the audit's finding indices are still valid because
        //       earlier steps in this ordered pipeline do not move pixels).
        if (options.fixIsolated) {
            var count = 0
            for (f in before.findings) {
                if (f.rule != AuditRule.ISOLATED_PIXEL) continue
                pixels[f.index] = 0
                count++
            }
            if (count > 0) fixed[AuditRule.ISOLATED_PIXEL] = count
        }

        // -- 2. tiny clusters: clear every member of same-color components
        //       smaller than the audit threshold. Uses its own labeling on
        //       the *current* work array so already-healed dust does not
        //       distort component shapes.
        if (options.fixTinyClusters && config.tinyClusterArea > 0) {
            val current = PixelFrame.of(w, h, pixels)
            val cc = ConnectedComponentOps.label(current, Connectivity.FOUR, ComponentColorMode.SAME_COLOR)
            val kill = BooleanArray(w * h)
            var count = 0
            for (blob in cc.blobs) {
                if (blob.area >= config.tinyClusterArea) continue
                for (idx in blob.pixelIndices) kill[idx] = true
                count++
            }
            if (count > 0) {
                for (i in pixels.indices) if (kill[i]) pixels[i] = 0
                fixed[AuditRule.TINY_CLUSTER] = count
            }
        }

        // -- 3. enclosed holes: flood each audited hole from its reported
        //       top-left cell and fill with the majority boundary color.
        if (options.fixHoles) {
            val holeFindings = before.findings.filter { it.rule == AuditRule.ENCLOSED_HOLE }
            if (holeFindings.isNotEmpty()) {
                // Rebuild the outside-transparency mask once, then flood
                // each hole's cells (all unvisited transparent cells that
                // are not outside).
                val visited = BooleanArray(w * h)
                val queue = IntArray(w * h)
                for (x in 0 until w) {
                    floodOutside(pixels, visited, queue, w, h, x, 0)
                    floodOutside(pixels, visited, queue, w, h, x, h - 1)
                }
                for (y in 0 until h) {
                    floodOutside(pixels, visited, queue, w, h, 0, y)
                    floodOutside(pixels, visited, queue, w, h, w - 1, y)
                }
                var count = 0
                for (f in holeFindings) {
                    if (f.index >= visited.size || visited[f.index]) continue
                    val cells = collectRegion(pixels, visited, queue, w, h, f.index)
                    if (cells.isEmpty()) continue
                    val color = majorityNeighborColor(pixels, w, h, cells) ?: continue
                    for (idx in cells) pixels[idx] = color
                    count++
                }
                if (count > 0) fixed[AuditRule.ENCLOSED_HOLE] = count
            }
        }

        // -- 4. broken corners + accidental checkers: paint one orthogonal
        //       support pixel into the most-connected transparent cell of
        //       the diagonal pair (sorted for determinism; the two rules
        //       share machinery because their geometry is the same).
        val support = ArrayList<Pair<AuditRule, AuditFinding>>(8)
        if (options.fixBrokenCorners) {
            support += before.findings.filter { it.rule == AuditRule.BROKEN_CORNER }.map { AuditRule.BROKEN_CORNER to it }
        }
        if (options.fixCheckers) {
            support += before.findings.filter { it.rule == AuditRule.ACCIDENTAL_CHECKER }.map { AuditRule.ACCIDENTAL_CHECKER to it }
        }
        if (support.isNotEmpty()) {
            var corners = 0
            var checkers = 0
            for ((rule, f) in support.sortedBy { it.second.index }) {
                val color = pixels[f.index]
                if (color ushr 24 == 0) continue // earlier pass cleared it
                val x = f.index % w
                val y = f.index / w
                // The diagonal partner is one of the four diagonal cells;
                // pick the first opaque similar one (the audit only flags
                // pairs, so exactly one exists per finding geometry).
                var partner = -1
                val diagonalOffsets = intArrayOf(-1, -1, 1, -1, -1, 1, 1, 1)
                var tap = 0
                while (tap < diagonalOffsets.size) {
                    val dx = diagonalOffsets[tap]
                    val dy = diagonalOffsets[tap + 1]
                    tap += 2
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = pixels[ny * w + nx]
                    if (n ushr 24 != 0 && channelDelta(color, n) <= config.similarColorTolerance) {
                        partner = ny * w + nx
                        break
                    }
                }
                if (partner < 0) continue
                // Orthogonal cells adjacent to BOTH pixels = the two cells
                // completing the 2x2 window.
                val px = partner % w
                val py = partner / w
                val candidates = ArrayList<Int>(2)
                val c1 = y * w + px
                val c2 = py * w + x
                if (inBounds(px, y, w, h) && pixels[c1] ushr 24 == 0) candidates.add(c1)
                if (inBounds(x, py, w, h) && pixels[c2] ushr 24 == 0) candidates.add(c2)
                if (candidates.isEmpty()) continue
                // Most-connected transparent candidate wins: count opaque
                // 8-neighbors, ties break toward the smaller index (raster
                // order) so output bytes are deterministic.
                val best = candidates.maxWith(
                    compareBy({ opaqueNeighbors(pixels, w, h, it) }, { -it }),
                )
                pixels[best] = color
                if (rule == AuditRule.BROKEN_CORNER) corners++ else checkers++
            }
            if (corners > 0) fixed[AuditRule.BROKEN_CORNER] = corners
            if (checkers > 0) fixed[AuditRule.ACCIDENTAL_CHECKER] = checkers
        }

        // -- Re-audit and honor the regression guard.
        val healed = PixelFrame.of(w, h, pixels)
        val after = PixelAuditor.audit(healed, config)
        if (options.requireNoRegression && after.score < before.score) {
            return HealReport(frame, before, before, emptyMap(), allApplied = false)
        }
        return HealReport(healed, before, after, fixed, allApplied = true)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun inBounds(x: Int, y: Int, w: Int, h: Int): Boolean = x in 0 until w && y in 0 until h

    private fun channelDelta(a: Int, b: Int): Int {
        val dr = Math.abs((a ushr 16 and 0xFF) - (b ushr 16 and 0xFF))
        val dg = Math.abs((a ushr 8 and 0xFF) - (b ushr 8 and 0xFF))
        val db = Math.abs((a and 0xFF) - (b and 0xFF))
        return maxOf(dr, dg, db)
    }

    /** Opaque 8-neighborhood count of [index] — the support-pixel score. */
    private fun opaqueNeighbors(pixels: IntArray, w: Int, h: Int, index: Int): Int {
        val x = index % w
        val y = index / w
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                if (pixels[ny * w + nx] ushr 24 != 0) count++
            }
        }
        return count
    }

    /** Marks the outer transparency reachable from any border cell. */
    private fun floodOutside(src: IntArray, visited: BooleanArray, queue: IntArray, w: Int, h: Int, x0: Int, y0: Int) {
        val start = y0 * w + x0
        if (visited[start] || src[start] ushr 24 != 0) return
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            val y = idx / w
            if (x > 0 && !visited[idx - 1] && src[idx - 1] ushr 24 == 0) { visited[idx - 1] = true; queue[tail++] = idx - 1 }
            if (x < w - 1 && !visited[idx + 1] && src[idx + 1] ushr 24 == 0) { visited[idx + 1] = true; queue[tail++] = idx + 1 }
            if (y > 0 && !visited[idx - w] && src[idx - w] ushr 24 == 0) { visited[idx - w] = true; queue[tail++] = idx - w }
            if (y < h - 1 && !visited[idx + w] && src[idx + w] ushr 24 == 0) { visited[idx + w] = true; queue[tail++] = idx + w }
        }
    }

    /** Flood-collects the connected transparent region containing [start]. */
    private fun collectRegion(src: IntArray, visited: BooleanArray, queue: IntArray, w: Int, h: Int, start: Int): IntArray {
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        val cells = ArrayList<Int>(16)
        while (head < tail) {
            val idx = queue[head++]
            cells.add(idx)
            val x = idx % w
            val y = idx / w
            if (x > 0 && !visited[idx - 1] && src[idx - 1] ushr 24 == 0) { visited[idx - 1] = true; queue[tail++] = idx - 1 }
            if (x < w - 1 && !visited[idx + 1] && src[idx + 1] ushr 24 == 0) { visited[idx + 1] = true; queue[tail++] = idx + 1 }
            if (y > 0 && !visited[idx - w] && src[idx - w] ushr 24 == 0) { visited[idx - w] = true; queue[tail++] = idx - w }
            if (y < h - 1 && !visited[idx + w] && src[idx + w] ushr 24 == 0) { visited[idx + w] = true; queue[tail++] = idx + w }
        }
        return cells.toIntArray()
    }

    /** Most frequent opaque color among the 4-neighbors of [cells]. */
    private fun majorityNeighborColor(src: IntArray, w: Int, h: Int, cells: IntArray): Int? {
        val counts = HashMap<Int, Int>(16)
        for (idx in cells) {
            val x = idx % w
            val y = idx / w
            if (x > 0 && src[idx - 1] ushr 24 != 0) counts.merge(src[idx - 1], 1, Int::plus)
            if (x < w - 1 && src[idx + 1] ushr 24 != 0) counts.merge(src[idx + 1], 1, Int::plus)
            if (y > 0 && src[idx - w] ushr 24 != 0) counts.merge(src[idx - w], 1, Int::plus)
            if (y < h - 1 && src[idx + w] ushr 24 != 0) counts.merge(src[idx + w], 1, Int::plus)
        }
        if (counts.isEmpty()) return null
        return counts.entries.maxWith(compareBy({ it.value }, { it.key })).key
    }
}
