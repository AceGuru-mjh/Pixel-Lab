package com.pixellab.core.color

import com.pixellab.core.model.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorVisionTest {

    private fun solid(w: Int, h: Int, argb: Int) = PixelFrame.of(w, h, IntArray(w * h) { argb })

    @Test
    fun `severity zero is identity`() {
        val c = 0xFF3366CC.toInt()
        assertEquals(c, ColorVision.simulatePixel(c, ColorVision.Deficiency.PROTAN, 0.0))
        val frame = solid(2, 2, c)
        assertTrue(ColorVision.simulate(frame, ColorVision.Deficiency.DEUTAN, 0.0) === frame)
    }

    @Test
    fun `protan simulation collapses red toward yellow-green`() {
        val red = 0xFFFF0000.toInt()
        val sim = ColorVision.simulatePixel(red, ColorVision.Deficiency.PROTAN, 1.0)
        // Red loses its long-cone pop: simulated R drops, G rises.
        val r = sim ushr 16 and 0xFF
        val g = sim ushr 8 and 0xFF
        assertTrue("r=$r g=$g", r < 255 && g > 60)
    }

    @Test
    fun `deutan simulation collapses green`() {
        val green = 0xFF00FF00.toInt()
        val sim = ColorVision.simulatePixel(green, ColorVision.Deficiency.DEUTAN, 1.0)
        // Deuteranopes see green as khaki/gray-ish: G drops sharply.
        val g = sim ushr 8 and 0xFF
        assertTrue("g=$g", g < 220)
    }

    @Test
    fun `tritan simulation shifts blue`() {
        val blue = 0xFF0000FF.toInt()
        val sim = ColorVision.simulatePixel(blue, ColorVision.Deficiency.TRITAN, 1.0)
        val b = sim and 0xFF
        val r = sim ushr 16 and 0xFF
        assertTrue("b=$b r=$r", b < 255 || r > 0) // blue moves off pure blue
    }

    @Test
    fun `achromat simulation is grayscale luma`() {
        val c = 0xFF3060C0.toInt()
        val sim = ColorVision.simulatePixel(c, ColorVision.Deficiency.ACHROMAT, 1.0)
        val r = sim ushr 16 and 0xFF
        val g = sim ushr 8 and 0xFF
        val b = sim and 0xFF
        assertEquals(r, g)
        assertEquals(g, b)
    }

    @Test
    fun `severity blends smoothly`() {
        val c = 0xFFC04030.toInt()
        val full = ColorVision.simulatePixel(c, ColorVision.Deficiency.PROTAN, 1.0)
        val half = ColorVision.simulatePixel(c, ColorVision.Deficiency.PROTAN, 0.5)
        val dFull = kotlin.math.abs((full ushr 16 and 0xFF) - (c ushr 16 and 0xFF))
        val dHalf = kotlin.math.abs((half ushr 16 and 0xFF) - (c ushr 16 and 0xFF))
        assertTrue("half ($dHalf) should sit between identity and full ($dFull)", dHalf in 0..dFull)
    }

    @Test
    fun `alpha channel passes through`() {
        val c = 0x80FF0080.toInt()
        val sim = ColorVision.simulatePixel(c, ColorVision.Deficiency.DEUTAN, 1.0)
        assertEquals(0x80, sim ushr 24)
    }

    @Test
    fun `frame simulation maps every opaque pixel`() {
        val frame = PixelFrame.of(2, 1, intArrayOf(0xFFFF0000.toInt(), 0))
        val sim = ColorVision.simulate(frame, ColorVision.Deficiency.PROTAN, 1.0)
        assertEquals(0, sim.pixels[1]) // transparent untouched
        assertTrue(sim.pixels[0] != 0xFFFF0000.toInt())
    }

    @Test
    fun `comparison strip layout`() {
        val frame = solid(8, 8, 0xFF4080C0.toInt())
        val strip = ColorVision.comparisonStrip(frame, severity = 1.0, cellWidth = 8, cellHeight = 8)
        assertEquals(5, strip.width / 8)
        assertEquals(8, strip.height)
    }

    @Test
    fun `brettel projection keeps neutral axis`() {
        // A neutral gray must project to itself under Brettel.
        val gray = 0xFF808080.toInt()
        val sim = ColorVision.simulateBrettel(gray, ColorVision.Deficiency.DEUTAN)
        val r = sim ushr 16 and 0xFF
        val g = sim ushr 8 and 0xFF
        val b = sim and 0xFF
        assertEquals(r, g)
        assertEquals(g, b)
    }

    @Test
    fun `validation rejects bad severity`() {
        try {
            ColorVision.simulatePixel(0xFF112233.toInt(), ColorVision.Deficiency.PROTAN, 1.5)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) { }
    }
}

class ContrastAuditTest {

