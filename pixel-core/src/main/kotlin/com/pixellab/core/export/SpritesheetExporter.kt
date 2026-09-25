package com.pixellab.core.export

import com.pixellab.core.model.PixelFrame

/**
 * Integer nearest-neighbor upscale shared by the spritesheet and Codex pet
 * exporters.
 *
 * Each source pixel becomes a `scale x scale` block of the same color and each
 * source row is emitted [scale] times, so pixel-art edges stay crisp with no
 * interpolation. This is the single scaling primitive of the export package —
 * [SpritesheetExporter] and [CodexPetExporter] both route through it so every
 * exported raster scales with identical semantics.
 *
 * @param frame raster to upscale; pixels are packed `0xAARRGGBB`, row-major.
 * @param scale integer upscale factor, `>= 1`; `1` returns [frame] itself.
 * @return a new frame sized `frame.width * scale x frame.height * scale`, or
 * [frame] when `scale == 1`.
 * @throws IllegalArgumentException if [scale] is below 1 or the scaled raster
 * would exceed the 31-bit dimension or `IntArray` size limits.
 */
internal fun nearestNeighborScale(frame: PixelFrame, scale: Int): PixelFrame {
    require(scale >= 1) { "scale must be >= 1 (was $scale)" }
    if (scale == 1) return frame
    val width = frame.width
    val height = frame.height
    val scaledWidth = width.toLong() * scale
    val scaledHeight = height.toLong() * scale
    require(
        scaledWidth in 1..Int.MAX_VALUE.toLong() &&
            scaledHeight in 1..Int.MAX_VALUE.toLong(),
    ) {
        "Scaled frame ${scaledWidth}x${scaledHeight} exceeds the 31-bit dimension limit"
    }
    val pixelCount = scaledWidth * scaledHeight
    require(pixelCount <= Int.MAX_VALUE.toLong()) {
        "Scaled frame needs $pixelCount pixels, beyond the IntArray limit"
    }
    val source = frame.pixels
    val out = IntArray(pixelCount.toInt())
    var o = 0
    for (y in 0 until height) {
        val rowStart = y * width
        for (repetition in 0 until scale) {
            for (x in 0 until width) {
                val color = source[rowStart + x]
                var copy = 0
                while (copy < scale) {
                    out[o++] = color
                    copy++
                }
            }
        }
    }
    return PixelFrame.of(scaledWidth.toInt(), scaledHeight.toInt(), out)
}

/**
 * Composes animation frames into one spritesheet raster.
 *
 * Every frame occupies a uniform cell of `max(frameWidth) * scale + 2 * margin`
 * by `max(frameHeight) * scale + 2 * margin` pixels (the maximum is taken
 * across all frames, which equals the common frame size for the usual
 * same-size input). Frame content is laid into the cell starting at the cell
 * origin offset by [margin] on both axes; the margin ring and any cell
 * without a frame stay fully transparent.
 *
 * Layouts:
 * - [SpritesheetLayout.HORIZONTAL] — one row, frames left to right;
 * - [SpritesheetLayout.VERTICAL] — one column, frames top to bottom;
 * - [SpritesheetLayout.GRID] — [columns] cells per row, rows wrap; when
 *   `columns` exceeds the frame count the trailing cells of the first row are
 *   left empty.
 *
 * The result is a plain pixel array plus geometry: deterministic and free of
 * any encoder concerns ([Exporter] feeds it to [PngCodec]).
 */
internal object SpritesheetExporter {

