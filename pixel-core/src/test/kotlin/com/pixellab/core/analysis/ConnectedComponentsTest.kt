package com.pixellab.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedComponentsTest {

    @Test
    fun `two separate blobs under four connectivity`() {
        val frame = TestFrames.fromRows(
            "rr..",
            "rr..",
            "..gg",
            "..gg",
        )
        val cc = ConnectedComponentOps.label(frame, Connectivity.FOUR, ComponentColorMode.OPAQUE)
        assertEquals(2, cc.count)
        val areas = cc.blobs.map { it.area }.sorted()
        assertEquals(listOf(4, 4), areas)
    }

    @Test
    fun `diagonal only contact merges under eight connectivity`() {
        val frame = TestFrames.fromRows(
            "r.",
            ".r",
        )
        val four = ConnectedComponentOps.label(frame, Connectivity.FOUR, ComponentColorMode.OPAQUE)
        assertEquals(2, four.count)
        val eight = ConnectedComponentOps.label(frame, Connectivity.EIGHT, ComponentColorMode.OPAQUE)
        assertEquals(1, eight.count)
        assertEquals(2, eight.blobs[0].area)
    }

    @Test
    fun `same-color mode separates different colors`() {
        val frame = TestFrames.fromRows(
            "rg",
            "gr",
        )
        val cc = ConnectedComponentOps.label(frame, Connectivity.FOUR, ComponentColorMode.SAME_COLOR)
        // Four single-pixel components (each color isolated diagonally).
        assertEquals(4, cc.count)
    }

    @Test
    fun `same-color mode links distant same-color pixels through adjacency`() {
        val frame = TestFrames.fromRows(
            "rrr",
            "g.g",
            "ggg",
        )
        val cc = ConnectedComponentOps.label(frame, Connectivity.FOUR, ComponentColorMode.SAME_COLOR)
        // r block (3) + g block (5, linked via (0,1)-(0,2)-(1,2)-(2,2)-(2,1)) = 2.
        assertEquals(2, cc.count)
        val areas = cc.blobs.map { it.area }.sorted()
        assertEquals(listOf(3, 5), areas)
    }

    @Test
    fun `blob statistics are correct`() {
        val frame = TestFrames.fromRows(
            "rrr..",
            "rrr..",
            ".....",
            ".gg..",
            "..g..",
        )
        val cc = ConnectedComponentOps.label(frame, Connectivity.FOUR, ComponentColorMode.OPAQUE)
        val r = cc.blobs.first { it.area == 6 }
        assertEquals(0, r.minX)
        assertEquals(0, r.minY)
        assertEquals(3, r.maxX)
        assertEquals(2, r.maxY)
        assertEquals(3, r.width)
        assertEquals(2, r.height)
        assertEquals(1.0, r.fillRatio, 1e-9)
        assertEquals(1.0, r.centroidX, 1e-9)
        assertEquals(0.5, r.centroidY, 1e-9)
        // Perimeter: 6-cell rectangle 3x2 → perimeter cells = 6 (all touch outside).
        assertEquals(6, r.perimeter)
        assertTrue(r.touchesBorder)

        // g block: (1,3),(2,3),(2,4) — an L-shaped 4-connected component.
        val g = cc.blobs.first { it.area == 3 }
        assertEquals(1, g.minX)
        assertEquals(3, g.minY)
        assertEquals(3, g.maxX)
        assertEquals(5, g.maxY)
        assertEquals(0, g.holes)
    }

    @Test
    fun `enclosed hole counted once for ring blob`() {
        val frame = TestFrames.fromRows(
            "rrr",
            "r.r",
            "rrr",
        )
        val cc = ConnectedComponentOps.label(frame, Connectivity.FOUR, ComponentColorMode.OPAQUE)
        assertEquals(1, cc.count)
        val ring = cc.blobs[0]
        assertEquals(8, ring.area)
        assertEquals(1, ring.holes)
    }

    @Test
    fun `transparent frame labels nothing`() {
        val cc = ConnectedComponentOps.label(
            com.pixellab.core.model.PixelFrame.blank(3, 3),
        )
        assertEquals(0, cc.count)
        assertNull(cc.largest)
    }

    @Test
    fun `removeSmall strips dust below threshold`() {
        val frame = TestFrames.fromRows(
            "r..r.",
            "....r",
            "r..r.",
        )
        val out = ConnectedComponentOps.removeSmall(frame, 2)
        // All pixels are isolated singletons → everything below 2 dies.
        assertEquals(0, out.pixels.count { it != 0 })

        val frame2 = TestFrames.fromRows(
            "rr.r",
            "rr.r",
        )
        val out2 = ConnectedComponentOps.removeSmall(frame2, 2)
        // The two 2x2 blocks (area 4) survive; the single column (area 2) also
        // survives exactly at threshold.
        assertEquals(6, out2.pixels.count { it != 0 })
    }

    @Test
    fun `keepLargest isolates the biggest component`() {
        val frame = TestFrames.fromRows(
            "rr..gg",
            "rr..gg",
            "rr..g.",
        )
        val out = ConnectedComponentOps.keepLargest(frame)
        // r block area 6 beats g block area 5.
        assertEquals(6, out.pixels.count { it != 0 })
        assertEquals(TestFrames.RED, out[0, 0])
        assertEquals(0, out[4, 0])
    }

    @Test
    fun `label map is consistent with blob membership`() {
        val frame = TestFrames.fromRows(
            "r.g",
            "r.g",
        )
        val cc = ConnectedComponentOps.label(frame, Connectivity.FOUR, ComponentColorMode.OPAQUE)
        assertEquals(2, cc.count)
        assertEquals(1, cc.labels[0]) // r
        assertEquals(0, cc.labels[1]) // transparent
        assertEquals(2, cc.labels[2]) // g
        assertEquals(1, cc.labels[3])
        assertEquals(0, cc.labels[4])
        assertEquals(2, cc.labels[5])
        // Both components touch the border.
        assertEquals(2, cc.borderComponents.size)
    }
}
