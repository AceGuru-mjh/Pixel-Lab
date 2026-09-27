package com.pixellab.core.engine

import com.pixellab.core.PixelLab
import com.pixellab.core.PixelLabConfig
import com.pixellab.core.analysis.TestFrames
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.palette.BuiltInPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the engine-correctness round:
 *
 *  * **removeLayer with content** — the historical two-step construction
 *    threw for every layer carrying cels (the intermediate project paired
 *    shrunken layers with stale cels and failed the cel-references-layer
 *    validation). Engine, MCP and the sample app all sit on this path.
 *  * **Undo memory budget** — depth alone allowed ~100 full-project
 *    snapshots of a mega canvas; the byte estimate now evicts first.
 *  * **Concurrent access across projects** — the history map is a
 *    ConcurrentHashMap with per-history locks.
 *  * **Circle radius cap** — the DoS primitive (5e8 radius = 25 s + OOM).
 *  * **Serial-anchored history** — entries expose monotonic commit serials
 *    that survive depth changes (the checkpoint dual anchor builds on it).
 */
class EngineCorrectnessTest {

    private val lab = PixelLab.create()
    private val engine = lab.engine

    private fun project(name: String = "t"): com.pixellab.core.model.SpriteProject =
        SpriteFactory.create(name, 16, 16, BuiltInPalettes.PICO8)

    @Test
    fun `removeLayer strips cels and retargets the active layer in one pass`() {
        var p = project()
        p = engine.addLayer(p, "ink")
        val inkId = p.activeLayerId
        // Paint on BOTH layers so the removed layer carries cels.
        p = engine.drawPixels(p, listOf(PixelPoint(0, 0)), TestFrames.RED)
        p = engine.drawPixel(p.withActiveLayer(0), 1, 1, TestFrames.BLUE)

        val next = engine.removeLayer(p, inkId)
        // The cel is gone, the remaining layer keeps its pixels, activity
        // retargeted to the layer below and — critically — no IAE escaped
        // the construction (this exact call used to throw "references
        // unknown layer" for any layer with content).
        assertEquals(1, next.layerCount)
        assertNull(next.frames[0].cels[inkId])
        assertNotNull(next.frames[0].cels[0])
        assertEquals(TestFrames.BLUE, next.frames[0].cels[0]!![1, 1])
        assertEquals(0, next.activeLayerId)
        // Undo restores the removed layer wholesale.
        val restored = engine.undo(next.id)!!
        assertEquals(2, restored.layerCount)
        assertEquals(TestFrames.RED, restored.frames[0].cels[inkId]!![0, 0])
    }

    @Test
    fun `removeLayer bottom layer hands activity to the new bottom`() {
        var p = project()
        p = engine.addLayer(p, "top")
        val next = engine.removeLayer(p, 0)
        assertEquals(1, next.layerCount)
        assertEquals(next.layers[0].id, next.activeLayerId)
    }

    @Test
    fun `undo history obeys the byte budget, not just the depth cap`() {
        val budget = PixelLabConfig.Builder()
            .maxUndoDepth(100) // depth would allow everything
            .maxUndoBytes(PixelLabConfig.MIN_UNDO_BYTES) // 1 MB floor
            .build()
        val eng = PixelLab.create(budget).engine
        // 64x64 project: materializing the first cel makes every snapshot
        // cost ~16 KB of raster; a 1 MB budget must cap the stack far below
        // the depth limit of 100.
        var p = SpriteFactory.create("budget", 64, 64, BuiltInPalettes.PICO8)
        for (i in 1..200) {
            p = eng.drawPixel(p, i % 64, (i / 64) % 64, TestFrames.RED)
        }
        assertTrue(eng.historyInfo(p.id).size < 100)
    }

    @Test
    fun `histories of distinct projects can be touched from two threads`() {
        val a = project("a")
        val b = project("b")
        val errors = ArrayList<Throwable>()
        val threads = listOf(
            Thread {
                try {
                    var p = a
                    repeat(50) { p = engine.drawPixel(p, it % 16, 0, TestFrames.RED) }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            },
            Thread {
                try {
                    var p = b
                    // 50 distinct positions (a repeat would be a no-op and
                    // commit nothing).
                    repeat(50) { p = engine.drawPixel(p, it % 16, (it / 16) % 16, TestFrames.BLUE) }
                    repeat(25) { engine.undo(b.id) }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            },
        )
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(emptyList<Throwable>(), errors)
        assertEquals(25, engine.historyInfo(b.id).size)
    }

    @Test
    fun `circle radius is capped at 65536`() {
        val p = project()
        try {
            engine.drawCircle(p, 8, 8, 65537, TestFrames.RED, filled = true)
            throw AssertionError("radius above the cap must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("65536"))
        }
    }

    @Test
    fun `history entries carry monotonic commit serials`() {
        var p = project("serial")
        p = engine.drawPixel(p, 0, 0, TestFrames.RED)
        p = engine.drawPixel(p, 1, 0, TestFrames.RED)
        val serials = engine.historyInfo(p.id).map { it.serial }
        assertEquals(2, serials.size)
        assertTrue(serials.zipWithNext().all { (a, b) -> b > a })
        // Serials keep climbing across an undo + re-record cycle — the
        // depth returns to 2 but the states (and serials) differ.
        engine.undo(p.id)
        p = engine.drawPixel(p, 2, 0, TestFrames.BLUE)
        val after = engine.historyInfo(p.id).map { it.serial }
        assertEquals(2, after.size)
        assertTrue(after[1] > serials[1])
    }

    @Test
    fun `byte estimate never evicts the last remaining record`() {
        val eng = PixelLab.create(
            PixelLabConfig.Builder().maxUndoBytes(PixelLabConfig.MIN_UNDO_BYTES).build(),
        ).engine
        var p = project("single")
        p = eng.drawPixel(p, 0, 0, TestFrames.RED)
        assertEquals(1, eng.historyInfo(p.id).size)
    }
}
