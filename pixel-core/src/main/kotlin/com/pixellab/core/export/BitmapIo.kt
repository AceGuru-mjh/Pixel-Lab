package com.pixellab.core.export

import android.graphics.Bitmap
import com.pixellab.core.convert.ImageData
import com.pixellab.core.model.PixelFrame

/**
 * Boundary bridge between Android [Bitmap]s and the JVM-pure pixel-core
 * raster types.
 *
 * This object is one of the only places in pixel-core allowed to touch
 * `android.graphics`; everything downstream of it works on plain
 * [PixelFrame]/[ImageData] rasters so the core stays unit-testable on the
 * JVM. Both directions are side-effect free with respect to caller-owned
 * bitmaps: reading never recycles the source, and any temporary copy made
 * for a non-ARGB_8888 input is recycled before returning.
 */
object BitmapIo {

    /** Inclusive range of accepted integer upscale factors. */
    private val SCALE_RANGE = 1..16

    /**
     * Reads a whole [bitmap] into a platform-neutral [ImageData] raster.
     *
     * Non-`ARGB_8888` bitmaps are first copied into an `ARGB_8888`
     * intermediate — the caller's bitmap is never mutated or recycled — and
     * the copy is recycled as soon as the pixels have been read. The pixels
     * are fetched in one full-frame [Bitmap.getPixels] call, so the result is
     * a row-major `0xAARRGGBB` array exactly `width * height` long.
     *
     * @param bitmap source bitmap; may be in any config.
     * @return the bitmap's pixels wrapped as [ImageData].
     */
    fun toPixels(bitmap: Bitmap): ImageData {
        val readable = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        try {
            val width = readable.width
            val height = readable.height
            require(width > 0 && height > 0) {
                "Bitmap dimensions must be positive (${width}x${height})"
            }
            val pixels = IntArray(width * height)
            readable.getPixels(pixels, 0, width, 0, 0, width, height)
            return ImageData(width, height, pixels)
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    /**
     * Renders [frame] as an immutable `ARGB_8888` [Bitmap].
     *
     * With [scale] `&gt; 1` the base bitmap is upscaled with
     * [Bitmap.createScaledBitmap] using `filter = false`, which keeps the
     * hard pixel-art edges of integer nearest-neighbor replication.
     *
     * @param frame raster to render; pixels are packed `0xAARRGGBB`.
     * @param scale integer upscale factor, `1..16`.
     * @return a bitmap sized `frame.width * scale x frame.height * scale`.
     * @throws IllegalArgumentException if [scale] is outside `1..16` or the
     * scaled dimensions would exceed the 31-bit Bitmap dimension limit.
     */
    fun frameToBitmap(frame: PixelFrame, scale: Int = 1): Bitmap {
        require(scale in SCALE_RANGE) { "scale must be in 1..16 (was $scale)" }
        val scaledWidth = frame.width.toLong() * scale
        val scaledHeight = frame.height.toLong() * scale
        require(scaledWidth in 1..Int.MAX_VALUE.toLong() && scaledHeight in 1..Int.MAX_VALUE.toLong()) {
            "Scaled bitmap ${scaledWidth}x${scaledHeight} exceeds the 31-bit dimension limit"
        }
        val base = Bitmap.createBitmap(
            frame.pixels,
            frame.width,
            frame.height,
            Bitmap.Config.ARGB_8888,
        )
        if (scale == 1) return base
        return Bitmap.createScaledBitmap(
            base,
            scaledWidth.toInt(),
            scaledHeight.toInt(),
            false,
        )
    }
}
