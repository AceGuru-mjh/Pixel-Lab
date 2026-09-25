package com.pixellab.core.color

import com.pixellab.core.model.PixelFrame

/**
 * Color-vision deficiency simulation.
 *
 * Two models are provided:
 *
 *  * **Machado et al. 2009** — the industry standard 3x3 linear RGB
 *    matrices fitted from physiological data, parameterized by severity
 *    in `[0, 1]`. Fast (one matrix multiply per pixel) and the model
 *    Photoshop/Chromium accessibility tooling approximates.
 *  * **Brettel 1997-style dichromatic projection** — projects colors
 *    onto the dichromat's reduced color manifold along the lines
 *    confusable with the missing cone. Implemented here in the widely
 *    used Viénot/Brettel reduced form with the neutral axis preserved.
 *
 * Both models leave luminance roughly intact so layouts remain readable
 * after simulation, which is the point of previewing designs for
 * affected users.
 */
object ColorVision {

    /** The deficiency kind to simulate. */
    enum class Deficiency {
        /** Red-blind (protanopia): long-wavelength cones missing. */
        PROTAN,

        /** Green-blind (deuteranopia): medium-wavelength cones missing. */
        DEUTAN,

        /** Blue-blind (tritanopia): short-wavelength cones missing. */
        TRITAN,

        /** Monochromacy (achromatopsia): luminance only. */
        ACHROMAT,
    }

    /**
     * Machado 2009 severity-1 simulation matrices (row-major, applied to
     * linear RGB). Severity interpolation blends toward identity.
     */
    private val MACHADO_FULL = mapOf(
        Deficiency.PROTAN to doubleArrayOf(
            0.152286, 1.052583, -0.204868,
            0.114503, 0.786281, 0.099216,
            -0.003882, -0.048116, 1.051998,
        ),
        Deficiency.DEUTAN to doubleArrayOf(
            0.367322, 0.860646, -0.227968,
            0.280085, 0.672501, 0.047413,
            -0.011820, 0.042940, 0.968881,
        ),
        Deficiency.TRITAN to doubleArrayOf(
            1.255528, -0.076749, -0.178779,
            -0.078411, 0.930809, 0.147602,
            0.004733, 0.691367, 0.303900,
        ),
    )

    /**
     * Simulates [deficiency] at [severity] on a single ARGB pixel using
     * the Machado model. `severity = 0` is identity, `1` is the full
     * dichromat simulation. Alpha passes through untouched.
     */
    fun simulatePixel(argb: Int, deficiency: Deficiency, severity: Double = 1.0): Int {
        if (deficiency == Deficiency.ACHROMAT) {
            return simulateAchromatPixel(argb, severity)
        }
        require(severity in 0.0..1.0) { "severity must be in [0, 1], was $severity" }
        if (severity == 0.0) return argb
        val m = MACHADO_FULL.getValue(deficiency)
        // Blend the matrix toward identity by severity:
        // B = se·M + (1−se)·I  →  B[i][j] = se·M[i][j], B[i][i] += (1−se).
        val se = severity
        val one = 1.0 - se
        val b00 = m[0] * se + one
        val b01 = m[1] * se
        val b02 = m[2] * se
        val b10 = m[3] * se
        val b11 = m[4] * se + one
        val b12 = m[5] * se
        val b20 = m[6] * se
        val b21 = m[7] * se
        val b22 = m[8] * se + one
        // sRGB → linear → blended matrix → back.
        val lr = srgbToLinear(argb ushr 16 and 0xFF)
        val lg = srgbToLinear(argb ushr 8 and 0xFF)
        val lb = srgbToLinear(argb and 0xFF)
        val or = lr * b00 + lg * b01 + lb * b02
        val og = lr * b10 + lg * b11 + lb * b12
        val ob = lr * b20 + lg * b21 + lb * b22
        val a = argb ushr 24
        return a shl 24 or
            (linearToSrgb(or) shl 16) or
            (linearToSrgb(og) shl 8) or
            linearToSrgb(ob)
    }

