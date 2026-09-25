package com.pixellab.core.gen

import com.pixellab.core.model.PixelFrame

/**
 * Tile ids used by the generated maps. Small closed enum so hosts can
 * pattern-match cheaply; concrete colors are the caller's business.
 */
enum class MapTile {
    /** Solid wall / bedrock. */
    WALL,

    /** Walkable floor. */
    FLOOR,

    /** Walkable corridor. */
    CORRIDOR,

    /** Door between corridor and room. */
    DOOR,

    /** Water body (not walkable by default). */
    WATER,
}

/**
 * A generated tile map plus its metadata.
 */
class GeneratedMap(
    /** Row-major tiles, `width * height` entries. */
    val tiles: Array<MapTile>,
    val width: Int,
    val height: Int,
) {
    init {
        require(tiles.size == width * height) { "tiles size ${tiles.size} != ${width}x$height" }
    }

    /** Tile accessor with WALL border semantics for out-of-bounds reads. */
    operator fun get(x: Int, y: Int): MapTile {
        if (x < 0 || y < 0 || x >= width || y >= height) return MapTile.WALL
        return tiles[y * width + x]
    }

    /** Count of tiles of the given kind. */
    fun count(tile: MapTile): Int = tiles.count { it == tile }

    /**
     * Converts to a [PixelFrame] using the supplied ARGB colors
     * (defaults: warm stone floors, dark walls, blue water).
     */
    fun toFrame(colors: Map<MapTile, Int> = defaultColors()): PixelFrame {
        val px = IntArray(tiles.size)
        for (i in tiles.indices) px[i] = colors[tiles[i]] ?: 0
        return PixelFrame.of(width, height, px)
    }

    override fun toString(): String = "GeneratedMap(${width}x$height, floors=${count(MapTile.FLOOR)}, corridors=${count(MapTile.CORRIDOR)})"

    companion object {
        /** Default painterly palette (opaque ARGB). */
        fun defaultColors(): Map<MapTile, Int> = mapOf(
            MapTile.WALL to 0xFF2B2B40.toInt(),
            MapTile.FLOOR to 0xFFD9C6A5.toInt(),
            MapTile.CORRIDOR to 0xFFBFA587.toInt(),
            MapTile.DOOR to 0xFF8B5A2B.toInt(),
            MapTile.WATER to 0xFF3E6C9E.toInt(),
        )
    }
}

/**
 * BSP dungeon generator: recursively splits the map into rooms, places
 * one room per leaf with margins, and connects sibling rooms with
 * L-shaped corridors.
 *
 * Deterministic for a given seed (uses [SeededRng]); every room is
 * reachable because each split's two children get a connecting corridor,
 * recursively covering the whole tree.
 */
class DungeonGen(private val rng: SeededRng) {

    /**
     * Generates a dungeon.
     *
     * @param width map width in tiles (≥ 24).
     * @param height map height in tiles (≥ 24).
     * @param minLeaf the smallest region the splitter will divide
     *   (smaller → more, smaller rooms).
     * @param roomMargin wall thickness between a room and its region.
     */
    fun generate(width: Int, height: Int, minLeaf: Int = 12, roomMargin: Int = 1): GeneratedMap {
        require(width >= 24 && height >= 24) { "Map too small for BSP: ${width}x$height" }
        require(minLeaf >= 8) { "minLeaf must be ≥ 8" }
        require(roomMargin >= 1) { "roomMargin must be ≥ 1" }
        val tiles = Array(width * height) { MapTile.WALL }
        val leaves = ArrayList<Rect>(64)
        split(Rect(0, 0, width, height), minLeaf, leaves)
        // Carve one room per leaf.
        val rooms = ArrayList<Rect>(leaves.size)
        for (leaf in leaves) {
            val w = rng.nextInt(4, (leaf.w - 2 * roomMargin).coerceAtLeast(5))
            val h = rng.nextInt(4, (leaf.h - 2 * roomMargin).coerceAtLeast(5))
            val x = leaf.x + roomMargin + rng.nextInt(0, (leaf.w - 2 * roomMargin - w).coerceAtLeast(1))
            val y = leaf.y + roomMargin + rng.nextInt(0, (leaf.h - 2 * roomMargin - h).coerceAtLeast(1))
            // Hard clamp: rooms must never touch the canvas border row or
            // column, whatever the leaf geometry math produces — the border
            // stays WALL by contract (and tests assert it).
            val safeW = w.coerceAtMost(width - 1 - x)
            val safeH = h.coerceAtMost(height - 1 - y)
            if (safeW < 2 || safeH < 2) continue
            val room = Rect(x, y, safeW, safeH)
            rooms.add(room)
            carveRect(tiles, width, room, MapTile.FLOOR)
        }
        // Connect consecutive rooms (sorted for stable, mostly-ordered
        // corridors) with L corridors + door tiles at the junctions.
        val sorted = rooms.sortedWith(compareBy({ it.x }, { it.y }))
        for (i in 0 until sorted.size - 1) {
            connect(tiles, width, height, sorted[i], sorted[i + 1])
        }
        return GeneratedMap(tiles, width, height)
    }

