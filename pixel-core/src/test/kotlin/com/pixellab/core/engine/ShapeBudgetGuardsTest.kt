package com.pixellab.core.engine

import com.pixellab.core.model.PixelPoint
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DoS-budget guards on the eager-materializing geometry primitives: the
 * engine builds point lists before clipping them to the canvas, so every
 * shape parameter needs an allocation ceiling independent of the target
 * canvas size.
 */
class ShapeBudgetGuardsTest {

    private fun rejects(action: () -> Unit): Boolean = try {
        action()
        false
    } catch (expected: IllegalArgumentException) {
        true
    }

    @Test
    fun `filled giant circle is rejected before materializing pi r squared points`() {
        // radius 65536 passed every pre-fix validation (the batch layer
        // validated the same constant as fine) and then attempted ~13.4
        // billion point allocations in spanFill.
        val rejected = rejects { DrawOps.circle(100, 100, 65_536, filled = true) }
        assertTrue("filled r=65536 must be rejected by the area budget", rejected)
    }

    @Test
    fun `outlined giant circle stays O(r) and is allowed`() {
        // Outlined circles are O(r) boundary points — no area
        // materialization — so the radius ceiling alone governs them.
        val points = DrawOps.circle(65_536, 65_536, 4_000, filled = false)
        assertTrue("boundary must not be empty", points.isNotEmpty())
        assertTrue(
            "outlined circle must stay linear in r (got ${points.size})",
            points.size < 100_000,
        )
    }

    @Test
    fun `filled circle at a sane radius is unaffected`() {
        val points = DrawOps.circle(50, 50, 40, filled = true)
        // ~pi*40^2 = 5027 interior points.
        assertTrue("expected a filled disc, got ${points.size}", points.size in 4900..5200)
    }

    @Test
    fun `filled giant ellipse is rejected by the area budget`() {
        val rejected = rejects { DrawOps.ellipse(0, 0, 32_000, 32_000, filled = true) }
        assertTrue("filled 32000^2 ellipse must be rejected", rejected)
    }

    @Test
    fun `thick line thickness is capped`() {
        val rejected = rejects { DrawOps.thickLine(0, 0, 10, 10, 500_000) }
        assertTrue("thickness 500000 must be rejected", rejected)
    }

    @Test
    fun `thick line at the cap still works`() {
        val points = DrawOps.thickLine(0, 0, 5, 0, DrawOps.MAX_THICKNESS)
        assertTrue(points.isNotEmpty())
    }

    @Test
    fun `point lists are clipped to a small tap`() {
        val points = DrawOps.thickLine(3, 3, 3, 3, 3)
        assertTrue(points.contains(PixelPoint(3, 3)))
    }
}
