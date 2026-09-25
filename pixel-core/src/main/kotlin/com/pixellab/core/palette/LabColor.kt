package com.pixellab.core.palette

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * CIELAB color space math for perceptual palette matching.
 *
 * RGB distances overweight luminance and underweight hue, which produces
 * "wrong" snaps when mapping images onto palettes. Matching in Lab (with the
 * usual `deltaE = sqrt(dL^2 + da^2 + db^2)`) gives visually closer results.
 * All math is pure Kotlin with fixed formulas: no state, fully deterministic.
 */
object LabColor {

    /** One color in CIELAB coordinates: `L*` in `[0,100]`, `a*`/`b*` unbounded. */
    data class Lab(val l: Double, val a: Double, val b: Double)

    // sRGB (D65) reference white, scaled to Y=1.
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    /** Converts an ARGB packed pixel to CIELAB (alpha ignored). */
    fun fromArgb(argb: Int): Lab {
        val r = linearize(((argb shr 16) and 0xFF) / 255.0)
        val g = linearize(((argb shr 8) and 0xFF) / 255.0)
        val b = linearize((argb and 0xFF) / 255.0)
        // sRGB -> XYZ (D65).
        val x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / XN
        val y = (r * 0.2126729 + g * 0.7151522 + b * 0.0721750) / YN
        val z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / ZN
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Lab(l = 116.0 * fy - 16.0, a = 500.0 * (fx - fy), b = 200.0 * (fy - fz))
    }

    /** Converts CIELAB back to an opaque packed ARGB pixel. */
    fun toArgb(lab: Lab): Int {
        val fy = (lab.l + 16.0) / 116.0
        val fx = fy + lab.a / 500.0
        val fz = fy - lab.b / 200.0
        val x = fInv(fx) * XN
        val y = fInv(fy) * YN
        val z = fInv(fz) * ZN
        val r = delinearize(x * 3.2404542 + y * -1.5371385 + z * -0.4985314)
        val g = delinearize(x * -0.9692660 + y * 1.8760108 + z * 0.0415560)
        val b = delinearize(x * 0.0556434 + y * -0.2040259 + z * 1.0572252)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    /** Euclidean Delta-E (1976) between two packed pixels. */
    fun distance(argb0: Int, argb1: Int): Double = distance(fromArgb(argb0), fromArgb(argb1))

    /** Delta-E between two Lab colors. */
    fun distance(lab0: Lab, lab1: Lab): Double {
        val dl = lab0.l - lab1.l
        val da = lab0.a - lab1.a
        val db = lab0.b - lab1.b
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Chroma magnitude `sqrt(a*^2 + b*^2)`; useful for palette heuristics. */
    fun chroma(argb: Int): Double {
        val lab = fromArgb(argb)
        return sqrt(lab.a * lab.a + lab.b * lab.b)
    }

    /** Lightness `L*` of a packed pixel in `[0, 100]`. */
    fun lightness(argb: Int): Double = fromArgb(argb).l

    /** Perceptual difference check with an inclusive threshold. */
    fun closeEnough(argb0: Int, argb1: Int, threshold: Double = 2.3): Boolean =
        distance(argb0, argb1) <= threshold

    /** Compares two packed pixels ignoring alpha. */
    fun sameRgb(argb0: Int, argb1: Int): Boolean =
        ((argb0 shr 16) and 0xFF) == ((argb1 shr 16) and 0xFF) &&
            ((argb0 shr 8) and 0xFF) == ((argb1 shr 8) and 0xFF) &&
            (argb0 and 0xFF) == (argb1 and 0xFF)

    /** Max absolute per-channel difference (A, R, G, B) for tolerance comparisons. */
    fun channelMaxDiff(argb0: Int, argb1: Int): Int = maxOf(
        abs(((argb0 shr 24) and 0xFF) - ((argb1 shr 24) and 0xFF)),
        abs(((argb0 shr 16) and 0xFF) - ((argb1 shr 16) and 0xFF)),
        abs(((argb0 shr 8) and 0xFF) - ((argb1 shr 8) and 0xFF)),
        abs((argb0 and 0xFF) - (argb1 and 0xFF)),
    )

    private fun linearize(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun delinearize(c: Double): Double =
        if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055

    /** CIE f(t): cube root above the epsilon knee, linear below. */
    private fun f(t: Double): Double =
        if (t > 0.008856) cbrt(t) else (903.3 * t + 16.0) / 116.0

    /** Inverse of [f]. */
    private fun fInv(t: Double): Double {
        val t3 = t * t * t
        return if (t3 > 0.008856) t3 else (116.0 * t - 16.0) / 903.3
    }

    private fun channel(c: Double): Int = (c * 255.0 + 0.5).toInt().coerceIn(0, 255)
}
