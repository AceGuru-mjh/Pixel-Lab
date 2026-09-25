package com.pixellab.core.tilemap

import com.pixellab.core.model.PixelFrame

/**
 * Immutable cell coordinate on a tile-map grid. Coordinates are non-negative:
 * a [TileLayer] grid has no negative axes, mirroring [com.pixellab.core.model.PixelPoint].
 *
 * @throws IllegalArgumentException if `x` or `y` is negative.
 */
data class TileCoord(val x: Int, val y: Int) {
    init {
        require(x >= 0 && y >= 0) { "TileCoord coordinates must be non-negative (x=$x, y=$y)" }
    }
}

/**
 * Mirror transforms a single map cell can carry on top of its tile index.
 *
 * The transform is stored packed in two bits, so it survives the flat
 * [TileLayer.flips] encoding without any object allocation:
 *
 *  * [NONE]        — 0 — no flip (identity);
 *  * [HORIZONTAL]  — 1 — mirror left-right (bit 0 set);
 *  * [VERTICAL]    — 2 — mirror top-bottom (bit 1 set);
 *  * [BOTH]        — 3 — both mirrors, i.e. a 180 degree rotation.
 *
 * `HORIZONTAL | VERTICAL == BOTH` under [bits], which is why the packed
 * representation accepts every value in `0..3` and nothing else.
 */
enum class TileFlip(val bits: Int) {
    /** Identity — the tile is drawn exactly as stored in the tileset. */
    NONE(0),

    /** Mirror horizontally (left column becomes the right column). Bit 0. */
    HORIZONTAL(1),

    /** Mirror vertically (top row becomes the bottom row). Bit 1. */
    VERTICAL(2),

    /** Both mirrors applied, equivalent to a 180 degree rotation. Bits 0+1. */
    BOTH(3);

    companion object {
        /**
         * Decodes a packed two-bit flip value (as stored in
         * [TileLayer.flips]) back to the enum.
         *
         * @throws IllegalArgumentException if [bits] is outside `0..3`.
         */
        fun fromBits(bits: Int): TileFlip {
            require(bits in 0..3) { "flip bits must be in 0..3 (was $bits)" }
            return entries[bits]
        }
    }
}

/**
 * The content of one map cell: which tile of the [Tileset] to draw (or
 * "nothing") plus an optional [TileFlip] mirror transform.
 *
 * `tileIndex == -1` is the canonical empty cell — a hole through which lower
 * layers (or the map background) show. Any other value indexes the tileset
 * list. The flip applies *before* placement; combined with the layer stack it
 * covers the classic "one tileset, mirrored decorations" workflow.
 *
 * @throws IllegalArgumentException if [tileIndex] is below -1.
 */
data class MapCell(val tileIndex: Int, val flip: TileFlip = TileFlip.NONE) {
    init {
        require(tileIndex >= -1) { "tileIndex must be >= -1 (was $tileIndex)" }
    }

    /** `true` when this cell leaves the layer transparent at its position. */
    val isEmpty: Boolean get() = tileIndex < 0

    /** The packed two-bit flip encoding of [flip]. */
    val flipBits: Int get() = flip.bits

    companion object {
        /** The canonical empty cell (`tileIndex == -1`, no flip). */
        val EMPTY: MapCell = MapCell(-1)
    }
}

/**
 * One immutable, rectangular grid of tiles.
 *
 * Content lives in two parallel flat arrays (row-major, `y * width + x`):
 * [cells] stores one tile index per cell (`-1` = empty) and [flips] stores the
 * packed [TileFlip] bits of the same cell (`0` = [TileFlip.NONE]). Storing
 * indices instead of objects keeps large maps allocation-free; the object
 * view is produced on demand by [get]/[cellAt].
 *
 * Instances are deeply immutable — every editing operation returns a new
 * layer with copied arrays ([withCell]); [equals]/[hashCode] compare the
 * *content* of both arrays, so layers work as cache keys. Create layers via
 * [of]; mutating the exposed arrays afterwards is a contract violation
 * (same convention as [PixelFrame.pixels]).
 *
 * Out-of-bounds queries never throw: [get]/[cellAt] answer [MapCell.EMPTY]
 * and [withCell] skips the write, matching the skip-semantics of
 * [PixelFrame.withPixel].
 */
