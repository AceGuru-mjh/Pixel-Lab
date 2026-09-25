package com.pixellab.core.gen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiomePainterTest {

    private fun field(width: Int, height: Int, value: Double): DoubleArray =
        DoubleArray(width * height) { value }

    @Test
    fun `below sea level paints water`() {
        val w = 8
        val h = 4
        val frame = BiomePainter.paint(w, h, field(w, h, 0.1), field(w, h, 0.5))
        val expected = BiomePainter.Theme.OVERWORLD.waterColor
        assertTrue(frame.pixels.all { it == expected })
    }

    @Test
    fun `shore band paints between water and land`() {
        val w = 4
        val h = 4
        val theme = BiomePainter.Theme.OVERWORLD
        // Just above sea level → shore.
        val frame = BiomePainter.paint(w, h, field(w, h, theme.seaLevel + 0.01), field(w, h, 0.5))
        assertTrue(frame.pixels.all { it == theme.shoreColor })
    }

    @Test
    fun `deep water stays flat when tint disabled`() {
        val w = 4
        val h = 2
        val theme = BiomePainter.Theme.OVERWORLD
        val frame = BiomePainter.paint(
            w, h, field(w, h, 0.05), field(w, h, 0.5),
            waterDepthTint = false,
        )
        assertTrue(frame.pixels.all { it == theme.waterColor })
    }

    @Test
    fun `moisture selects different ramp rows`() {
        val w = 4
        val h = 1
        val dry = BiomePainter.paint(w, h, field(w, h, 0.8), field(w, h, 0.0))
        val wet = BiomePainter.paint(w, h, field(w, h, 0.8), field(w, h, 1.0))
        assertTrue(dry.pixels[0] != wet.pixels[0])
    }

    @Test
    fun `paintFromNoise produces mixed palette from theme`() {
        val frame = BiomePainter.paintFromNoise(64, 48, seed = 5L)
        assertEquals(64, frame.width)
        assertEquals(48, frame.height)
        val distinct = frame.pixels.distinct()
        // A world map mixes water, shore and several land bands.
        assertTrue("distinct=${distinct.size}", distinct.size >= 3)
    }

    @Test
    fun `validation rejects mismatched fields`() {
        try {
            BiomePainter.paint(4, 4, DoubleArray(5), DoubleArray(16))
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) { }
    }
}

class LSystemTest {

    @Test
    fun `deterministic expansion follows rules`() {
        val ls = LSystem.ofDeterministic("A", mapOf('A' to "AB"))
        assertEquals("A", ls.expand(0))
        assertEquals("AB", ls.expand(1))
        assertEquals("ABB", ls.expand(2))
        assertEquals("ABBB", ls.expand(3))
    }

    @Test
    fun `multiple rules expand simultaneously`() {
        val ls = LSystem.ofDeterministic("X", mapOf('X' to "F+X", 'F' to "FF"))
        // X → F+X → FF+F+X
        assertEquals("FF+F+X", ls.expand(2))
    }

    @Test
    fun `stochastic expansion is seeded`() {
        val ls = LSystem.bush()
        val a = ls.expand(4, SeededRng(10L))
        val b = ls.expand(4, SeededRng(10L))
        assertEquals(a, b)
        val c = ls.expand(4, SeededRng(11L))
        assertTrue(a != c)
    }

    @Test
    fun `render draws opaque pixels within bounds`() {
        val ls = LSystem.binaryTree()
        val frame = ls.render(step = 3.0, angleDeg = 25.0, iterations = 3)
        assertTrue(frame.width > 4)
        assertTrue(frame.height > 4)
        assertTrue(frame.pixels.count { it != 0 } > 10)
    }

    @Test
    fun `render with palette recolors branches by depth`() {
        val ls = LSystem.binaryTree()
        val frame = ls.render(
            step = 3.0, angleDeg = 30.0, iterations = 3,
            palette = listOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt()),
        )
        val colors = frame.pixels.filter { it != 0 }.distinct()
        assertTrue("branch colors: ${colors.size}", colors.size >= 2)
    }

    @Test
    fun `koch curve grows predictably`() {
        // Rule length 5: |F| → 5^n after n iterations (plus + and -).
        val ls = LSystem.koch()
        val s1 = ls.expand(1)
        assertEquals("F+F-F-F+F", s1)
        assertEquals(9, ls.expand(1).length) // 5 F symbols + 4 turns
        assertEquals(25, ls.expand(2).count { it == 'F' })
    }

    @Test
    fun `fern uses two symbols with one ignored`() {
        val ls = LSystem.fern()
        // X is rewritten but ignored by the turtle — only F draws.
        val s = ls.expand(2)
        assertTrue(s.contains('F'))
        val frame = ls.render(step = 2.0, angleDeg = 22.0, iterations = 2)
        assertTrue(frame.pixels.count { it != 0 } > 4)
    }

    @Test
    fun `empty draw produces blank padded frame`() {
        // Only ignored symbols: nothing is drawn.
        val ls = LSystem.ofDeterministic("+-[]", mapOf('Q' to "Q"))
        val frame = ls.render(iterations = 1)
        assertTrue(frame.pixels.all { it == 0 })
    }

    @Test
    fun `iteration cap is enforced`() {
        val ls = LSystem.ofDeterministic("A", mapOf('A' to "AA"))
        try {
            ls.expand(13)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) { }
    }
}
