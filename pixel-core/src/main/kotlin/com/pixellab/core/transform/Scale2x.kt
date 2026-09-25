package com.pixellab.core.transform

import com.pixellab.core.model.PixelFrame

/**
 * AdvMAME-family integer upscalers: Scale2x and Scale3x, plus the
 * corner-variant of 2x.
 *
 * These scalers look at each pixel's 3x3 neighborhood, detect the local
 * edge direction, and paint the output block so that edges stay crisp
 * (colors are never mixed — output pixels are always copies of input
 * pixels). They are the classic "no-blur" upscales used by arcade
 * emulators and pixel-art tools.
 */
object Scale2x {

    /**
     * Scale2x (AdvMAME 2x): each input pixel becomes a 2x2 block.
     *
     * Neighborhood naming (industry standard):
     * ```
     * A B C
     * D E F     →  E0 E1
     * G H I         E2 E3
     * ```
     * Rules:
     * ```
     * E0 = (D == B && D != H && B != F) ? D : E
     * E1 = (B == F && B != D && F != H) ? B : E
     * E2 = (D == H && D != B && H != F) ? D : E
     * E3 = (H == F && D != H && B != F) ? H : E
     * ```
     * (E0/E1/E2/E3 are ordered left-right, top-bottom.)
     */
    fun scale2x(frame: PixelFrame): PixelFrame {
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = IntArray(w * 2 * h * 2)
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

                val e0 = if (d == b && d != hh && b != f) d else e
                val e1 = if (b == f && b != d && f != hh) b else e
                val e2 = if (d == hh && d != b && hh != f) d else e
                val e3 = if (hh == f && d != hh && b != f) hh else e

                val row0 = (y * 2) * (w * 2) + x * 2
                val row1 = (y * 2 + 1) * (w * 2) + x * 2
                out[row0] = e0
                out[row0 + 1] = e1
                out[row1] = e2
                out[row1 + 1] = e3
            }
        }
        return PixelFrame.of(w * 2, h * 2, out)
    }

    /**
     * Scale3x (AdvMAME 3x): each input pixel becomes a 3x3 block with
     * the full rule set from the AdvMAME Scale3x filter. The center and
     * edge-middle outputs mostly copy E; the ring corners adopt the
     * neighbor that the two adjacent edge rules agree on.
     *
     * Only colors from the input ever appear in the output.
     */
    fun scale3x(frame: PixelFrame): PixelFrame {
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val outW = w * 3
        val out = IntArray(outW * h * 3)
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

                // AdvMAME Scale3x rule set (0..8 outputs, row-major):
                val e0 = if (d == b && d != hh && b != f) d else e
                val e1 = e
                val e2 = if (b == f && b != d && f != hh) b else e
                val e3 = e
                val e4 = e
                val e5 = e
                val e6 = if (d == hh && d != b && hh != f) d else e
                val e7 = e
                val e8 = if (hh == f && d != hh && b != f) hh else e

                // Corners of the ring sharpen further when the diagonal
                // agrees with both adjacent edge conditions.
                val c0 = if (d == b && d != hh && b != f && d != a && b != a) a else e0
                val c2 = if (b == f && b != d && f != hh && b != c && f != c) c else e2
                val c6 = if (d == hh && d != b && hh != f && d != g && hh != g) g else e6
                val c8 = if (hh == f && d != hh && b != f && hh != ii && f != ii) ii else e8

                val row0 = (y * 3) * outW + x * 3
                val row1 = (y * 3 + 1) * outW + x * 3
                val row2 = (y * 3 + 2) * outW + x * 3
                out[row0] = c0; out[row0 + 1] = e0; out[row0 + 2] = c2
                out[row1] = e3; out[row1 + 1] = e4; out[row1 + 2] = e5
                out[row2] = c6; out[row2 + 1] = e6; out[row2 + 2] = c8
            }
        }
        return PixelFrame.of(outW, h * 3, out)
    }

    /**
     * Scale2x "corner" variant: the same 2x edge detection, but the four
     * output sub-pixels additionally consult the diagonal (A/C/G/I) —
     * when the edge pair and the diagonal all agree, the sub-pixel takes
     * the diagonal color. Slightly sharper corners than [scale2x] at the
     * cost of one extra comparison per sub-pixel.
     */
    fun scale2xCorners(frame: PixelFrame): PixelFrame {
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

                var e0 = if (d == b && d != hh && b != f) d else e
                var e1 = if (b == f && b != d && f != hh) b else e
                var e2 = if (d == hh && d != b && hh != f) d else e
                var e3 = if (hh == f && d != hh && b != f) hh else e
                if (d == b && d != hh && b != f && d == a) e0 = a
                if (b == f && b != d && f != hh && b == c) e1 = c
                if (d == hh && d != b && hh != f && d == g) e2 = g
                if (hh == f && d != hh && b != f && hh == ii) e3 = ii

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
}

