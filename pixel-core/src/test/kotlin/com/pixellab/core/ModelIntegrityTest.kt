package com.pixellab.core

import com.pixellab.core.engine.DrawOps
import com.pixellab.core.history.CommandHistory
import com.pixellab.core.history.EditorCommand
import com.pixellab.core.model.Frame
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.core.store.ProjectStore
import com.pixellab.core.tools.BrushEngine
import com.pixellab.core.tools.BrushSpec
import com.pixellab.core.tools.GeometryShapes
import com.pixellab.core.transform.Resample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pixellab.core.model.PixelPoint

/**
 * Round-2 audit regression suite (5-b): the shape-budget family, the
 * with\* copy-family structural checks, defensive copies at the model
 * boundary, integer-overflow guards and the history/store integrity
 * contracts.
 */
class ModelIntegrityTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun project(): SpriteProject =
        SpriteFactory.create("t", 16, 16, BuiltInPalettes.PICO8)

    private class Cmd(override val label: String, override val coalesceKey: String?) : EditorCommand

    // ---- shape budgets -------------------------------------------------------

    @Test
    fun `giant filled rect is rejected by the point budget, not by an OOM`() {
        try {
            DrawOps.rect(0, 0, 100_000, 100_000, true)
            throw AssertionError("100000x100000 filled rect must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("budget"))
        }
    }

    @Test
    fun `giant outlined rect is rejected by the perimeter budget`() {
        // 2e9 x 1: the old outline path allocated 2e9 PixelPoints.
        try {
            DrawOps.rect(0, 0, 2_000_000_000, 1, false)
            throw AssertionError("2e9-wide outline must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("budget"))
        }
    }

    @Test
    fun `roundedRect perimeter is budgeted`() {
        try {
            GeometryShapes.roundedRect(0, 0, 2_000_000_000, 1, 0)
            throw AssertionError("2e9-wide roundedRect must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("budget"))
        }
    }

    @Test
    fun `normal rects still draw`() {
        assertEquals(12, DrawOps.rect(2, 2, 3, 4, true).size)
        // 2*(3+4) - 4 corners = 10 distinct outline points.
        assertEquals(10, DrawOps.rect(2, 2, 3, 4, false).size)
    }

    // ---- with* structural validation -----------------------------------------

    @Test
    fun `withCel rejects a cel whose size differs from the project`() {
        val p = project()
        val wrong = PixelFrame.blank(8, 8)
        try {
            p.withCel(p.activeLayerId, 0, wrong)
            throw AssertionError("mismatched cel must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not match"))
        }
    }

    @Test
    fun `withFrames rejects frames carrying mismatched cels`() {
        val p = project()
        val wrong = p.frames[0].withCel(p.activeLayerId, PixelFrame.blank(8, 8))
        try {
            p.withFrames(listOf(wrong))
            throw AssertionError("mismatched cel must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("does not match"))
        }
    }

    // ---- defensive copies at the model boundary --------------------------------

    @Test
    fun `PixelFrame of copies the input array`() {
        val shared = IntArray(4) { 0xFF000000.toInt() }
        val frame = PixelFrame.of(2, 2, shared)
        shared[0] = 0xFF00FF00.toInt()
        assertEquals(0xFF000000.toInt(), frame.pixels[0])
    }

    @Test
    fun `Palette constructor copies the color array`() {
        val shared = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val palette = Palette("t", "t", shared, PaletteSource.CUSTOM)
        shared[0] = 0xFF0000FF.toInt()
        assertEquals(0xFF000000.toInt(), palette.colors[0])
    }

    // ---- integer-overflow guards -------------------------------------------------

    @Test
    fun `Resample nearest rejects wrapped targets`() {
        val frame = PixelFrame.blank(4, 4)
        try {
            Resample.nearest(frame, 65536, 65536)
            throw AssertionError("65536x65536 target must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("268M"))
        }
    }

    @Test
    fun `BrushEngine tolerates a centerline touching Int MAX_VALUE`() {
        // deStep's +1 probes used to overflow at exactly Int.MAX_VALUE and
        // rejected the WHOLE stroke ("coordinates must be non-negative").
        val centers = listOf(
            PixelPoint(Int.MAX_VALUE - 2, 3),
            PixelPoint(Int.MAX_VALUE, 3),
        )
        val engine = BrushEngine()
        val stroke = engine.stroke(centers, BrushSpec(size = 1), pixelPerfect = true)
        // The boundary point itself cannot stamp (rect x+w wraps), but its
        // in-bounds neighbors must survive.
        assertTrue(stroke.contains(PixelPoint(Int.MAX_VALUE - 2, 3)))
        assertTrue(stroke.contains(PixelPoint(Int.MAX_VALUE - 1, 3)))
    }

    // ---- history coalesce continuity ----------------------------------------------

    @Test
    fun `diverged same-key record pushes a new entry instead of stitching`() {
        val history = CommandHistory()
        val a = project()
        val b = a.withName("b")
        val c = a.withName("c")
        val d = a.withName("d")

        history.record(Cmd("stroke", "brush#1"), a, b)
        // Divergent: `before` (c) does NOT continue from the top's after (b).
        history.record(Cmd("stroke", "brush#1"), c, d)

        // Two entries: undo from d restores c (the divergent before), then a.
        assertEquals(d, history.undo(d))
        assertEquals(c, history.undo(c))
        assertEquals(a, history.undo(a))
    }

    @Test
    fun `continuous same-key record still coalesces into one entry`() {
        val history = CommandHistory()
        val a = project()
        val b = a.withName("b")
        val c = b.withName("c")

        history.record(Cmd("stroke", "brush#1"), a, b)
        history.record(Cmd("stroke", "brush#1"), b, c)

        // One entry: undo from c jumps straight back to a.
        assertEquals(a, history.undo(c))
        assertEquals(null, history.undo(a))
    }

    // ---- store sanitization collision ----------------------------------------------

    @Test
    fun `project ids colliding after sanitization refuse to clobber each other`() {
        val store = ProjectStore(folder.newFolder())
        val first = SpriteFactory.create("first", 8, 8, BuiltInPalettes.PICO8, id = "a:b")
        store.save(first)
        assertTrue(store.load("a:b").name == "first")

        // "a;b" sanitizes to the SAME directory "a_b"; the sweep would have
        // deleted first's document. Now the save fails loudly instead.
        val second = SpriteFactory.create("second", 8, 8, BuiltInPalettes.PICO8, id = "a;b")
        try {
            store.save(second)
            throw AssertionError("colliding id must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("collides"))
        }
        // The original document survives untouched.
        assertTrue(store.load("a:b").name == "first")
    }

    @Test
    fun `same project renamed still sweeps its old document`() {
        val store = ProjectStore(folder.newFolder())
        var p = SpriteFactory.create("old-name", 8, 8, BuiltInPalettes.PICO8)
        store.save(p)
        p = p.withName("new-name")
        store.save(p)

        val summaries = store.list()
        assertEquals(1, summaries.size)
        assertEquals("new-name", summaries[0].name)
    }

    // ---- Frame construction sanity ---------------------------------------------------

    @Test
    fun `Frame withCel keeps the map copy semantics`() {
        val f = Frame(id = 0, cels = emptyMap())
        val cel = PixelFrame.blank(4, 4)
        val next = f.withCel(7, cel)
        assertTrue(f.cels.isEmpty())
        assertEquals(cel, next.cels[7])
    }
}
