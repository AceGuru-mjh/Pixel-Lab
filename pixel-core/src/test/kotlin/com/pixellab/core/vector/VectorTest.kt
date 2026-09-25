package com.pixellab.core.vector

import com.pixellab.core.model.Frame
import com.pixellab.core.model.Layer
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarchingSquaresTest {

    private fun frameOf(vararg rows: String): PixelFrame {
        val w = rows[0].length
        val h = rows.size
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                px[y * w + x] = if (rows[y][x] == '.') 0 else 0xFF102030.toInt()
            }
        }
        return PixelFrame.of(w, h, px)
    }

    @Test
    fun `solid rectangle yields one closed contour`() {
        val contours = MarchingSquares.traceOpacity(frameOf(
            "....",
            ".rr.",
            ".rr.",
            "....",
        ))
        assertEquals(1, contours.size)
        val c = contours[0]
        assertTrue(!c.isHole)
        // Simplified ring of a rectangle: 4 corners + closing point.
        assertEquals(5, c.size)
        // The ring spans the 2x2 block: (1,1) → (3,1) → (3,3) → (1,3) → close.
        assertEquals(1, c.x[0])
        assertEquals(1, c.y[0])
    }

    @Test
    fun `full-frame sprite ring hugs the canvas`() {
        val contours = MarchingSquares.traceOpacity(frameOf("rr", "rr"))
        assertEquals(1, contours.size)
        val c = contours[0]
        assertEquals(0, c.x.min())
        assertEquals(0, c.y.min())
        assertEquals(2, c.x.max())
        assertEquals(2, c.y.max())
    }

    @Test
    fun `ring with hole produces two contours with opposite winding`() {
        val contours = MarchingSquares.traceOpacity(frameOf(
            "rrrr",
            "r..r",
            "rrrr",
        ))
        assertEquals(2, contours.size)
        val holes = contours.filter { it.isHole }
        val outers = contours.filter { !it.isHole }
        assertEquals(1, holes.size)
        assertEquals(1, outers.size)
        // The hole's ring is inside the outer ring's bounding box.
        val hole = holes[0]
        val outer = outers[0]
        assertTrue(hole.x.min() > outer.x.min())
        assertTrue(hole.x.max() < outer.x.max())
    }

    @Test
    fun `disconnected shapes trace separately`() {
        val contours = MarchingSquares.traceOpacity(frameOf(
            "r.r",
            "r.r",
        ))
        assertEquals(2, contours.size)
    }

    @Test
    fun `transparent frame has no contours`() {
        assertEquals(0, MarchingSquares.traceOpacity(PixelFrame.blank(4, 4)).size)
    }

    @Test
    fun `unsimplified rings walk pixel edges`() {
        val contours = MarchingSquares.traceOpacity(
            frameOf("rr", "rr"),
            simplifyCollinear = false,
        )
        val c = contours[0]
        // A 2x2 block's edge ring has 8 unit steps + closing point.
        assertEquals(9, c.size)
        // Every step is unit-length along one axis.
        for (i in 0 until c.size - 1) {
            val dx = kotlin.math.abs(c.x[i + 1] - c.x[i])
            val dy = kotlin.math.abs(c.y[i + 1] - c.y[i])
            assertTrue("step $i = ($dx, $dy)", dx + dy == 1)
        }
    }

    @Test
    fun `traceColor isolates one color region`() {
        val px = IntArray(3 * 2)
        px[0] = 0xFFFF0000.toInt()
        px[1] = 0xFF00FF00.toInt()
        px[2] = 0xFFFF0000.toInt()
        px[3] = 0
        px[4] = 0
        px[5] = 0
        val frame = PixelFrame.of(3, 2, px)
        val redContours = MarchingSquares.traceColor(frame, 0xFFFF0000.toInt())
        // Two horizontally separated red pixels → two contours.
        assertEquals(2, redContours.size)
        val greenContours = MarchingSquares.traceColor(frame, 0xFF00FF00.toInt())
        assertEquals(1, greenContours.size)
    }
}

class SvgExporterTest {

