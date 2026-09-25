package com.pixellab.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvolutionTest {

    // ── Kernel model ────────────────────────────────────────────────────────

    @Test
    fun `identity kernel leaves frame unchanged`() {
        val frame = TestFrames.fromRows(
            "rrg",
            ".rg",
        )
        val out = ConvolutionOps.convolve(frame, ConvolutionKernel.IDENTITY)
        assertEquals(frame, out)
    }

    @Test
    fun `kernel validation`() {
        try {
            ConvolutionKernel(3, 3, FloatArray(8), 1f, 0f)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { }
        try {
            ConvolutionKernel(3, 3, FloatArray(9), 0f, 0f)
            throw AssertionError("Expected IllegalArgumentException for zero divisor")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `box blur on solid frame is a no-op colorwise`() {
        val frame = TestFrames.solid(6, 6, TestFrames.rgb(120, 60, 200))
        val out = ConvolutionOps.boxBlur(frame, 3, 2)
        assertEquals(frame, out)
    }

    @Test
    fun `averaging detection for standard kernels`() {
        assertTrue(ConvolutionKernel.BOX_BLUR_3.isAveraging)
        assertTrue(ConvolutionKernel.GAUSSIAN_3.isAveraging)
        assertTrue(!ConvolutionKernel.SHARPEN.isAveraging)
        assertTrue(!ConvolutionKernel.LAPLACIAN.isAveraging)
    }

    // ── Edge modes ──────────────────────────────────────────────────────────

    @Test
    fun `clamp edge mode replicates border pixels`() {
        // Single white pixel on black, 3x3 box: with CLAMP the corner
        // output averages 9 samples of which the center and its clamped
        // replicas contribute.
        val frame = TestFrames.fromRows(
            "kkk",
            "kwk",
            "kkk",
        )
        val out = ConvolutionOps.convolve(
            frame, ConvolutionKernel.BOX_BLUR_3,
            ConvolutionEdgeMode.CLAMP, ConvolutionAlphaMode.STRAIGHT,
        )
        // Center: all 9 taps are k or w → (8*k + w)/9 = 255/9 ≈ 28.
        assertEquals(28, out[1, 1] and 0xFF)
        // Top-left corner with clamp: taps include w once (its (1,1)
        // neighbor clamped) → same 28.
        assertEquals(28, out[0, 0] and 0xFF)
    }

    @Test
    fun `wrap edge mode tiles the raster`() {
        // 2x2 checkerboard r/b wrapped with 3x3 box stays 50/50 mix.
        val frame = TestFrames.fromRows(
            "rb",
            "br",
        )
        val out = ConvolutionOps.convolve(
            frame, ConvolutionKernel.BOX_BLUR_3,
            ConvolutionEdgeMode.WRAP, ConvolutionAlphaMode.STRAIGHT,
        )
        for (y in 0..1) for (x in 0..1) {
            // Every position sees exactly 4r + 5b or 5r + 4b under wrap.
            val r = out[x, y] ushr 16 and 0xFF
            val b = out[x, y] and 0xFF
            assertTrue("r=$r b=$b", r == 113 && b == 142 || r == 142 && b == 113)
        }
    }

    @Test
    fun `transparent edge mode pulls coverage toward borders`() {
        val frame = TestFrames.solid(3, 3, TestFrames.WHITE)
        val out = ConvolutionOps.convolve(
            frame, ConvolutionKernel.BOX_BLUR_3,
            ConvolutionEdgeMode.TRANSPARENT, ConvolutionAlphaMode.PREMULTIPLIED,
        )
        // Center keeps full alpha; the (0,0) corner sees only the 4
        // in-bounds taps of its 9 → 4/9 of 255 ≈ 113.
        assertEquals(255, out[1, 1] ushr 24)
        assertEquals(113, out[0, 0] ushr 24)
    }

    // ── Alpha modes ─────────────────────────────────────────────────────────

    @Test
    fun `premultiplied blur avoids black halos around transparency`() {
        // A white block over transparency, blurred 3x3: pixels just outside
        // the block must stay gray-white, not dark.
        val frame = TestFrames.fromRows(
            "..ww..",
            "..ww..",
            "..ww..",
            "..ww..",
        )
        val out = ConvolutionOps.boxBlur(frame, 3, 1)
        val sample = out[1, 2] // left neighbor of the block
        val a = sample ushr 24
        val r = sample ushr 16 and 0xFF
        assertTrue("alpha should be partial, was $a", a in 1..254)
        // Un-premultiplied color must remain bright white.
        assertEquals(255, r)
    }

    @Test
    fun `alpha-only convolution filters opacity but keeps center color`() {
        val frame = TestFrames.fromRows(
            "..ww..",
            "..ww..",
            "..ww..",
            "..ww..",
        )
        val out = ConvolutionOps.convolve(
            frame, ConvolutionKernel.BOX_BLUR_3,
            ConvolutionEdgeMode.TRANSPARENT, ConvolutionAlphaMode.ALPHA_ONLY,
        )
        // Every originally white pixel keeps its white color.
        assertEquals(255, out[2, 1] ushr 16 and 0xFF)
        // A pixel outside gains partial alpha from the filtered mask.
        assertTrue(out[1, 1] ushr 24 > 0)
    }

    // ── Specific kernels ────────────────────────────────────────────────────

    @Test
    fun `gaussian3 equals two-pass binomial smoothing`() {
        val frame = TestFrames.fromRows(
            "kwwwk",
            "kwwwk",
            "kwwwk",
        )
        val once = ConvolutionOps.convolve(frame, ConvolutionKernel.GAUSSIAN_3)
        val twice = ConvolutionOps.convolve(once, ConvolutionKernel.GAUSSIAN_3)
        // GAUSSIAN_5 == two GAUSSIAN_3 passes for interior pixels.
        val direct = ConvolutionOps.convolve(frame, ConvolutionKernel.GAUSSIAN_5)
        // Compare an interior pixel: (2,1).
        assertEquals(direct[2, 1], twice[2, 1])
    }

    @Test
    fun `sobel magnitude peaks on vertical edges`() {
        // Left half black, right half white → vertical edge at x=1.
        val frame = TestFrames.fromRows(
            "kww",
            "kww",
            "kww",
        )
        val mag = ConvolutionOps.sobelMagnitude(frame)
        val edge = mag[1, 1] and 0xFF
        val flat = mag[2, 1] and 0xFF
        assertTrue("edge ($edge) should dominate flat ($flat)", edge > flat + 200)
    }

    @Test
    fun `laplacian responds to center surges only`() {
        // Uniform frame: Laplacian output is flat mid value via clamping
        // (sum of weights 0 → offset 0 → black).
        val frame = TestFrames.solid(5, 5, TestFrames.rgb(100, 100, 100))
        val out = ConvolutionOps.convolve(
            frame, ConvolutionKernel.LAPLACIAN,
            ConvolutionEdgeMode.CLAMP, ConvolutionAlphaMode.STRAIGHT,
        )
        assertEquals(0, out[2, 2] and 0xFF) // 0 * anything = 0
        // Single bright pixel: center of the surge reads strongly negative
        // (clamped to 0), its neighbor reads the positive peak.
        val spike = TestFrames.fromRows(
            "kkk",
            "kwk",
            "kkk",
        )
        val s = ConvolutionOps.convolve(
            spike, ConvolutionKernel.LAPLACIAN,
            ConvolutionEdgeMode.CLAMP, ConvolutionAlphaMode.STRAIGHT,
        )
        assertEquals(255, s[2, 1] and 0xFF) // neighbor: +w − 0 = 255
        assertEquals(0, s[1, 1] and 0xFF)   // center: −4·255 clamped
    }

    @Test
    fun `boxBlur validation`() {
        val frame = TestFrames.solid(2, 2, TestFrames.RED)
        try {
            ConvolutionOps.boxBlur(frame, 0, 1)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { }
        try {
            ConvolutionOps.boxBlur(frame, 3, 0)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { }
    }
}