    /** Recursive splitter: divides until regions fall below 2·minLeaf. */
    private fun split(region: Rect, minLeaf: Int, out: MutableList<Rect>) {
        val canSplitX = region.w >= 2 * minLeaf
        val canSplitY = region.h >= 2 * minLeaf
        if (!canSplitX && !canSplitY) {
            out.add(region)
            return
        }
        val horizontal = if (canSplitX && canSplitY) rng.chance(0.5f) else canSplitY
        if (horizontal) {
            val cut = rng.nextInt(minLeaf, region.h - minLeaf + 1)
            split(Rect(region.x, region.y, region.w, cut), minLeaf, out)
            split(Rect(region.x, region.y + cut, region.w, region.h - cut), minLeaf, out)
        } else {
            val cut = rng.nextInt(minLeaf, region.w - minLeaf + 1)
            split(Rect(region.x, region.y, cut, region.h), minLeaf, out)
            split(Rect(region.x + cut, region.y, region.w - cut, region.h), minLeaf, out)
        }
    }

    /** L-shaped corridor between room centers, doors at room boundaries. */
    private fun connect(tiles: Array<MapTile>, width: Int, height: Int, a: Rect, b: Rect) {
        val ax = a.x + a.w / 2
        val ay = a.y + a.h / 2
        val bx = b.x + b.w / 2
        val by = b.y + b.h / 2
        // Horizontal leg then vertical leg (order chosen deterministically
        // by the a→b direction, keeping corridors mostly axis-aligned).
        val x0 = minOf(ax, bx)
        val x1 = maxOf(ax, bx)
        val y0 = minOf(ay, by)
        val y1 = maxOf(ay, by)
        if (rng.chance(0.5f)) {
            hLine(tiles, width, height, x0, x1, ay)
            vLine(tiles, width, height, y0, y1, bx)
        } else {
            vLine(tiles, width, height, y0, y1, ax)
            hLine(tiles, width, height, x0, x1, by)
        }
    }

    private fun hLine(tiles: Array<MapTile>, width: Int, height: Int, x0: Int, x1: Int, y: Int) {
        for (x in x0..x1) mark(tiles, width, height, x, y)
    }

    private fun vLine(tiles: Array<MapTile>, width: Int, height: Int, y0: Int, y1: Int, x: Int) {
        for (y in y0..y1) mark(tiles, width, height, x, y)
    }

    /** Writes CORRIDOR and stamps DOOR where the corridor exits a room. */
    private fun mark(tiles: Array<MapTile>, width: Int, height: Int, x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val idx = y * width + x
        val current = tiles[idx]
        if (current == MapTile.FLOOR) return
        // A WALL adjacent to a FLOOR on both sides of the track becomes
        // a door; everything else is corridor.
        val neighborsFloor =
            (x > 0 && tiles[idx - 1] == MapTile.FLOOR) ||
                (x < width - 1 && tiles[idx + 1] == MapTile.FLOOR) ||
                (y > 0 && tiles[idx - width] == MapTile.FLOOR) ||
                (y < height - 1 && tiles[idx + width] == MapTile.FLOOR)
        tiles[idx] = if (neighborsFloor) MapTile.DOOR else MapTile.CORRIDOR
    }

