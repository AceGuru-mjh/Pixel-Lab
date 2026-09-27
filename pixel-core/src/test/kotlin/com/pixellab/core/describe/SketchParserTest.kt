package com.pixellab.core.describe

import com.pixellab.core.analysis.TestFrames
import com.pixellab.core.model.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [SketchParser] — the write half of the SKETCH round-trip.
 *
 * The happy-path fixtures are the *real* output of
 * [RegionReader.format]`(_, RegionFormat.SKETCH, _)` so the round-trip
 * contract (writer output must always parse back to the same pixels) is
 * exercised against the actual writer, not a hand-crafted lookalike.
 */
class SketchParserTest {

    private fun sketchOf(frame: PixelFrame): String {
        val read = RegionReader.read(frame, RegionRequest(0, 0, frame.width, frame.height))
        return RegionReader.format(read, RegionFormat.SKETCH, null)
    }

    // ── round-trips ─────────────────────────────────────────────────────

    @Test
    fun `round-trips region reader sketch output`() {
        val frame = TestFrames.fromRows(
            "rr.g.",
            "rg...",
        )
        val parsed = SketchParser.parse(sketchOf(frame))
        assertEquals(5, parsed.width)
        assertEquals(2, parsed.height)
        assertEquals(5, parsed.pointCount)
        // Row-major order, '.' cells skipped: (0,0) r, (1,0) r, (3,0) g,
        // (0,1) r, (1,1) g.
        assertEquals(
            listOf(0 to 0, 1 to 0, 3 to 0, 0 to 1, 1 to 1),
            parsed.pixels.map { it.x to it.y },
        )
        assertEquals(
            listOf(TestFrames.RED, TestFrames.RED, TestFrames.GREEN, TestFrames.RED, TestFrames.GREEN),
            parsed.argbs,
        )
    }

    @Test
    fun `round-trip preserves distinct colors including semi-transparent`() {
        val semi = 0x80FF00E4.toInt()
        val px = IntArray(4)
        px[0] = TestFrames.RED
        px[2] = semi
        val frame = PixelFrame.of(2, 2, px)
        val text = sketchOf(frame)
        // Semi-transparent colors serialize as 8-digit #AARRGGBB and must
        // parse back with the alpha preserved.
        assertTrue(text.contains("=#80ff00e4"))
        val parsed = SketchParser.parse(text)
        assertEquals(2, parsed.pointCount)
        assertEquals(TestFrames.RED, parsed.argbs[0])
        assertEquals(semi, parsed.argbs[1])
    }

    @Test
    fun `empty region placeholder parses to zero points`() {
        val blank = PixelFrame.blank(3, 2)
        val text = sketchOf(blank)
        // The writer emits ".=#00000000 (empty region)" for fully
        // transparent regions; the parser must accept it.
        assertTrue(text.contains(".=#00000000"))
        val parsed = SketchParser.parse(text)
        assertEquals(3, parsed.width)
        assertEquals(2, parsed.height)
        assertEquals(0, parsed.pointCount)
        assertTrue(parsed.pixels.isEmpty())
        assertTrue(parsed.argbs.isEmpty())
    }

    @Test
    fun `parses hand written sketch with legend prefix and bare hex`() {
        val parsed = SketchParser.parse(
            """
            legend: a=#FF0000
            b=00ff00
            ---
            ab..
            ..ab
            """.trimIndent(),
        )
        assertEquals(4, parsed.width)
        assertEquals(2, parsed.height)
        assertEquals(4, parsed.pointCount)
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 1, 3 to 1), parsed.pixels.map { it.x to it.y })
        assertEquals(
            listOf(TestFrames.RED, TestFrames.GREEN, TestFrames.RED, TestFrames.GREEN),
            parsed.argbs,
        )
    }

    // ── malformed input ─────────────────────────────────────────────────

    @Test
    fun `unknown grid char reports row and column`() {
        try {
            SketchParser.parse("a=#ff0000\n---\naXa")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("row 0, col 1"))
            assertTrue(expected.message!!, expected.message!!.contains("'X'"))
        }
    }

    @Test
    fun `ragged grid rows are rejected`() {
        try {
            SketchParser.parse("a=#ff0000\n---\naaa\naa")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("row 1 has 2 cells, expected 3"))
        }
    }

    @Test
    fun `bad legend color is rejected`() {
        try {
            SketchParser.parse("a=zzzzzz\n---\na")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("'zzzzzz'"))
        }
    }

    @Test
    fun `multi character legend key is rejected`() {
        try {
            SketchParser.parse("ab=#ff0000\n---\na")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("expected 'C=#hex'"))
        }
    }

    @Test
    fun `missing separator is rejected`() {
        try {
            SketchParser.parse("a=#ff0000\naaa")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("'---'"))
        }
    }

    @Test
    fun `oversized grid is rejected`() {
        val wide = "a".repeat(513)
        try {
            SketchParser.parse("a=#ff0000\n---\n$wide")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("513x1"))
        }
        val tall = (1..513).joinToString("\n") { "a" }
        try {
            SketchParser.parse("a=#ff0000\n---\n$tall")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("1x513"))
        }
    }

    @Test
    fun `too many points are rejected`() {
        // 512x512 fully opaque grid = 262144 points, beyond the 65536 cap.
        val rows = (1..512).joinToString("\n") { "a".repeat(512) }
        try {
            SketchParser.parse("a=#ff0000\n---\n$rows")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("65536"))
        }
    }

    @Test
    fun `duplicate legend chars are rejected`() {
        try {
            SketchParser.parse("a=#ff0000\na=#00ff00\n---\na")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("duplicate entry for 'a'"))
        }
    }

    @Test
    fun `legend mapping dot to opaque color is rejected`() {
        try {
            SketchParser.parse(".=#ff0000\n---\n.")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!, expected.message!!.contains("'.'"))
        }
    }
}
