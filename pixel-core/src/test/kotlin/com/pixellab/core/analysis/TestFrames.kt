package com.pixellab.core.analysis

import com.pixellab.core.model.PixelFrame

/**
 * Tiny raster builders shared by the analysis test suite. All frames are
 * small (≤ 8x8) so expected values are hand-verifiable.
 */
internal object TestFrames {

    /** ARGB packer. */
    fun rgb(r: Int, g: Int, b: Int): Int = 0xFF shl 24 or (r shl 16) or (g shl 8) or b

    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** Builds a frame from per-row strings: '.'=transparent, other chars map to [palette]. */
    fun fromRows(vararg rows: String, palette: Map<Char, Int> = defaultPalette()): PixelFrame {
        require(rows.isNotEmpty()) { "Need at least one row" }
        val w = rows[0].length
        require(rows.all { it.length == w }) { "Row length mismatch" }
        val h = rows.size
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = rows[y][x]
                px[y * w + x] = if (c == '.') 0 else (palette[c] ?: error("No palette entry for '$c'"))
            }
        }
        return PixelFrame.of(w, h, px)
    }

    /** Single solid color frame. */
    fun solid(width: Int, height: Int, argb: Int): PixelFrame =
        PixelFrame.of(width, height, IntArray(width * height) { argb })

    fun defaultPalette(): Map<Char, Int> = mapOf(
        'r' to RED, 'g' to GREEN, 'b' to BLUE,
        'k' to BLACK, 'w' to WHITE,
        'a' to rgb(0x80, 0x80, 0x80),
        'c' to rgb(0x00, 0xFF, 0xFF),
        'm' to rgb(0xFF, 0x00, 0xFF),
        'y' to rgb(0xFF, 0xFF, 0x00),
    )
}
