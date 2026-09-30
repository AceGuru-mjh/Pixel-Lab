package com.pixellab.ui

import com.pixellab.core.model.BuiltInPalettes
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM unit tests for the editor suite's pure logic layer: shared canvas
 * state, the editor session contract, symmetry mirroring, color math, the
 * shortcut map and the timeline frame operations. No Compose rendering is
 * exercised — everything here runs on the plain JVM.
 */
class EditorSuiteLogicTest {

    // ---- CanvasState -------------------------------------------------------

    @Test
    fun zoomClampsIntoInteractiveRange() {
        val state = CanvasState()
        state.zoom = 0.1f
        assertEquals(1f, state.zoom)
        state.zoom = 999f
        assertEquals(64f, state.zoom)
    }

    @Test
    fun zoomSnapsToPowerOfTwoRungs() {
        val state = CanvasState()
        state.zoom = 7.9f
        assertEquals(8f, state.zoom)
        state.zoom = 30f
        assertEquals(32f, state.zoom)
    }

    @Test
    fun brushSizeCoercesIntoOneToEight() {
        val state = CanvasState()
        state.brushSize = -3
        assertEquals(1, state.brushSize)
        state.brushSize = 99
        assertEquals(8, state.brushSize)
    }

    @Test
    fun screenToPixelMapsCellsAndRejectsOutside() {
        val state = CanvasState(initialZoom = 2f)
        state.canvasWidth = 4
        state.canvasHeight = 4
        state.panX = 10f
        state.panY = 20f
        val cell = CanvasState.BaseCellSize * state.zoom
        assertEquals(PixelPoint(0, 0), state.screenToPixel(10f, 20f, cell))
        assertEquals(PixelPoint(1, 2), state.screenToPixel(10f + 1.5f * cell, 20f + 2.5f * cell, cell))
        assertNull(state.screenToPixel(-5f, 0f, cell))
        assertNull(state.screenToPixel(0f, 0f, 0f))
    }

    @Test
    fun fitToViewCentersFrameAndKeepsItInside() {
        val state = CanvasState(initialZoom = 64f)
        state.canvasWidth = 32
        state.canvasHeight = 32
        state.viewportWidth = 200
        state.viewportHeight = 100
        assertTrue(state.fitToView())
        val cell = CanvasState.BaseCellSize * state.zoom
        val frameW = 32 * cell
        val frameH = 32 * cell
        assertTrue(frameW <= 200f, "frame width $frameW must fit viewport 200")
        assertTrue(frameH <= 100f, "frame height $frameH must fit viewport 100")
        assertTrue(state.panX >= 0f && state.panX + frameW <= 200f)
        assertTrue(state.panY >= 0f && state.panY + frameH <= 100f)
    }

    @Test
    fun fitToViewIsNoOpWithoutViewport() {
        val state = CanvasState()
        state.canvasWidth = 32
        state.canvasHeight = 32
        assertFalse(state.fitToView())
    }

    // ---- EditorSession -----------------------------------------------------

    private fun newProject() = SpriteFactory.create("ui", 8, 8, BuiltInPalettes.PICO8)

    @Test
    fun editRecordsHistoryAndNoOpsAreFree() {
        val session = EditorSession(newProject())
        var notifications = 0
        session.setOnProjectChanged { notifications++ }

        val painted = session.edit("Paint") { p ->
            p.withActiveCel((p.activeCel() ?: PixelFrame.blank(p.width, p.height)).withPixel(0, 0, 0xFF112233.toInt()))
        }
        assertEquals(1, notifications)
        assertEquals(1, session.undoDepth())
        assertEquals(0xFF112233.toInt(), painted[0, 0])

        // Same-value rewrite is a no-op: no entry, no notification.
        session.edit("Paint again") { painted }
        assertEquals(1, notifications)
        assertEquals(1, session.undoDepth())
    }

    @Test
    fun undoRedoRoundTrip() {
        val session = EditorSession(newProject())
        session.edit("Paint") { p ->
            p.withActiveCel((p.activeCel() ?: PixelFrame.blank(p.width, p.height)).withPixel(1, 1, 0xFF445566.toInt()))
        }
        assertTrue(session.canUndo)
        val undone = session.undo()
        assertTrue(undone != null)
        assertEquals(0, undone!![1, 1])
        assertTrue(session.canRedo)
        val redone = session.redo()
        assertEquals(0xFF445566.toInt(), redone!![1, 1])
    }

