package com.pixellab.core.export

import com.pixellab.core.io.GifDecoder
import com.pixellab.core.model.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip regression tests for [KotlinGifEncoder] -> [GifDecoder].
 *
 * The encoder and the native NativeGifEncoder produce the same *logical*
 * layout (GCT with transparent slot 0, disposal-2 GCEs, NETSCAPE loop
 * block), so this suite guards the byte-level contract BOTH encoders rely
 * on: palette mapping exactness, transparency survival, delay clamping and
 * loop passthrough. Prior to this suite the encode side had zero tests —
 * only the decoder was exercised on hostile inputs.
 */
class KotlinGifEncoderTest {

    /** Four colors chosen to land in distinct RGB 6-6-5 quantization bins. */
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00CC44.toInt()
    private val blue = 0xFF2040D0.toInt()
    private val white = 0xFFF8F8F8.toInt()
    private val transparent = 0x00000000

    private fun checkerFrame(width: Int, height: Int, a: Int, b: Int): PixelFrame {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if ((x + y) % 2 == 0) a else b
            }
        }
        return PixelFrame.of(width, height, pixels)
    }

    @Test
    fun `round trip preserves canvas size frame count and colors`() {
        val f0 = checkerFrame(8, 6, red, green)
        val f1 = checkerFrame(8, 6, blue, transparent)
        val bytes = KotlinGifEncoder.encode(8, 6, listOf(f0, f1), listOf(120, 240))
        val decoded = GifDecoder.decode(bytes)

        assertEquals(8, decoded.logicalWidth)
        assertEquals(6, decoded.logicalHeight)
        assertEquals(2, decoded.frames.size)
        assertTrue(decoded.hasTransparency)

        // Distinct colors were few: every opaque pixel round-trips exactly.
        for ((i, original) in listOf(f0, f1).withIndex()) {
            val raster = decoded.frames[i].frame
            for (p in 0 until original.pixelCount) {
                assertEquals("frame $i pixel $p", original.pixels[p], raster.pixels[p])
            }
        }
    }

    @Test
    fun `delays are stored in centiseconds with the two cs floor`() {
        val f = checkerFrame(4, 4, red, white)
        // 100 ms -> 10 cs; 0 ms clamps up to the 2 cs renderer-safety floor.
        val decoded = GifDecoder.decode(
            KotlinGifEncoder.encode(4, 4, listOf(f, f), listOf(100, 0)),
        )
        assertEquals(10, decoded.frames[0].delayCs)
        assertEquals(2, decoded.frames[1].delayCs)
    }

    @Test
    fun `loop count passes through and zero means forever`() {
        val f = checkerFrame(4, 4, red, green)
        val looped = GifDecoder.decode(
            KotlinGifEncoder.encode(4, 4, listOf(f), listOf(50), loopCount = 3),
        )
        assertEquals(3, looped.loopCount)

        val forever = GifDecoder.decode(
            KotlinGifEncoder.encode(4, 4, listOf(f), listOf(50), loopCount = 0),
        )
        // Extension present with value 0 (loop forever), NOT missing.
        assertEquals(0, forever.loopCount)
    }

    @Test
    fun `transparent pixels survive the palette round trip`() {
        // Solid opaque checkerboard with a transparent hole.
        val pixels = IntArray(9) { if (it == 4) transparent else red }
        val f = PixelFrame.of(3, 3, pixels)
        val decoded = GifDecoder.decode(KotlinGifEncoder.encode(3, 3, listOf(f), listOf(80)))

        val raster = decoded.frames[0].frame
        assertEquals(transparent, raster.pixels[4])
        for (p in intArrayOf(0, 1, 2, 3, 5, 6, 7, 8)) {
            assertEquals(red, raster.pixels[p])
        }
    }

    @Test
    fun `mismatched delay list is rejected`() {
        val f = checkerFrame(2, 2, red, green)
        try {
            KotlinGifEncoder.encode(2, 2, listOf(f), emptyList())
            throw AssertionError("empty delays must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("delays"))
        }
    }

    @Test
    fun `empty frame list is rejected`() {
        try {
            KotlinGifEncoder.encode(2, 2, emptyList(), emptyList())
            throw AssertionError("empty frames must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("frames"))
        }
    }
}
