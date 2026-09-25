package com.pixellab.core.io

import com.pixellab.core.model.PixelFrame

/**
 * Raised by [BmpCodec] when a byte stream violates the BMP structure: wrong
 * magic, unsupported DIB header size or bit depth, compression other than
 * `BI_RGB`/`BI_BITFIELDS`, non-contiguous channel masks or pixel data that
 * runs past the end of the file. Messages carry absolute offsets.
 */
class BmpDecodeException(message: String) : IllegalArgumentException(message)

/**
 * Pure-JVM BMP codec for 24/32-bpp Windows bitmaps — the reading and
 * writing counterpart of the Android-side `BitmapIo` path for hosts that
 * only have raw bytes.
 *
 * **Encoding** always emits the most portable flavour: `BM` file header, a
 * 40-byte `BITMAPINFOHEADER`, 32 bpp `BI_RGB` BGRA pixel data, positive
 * height (bottom-up row order).
 *
 * **Decoding** supports:
 * * `BITMAPINFOHEADER` (40 bytes), `BITMAPV4HEADER` (108) and
 *   `BITMAPV5HEADER` (124);
 * * bit counts 24 and 32 — paletted depths (`<= 8` bpp) are rejected with a
 *   descriptive error (palette import belongs to the GIF/Aseprite paths);
 * * `BI_RGB`: 24 bpp rows of `B, G, R` triplets with 4-byte row alignment
 *   padding, 32 bpp BGRA where the fourth byte is padding → alpha forced to
 *   255 (per the classic spec reading);
 * * `BI_BITFIELDS` (compression 3): explicit R/G/B masks — three masks
 *   right after a 40-byte header, four masks at their fixed V4/V5 offsets
 *   (alpha mask 0 → fully opaque). V4/V5 files that carry non-zero masks
 *   alongside `BI_RGB` are honored too, since many writers fill them in;
 * * bottom-up (positive height) and top-down (negative height) row order;
 * * arbitrary contiguous masks, scaled to 8-bit per channel
 *   (`(v * 255 + max / 2) / max`).
 */
object BmpCodec {

    /** BITMAPINFOHEADER size in bytes. */
    private const val DIB_INFO = 40

    /** BITMAPV4HEADER size in bytes. */
    private const val DIB_V4 = 108

    /** BITMAPV5HEADER size in bytes. */
    private const val DIB_V5 = 124

    /** File header size in bytes. */
    private const val FILE_HEADER_SIZE = 14

    /** `BI_RGB` compression value. */
    private const val BI_RGB = 0

    /** `BI_BITFIELDS` compression value. */
    private const val BI_BITFIELDS = 3

    /**
     * Encodes [frame] as a 32-bpp `BI_RGB` BMP (BGRA, bottom-up,
     * `BITMAPINFOHEADER`).
     *
     * @param frame raster to encode; pixels are packed `0xAARRGGBB`.
     * @return BMP file bytes.
     */
    fun encode(frame: PixelFrame): ByteArray {
        val width = frame.width
        val height = frame.height
        val pixelBytes = width.toLong() * height * 4L
        require(pixelBytes + 54L <= Int.MAX_VALUE) {
            "BMP pixel payload of $pixelBytes bytes exceeds the ByteArray limit"
        }
        val pixels = frame.pixels
        val out = BinaryWriter(54 + pixelBytes.toInt())

        // BITMAPFILEHEADER: BM, size, reserved, reserved, pixel offset.
        out.ascii("BM")
        out.u32Le(54 + pixelBytes.toInt())
        out.u16Le(0)
        out.u16Le(0)
        out.u32Le(54)

        // BITMAPINFOHEADER.
        out.u32Le(DIB_INFO)
        out.i32Le(width)
        out.i32Le(height) // positive: rows stored bottom-up
        out.u16Le(1) // planes
        out.u16Le(32) // bits per pixel
        out.u32Le(BI_RGB)
        out.u32Le(pixelBytes.toInt())
        out.i32Le(0) // horizontal resolution (unspecified)
        out.i32Le(0) // vertical resolution (unspecified)
        out.u32Le(0) // colors used (no palette)
        out.u32Le(0) // important colors

        // Pixel data: bottom-up rows, BGRA byte order.
        for (y in height - 1 downTo 0) {
            val rowStart = y * width
            for (x in 0 until width) {
                val argb = pixels[rowStart + x]
                out.u8(argb and 0xFF) // B
                out.u8((argb shr 8) and 0xFF) // G
                out.u8((argb shr 16) and 0xFF) // R
                out.u8((argb ushr 24) and 0xFF) // A
            }
        }
        return out.toByteArray()
    }