    @Test
    fun `black on white is 21 to 1`() {
        assertEquals(21.0, ContrastAudit.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun `identical colors ratio 1`() {
        assertEquals(1.0, ContrastAudit.contrastRatio(0xFF334455.toInt(), 0xFF334455.toInt()), 1e-9)
    }

    @Test
    fun `transparent is treated as black`() {
        assertEquals(
            ContrastAudit.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            ContrastAudit.contrastRatio(0, 0xFFFFFFFF.toInt()),
            1e-9,
        )
    }

    @Test
    fun `conformance levels`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        assertEquals(ContrastAudit.Level.AAA, ContrastAudit.conformance(black, white))
        val midGray = 0xFF666666.toInt()
        assertNull(ContrastAudit.conformance(midGray, black, large = false))
        // Gray on black at 3:1-ish qualifies as AA for large text only.
        val level = ContrastAudit.conformance(midGray, black, large = true)
        assertTrue(level == null || level == ContrastAudit.Level.AA)
    }

    @Test
    fun `passes respects large-text thresholds`() {
        val a = 0xFF666666.toInt()
        val b = 0xFF999999.toInt()
        // Ratio ≈ 2.5 — fails both normal and large AA.
        assertTrue(!ContrastAudit.passes(a, b, ContrastAudit.Level.AA, large = true))
        val dark = 0xFF222222.toInt()
        // Dark on white ≈ 12:1 — passes everything.
        assertTrue(ContrastAudit.passes(dark, 0xFFFFFFFF.toInt(), ContrastAudit.Level.AAA))
    }

    @Test
    fun `auditPalette sorts worst first`() {
        val report = ContrastAudit.auditPalette(
            intArrayOf(
                0xFF101010.toInt(), // near black
                0xFF1A1A1A.toInt(), // near black (confusable!)
                0xFFFFFFFF.toInt(), // white
            ),
        )
        assertEquals(3, report.pairs.size)
        val worst = report.worstPair!!
        // The two near-blacks are the most confusable pair.
        assertTrue(worst.ratio < 1.5)
        assertTrue(report.pairs[0].ratio <= report.pairs[1].ratio)
        assertTrue(report.failing.isNotEmpty())
        assertEquals(1, report.failing.size) // only the near-black pair fails
    }

    @Test
    fun `suggestAccessible keeps hue direction`() {
        val lowContrast = 0xFF666666.toInt() // gray on white fails AA for normal text
        val suggestion = ContrastAudit.suggestAccessible(lowContrast, 0xFFFFFFFF.toInt())
        assertNotNull(suggestion)
        assertTrue(ContrastAudit.contrastRatio(suggestion!!, 0xFFFFFFFF.toInt()) >= 4.5)
        // Darkened toward black (the opposite extreme from white).
        val r = suggestion ushr 16 and 0xFF
        assertTrue("r=$r", r <= 0x66)
    }

    @Test
    fun `suggestAccessible returns input when already compliant`() {
        val good = 0xFF101010.toInt()
        val s = ContrastAudit.suggestAccessible(good, 0xFFFFFFFF.toInt())
        assertEquals(good, s)
    }

    @Test
    fun `relative luminance of pure primaries`() {
        // Rec.709 weights.
        assertEquals(0.2126, ContrastAudit.relativeLuminance(0xFFFF0000.toInt()), 1e-3)
        assertEquals(0.7152, ContrastAudit.relativeLuminance(0xFF00FF00.toInt()), 1e-3)
        assertEquals(0.0722, ContrastAudit.relativeLuminance(0xFF0000FF.toInt()), 1e-3)
    }
}

class ColorNamerTest {

    @Test
    fun `css list covers the standard set`() {
        assertEquals(148, ColorNamer.CSS_COLORS.size)
        assertTrue(ColorNamer.CSS_COLORS.any { it.name == "rebeccapurple" })
        assertTrue(ColorNamer.CSS_COLORS.any { it.name == "black" && it.argb == 0xFF000000.toInt() })
    }

    @Test
    fun `exactName finds matches including aliases`() {
        assertEquals(listOf("aqua", "cyan"), ColorNamer.exactName(0xFF00FFFF.toInt()).sorted())
        assertEquals(listOf("red"), ColorNamer.exactName(0xFFFF0000.toInt()))
        assertEquals(listOf("transparent"), ColorNamer.exactName(0x00000000))
        assertTrue(ColorNamer.exactName(0xFF123456.toInt()).isEmpty())
    }

    @Test
    fun `nearestName is exact for css colors`() {
        val m = ColorNamer.nearestName(0xFFFFD700.toInt())
        assertTrue(m.exact)
        assertEquals("gold", m.name)
    }

