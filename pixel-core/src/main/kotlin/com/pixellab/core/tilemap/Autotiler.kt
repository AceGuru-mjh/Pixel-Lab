package com.pixellab.core.tilemap

/**
 * Which blob tileset shape [Autotiler] targets.
 */
enum class BlobMode {
    /**
     * The full 47-tile blob set: 8-bit neighbor masks normalized to the 47
     * visually distinct configurations (see [Autotiler] for the layout).
     * Interior tiles, straight runs, T-junctions, corners and rounded
     * peninsulas are all distinguished.
     */
    FULL_47,

    /**
     * The simplified 16-tile set: only the four edge neighbors matter
     * (N, E, S, W). Corner diagonals are ignored, so inner corners are not
     * distinguished — cheaper art budget, chunkier look. Tile index is the
     * 4-bit edge mask itself: `N=1 | E=2 | S=4 | W=8`.
     */
    SIMPLE_16,
}

/**
 * Blob (a.k.a. "wang blob") autotiler — turns a solid/empty terrain mask into
 * tile indices.
 *
 * ## How it works
 *
 * 1. A cell of the input [TileLayer] is **solid** when it references a tile
 *    whose `terrainTags` entry equals the requested terrain tag. Every other
 *    cell (including out-of-tileset and empty cells) is background.
 * 2. For each solid cell an 8-bit neighbor bitmask is computed — bit set
 *    when the *neighbor* cell is solid:
 *
 *    ```
 *    N=1  NE=2  E=4  SE=8  S=16  SW=32  W=64  NW=128
 *    ```
 *
 * 3. The mask is **normalized**: a corner bit only matters when both of its
 *    adjacent edge bits are set. Concretely:
 *    * `NW` counts only if `N AND W` are set;
 *    * `NE` counts only if `N AND E` are set;
 *    * `SE` counts only if `S AND E` are set;
 *    * `SW` counts only if `S AND W` are set.
 *
 *    Otherwise the corner is cleared — a diagonal neighbor cannot be visible
 *    through a missing side neighbor, so masks 0x81 (`N|NW`) and 0x01 (`N`)
 *    are the same tile. Normalization collapses 256 raw masks onto the 47
 *    distinct configurations.
 * 4. The normalized mask maps to a tile index `0..46` through the canonical
 *    blob-47 order below. The output layer replaces every solid cell with
 *    that index and every background cell with `-1` (empty).
 *
 * ## Blob-47 canonical order
 *
 * 47 tiles in a row-major 8x6 grid (slot 47 of the 48 is unused). The
 * authoritative mapping is the mask table; the 3x3 miniatures show what the
 * art looks like (`#` = border/edge toward *missing* neighbors, `.` = tile
 * body, corner glyphs = closed corners):
 *
 * ```
 *  idx:   0    1    2    3    4    5    6    7
 *        ###  #.#  ###  ###  ###  #.#  #..  #.#
 *        ###  #.#  #..  #.#  ..#  #..  #..  #.#
 *        ###  ###  ###  #.#  ###  ###  ###  #.#
 *
 *  idx:   8    9   10   11   12   13   14   15
 *        #.#  ..#  ###  ###  ###  ###  ###  #.#
 *        ..#  ..#  #..  #..  ...  ..#  ..#  #..
 *        ###  ###  #.#  #..  ###  #.#  ..#  #.#
 *
 *  idx:  16   17   18   19   20   21   22   23
 *        #..  #.#  #..  #.#  #..  ..#  ...  #.#
 *        #..  #..  #..  ...  ...  ...  ...  ..#
 *        #.#  #..  #..  ###  ###  ###  ###  #.#
 *
 *  idx:  24   25   26   27   28   29   30   31
 *        ..#  #.#  ..#  ###  ###  ###  ###  #.#
 *        ..#  ..#  ..#  ...  ...  ...  ...  ...
 *        #.#  ..#  ..#  #.#  #..  ..#  ...  #.#
 *
 *  idx:  32   33   34   35   36   37   38   39
 *        #..  #.#  #..  #.#  #..  #.#  #..  ..#
 *        ...  ...  ...  ...  ...  ...  ...  ...
 *        #.#  #..  #..  ..#  ..#  ...  ...  #.#
 *
 *  idx:  40   41   42   43   44   45   46   (47)
 *        ...  ..#  ...  ..#  ...  ..#  ...   --
 *        ...  ...  ...  ...  ...  ...  ...   --
 *        #.#  #..  #..  ..#  ..#  ...  ...   --
 * ```
 *
 * Index-to-open-neighbor table (edges and corners that ARE solid neighbors;
 * `+x` extends the previous row's all-edges mask with corners):
 *
 * ```
 *  0: -                16: NES+NE          32: NESW+NE
 *  1: N                17: NES+SE          33: NESW+SE
 *  2: E                18: NES+NE+SE       34: NESW+NE+SE
 *  3: S                19: NEW             35: NESW+SW
 *  4: W                20: NEW+NE          36: NESW+NE+SW
 *  5: NE(edges)        21: NEW+NW          37: NESW+SE+SW
 *  6: NE+NEdiag        22: NEW+NE+NW       38: NESW+NE+SE+SW
 *  7: NS               23: NSW             39: NESW+NW
 *  8: NW(edges)        24: NSW+NW          40: NESW+NW+NE
 *  9: NW+NWdiag        25: NSW+SW          41: NESW+NW+SE
 * 10: ES               26: NSW+NW+SW       42: NESW+NW+NE+SE
 * 11: ES+SEdiag        27: ESW             43: NESW+NW+SW
 * 12: EW               28: ESW+SE          44: NESW+NW+NE+SW
 * 13: SW(edges)        29: ESW+SW          45: NESW+NW+SE+SW
 * 14: SW+SWdiag        30: ESW+SE+SW       46: NESW+NE+NW+SE+SW (all 8)
 * 15: NES              31: NESW(edges only)
 * ```
 *
 * At 3x3 resolution a few corner-only differences collapse to the same
 * miniature — the mask table above is the authoritative mapping.
 *
 * ## Simple-16 layout
 *
 * `index = (N?1:0) | (E?2:0) | (S?4:0) | (W?8:0)`, drawn as a 4x4 grid:
 *
 * ```
 *  idx:   0    1    2    3
 *        ###  #.#  ###  #.#
 *        ###  #.#  #..  #..
 *        ###  ###  ###  ###
 *
 *  idx:   4    5    6    7
 *        ###  #.#  ###  #.#
 *        #.#  #.#  #..  #..
 *        #.#  #.#  #.#  #.#
 *
 *  idx:   8    9   10   11
 *        ###  #.#  ###  #.#
 *        ..#  ..#  ...  ...
 *        ###  ###  ###  ###
 *
 *  idx:  12   13   14   15
 *        ###  #.#  ###  #.#
 *        ..#  ..#  ...  ...
 *        ..#  ..#  ..#  ...
 * ```
 *
 * (Column headers: bit order per index is N,E,S,W; row 3 = W bit set.)
 */
