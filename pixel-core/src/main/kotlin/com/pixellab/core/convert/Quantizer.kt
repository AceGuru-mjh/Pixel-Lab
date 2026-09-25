package com.pixellab.core.convert

import com.pixellab.core.nativelib.NativeLib
import com.pixellab.core.nativelib.NativeQuantizer

/**
 * Quantizer facade: validates the request, clamps the color target and
 * dispatches to the native bridge when [NativeLib] links, transparently
 * falling back to [KotlinQuantizer] on `UnsatisfiedLinkError` or
 * `IllegalArgumentException`.
 *
 * Both paths implement identical semantics: transparent pixels
 * (`alpha < 0x80`) are excluded from statistics and pass through the mapped
 * output unchanged; palette entries are opaque; the color target is clamped
 * to `[2, 256]`.
 */
object Quantizer {

    /**
     * When true (the default), quantization prefers the native implementation
     * once [NativeLib.load] succeeds. Hosts may flip this at runtime to pin
     * the pure-Kotlin path.
     */
    @Volatile
    var preferNative: Boolean = true

    /**
     * Quantizes [pixels] down to at most [targetColors] entries with
     * [algorithm]. Structural validation: an empty raster short-circuits to
     * the all-transparent path (empty palette, pixels passed through).
     */
    fun quantize(pixels: IntArray, targetColors: Int, algorithm: QuantizeAlgorithm): QuantizeResult {
        if (pixels.isEmpty()) {
            return KotlinQuantizer.quantize(pixels, targetColors, algorithm)
        }
        val target = targetColors.coerceIn(2, 256)
        if (preferNative && NativeLib.load()) {
            try {
                return NativeQuantizer.quantize(pixels, target, algorithm.id)
            } catch (e: UnsatisfiedLinkError) {
                // Quantizer symbols missing from this native build: fall back.
            } catch (e: IllegalArgumentException) {
                // Native rejected the input or found no colors: fall back.
            }
        }
        return KotlinQuantizer.quantize(pixels, target, algorithm)
    }
}
