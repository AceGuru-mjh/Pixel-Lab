package com.pixellab.core.gen

import com.pixellab.core.model.PixelFrame
import kotlin.math.cos
import kotlin.math.sin

/** One turtle stroke: start/end in integer pixel coordinates. */
internal data class Stroke(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

/**
 * L-system renderer: expands a string axiom through production rules,
 * then interprets the symbols as a pixel-art turtle.
 *
 * Supported symbols during drawing:
 *  * `F`, `G` — draw forward [step] pixels in the current heading,
 *  * `+` / `-` — turn left/right by [angleDeg],
 *  * `[` / `]` — push/pop turtle state (branching),
 *  * other symbols are ignored (use them for rule structure).
 *
 * Deterministic for one seed when rules contain stochastic weights.
 */
class LSystem(
    /** Initial string. */
    val axiom: String,
    /** Productions: symbol → replacement (with optional weights). */
    private val rules: Map<Char, List<WeightedRule>>,
) {

    /**
     * One weighted alternative of a production.
     */
    data class WeightedRule(val replacement: String, val weight: Double = 1.0)

    init {
        require(axiom.isNotEmpty()) { "axiom must not be empty" }
        for ((symbol, alternatives) in rules) {
            require(alternatives.isNotEmpty()) { "Rule for '$symbol' has no alternatives" }
            require(alternatives.sumOf { it.weight } > 0.0) { "Rule for '$symbol' weights sum to zero" }
        }
    }

    /**
     * Expands the axiom [iterations] times (deterministic rules, or
     * seeded stochastic when [rng] is non-null).
     */
    fun expand(iterations: Int, rng: SeededRng? = null): String {
        require(iterations in 0..12) { "iterations must be in 0..12 (L-systems explode exponentially)" }
        var current = axiom
        repeat(iterations) {
            val sb = StringBuilder(current.length * 4)
            for (c in current) {
                val alts = rules[c]
                if (alts == null) {
                    sb.append(c)
                } else if (rng == null || alts.size == 1) {
                    sb.append(alts[0].replacement)
                } else {
                    sb.append(pickWeighted(alts, rng))
                }
            }
            current = sb.toString()
            if (current.length > 1_000_000) {
                throw IllegalStateException("L-system expansion exceeded 1M symbols at iteration ${it + 1}")
            }
        }
        return current
    }

    private fun pickWeighted(alts: List<WeightedRule>, rng: SeededRng): String {
        val total = alts.sumOf { it.weight }
        var roll = rng.nextDouble() * total
        for (alt in alts) {
            roll -= alt.weight
            if (roll <= 0.0) return alt.replacement
        }
        return alts.last().replacement
    }

    /**
     * Turtle state exposed for tests and custom interpretation.
     */
    data class TurtleState(var x: Double, var y: Double, var headingDeg: Double)

    /**
     * Renders the expanded string (or the axiom when `iterations = 0`)
     * onto an auto-sized transparent canvas, stroking with [color]
     * (branch depth may recolor via [palette] — index = depth).
     *
     * @param step forward distance in pixels.
     * @param angleDeg turn angle.
     * @param iterations expansion count.
     * @param rng optional seed for stochastic rules.
     * @param padding transparent border in pixels.
     * @param palette optional per-depth colors; depth 0 uses [color].
     */
    fun render(
        step: Double = 3.0,
        angleDeg: Double = 25.0,
        iterations: Int = 4,
        rng: SeededRng? = null,
        color: Int = 0xFF2E7D32.toInt(),
        palette: List<Int> = emptyList(),
        padding: Int = 4,
    ): PixelFrame {
        require(step > 0.0) { "step must be positive" }
        require(angleDeg > 0.0) { "angleDeg must be positive" }
        require(padding >= 0) { "padding must be ≥ 0" }
        val symbols = expand(iterations, rng)

        // First pass: track the turtle to find bounds.
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE
        val strokes = ArrayList<Stroke>(256)
        val strokeDepths = ArrayList<Int>(256)
        val stack = ArrayList<TurtleState>()
        var turtle = TurtleState(0.0, 0.0, 90.0) // heading up.
        var depth = 0
        for (c in symbols) {
            when (c) {
                'F', 'G' -> {
                    val rad = Math.toRadians(turtle.headingDeg)
                    val nx = turtle.x + step * cos(rad)
                    val ny = turtle.y - step * sin(rad)
                    strokes.add(Stroke(turtle.x.toInt(), turtle.y.toInt(), nx.toInt(), ny.toInt()))
                    strokeDepths.add(depth)
                    if (turtle.x < minX) minX = turtle.x
                    if (turtle.y < minY) minY = turtle.y
                    if (nx > maxX) maxX = nx
                    if (ny < minY) minY = ny
                    if (nx < minX) minX = nx
                    if (ny > maxY) maxY = ny
                    if (turtle.x > maxX) maxX = turtle.x
                    if (turtle.y > maxY) maxY = turtle.y
                    turtle.x = nx
                    turtle.y = ny
                }
                '+' -> turtle.headingDeg += angleDeg
                '-' -> turtle.headingDeg -= angleDeg
                '[' -> {
                    stack.add(turtle.copy())
                    depth++
                }
                ']' -> {
                    if (stack.isNotEmpty()) {
                        turtle = stack.removeAt(stack.size - 1)
                        depth--
                    }
                }
            }
        }
        if (strokes.isEmpty()) {
            return PixelFrame.blank(1 + 2 * padding, 1 + 2 * padding)
        }
        // Rasterize into a canvas offset so all coordinates are ≥ padding.
        val w = (maxX - minX).toInt() + 1 + 2 * padding
        val h = (maxY - minY).toInt() + 1 + 2 * padding
        val ox = -minX + padding
        val oy = -minY + padding
        val out = IntArray(w * h)
        for (i in strokes.indices) {
            val s = strokes[i]
            val c = if (palette.isEmpty()) color
            else palette[strokeDepths[i].coerceIn(0, palette.size - 1)]
            line(
                out, w, h,
                (s.x0 + ox).toInt(), (s.y0 + oy).toInt(),
                (s.x1 + ox).toInt(), (s.y1 + oy).toInt(), c,
            )
        }
        return PixelFrame.of(w, h, out)
    }

    /** Bresenham line into the raster buffer. */
    private fun line(px: IntArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val dy = kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        while (true) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = color
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x += sx }
            if (e2 < dx) { err += dx; y += sy }
        }
    }

    companion object {
        /** Builds an L-system from single-replacement (deterministic) rules. */
        fun ofDeterministic(axiom: String, rules: Map<Char, String>): LSystem = LSystem(
            axiom,
            buildMap<Char, List<WeightedRule>> {
                for ((symbol, replacement) in rules) {
                    put(symbol, listOf(WeightedRule(replacement, 1.0)))
                }
            },
        )

        /** Classic stochastic bush: 70/30 split into two branch shapes. */
        fun bush(): LSystem = LSystem(
            axiom = "F",
            rules = mapOf(
                'F' to listOf(
                    WeightedRule("F[+F]F[-F]F", 0.7),
                    WeightedRule("FF-[-F+F]+F+F", 0.3),
                ),
            ),
        )

        /** Balanced deterministic binary tree. */
        fun binaryTree(): LSystem = ofDeterministic("F", mapOf('F' to "FF-[-F+F]+[+F-F]"))

        /** Koch curve (edge-sensitive, good for snowflake terrain). */
        fun koch(): LSystem = ofDeterministic("F", mapOf('F' to "F+F-F-F+F"))

        /** Fern-like dragon with narrow branching. */
        fun fern(): LSystem = ofDeterministic("X", mapOf(
            'X' to "F[+X][-X]FX",
            'F' to "FF",
        ))
    }
}
