package com.pixellab.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMetricsTest {

    @Test
    fun `identical frames compare clean`() {
        val a = TestFrames.fromRows(
            "rr.g",
            "..bg",
        )
        val stats = ImageMetrics.compare(a, a)
        assertEquals(0L, stats.changedPixels)
        assertEquals(0.0, stats.changedRatio, 1e-9)
        assertEquals(0.0, stats.mae, 1e-9)
        assertEquals(0L, stats.opacityMismatches)
        assertTrue(ImageMetrics.psnr(a, a) == Double.POSITIVE_INFINITY)
    }

    @Test
    fun `geometry mismatch is rejected`() {
        val a = TestFrames.solid(2, 2, TestFrames.RED)
        val b = TestFrames.solid(3, 2, TestFrames.RED)
        try {
            ImageMetrics.compare(a, b)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `mae and max delta from known differences`() {
        val a = TestFrames.solid(2, 1, TestFrames.rgb(100, 100, 100))
        val b = TestFrames.solid(2, 1, TestFrames.rgb(110, 90, 100))
        val stats = ImageMetrics.compare(a, b)
        assertEquals(2L, stats.comparedPixels)
        assertEquals(10.0, stats.maeR, 1e-9)
        assertEquals(10.0, stats.maeG, 1e-9)
        assertEquals(0.0, stats.maeB, 1e-9)
        assertEquals((10.0 + 10.0 + 0.0) / 3.0, stats.mae, 1e-9)
        assertEquals(10, stats.maxChannelDelta)
        assertEquals(2L, stats.changedPixels)
    }

    @Test
    fun `transparent pixels are excluded from error but counted on mismatch`() {
        val a = TestFrames.fromRows("rr..")
        val b = TestFrames.fromRows("rg..")
        val stats = ImageMetrics.compare(a, b)
        // 2 compared, 1 changed (r→g), 2 both-transparent padding ignored.
        assertEquals(2L, stats.comparedPixels)
        assertEquals(1L, stats.changedPixels)
        assertEquals(0L, stats.opacityMismatches)

        val c = TestFrames.fromRows("r...")
        val d = TestFrames.fromRows("rr..")
        val stats2 = ImageMetrics.compare(c, d)
        assertEquals(1L, stats2.opacityMismatches)
        assertEquals(1L, stats2.comparedPixels)
    }

    @Test
    fun `psnr of all-black vs all-white is about 0`() {
        // MSE = 255² per channel → PSNR = 10·log10(255²·255²/255²)/… = 0 dB
        // exactly when MSE equals MAX².
        val a = TestFrames.solid(2, 2, TestFrames.BLACK)
        val b = TestFrames.solid(2, 2, TestFrames.WHITE)
        assertEquals(0.0, ImageMetrics.psnr(a, b), 1e-6)
    }

    @Test
    fun `psnr grows as error shrinks`() {
        val a = TestFrames.solid(2, 2, TestFrames.rgb(100, 100, 100))
        val small = TestFrames.solid(2, 2, TestFrames.rgb(110, 100, 100))
        val large = TestFrames.solid(2, 2, TestFrames.rgb(200, 100, 100))
        val psnrSmall = ImageMetrics.psnr(a, small)
        val psnrLarge = ImageMetrics.psnr(a, large)
        assertTrue(psnrSmall > psnrLarge)
    }

    @Test
    fun `diffMask colors differences`() {
        val a = TestFrames.fromRows(
            "rr..",
            "gg..",
        )
        val b = TestFrames.fromRows(
            "rg..",
            "gg..",
        )
        val mask = ImageMetrics.diffMask(a, b)
        assertEquals(0xFFFF0000.toInt(), mask[1, 0])
        assertEquals(0xFF00FF00.toInt(), mask[0, 0])
        assertEquals(0xFF00FF00.toInt(), mask[0, 1])
        assertEquals(0, mask[2, 0])
    }

    @Test
    fun `diffMask tolerance absorbs small noise`() {
        val a = TestFrames.solid(2, 1, TestFrames.rgb(100, 100, 100))
        val b = TestFrames.solid(2, 1, TestFrames.rgb(105, 95, 100))
        val strict = ImageMetrics.diffMask(a, b, tolerance = 0)
        val loose = ImageMetrics.diffMask(a, b, tolerance = 6)
        assertEquals(0xFFFF0000.toInt(), strict[0, 0])
        assertEquals(0xFF00FF00.toInt(), loose[0, 0])
    }

    @Test
    fun `diffMask flags opacity mismatches as diffs`() {
        val a = TestFrames.fromRows("r.")
        val b = TestFrames.fromRows("rr")
        val mask = ImageMetrics.diffMask(a, b)
        assertEquals(0xFFFF0000.toInt(), mask[1, 0])
    }

    @Test
    fun `changedBounds finds tight box`() {
        val a = TestFrames.solid(6, 6, TestFrames.RED)
        val b = a.withPixel(2, 3, TestFrames.GREEN).withPixel(4, 5, TestFrames.BLUE)
        val bounds = ImageMetrics.changedBounds(a, b)
        requireNotNull(bounds)
        org.junit.Assert.assertArrayEquals(intArrayOf(2, 3, 5, 6), bounds)
    }

    @Test
    fun `changedBounds null on equal frames`() {
        val a = TestFrames.solid(3, 3, TestFrames.RED)
        assertNull(ImageMetrics.changedBounds(a, a))
    }

    @Test
    fun `changedBounds respects tolerance`() {
        val a = TestFrames.solid(3, 3, TestFrames.rgb(100, 100, 100))
        val b = TestFrames.solid(3, 3, TestFrames.rgb(103, 97, 100))
        assertNull(ImageMetrics.changedBounds(a, b, tolerance = 4))
        requireNotNull(ImageMetrics.changedBounds(a, b, tolerance = 2))
    }
}