    @Test
    fun `nearestName finds a close css neighbor`() {
        // Slightly off-gold: nearest should still be gold-ish.
        val m = ColorNamer.nearestName(0xFFF0D060.toInt())
        assertTrue(m.distance > 0)
        assertTrue("distance=${m.distance}", m.distance < 110.0)
    }

    @Test
    fun `nearestName is deterministic and symmetric-ish`() {
        val c = 0xFF506070.toInt()
        assertEquals(ColorNamer.nearestName(c).name, ColorNamer.nearestName(c).name)
    }

    @Test
    fun `nameFrameColors sorts by pixel count`() {
        val frame = PixelFrame.of(
            3, 1,
            intArrayOf(0xFFFF0000.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
        )
        val names = ColorNamer.nameFrameColors(frame)
        assertEquals(2, names.size)
        assertEquals(2, names[0].pixelCount)
        assertEquals(1, names[1].pixelCount)
        assertEquals("red", names[0].name)
        assertEquals("blue", names[1].name)
    }
}

class ColorTemperatureTest {

    @Test
    fun `kelvinToRgb anchors`() {
        // Low kelvin is deep orange/red.
        val candle = ColorTemperature.kelvinToRgb(1800.0)
        assertTrue((candle ushr 16 and 0xFF) > 200)
        assertTrue((candle and 0xFF) < 60)
        // Mid-day is near-white.
        val noon = ColorTemperature.kelvinToRgb(6600.0)
        val r = noon ushr 16 and 0xFF
        val g = noon ushr 8 and 0xFF
        val b = noon and 0xFF
        assertTrue("noon=($r,$g,$b)", kotlin.math.abs(r - g) < 12 && kotlin.math.abs(g - b) < 12)
        // Very high kelvin is blue.
        val sky = ColorTemperature.kelvinToRgb(20000.0)
        assertTrue((sky and 0xFF) > (sky ushr 16 and 0xFF))
    }

    @Test
    fun `kelvin clamps outside the valid range`() {
        assertEquals(
            ColorTemperature.kelvinToRgb(500.0),
            ColorTemperature.kelvinToRgb(ColorTemperature.MIN_KELVIN),
        )
        assertEquals(
            ColorTemperature.kelvinToRgb(99999.0),
            ColorTemperature.kelvinToRgb(ColorTemperature.MAX_KELVIN),
        )
    }

    @Test
    fun `applyTemperature warms a scene`() {
        val frame = PixelFrame.of(2, 2, IntArray(4) { 0xFFB0B0B0.toInt() })
        val warm = ColorTemperature.applyTemperature(frame, 2500.0, 1.0)
        val r = warm.pixels[0] ushr 16 and 0xFF
        val b = warm.pixels[0] and 0xFF
        assertTrue("warm r=$r b=$b", r > b)
        // Mean-brightness equalization keeps overall level close.
        val cool = ColorTemperature.applyTemperature(frame, 15000.0, 1.0)
        val cb = cool.pixels[0] and 0xFF
        val cr = cool.pixels[0] ushr 16 and 0xFF
        assertTrue("cool r=$cr b=$cb", cb > cr)
    }

    @Test
    fun `applyTemperature strength zero is identity`() {
        val frame = PixelFrame.of(2, 1, intArrayOf(0xFFAABBCC.toInt(), 0xFF010203.toInt()))
        assertTrue(ColorTemperature.applyTemperature(frame, 3000.0, 0.0) === frame)
    }

    @Test
    fun `applyTemperature preserves transparency and alpha`() {
        val frame = PixelFrame.of(2, 1, intArrayOf(0x40FF8844.toInt(), 0))
        val out = ColorTemperature.applyTemperature(frame, 5000.0, 1.0)
        assertEquals(0x40, out.pixels[0] ushr 24)
        assertEquals(0, out.pixels[1])
    }

    @Test
    fun `autoWhiteBalance neutralizes a color cast`() {
        // Strong blue cast on a "should-be gray" field.
        val frame = PixelFrame.of(4, 4, IntArray(16) { 0xFF9090D0.toInt() })
        val balanced = ColorTemperature.autoWhiteBalance(frame, 1.0)
        val r = balanced.pixels[0] ushr 16 and 0xFF
        val g = balanced.pixels[0] ushr 8 and 0xFF
        val b = balanced.pixels[0] and 0xFF
        assertTrue("r=$r g=$g b=$b (cast removed)", kotlin.math.abs(r - b) < 4 && kotlin.math.abs(r - g) < 4)
    }

    @Test
    fun `autoWhiteBalance returns input on fully transparent frame`() {
        val blank = PixelFrame.blank(3, 3)
        assertTrue(ColorTemperature.autoWhiteBalance(blank) === blank)
    }

    @Test
    fun `presets construct`() {
        for (preset in ColorTemperature.Preset.entries) {
            val c = ColorTemperature.presetColor(preset)
            assertEquals(0xFF, c ushr 24)
        }
    }
}