/**
 * EPX-family integer upscalers (2x and 3x) with corner-detection
 * semantics: each output sub-pixel consults the two orthogonal
 * neighbors of the source pixel; when those neighbors differ (a
 * potential corner) the sub-pixel adopts the neighbor matching the
 * source color, extending edges diagonally without inventing colors.
 */
object EpxScale {

    /**
     * EPX 2x: each input pixel E with neighbors
     * ```
     *   B
     * D E F
     *   H
     * ```
     * produces a 2x2 block. For the top-left sub-pixel (neighbors B up,
     * D left): if B != D and E == B, use B; if B != D and E == D, use D;
     * otherwise copy E. The other three sub-pixels follow the same rule
     * with their respective orthogonal pair (B/F for top-right, D/H for
     * bottom-left, F/H for bottom-right).
     *
     * Interior pixels (all four neighbors equal E) copy E into all four
     * sub-pixels; uniform areas stay perfectly flat.
     */
    fun epx2x(frame: PixelFrame): PixelFrame {
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val outW = w * 2
        val out = IntArray(outW * h * 2)
        for (y in 0 until h) {
            val up = if (y > 0) y - 1 else y
            val down = if (y < h - 1) y + 1 else y
            for (x in 0 until w) {
                val b = src[up * w + x]
                val d = src[y * w + if (x > 0) x - 1 else x]
                val e = src[y * w + x]
                val f = src[y * w + if (x < w - 1) x + 1 else x]
                val hh = src[down * w + x]

                val row0 = (y * 2) * outW + x * 2
                val row1 = (y * 2 + 1) * outW + x * 2
                out[row0] = pickCorner(e, b, d)
                out[row0 + 1] = pickCorner(e, b, f)
                out[row1] = pickCorner(e, d, hh)
                out[row1 + 1] = pickCorner(e, hh, f)
            }
        }
        return PixelFrame.of(outW, h * 2, out)
    }

    /**
     * EPX 3x: the center 3x3 block copies E into the cross (4 edge
     * cells + center); the four corners of the block apply the same
     * [pickCorner] rule as [epx2x] so edges extend diagonally. The
     * remaining ring cells copy E.
     */
    fun epx3x(frame: PixelFrame): PixelFrame {
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val outW = w * 3
        val out = IntArray(outW * h * 3)
        for (y in 0 until h) {
            val up = if (y > 0) y - 1 else y
            val down = if (y < h - 1) y + 1 else y
            for (x in 0 until w) {
                val b = src[up * w + x]
                val d = src[y * w + if (x > 0) x - 1 else x]
                val e = src[y * w + x]
                val f = src[y * w + if (x < w - 1) x + 1 else x]
                val hh = src[down * w + x]

                val row0 = (y * 3) * outW + x * 3
                val row1 = (y * 3 + 1) * outW + x * 3
                val row2 = (y * 3 + 2) * outW + x * 3
                out[row0] = pickCorner(e, b, d)
                out[row0 + 1] = e
                out[row0 + 2] = pickCorner(e, b, f)
                out[row1] = e
                out[row1 + 1] = e
                out[row1 + 2] = e
                out[row2] = pickCorner(e, d, hh)
                out[row2 + 1] = e
                out[row2 + 2] = pickCorner(e, hh, f)
            }
        }
        return PixelFrame.of(outW, h * 3, out)
    }

    /**
     * Corner pick: [e] is the center color, [n1]/[n2] two orthogonal
     * neighbor colors. When the neighbors disagree (edge corner) and E
     * matches one of them, that neighbor wins; otherwise E stays.
     */
    private fun pickCorner(e: Int, n1: Int, n2: Int): Int {
        if (n1 == n2) return e
        if (e == n1 && e != n2) return n1
        if (e == n2 && e != n1) return n2
        return e
    }
}
