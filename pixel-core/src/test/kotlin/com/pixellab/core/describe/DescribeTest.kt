package com.pixellab.core.describe

import com.pixellab.core.analysis.TestFrames
import com.pixellab.core.model.PixelFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the describe package (agent vision): ASCII rendering,
 * region reads, census, structure, symmetry, diff and the prose describer.
 * All frames are tiny and hand-verifiable.
 */
class DescribeTest {

    private fun frame(vararg rows: String): PixelFrame = TestFrames.fromRows(*rows)

    // ── ColorCensus ──────────────────────────────────────────────────────

    @Test
    fun `census counts exact colors and transparency`() {
        val f = frame(
            "rr..",
            "rg..",
        )
        val c = ColorCensus.census(f)
        assertEquals(2, c.uniqueColors)
        assertEquals(4, c.transparentPixels)
        assertEquals(4, c.visiblePixels)
        assertEquals(8, c.total)
        assertEquals(2, c.tallies.size)
        assertEquals(TestFrames.RED, c.tallies[0].argb)
        assertEquals(3, c.tallies[0].count)
        assertEquals(1, c.tallies[1].count)
    }

    @Test
    fun `census names families`() {
        val f = frame(
            "rgy",
        )
        val c = ColorCensus.census(f)
        val families = c.families.map { it.family }.toSet()
        assertTrue(families.containsAll(listOf("red", "green", "yellow")))
        assertEquals(3, c.uniqueColors)
    }

    @Test
    fun `census family classification covers anchors`() {
        // Anchor colors: pure hue families, black/white/gray, brown (dark orange).
        assertEquals("red", ColorCensus.familyOf(0xFFFF0000.toInt()))
        assertEquals("orange", ColorCensus.familyOf(0xFFFFA500.toInt()))
        assertEquals("yellow", ColorCensus.familyOf(0xFFFFFF00.toInt()))
        assertEquals("green", ColorCensus.familyOf(0xFF00FF00.toInt()))
        assertEquals("cyan", ColorCensus.familyOf(0xFF00FFFF.toInt()))
        assertEquals("blue", ColorCensus.familyOf(0xFF0000FF.toInt()))
        assertEquals("purple", ColorCensus.familyOf(0xFF8000FF.toInt()))
        assertEquals("magenta", ColorCensus.familyOf(0xFFFF00FF.toInt()))
        assertEquals("black", ColorCensus.familyOf(0xFF000000.toInt()))
        assertEquals("white", ColorCensus.familyOf(0xFFFFFFFF.toInt()))
        assertEquals("gray", ColorCensus.familyOf(0xFF808080.toInt()))
        assertEquals("transparent", ColorCensus.familyOf(0x00000000))
        assertEquals("brown", ColorCensus.familyOf(0xFF8B4513.toInt()))
    }

    @Test
    fun `census hex formatting`() {
        assertEquals("#ff0000", ColorCensus.hex(0xFFFF0000.toInt()))
        assertEquals("#00000000", ColorCensus.hex(0x00000000))
        assertEquals("#80ff0000", ColorCensus.hex(0x80FF0000.toInt()))
    }
    @Test
    fun `census dominant ordering is count then argb`() {
        val f = frame(
            "ggg.",
            "rrr.",
            "b...",
        )
        val c = ColorCensus.census(f)
        // green and red tie at 3 → lower ARGB (green 0x00ff00 < red 0xff0000) first.
        assertEquals(TestFrames.GREEN, c.tallies[0].argb)
        assertEquals(TestFrames.RED, c.tallies[1].argb)
        assertEquals(1, c.tallies[2].count)
    }

    @Test
    fun `census of transparent frame`() {
        val f = frame("....")
        val c = ColorCensus.census(f)
        assertEquals(0, c.uniqueColors)
        assertEquals(0.0, c.occupancy, 1e-9)
        assertNull(c.dominant)
        assertTrue(c.families.isEmpty())
    }