    @Test
    fun coalesceKeyMergesStrokeDabs() {
        val session = EditorSession(newProject())
        session.edit("Paint", coalesceKey = "stroke-1") { p ->
            p.withActiveCel((p.activeCel() ?: PixelFrame.blank(p.width, p.height)).withPixel(0, 0, 0xFF000001.toInt()))
        }
        session.edit("Paint", coalesceKey = "stroke-1") { p ->
            p.withActiveCel((p.activeCel() ?: PixelFrame.blank(p.width, p.height)).withPixel(1, 0, 0xFF000002.toInt()))
        }
        session.edit("Paint", coalesceKey = "stroke-2") { p ->
            p.withActiveCel((p.activeCel() ?: PixelFrame.blank(p.width, p.height)).withPixel(2, 0, 0xFF000003.toInt()))
        }
        // Two distinct keys -> two entries.
        assertEquals(2, session.undoDepth())
    }

    @Test
    fun transactionCommitsAsSingleEntry() {
        val session = EditorSession(newProject())
        var notifications = 0
        session.setOnProjectChanged { notifications++ }
        val tx = session.beginTransaction("Multi")
        tx.edit("a") { p -> p.withFps(12) }
        tx.edit("b") { p -> p.withName("renamed") }
        tx.commit()
        assertEquals(1, session.undoDepth())
        assertEquals(1, notifications)
        assertEquals("renamed", session.current().name)
    }

    @Test
    fun transactionCancelRestoresStart() {
        val session = EditorSession(newProject())
        val start = session.current()
        val tx = session.beginTransaction("Multi")
        tx.edit("a") { p -> p.withName("draft") }
        tx.cancel()
        assertEquals(start, session.current())
        assertEquals(0, session.undoDepth())
    }

    @Test
    fun markSavedTracksDirtyState() {
        val session = EditorSession(newProject())
        assertFalse(session.isModified)
        session.edit("Paint") { p ->
            p.withActiveCel((p.activeCel() ?: PixelFrame.blank(p.width, p.height)).withPixel(0, 0, 0xFF0A0B0C.toInt()))
        }
        assertTrue(session.isModified)
        session.markSaved()
        assertFalse(session.isModified)
        session.undo()
        // Undoing below the save point counts as a modification again.
        assertTrue(session.isModified)
    }

    // ---- Symmetry mirroring --------------------------------------------------

    @Test
    fun mirrorStrokeHorizontalMirrorsY() {
        val mirrored = mirrorStroke(listOf(PixelPoint(2, 1)), 8, 8, CanvasSymmetry.HORIZONTAL)
        assertEquals(listOf(PixelPoint(2, 1), PixelPoint(2, 6)), mirrored)
    }

    @Test
    fun mirrorStrokeVerticalMirrorsX() {
        val mirrored = mirrorStroke(listOf(PixelPoint(1, 3)), 8, 8, CanvasSymmetry.VERTICAL)
        assertEquals(listOf(PixelPoint(1, 3), PixelPoint(6, 3)), mirrored)
    }

    @Test
    fun mirrorStrokeFourWayProducesAllQuadrants() {
        val mirrored = mirrorStroke(listOf(PixelPoint(1, 2)), 8, 8, CanvasSymmetry.FOUR_WAY).toSet()
        assertEquals(
            setOf(PixelPoint(1, 2), PixelPoint(6, 2), PixelPoint(1, 5), PixelPoint(6, 5)),
            mirrored,
        )
    }

    @Test
    fun mirrorStrokeOffAndEmptyArePassthrough() {
        val points = listOf(PixelPoint(0, 0))
        assertEquals(points, mirrorStroke(points, 8, 8, CanvasSymmetry.OFF))
        assertTrue(mirrorStroke(emptyList(), 8, 8, CanvasSymmetry.FOUR_WAY).isEmpty())
    }

    // ---- Color math ----------------------------------------------------------

