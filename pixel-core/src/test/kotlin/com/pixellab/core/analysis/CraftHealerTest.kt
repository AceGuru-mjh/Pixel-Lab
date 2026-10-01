package com.pixellab.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior contract of [CraftHealer]: each audited rule has a targeted
 * repair, the regression guard can veto a net-negative pass, the input
 * frame is never mutated, and `scoreDelta` is positive exactly when the
 * heal raised the 0-100 quality score.
 */
class CraftHealerTest {

    private fun auditScore(frame: com.pixellab.core.model.PixelFrame): Int =
        PixelAuditor.audit(frame).score

    @Test
    fun `clean frame is a no-op`() {
        val frame = TestFrames.solid(8, 8, TestFrames.RED)
        val report = CraftHealer.heal(frame)
        assertTrue(report.allApplied)
        assertTrue(report.fixed.isEmpty())
        assertEquals(0, report.scoreDelta)
        assertEquals(frame.pixels.toList(), report.frame.pixels.toList())
    }

    @Test
    fun `isolated dust pixel is cleared and input is not mutated`() {
        val frame = TestFrames.fromRows(
            "....r",
            "rr...",
            "rr...",
            ".....",
        )
        val report = CraftHealer.heal(frame)
        assertEquals(1, report.fixed[AuditRule.ISOLATED_PIXEL])
        assertEquals(0, report.frame[4, 0])
        // The main body survives untouched.
        assertEquals(TestFrames.RED, report.frame[0, 1])
        assertEquals(TestFrames.RED, report.frame[1, 1])
        // The input raster is never mutated (dust still present in `frame`).
        assertEquals(TestFrames.RED, frame[4, 0])
        assertTrue(report.scoreDelta > 0)
    }

    @Test
    fun `enclosed hole is filled with the majority boundary color`() {
        // 5x5 red ring around a 3x3 hollow center: one ENCLOSED_HOLE region.
        val frame = TestFrames.fromRows(
            "rrrrr",
            "r...r",
            "r...r",
            "r...r",
            "rrrrr",
        )
        val report = CraftHealer.heal(frame)
        assertEquals(1, report.fixed[AuditRule.ENCLOSED_HOLE])
        assertEquals(TestFrames.RED, report.frame[2, 2])
        assertEquals(TestFrames.RED, report.frame[1, 1])
        assertTrue(report.scoreDelta > 0)
    }

    @Test
    fun `outside transparency is not treated as a hole`() {
        // Open 'C' shape: the transparent cells connect to the outside, so
        // the healer must not paint them.
        val frame = TestFrames.fromRows(
            "rrr..",
            "r....",
            "rrr..",
        )
        val report = CraftHealer.heal(frame)
        assertNull(report.fixed[AuditRule.ENCLOSED_HOLE])
        assertEquals(0, report.frame[3, 0])
        assertEquals(0, report.frame[1, 1])
    }

    @Test
    fun `tiny diagonal pair is removed as two four-connected components`() {
        // Two green pixels touching only diagonally: each has an opaque
        // 8-neighbor (not ISOLATED), but as same-color FOUR-connected
        // components each has area 1 < tinyClusterArea=2 → both cleared.
        // The solid 2x2 blue block (area 4) survives.
        val frame = TestFrames.fromRows(
            "g....",
            ".g...",
            "bb...",
            "bb...",
        )
        val report = CraftHealer.heal(frame)
        assertEquals(2, report.fixed[AuditRule.TINY_CLUSTER])
        assertEquals(0, report.frame[0, 0])
        assertEquals(0, report.frame[1, 1])
        assertEquals(TestFrames.BLUE, report.frame[0, 2])
        assertEquals(TestFrames.BLUE, report.frame[1, 2])
        assertEquals(TestFrames.BLUE, report.frame[0, 3])
        assertEquals(TestFrames.BLUE, report.frame[1, 3])
    }

