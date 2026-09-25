package com.pixellab.core.tilemap

import com.pixellab.core.model.PixelFrame

/**
 * Procedurally painted demo tilesets — instant, dependency-free terrain art.
 *
 * Every set is generated from fixed rules (no RNG at all — speckles sit at
 * hard-coded positions), so results are byte-identical on every platform and
 * every call. They exist so smoke tests, MCP hosts and sample apps can build
 * a visible, autotile-correct map in three lines:
 *
 * ```
 * val set = DemoTilesets.grassBlob47()
 * val layer = Autotiler(set).autotile(inputLayer, terrainTag = 1)
 * val map = TileMap.of("demo", set, 8, 8, listOf(layer)).render()
 * ```
 *
 * ## Painting rule (blob and simple sets)
 *
 * Tile `i` is painted from the **inverse autotile lookup** —
 * `Autotiler.maskFor(i, mode)` recovers the exact neighbor mask of slot `i`,
 * so demo art and the [Autotiler] tables can never drift apart. Given the
 * mask:
 *
 *  * the body is filled with the body color;
 *  * sparse darker speckle pixels land at the fixed positions `(2, 3)`,
 *    `(5, 5)` and `(6, 1)` (all interior — never covered by borders);
 *  * a 1px **border line** is drawn on every side whose edge bit is clear
 *    (that side faces a *missing* neighbor → the terrain outline);
 *  * the corner pixel of every corner whose bit is clear is also painted
 *    border color — for simple-16 (which has no corner bits) the rule is the
 *    normalized equivalent: a corner is border iff at least one of its two
 *    adjacent edges is open.
 *
 * Borders are painted last, so they win over speckles at edges.
 *
 * ## Palette contract
 *
 * Each factory takes an optional `palette` (ARGB integers):
 *
 *  * grass sets: `[body, border, speckle]` — defaults are PICO-8-ish greens
 *    (`0x00E436` body, `0x008751` border, `0x005C57` speckle);
 *  * stone set: `[stone, shade]` — defaults `0xC2C3C7` / `0x5F574F`.
 */
object DemoTilesets {

    /** Grass palette default: body, border, speckle. */
    private val GRASS_DEFAULT: IntArray =
        intArrayOf(0xFF00E436.toInt(), 0xFF008751.toInt(), 0xFF005C57.toInt())

    /** Stone palette default: stone, shade. */
    private val STONE_DEFAULT: IntArray =
        intArrayOf(0xFFC2C3C7.toInt(), 0xFF5F574F.toInt())

    /** Fixed speckle positions inside an 8x8 tile (interior only). */
    private val SPECKLES: IntArray = intArrayOf(2, 3, 5, 5, 6, 1)

    /**
     * The 47-tile grass blob set, 8x8 pixels per tile, every tile tagged
     * terrain `1`.
     *
     * Slot `i` is painted for the normalized mask
     * `Autotiler.maskFor(i, FULL_47)`: body fill + speckles + 1px borders on
     * the sides/corners facing missing neighbors (see the class docs). The
     * interior tile (slot 46, mask `0xFF`) is pure body with speckles; the
     * isolated tile (slot 0, mask 0) is a full border ring.
     *
     * @param palette `[body, border, speckle]` ARGB colors.
     * @throws IllegalArgumentException if [palette] has fewer than 3 entries.
     */
    fun grassBlob47(palette: IntArray = GRASS_DEFAULT): Tileset {
        requirePalette(palette, 3, "grass")
        val autotiler = Autotiler(placeholderTileset(47))
        val tiles = ArrayList<PixelFrame>(47)
        for (i in 0 until 47) {
            val mask = autotiler.maskFor(i, BlobMode.FULL_47)
            tiles.add(paintBlobTile(mask, palette))
        }
        return Tileset.of(tiles, IntArray(47) { 1 })
    }

    /**
     * The 16-tile grass set for [BlobMode.SIMPLE_16], 8x8 pixels per tile,
     * every tile tagged terrain `1`. Painted from the 4-bit edge mask
     * `Autotiler.maskFor(i, SIMPLE_16) == i` with the normalized corner rule.
     *
     * @param palette `[body, border, speckle]` ARGB colors.
     * @throws IllegalArgumentException if [palette] has fewer than 3 entries.
     */
    fun grassSimple16(palette: IntArray = GRASS_DEFAULT): Tileset {
        requirePalette(palette, 3, "grass")
        val tiles = ArrayList<PixelFrame>(16)
        for (i in 0 until 16) {
            tiles.add(paintEdgeTile(i, palette))
        }
        return Tileset.of(tiles, IntArray(16) { 1 })
    }