    @Test
    fun hsvRoundTripWithinOneChannel() {
        for (argb in intArrayOf(0xFFFF0000.toInt(), 0xFF00FF88.toInt(), 0xFF123456.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
            val hsv = ColorMath.argbToHsv(argb)
            val back = ColorMath.hsvToArgb(hsv[0], hsv[1], hsv[2], (argb ushr 24) and 0xFF)
            val dr = Math.abs(((back shr 16) and 0xFF) - ((argb shr 16) and 0xFF))
            val dg = Math.abs(((back shr 8) and 0xFF) - ((argb shr 8) and 0xFF))
            val db = Math.abs((back and 0xFF) - (argb and 0xFF))
            assertTrue(dr <= 1 && dg <= 1 && db <= 1, "round trip drifted for ${argb.toLong() and 0xFFFFFFFFL}")
        }
    }

    @Test
    fun hexParseAndFormat() {
        assertEquals(0xFF336699.toInt(), ColorMath.parseHex("#336699"))
        assertEquals(0xFF336699.toInt(), ColorMath.parseHex("336699"))
        assertEquals(0x80336699.toInt(), ColorMath.parseHex("#80336699"))
        assertNull(ColorMath.parseHex("#12345"))
        assertNull(ColorMath.parseHex("zzzzzz"))
        assertEquals("#336699", ColorMath.toHex(0xFF336699.toInt()))
        assertEquals("#80336699", ColorMath.toHex(0x80336699.toInt(), withAlpha = true))
    }

    // ---- Shortcut map ----------------------------------------------------------

    @Test
    fun defaultBindingsResolve() {
        val map = ShortcutMap()
        assertEquals(ShortcutAction.TOOL_PENCIL, map.find("b"))
        assertEquals(ShortcutAction.UNDO, map.find("z", ctrl = true))
        assertEquals(ShortcutAction.REDO, map.find("z", ctrl = true, shift = true))
        assertEquals(ShortcutAction.SAVE, map.find("s", ctrl = true))
        assertEquals(ShortcutAction.DELETE, map.find("Delete"))
        assertEquals(ShortcutAction.SELECT_ALL, map.find("a", ctrl = true))
        assertEquals(ShortcutAction.SELECT_PALETTE_SLOT, map.find("5"))
    }

    @Test
    fun customBindingsShadowDefaults() {
        val map = ShortcutMap()
        map.bind(Shortcut("b"), ShortcutAction.TOOL_ERASER)
        assertEquals(ShortcutAction.TOOL_ERASER, map.find("B"))
        assertTrue(map.unbind(Shortcut("b")))
        assertEquals(ShortcutAction.TOOL_PENCIL, map.find("B"))
    }

    @Test
    fun zoomKeysResolveWithAndWithoutAliases() {
        val map = ShortcutMap()
        assertEquals(ShortcutAction.ZOOM_IN, map.find("+"))
        assertEquals(ShortcutAction.ZOOM_IN, map.find("="))
        assertEquals(ShortcutAction.ZOOM_IN, map.find("Equals"))
        assertEquals(ShortcutAction.ZOOM_OUT, map.find("-"))
        assertEquals(ShortcutAction.ZOOM_OUT, map.find("Minus"))
    }

    // ---- Timeline frame operations ---------------------------------------------

    @Test
    fun addFrameInsertsAfterIndex() {
        var project = newProject()
        project = addFrame(project, 0)
        assertEquals(2, project.frameCount)
        project = duplicateFrame(project, 1)
        assertEquals(3, project.frameCount)
    }

    @Test
    fun deleteFrameKeepsAtLeastOne() {
        val project = newProject()
        assertEquals(project, deleteFrame(project, 0))
    }

    @Test
    fun deleteFrameClampsTags() {
        var project = newProject()
        project = addFrame(project, 0)
        project = project.withTags(
            listOf(
                com.pixellab.core.model.AnimationTag("walk", 0, 1),
            ),
        )
        project = deleteFrame(project, 1)
        assertEquals(1, project.frameCount)
        // The remaining tag span clamps into the shrunken index space.
        assertTrue(project.tags.all { it.endFrame < project.frameCount })
    }

    @Test
    fun moveFrameReordersAndFollowsActive() {
        var project = newProject()
        project = addFrame(project, 0)
        project = addFrame(project, 1)
        project = project.withActiveFrameIndex(0)
        project = moveFrame(project, 0, 2)
        assertEquals(3, project.frameCount)
        assertEquals(2, project.activeFrameIndex)
    }

    @Test
    fun setFrameDurationValidates() {
        var project = newProject()
        project = setFrameDuration(project, 0, 250)
        assertEquals(250, project.frames[0].durationMs)
        project = setFrameDuration(project, 0, null)
        assertNull(project.frames[0].durationMs)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            setFrameDuration(project, 0, 0)
        }
    }
}
