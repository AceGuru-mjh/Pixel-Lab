package com.pixellab.core.nativelib

/**
 * JNI bridge to the native GIF89a encoder (LZW, per-frame local palettes,
 * quantization + optional dithering inside native code).
 *
 * The Kotlin [com.pixellab.core.export.KotlinGifEncoder] mirrors the byte
 * layout; Exporter prefers this path when native is available and falls back
 * otherwise. Byte output is NOT guaranteed identical between paths — each is
 * independently deterministic.
 */
object NativeGifEncoder {

    /** True when the native library is linked and GIF symbols exist. */
    val isAvailable: Boolean
        get() = NativeLib.available

    /**
     * Encodes [framePixels] (each a `width x height` row-major ARGB array)
     * with [delaysMs] per frame and [loopCount] (0 = forever).
     * `quantAlgorithmId` / `ditherId` use the enum ids of
     * `QuantizeAlgorithm` / `DitherAlgorithm`.
     *
     * @throws IllegalArgumentException on malformed input.
     * @throws UnsatisfiedLinkError when the library is not loaded.
     */
    fun encode(
        width: Int, height: Int, framePixels: Array<IntArray>,
        delaysMs: IntArray, loopCount: Int, quantAlgorithmId: Int, ditherId: Int,
    ): ByteArray {
        require(framePixels.isNotEmpty()) { "No frames to encode" }
        require(framePixels.size == delaysMs.size) { "frames (${framePixels.size}) != delays (${delaysMs.size})" }
        val sizes = IntArray(framePixels.size) { framePixels[it].size }
        val encoded = nativeEncodeGif(width, height, framePixels, sizes, framePixels.size, delaysMs, loopCount, quantAlgorithmId, ditherId)
        require(encoded != null && encoded.isNotEmpty()) { "Native GIF encoding failed" }
        return encoded
    }

    private external fun nativeEncodeGif(
        width: Int, height: Int, frames: Array<IntArray>, frameSizes: IntArray, frameCount: Int,
        delaysMs: IntArray, loopCount: Int, quantAlgorithmId: Int, ditherId: Int,
    ): ByteArray?
}
