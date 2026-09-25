package com.pixellab.core.transform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResampleTest {

    @Test
    fun `nearest on same size is identity`() {
        val frame = TestArt.rows("ab", "cd")
        assertTrue(Resample.nearest(frame, 2, 2) === frame)
    }

    @Test
    fun `nearest upscale picks nearest source`() {
        val frame = TestArt.rows("ab")
        val out = Resample.nearest(frame, 4, 2)
        // Half-pixel centers: x=0,1 → sx 0,0? (0.5*1/4=0.125→0, 1.5*0.25=0.375→0)
        // x=2,3 → 0.625→0? no: (2+0.5)*1/4=0.625→0; (3.5)*0.25=0.875→0.
        // All map to column 0 for 1→4? w=2: (x+0.5)*2/4: 0.25→0, 0.75→0,
        // 1.25→1, 1.75→1.
        assertEquals(TestArt.A, out[0, 0])
        assertEquals(TestArt.A, out[1, 0])
        assertEquals(TestArt.B, out[2, 0])
        assertEquals(TestArt.B, out[3, 0])
    }

    @Test
    fun `nearest downscale samples cell centers`() {
        // 4x1 → 2x1: cell 0 covers [0,2) center 1 → src 1; cell 1 covers
        // [2,4) center 3 → src 3.
        val frame = TestArt.rows("abcd")
        val out = Resample.nearest(frame, 2, 1)
        assertEquals(TestArt.B, out[0, 0])
        assertEquals(TestArt.D, out[1, 0])
    }

    @Test
    fun `box resample of uniform frame is uniform`() {
        val frame = TestArt.solid(6, 6, TestArt.A)
        val out = Resample.boxResample(frame, 3, 3)
        assertTrue(out.pixels.all { it == TestArt.A })
    }

    @Test
    fun `box downscale averages the covered area`() {
        // 2x2 with two a and two b → 1x1 average is 50/50 premultiplied.
        val frame = TestArt.rows(
            "aa",
            "bb",
        )
        val out = Resample.boxResample(frame, 1, 1)
        val r = out[0, 0] ushr 16 and 0xFF
        val b = out[0, 0] and 0xFF
        val g = out[0, 0] ushr 8 and 0xFF
        assertEquals(255, out[0, 0] ushr 24)
        // A = 0x102030, B = 0xAABBCC → per-channel averages 0x5D, 0x6D
        // (109.5 truncates), 0x7E.
        assertEquals(0x5D, r)
        assertEquals(0x6D, g)
        assertEquals(0x7E, b)
    }

    @Test
    fun `box downscale blends transparency without black bleed`() {
        // Left half opaque white, right half transparent → 1x1 output
        // should be 50% white (NOT dark gray).
        val frame = TestArt.rows(
            "w.",
            "w.",
        )
        val out = Resample.boxResample(frame, 1, 1)
        assertEquals(128, out[0, 0] ushr 24) // half coverage
        assertEquals(255, out[0, 0] ushr 16 and 0xFF) // un-premultiplied stays white
        assertEquals(255, out[0, 0] and 0xFF)
    }

    @Test
    fun `downscaleByInt averages integer blocks`() {
        val frame = TestArt.rows(
            "aaaa",
            "aabb",
        )
        val out = Resample.downscaleByInt(frame, 2)
        assertEquals(2, out.width)
        assertEquals(1, out.height)
        // Block (0,0): a,a,a,a → a.
        assertEquals(TestArt.A, out[0, 0])
        // Block (1,0): a,b,b? cells (2,0)=a (3,0)=b (2,1)=b (3,1)=b → avg of A and B.
        val avg = out[1, 0]
        assertEquals(0x5D, avg ushr 16 and 0xFF)
    }

    @Test
    fun `mipmap chain halves each level`() {
        val frame = TestArt.solid(16, 16, TestArt.A)
        val chain = Resample.mipmapChain(frame)
        assertEquals(listOf(16, 8, 4, 2, 1), chain.map { it.width })
        assertEquals(5, chain.size)
        assertTrue(chain.all { it.pixels.all { p -> p == TestArt.A } })
    }

    @Test
    fun `mipmap respects max levels`() {
        val frame = TestArt.solid(64, 64, TestArt.A)
        val chain = Resample.mipmapChain(frame, maxLevels = 3)
        assertEquals(3, chain.size)
        assertEquals(16, chain.last().width)
    }

    @Test
    fun `validation rejects bad dimensions`() {
        val frame = TestArt.solid(2, 2, TestArt.A)
        try {
            Resample.nearest(frame, 0, 1)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) { }
        try {
            Resample.downscaleByInt(frame, 1)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) { }
        try {
            Resample.mipmapChain(frame, 0)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) { }
    }
}

class RotateOpsTest {

    @Test
    fun `rotate facade delegates quarters`() {
        val frame = TestArt.rows(
            "rg.",
            "b..",
        )
        assertEquals(frame.rotated180(), RotateOps.rotate(frame, 180.0))
    }

    @Test
    fun `rotateNearest handles quarter exactly`() {
        val frame = TestArt.rows(
            "rg.",
            "b..",
        )
        assertEquals(frame.rotated90Cw(), RotateOps.rotateNearest(frame, 90.0))
    }

    @Test
    fun `rotateNearest small angle keeps most pixels`() {
        val frame = TestArt.solid(12, 12, TestArt.A)
        val out = RotateOps.rotateNearest(frame, 5.0)
        // 5° on an expanded canvas: nearly all 144 pixels survive.
        assertTrue(out.pixels.count { it == TestArt.A } > 120)
        assertEquals(TestArt.A, out[out.width / 2, out.height / 2])
    }

    @Test
    fun `rotateNearest crop keeps geometry`() {
        val frame = TestArt.solid(9, 7, TestArt.A)
        val out = RotateOps.rotateNearest(frame, 33.0, RotateBoundsMode.CROP)
        assertEquals(9, out.width)
        assertEquals(7, out.height)
    }

    @Test
    fun `rotateInPlace keeps geometry for arbitrary angle`() {
        val frame = TestArt.solid(10, 10, TestArt.A)
        val out = RotateOps.rotateInPlace(frame, 40.0)
        assertEquals(10, out.width)
        assertEquals(10, out.height)
    }
}
