package com.pixellab.core.describe

import com.pixellab.core.model.PixelFrame
import kotlin.math.abs

/**
 * One symmetry probe result.
 */
class SymmetryVerdict(
    /** The symmetry kind tested. */
    val kind: SymmetryKind,
    /** True when mismatched pixels are within tolerance. */
    val holds: Boolean,
    /** Number of compared pairs whose colors differ beyond tolerance. */
    val mismatchedPairs: Int,
    /** Total pixel pairs compared (visible pixels only). */
    val comparedPairs: Int,
) {
    /** Mismatch ratio 0..1. */
    val mismatchRatio: Double get() = if (comparedPairs == 0) 0.0 else mismatchedPairs.toDouble() / comparedPairs

    override fun toString(): String =
        "$kind=${if (holds) "yes" else "no"} (${mismatchedPairs}/$comparedPairs off)"
}

/**
 * Symmetry axes a frame can be probed for.
 */
enum class SymmetryKind {
    /** Mirror across the vertical center axis (left ↔ right). */
    HORIZONTAL,

    /** Mirror across the horizontal center axis (top ↔ bottom). */
    VERTICAL,

    /** 180° rotational symmetry about the center. */
    ROT180,

    /** Main-diagonal (top-left ↔ bottom-right) mirror; square frames only. */
    DIAGONAL,

    /** Anti-diagonal (top-right ↔ bottom-left) mirror; square frames only. */
    ANTI_DIAGONAL,
}

/**
 * Result of a full symmetry sweep.
 */
class SymmetryReport(
    /** Frame the report describes. */
    val frame: PixelFrame,
    /** One verdict per probed kind (diagonals omitted on non-square frames). */
    val verdicts: List<SymmetryVerdict>,
) {
    /** All kinds that hold. */
    val holding: List<SymmetryKind> get() = verdicts.filter { it.holds }.map { it.kind }

    override fun toString(): String = "Symmetry(${holding.joinToString(",") { it.name.lowercase() }})"
}

/**
 * Symmetry detection for pixel-art canvases — the property agents most
 * often want to *enforce* ("make the sprite left-right symmetric") or
 * *discover* ("does this shield have 4-fold symmetry?").
 *
 * Pairing rule: a pixel at (`x`, `y`) pairs with its mirrored/rotated
 * counterpart; both must agree on *visibility* (both transparent or both
 * visible) and, when visible, on color within [tolerance] (max per-channel
 * distance). Fully-transparent frames trivially satisfy every kind.
 *
 * Odd-sized axes pair through the center row/column (which is self-paired
 * and never counted). Pure, deterministic, O(pixels) per probe.
 */
object SymmetryAnalyzer {

    /** Default per-channel tolerance for "same color" (0 = exact). */
    const val DEFAULT_TOLERANCE: Int = 0

    /**
     * Probes every applicable [SymmetryKind] on [frame] (diagonals only on
     * square frames) with per-channel [tolerance].
     */
    fun analyze(frame: PixelFrame, tolerance: Int = DEFAULT_TOLERANCE): SymmetryReport {
        require(tolerance in 0..255) { "tolerance must be in 0..255 (was $tolerance)" }
        val verdicts = ArrayList<SymmetryVerdict>(5)
        verdicts.add(probe(frame, SymmetryKind.HORIZONTAL, tolerance))
        verdicts.add(probe(frame, SymmetryKind.VERTICAL, tolerance))
        verdicts.add(probe(frame, SymmetryKind.ROT180, tolerance))
        if (frame.width == frame.height) {
            verdicts.add(probe(frame, SymmetryKind.DIAGONAL, tolerance))
            verdicts.add(probe(frame, SymmetryKind.ANTI_DIAGONAL, tolerance))
        }
        return SymmetryReport(frame, verdicts)
    }

    /**
     * Probes a single [kind]. `holds` requires *zero* mismatched pairs —
     * near-symmetry is visible through the pair counts.
     */
    fun probe(frame: PixelFrame, kind: SymmetryKind, tolerance: Int = DEFAULT_TOLERANCE): SymmetryVerdict {
        require(tolerance in 0..255) { "tolerance must be in 0..255 (was $tolerance)" }
        val w = frame.width
        val h = frame.height
        var mismatched = 0
        var compared = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (px, py) = when (kind) {
                    SymmetryKind.HORIZONTAL -> w - 1 - x to y
                    SymmetryKind.VERTICAL -> x to h - 1 - y
                    SymmetryKind.ROT180 -> w - 1 - x to h - 1 - y
                    SymmetryKind.DIAGONAL -> y to x
                    SymmetryKind.ANTI_DIAGONAL -> h - 1 - y to w - 1 - x
                }
                if (px < x || (px == x && py <= y)) continue // each pair once
                val a = frame.pixels[y * w + x]
                val b = frame.pixels[py * w + px]
                val aVisible = a ushr 24 != 0
                val bVisible = b ushr 24 != 0
                if (aVisible != bVisible) {
                    compared++
                    mismatched++
                    continue
                }
                if (!aVisible) continue
                compared++
                if (channelDistance(a, b) > tolerance) mismatched++
            }
        }
        return SymmetryVerdict(kind, mismatched == 0, mismatched, compared)
    }

    /** Max per-channel distance between two opaque colors. */
    internal fun channelDistance(a: Int, b: Int): Int = maxOf(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        abs((a and 0xFF) - (b and 0xFF)),
    )

    /**
     * One-line summary for descriptions: "left-right and 180° symmetric"
     * / "asymmetric (H 12/80 off)".
     */
    fun summarize(report: SymmetryReport): String {
        if (report.verdicts.isEmpty()) return "no symmetry probes ran"
        val holding = report.holding
        if (holding.isEmpty()) {
            val worst = report.verdicts.minByOrNull { it.mismatchRatio } ?: return "asymmetric"
            return "asymmetric (closest: ${worst.kind.name.lowercase().replace('_', ' ')}, ${worst.mismatchedPairs}/${worst.comparedPairs} pairs off)"
        }
        return holding.joinToString(" and ") { it.name.lowercase().replace('_', ' ') } + " symmetric"
    }
}