    /**
     * Decodes a 24 or 32 bpp BMP file.
     *
     * @param bytes BMP file contents.
     * @return the raster, row-major ARGB, in visual (top-first) orientation
     * regardless of the file's row order.
     * @throws BmpDecodeException on any structural violation described in
     * the class documentation.
     */
    fun decode(bytes: ByteArray): PixelFrame {
        try {
            return decodeInternal(bytes)
        } catch (error: BmpDecodeException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw BmpDecodeException("Malformed BMP: ${error.message}")
        }
    }

    // ---- decoder -------------------------------------------------------------

    private fun decodeInternal(bytes: ByteArray): PixelFrame {
        if (bytes.size < FILE_HEADER_SIZE + DIB_INFO) {
            throw BmpDecodeException(
                "BMP file too short: ${bytes.size} bytes (minimum ${FILE_HEADER_SIZE + DIB_INFO})"
            )
        }
        if (bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) {
            throw BmpDecodeException("Not a BMP: magic is not \"BM\"")
        }
        val declaredSize = u32LeAt(bytes, 2).toLong() and 0xFFFFFFFF
        if (declaredSize != bytes.size.toLong()) {
            throw BmpDecodeException(
                "BMP header declares $declaredSize file bytes but the buffer holds ${bytes.size}"
            )
        }
        val pixelOffset = u32LeAt(bytes, 10).toLong() and 0xFFFFFFFF
        if (pixelOffset < FILE_HEADER_SIZE || pixelOffset > bytes.size) {
            throw BmpDecodeException(
                "BMP pixel data offset $pixelOffset outside the file (size ${bytes.size})"
            )
        }

        val dibSize = u32LeAt(bytes, FILE_HEADER_SIZE)
        if (dibSize != DIB_INFO && dibSize != DIB_V4 && dibSize != DIB_V5) {
            throw BmpDecodeException(
                "Unsupported DIB header size $dibSize (supported: $DIB_INFO, $DIB_V4, $DIB_V5)"
            )
        }
        val width = i32LeAt(bytes, FILE_HEADER_SIZE + 4)
        val rawHeight = i32LeAt(bytes, FILE_HEADER_SIZE + 8)
        if (width <= 0 || rawHeight == 0) {
            throw BmpDecodeException("BMP dimensions ${width}x$rawHeight are invalid")
        }
        val topDown = rawHeight < 0
        val height = kotlin.math.abs(rawHeight)
        val planes = u16LeAt(bytes, FILE_HEADER_SIZE + 12)
        if (planes != 1) {
            throw BmpDecodeException("BMP color planes $planes != 1")
        }
        val bitCount = u16LeAt(bytes, FILE_HEADER_SIZE + 14)
        if (bitCount != 24 && bitCount != 32) {
            throw BmpDecodeException(
                "BMP bit depth $bitCount unsupported (24 and 32 only; paletted depths " +
                    "belong to the GIF/Aseprite import paths)"
            )
        }
        val compression = u32LeAt(bytes, FILE_HEADER_SIZE + 16)
        if (compression != BI_RGB && compression != BI_BITFIELDS) {
            throw BmpDecodeException(
                "BMP compression $compression unsupported (BI_RGB = $BI_RGB, BI_BITFIELDS = $BI_BITFIELDS)"
            )
        }

        // Channel masks: explicit for BI_BITFIELDS, honored when V4/V5 files
        // carry non-zero masks with BI_RGB, defaults otherwise.
        var redMask = 0
        var greenMask = 0
        var blueMask = 0
        var alphaMask = 0
        if (compression == BI_BITFIELDS) {
            if (dibSize == DIB_INFO) {
                // Three DWORDs immediately after the 40-byte header.
                val maskStart = FILE_HEADER_SIZE + DIB_INFO
                if (maskStart + 12 > pixelOffset) {
                    throw BmpDecodeException("BI_BITFIELDS masks at offset $maskStart overlap the pixel data")
                }
                redMask = u32LeAt(bytes, maskStart)
                greenMask = u32LeAt(bytes, maskStart + 4)
                blueMask = u32LeAt(bytes, maskStart + 8)
                alphaMask = 0 // classic Win32: alpha byte is padding, i.e. opaque
            } else {
                redMask = u32LeAt(bytes, FILE_HEADER_SIZE + 40)
                greenMask = u32LeAt(bytes, FILE_HEADER_SIZE + 44)
                blueMask = u32LeAt(bytes, FILE_HEADER_SIZE + 48)
                alphaMask = u32LeAt(bytes, FILE_HEADER_SIZE + 52)
            }
            if (redMask == 0 || greenMask == 0 || blueMask == 0) {
                throw BmpDecodeException(
                    "BI_BITFIELDS requires non-zero R/G/B masks (found R=$redMask, G=$greenMask, B=$blueMask)"
                )
            }
            if (compression == BI_BITFIELDS && bitCount == 24) {
                throw BmpDecodeException("BI_BITFIELDS is not defined for 24 bpp data")
            }
        } else if (dibSize != DIB_INFO) {
            redMask = u32LeAt(bytes, FILE_HEADER_SIZE + 40)
            greenMask = u32LeAt(bytes, FILE_HEADER_SIZE + 44)
            blueMask = u32LeAt(bytes, FILE_HEADER_SIZE + 48)
            alphaMask = u32LeAt(bytes, FILE_HEADER_SIZE + 52)
            if (redMask == 0 || greenMask == 0 || blueMask == 0) {
                // Unfilled mask fields with BI_RGB: fall back to byte order.
                redMask = 0
                greenMask = 0
                blueMask = 0
                alphaMask = 0
            }
        }
        val useMasks = redMask != 0

        val rowSize = (bitCount * width + 31) / 32 * 4
        val raster = IntArray(width * height)
        val pixelsStart = pixelOffset.toInt()
        if (pixelsStart.toLong() + rowSize.toLong() * height > bytes.size) {
            throw BmpDecodeException(
                "BMP pixel data truncated: needs ${rowSize * height} bytes at offset $pixelsStart " +
                    "but only ${bytes.size - pixelsStart} remain"
            )
        }

        if (useMasks) {
            val redShift = maskShift(redMask)
            val greenShift = maskShift(greenMask)
            val blueShift = maskShift(blueMask)
            val alphaShift = if (alphaMask == 0) -1 else maskShift(alphaMask)
            val redMax = (redMask ushr redShift)
            val greenMax = (greenMask ushr greenShift)
            val blueMax = (blueMask ushr blueShift)
            val alphaMax = if (alphaMask == 0) 0 else (alphaMask ushr alphaShift)
            for (y in 0 until height) {
                val sourceRow = if (topDown) y else height - 1 - y
                var rowPos = pixelsStart + sourceRow * rowSize
                for (x in 0 until width) {
                    val word = u32LeAt(bytes, rowPos)
                    rowPos += 4
                    val r = scaleChannel((word ushr redShift) and redMax, redMax)
                    val g = scaleChannel((word ushr greenShift) and greenMax, greenMax)
                    val b = scaleChannel((word ushr blueShift) and blueMax, blueMax)
                    val a = if (alphaMask == 0) 255 else scaleChannel((word ushr alphaShift) and alphaMax, alphaMax)
                    raster[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        } else if (bitCount == 32) {
            for (y in 0 until height) {
                val sourceRow = if (topDown) y else height - 1 - y
                var rowPos = pixelsStart + sourceRow * rowSize
                for (x in 0 until width) {
                    val b = bytes[rowPos].toInt() and 0xFF
                    val g = bytes[rowPos + 1].toInt() and 0xFF
                    val r = bytes[rowPos + 2].toInt() and 0xFF
                    // Fourth byte is padding in classic BI_RGB 32 bpp: opaque.
                    raster[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    rowPos += 4
                }
            }
        } else {
            for (y in 0 until height) {
                val sourceRow = if (topDown) y else height - 1 - y
                val rowPos = pixelsStart + sourceRow * rowSize
                for (x in 0 until width) {
                    val at = rowPos + x * 3
                    val b = bytes[at].toInt() and 0xFF
                    val g = bytes[at + 1].toInt() and 0xFF
                    val r = bytes[at + 2].toInt() and 0xFF
                    raster[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return PixelFrame.of(width, height, raster)
    }

    /** Least significant set bit position of a contiguous channel mask. */
    private fun maskShift(mask: Int): Int {
        require(mask.countOneBits() > 0) { "mask $mask has no bits" }
        val expected = mask shr mask.countTrailingZeroBits()
        if (expected.countOneBits() != mask.countOneBits()) {
            throw BmpDecodeException("Channel mask 0x${mask.toString(16)} is not contiguous")
        }
        return mask.countTrailingZeroBits()
    }

    /** Scales a masked channel value to `0..255` (identity for 8-bit masks). */
    private fun scaleChannel(value: Int, max: Int): Int = when {
        max == 255 -> value
        max == 0 -> 0
        else -> (value * 255 + max / 2) / max
    }

    /** Reads a little-endian u32 at [offset] (raw Int, bits preserved). */
    private fun u32LeAt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** Reads a little-endian u16 at [offset]. */
    private fun u16LeAt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** Reads a little-endian i32 at [offset]. */
    private fun i32LeAt(bytes: ByteArray, offset: Int): Int = u32LeAt(bytes, offset)
}
