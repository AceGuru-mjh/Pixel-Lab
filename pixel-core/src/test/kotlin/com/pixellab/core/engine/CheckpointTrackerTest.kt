package com.pixellab.core.engine

import com.pixellab.core.PixelLab
import com.pixellab.core.analysis.TestFrames
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.palette.BuiltInPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CheckpointTracker]: depth recording, rollback loops,
 * idempotence, stale-lineage refusal and per-session bookkeeping against
 * a real [PixelEngine] history.
 */
class CheckpointTrackerTest {

    private lateinit var lab: PixelLab
    private lateinit var engine: PixelEngine
    private lateinit var tracker: CheckpointTracker
    private val project by lazy { SpriteFactory.create("cp", 8, 8, BuiltInPalettes.PICO8) }

    @Before
    fun setUp() {
        lab = PixelLab.create()
        engine = lab.engine
        tracker = CheckpointTracker()
    }

    /** Draws n single pixels through the engine, returning the latest project. */
    private fun drawSteps(count: Int): com.pixellab.core.model.SpriteProject {
        var current = project
        for (i in 0 until count) {
            current = engine.drawPixel(current, i % 8, 0, TestFrames.RED)
        }
        return current
    }

    @Test
    fun `checkpoint records current undo depth`() {
        drawSteps(3)
        val checkpoint = tracker.set("s1", "safe", project.id, engine)
        assertEquals(3, checkpoint.depth)
        assertEquals("safe", checkpoint.name)
        assertEquals("drawPixel", checkpoint.nextLabel)
        assertTrue(checkpoint.createdAtMs > 0)
    }

    @Test
    fun `rollback undoes exactly the post-checkpoint steps`() {
        val drawn = drawSteps(2)
        tracker.set("s1", "safe", project.id, engine)
        val after = engine.drawPixel(drawn, 5, 5, TestFrames.GREEN)
        val after2 = engine.drawPixel(after, 6, 6, TestFrames.GREEN)
        assertEquals(4, engine.historyInfo(project.id).size)

        val result = tracker.rollback("s1", "safe", project.id, engine)
        assertTrue(result.rewound)
        assertEquals(2, result.steps)
        assertNotNull(result.restoredProject)
        val restored = result.restoredProject!!
        // The restored snapshot is exactly the state at checkpoint time:
        // (5,5) was painted green only AFTER the checkpoint.
        val restoredPixel = restored.compositeActiveFrame()[5, 5]
        assertTrue("restored (5,5) should predate the green draw", restoredPixel != TestFrames.GREEN)
        assertEquals(2, engine.historyInfo(project.id).size)
    }

    @Test
    fun `rollback to fresh checkpoint is a two-step restore`() {
        val drawn = drawSteps(2)
        tracker.set("s1", "base", project.id, engine)
        engine.drawPixel(drawn, 7, 7, TestFrames.BLUE)
        val result = tracker.rollback("s1", "base", project.id, engine)
        assertEquals(1, result.steps)
        assertEquals(2, engine.historyInfo(project.id).size)
    }

    @Test
    fun `rollback is idempotent when history already at target`() {
        val drawn = drawSteps(2)
        tracker.set("s1", "safe", project.id, engine)
        engine.drawPixel(drawn, 5, 5, TestFrames.GREEN)
        engine.drawPixel(drawn, 6, 6, TestFrames.GREEN)
        val first = tracker.rollback("s1", "safe", project.id, engine)
        assertTrue(first.rewound)
        assertEquals(2, first.steps)
        val second = tracker.rollback("s1", "safe", project.id, engine)
        assertFalse(second.rewound)
        assertEquals(0, second.steps)
        assertNull(second.restoredProject)
    }

    @Test
    fun `rollback past checkpoint manually then rollback reports zero steps`() {
        drawSteps(3)
        tracker.set("s1", "safe", project.id, engine)
        engine.undo(project.id) // user undoes past depth 3 → depth 2
        val result = tracker.rollback("s1", "safe", project.id, engine)
        assertFalse(result.rewound)
        assertEquals(0, result.steps)
    }

    @Test
    fun `stale lineage is refused`() {
        drawSteps(1)
        tracker.set("s1", "safe", project.id, engine)
        val other = SpriteFactory.create("other", 8, 8, BuiltInPalettes.PICO8)
        engine.drawPixel(other, 0, 0, TestFrames.RED)
        try {
            tracker.rollback("s1", "safe", other.id, engine)
            throw AssertionError("expected stale-lineage failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("belongs to project"))
        }
    }

    @Test
    fun `unknown checkpoint is refused`() {
        try {
            tracker.rollback("s1", "nope", project.id, engine)
            throw AssertionError("expected unknown-checkpoint failure")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not found"))
        }
    }

    @Test
    fun `setting the same name replaces the checkpoint`() {
        drawSteps(1)
        tracker.set("s1", "safe", project.id, engine)
        drawSteps(2)
        tracker.set("s1", "safe", project.id, engine)
        val list = tracker.list("s1")
        assertEquals(1, list.size)
        assertEquals(3, list[0].depth)
    }

    @Test
    fun `list is ordered and per-session isolated`() {
        drawSteps(1)
        tracker.set("s1", "a", project.id, engine)
        tracker.set("s1", "b", project.id, engine)
        tracker.set("s2", "a", project.id, engine)
        val s1 = tracker.list("s1")
        val s2 = tracker.list("s2")
        assertEquals(listOf("a", "b"), s1.map { it.name })
        assertEquals(listOf("a"), s2.map { it.name })
    }

    @Test
    fun `remove and clearFor drop checkpoints`() {
        drawSteps(1)
        tracker.set("s1", "a", project.id, engine)
        tracker.set("s1", "b", project.id, engine)
        assertTrue(tracker.remove("s1", "a"))
        assertFalse(tracker.remove("s1", "a"))
        assertEquals(1, tracker.list("s1").size)
        tracker.clearFor("s1")
        assertEquals(0, tracker.list("s1").size)
    }

    @Test
    fun `checkpoint cap evicts oldest`() {
        drawSteps(0)
        for (i in 1..33) {
            tracker.set("s1", "cp$i", project.id, engine)
        }
        assertEquals(32, tracker.list("s1").size)
        // cp1 was evicted; cp2 is the oldest remaining.
        assertEquals("cp2", tracker.list("s1").first().name)
    }

    @Test
    fun `blank names are rejected`() {
        try {
            tracker.set("s1", "  ", project.id, engine)
            throw AssertionError("expected blank-name failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("blank"))
        }
    }
}
