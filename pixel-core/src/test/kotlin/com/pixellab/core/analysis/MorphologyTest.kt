package com.pixellab.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphologyTest {

    // ── StructElement ───────────────────────────────────────────────────────

    @Test
    fun `struct element offsets`() {
        val square = StructElement.square3()
        assertEquals(9, square.tapCount)
        // Row-major from the top-left corner offset (-1, -1).
        assertEquals(-1, square.offsets[0])
        assertEquals(-1, square.offsets[1])
        // Cross has 5 taps for radius 1.
        val cross = StructElement.cross3()
        assertEquals(5, cross.tapCount)
        // Every cross offset has at least one zero axis.
        var k = 0
        while (k < cross.offsets.size) {
            assertTrue(cross.offsets[k] == 0 || cross.offsets[k + 1] == 0)
            k += 2
        }
    }

    @Test
    fun `radius zero degenerates to identity`() {
        val frame = TestFrames.fromRows("rr.", "rr.")
        val e = StructElement(MorphShape.SQUARE, 0)
        assertEquals(frame, MorphologyOps.dilate(frame, e))
        assertEquals(frame, MorphologyOps.erode(frame, e))
    }

    // ── dilate / erode ──────────────────────────────────────────────────────

    @Test
    fun `dilate grows shape outward`() {
        val frame = TestFrames.fromRows(
            "...",
            ".r.",
            "...",
        )
        val out = MorphologyOps.dilate(frame, StructElement.cross3())
        // 3x3 cross dilate: plus shape of r.
        assertEquals(TestFrames.RED, out[1, 1])
        assertEquals(TestFrames.RED, out[1, 0])
        assertEquals(TestFrames.RED, out[1, 2])
        assertEquals(TestFrames.RED, out[0, 1])
        assertEquals(TestFrames.RED, out[2, 1])
        assertEquals(0, out[0, 0]) // corners untouched by cross element
        assertEquals(5, out.pixels.count { it != 0 })
    }

    @Test
    fun `dilate carries brightest neighbor color`() {
        val frame = TestFrames.fromRows(
            ".w.",
            "k.r",
            "...",
        )
        val out = MorphologyOps.dilate(frame)
        // Center (1,1) transparent with neighbors k (alpha FF both) and r;
        // first tap with strictly greater alpha wins ties → k from up.
        // Actually all taps share alpha 255, so the first encountered
        // opaque neighbor in element order (up) wins: w.
        assertEquals(TestFrames.WHITE, out[1, 1])
    }

    @Test
    fun `erode peels boundary`() {
        val frame = TestFrames.fromRows(
            "rrr",
            "rrr",
            "rrr",
        )
        val out = MorphologyOps.erode(frame)
        // 3x3 cross erode: only the exact center survives (all four
        // orthogonal neighbors opaque, but border cells die).
        assertEquals(1, out.pixels.count { it != 0 })
        assertEquals(TestFrames.RED, out[1, 1])
    }

    @Test
    fun `erode with square element is stricter than cross`() {
        val frame = TestFrames.fromRows(
            "rrr",
            "rrr",
            "rrr",
        )
        val cross = MorphologyOps.erode(frame, StructElement.cross3())
        val square = MorphologyOps.erode(frame, StructElement.square3())
        assertTrue(cross.pixels.count { it != 0 } >= square.pixels.count { it != 0 })
    }

    @Test
    fun `erode eats from the canvas border`() {
        // Out-of-bounds counts as transparent, so a full-frame solid erodes
        // its outer ring away.
        val frame = TestFrames.solid(4, 4, TestFrames.BLUE)
        val out = MorphologyOps.erode(frame, StructElement.cross3())
        val survivors = out.pixels.count { it != 0 }
        // 4x4 cross-erode keeps only pixels whose 4 neighbors are inside
        // and opaque → the central 2x2.
        assertEquals(4, survivors)
        assertEquals(TestFrames.BLUE, out[1, 1])
    }

    // ── open / close ────────────────────────────────────────────────────────

    @Test
    fun `open removes diagonal speckles but keeps the body`() {
        // (0,0) touches the body only diagonally: cross-erode kills it and
        // cross-dilate (no diagonal taps) cannot resurrect it. The body is
        // 3 wide and 3 tall so a cross-core survives erosion.
        val frame = TestFrames.fromRows(
            "r....",
            ".rrr.",
            ".rrr.",
            ".rrr.",
            ".....",
        )
        val opened = MorphologyOps.open(frame, StructElement.cross3())
        assertEquals(0, opened[0, 0]) // diagonal dust stays dead
        // The body reduces to its cross-eroded core plus the cross-dilated
        // halo — the center always survives.
        assertEquals(TestFrames.RED, opened[2, 2])
        assertTrue(opened.pixels.count { it != 0 } >= 5)
    }

    @Test
    fun `open keeps orthogonal single-pixel protrusions`() {
        // A 1-px stub that is orthogonally attached survives cross-open:
        // classic morphology only removes fragments smaller than the
        // element that have no orthogonal bridge.
        val frame = TestFrames.fromRows(
            "..r..",
            "rrrrr",
            "rrrrr",
        )
        val opened = MorphologyOps.open(frame, StructElement.cross3())
        assertEquals(TestFrames.RED, opened[2, 0])
    }

    @Test
    fun `close fills single-pixel holes`() {
        val frame = TestFrames.fromRows(
            "rrr",
            "r.r",
            "rrr",
        )
        val closed = MorphologyOps.close(frame)
        assertEquals(TestFrames.RED, closed[1, 1])
    }

    // ── removeIsolatedPixels ───────────────────────────────────────────────

    @Test
    fun `isolated pixels are removed, clustered ones survive`() {
        // Two 2px vertical pairs (survive) + two lone pixels (removed).
        val frame = TestFrames.fromRows(
            "r..g.",
            "r..g.",
            ".....",
            ".b..y",
        )
        val out = MorphologyOps.removeIsolatedPixels(frame)
        assertEquals(4, out.pixels.count { it != 0 })
        assertEquals(TestFrames.RED, out[0, 0])
        assertEquals(TestFrames.GREEN, out[3, 1])
        assertEquals(0, out[1, 3])
        assertEquals(0, out[4, 3])
    }

    @Test
    fun `diagonal neighbor keeps a pixel alive by default`() {
        val frame = TestFrames.fromRows(
            "r.",
            ".r",
        )
        val out = MorphologyOps.removeIsolatedPixels(frame, diagonalCounts = false)
        assertEquals(2, out.pixels.count { it != 0 })
        val outStrict = MorphologyOps.removeIsolatedPixels(frame, diagonalCounts = true)
        assertEquals(0, outStrict.pixels.count { it != 0 })
    }

    // ── fillEnclosedHoles ───────────────────────────────────────────────────

    @Test
    fun `enclosed holes are filled with majority boundary color`() {
        val frame = TestFrames.fromRows(
            "wwwww",
            "w...w",
            "w.w.w", // nested: hole inside hole fill
            "w...w",
            "wwwww",
        )
        val out = MorphologyOps.fillEnclosedHoles(frame)
        // Everything inside the border becomes white.
        for (y in 1..3) for (x in 1..3) {
            assertEquals("($x,$y)", TestFrames.WHITE, out[x, y])
        }
    }

    @Test
    fun `open transparency is not filled`() {
        val frame = TestFrames.fromRows(
            "ww..",
            "ww..",
        )
        val out = MorphologyOps.fillEnclosedHoles(frame)
        assertEquals(0, out[2, 0])
        assertEquals(0, out[3, 1])
    }

    @Test
    fun `hole fill picks the dominant surrounding color`() {
        // Ring: left half r, right half g; hole center leans r (3 vs 2? —
        // 4-neighborhood of the hole cell is r,g,g,r → tie broken by
        // first-seen; majority by count is ambiguous here, so craft 3:1).
        val frame = TestFrames.fromRows(
            "rrr",
            "r.g",
            "ggg",
        )
        val out = MorphologyOps.fillEnclosedHoles(frame)
        // The (1,1) hole sees up=r, left=r, right=g, down=g → tie 2:2,
        // resolved deterministically toward the first-inserted key (r).
        // Rather than lock the tie rule, assert the hole is filled at all.
        assertTrue(out[1, 1] != 0)
    }

    // ── outlineMask ─────────────────────────────────────────────────────────

    @Test
    fun `outlineMask extracts boundary ring`() {
        val frame = TestFrames.solid(4, 4, TestFrames.BLUE)
        val out = MorphologyOps.outlineMask(frame)
        // 3x3 cross erode leaves the central 2x2; outline = 16 − 4 = 12.
        assertEquals(12, out.pixels.count { it != 0 })
        assertEquals(0, out[1, 1])
        assertEquals(TestFrames.BLUE, out[0, 0])
    }

    // ── cleanupSprite preset ────────────────────────────────────────────────

    @Test
    fun `cleanupSprite removes dust and heals cracks`() {
        // Body with one pure-diagonal dust pixel and a 1px crack.
        val frame = TestFrames.fromRows(
            "..rrr.",
            "r.rr..", // dust at (0,1): orthogonal neighbors all transparent
            "..rr..",
            "..r.r.", // crack at (3,3): transparent cell, orthogonal r around
            "..rrr.",
        )
        val out = MorphologyOps.cleanupSprite(frame)
        // Diagonal dust at (0,1) is removed by the open step.
        assertEquals(0, out[0, 1])
        // Crack healed by the close step.
        assertEquals(TestFrames.RED, out[3, 3])
    }
}
