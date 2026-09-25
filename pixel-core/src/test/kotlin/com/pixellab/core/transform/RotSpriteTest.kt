package com.pixellab.core.transform

import com.pixellab.core.model.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotSpriteTest {

    @Test
    fun `zero degrees returns input`() {
        val frame = TestArt.rows("rr.", ".gg")
        assertTrue(RotSprite.rotate(frame, 0.0) === frame)
    }

    @Test
    fun `quarter turns delegate to lossless rotations`() {
        val frame = TestArt.rows(
            "rg.",
            "b..",
        )
        assertEquals(frame.rotated90Cw(), RotSprite.rotate(frame, 90.0))
        assertEquals(frame.rotated90Ccw(), RotSprite.rotate(frame, -90.0))
        assertEquals(frame.rotated90Cw().rotated90Cw(), RotSprite.rotate(frame, 180.0))
        // Large equivalent angles normalize to the same result.
        assertEquals(frame.rotated90Cw(), RotSprite.rotate(frame, 450.0))
        assertEquals(frame.rotated90Cw(), RotSprite.rotate(frame, -270.0))
    }

    @Test
    fun `quarter turns with crop keep geometry`() {
        val frame = TestArt.rows(
            "rg.",
            "b..",
        )
        val rotated = RotSprite.rotate(frame, 90.0, RotateBoundsMode.CROP)
        assertEquals(frame.width, rotated.width)
        assertEquals(frame.height, rotated.height)
    }

    @Test
    fun `shearX shifts rows by rounding`() {
        // factor 0.5: row y shifts right by round(y/2) → rows 0,1,2 shift 0,1,1.
        val frame = TestArt.rows(
            "aa",
            "aa",
            "aa",
        )
        val sheared = RotSprite.shearX(frame, 0.5)
        assertEquals(3, sheared.height)
        assertEquals(3, sheared.width) // widened by max shift 1
        // Row 0: 'aa.'  Row 1: '.aa'  Row 2: '.aa'  (indices are [x, y])
        assertEquals(TestArt.A, sheared[0, 0])
        assertEquals(0, sheared[2, 0])
        assertEquals(0, sheared[0, 1])
        assertEquals(TestArt.A, sheared[2, 1])
    }

    @Test
    fun `shearY shifts columns by rounding`() {
        val frame = TestArt.rows(
            "aaa",
            "aaa",
        )
        val sheared = RotSprite.shearY(frame, 1.0)
        assertEquals(3, sheared.width)
        assertEquals(4, sheared.height) // max shift 2 + height 2
        // Column 0 unshifted; column 1 shifts down 1; column 2 shifts down 2.
        assertEquals(TestArt.A, sheared[0, 0])
        assertEquals(TestArt.A, sheared[1, 1])
        assertEquals(0, sheared[1, 0])
        assertEquals(0, sheared[2, 1])
        assertEquals(TestArt.A, sheared[2, 2])
    }

    @Test
    fun `shear zero factor is identity`() {
        val frame = TestArt.rows("ab", "cd")
        assertTrue(RotSprite.shearX(frame, 0.0) === frame)
        assertTrue(RotSprite.shearY(frame, 0.0) === frame)
    }

    @Test
    fun `arbitrary angle expands the canvas`() {
        val frame = TestArt.solid(10, 10, TestArt.A)
        val rotated = RotSprite.rotate(frame, 45.0)
        // A 45° rotation of a square grows it by √2, minus shear rounding.
        val minExpected = (10 * 1.35).toInt()
        assertTrue("width ${rotated.width}", rotated.width >= minExpected)
        assertTrue("height ${rotated.height}", rotated.height >= minExpected)
        // No new colors invented; pixel count preserved.
        assertEquals(100, rotated.pixels.count { it == TestArt.A })
    }

    @Test
    fun `crop mode keeps the original geometry`() {
        val frame = TestArt.solid(8, 6, TestArt.A)
        val rotated = RotSprite.rotate(frame, 30.0, RotateBoundsMode.CROP)
        assertEquals(8, rotated.width)
        assertEquals(6, rotated.height)
    }

    @Test
    fun `single pixel survives any angle`() {
        val frame = TestArt.solid(1, 1, TestArt.A)
        for (deg in listOf(17.0, 45.0, 89.0, 133.0)) {
            val rotated = RotSprite.rotate(frame, deg)
            assertEquals("deg=$deg", 1, rotated.pixels.count { it == TestArt.A })
        }
    }

    @Test
    fun `expanded bounds formula matches math`() {
        val bounds = RotSprite.expandedBounds(10, 20, 30.0)
        // 10·cos30 + 20·sin30 = 8.66 + 10 = 18.66 → 19
        assertEquals(19, bounds[0])
        // 10·sin30 + 20·cos30 = 5 + 17.32 = 22.32 → 23
        assertEquals(23, bounds[1])
    }

    @Test
    fun `opposite angles preserve opaque area`() {
        val frame = TestArt.rows(
            "rrr",
            "..r",
            "..r",
        )
        val cw = RotSprite.rotate(frame, 20.0)
        val ccw = RotSprite.rotate(frame, -20.0)
        assertEquals(cw.pixels.count { it != 0 }, ccw.pixels.count { it != 0 })
    }
}

/** Shared fixtures for the transform tests. */
internal object TestArt {
    const val A = 0xFF102030.toInt()
    const val B = 0xFFAABBCC.toInt()
    const val R = 0xFFFF0000.toInt()
    const val G = 0xFF00FF00.toInt()
    const val K = 0xFF000000.toInt()
    const val W = 0xFFFFFFFF.toInt()
    const val D = 0xFF405060.toInt()

    fun rows(vararg rows: String): PixelFrame {
        val pal = mapOf('a' to A, 'b' to B, 'c' to 0xFF304050.toInt(), 'd' to D, 'r' to R, 'g' to G, 'k' to K, 'w' to W)
        val w = rows[0].length
        val h = rows.size
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = rows[y][x]
                px[y * w + x] = if (c == '.') 0 else (pal[c] ?: error("no palette entry for '$c'"))
            }
        }
        return PixelFrame.of(w, h, px)
    }

    fun solid(w: Int, h: Int, argb: Int): PixelFrame = PixelFrame.of(w, h, IntArray(w * h) { argb })
}
