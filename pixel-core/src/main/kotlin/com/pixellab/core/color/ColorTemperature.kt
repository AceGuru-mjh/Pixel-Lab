package com.pixellab.core.color

import com.pixellab.core.model.PixelFrame
import kotlin.math.pow

/**
 * Color temperature (Kelvin) synthesis and white-balance correction.
 *
 * Kelvin → RGB uses the Tanner Helland approximation: a piecewise
 * curve-fit of a blackbody radiator mapped into the sRGB gamut, with the
 * 6600K neutral anchor and clamped asymptotes. Accurate to a few RGB
 * units across the 1000K–40000K range — plenty for pixel-art lighting
 * presets (torch 1800K, sunset 3200K, daylight 5600K, overcast 7000K,
 * shade 9000K).
 */
object ColorTemperature {

    /** Valid Kelvin range for [kelvinToRgb]. */
    const val MIN_KELVIN = 1000.0
    const val MAX_KELVIN = 40000.0

    /**
     * The color of a blackbody-like source at [kelvin], as opaque ARGB.
     * Values outside `[MIN_KELVIN, MAX_KELVIN]` are clamped.
     */
    fun kelvinToRgb(kelvin: Double): Int {
        val t = kelvin.coerceIn(MIN_KELVIN, MAX_KELVIN) / 100.0
        // Red: full below 66; decaying log tail above.
        val r = if (t <= 66.0) 255.0 else 329.698727446 * (t - 60.0).pow(-0.1332047592)
        // Green: rises logarithmically, decays past 66.
        val g = if (t <= 66.0) {
            99.4708025861 * kotlin.math.ln(t) - 161.1195681661
        } else {
            288.1221695283 * (t - 60.0).pow(-0.0755148492)
        }
        // Blue: zero below 19, log rise to 66, full after.
        val b = when {
            t >= 66.0 -> 255.0
            t <= 19.0 -> 0.0
            else -> 138.5177312231 * kotlin.math.ln(t - 10.0) - 305.0447927307
        }
        fun c(v: Double) = (v.coerceIn(0.0, 255.0) + 0.5).toInt()
        return 0xFF shl 24 or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /** Named lighting presets (opaque ARGB colors). */
    enum class Preset(val kelvin: Double) {
        CANDLE(1800.0),
        TUNGSTEN(2700.0),
        SUNSET(3200.0),
        FLUORESCENT(4200.0),
        DAYLIGHT(5600.0),
        NOON(6500.0),
        OVERCAST(7000.0),
        SHADE(9000.0),
        BLUE_SKY(15000.0),
    }

    /** The preset's color. */
    fun presetColor(preset: Preset): Int = kelvinToRgb(preset.kelvin)

    /**
     * Applies a color temperature to [frame] as multiplicative channel
     * gains derived from [kelvinToRgb]'s normalized channels (white
     * balance warmth), with [strength] in `[0, 1]` blending toward the
     * original. Alpha is preserved; transparent pixels untouched.
     */
    fun applyTemperature(frame: PixelFrame, kelvin: Double, strength: Double = 1.0): PixelFrame {
        require(strength in 0.0..1.0) { "strength must be in [0, 1]" }
        if (strength == 0.0) return frame
        val tint = kelvinToRgb(kelvin)
        // Channel gains normalized against pure white.
        val kr = ((tint ushr 16 and 0xFF) / 255.0)
        val kg = ((tint ushr 8 and 0xFF) / 255.0)
        val kb = ((tint and 0xFF) / 255.0)
        // Equalize so the operation shifts color, not brightness: scale
        // all gains so their mean stays 1.
        val mean = (kr + kg + kb) / 3.0
        val gr = kr / mean
        val gg = kg / mean
        val gb = kb / mean
        return frame.map { argb ->
            if (argb ushr 24 == 0) argb
            else {
                val a = argb ushr 24
                val r = argb ushr 16 and 0xFF
                val g = argb ushr 8 and 0xFF
                val b = argb and 0xFF
                val nr = (r + (r * gr - r) * strength).toInt().coerceIn(0, 255)
                val ng = (g + (g * gg - g) * strength).toInt().coerceIn(0, 255)
                val nb = (b + (b * gb - b) * strength).toInt().coerceIn(0, 255)
                a shl 24 or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }

    /**
     * Gray-world white balance: estimates the scene illuminant as the
     * mean of the opaque pixels' channels, then scales every channel to
     * pull that mean toward neutral gray. Cheap, deterministic, and the
     * standard "auto white balance" of pixel editors.
     *
     * [strength] blends the correction in `[0, 1]`. Returns the input
     * reference when there is nothing opaque to balance on.
     */
    fun autoWhiteBalance(frame: PixelFrame, strength: Double = 1.0): PixelFrame {
        require(strength in 0.0..1.0) { "strength must be in [0, 1]" }
        if (strength == 0.0) return frame
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        for (p in frame.pixels) {
            if (p ushr 24 == 0) continue
            sumR += p ushr 16 and 0xFF
            sumG += p ushr 8 and 0xFF
            sumB += p and 0xFF
            count++
        }
        if (count == 0L) return frame
        val mr = sumR.toDouble() / count
        val mg = sumG.toDouble() / count
        val mb = sumB.toDouble() / count
        val target = (mr + mg + mb) / 3.0
        if (target <= 0.0) return frame
        val gr = target / mr.coerceAtLeast(1.0)
        val gg = target / mg.coerceAtLeast(1.0)
        val gb = target / mb.coerceAtLeast(1.0)
        return frame.map { argb ->
            if (argb ushr 24 == 0) argb
            else {
                val a = argb ushr 24
                val r = argb ushr 16 and 0xFF
                val g = argb ushr 8 and 0xFF
                val b = argb and 0xFF
                val nr = (r + (r * gr - r) * strength).toInt().coerceIn(0, 255)
                val ng = (g + (g * gg - g) * strength).toInt().coerceIn(0, 255)
                val nb = (b + (b * gb - b) * strength).toInt().coerceIn(0, 255)
                a shl 24 or (nr shl 16) or (ng shl 8) or nb
            }
        }
    }
}