    // ── AsciiRenderer ────────────────────────────────────────────────────

    @Test
    fun `letters render maps colors by frequency with legend`() {
        val f = frame(
            "rrg.",
            "rrg.",
        )
        val r = AsciiRenderer.render(f, AsciiStyle.LETTERS)
        assertEquals(listOf("AAB.", "AAB."), r.rows)
        assertEquals(2, r.legend.size)
        assertEquals('A', r.legend[0].char)
        assertEquals("#ff0000", r.legend[0].hex)
        assertEquals('B', r.legend[1].char)
        assertEquals("#00ff00", r.legend[1].hex)
    }

    @Test
    fun `letters render ranks by count not order`() {
        val f = frame(
            "g..",
            "gg.",
        )
        val r = AsciiRenderer.render(f, AsciiStyle.LETTERS)
        // green is the most frequent color → 'A'.
        assertEquals(listOf("A..", "AA."), r.rows)
    }

    @Test
    fun `shades render maps luminance and keeps transparent distinct`() {
        val f = frame(
            "kw.",
        )
        val r = AsciiRenderer.render(f, AsciiStyle.SHADES)
        assertEquals(1, r.rows.size)
        val row = r.rows[0]
        // transparent is a space; black maps to the darkest ramp slot '.';
        // white to the brightest '@'.
        assertEquals('.', row[0])
        assertEquals('@', row[1])
        assertEquals(' ', row[2])
        assertTrue(r.legend.isEmpty())
    }

    @Test
    fun `blocks render packs two rows into one`() {
        val f = frame(
            "kk",
            "..",
            "kk",
            "..",
        )
        val r = AsciiRenderer.render(f, AsciiStyle.BLOCKS, AsciiRenderOptions(asciiOnly = true))
        // Each output line pairs a filled row with an empty row: top-only
        // half → '^' in ascii-only mode.
        assertEquals(2, r.rows.size)
        assertEquals("^^", r.rows[0])
        assertEquals("^^", r.rows[1])
    }

    @Test
    fun `blocks render uses half blocks when unicode allowed`() {
        val f = frame(
            "kk",
            "..",
        )
        val r = AsciiRenderer.render(f, AsciiStyle.BLOCKS)
        assertEquals(listOf("▀▀"), r.rows)
    }

    @Test
    fun `wide frames are box sampled down`() {
        val f = TestFrames.solid(64, 8, TestFrames.RED)
        val r = AsciiRenderer.render(f, AsciiStyle.LETTERS, AsciiRenderOptions(maxWidth = 16))
        assertEquals(16, r.width)
        assertEquals(2, r.height)
        assertEquals(4, r.strideX)
        assertTrue(r.rows.all { it.length == 16 && it.all { c -> c == 'A' } })
    }

    @Test
    fun `transparent cells keep their char in letters mode`() {
        val f = frame(".r.")
        val r = AsciiRenderer.render(f, AsciiStyle.LETTERS, AsciiRenderOptions(transparentChar = ' '))
        assertEquals(" A ", r.rows[0])
    }

    @Test
    fun `more than 24 colors recycle alphabet deterministically`() {
        // 26 distinct colors on one row.
        val colors = (0 until 26).map { 0xFF000000.toInt() or (it * 0x080808) or 0x010101 }
        val px = IntArray(26) { colors[it] }
        val f = PixelFrame.of(26, 1, px)
        val r = AsciiRenderer.render(f, AsciiStyle.LETTERS)
        assertEquals(26, r.rows[0].length)
        // First 24 get A..X; the last two recycle to A, B.
        assertEquals('A', r.rows[0][24])
        assertEquals('B', r.rows[0][25])
        // Legend reports one entry per distinct color (26, recycled chars included).
        assertEquals(26, r.legend.size)
    }

    // ── RegionReader ─────────────────────────────────────────────────────

