package com.pixellab.core.convert

import com.pixellab.core.nativelib.NativeDitherer
import com.pixellab.core.nativelib.NativeLib

/**
 * Ditherer facade: validates the raster structure and dispatches to the
 * native bridge when [NativeLib] links, transparently falling back to
 * [KotlinDitherer] on `UnsatisfiedLinkError` or `IllegalArgumentException`.
 *
 * The two paths agree on the structural semantics — transparent pixels
 * (`alpha < 0x80`) keep their original value, opaque pixels map to the
 * nearest palette color preserving their alpha, intensity is clamped to
 * `[0, 1]` with NaN treated as 0 — but they are NOT byte-identical: the
 * Kotlin path measures nearest colors in CIELAB distance and rounds the
 * accumulated error half-up, while the native kernels use squared-RGB
 * distance and truncate toward zero (and the native k-means shares only the
 * seed integer, not the RNG stream, with the Kotlin quantizer). Which path
 * runs depends on [NativeLib] linking, so cross-platform comparisons and
 * golden-file tests must pin [preferNative] to one side.
 */
object Ditherer {

    /**
     * When true (the default), dithering prefers the native implementation
     * once [NativeLib.load] succeeds. Hosts may flip this at runtime to pin
     * the pure-Kotlin path.
     */
    @Volatile
    var preferNative: Boolean = true

    /**
     * Dithers [pixels] (row-major, `width * height`) onto [palette] with
     * [algorithm] and [intensity]. Structural validation: non-positive
     * dimensions, an array length that does not match the geometry or an
     * empty palette skip the native call and degrade to the Kotlin behavior
     * of returning a copy of the input.
     */
    fun dither(
        pixels: IntArray, width: Int, height: Int,
        palette: IntArray, algorithm: DitherAlgorithm, intensity: Float,
    ): IntArray {
        if (width <= 0 || height <= 0 || width.toLong() * height != pixels.size.toLong() || palette.isEmpty()) {
            return KotlinDitherer.dither(pixels, width, height, palette, algorithm, intensity)
        }
        if (preferNative && NativeLib.load()) {
            try {
                return NativeDitherer.applyDither(pixels, width, height, palette, algorithm.id, intensity)
            } catch (e: UnsatisfiedLinkError) {
                // Ditherer symbols missing from this native build: fall back.
            } catch (e: IllegalArgumentException) {
                // Native rejected the input: fall back.
            }
        }
        return KotlinDitherer.dither(pixels, width, height, palette, algorithm, intensity)
    }
}
