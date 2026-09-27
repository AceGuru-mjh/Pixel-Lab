package com.pixellab.mcp

import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject

/**
 * One stamp compositing pass: the produced cel plus what happened to the
 * source pixels.
 */
class StampResult internal constructor(
    /** New active-cel raster (`width x height` of the target canvas). */
    val cel: PixelFrame,
    /** Source pixels that landed inside the canvas (overlays counted once). */
    val placed: Int,
    /** Source pixels that fell outside the canvas and were dropped. */
    val clipped: Int,
)

/**
 * Shared stamp compositor for the closed-loop tools ([draw_text],
 * [sketch_draw], `convert_image` with `session_id`, `io_import_image`
 * overlays): overlays every non-transparent pixel of a source raster onto a
 * project cel at an offset, clipping at the canvas bounds.
 *
 * This is the "rubber stamp" the engine does not expose directly —
 * [com.pixellab.core.engine.PixelEngine.applyFrame] overwrites a whole cel,
 * while these tools need *additive* placement of a rendered artifact
 * (text frame, sketch points, converted pixels) onto whatever the session
 * already holds. The result feeds straight into `applyFrame` / `withCel`
 * so the normal engine history and store write-back semantics apply.
 */
internal object CelStamp {

    /**
     * Overlays [source] onto the active cel at (`x`, `y`) of [project]'s
     * active layer (a missing cel composites onto transparency). See
     * [stamp] for the compositing and clipping rules.
     */
    fun stamp(project: SpriteProject, source: PixelFrame, x: Int, y: Int): StampResult =
        stamp(project.width, project.height, project.activeCel(), source, x, y)

    /**
     * Overlays [source] onto [base] (or transparency when null) inside a
     * `width x height` canvas at offset (`x`, `y`).
     *
     * Every source pixel with non-zero alpha replaces the target pixel at
     * (`x + sourceX`, `y + sourceY`); source pixels outside the canvas are
     * dropped and counted in [StampResult.clipped]. Canvas pixels the
     * source leaves untouched (including its transparent cells) keep the
     * value [base] had.
     */
    fun stamp(width: Int, height: Int, base: PixelFrame?, source: PixelFrame, x: Int, y: Int): StampResult {
        val target = base?.copyPixels() ?: IntArray(width * height)
        var placed = 0
        var clipped = 0
        val src = source.pixels
        for (sy in 0 until source.height) {
            val ty = y + sy
            if (ty < 0 || ty >= height) {
                clipped += countRow(src, sy, source.width)
                continue
            }
            val srcRow = sy * source.width
            for (sx in 0 until source.width) {
                val value = src[srcRow + sx]
                if (value ushr 24 == 0) continue
                val tx = x + sx
                if (tx < 0 || tx >= width) {
                    clipped++
                    continue
                }
                target[ty * width + tx] = value
                placed++
            }
        }
        return StampResult(PixelFrame.of(width, height, target), placed, clipped)
    }

    /** Non-transparent cells of one source row (fully clipped row). */
    private fun countRow(src: IntArray, sy: Int, width: Int): Int {
        val row = sy * width
        var count = 0
        for (i in 0 until width) if (src[row + i] ushr 24 != 0) count++
        return count
    }
}
