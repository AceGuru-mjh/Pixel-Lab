package com.pixellab.core.batch

import com.pixellab.core.PixelLab
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.core.analysis.TestFrames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DrawingBatch]: validation-first semantics, per-op
 * accounting, no-op detection and the batch/undo interaction.
 */
class DrawingBatchTest {

    private lateinit var lab: PixelLab
    private lateinit var project: SpriteProject

    @Before
    fun setUp() {
        lab = PixelLab.create()
        project = com.pixellab.core.model.SpriteFactory.create("batch", 8, 8, BuiltInPalettes.PICO8)
    }

    @Test
    fun `apply draws pixels and reports outcomes`() {
        val result = DrawingBatch.validateAndApply(
            listOf(
                BatchOp.Pixel(1, 1, TestFrames.RED),
                BatchOp.Pixel(2, 2, TestFrames.GREEN),
            ),
            project, lab.engine,
        )
        assertEquals(2, result.outcomes.size)
        assertEquals(2, result.appliedCount)
        assertTrue(result.outcomes[0].applied)
        assertEquals("batch:pixel", result.outcomes[0].label)
        assertEquals(TestFrames.RED, result.project.compositeActiveFrame()[1, 1])
        assertEquals(TestFrames.GREEN, result.project.compositeActiveFrame()[2, 2])
    }

    @Test
    fun `duplicate pixel is a no-op and adds no history`() {
        val drawn = lab.engine.drawPixel(project, 3, 3, TestFrames.BLUE)
        val beforeHistory = lab.engine.historyInfo(drawn.id).size
        val result = DrawingBatch.validateAndApply(
            listOf(BatchOp.Pixel(3, 3, TestFrames.BLUE)),
            drawn, lab.engine,
        )
        assertFalse(result.outcomes[0].applied)
        assertEquals(0, result.historyEntriesAdded)
        assertEquals(beforeHistory, lab.engine.historyInfo(drawn.id).size)
    }

    @Test
    fun `validation rejects out-of-bounds pixel before applying anything`() {
        val ops = listOf(
            BatchOp.Pixel(0, 0, TestFrames.RED), // valid
            BatchOp.Pixel(99, 99, TestFrames.RED), // invalid
        )
        try {
            DrawingBatch.validateAndApply(ops, project, lab.engine)
            throw AssertionError("expected BatchValidationException")
        } catch (expected: BatchValidationException) {
            assertEquals(1, expected.index)
            assertTrue(expected.message!!.contains("op[1]"))
        }
        // Nothing was applied: canvas still blank.
        assertEquals(0, countVisible(project))
    }

    @Test
    fun `validation reports failing op index for every kind`() {
        data class Case(val op: BatchOp, val keyword: String)

        val cases = listOf(
            Case(BatchOp.Pixel(-1, 0, TestFrames.RED), "outside"),
            Case(BatchOp.Line(9, 1, 10, 2, TestFrames.RED), "outside"),
            Case(BatchOp.Line(0, 0, 7, 7, TestFrames.RED, thickness = 0), "thickness"),
            Case(BatchOp.Rect(2, 2, 0, 4, TestFrames.RED), "edges"),
            Case(BatchOp.Rect(-10, -10, 4, 4, TestFrames.RED), "outside"),
            Case(BatchOp.Circle(-9, 0, 4, TestFrames.RED), "outside"),
            Case(BatchOp.Circle(4, 4, -1, TestFrames.RED), "radius"),
            Case(BatchOp.Stroke(emptyList(), TestFrames.RED), "empty"),
            Case(BatchOp.Stroke(listOf(PixelPoint(0, 0)), TestFrames.RED, thickness = 99), "thickness"),
            Case(BatchOp.Stroke(listOf(PixelPoint(50, 50)), TestFrames.RED), "outside"),
            Case(BatchOp.Fill(50, 50, TestFrames.RED), "outside"),
            Case(BatchOp.Fill(0, 0, TestFrames.RED, tolerance = -1), "tolerance"),
            Case(BatchOp.Replace(TestFrames.RED, TestFrames.BLUE, tolerance = -2), "tolerance"),
            Case(BatchOp.Erase(emptyList()), "empty"),
            Case(BatchOp.Erase(listOf(PixelPoint(50, 50))), "outside"),
        )
        for ((index, case) in cases.withIndex()) {
            try {
                DrawingBatch.validate(listOf(case.op), project)
                throw AssertionError("case $index (${case.keyword}) should have failed")
            } catch (expected: BatchValidationException) {
                assertTrue(
                    "case $index message '${expected.message}' should contain '${case.keyword}'",
                    expected.message!!.contains(case.keyword),
                )
            }
        }
    }

