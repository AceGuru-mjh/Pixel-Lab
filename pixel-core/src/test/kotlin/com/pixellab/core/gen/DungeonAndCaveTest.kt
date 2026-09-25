package com.pixellab.core.gen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorleyNoiseTest {

    @Test
    fun `same seed same values`() {
        val a = WorleyNoise(42L)
        val b = WorleyNoise(42L)
        for (i in 0 until 20) {
            val x = i * 0.37
            val y = i * 0.61
            assertEquals(a.evaluate(x, y), b.evaluate(x, y), 1e-12)
        }
    }

    @Test
    fun `different seeds give different fields`() {
        val a = WorleyNoise(1L)
        val b = WorleyNoise(2L)
        var differs = 0
        for (i in 0 until 50) {
            if (kotlin.math.abs(a.evaluate(i * 0.5, i * 0.25) - b.evaluate(i * 0.5, i * 0.25)) > 1e-6) differs++
        }
        assertTrue("expected uncorrelated fields, $differs/50 differ", differs > 30)
    }

    @Test
    fun `f2 dominates f1`() {
        val n = WorleyNoise(7L)
        for (i in 0 until 30) {
            val f1 = n.evaluate(i * 0.83, i * 0.47, WorleyNoise.Feature.NEAREST)
            val f2 = n.evaluate(i * 0.83, i * 0.47, WorleyNoise.Feature.SECOND)
            assertTrue("f2 ($f2) >= f1 ($f1)", f2 >= f1 - 1e-9)
        }
    }

    @Test
    fun `border feature is non-negative`() {
        val n = WorleyNoise(9L)
        for (i in 0 until 40) {
            val b = n.evaluate(i * 0.71, i * 0.29, WorleyNoise.Feature.BORDER)
            assertTrue(b >= 0.0)
        }
    }

    @Test
    fun `cell feature bounded to unit interval`() {
        val n = WorleyNoise(11L)
        for (i in 0 until 40) {
            val c = n.evaluate(i * 0.13, i * 0.97, WorleyNoise.Feature.CELL)
            assertTrue(c in 0.0..1.0)
        }
    }

    @Test
    fun `jitter is deterministic and in unit range`() {
        val n = WorleyNoise(33L)
        val j1 = n.jitterX(3.0, 4.0)
        val j2 = n.jitterX(3.0, 4.0)
        assertEquals(j1, j2, 1e-15)
        assertTrue(j1 in 0.0..1.0)
        assertTrue(n.jitterY(3.0, 4.0) in 0.0..1.0)
    }

    @Test
    fun `render produces opaque grayscale with requested geometry`() {
        val n = WorleyNoise(5L)
        val frame = n.render(24, 16, cellSize = 6.0)
        assertEquals(24, frame.width)
        assertEquals(16, frame.height)
        for (p in frame.pixels) {
            val r = p ushr 16 and 0xFF
            val g = p ushr 8 and 0xFF
            val b = p and 0xFF
            assertEquals(255, p ushr 24)
            assertEquals(r, g)
            assertEquals(g, b)
        }
    }
}

class DungeonGenTest {

    @Test
    fun `deterministic generation`() {
        val a = DungeonGen.withSeed(1234L)
        val b = DungeonGen.withSeed(1234L)
        assertTrue(a.tiles.contentEquals(b.tiles))
    }

    @Test
    fun `map has floor corridors and doors`() {
        val map = DungeonGen.withSeed(77L, width = 64, height = 48)
        assertTrue("floors=${map.count(MapTile.FLOOR)}", map.count(MapTile.FLOOR) > 40)
        assertTrue("corridors=${map.count(MapTile.CORRIDOR)}", map.count(MapTile.CORRIDOR) > 10)
        assertTrue("doors=${map.count(MapTile.DOOR)}", map.count(MapTile.DOOR) > 0)
    }

