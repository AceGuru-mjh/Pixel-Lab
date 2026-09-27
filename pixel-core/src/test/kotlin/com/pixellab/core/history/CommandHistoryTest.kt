package com.pixellab.core.history

import com.pixellab.core.analysis.TestFrames
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.palette.BuiltInPalettes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the save-point semantics of [CommandHistory] — the
 * state-anchor rework.
 *
 * The historical position counter answered `isModified == false` after a
 * diverging undo-then-record pair (same depth, different branch), so hosts
 * skipped their "unsaved changes" prompt and dropped work on exit. The
 * anchor compares the CURRENT document against the SAVED snapshot instead.
 */
class CommandHistoryTest {

    private object Draw : EditorCommand {
        override val label = "draw"
    }

    private fun newProject() = SpriteFactory.create("doc", 8, 8, BuiltInPalettes.PICO8)

    private fun painted(base: com.pixellab.core.model.SpriteProject, x: Int): com.pixellab.core.model.SpriteProject =
        base.withActiveCel(
            (base.activeCel() ?: com.pixellab.core.model.PixelFrame.blank(8, 8))
                .withPixels(listOf(PixelPoint(x, 0)), TestFrames.RED),
        )

    @Test
    fun `undo then record on a diverging branch stays dirty`() {
        val history = CommandHistory()
        var p = newProject()
        val p1 = painted(p, 1); history.record(Draw, p, p1); p = p1
        val p2 = painted(p, 2); history.record(Draw, p, p2); p = p2
        history.markSaved()
        assertFalse(history.isModified)

        // User undoes to p1, then paints a DIFFERENT branch to p3. Depth is
        // back at 2 — the position counter used to call this clean.
        history.undo(p) // p2 -> p1
        val p3 = painted(p1, 3); history.record(Draw, p1, p3); p = p3
        assertTrue("diverged branch must read as modified", history.isModified)

        // The redo stack was cleared by the diverging record, so the saved
        // p2 is unreachable through undo/redo — but the ANCHOR compares
        // documents, not stack reachability: re-creating the saved content
        // (a distinct instance that is content-EQUAL to p2) reads clean via
        // the deep-equality fallback of [CommandHistory.isModified].
        history.undo(p) // p3 -> p1
        val p2Again = painted(p1, 2) // same pixel pattern as the saved p2
        history.record(Draw, p1, p2Again)
        p = p2Again
        assertFalse("content-equal document reads clean", history.isModified)
    }

    @Test
    fun `evicted save point keeps reporting dirty`() {
        val history = CommandHistory(limit = 4)
        var p = newProject()
        for (i in 1..4) {
            val next = painted(p, i); history.record(Draw, p, next); p = next
        }
        history.markSaved()
        // Evict the saved state off the stack.
        for (i in 5..8) {
            val next = painted(p, i); history.record(Draw, p, next); p = next
        }
        // Undo cannot reach the saved state anymore (it was evicted), so the
        // document can never be exactly "saved" — isModified must stay true.
        repeat(4) { p = history.undo(p)!! }
        assertTrue("evicted save point must stay dirty", history.isModified)
    }

    @Test
    fun `no-op records keep the dirty state and the mirror current`() {
        val history = CommandHistory()
        val p0 = newProject()
        val p1 = painted(p0, 1)
        history.record(Draw, p0, p1)
        history.markSaved()
        // A no-op record (before == after) mirrors the current state without
        // touching the stacks.
        history.record(Draw, p1, p1)
        assertFalse(history.isModified)
    }

    @Test
    fun `empty transaction records the raw transition as documented`() {
        val history = CommandHistory()
        val p0 = newProject()
        val p1 = painted(p0, 5)
        // The KDoc promises: with no collected steps the transaction records
        // the raw initial -> final transition. The old implementation threw
        // IAE here instead.
        val tx = HistoryTransaction("raw")
        tx.commit(history, p0, p1)
        assertTrue(history.canUndo)
        val restored = history.undo(p1)!!
        assertTrue(restored == p0)
    }
}
