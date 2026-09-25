package com.pixellab.core.transform

import com.pixellab.core.model.PixelFrame
import kotlin.math.abs

/**
 * xBR-family 2x upscaler (CPU port of the "xBR level-1" rules by
 * Hyllian, widely used in emulator renderers).
 *
 * xBR detects the local edge direction by comparing the center pixel
 * against its 8 neighbors with luminance-weighted distances, then
 * paints the 2x2 output block so that:
 *
 *  * flat interiors copy the center (no shimmer),
 *  * straight edges stay perfectly straight,
 *  * diagonal edges get "smoothed" by letting the block corners adopt
 *    the neighbor the edge is turning toward.
 *
 * Output pixels are always exact input colors — xBR never blends.
 */
object XbrScale {

    /**
     * Neighborhood naming shared by the rule descriptions:
     * ```
     * A B C
     * D E F
     * G H I
     * ```
     * Output block (row-major):
     * ```
     * E0 E1
     * E2 E3
     * ```
     */
    fun xbr2x(frame: PixelFrame): PixelFrame {
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val outW = w * 2
        val out = IntArray(outW * h * 2)
        for (y in 0 until h) {
            val up = if (y > 0) y - 1 else y
            val down = if (y < h - 1) y + 1 else y
            for (x in 0 until w) {
                val left = if (x > 0) x - 1 else x
                val right = if (x < w - 1) x + 1 else x
                val a = src[up * w + left]
                val b = src[up * w + x]
                val c = src[up * w + right]
                val d = src[y * w + left]
                val e = src[y * w + x]
                val f = src[y * w + right]
                val g = src[down * w + left]
                val hh = src[down * w + x]
                val ii = src[down * w + right]

                // Level-1 rules, evaluated per sub-pixel:
                val e0 = rule(e, b, d, a, f, hh)
                val e1 = rule(e, b, f, c, d, hh)
                val e2 = rule(e, d, hh, g, b, f)
                val e3 = rule(e, f, hh, ii, b, d)

                val row0 = (y * 2) * outW + x * 2
                val row1 = (y * 2 + 1) * outW + x * 2
                out[row0] = e0
                out[row0 + 1] = e1
                out[row1] = e2
                out[row1 + 1] = e3
            }
        }
        return PixelFrame.of(outW, h * 2, out)
    }

    /**
     * One sub-pixel's xBR level-1 rule.
     *
     * @param e center color
     * @param n1 first orthogonal neighbor of the sub-pixel (e.g. up)
     * @param n2 second orthogonal neighbor (e.g. left)
     * @param diag the diagonal neighbor between n1 and n2 (e.g. up-left)
     * @param far1 the neighbor opposite n2 (right when n2 is left)
     * @param far2 the neighbor opposite n1 (down when n1 is up)
     */
    private fun rule(e: Int, n1: Int, n2: Int, diag: Int, far1: Int, far2: Int): Int {
        // Only edge pixels participate: both orthogonal neighbors must
        // differ from the center for a corner candidate.
        if (n1 == e && n2 == e) return e
        if (n1 == n2) return e
        // The classic xBR "corner smoothing": the sub-pixel takes the
        // diagonal color when (a) the two orthogonal neighbors disagree
        // with each other AND with the center (a genuine corner), and
        // (b) the diagonal agrees with the side the edge turns toward.
        if (n1 != e && n2 != e) {
            if (diag == n1 && diag != n2 && far2 == e) return diag
            if (diag == n2 && diag != n1 && far1 == e) return diag
            // When the diagonal is a third color entirely, extend the
            // stronger neighbor: pick whichever orthogonal neighbor is
            // closer in luma distance (ties → keep e).
            val d1 = distance(e, n1)
            val d2 = distance(e, n2)
            return if (d1 < d2) n1 else if (d2 < d1) n2 else e
        }
        // One neighbor matches: flat edge — copy the center.
        return e
    }

    /** Rec.709-weighted channel distance between two packed pixels. */
    private fun distance(a: Int, b: Int): Int {
        val dr = abs((a ushr 16 and 0xFF) - (b ushr 16 and 0xFF))
        val dg = abs((a ushr 8 and 0xFF) - (b ushr 8 and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        val da = abs((a ushr 24) - (b ushr 24))
        // Luma weights from Rec.709, alpha as a tiebreaker weight.
        return (2126 * dr + 7152 * dg + 722 * db) / 10000 + da * 200
    }
}
