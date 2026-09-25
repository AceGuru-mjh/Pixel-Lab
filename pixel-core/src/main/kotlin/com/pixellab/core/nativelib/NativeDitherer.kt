package com.pixellab.core.nativelib

/**
 * JNI bridge to the native ditherer (Floyd-Steinberg, Atkinson, Bayer
 * matrices 2x2/4x4/8x8, checkerboard).
 *
 * Semantics match the Kotlin fallback: transparent pixels
 * (`alpha < 0x80`) keep their original alpha; opaque pixels map to the
 * nearest palette color preserving their alpha; intensity is clamped to
 * `[0, 1]` with NaN treated as 0.
 */
object NativeDitherer {

    /** True when the native library is linked and ditherer symbols exist. */
    val isAvailable: Boolean
        get() = NativeLib.available

    /**
     * Applies ordered or error-diffusion dithering to a row-major ARGB array
     * snapped onto [palette] with [algorithmId]
     * (`0 = NONE`, `1 = FLOYD_STEINBERG`, `2 = ATKINSON`, `3..5 = BAYER`,
     * `6 = CHECKERBOARD`).
     *
     * @throws IllegalArgumentException on malformed input.
     * @throws UnsatisfiedLinkError when the library is not loaded.
     */
    fun applyDither(
        pixels: IntArray, width: Int, height: Int,
        palette: IntArray, algorithmId: Int, intensity: Float,
    ): IntArray {
        val out = IntArray(pixels.size)
        nativeDither(pixels, width, height, palette, palette.size, algorithmId, intensity, out)
        return out
    }

    private external fun nativeDither(
        pixels: IntArray, width: Int, height: Int,
        palette: IntArray, paletteCount: Int,
        algorithmId: Int, intensity: Float, outPixels: IntArray,
    )
}