    /**
     * The 16-tile 2-corner Wang stone set, 4x4 pixels per tile, every tile
     * tagged terrain `2`.
     *
     * Each tile is split into four 2x2 quadrants. The quadrant of a corner is
     * filled with stone (plus one shade pixel at its tile-center-adjacent
     * corner, giving a subtle cross seam) when that corner bit of the Wang
     * mask is set (`NW=1, NE=2, SE=4, SW=8`), and left fully transparent
     * otherwise — so tiles blend solid stone against whatever shows through
     * from lower layers.
     *
     * @param palette `[stone, shade]` ARGB colors.
     * @throws IllegalArgumentException if [palette] has fewer than 2 entries.
     */
    fun stoneWang16(palette: IntArray = STONE_DEFAULT): Tileset {
        requirePalette(palette, 2, "stone")
        val stone = palette[0]
        val shade = palette[1]
        val tiles = ArrayList<PixelFrame>(16)
        for (mask in 0 until 16) {
            val out = IntArray(4 * 4)
            // Quadrant origins: NW (0,0), NE (2,0), SE (2,2), SW (0,2).
            if (mask and 1 != 0) fillQuadrant(out, 0, 0, stone, shade)
            if (mask and 2 != 0) fillQuadrant(out, 2, 0, stone, shade)
            if (mask and 4 != 0) fillQuadrant(out, 2, 2, stone, shade)
            if (mask and 8 != 0) fillQuadrant(out, 0, 2, stone, shade)
            tiles.add(PixelFrame.of(4, 4, out))
        }
        return Tileset.of(tiles, IntArray(16) { 2 })
    }

    // ------------------------------------------------------------------
    // Painting internals
    // ------------------------------------------------------------------

    /** Validates that [palette] carries at least [needed] colors. */
    private fun requirePalette(palette: IntArray, needed: Int, what: String) {
        require(palette.size >= needed) {
            "$what palette needs at least $needed colors (got ${palette.size})"
        }
    }

    /**
     * A throwaway 47-tile tileset that exists only so [Autotiler.maskFor]
     * can be consulted while painting — its tiles are never read.
     */
    private fun placeholderTileset(count: Int): Tileset {
        val blank = PixelFrame.blank(8, 8)
        val tiles = (0 until count).map { blank }
        return Tileset.of(tiles)
    }

    /**
     * Paints one 8x8 blob tile for an 8-bit normalized [mask]
     * (N=1, NE=2, E=4, SE=8, S=16, SW=32, W=64, NW=128; bit set = neighbor
     * solid = no border on that side).
     */
    private fun paintBlobTile(mask: Int, palette: IntArray): PixelFrame {
        val body = palette[0]
        val border = palette[1]
        val speckle = palette[2]
        val out = IntArray(8 * 8)
        out.fill(body)
        for (i in SPECKLES.indices step 2) {
            out[SPECKLES[i + 1] * 8 + SPECKLES[i]] = speckle
        }
        paintBorders(
            out, 8,
            north = mask and 1 == 0,
            east = mask and 4 == 0,
            south = mask and 16 == 0,
            west = mask and 64 == 0,
            nw = mask and 128 == 0,
            ne = mask and 2 == 0,
            se = mask and 8 == 0,
            sw = mask and 32 == 0,
            border = border,
        )
        return PixelFrame.of(8, 8, out)
    }

    /**
     * Paints one 8x8 simple-16 tile for the 4-bit edge [mask]
     * (N=1, E=2, S=4, W=8; bit set = neighbor solid). Corners follow the
     * normalized rule: corner border iff at least one adjacent edge is open.
     */
    private fun paintEdgeTile(mask: Int, palette: IntArray): PixelFrame {
        val body = palette[0]
        val border = palette[1]
        val speckle = palette[2]
        val out = IntArray(8 * 8)
        out.fill(body)
        for (i in SPECKLES.indices step 2) {
            out[SPECKLES[i + 1] * 8 + SPECKLES[i]] = speckle
        }
        val n = mask and 1 == 0
        val e = mask and 2 == 0
        val s = mask and 4 == 0
        val w = mask and 8 == 0
        paintBorders(
            out, 8,
            north = n, east = e, south = s, west = w,
            nw = n || w, ne = n || e, se = s || e, sw = s || w,
            border = border,
        )
        return PixelFrame.of(8, 8, out)
    }

    /**
     * Draws 1px border lines and corner pixels into [out] (size [size]) for
     * the given open sides/corners.
     */
    private fun paintBorders(
        out: IntArray,
        size: Int,
        north: Boolean,
        east: Boolean,
        south: Boolean,
        west: Boolean,
        nw: Boolean,
        ne: Boolean,
        se: Boolean,
        sw: Boolean,
        border: Int,
    ) {
        val last = size - 1
        if (north) for (x in 0 until size) out[x] = border
        if (south) for (x in 0 until size) out[last * size + x] = border
        if (west) for (y in 0 until size) out[y * size] = border
        if (east) for (y in 0 until size) out[y * size + last] = border
        if (nw) out[0] = border
        if (ne) out[last] = border
        if (se) out[last * size + last] = border
        if (sw) out[last * size] = border
    }

    /**
     * Fills one 2x2 quadrant of a 4x4 Wang tile at (`qx`, `qy`) with [stone]
     * and puts [shade] on the pixel nearest the tile center, so four filled
     * quadrants show a small shaded cross.
     */
    private fun fillQuadrant(out: IntArray, qx: Int, qy: Int, stone: Int, shade: Int) {
        val centerX = if (qx == 0) 1 else 2
        val centerY = if (qy == 0) 1 else 2
        for (y in 0 until 2) {
            for (x in 0 until 2) {
                val px = qx + x
                val py = qy + y
                out[py * 4 + px] = if (px == centerX && py == centerY) shade else stone
            }
        }
    }
}
