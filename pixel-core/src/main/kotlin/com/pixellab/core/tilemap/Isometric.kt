package com.pixellab.core.tilemap

import com.pixellab.core.model.PixelFrame

/**
 * A free pixel-space coordinate. Unlike [TileCoord] (grid cells) and
 * [com.pixellab.core.model.PixelPoint] (canvas pixels), isometric screen
 * space legitimately extends into negative x — the diamond grid centers on
 * the origin — so no sign constraint is imposed.
 */
data class PixelCoord(val x: Int, val y: Int)

/**
 * Isometric tile-grid math and renderer.
 *
 * ## Coordinate system
 *
 * A cell (`x`, `y`) maps to a `tileW x tileH` bounding box whose top-left
 * corner sits at
 *
 * ```
 * sx = (x - y) * tileW / 2
 * sy = (x + y) * tileH / 2
 * ```
 *
 * which produces the classic 2:1 diamond lattice (any `tileW:tileH` ratio
 * works; odd dimensions truncate the halving toward zero, so even values are
 * recommended). Worked examples for `tileW = 16, tileH = 8`:
 *
 * ```
 * cellToScreen(0,0) = (  0,  0)   cellToScreen(1,0) = (  8,  4)
 * cellToScreen(0,1) = ( -8,  4)   cellToScreen(1,1) = (  0,  8)
 * cellToScreen(2,1) = (  8,  8)   cellToScreen(2,2) = (  0, 16)
 * ```
 *
 * Diamond art within a tile box: top vertex at `(tileW/2, 0)`, right vertex
 * at `(tileW-1, tileH/2)`'s column, bottom vertex `(tileW/2, tileH-1)`, left
 * vertex at column 0 (see [isoDiamondMask]).
 *
 * All functions are pure and deterministic; no platform calls, no state.
 */
object Isometric {

    /** Minimum supported tile dimension (the diamond needs a middle axis). */
    private const val MIN_DIM = 2

    /**
     * Grid cell to screen space: the top-left of the cell's `tileW x tileH`
     * diamond bounding box.
     *
     * @param tileW tile bounding-box width, `>= 2` (halved internally).
     * @param tileH tile bounding-box height, `>= 2`.
     * @throws IllegalArgumentException for non-negative cells or tile
     *   dimensions below 2.
     */
    fun cellToScreen(x: Int, y: Int, tileW: Int, tileH: Int): PixelCoord {
        require(x >= 0 && y >= 0) { "cell coordinates must be non-negative (x=$x, y=$y)" }
        require(tileW >= MIN_DIM && tileH >= MIN_DIM) {
            "tile dimensions must be >= ${MIN_DIM} (tileW=$tileW, tileH=$tileH)"
        }
        return PixelCoord((x - y) * (tileW / 2), (x + y) * (tileH / 2))
    }

