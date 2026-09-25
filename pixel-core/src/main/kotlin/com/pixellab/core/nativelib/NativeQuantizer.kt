package com.pixellab.core.nativelib

import com.pixellab.core.convert.QuantizeResult

/**
 * JNI bridge to the native color quantizer (median cut / k-means / octree).
 *
 * The Kotlin facades in `com.pixellab.core.convert` own validation and
 * fallback; this object is a thin, exact-signature mirror of the C++ entry
 * points. Never call these functions without checking [isAvailable].
 */
object NativeQuantizer {

    /** True when the native library is linked and quantizer symbols exist. */
    val isAvailable: Boolean
        get() = NativeLib.available

    /**
     * Quantizes opaque pixels of a row-major ARGB array down to
     * [targetColors] palette entries using the algorithm with [algorithmId]
     * (`0 = MEDIAN_CUT`, `1 = KMEANS`, `2 = OCTREE`).
     *
     * Semantics match the Kotlin fallback: transparent pixels
     * (`alpha < 0x80`) are excluded from palette statistics and preserved
     * unchanged in `mappedPixels`; palette entries are opaque.
     *
     * @throws IllegalArgumentException on malformed input or when the image
     * yields no quantizable colors (caller falls back).
     * @throws UnsatisfiedLinkError when the library is not loaded.
     */
    fun quantize(pixels: IntArray, targetColors: Int, algorithmId: Int): QuantizeResult {
        val palette = IntArray(targetColors)
        val mapped = IntArray(pixels.size)
        val count = nativeQuantize(pixels, pixels.size, targetColors, algorithmId, palette, mapped)
        if (count <= 0) {
            throw IllegalArgumentException("Native quantization produced no colors")
        }
        return QuantizeResult(palette.copyOf(count), mapped)
    }

    private external fun nativeQuantize(
        pixels: IntArray, pixelCount: Int, targetColors: Int, algorithmId: Int,
        outPalette: IntArray, outMappedPixels: IntArray,
    ): Int
}