    @Test
    fun `hex read of a region`() {
        val f = frame(
            "rrgg",
            "bbww",
        )
        val read = RegionReader.read(f, RegionRequest(0, 0, 2, 2))
        val text = RegionReader.format(read, RegionFormat.HEX)
        assertEquals("#ff0000 #ff0000\n#0000ff #0000ff", text)
    }

    @Test
    fun `read clamps to frame and reports out-of-frame`() {
        val f = frame(
            "rr",
            "rr",
        )
        val read = RegionReader.read(f, RegionRequest(1, 1, 4, 4))
        assertEquals(1, read.clippedWidth)
        assertEquals(1, read.clippedHeight)
        assertEquals(15, read.outOfFrameCells)
        assertEquals("#ff0000", RegionReader.format(read, RegionFormat.HEX))
    }

    @Test
    fun `rle read compresses runs`() {
        val f = frame(
            "rrrg",
            "....",
        )
        val read = RegionReader.read(f, RegionRequest(0, 0, 4, 2))
        val text = RegionReader.format(read, RegionFormat.RLE)
        assertEquals("3:#ff0000,1:#00ff00\n4:#00000000", text)
    }

    @Test
    fun `sketch read round trips through legend and grid`() {
        val f = frame(
            "rr.",
            ".g.",
        )
        val read = RegionReader.read(f, RegionRequest(0, 0, 3, 2))
        val text = RegionReader.format(read, RegionFormat.SKETCH)
        val lines = text.split('\n')
        assertTrue(lines[0].startsWith("A="))
        assertTrue(lines[0].contains("#ff0000"))
        assertTrue(lines[1].startsWith("B="))
        assertTrue(text.contains("---"))
        assertEquals("AA.", lines[3])
        assertEquals(".B.", lines[4])
    }