    /**
     * Screen space back to a grid cell — the algebraic inverse of
     * [cellToScreen] with rounding:
     *
     * ```
     * cx = (sx / (tileW/2) + sy / (tileH/2)) / 2   ->  x = round(cx)
     * cy = (sy / (tileH/2) - sx / (tileW/2)) / 2   ->  y = round(cy)
     * ```
     *
     * Rounding is half-up toward `+Infinity` (Java `Math.round`), which makes
     * the rounding cells diamonds centered on the cellToScreen lattice
     * points: **every `cellToScreen(x, y)` point round-trips exactly** —
     * `screenToCell(cellToScreen(x, y)) == TileCoord(x, y)`. Points sampled
     * inside the half-open rounding diamond resolve to the cell whose
     * lattice point is nearest in the sheared metric; ties (edges of the
     * rounding diamonds) resolve to the cell with the larger coordinate.
     * There is no upper clamp (the map size is unknown here), but the result
     * is clamped to be non-negative to honor the [TileCoord] invariant —
     * far-off screen points collapse to the `(0, y)` / `(x, 0)` axes.
     *
     * @throws IllegalArgumentException for tile dimensions below 2.
     */
    fun screenToCell(sx: Int, sy: Int, tileW: Int, tileH: Int): TileCoord {
        require(tileW >= MIN_DIM && tileH >= MIN_DIM) {
            "tile dimensions must be >= ${MIN_DIM} (tileW=$tileW, tileH=$tileH)"
        }
        val halfW = (tileW / 2).toDouble()
        val halfH = (tileH / 2).toDouble()
        val a = sx / halfW
        val b = sy / halfH
        val x = Math.round((a + b) / 2.0).toInt()
        val y = Math.round((b - a) / 2.0).toInt()
        return TileCoord(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }

    /**
     * An opaque white (`0xFFFFFFFF`) diamond mask tile of `tileW x tileH`.
     *
     * A pixel (`x`, `y`) is inside the diamond iff
     * `|x - tileW/2| * (tileH/2) + |y - tileH/2| * (tileW/2) <= (tileW/2) * (tileH/2)`
     * (integer division for the halves). This is the diamond whose vertices
     * are the midpoints of the box edges: the full middle row/column is
     * opaque and the corners are transparent. For `16x8`:
     *
     * ```
     * x: 0123456789012345
     * y=0:        #                  (only x=8)
     * y=4: ################          (full row)
     * y=7:        #                  (only x=8)
     * ```
     *
     * @throws IllegalArgumentException for tile dimensions below 2.
     */
    fun isoDiamondMask(tileW: Int, tileH: Int): PixelFrame {
        require(tileW >= MIN_DIM && tileH >= MIN_DIM) {
            "tile dimensions must be >= ${MIN_DIM} (tileW=$tileW, tileH=$tileH)"
        }
        val cx = tileW / 2
        val cy = tileH / 2
        val a = tileW / 2
        val b = tileH / 2
        val limit = a * b
        val white = 0xFFFFFFFF.toInt()
        val out = IntArray(tileW * tileH)
        for (y in 0 until tileH) {
            val dy = kotlin.math.abs(y - cy) * a
            for (x in 0 until tileW) {
                if (dy + kotlin.math.abs(x - cx) * b <= limit) {
                    out[y * tileW + x] = white
                }
            }
        }
        return PixelFrame.of(tileW, tileH, out)
    }

    /**
     * Renders a whole map in isometric painter order.
     *
     * The canvas is sized to the exact bounds of all cell boxes: for a
     * `w x h` cell map it is `(w + h) * tileW/2 x (w + h) * tileH/2` for even
     * tiles (odd sizes adjust by the truncation), widened vertically when
     * [heightOffsets] raise/lower rows. Cells paint in ascending `(x + y)`
     * order (ties broken by ascending `x`) so nearer diamonds always cover
     * farther ones; within one cell the map's layers composite bottom-up
     * with source-over alpha blending and the cell's [TileFlip] applies.
     *
     * Each tile is drawn **as-is** at its diamond box origin — the art is
     * expected to be diamond-shaped (see [isoDiamondMask]); rectangular art
     * still works but will not occlude correctly where diamonds overlap.
     *
     * [heightOffsets] adds per-map-row elevation: index `y` of the array
     * shifts row `y` vertically (negative raises terrain). The canvas grows
     * to keep every shifted tile visible.
     *
     * @param map the map to render; layers composite bottom-up.
     * @param tileW tile bounding-box width, `>= 2`.
     * @param tileH tile bounding-box height, `>= 2`.
     * @param heightOffsets optional per-row y offset, size `map.height`, or
     *   `null` for flat terrain.
     * @throws IllegalArgumentException for bad tile dimensions, a
     *   [heightOffsets] size mismatch, or a canvas beyond the 31-bit /
     *   `IntArray` limits.
     */
    fun renderIso(map: TileMap, tileW: Int, tileH: Int, heightOffsets: IntArray? = null): PixelFrame {
        require(tileW >= MIN_DIM && tileH >= MIN_DIM) {
            "tile dimensions must be >= ${MIN_DIM} (tileW=$tileW, tileH=$tileH)"
        }
        if (heightOffsets != null) {
            require(heightOffsets.size == map.height) {
                "heightOffsets size ${heightOffsets.size} does not match map height ${map.height}"
            }
        }
        val halfW = tileW / 2
        val halfH = tileH / 2
        val w = map.width
        val h = map.height

        // Exact pixel bounds over all cell boxes (Long-safe).
        val minX = -(h - 1).toLong() * halfW
        val maxX = (w - 1).toLong() * halfW + tileW
        var minY = Long.MAX_VALUE
        var maxY = Long.MIN_VALUE
        for (y in 0 until h) {
            val top = y.toLong() * halfH + (heightOffsets?.get(y) ?: 0)
            val bottom = (w - 1 + y).toLong() * halfH + (heightOffsets?.get(y) ?: 0) + tileH
            if (top < minY) minY = top
            if (bottom > maxY) maxY = bottom
        }
        val canvasW = maxX - minX
        val canvasH = maxY - minY
        require(canvasW in 1..Int.MAX_VALUE.toLong() && canvasH in 1..Int.MAX_VALUE.toLong()) {
            "isometric canvas ${canvasW}x${canvasH} exceeds the 31-bit dimension limit"
        }
        val pixelCount = canvasW * canvasH
        require(pixelCount <= Int.MAX_VALUE.toLong()) {
            "isometric canvas needs $pixelCount pixels, beyond the IntArray limit"
        }

        val widthPx = canvasW.toInt()
        val out = IntArray(pixelCount.toInt())
        val originX = -minX
        val originY = -minY
        val size = map.tileset.tileSize
        val tileset = map.tileset

        // Painter order: ascending (x + y), ties by ascending x.
        for (sum in 0 until w + h - 1) {
            val xStart = maxOf(0, sum - (h - 1))
            val xEnd = minOf(w - 1, sum)
            for (x in xStart..xEnd) {
                val y = sum - x
                val boxX = ((x - y).toLong() * halfW + originX).toInt()
                val boxY = ((x + y).toLong() * halfH + (heightOffsets?.get(y) ?: 0) + originY).toInt()
                for (layer in map.layers) {
                    val cell = layer.cellAt(x, y)
                    if (cell.isEmpty) continue
                    if (cell.tileIndex >= tileset.tileCount) continue
                    val tile = tileset[cell.tileIndex]
                    drawTile(out, widthPx, tile, cell.flipBits, boxX, boxY, size)
                }
            }
        }
        return PixelFrame.of(widthPx, canvasH.toInt(), out)
    }

    /**
     * Composites one (possibly flipped) tile at (`boxX`, `boxY`), clipped to
     * the canvas, source-over — the same blend formula as [TileMap.render].
     */
    private fun drawTile(
        out: IntArray,
        outWidth: Int,
        tile: PixelFrame,
        flipBits: Int,
        boxX: Int,
        boxY: Int,
        size: Int,
    ) {
        val src = tile.pixels
        for (y in 0 until size) {
            val py = boxY + y
            if (py < 0) continue
            if (py * outWidth >= out.size) break
            val sy = if (flipBits and 2 != 0) size - 1 - y else y
            for (x in 0 until size) {
                val px = boxX + x
                if (px < 0 || px >= outWidth) continue
                val sx = if (flipBits and 1 != 0) size - 1 - x else x
                val s = src[sy * size + sx]
                if (s == 0) continue
                val at = py * outWidth + px
                out[at] = blendOver(s, out[at])
            }
        }
    }

    /** Source-over composite of ARGB [src] over ARGB [dst]. */
    private fun blendOver(src: Int, dst: Int): Int {
        val sa = src ushr 24
        if (sa == 255) return src
        if (sa == 0) return dst
        val da = dst ushr 24
        if (da == 0) return src
        val inv = 255 - sa
        val outA = sa + da * inv / 255
        if (outA == 0) return 0
        fun channel(s: Int, d: Int): Int = (s * sa + d * da * inv / 255) / outA
        val r = channel(src shr 16 and 0xFF, dst shr 16 and 0xFF)
        val g = channel(src shr 8 and 0xFF, dst shr 8 and 0xFF)
        val b = channel(src and 0xFF, dst and 0xFF)
        return (outA shl 24) or (r shl 16) or (g shl 8) or b
    }
}