class TileLayer private constructor(
    /** Layer name; unique names are recommended but not enforced (see [TileMap.withoutLayer]). */
    val name: String,
    /** Grid width in cells, `>= 1`. */
    val width: Int,
    /** Grid height in cells, `>= 1`. */
    val height: Int,
    /** Flat row-major tile indices, size `width * height`; `-1` marks an empty cell. */
    val cells: IntArray,
    /** Flat row-major packed flip bits, size `width * height`, each in `0..3`. */
    val flips: IntArray,
) {

    companion object {
        /**
         * Builds a layer from flat row-major arrays.
         *
         * @param name layer name, non-blank (used by [TileMap.withoutLayer]).
         * @param width grid width in cells, `>= 1`.
         * @param height grid height in cells, `>= 1`.
         * @param cells tile index per cell, `>= -1`; `-1` = empty.
         * @param flips optional packed flip bits per cell in `0..3`; defaults
         *   to all-zero (no flips). Must have the same size as [cells] when
         *   given.
         * @throws IllegalArgumentException if any dimension, size, tile index
         *   or flip bit violates the constraints above.
         */
        fun of(name: String, width: Int, height: Int, cells: IntArray, flips: IntArray = IntArray(0)): TileLayer {
            require(name.isNotBlank()) { "layer name must not be blank" }
            require(width >= 1 && height >= 1) { "layer dimensions must be >= 1 (w=$width, h=$height)" }
            require(cells.size == width * height) {
                "cells size ${cells.size} does not match ${width}x${height}"
            }
            val f = if (flips.isEmpty()) IntArray(cells.size) else flips
            require(f.size == cells.size) {
                "flips size ${f.size} does not match cells size ${cells.size}"
            }
            for (i in cells.indices) {
                require(cells[i] >= -1) { "cells[$i] must be >= -1 (was ${cells[i]})" }
                require(f[i] in 0..3) { "flips[$i] must be in 0..3 (was ${f[i]})" }
            }
            return TileLayer(name, width, height, cells.copyOf(), f.copyOf())
        }

        /**
         * A fully empty layer (`width x height` cells, every cell `-1`).
         *
         * @throws IllegalArgumentException on non-positive dimensions.
         */
        fun empty(name: String, width: Int, height: Int): TileLayer =
            of(name, width, height, IntArray(width * height) { -1 })
    }

    /** Total cell count (`width * height`). */
    val cellCount: Int get() = cells.size

    /** Reads the cell at (`x`, `y`); out-of-bounds reads answer [MapCell.EMPTY]. */
    operator fun get(x: Int, y: Int): MapCell = cellAt(x, y)

    /** Same as [get] — reads the cell at (`x`, `y`) as a [MapCell]. */
    fun cellAt(x: Int, y: Int): MapCell {
        if (x < 0 || y < 0 || x >= width || y >= height) return MapCell.EMPTY
        val i = y * width + x
        return MapCell(cells[i], TileFlip.fromBits(flips[i]))
    }

    /**
     * Returns a copy with the cell at (`x`, `y`) replaced by [cell].
     * Out-of-bounds coordinates are skipped silently (the same instance is
     * returned), matching [PixelFrame.withPixel] semantics.
     */
    fun withCell(x: Int, y: Int, cell: MapCell): TileLayer {
        if (x < 0 || y < 0 || x >= width || y >= height) return this
        val i = y * width + x
        val nextCells = cells.copyOf()
        val nextFlips = flips.copyOf()
        nextCells[i] = cell.tileIndex
        nextFlips[i] = cell.flipBits
        return TileLayer(name, width, height, nextCells, nextFlips)
    }

    /**
     * Lazy sequence over every **non-empty** cell in row-major order, pairing
     * its coordinate with its [MapCell] view. Empty cells are filtered out,
     * which makes this the natural iteration for renderers and autotilers.
     */
    fun cells(): Sequence<Pair<TileCoord, MapCell>> = sequence {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (cells[i] >= 0) yield(Pair(TileCoord(x, y), MapCell(cells[i], TileFlip.fromBits(flips[i]))))
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TileLayer) return false
        return name == other.name && width == other.width && height == other.height &&
            cells.contentEquals(other.cells) && flips.contentEquals(other.flips)
    }

    override fun hashCode(): Int {
        var h = 31 * (31 * (31 * name.hashCode() + width) + height)
        h = 31 * h + cells.contentHashCode()
        return 31 * h + flips.contentHashCode()
    }

    override fun toString(): String = "TileLayer('$name' ${width}x${height})"
}