    private fun frameOf(vararg rows: String, palette: Map<Char, Int> = mapOf('r' to 0xFFFF0000.toInt(), 'g' to 0xFF00FF00.toInt())): PixelFrame {
        val w = rows[0].length
        val h = rows.size
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = rows[y][x]
                px[y * w + x] = if (c == '.') 0 else palette[c]!!
            }
        }
        return PixelFrame.of(w, h, px)
    }

    @Test
    fun `runs mode produces well-formed svg`() {
        val svg = SvgExporter.export(frameOf("rr.", ".gg"))
        assertTrue(svg.startsWith("<?xml"))
        assertTrue(svg.contains("<svg xmlns=\"http://www.w3.org/2000/svg\""))
        assertTrue(svg.contains("width=\"3\" height=\"2\""))
        assertTrue(svg.contains("viewBox=\"0 0 3 2\""))
        assertTrue(svg.contains("shape-rendering=\"crispEdges\""))
        assertTrue(svg.endsWith("</svg>\n"))
        // One path per color: red (2 runs on row 0) and green (2 runs).
        assertEquals(2, Regex("<path ").findAll(svg).count())
        assertTrue(svg.contains("fill=\"#FF0000\""))
        assertTrue(svg.contains("fill=\"#00FF00\""))
        // Red row: M0 0h2v1h-2z.
        assertTrue(svg.contains("M0 0h2v1h-2z"))
        assertTrue(svg.contains("M1 1h2v1h-2z"))
    }

    @Test
    fun `palette comment embedded`() {
        val svg = SvgExporter.export(frameOf("rg", ".."))
        assertTrue(svg.contains("<!-- palette: #FF0000, #00FF00 -->"))
        val svgOff = SvgExporter.export(frameOf("rg", ".."), SvgExporter.Options(embedPaletteComment = false))
        assertTrue(!svgOff.contains("<!-- palette"))
    }

    @Test
    fun `semi transparency writes fill-opacity`() {
        val half = 0x80FF0000.toInt()
        val frame = PixelFrame.of(1, 1, intArrayOf(half))
        val svg = SvgExporter.export(frame)
        assertTrue(svg.contains("fill-opacity=\""))
    }

    @Test
    fun `title element when requested`() {
        val svg = SvgExporter.export(frameOf("r."), SvgExporter.Options(title = "hero <sprite>"))
        assertTrue(svg.contains("<title>hero &lt;sprite&gt;</title>"))
    }

    @Test
    fun `outline mode emits polygons`() {
        val svg = SvgExporter.export(
            frameOf("rr.", "rr."),
            SvgExporter.Options(mode = SvgExporter.ShapeMode.OUTLINE),
        )
        assertEquals(1, Regex("<path ").findAll(svg).count())
        // Contour path commands use M/L/Z (not run rectangles).
        assertTrue(svg.contains("M0 0L2 0L2 2L0 2L0 0Z"))
        assertTrue(!svg.contains("h2v1"))
    }

    @Test
    fun `animated export drives frames with smil`() {
        val project = projectOf(
            frameOf("r.") to 1,
            frameOf(".r") to 2,
        )
        val svg = SvgExporter.exportAnimated(project, frameDurationMs = 100)
        assertTrue(svg.contains("<g id=\"frame0\" opacity=\"1\">"))
        assertTrue(svg.contains("<g id=\"frame1\" opacity=\"0\">"))
        assertEquals(2, Regex("<animate ").findAll(svg).count())
        assertTrue(svg.contains("dur=\"200ms\""))
        assertTrue(svg.contains("repeatCount=\"indefinite\""))
        assertTrue(svg.contains("calcMode=\"discrete\""))
        assertTrue(svg.contains("animated, 2 frames @ 100ms"))
        // Discrete values: each frame owns its slot.
        assertTrue(svg.contains("values=\"1;0\""))
        assertTrue(svg.contains("values=\"0;1\""))
    }

    @Test
    fun `animated export can disable looping`() {
        val project = projectOf(frameOf("r.") to 1)
        val svg = SvgExporter.exportAnimated(project, frameDurationMs = 50, loop = false)
        assertTrue(svg.contains("repeatCount=\"1\""))
    }

    @Test
    fun `validation`() {
        try {
            SvgExporter.exportAnimated(projectOf(frameOf("r.") to 1), frameDurationMs = 0)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) { }
    }

    /** Builds a minimal single-layer, N-frame project. */
    private fun projectOf(vararg frames: Pair<PixelFrame, Int>): SpriteProject {
        val layer = Layer(id = 1, name = "base")
        val frameList = frames.map { (cel, id) -> Frame(id = id, cels = mapOf(1 to cel)) }
        return SpriteProject(
            id = "test-${frames.size}",
            name = "test",
            width = frames.first().first.width,
            height = frames.first().first.height,
            layers = listOf(layer),
            frames = frameList,
            activeLayerId = 1,
            activeFrameIndex = 0,
            palette = Palette("test", "test", intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), PaletteSource.CUSTOM),
        )
    }
}