class Autotiler(private val tiles: Tileset) {

    /**
     * Re-autotiles a layer's terrain region.
     *
     * @param layer the input layer; its cells classify as solid/background
     *   via the tileset terrain tags (see the class docs). Not mutated.
     * @param terrainTag the terrain to autotile, `>= 1` (0 is reserved for
     *   "no terrain").
     * @param mode [BlobMode.FULL_47] (needs `tileCount >= 47`) or
     *   [BlobMode.SIMPLE_16] (needs `tileCount >= 16`).
     * @return a new layer with the same name/dimensions where every solid
     *   cell carries the blob tile index and every other cell is empty
     *   (`-1`, flip [TileFlip.NONE]).
     * @throws IllegalArgumentException if [terrainTag] is below 1 or the
     *   tileset is too small for [mode].
     */
    fun autotile(layer: TileLayer, terrainTag: Int, mode: BlobMode = BlobMode.FULL_47): TileLayer {
        require(terrainTag >= 1) { "terrainTag must be >= 1 (was $terrainTag); 0 means 'no terrain'" }
        requireTileCount(mode)
        val isSolid = solidMask(layer, terrainTag)
        val w = layer.width
        val h = layer.height
        val out = IntArray(w * h) { -1 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!isSolid[y * w + x]) continue
                val mask = neighborMask(isSolid, w, h, x, y)
                out[y * w + x] = when (mode) {
                    BlobMode.FULL_47 -> blob47IndexFor(mask)
                    BlobMode.SIMPLE_16 -> edgeMaskOf(mask)
                }
            }
        }
        return TileLayer.of(layer.name, w, h, out)
    }

    /**
     * 2-corner Wang tiling: maps each solid cell to one of 16 tiles keyed by
     * the occupancy of its four *corners*.
     *
     * A corner of cell (`x`, `y`) is **filled** iff the cell itself, the
     * horizontal side neighbor and the vertical side neighbor of that corner
     * are all solid — the diagonal neighbor is deliberately ignored (the
     * standard 2-corner Wang rule; it keeps the rule local and the tile set
     * at exactly 16):
     *
     * ```
     * NW filled = self(x,y) && N(x,y-1) && W(x-1,y)
     * NE filled = self(x,y) && N(x,y-1) && E(x+1,y)
     * SE filled = self(x,y) && S(x,y+1) && E(x+1,y)
     * SW filled = self(x,y) && S(x,y+1) && W(x-1,y)
     *
     * corner bits: NW=1, NE=2, SE=4, SW=8  ->  tile index 0..15
     * ```
     *
     * Non-solid cells become empty (`-1`). Unlike [autotile], Wang tiles
     * blend *between* solid and background (each tile carries both), which
     * is why 16 tiles suffice for smooth coastlines.
     *
     * @param terrainTag terrain to treat as solid, `>= 1`.
     * @throws IllegalArgumentException if [terrainTag] is below 1 or the
     *   tileset has fewer than 16 tiles.
     */
    fun wangEdges(layer: TileLayer, terrainTag: Int): TileLayer {
        require(terrainTag >= 1) { "terrainTag must be >= 1 (was $terrainTag); 0 means 'no terrain'" }
        require(tiles.tileCount >= 16) {
            "wang tiling needs at least 16 tiles (tileset has ${tiles.tileCount})"
        }
        val isSolid = solidMask(layer, terrainTag)
        val w = layer.width
        val h = layer.height
        val out = IntArray(w * h) { -1 }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!isSolid[y * w + x]) continue
                var corners = 0
                if (isSolidAt(isSolid, w, h, x, y - 1) && isSolidAt(isSolid, w, h, x - 1, y)) corners = corners or 1 // NW
                if (isSolidAt(isSolid, w, h, x, y - 1) && isSolidAt(isSolid, w, h, x + 1, y)) corners = corners or 2 // NE
                if (isSolidAt(isSolid, w, h, x, y + 1) && isSolidAt(isSolid, w, h, x + 1, y)) corners = corners or 4 // SE
                if (isSolidAt(isSolid, w, h, x, y + 1) && isSolidAt(isSolid, w, h, x - 1, y)) corners = corners or 8 // SW
                out[y * w + x] = corners
            }
        }
        return TileLayer.of(layer.name, w, h, out)
    }

    /**
     * Pure lookup: the blob tile index of a neighbor [mask].
     *
     * @param mask for [BlobMode.FULL_47] the raw 8-bit blob mask (N=1 .. NW=128,
     *   `0..255` — it is normalized internally); for [BlobMode.SIMPLE_16] the
     *   4-bit edge mask (`0..15`, `N=1|E=2|S=4|W=8`), which is its own index.
     * @throws IllegalArgumentException if [mask] is out of range for [mode].
     */
    fun tileIndexFor(mask: Int, mode: BlobMode): Int = when (mode) {
        BlobMode.FULL_47 -> blob47IndexFor(mask)
        BlobMode.SIMPLE_16 -> {
            require(mask in 0..15) { "SIMPLE_16 mask must be in 0..15 (was $mask)" }
            mask
        }
    }

    /**
     * Inverse lookup for painters and tests: the normalized mask that tile
     * [index] of the canonical layout answers to.
     *
     * * [BlobMode.FULL_47]: `index` in `0..46`, result is the 8-bit
     *   normalized blob mask of that slot;
     * * [BlobMode.SIMPLE_16]: `index` in `0..15`, result is the 4-bit edge
     *   mask (identity).
     *
     * [DemoTilesets] uses this to paint each tile of a demo set with exactly
     * the borders its slot implies, so demo art and the lookup table can
     * never drift apart.
     *
     * @throws IllegalArgumentException if [index] is out of range for [mode].
     */
    fun maskFor(index: Int, mode: BlobMode): Int = when (mode) {
        BlobMode.FULL_47 -> {
            require(index in 0..46) { "FULL_47 tile index must be in 0..46 (was $index)" }
            BLOB47_ORDER[index]
        }
        BlobMode.SIMPLE_16 -> {
            require(index in 0..15) { "SIMPLE_16 tile index must be in 0..15 (was $index)" }
            index
        }
    }

    /**
     * Inverse lookup for the Wang layout: the 4-bit corner mask
     * (`NW=1|NE=2|SE=4|SW=8`) of Wang tile [index].
     *
     * @throws IllegalArgumentException if [index] is outside `0..15`.
     */
    fun wangMaskFor(index: Int): Int {
        require(index in 0..15) { "wang tile index must be in 0..15 (was $index)" }
        return index
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun requireTileCount(mode: BlobMode) {
        val needed = when (mode) {
            BlobMode.FULL_47 -> 47
            BlobMode.SIMPLE_16 -> 16
        }
        require(tiles.tileCount >= needed) {
            "$mode needs at least $needed tiles (tileset has ${tiles.tileCount})"
        }
    }

    /** Solid classification: cell references a tile tagged [terrainTag]. */
    private fun solidMask(layer: TileLayer, terrainTag: Int): BooleanArray {
        val w = layer.width
        val h = layer.height
        val out = BooleanArray(w * h)
        for (i in out.indices) {
            val tileIndex = layer.cells[i]
            out[i] = tileIndex in 0 until tiles.tileCount && tiles.terrainTags[tileIndex] == terrainTag
        }
        return out
    }

    /** 8-bit neighbor mask around (`x`, `y`) — bit set when neighbor solid. */
    private fun neighborMask(solid: BooleanArray, w: Int, h: Int, x: Int, y: Int): Int {
        var mask = 0
        if (isSolidAt(solid, w, h, x, y - 1)) mask = mask or BIT_N
        if (isSolidAt(solid, w, h, x + 1, y - 1)) mask = mask or BIT_NE
        if (isSolidAt(solid, w, h, x + 1, y)) mask = mask or BIT_E
        if (isSolidAt(solid, w, h, x + 1, y + 1)) mask = mask or BIT_SE
        if (isSolidAt(solid, w, h, x, y + 1)) mask = mask or BIT_S
        if (isSolidAt(solid, w, h, x - 1, y + 1)) mask = mask or BIT_SW
        if (isSolidAt(solid, w, h, x - 1, y)) mask = mask or BIT_W
        if (isSolidAt(solid, w, h, x - 1, y - 1)) mask = mask or BIT_NW
        return mask
    }

    private fun isSolidAt(solid: BooleanArray, w: Int, h: Int, x: Int, y: Int): Boolean =
        x >= 0 && y >= 0 && x < w && y < h && solid[y * w + x]

    /** Reduces an 8-bit blob mask to its 4 edge bits (N, E, S, W). */
    private fun edgeMaskOf(mask: Int): Int {
        var edges = 0
        if (mask and BIT_N != 0) edges = edges or 1
        if (mask and BIT_E != 0) edges = edges or 2
        if (mask and BIT_S != 0) edges = edges or 4
        if (mask and BIT_W != 0) edges = edges or 8
        return edges
    }

    /** Validated FULL_47 lookup (mask `0..255`). */
    private fun blob47IndexFor(mask: Int): Int {
        require(mask in 0..255) { "blob mask must be in 0..255 (was $mask)" }
        return BLOB47_TABLE[mask]
    }

    companion object {
        private const val BIT_N = 1
        private const val BIT_NE = 2
        private const val BIT_E = 4
        private const val BIT_SE = 8
        private const val BIT_S = 16
        private const val BIT_SW = 32
        private const val BIT_W = 64
        private const val BIT_NW = 128

        /**
         * The 47 normalized masks in canonical slot order (see the class
         * KDoc table; row-major 8x6 grid, slot 47 unused).
         */
        private val BLOB47_ORDER: IntArray = intArrayOf(
            0, 1, 4, 16, 64, 5, 7, 17,                       // row 0: idx 0..7
            65, 193, 20, 28, 68, 80, 112, 21,                // row 1: idx 8..15
            23, 29, 31, 69, 71, 197, 199, 81,                // row 2: idx 16..23
            209, 113, 241, 84, 92, 116, 124, 85,             // row 3: idx 24..31
            87, 93, 95, 117, 119, 125, 127, 213,             // row 4: idx 32..39
            215, 221, 223, 245, 247, 253, 255,               // row 5: idx 40..46
        )

        /**
         * Corner-clearing normalization: each corner bit survives only when
         * both adjacent edge bits are set.
         */
        private fun normalizeBlobMask(mask: Int): Int {
            var m = mask
            if (mask and BIT_N == 0 || mask and BIT_W == 0) m = m and BIT_NW.inv()
            if (mask and BIT_N == 0 || mask and BIT_E == 0) m = m and BIT_NE.inv()
            if (mask and BIT_S == 0 || mask and BIT_E == 0) m = m and BIT_SE.inv()
            if (mask and BIT_S == 0 || mask and BIT_W == 0) m = m and BIT_SW.inv()
            return m
        }

        /**
         * The full 256-entry lookup table, derived systematically from
         * [BLOB47_ORDER]: entry `m` is the canonical index of
         * `normalizeBlobMask(m)`. The init guard proves the 47 slots are
         * distinct, normalized, and cover every possible normalized mask.
         */
        private val BLOB47_TABLE: IntArray = run {
            val reverse = IntArray(256) { -1 }
            for (i in BLOB47_ORDER.indices) {
                require(reverse[BLOB47_ORDER[i]] < 0) { "duplicate blob mask in BLOB47_ORDER at $i" }
                reverse[BLOB47_ORDER[i]] = i
            }
            for (m in 0..255) {
                val normalized = normalizeBlobMask(m)
                require(reverse[normalized] >= 0) {
                    "normalized mask $normalized (from $m) is not covered by the 47-tile order"
                }
            }
            IntArray(256) { m -> reverse[normalizeBlobMask(m)] }
        }
    }
}