/**
 * A uniform square tileset: the palette of tiles a [TileMap] draws from.
 *
 * All tiles are normalized to one square [tileSize] — `tileSize x tileSize`
 * pixels — so a map renderer can compute every cell origin with a single
 * multiplication. Tiles that arrive at other sizes are rejected unless
 * [of] is called with `allowMixedSizes = true`, in which case every tile is
 * padded **top-left aligned** into a `maxSize x maxSize` transparent canvas:
 * the original pixels keep their (0, 0) anchor and the grown right/bottom
 * margin stays fully transparent. Padding (not scaling) preserves every
 * source pixel exactly.
 *
 * [terrainTags] carries one non-negative tag per tile (`0` = no terrain).
 * Tags are the join between art and procedural logic: [Autotiler] selects
 * the solid region of a layer by matching the tile tag, and the demo
 * tilesets ship pre-tagged tiles for instant use.
 */
class Tileset private constructor(
    /** The uniform tiles, all `tileSize x tileSize`; immutable list. */
    val tiles: List<PixelFrame>,
    /** Side length of every tile in pixels, `>= 1`. */
    val tileSize: Int,
    /** One terrain tag per tile, size == [tileCount]; `0` = no terrain. */
    val terrainTags: IntArray,
) {

    companion object {
        /**
         * Builds a tileset from frames.
         *
         * @param frames source tiles; must not be empty. Every frame must be
         *   square (`width == height`) — either all of the same size, or, with
         *   [allowMixedSizes] `true`, of any square sizes (each padded
         *   top-left aligned to the maximum size, see the class docs).
         * @param terrainTags one tag per tile, `>= 0`; `null` (default) or an
         *   empty array means "no terrain" for every tile.
         * @param allowMixedSizes when `true`, differing square sizes are
         *   accepted and padded to `max(width, height)`; when `false` (the
         *   default), any size mismatch is rejected.
         * @throws IllegalArgumentException if [frames] is empty, a frame is
         *   not square, sizes mismatch without [allowMixedSizes], or
         *   [terrainTags] has a foreign size or negative entry.
         */
        fun of(
            frames: List<PixelFrame>,
            terrainTags: IntArray? = null,
            allowMixedSizes: Boolean = false,
        ): Tileset {
            require(frames.isNotEmpty()) { "tileset needs at least one tile" }
            var maxSize = 0
            for (frame in frames) {
                require(frame.width == frame.height) {
                    "tiles must be square (got ${frame.width}x${frame.height})"
                }
                if (frame.width > maxSize) maxSize = frame.width
            }
            if (!allowMixedSizes) {
                for (frame in frames) {
                    require(frame.width == maxSize) {
                        "tile size mismatch: expected ${maxSize}x$maxSize, got ${frame.width}x${frame.height}" +
                            " (pass allowMixedSizes to pad instead)"
                    }
                }
            }
            val tags = when {
                terrainTags == null || terrainTags.isEmpty() -> IntArray(frames.size)
                else -> terrainTags
            }
            require(tags.size == frames.size) {
                "terrainTags size ${tags.size} does not match tile count ${frames.size}"
            }
            for (i in tags.indices) require(tags[i] >= 0) { "terrainTags[$i] must be >= 0 (was ${tags[i]})" }

            val tiles = if (allowMixedSizes && frames.any { it.width != maxSize }) {
                frames.map { padTopLeft(it, maxSize) }
            } else {
                frames
            }
            return Tileset(tiles, maxSize, tags.copyOf())
        }

        /** Pads [frame] to `size x size`, top-left aligned, transparent fill. */
        private fun padTopLeft(frame: PixelFrame, size: Int): PixelFrame {
            if (frame.width == size) return frame
            val out = IntArray(size * size)
            val src = frame.pixels
            for (y in 0 until frame.height) {
                System.arraycopy(src, y * frame.width, out, y * size, frame.width)
            }
            return PixelFrame.of(size, size, out)
        }
    }

    /** Number of tiles in this set. */
    val tileCount: Int get() = tiles.size

    /**
     * Reads tile [index]. Bounds are checked loudly — a bad tile index is a
     * programmer error (map data references a tile that does not exist).
     *
     * @throws IllegalArgumentException if [index] is outside `0 until [tileCount]`.
     */
    operator fun get(index: Int): PixelFrame {
        require(index in 0 until tiles.size) { "tile index $index out of bounds ($tileCount tiles)" }
        return tiles[index]
    }

    /** Terrain tag of tile [index]; `0` when the tile carries no terrain. */
    fun terrainTagAt(index: Int): Int {
        require(index in 0 until tiles.size) { "tile index $index out of bounds ($tileCount tiles)" }
        return terrainTags[index]
    }

    /**
     * Number of tiles that contain at least one non-transparent pixel — the
     * tiles actually worth rasterizing. All-blank padding tiles are excluded;
     * useful when deciding how much of a set to upload as an atlas.
     */
    val renderedTileCount: Int
        get() = tiles.count { tile -> tile.pixels.any { it != 0 } }

    /**
     * Returns a copy with [frame] appended as the last tile.
     *
     * The frame must already match this set's [tileSize] exactly — appending
     * is the "add one more drawn tile" path, not the mixed-size path (use
     * [of] with `allowMixedSizes` to build a padded set).
     *
     * @param frame square tile of size [tileSize].
     * @param terrainTag terrain tag for the new tile, `>= 0` (default `0`).
     * @throws IllegalArgumentException if [frame] has foreign dimensions or
     *   [terrainTag] is negative.
     */
    fun withTile(frame: PixelFrame, terrainTag: Int = 0): Tileset {
        require(frame.width == tileSize && frame.height == tileSize) {
            "appended tile must be ${tileSize}x$tileSize (got ${frame.width}x${frame.height})"
        }
        require(terrainTag >= 0) { "terrainTag must be >= 0 (was $terrainTag)" }
        val nextTiles = ArrayList<PixelFrame>(tiles.size + 1)
        nextTiles.addAll(tiles)
        nextTiles.add(frame)
        val nextTags = terrainTags.copyOf(tiles.size + 1)
        nextTags[tiles.size] = terrainTag
        return Tileset(nextTiles, tileSize, nextTags)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Tileset) return false
        return tileSize == other.tileSize && tiles == other.tiles &&
            terrainTags.contentEquals(other.terrainTags)
    }

    override fun hashCode(): Int {
        var h = 31 * tileSize + tiles.hashCode()
        return 31 * h + terrainTags.contentHashCode()
    }

    override fun toString(): String = "Tileset(${tileCount} tiles @${tileSize}px)"
}