    private fun carveRect(tiles: Array<MapTile>, width: Int, r: Rect, tile: MapTile) {
        for (y in r.y until r.y + r.h) {
            for (x in r.x until r.x + r.w) {
                if (x in 0 until width) tiles[y * width + x] = tile
            }
        }
    }

    /** Simple integer rect used during splitting. */
    internal data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val x2: Int get() = x + w
        val y2: Int get() = y + h
    }

    companion object {
        /** Convenience: generate with a fresh [SeededRng]. */
        fun withSeed(seed: Long, width: Int = 64, height: Int = 48): GeneratedMap =
            DungeonGen(SeededRng(seed)).generate(width, height)
    }
}

/**
 * Cave generator via cellular automata smoothing plus an optional
 * drunkard's-walk carving pass — the classic "Gamey Cave" recipe.
 */
class CaveGen(private val rng: SeededRng) {

    /**
     * Generates a cave map.
     *
     * 1. Random noise fills `fillChance` of the interior as FLOOR.
     * 2. `smoothPasses` rounds of the 4-5 rule: a cell becomes WALL when
     *    ≥ 5 of its 8 neighbors are WALL, else FLOOR (border always WALL).
     * 3. Optionally a drunkard's walk carves `walkTiles` additional floor
     *    from the densest region, guaranteeing a long connected tunnel.
     */
    fun generate(
        width: Int,
        height: Int,
        fillChance: Float = 0.46f,
        smoothPasses: Int = 4,
        walkTiles: Int = 0,
    ): GeneratedMap {
        require(width >= 16 && height >= 16) { "Cave too small: ${width}x$height" }
        require(fillChance in 0.0f..1.0f) { "fillChance out of range" }
        require(smoothPasses in 0..16) { "smoothPasses out of range" }
        var tiles = Array(width * height) { i ->
            val border = i < width || i >= width * (height - 1) || i % width == 0 || i % width == width - 1
            if (border || !rng.chance(fillChance)) MapTile.WALL else MapTile.FLOOR
        }
        repeat(smoothPasses) {
            tiles = smooth(tiles, width, height)
        }
        if (walkTiles > 0) drunkardWalk(tiles, width, height, walkTiles)
        return GeneratedMap(tiles, width, height)
    }

    /** One 4-5 rule pass (border stays WALL). */
    private fun smooth(tiles: Array<MapTile>, width: Int, height: Int): Array<MapTile> {
        val out = tiles.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var walls = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (tiles[(y + dy) * width + x + dx] == MapTile.WALL) walls++
                    }
                }
                out[y * width + x] = if (walls >= 5) MapTile.WALL else MapTile.FLOOR
            }
        }
        return out
    }

    /** Carves a random walk from the center-most floor cell. */
    private fun drunkardWalk(tiles: Array<MapTile>, width: Int, height: Int, budget: Int) {
        var x = width / 2
        var y = height / 2
        // Nudge toward the nearest existing floor before carving.
        outer@ for (r in 0 until 8) {
            for (dy in -r..r) for (dx in -r..r) {
                if (x + dx in 1 until width - 1 && y + dy in 1 until height - 1 &&
                    tiles[(y + dy) * width + x + dx] == MapTile.FLOOR
                ) {
                    x += dx; y += dy
                    break@outer
                }
            }
        }
        var carved = 0
        var dir = 0
        while (carved < budget) {
            if (x in 1 until width - 1 && y in 1 until height - 1) {
                val idx = y * width + x
                if (tiles[idx] == MapTile.WALL) carved++
                tiles[idx] = MapTile.CORRIDOR
            }
            if (rng.chance(0.25f)) dir = rng.nextInt(4)
            when (dir) {
                0 -> x++
                1 -> x--
                2 -> y++
                else -> y--
            }
            x = x.coerceIn(1, width - 2)
            y = y.coerceIn(1, height - 2)
        }
    }

    companion object {
        /** Convenience: generate with a fresh [SeededRng]. */
        fun withSeed(seed: Long, width: Int = 64, height: Int = 48): GeneratedMap =
            CaveGen(SeededRng(seed)).generate(width, height)
    }
}