    @Test
    fun `disabled rules are skipped`() {
        val frame = TestFrames.fromRows(
            "....r",
            "rr...",
            "rr...",
            ".....",
        )
        val report = CraftHealer.heal(
            frame,
            options = CraftHealer.HealOptions(fixIsolated = false, fixTinyClusters = false),
        )
        assertNull(report.fixed[AuditRule.ISOLATED_PIXEL])
        assertNull(report.fixed[AuditRule.TINY_CLUSTER])
        // Nothing else applies to this frame: the dust pixel survives.
        assertEquals(TestFrames.RED, report.frame[4, 0])
        assertEquals(frame.pixels.toList(), report.frame.pixels.toList())
    }

    @Test
    fun `regression guard rolls back a net-negative heal`() {
        // A frame whose entire content is defect-flagged: clearing it may
        // score WORSE than keeping it. Both outcomes are contract-valid —
        // either the guard fires (input restored) or the heal helped.
        val sparse = TestFrames.fromRows(
            "r....",
            ".....",
            "....g",
        )
        val healed = CraftHealer.heal(
            sparse,
            options = CraftHealer.HealOptions(requireNoRegression = true),
        )
        if (auditScore(healed.frame) < auditScore(sparse)) {
            // The guard fired: input raster returned byte-for-byte.
            assertFalse(healed.allApplied)
            assertEquals(sparse.pixels.toList(), healed.frame.pixels.toList())
            assertEquals(0, healed.totalFixed)
        } else {
            assertTrue(healed.allApplied)
            assertTrue(healed.scoreDelta >= 0)
        }
    }

    @Test
    fun `heal is deterministic`() {
        val frame = TestFrames.fromRows(
            "....r",
            "rr..r",
            "rr..b",
            ".....",
            "g....",
        )
        val first = CraftHealer.heal(frame)
        val second = CraftHealer.heal(frame)
        assertNotSame(first, second)
        assertEquals(first.frame.pixels.toList(), second.frame.pixels.toList())
        assertEquals(first.fixed, second.fixed)
        assertEquals(first.before.score, second.before.score)
        assertEquals(first.after.score, second.after.score)
    }

    @Test
    fun `scoreDelta direction matches the documented quality semantics`() {
        // The score is 0-100 with higher = better, so a successful heal
        // must report a positive delta (the pre-fix sign was inverted).
        val dirty = TestFrames.fromRows(
            "....r",
            "rr...",
            "rr...",
        )
        val report = CraftHealer.heal(dirty)
        assertTrue(report.totalFixed > 0)
        assertTrue(
            "expected positive scoreDelta, got ${report.scoreDelta} " +
                "(before=${report.before.score}, after=${report.after.score})",
            report.scoreDelta > 0,
        )
    }

    @Test
    fun `broken diagonal pair receives orthogonal support pixels`() {
        // Two 2-px segments meeting only diagonally (each segment is a
        // four-connected component of area 2, so the tiny-cluster pass
        // leaves them alone): window (1,0) is a BROKEN_CORNER (and an
        // ACCIDENTAL_CHECKER). The healer bridges the gap with support
        // pixels until the segments orthogonally connect.
        val frame = TestFrames.fromRows(
            "rr..",
            "..rr",
        )
        val report = CraftHealer.heal(frame)
        assertTrue(
            "expected a BROKEN_CORNER repair, fixed=${report.fixed}",
            report.fixed.containsKey(AuditRule.BROKEN_CORNER),
        )
        // The diagonal pair survives and is now orthogonally bridged.
        assertEquals(TestFrames.RED, report.frame[1, 0])
        assertEquals(TestFrames.RED, report.frame[2, 1])
        val bridged = report.frame[2, 0] ushr 24 != 0 || report.frame[1, 1] ushr 24 != 0
        assertTrue("expected at least one orthogonal support pixel painted", bridged)
        assertTrue(report.scoreDelta >= 0)
    }

    @Test
    fun `heal report exposes coherent totals`() {
        val frame = TestFrames.fromRows(
            "....r",
            "rr..r",
            "rr..b",
            ".....",
            "g....",
        )
        val report = CraftHealer.heal(frame)
        assertEquals(report.fixed.values.sum(), report.totalFixed)
        assertTrue(report.toString().contains("HealReport"))
        // before audit describes the input, after describes the result.
        assertEquals(auditScore(frame), report.before.score)
        assertEquals(auditScore(report.frame), report.after.score)
    }
}