    @Test
    fun `validation rejects batches over the op cap`() {
        val ops = (1..DrawingBatch.MAX_OPS + 1).map { BatchOp.Pixel(0, 0, TestFrames.RED) }
        try {
            DrawingBatch.validate(ops, project)
            throw AssertionError("expected cap failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("max ${DrawingBatch.MAX_OPS}"))
        }
    }

    @Test
    fun `locked active layer is rejected with actionable hint`() {
        val locked = project.copy(
            layers = project.layers.map { if (it.id == project.activeLayerId) it.copy(locked = true) else it },
        )
        try {
            DrawingBatch.validate(listOf(BatchOp.Pixel(1, 1, TestFrames.RED)), locked)
            throw AssertionError("expected locked-layer failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("locked"))
            assertTrue(expected.message!!.contains("layer_set_locked"))
        }
    }

    @Test
    fun `overhanging primitives are clipped not rejected`() {
        // A circle centered near the edge overhangs but overlaps the canvas.
        val result = DrawingBatch.validateAndApply(
            listOf(BatchOp.Circle(6, 6, 4, TestFrames.RED)),
            project, lab.engine,
        )
        assertTrue(result.outcomes[0].applied)
        assertTrue(countVisible(result.project) > 0)
    }

    @Test
    fun `rect and circle apply real geometry`() {
        val result = DrawingBatch.validateAndApply(
            listOf(
                BatchOp.Rect(1, 1, 4, 4, TestFrames.RED, filled = true),
                BatchOp.Circle(6, 6, 2, TestFrames.GREEN, filled = false),
            ),
            project, lab.engine,
        )
        assertEquals(2, result.appliedCount)
        val frame = result.project.compositeActiveFrame()
        assertEquals(TestFrames.RED, frame[2, 2])
        // outline circle at (6,6) r=2 touches (4,6) leftmost point
        assertEquals(TestFrames.GREEN, frame[4, 6])
    }

    @Test
    fun `fill and replace work inside batches`() {
        val drawn = lab.engine.drawRect(project, 1, 1, 5, 5, TestFrames.RED, true)
        val result = DrawingBatch.validateAndApply(
            listOf(
                BatchOp.Fill(2, 2, TestFrames.GREEN),
                BatchOp.Replace(TestFrames.GREEN, TestFrames.BLUE),
            ),
            drawn, lab.engine,
        )
        val frame = result.project.compositeActiveFrame()
        assertEquals(TestFrames.BLUE, frame[2, 2])
    }

    @Test
    fun `each applied op adds one undo entry`() {
        val result = DrawingBatch.validateAndApply(
            listOf(
                BatchOp.Pixel(0, 0, TestFrames.RED),
                BatchOp.Pixel(1, 1, TestFrames.RED),
                BatchOp.Pixel(2, 2, TestFrames.RED),
            ),
            project, lab.engine,
        )
        assertEquals(3, result.historyEntriesAdded)
        assertEquals(3, lab.engine.historyInfo(result.project.id).size)
        // Undoing three times restores the blank canvas.
        var current = result.project
        repeat(3) { current = lab.engine.undo(current.id) ?: error("undo should succeed") }
        assertEquals(0, countVisible(current))
    }

    @Test
    fun `apply of empty batch returns same instance`() {
        val result = DrawingBatch.apply(listOf<BatchOp>(), project, lab.engine)
        assertSame(project, result.project)
    }

    @Test
    fun `apply on new project produces distinct instance`() {
        val result = DrawingBatch.validateAndApply(
            listOf(BatchOp.Pixel(5, 5, TestFrames.RED)),
            project, lab.engine,
        )
        assertNotSame(project, result.project)
    }

    private fun countVisible(p: SpriteProject): Int {
        val pixels = p.compositeActiveFrame().pixels
        return pixels.count { it ushr 24 != 0 }
    }
}