    @Test
    fun `border is always wall`() {
        val map = DungeonGen.withSeed(3L, width = 32, height = 32)
        for (x in 0 until map.width) {
            assertEquals(MapTile.WALL, map[x, 0])
            assertEquals(MapTile.WALL, map[x, map.height - 1])
        }
        for (y in 0 until map.height) {
            assertEquals(MapTile.WALL, map[0, y])
            assertEquals(MapTile.WALL, map[map.width - 1, y])
        }
        // Out-of-bounds reads are WALL too.
        assertEquals(MapTile.WALL, map[-1, 5])
        assertEquals(MapTile.WALL, map[5, -1])
        assertEquals(MapTile.WALL, map[999, 999])
    }

    @Test
    fun `all rooms reachable from the first floor tile`() {
        val map = DungeonGen.withSeed(88L, width = 72, height = 56)
        // BFS over floor/corridor/door tiles; every floor tile must be hit.
        val walkable = { t: MapTile -> t == MapTile.FLOOR || t == MapTile.CORRIDOR || t == MapTile.DOOR }
        val startIdx = map.tiles.indexOfFirst { walkable(it) }
        assertTrue(startIdx >= 0)
        val seen = BooleanArray(map.tiles.size)
        val queue = ArrayDeque<Int>()
        queue.add(startIdx)
        seen[startIdx] = true
        var visited = 0
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            visited++
            val x = idx % map.width
            val y = idx / map.width
            val neighbors = intArrayOf(idx - 1, idx + 1, idx - map.width, idx + map.width)
            val offsets = intArrayOf(-1, 1, 0, 0)
            for (k in neighbors.indices) {
                val n = neighbors[k]
                val nx = if (k < 2) x + offsets[k] else x
                val ny = if (k < 2) y else y + offsets[k] - if (k == 3) 1 else 1
                if (nx in 0 until map.width && ny in 0 until map.height && !seen[n] && walkable(map.tiles[n])) {
                    seen[n] = true
                    queue.add(n)
                }
            }
        }
        val totalWalkable = map.tiles.count { walkable(it) }
        assertEquals("not all rooms reachable: $visited/$totalWalkable", totalWalkable, visited)
    }

    @Test
    fun `toFrame paints every tile`() {
        val map = DungeonGen.withSeed(9L, width = 32, height = 32)
        val frame = map.toFrame()
        assertEquals(map.width, frame.width)
        val colors = map.tiles.map { frame.pixels[it.let { _ -> 0 } + 0] } // placeholder; real check below
        // Real check: each tile color maps through defaultColors.
        val colorsFor = GeneratedMap.defaultColors()
        for (i in map.tiles.indices) {
            assertEquals(colorsFor[map.tiles[i]], frame.pixels[i])
        }
        assertTrue(colors.size >= 0)
    }
}

class CaveGenTest {

    @Test
    fun `deterministic generation`() {
        val a = CaveGen.withSeed(5L)
        val b = CaveGen.withSeed(5L)
        assertTrue(a.tiles.contentEquals(b.tiles))
    }

    @Test
    fun `caves have open floor after smoothing`() {
        val map = CaveGen.withSeed(21L, width = 64, height = 48)
        val floor = map.count(MapTile.FLOOR) + map.count(MapTile.CORRIDOR)
        val total = map.width * map.height
        // 4-5 smoothing converges to ~45-55% floor typically.
        assertTrue("floor ratio ${floor.toDouble() / total}", floor in (total * 25 / 100)..(total * 75 / 100))
    }

    @Test
    fun `border stays wall after smoothing`() {
        val map = CaveGen.withSeed(21L, width = 48, height = 32)
        for (x in 0 until map.width) {
            assertEquals(MapTile.WALL, map[x, 0])
            assertEquals(MapTile.WALL, map[x, map.height - 1])
        }
        for (y in 0 until map.height) {
            assertEquals(MapTile.WALL, map[0, y])
            assertEquals(MapTile.WALL, map[map.width - 1, y])
        }
    }

    @Test
    fun `drunkard walk carves extra floor`() {
        val gen = CaveGen(SeededRng(31L))
        val base = gen.generate(48, 32)
        val walked = gen.generate(48, 32, walkTiles = 200)
        val baseFloor = base.count(MapTile.FLOOR)
        val walkedFloor = walked.count(MapTile.FLOOR) + walked.count(MapTile.CORRIDOR)
        assertTrue("walked=$walkedFloor base=$baseFloor", walkedFloor > baseFloor)
    }
}
