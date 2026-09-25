package com.pixellab.core.nativelib

/**
 * JNI bridge to the native bulk pixel operations: batch writes, scanline
 * flood fill and layer compositing.
 *
 * All functions return fresh arrays (the caller may keep the input); the C++
 * implementations are allocation-conscious and work directly on flat
 * row-major ARGB buffers.
 */
object NativePixelOps {

    /** True when the native library is linked and pixel-op symbols exist. */
    val isAvailable: Boolean
        get() = NativeLib.available

    /**
     * Batch pixel write: [points] holds flattened `(x, y)` pairs. Out-of-range
     * coordinates are skipped. Returns a new array even when nothing matched.
     */
    fun setPixelsBatch(pixels: IntArray, width: Int, height: Int, points: IntArray, argb: Int): IntArray {
        val out = IntArray(pixels.size)
        val written = nativeSetPixelsBatch(pixels, width, height, points, points.size, argb, out)
        require(written >= 0) { "Native batch write failed" }
        return out
    }

    /**
     * Scanline flood fill from (`x`, `y`) with per-channel tolerance
     * (max abs diff, alpha included). An out-of-bounds seed returns the input
     * copied unchanged. Tolerance 0 takes the exact-color fast path.
     */
    fun floodFill(pixels: IntArray, width: Int, height: Int, x: Int, y: Int, replacement: Int, tolerance: Int): IntArray {
        val out = IntArray(pixels.size)
        nativeFloodFill(pixels, width, height, x, y, replacement, tolerance, out)
        return out
    }

    /**
     * Composites a stack of same-size layers (bottom-up) with per-layer
     * opacity into one flat ARGB array. Result alpha is straight (not
     * premultiplied).
     */
    fun compositeLayers(layerPixels: Array<IntArray>, widths: IntArray, heights: IntArray, opacities: FloatArray): IntArray {
        require(layerPixels.isNotEmpty()) { "compositeLayers needs at least one layer" }
        val first = layerPixels[0]
        val out = IntArray(first.size)
        val flat = IntArray(layerPixels.size)
        for (i in layerPixels.indices) flat[i] = layerPixels[i].size
        val ok = nativeCompositeLayers(layerPixels, flat, widths, heights, opacities, layerPixels.size, out)
        require(ok == 0) { "Native composite failed" }
        return out
    }

    private external fun nativeSetPixelsBatch(
        pixels: IntArray, width: Int, height: Int, points: IntArray, pointCount: Int, argb: Int, out: IntArray,
    ): Int

    private external fun nativeFloodFill(
        pixels: IntArray, width: Int, height: Int, x: Int, y: Int, replacement: Int, tolerance: Int, out: IntArray,
    ): Int

    private external fun nativeCompositeLayers(
        layers: Array<IntArray>, layerSizes: IntArray, widths: IntArray, heights: IntArray,
        opacities: FloatArray, layerCount: Int, out: IntArray,
    ): Int
}