    @Test
    fun `read cap rejects huge regions`() {
        val f = TestFrames.solid(300, 300, TestFrames.RED)
        try {
            RegionReader.read(f, RegionRequest(0, 0, 300, 300))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("cap"))
        }
    }

    @Test
    fun `palette index read maps and marks orphans`() {
        val f = frame(
            "ry.",
        )
        val palette = com.pixellab.core.model.Palette(
            "test",
            "Test",
            intArrayOf(TestFrames.RED, TestFrames.GREEN),
            com.pixellab.core.model.PaletteSource.BUILTIN,
        )
        val read = RegionReader.read(f, RegionRequest(0, 0, 3, 1))
        val text = RegionReader.format(read, RegionFormat.PALETTE_INDEX, palette)
        // yellow (#ffff00) is far from both entries → -1.
        assertEquals("0 -1 .", text)
    }

    // ── StructureAnalyzer ────────────────────────────────────────────────

    @Test
    fun `structure finds blobs with dominant colors and shapes`() {
        val f = frame(
            "rrr..",
            "rrr..",
            ".....",
            "..gg.",
        )
        val report = StructureAnalyzer.analyze(f)
        assertEquals(2, report.blobs.size)
        val largest = report.largest!!
        assertEquals(6, largest.area)
        assertEquals("red", largest.dominantName)
        assertEquals(0, largest.boxX)
        assertEquals(0, largest.boxY)
        assertEquals(3, largest.boxW)
        assertEquals(2, largest.boxH)
        assertEquals("solid block", StructureAnalyzer.shapeVerdict(largest))
    }

    @Test
    fun `isolated pixels are counted separately`() {
        val f = frame(
            "r.g",
        )
        val report = StructureAnalyzer.analyze(f)
        assertEquals(2, report.blobs.size)
        assertEquals(2, report.isolatedPixels)
    }

    @Test
    fun `content box spans all visible pixels`() {
        val f = frame(
            ".....",
            ".rr..",
            "...g.",
        )
        val box = StructureAnalyzer.contentBox(f)!!
        assertEquals(1, box[0])
        assertEquals(1, box[1])
        assertEquals(3, box[2])
        assertEquals(2, box[3])
    }

    @Test
    fun `fingerprint quantizes macro cells`() {
        val f = TestFrames.solid(4, 4, TestFrames.RED)
        val fp = StructureAnalyzer.fingerprint(f)
        assertEquals(4, fp.cols)
        assertEquals(4, fp.rows)
        assertTrue(fp.levels.all { it == 4 })
        assertEquals("████", fp.rowsAsText[0])
        // Fully transparent frame fingerprints to all-empty.
        val empty = frame("....")
        val fp2 = StructureAnalyzer.fingerprint(empty)
        assertTrue(fp2.levels.all { it == 0 })
    }

    // ── SymmetryAnalyzer ─────────────────────────────────────────────────

    @Test
    fun `horizontal mirror detection`() {
        val f = frame(
            "r.g",
            "r.g",
        )
        val report = SymmetryAnalyzer.analyze(f)
        // 'r.g' mirrored left-right is 'g.r' ≠ 'r.g' → HORIZONTAL fails.
        assertFalse(report.verdicts.first { it.kind == SymmetryKind.HORIZONTAL }.holds)
        // Top-bottom pairs match exactly → VERTICAL holds.
        assertTrue(report.verdicts.first { it.kind == SymmetryKind.VERTICAL }.holds)
    }

    @Test
    fun `rot180 detection`() {
        val f = frame(
            "r.",
            ".r",
        )
        val report = SymmetryAnalyzer.analyze(f)
        assertTrue(report.verdicts.first { it.kind == SymmetryKind.ROT180 }.holds)
        assertFalse(report.verdicts.first { it.kind == SymmetryKind.HORIZONTAL }.holds)
    }

    @Test
    fun `diagonal symmetry on square frames`() {
        val f = frame(
            "rg",
            "gb",
        )
        val report = SymmetryAnalyzer.analyze(f)
        assertTrue(report.verdicts.first { it.kind == SymmetryKind.DIAGONAL }.holds)
        assertFalse(report.verdicts.first { it.kind == SymmetryKind.ANTI_DIAGONAL }.holds)
    }

    @Test
    fun `tolerance forgives small channel differences`() {
        val nearRed = 0xFFFE0000.toInt()
        val f = PixelFrame.of(
            2, 1,
            intArrayOf(TestFrames.RED, nearRed),
        )
        assertFalse(SymmetryAnalyzer.probe(f, SymmetryKind.HORIZONTAL).holds)
        assertTrue(SymmetryAnalyzer.probe(f, SymmetryKind.HORIZONTAL, tolerance = 1).holds)
    }

    @Test
    fun `transparent frame is trivially symmetric`() {
        val f = frame("....", "....")
        val report = SymmetryAnalyzer.analyze(f)
        // Non-square frame: diagonals are not probed; the other three hold.
        assertEquals(3, report.verdicts.size)
        assertTrue(report.holding.containsAll(listOf(SymmetryKind.HORIZONTAL, SymmetryKind.VERTICAL, SymmetryKind.ROT180)))
    }

    @Test
    fun `visibility mismatches break symmetry`() {
        val f = frame(
            "r.",
            "..",
        )
        val report = SymmetryAnalyzer.analyze(f)
        assertFalse(report.verdicts.first { it.kind == SymmetryKind.ROT180 }.holds)
    }

    // ── FrameDiff ────────────────────────────────────────────────────────

    @Test
    fun `diff classifies added removed changed`() {
        val before = frame(
            "rr..",
            "gg..",
        )
        val after = frame(
            "rrb.",
            "g...",
        )
        val d = FrameDiff.diff(before, after)
        // (2,0) .→b = added; (1,1) g→. = removed; nothing recolored.
        assertEquals(1, d.added)
        assertEquals(1, d.removed)
        assertEquals(0, d.changed)
        assertEquals(6, d.unchanged)
        assertEquals(2, d.totalChanges)
        assertFalse(d.identical)
        assertNotNull(d.addedBox)
        assertEquals(2, d.addedBox!!.x)
        assertEquals(1, d.addedBox!!.w)
    }

    @Test
    fun `diff identical frames`() {
        val f = frame("rr", "gg")
        val d = FrameDiff.diff(f, f)
        assertTrue(d.identical)
        assertEquals(0, d.totalChanges)
        assertNull(d.addedBox)
        assertNull(d.removedBox)
        assertNull(d.changedBox)
        assertEquals("identical — no pixel changed", FrameDiff.summarize(d))
    }

    @Test
    fun `diff records color transitions`() {
        val before = frame("rr")
        val after = frame("gg")
        val d = FrameDiff.diff(before, after)
        assertEquals(2, d.changed)
        assertEquals(1, d.transitions.size)
        assertEquals(TestFrames.RED, d.transitions[0].from)
        assertEquals(TestFrames.GREEN, d.transitions[0].to)
        assertEquals(2, d.transitions[0].count)
    }

    @Test
    fun `diff handles transparent padding growth`() {
        val small = frame("rr")
        val big = frame(
            "rr.",
            "...",
        )
        val grow = FrameDiff.diff(small, big)
        assertTrue(grow.identical) // same visible content, extra space transparent
        val shrink = FrameDiff.diff(big, small)
        assertTrue(shrink.identical)
    }

    @Test
    fun `diff shrink with visible pixels outside counts removal`() {
        val big = frame(
            "rr",
            "rr",
        )
        val small = frame("rr")
        val d = FrameDiff.diff(big, small)
        assertEquals(2, d.removed)
        assertEquals(0, d.added)
    }

    // ── FrameDescriber ───────────────────────────────────────────────────

    @Test
    fun `describe composes headline colors structure symmetry`() {
        val f = frame(
            "rrr.",
            "r.r.",
            "rrr.",
        )
        val d = FrameDescriber.describe(f)
        assertTrue(d.headline.startsWith("4x3 canvas, 66% occupied, 1 color."))
        assertTrue(d.text.contains("Colors:"))
        assertTrue(d.text.contains("Structure: 1 region(s)"))
        assertTrue(d.text.contains("Symmetry:"))
        assertTrue(d.text.contains("Layout fingerprint"))
        assertEquals(8, d.structure!!.blobs[0].area)
        assertEquals(1, d.structure!!.blobs[0].holes)
    }

    @Test
    fun `describe empty canvas`() {
        val f = frame("....", "....")
        val d = FrameDescriber.describe(f)
        assertTrue(d.headline.contains("0% occupied"))
        assertTrue(d.text.contains("fully transparent"))
        assertTrue(d.text.contains("no visible content"))
    }

    @Test
    fun `describe sections can be toggled`() {
        val f = frame("rr", "gg")
        val d = FrameDescriber.describe(
            f,
            DescribeOptions(
                includeColors = false,
                includeSymmetry = false,
                includeFingerprint = false,
                includeStructure = false,
            ),
        )
        assertEquals(1, d.text.lines().size) // headline only
        assertNull(d.census)
        assertNull(d.structure)
        assertNull(d.symmetry)
    }

    @Test
    fun `describe pixel sentences`() {
        val f = frame("r.")
        assertTrue(FrameDescriber.describePixel(f, 0, 0).contains("#ff0000"))
        assertTrue(FrameDescriber.describePixel(f, 1, 0).contains("transparent"))
        assertTrue(FrameDescriber.describePixel(f, 5, 5).contains("outside"))
    }

    @Test
    fun `ring blob gets a ring verdict`() {
        val f = frame(
            "rrr",
            "r.r",
            "rrr",
        )
        val report = StructureAnalyzer.analyze(f)
        assertEquals("ring/outline with 1 hole(s)", StructureAnalyzer.shapeVerdict(report.blobs[0]))
    }
}