    private fun simulateAchromatPixel(argb: Int, severity: Double): Int {
        require(severity in 0.0..1.0) { "severity must be in [0, 1], was $severity" }
        if (severity == 0.0) return argb
        // Rec.709 luma keeps the preview readable.
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)
        val blend = { c: Int -> (c + ((luma - c) * severity)).toInt().coerceIn(0, 255) }
        val a = argb ushr 24
        return a shl 24 or (blend(r) shl 16) or (blend(g) shl 8) or blend(b)
    }

    /**
     * Simulates [deficiency] over an entire [frame] (Machado model for
     * the dichromacies, luma blend for [Deficiency.ACHROMAT]).
     * Returns a new frame; transparent pixels stay untouched.
     */
    fun simulate(frame: PixelFrame, deficiency: Deficiency, severity: Double = 1.0): PixelFrame {
        require(severity in 0.0..1.0) { "severity must be in [0, 1], was $severity" }
        if (severity == 0.0) return frame
        if (deficiency == Deficiency.ACHROMAT) {
            return frame.map { simulateAchromatPixel(it, severity) }
        }
        return frame.map { simulatePixel(it, deficiency, severity) }
    }

    /**
     * Renders a side-by-side comparison strip: original + the four
     * simulations at [severity], each cell [cellWidth] x [cellHeight]
     * (sampled by nearest neighbor from [frame]). Useful for one-glance
     * accessibility reviews and MCP tool previews.
     */
    fun comparisonStrip(frame: PixelFrame, severity: Double = 1.0, cellWidth: Int = 24, cellHeight: Int = 24): PixelFrame {
        require(cellWidth > 0 && cellHeight > 0) { "Positive cell dimensions required" }
        val variants = listOf(
            frame,
            simulate(frame, Deficiency.PROTAN, severity),
            simulate(frame, Deficiency.DEUTAN, severity),
            simulate(frame, Deficiency.TRITAN, severity),
            simulate(frame, Deficiency.ACHROMAT, severity),
        )
        val out = IntArray(cellWidth * cellHeight * variants.size)
        for (v in variants.indices) {
            val base = v * cellWidth * cellHeight
            for (y in 0 until cellHeight) {
                val sy = (y * frame.height / cellHeight).coerceIn(0, frame.height - 1)
                for (x in 0 until cellWidth) {
                    val sx = (x * frame.width / cellWidth).coerceIn(0, frame.width - 1)
                    out[base + y * cellWidth + x] = variants[v].pixels[sy * frame.width + sx]
                }
            }
        }
        return PixelFrame.of(cellWidth * variants.size, cellHeight, out)
    }

    /**
     * Viénot 1999 reduced dichromat matrices (linear RGB, D65):
     * direct RGB-domain projections of the Brettel approach that keep
     * the neutral axis fixed by construction (each row preserves gray).
     */
    private val VIENOT_MATRICES = mapOf(
        Deficiency.PROTAN to doubleArrayOf(
            0.11238, 0.88762, 0.0,
            0.11238, 0.88762, 0.0,
            0.00401, -0.00401, 1.0,
        ),
        Deficiency.DEUTAN to doubleArrayOf(
            0.29275, 0.70725, 0.0,
            0.29275, 0.70725, 0.0,
            -0.02234, 0.02234, 1.0,
        ),
    )

    /**
     * Viénot/Brettel-style dichromatic projection for red/green
     * blindness. The neutral axis is preserved exactly; tritan and
     * achromat delegate to [simulatePixel] (Viénot's derivation covers
     * only the L/M-cone deficiencies).
     */
    fun simulateBrettel(argb: Int, deficiency: Deficiency): Int {
        val m = VIENOT_MATRICES[deficiency] ?: return simulatePixel(argb, deficiency, 1.0)
        val lr = srgbToLinear(argb ushr 16 and 0xFF)
        val lg = srgbToLinear(argb ushr 8 and 0xFF)
        val lb = srgbToLinear(argb and 0xFF)
        val or = lr * m[0] + lg * m[1] + lb * m[2]
        val og = lr * m[3] + lg * m[4] + lb * m[5]
        val ob = lr * m[6] + lg * m[7] + lb * m[8]
        val a = argb ushr 24
        return a shl 24 or
            (linearToSrgb(or) shl 16) or
            (linearToSrgb(og) shl 8) or
            linearToSrgb(ob)
    }

    // ── sRGB transfer helpers ───────────────────────────────────────────────

    private fun srgbToLinear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun linearToSrgb(v: Double): Int {
        val c = if (v <= 0.0031308) v * 12.92 else 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055
        return (c * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }
}
