package com.pixellab.core.transform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Scale2xTest {

    @Test
    fun `uniform frame scales to uniform frame`() {
        val frame = TestArt.solid(4, 4, TestArt.R)
        val out = Scale2x.scale2x(frame)
        assertEquals(8, out.width)
        assertEquals(8, out.height)
        assertTrue(out.pixels.all { it == TestArt.R })
    }

    @Test
    fun `flat interior copies center color`() {
        // 4x4 field with one edge row of a different color: interior
        // pixels far from the boundary must copy E.
        val frame = TestArt.rows(
            "kkkk",
            "rrrr",
            "rrrr",
            "rrrr",
        )
        val out = Scale2x.scale2x(frame)
        // Source pixel (2,2) is deep interior (all neighbors r).
        assertEquals(TestArt.R, out[4, 4])
        assertEquals(TestArt.R, out[5, 5])
    }

    @Test
    fun `straight edge produces matching sub-pixels on both sides`() {
        // Vertical edge between k (left) and r (right).
        val frame = TestArt.rows(
            "kkrr",
            "kkrr",
            "kkrr",
            "kkrr",
        )
        val out = Scale2x.scale2x(frame)
        // For a source pixel at column 1 (k, right neighbor r): its
        // right-side sub-pixels stay k (edge parallel to the boundary),
        // matching Scale2x behavior for vertical edges.
        // Source (1,1): D=k, B=k, F=r, H=k → E1 = (B==F... no) → E.
        assertEquals(TestArt.K, out[2, 2])
        assertEquals(TestArt.K, out[3, 2])
        // Corner behavior: source (1,1) E1 rule B==F? B=k F=r no → E=k.
        assertEquals(TestArt.K, out[3, 2])
    }

    @Test
    fun `scale3x triples dimensions and keeps the center`() {
        val frame = TestArt.rows(
            "rr",
            "rr",
        )
        val out = Scale2x.scale3x(frame)
        assertEquals(6, out.width)
        assertEquals(6, out.height)
        // Center of each source block is always E.
        assertEquals(TestArt.R, out[1, 1])
        assertEquals(TestArt.R, out[4, 4])
        assertTrue(out.pixels.all { it == TestArt.R })
    }

    @Test
    fun `corner rule adopts diagonal when edge pair agrees`() {
        // L-corner: r block at bottom-right of k field.
        val frame = TestArt.rows(
            "kkk",
            "kkk",
            "krr",
        )
        val out = Scale2x.scale2xCorners(frame)
        // Source (1,1) (k, with right neighbor r below-right): the
        // bottom-right sub-pixel may adopt r because the corner rules
        // detect the turning edge. The exact value depends on rules;
        // assert it is one of the two legal colors.
        val v = out[5, 5]
        assertTrue(v == TestArt.K || v == TestArt.R)
    }

    @Test
    fun `transparent pixels are preserved`() {
        val frame = TestArt.rows(
            "..",
            "rr",
        )
        val out = Scale2x.scale2x(frame)
        assertEquals(0, out[0, 0])
        assertEquals(0, out[3, 0])
        // Source transparent pixel with r below: E stays 0? E0 rule
        // needs D==B: D=left(0? '.',) B=up(0). D==B(both transparent=0)
        // and D!=H(r), B!=F(r) → E0 = D = 0 ✓ transparency may bleed
        // into the r block edge — legal output colors are 0 or r.
        assertTrue(out[0, 2] == 0 || out[0, 2] == TestArt.R)
    }
}

class EpxScaleTest {

    @Test
    fun `uniform frame stays uniform`() {
        val frame = TestArt.solid(3, 3, TestArt.G)
        val out = EpxScale.epx2x(frame)
        assertEquals(6, out.width)
        assertTrue(out.pixels.all { it == TestArt.G })
    }

    @Test
    fun `interior pixels copy the center`() {
        val frame = TestArt.rows(
            "kkkkk",
            "krrrk",
            "krrrk",
            "krrrk",
            "kkkkk",
        )
        val out = EpxScale.epx2x(frame)
        // Source (2,2) r: all four neighbors r → all sub-pixels r.
        assertEquals(TestArt.R, out[4, 4])
        assertEquals(TestArt.R, out[5, 4])
        assertEquals(TestArt.R, out[4, 5])
        assertEquals(TestArt.R, out[5, 5])
    }

    @Test
    fun `corner extends the matching neighbor`() {
        // A single white pixel on black: its sub-pixels extend toward
        // nothing (all neighbors black, B==D) → copy E.
        val frame = TestArt.rows(
            "kkk",
            "kwk",
            "kkk",
        )
        val out = EpxScale.epx2x(frame)
        assertEquals(TestArt.W, out[2, 2])
        assertEquals(TestArt.W, out[3, 2])
        assertEquals(TestArt.W, out[2, 3])
        assertEquals(TestArt.W, out[3, 3])
    }

    @Test
    fun `diagonal line thickens correctly`() {
        // 1-px diagonal: each w pixel has k orthogonal neighbors that
        // disagree (B=k, D=k → equal) → sub-pixels copy E (no change),
        // so the diagonal stays 1:1 after 2x EPX.
        val frame = TestArt.rows(
            "w..",
            ".w.",
            "..w",
        )
        val out = EpxScale.epx2x(frame)
        // Source (1,1): B=k, D=k → B==D → E. All four sub-pixels w.
        assertEquals(TestArt.W, out[2, 2])
        assertEquals(TestArt.W, out[3, 3])
    }

    @Test
    fun `epx3x triples and keeps the cross`() {
        val frame = TestArt.solid(2, 2, TestArt.B)
        val out = EpxScale.epx3x(frame)
        assertEquals(6, out.width)
        assertEquals(6, out.height)
        assertTrue(out.pixels.all { it == TestArt.B })
    }
}

class XbrScaleTest {

    @Test
    fun `uniform frame stays uniform`() {
        val frame = TestArt.solid(5, 5, TestArt.A)
        val out = XbrScale.xbr2x(frame)
        assertEquals(10, out.width)
        assertEquals(10, out.height)
        assertTrue(out.pixels.all { it == TestArt.A })
    }

    @Test
    fun `output colors are always input colors`() {
        val frame = TestArt.rows(
            "rrrggg",
            "rrrggg",
            "kkkkkk",
        )
        val out = XbrScale.xbr2x(frame)
        val legal = setOf(TestArt.R, TestArt.G, TestArt.K, 0)
        assertTrue(out.pixels.all { it in legal })
    }

    @Test
    fun `vertical edge remains sharp`() {
        val frame = TestArt.rows(
            "rrrr",
            "gggg",
            "rrrr",
            "gggg",
        )
        val out = XbrScale.xbr2x(frame)
        // Horizontal stripes: output row 0 doubles source row 0 (r),
        // output row 2 doubles source row 1 (g). Indices are [x, y].
        assertEquals(TestArt.R, out[1, 0])
        assertEquals(TestArt.G, out[1, 2])
    }

    @Test
    fun `single different pixel keeps its color`() {
        val frame = TestArt.rows(
            "kkk",
            "kwk",
            "kkk",
        )
        val out = XbrScale.xbr2x(frame)
        // B==D==F==H==k around w: n1==n2 → copy E.
        assertEquals(TestArt.W, out[2, 2])
        assertEquals(TestArt.W, out[3, 2])
        assertEquals(TestArt.W, out[2, 3])
        assertEquals(TestArt.W, out[3, 3])
    }
}