    /**
     * A composed spritesheet raster.
     *
     * @property width sheet width in pixels.
     * @property height sheet height in pixels.
     * @property pixels row-major ARGB pixels, `width * height` long; cells
     * without content (and margin rings) are fully transparent `0x00000000`.
     */
    internal data class SheetResult(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    /**
     * Composes [frames] into a single sheet raster according to [layout].
     *
     * @param frames frames to pack; all frames are read only. May have
     * differing dimensions — the cell size follows the largest frame so no
     * content overflows its cell.
     * @param scale integer upscale factor applied to each frame (`>= 1`).
     * @param layout one of [SpritesheetLayout.HORIZONTAL],
     * [SpritesheetLayout.VERTICAL] or [SpritesheetLayout.GRID].
     * @param columns cells per row for [SpritesheetLayout.GRID]; ignored by
     * the single-row/single-column layouts (`>= 1`).
     * @param margin transparent padding around the frame content inside every
     * cell (`>= 0`).
     * @return the sheet raster and its dimensions.
     * @throws IllegalArgumentException if [frames] is empty, [scale] is below
     * 1, [columns] is below 1, [margin] is negative, or the sheet would
     * exceed the 31-bit dimension or `IntArray` size limits.
     */
    fun compose(
        frames: List<PixelFrame>,
        scale: Int,
        layout: SpritesheetLayout,
        columns: Int,
        margin: Int,
    ): SheetResult {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(scale >= 1) { "scale must be >= 1 (was $scale)" }
        require(columns >= 1) { "columns must be >= 1 (was $columns)" }
        require(margin >= 0) { "margin must be >= 0 (was $margin)" }

        var maxWidth = 0
        var maxHeight = 0
        for (frame in frames) {
            if (frame.width > maxWidth) maxWidth = frame.width
            if (frame.height > maxHeight) maxHeight = frame.height
        }
        val cellWidth = maxWidth.toLong() * scale + 2L * margin
        val cellHeight = maxHeight.toLong() * scale + 2L * margin
        require(
            cellWidth in 1..Int.MAX_VALUE.toLong() &&
                cellHeight in 1..Int.MAX_VALUE.toLong(),
        ) {
            "Cell size ${cellWidth}x${cellHeight} exceeds the 31-bit dimension limit"
        }

        val frameCount = frames.size
        val gridColumns: Int
        val gridRows: Int
        when (layout) {
            SpritesheetLayout.HORIZONTAL -> {
                gridColumns = frameCount
                gridRows = 1
            }
            SpritesheetLayout.VERTICAL -> {
                gridColumns = 1
                gridRows = frameCount
            }
            SpritesheetLayout.GRID -> {
                gridColumns = columns
                gridRows = (frameCount + columns - 1) / columns
            }
        }
        val sheetWidth = gridColumns.toLong() * cellWidth
        val sheetHeight = gridRows.toLong() * cellHeight
        require(
            sheetWidth in 1..Int.MAX_VALUE.toLong() &&
                sheetHeight in 1..Int.MAX_VALUE.toLong(),
        ) {
            "Sheet size ${sheetWidth}x${sheetHeight} exceeds the 31-bit dimension limit"
        }
        val pixelCount = sheetWidth * sheetHeight
        require(pixelCount <= Int.MAX_VALUE.toLong()) {
            "Sheet needs $pixelCount pixels, beyond the IntArray limit"
        }

        val width = sheetWidth.toInt()
        val height = sheetHeight.toInt()
        val cellW = cellWidth.toInt()
        val cellH = cellHeight.toInt()
        val pixels = IntArray(pixelCount.toInt()) // zero-filled = transparent
        for (index in frames.indices) {
            val frame = frames[index]
            val column: Int
            val row: Int
            when (layout) {
                SpritesheetLayout.HORIZONTAL -> {
                    column = index
                    row = 0
                }
                SpritesheetLayout.VERTICAL -> {
                    column = 0
                    row = index
                }
                SpritesheetLayout.GRID -> {
                    column = index % columns
                    row = index / columns
                }
            }
            val scaled = nearestNeighborScale(frame, scale)
            val originX = column * cellW + margin
            val originY = row * cellH + margin
            blit(pixels, width, scaled, originX, originY)
        }
        return SheetResult(width, height, pixels)
    }

    /**
     * Copies every row of [frame] into [sheet] with its top-left corner at
     * (`[originX]`, `[originY]`). Callers guarantee the frame fits inside the
     * sheet, so no clipping is performed.
     */
    private fun blit(sheet: IntArray, sheetWidth: Int, frame: PixelFrame, originX: Int, originY: Int) {
        val source = frame.pixels
        for (y in 0 until frame.height) {
            System.arraycopy(
                source,
                y * frame.width,
                sheet,
                (originY + y) * sheetWidth + originX,
                frame.width,
            )
        }
    }
}
