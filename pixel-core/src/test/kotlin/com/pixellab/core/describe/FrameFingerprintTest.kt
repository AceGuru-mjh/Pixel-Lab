package com.pixellab.core.describe

import com.pixellab.core.analysis.TestFrames
import com.pixellab.core.model.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FrameFingerprintComputer]: digest sensitivity, alpha-zero
 * canonicalization, dimension mixing and visible-pixel accounting.
 */
class FrameFingerprintTest {

    @Test
    fun `blank canvas fingerprints deterministically`() {
        val a = TestFrames.fromRows("..", "..")
        val b = TestFrames.fromRows("..", "..")
        assertEquals(FrameFingerprintComputer.of(a), FrameFingerprintComputer.of(b))
        assertEquals(16, FrameFingerprintComputer.of(a).digest.length)
        assertEquals(0, FrameFingerprintComputer.of(a).visiblePixels)
    }

    @Test
    fun `single pixel changes the digest`() {
        val blank = TestFrames.fromRows("..", "..")
        val one = TestFrames.fromRows("r.", "..")
        val fBlank = FrameFingerprintComputer.of(blank)
        val fOne = FrameFingerprintComputer.of(one)
        assertNotEquals(fBlank.digest, fOne.digest)
        assertEquals(1, fOne.visiblePixels)
        assertEquals(0, fBlank.visiblePixels)
    }

    @Test
    fun `different color same position changes digest`() {
        val red = TestFrames.fromRows("r.")
        val green = TestFrames.fromRows("g.")
        assertNotEquals(
            FrameFingerprintComputer.of(red).digest,
            FrameFingerprintComputer.of(green).digest,
        )
    }

    @Test
    fun `alpha-zero rgb channels are canonicalized`() {
        // A pixel of 0x00FF0000 is fully transparent: identical to blank.
        val blank = TestFrames.fromRows(".")
        val cosmetic = PixelFrame.of(1, 1, intArrayOf(0x00FF0000))
        assertEquals(
            FrameFingerprintComputer.of(blank).digest,
            FrameFingerprintComputer.of(cosmetic).digest,
        )
    }

    @Test
    fun `dimensions mix into the digest`() {
        // Same pixel sequence, different canvas shape → different digest.
        val wide = TestFrames.fromRows("rr..")
        val tall = TestFrames.fromRows("rr", "..")
        assertNotEquals(
            FrameFingerprintComputer.of(wide).digest,
            FrameFingerprintComputer.of(tall).digest,
        )
    }

    @Test
    fun `digest is stable across repeated computations`() {
        val frame = TestFrames.fromRows(
            "rg.",
            "bw.",
        )
        val first = FrameFingerprintComputer.of(frame)
        val second = FrameFingerprintComputer.of(frame)
        assertEquals(first, second)
        assertEquals(4, first.visiblePixels)
        assertEquals(3, first.width)
        assertEquals(2, first.height)
    }

    @Test
    fun `visible count counts alpha non-zero only`() {
        val half = PixelFrame.of(
            2, 1,
            intArrayOf(0x80FF0000.toInt(), 0x00FFFFFF),
        )
        val fingerprint = FrameFingerprintComputer.of(half)
        assertEquals(1, fingerprint.visiblePixels)
    }

    @Test
    fun `partial alpha is visible and distinct from opaque`() {
        val semi = PixelFrame.of(1, 1, intArrayOf(0x80FF0000.toInt()))
        val opaque = PixelFrame.of(1, 1, intArrayOf(0xFFFF0000.toInt()))
        val semiF = FrameFingerprintComputer.of(semi)
        val opaqueF = FrameFingerprintComputer.of(opaque)
        assertEquals(semiF.visiblePixels, opaqueF.visiblePixels)
        assertNotEquals(semiF.digest, opaqueF.digest)
    }

    @Test
    fun `toString carries the essentials`() {
        val f = FrameFingerprintComputer.of(TestFrames.fromRows("r."))
        val text = f.toString()
        assertTrue(text.contains(f.digest))
        assertTrue(text.contains("2x1"))
        assertTrue(text.contains("1 visible"))
    }
}
