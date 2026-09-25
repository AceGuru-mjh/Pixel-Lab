package com.pixellab.core.io

import com.pixellab.core.export.PngCodec
import com.pixellab.core.model.PixelFrame

/**
 * Windows ICO (.ico) writer for Pixel Lab rasters.
 *
 * Layout: a 6-byte `ICONDIR` (reserved `0x0000`, type `0x0001` = icon,
 * image count), one 16-byte `ICONDIRENTRY` per image, then the image
 * payloads back to back. Each payload is a complete PNG produced by the
 * existing [PngCodec] encoder (8-bit RGBA, single `IDAT`) — the PNG-in-ICO
 * encoding every Windows shell since Vista renders natively, and the one
 * `BitmapFactory`/`ImageIO` accept without a legacy BMP header dance.
 *
 * Entry fields: width/height bytes (`0` encodes 256), color count 0
 * (truecolor), reserved 0, planes 1, bit count 32, payload length and the
 * absolute offset of the payload.
 *
 * ## Scaling
 *
 * Sizes are produced with a dedicated nearest-neighbor resampler
 * (`out(x, y) = src((x * srcW) / dstW, (y * srcH) / dstH)`, floor division)
 * rather than `TransformOps.scaleNearest`, which only supports integer
 * upscaling — ICO generation is dominated by downscale steps. Nearest
 * neighbor is deliberate: pixel-art edges stay hard, no interpolation halos,
 * deterministic output for identical input.
 */
object IcoEncoder {

    /** Largest icon dimension; stored as the byte value 0 in directory entries. */
    private const val MAX_SIZE = 256

    /**
     * Encodes [frame] at every size in [sizes], largest-first order
     * recommended but any order is preserved as given.
     *
     * @param sizes target edge lengths in pixels, `1..256` each.
     * @param frame source raster.
     * @return ICO file bytes.
     * @throws IllegalArgumentException when [sizes] is empty or contains a
     * value outside `1..256`.
     */
    fun encode(sizes: List<Int>, frame: PixelFrame): ByteArray =
        encode(sizes, List(sizes.size) { frame })

    /**
     * Encodes one ICO whose entry `i` shows [frames]`[i]` at
     * [sizes]`[i]` — for icon sets authored at several resolutions.
     *
     * @param sizes target edge lengths in pixels, `1..256` each.
     * @param frames rasters, one per entry, same length as [sizes].
     * @return ICO file bytes.
     * @throws IllegalArgumentException when [sizes] is empty, sizes and
     * frames differ in length, or a size is outside `1..256`.
     */
    fun encode(sizes: List<Int>, frames: List<PixelFrame>): ByteArray {
        require(sizes.isNotEmpty()) { "ICO needs at least one size (got an empty list)" }
        require(sizes.size == frames.size) {
            "ICO sizes (${sizes.size}) and frames (${frames.size}) counts differ"
        }
        for (index in sizes.indices) {
            require(sizes[index] in 1..MAX_SIZE) {
                "ICO size ${sizes[index]} at index $index outside 1..$MAX_SIZE"
            }
        }

        // Encode payloads first: entry offsets depend on their exact lengths.
        val payloads = Array(sizes.size) { index ->
            PngCodec.encode(resampleNearest(frames[index], sizes[index], sizes[index]))
        }
        val headerSize = 6 + 16 * sizes.size

        val out = BinaryWriter(headerSize + payloads.sumOf { it.size })
        // ICONDIR
        out.u16Le(0) // reserved
        out.u16Le(1) // type: icon
        out.u16Le(sizes.size)
        var offset = headerSize
        for (index in sizes.indices) {
            val size = sizes[index]
            val dimensionByte = if (size == MAX_SIZE) 0 else size
            // ICONDIRENTRY
            out.u8(dimensionByte) // width in pixels (0 = 256)
            out.u8(dimensionByte) // height in pixels (0 = 256)
            out.u8(0) // color count: 0 = truecolor
            out.u8(0) // reserved
            out.u16Le(1) // color planes
            out.u16Le(32) // bits per pixel
            out.u32Le(payloads[index].size) // bytes in resource
            out.u32Le(offset) // payload offset from file start
            offset += payloads[index].size
        }
        for (payload in payloads) out.bytes(payload)
        return out.toByteArray()
    }

    /**
     * Nearest-neighbor resample of [frame] to [width] x [height] via
     * floor-mapped source coordinates. Pure function of the input raster;
     * identity when dimensions already match.
     */
    internal fun resampleNearest(frame: PixelFrame, width: Int, height: Int): PixelFrame {
        require(width > 0 && height > 0) { "resample dimensions must be positive (${width}x${height})" }
        if (frame.width == width && frame.height == height) return frame
        val src = frame.pixels
        val srcW = frame.width
        val srcH = frame.height
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val sourceY = (y.toLong() * srcH / height).toInt()
            for (x in 0 until width) {
                val sourceX = (x.toLong() * srcW / width).toInt()
                out[y * width + x] = src[sourceY * srcW + sourceX]
            }
        }
        return PixelFrame.of(width, height, out)
    }
}