/**
 * An immutable tile map: a stack of [TileLayer]s over one shared [Tileset].
 *
 * `layers[0]` is the **bottom** layer; each following layer composites on top
 * with source-over alpha blending (see [render]). All layers must share the
 * map's `width x height` cell grid — layers of foreign dimensions are
 * rejected by [of]/[withLayer]/[withLayerAt] so the render loop never needs
 * per-layer bounds special cases.
 *
 * Maps are built by fluent copies (`withLayer`, `withLayerAt`,
 * `withoutLayer`) — the classic builder pattern over immutable data, so any
 * map state can be kept and restored cheaply.
 */
class TileMap private constructor(
    /** Map name, non-blank; free-form identifier for hosts and debugging. */
    val name: String,
    /** The shared tile palette every layer indexes into. */
    val tileset: Tileset,
    /** Grid width in cells, `>= 1`. */
    val width: Int,
    /** Grid height in cells, `>= 1`. */
    val height: Int,
    /** Layers bottom-up (`layers[0]` renders first); all `width x height`. */
    val layers: List<TileLayer>,
) {

    companion object {
        /**
         * Builds a map from a layer stack.
         *
         * @param name map name, non-blank.
         * @param tileset the shared tileset.
         * @param width grid width in cells, `>= 1`.
         * @param height grid height in cells, `>= 1`.
         * @param layers bottom-up layer stack (may be empty — rendering then
         *   yields a transparent canvas of the map size). Every layer must
         *   match `width x height`.
         * @throws IllegalArgumentException if any constraint above fails.
         */
        fun of(
            name: String,
            tileset: Tileset,
            width: Int,
            height: Int,
            layers: List<TileLayer> = emptyList(),
        ): TileMap {
            require(name.isNotBlank()) { "map name must not be blank" }
            require(width >= 1 && height >= 1) { "map dimensions must be >= 1 (w=$width, h=$height)" }
            for ((i, layer) in layers.withIndex()) {
                require(layer.width == width && layer.height == height) {
                    "layers[$i] ('${layer.name}') is ${layer.width}x${layer.height}, " +
                        "expected ${width}x${height}"
                }
            }
            return TileMap(name, tileset, width, height, layers.toList())
        }
    }

    /** Total number of layers. */
    val layerCount: Int get() = layers.size

    /**
     * Returns a copy with [layer] appended on top (it becomes the new
     * `layers.last()`).
     *
     * @throws IllegalArgumentException if [layer] has foreign dimensions.
     */
    fun withLayer(layer: TileLayer): TileMap {
        require(layer.width == width && layer.height == height) {
            "layer '${layer.name}' is ${layer.width}x${layer.height}, expected ${width}x${height}"
        }
        return TileMap(name, tileset, width, height, layers + layer)
    }

    /**
     * Returns a copy with [layer] inserted at [index] in the stack
     * (`0` = new bottom, `layerCount` = new top, i.e. append).
     *
     * @throws IllegalArgumentException if [index] is outside `0..layerCount`
     *   or [layer] has foreign dimensions.
     */
    fun withLayerAt(index: Int, layer: TileLayer): TileMap {
        require(index in 0..layers.size) { "layer index $index out of bounds (0..${layers.size})" }
        require(layer.width == width && layer.height == height) {
            "layer '${layer.name}' is ${layer.width}x${layer.height}, expected ${width}x${height}"
        }
        val next = ArrayList<TileLayer>(layers.size + 1)
        next.addAll(layers.subList(0, index))
        next.add(layer)
        next.addAll(layers.subList(index, layers.size))
        return TileMap(name, tileset, width, height, next)
    }

    /**
     * Returns a copy with **every** layer named [name] removed (names are not
     * forced unique, so all matches go). When no layer matches, `this` is
     * returned unchanged.
     */
    fun withoutLayer(name: String): TileMap {
        if (layers.none { it.name == name }) return this
        return TileMap(this.name, tileset, width, height, layers.filter { it.name != name })
    }

    /**
     * Renders the map to a pixel frame of `width * tileSize x height * tileSize`.
     *
     * Layers composite bottom-up with standard source-over alpha blending:
     * ```
     * outA = sa + da * (255 - sa) / 255
     * outC = (sc * sa + dc * da * (255 - sa) / 255) / outA
     * ```
     * (the same formula used by `OutlineShading`/`BlendModes`). Each cell's
     * tile is drawn with its [TileFlip] transform applied; empty cells leave
     * the layer transparent at that spot. The fully transparent result pixel
     * is `0x00000000`.
     *
     * @throws IllegalArgumentException if the canvas would exceed the 31-bit
     *   dimension or `IntArray` size limits.
     */
    fun render(): PixelFrame = renderInto(width, height, 0, 0)

    /**
     * Renders a rectangular **cell region** of the map: cells
     * `[x0, x0 + w) x [y0, y0 + h)` land at pixel
     * `((cx - x0) * tileSize, (cy - y0) * tileSize)` of a
     * `w * tileSize x h * tileSize` frame. Cells outside the map grid are
     * simply not drawn (the region is clipped), so partial viewport renders
     * never throw. Negative [x0]/[y0] are legal — they just shift the visible
     * window.
     *
     * @throws IllegalArgumentException if `w` or `h` is below 1, or the canvas
     *   would exceed the 31-bit/`IntArray` limits.
     */
    fun renderRegion(x0: Int, y0: Int, w: Int, h: Int): PixelFrame {
        require(w >= 1 && h >= 1) { "region dimensions must be >= 1 (w=$w, h=$h)" }
        return renderInto(w, h, x0, y0)
    }

    /** Shared renderer: [outW]/[outH] canvas, map cell (x0+cx, y0+cy) at (cx, cy). */
    private fun renderInto(outW: Int, outH: Int, x0: Int, y0: Int): PixelFrame {
        val size = tileset.tileSize
        val canvasW = outW.toLong() * size
        val canvasH = outH.toLong() * size
        require(canvasW in 1..Int.MAX_VALUE.toLong() && canvasH in 1..Int.MAX_VALUE.toLong()) {
            "render canvas ${canvasW}x${canvasH} exceeds the 31-bit dimension limit"
        }
        val pixelCount = canvasW * canvasH
        require(pixelCount <= Int.MAX_VALUE.toLong()) {
            "render canvas needs $pixelCount pixels, beyond the IntArray limit"
        }
        val widthPx = canvasW.toInt()
        val out = IntArray(pixelCount.toInt())
        for (layer in layers) {
            for (y in 0 until outH) {
                val mapY = y0 + y
                if (mapY < 0 || mapY >= height) continue
                for (x in 0 until outW) {
                    val mapX = x0 + x
                    if (mapX < 0 || mapX >= width) continue
                    val i = mapY * width + mapX
                    val tileIndex = layer.cells[i]
                    if (tileIndex < 0 || tileIndex >= tileset.tileCount) continue
                    val tile = tileset[tileIndex]
                    val flipBits = layer.flips[i]
                    val originX = x * size
                    val originY = y * size
                    drawCell(out, widthPx, tile, flipBits, originX, originY, size)
                }
            }
        }
        return PixelFrame.of(widthPx, canvasH.toInt(), out)
    }

    /**
     * Composites one flipped tile into [out] at (`originX`, `originY`),
     * source-over. Pure integer math, no allocation.
     */
    private fun drawCell(
        out: IntArray,
        outWidth: Int,
        tile: PixelFrame,
        flipBits: Int,
        originX: Int,
        originY: Int,
        size: Int,
    ) {
        val src = tile.pixels
        for (y in 0 until size) {
            val sy = if (flipBits and 2 != 0) size - 1 - y else y
            val rowBase = (originY + y) * outWidth + originX
            for (x in 0 until size) {
                val sx = if (flipBits and 1 != 0) size - 1 - x else x
                val s = src[sy * size + sx]
                if (s == 0) continue
                out[rowBase + x] = blendOver(s, out[rowBase + x])
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

    override fun toString(): String = "TileMap('$name' ${width}x${height}, ${layers.size} layers, $tileset)"
}
