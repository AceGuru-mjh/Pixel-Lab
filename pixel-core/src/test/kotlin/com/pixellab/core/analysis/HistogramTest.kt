package com.pixellab.core.analysis

import com.pixellab.core.model.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramTest {

    // ── ChannelHistogram basics ─────────────────────────────────────────────

    @Test
    fun `empty histogram statistics`() {
        val h = ChannelHistogram(IntArray(256))
        assertEquals(0L, h.total)
        assertEquals(0, h.distinctValues)
        assertEquals(0.0, h.mean, 1e-9)
        assertEquals(0.0, h.entropy, 1e-9)
        assertEquals(0, h.percentile(0.5))
    }

    @Test
    fun `mean variance and mode from known samples`() {
        // Four samples: 10, 10, 20, 30 → mean 17.5, mode 10.
        val counts = IntArray(256)
        counts[10] = 2
        counts[20] = 1
        counts[30] = 1
        val h = ChannelHistogram(counts)
        assertEquals(4L, h.total)
        assertEquals(3, h.distinctValues)
        assertEquals(17.5, h.mean, 1e-9)
        assertEquals(10, h.mode)
        // Population variance = mean of squared deviations.
        val expected = ((10 - 17.5).pow(2) * 2 + (20 - 17.5).pow(2) + (30 - 17.5).pow(2)) / 4.0
        assertEquals(expected, h.variance, 1e-9)
    }

    private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())

    @Test
    fun `cumulative and percentile`() {
        val counts = IntArray(256)
        counts[0] = 5
        counts[128] = 4
        counts[255] = 1
        val h = ChannelHistogram(counts)
        assertEquals(5L, h.cumulative(0))
        assertEquals(9L, h.cumulative(128))
        assertEquals(10L, h.cumulative(255))
        // 50% of 10 = 5th sample → first bin reaching it is 0.
        assertEquals(0, h.percentile(0.5))
        // 95% = 9.5 → cumulative ≥ 10 first at 255.
        assertEquals(255, h.percentile(0.95))
    }

    @Test
    fun `uniform distribution maximizes entropy at 8 bits`() {
        val counts = IntArray(256) { 1 }
        val h = ChannelHistogram(counts)
        assertEquals(8.0, h.entropy, 1e-9)
    }

    @Test
    fun `constructor validation`() {
        try {
            ChannelHistogram(IntArray(10))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { }
    }

    // ── HistogramOps.compute ────────────────────────────────────────────────

    @Test
    fun `compute excludes transparent pixels by default`() {
        val frame = TestFrames.fromRows(
            "rr..",
            "rr..",
        )
        val h = HistogramOps.compute(frame)
        assertEquals(4, h.sampleCount)
        assertEquals(0.5, h.transparentRatio, 1e-9)
        assertEquals(4L, h.red.total)
        assertEquals(0, h.red.count(0))
        assertEquals(4, h.red.count(0xFF))
        assertEquals(8L, h.alpha.total) // alpha histogram covers every pixel
        assertEquals(4, h.alpha.count(255))
        assertEquals(4, h.alpha.count(0))
    }

    @Test
    fun `luma of pure channels`() {
        val frame = PixelFrame.of(3, 1, intArrayOf(TestFrames.RED, TestFrames.GREEN, TestFrames.BLUE))
        val h = HistogramOps.compute(frame)
        // Rec.709 weights: R→54, G→182, B→18 (rounded).
        assertEquals(1, h.luma.count(54))
        assertEquals(1, h.luma.count(182))
        assertEquals(1, h.luma.count(18))
    }

    @Test
    fun `equalize preserves transparency and expands narrow luma range`() {
        // All pixels share one low luma (dark red) → equalize lifts them.
        val dark = TestFrames.rgb(40, 0, 0)
        val frame = TestFrames.solid(4, 2, dark)
        val out = HistogramOps.equalize(frame)
        // After equalization the single occupied bin maps to the top of the CDF.
        val newLuma = lumaOf(out.pixels[0])
        assertTrue("expected luma lifted above $newLuma", newLuma > 40)
        // Hue channel ratio: r must still dominate g and b.
        val r = out.pixels[0] ushr 16 and 0xFF
        val g = out.pixels[0] ushr 8 and 0xFF
        val b = out.pixels[0] and 0xFF
        assertTrue(r > g && r > b)
    }

    /** Local Rec.709 luma mirror (the production one is internal). */
    private fun lumaOf(argb: Int): Int =
        (0.2126 * (argb ushr 16 and 0xFF) + 0.7152 * (argb ushr 8 and 0xFF) +
            0.0722 * (argb and 0xFF) + 0.5).toInt().coerceIn(0, 255)

    // ── OtsuThreshold ───────────────────────────────────────────────────────

    @Test
    fun `otsu splits bimodal histogram between modes`() {
        val counts = IntArray(256)
        for (v in 0..60) counts[v] = 10
        for (v in 180..255) counts[v] = 10
        val t = OtsuThreshold.single(ChannelHistogram(counts))
        assertTrue("threshold should sit in the valley, was $t", t in 60..180)
    }

    @Test
    fun `otsu degenerates safely on flat histograms`() {
        val flat = ChannelHistogram(IntArray(256).also { it[7] = 9 })
        assertEquals(0, OtsuThreshold.single(flat))
    }

    @Test
    fun `multi-level otsu returns ascending thresholds`() {
        val counts = IntArray(256)
        for (v in 0..40) counts[v] = 5
        for (v in 100..140) counts[v] = 5
        for (v in 200..255) counts[v] = 5
        val ts = OtsuThreshold.multi(ChannelHistogram(counts), 3)
        assertEquals(2, ts.size)
        assertTrue(ts[0] < ts[1])
        assertTrue("first threshold near first valley: ${ts[0]}", ts[0] in 40..100)
        assertTrue("second threshold near second valley: ${ts[1]}", ts[1] in 140..200)
    }

    // ── equalize / stretch / binarize ───────────────────────────────────────

    @Test
    fun `equalize returns same reference for fully transparent frame`() {
        val blank = PixelFrame.blank(4, 4)
        assertTrue(HistogramOps.equalize(blank) === blank)
    }

    @Test
    fun `stretch maps percentile extrema to 0 and 255`() {
        // 6 distinct grays: 10, 20, 30, 40, 200, 220
        val grays = intArrayOf(10, 20, 30, 40, 200, 220).map { TestFrames.rgb(it, it, it) }
        val frame = PixelFrame.of(6, 1, grays.toIntArray())
        val out = HistogramOps.stretch(frame, 0.0, 1.0)
        assertEquals(0, out.pixels[0] and 0xFF)
        assertEquals(255, out.pixels[5] and 0xFF)
        // Monotonic mapping preserved.
        for (i in 0..4) {
            assertTrue((out.pixels[i + 1] and 0xFF) >= (out.pixels[i] and 0xFF))
        }
    }

    @Test
    fun `stretch rejects inverted percentiles`() {
        val frame = TestFrames.solid(2, 2, TestFrames.RED)
        try {
            HistogramOps.stretch(frame, 0.9, 0.1)
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `binarize splits by luma and keeps transparency`() {
        val frame = TestFrames.fromRows(
            "wk.",
            "kw.",
        )
        val t = 128
        val out = HistogramOps.binarize(frame, t)
        assertEquals(TestFrames.WHITE, out[0, 0])
        assertEquals(TestFrames.BLACK, out[1, 0])
        assertEquals(TestFrames.BLACK, out[0, 1])
        assertEquals(TestFrames.WHITE, out[1, 1])
        assertEquals(0, out[2, 0])
        assertEquals(0, out[2, 1])
    }
}
