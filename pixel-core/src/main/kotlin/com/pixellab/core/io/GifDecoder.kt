package com.pixellab.core.io

import com.pixellab.core.model.PixelFrame

/**
 * Raised by [GifDecoder] when the byte stream violates the GIF87a/89a
 * structure: bad header, missing color table, invalid LZW codes, truncated
 * sub-blocks or a missing image. Messages carry the absolute byte offset (or
 * bit position for LZW) where the violation was detected.
 */
class GifDecodeException(message: String) : IllegalArgumentException(message)

/**
 * One composited GIF frame rendered onto the full logical screen.
 *
 * Compositing follows the frame's disposal method against the previous
 * canvas state, so the ARGB raster is what a conforming viewer would display
 * — background is always fully transparent (`0x00000000`), never the GIF
 * background color index.
 */
data class GifFrame(
    /** Frame raster sized `logicalWidth x logicalHeight`, row-major ARGB. */
    val frame: PixelFrame,
    /** Graphic Control delay in centiseconds, as stored in the file. */
    val delayCs: Int,
    /** Raw disposal method `0..7` of the Graphic Control Extension. */
    val disposalMethod: Int,
    /** Transparent palette index of this frame's GCE, or null when none. */
    val transparentIndex: Int?,
)

/**
 * Result of decoding a GIF87a/89a byte stream.
 */
data class GifDecoded(
    /** Logical Screen Descriptor width in pixels. */
    val logicalWidth: Int,
    /** Logical Screen Descriptor height in pixels. */
    val logicalHeight: Int,
    /** Composited frames in playback order; at least one. */
    val frames: List<GifFrame>,
    /**
     * Global Color Table as opaque ARGB integers (`0xFFRRGGBB`) in table
     * order (size `2^(n+1)` entries), or null when the file has no GCT.
     */
    val globalColors: List<Int>?,
    /**
     * NETSCAPE2.0 loop count, or null when the file carries no loop
     * extension. `0` means "loop forever".
     */
    val loopCount: Int?,
    /** Whether any Graphic Control Extension declared a transparent index. */
    val hasTransparency: Boolean,
)

/**
 * Pure-JVM GIF87a/89a decoder — the reading counterpart of
 * `com.pixellab.core.export.KotlinGifEncoder`.
 *
 * Supported structure:
 * * headers `GIF87a` / `GIF89a`;
 * * Logical Screen Descriptor with optional Global Color Table (packed size
 *   field `0..7` → `2^(n+1)` entries);
 * * Application Extensions, including `NETSCAPE2.0` loop counts;
 * * Comment (`0xFE`) and Plain Text (`0x01`) extensions (skipped, structure
 *   validated);
 * * Graphic Control Extensions (disposal `0..7`, delay, transparent index);
 * * Image Descriptors with optional Local Color Table and interlace flag;
 * * LZW image data with minimum code size `2..8`, code widths growing 9..12
 *   bits, clear-code table resets, and the decoder's table trailing the
 *   encoder's by one entry (width switches when `next >= 1 shl width`);
 * * de-interlacing in the four standard passes (rows 0,8 / 4,8 / 2,4 / 1,2);
 * * disposal semantics when compositing to the full logical canvas:
 *   disposal 2 clears the previous frame's rectangle to transparent and
 *   disposal 3 restores the snapshot taken before that frame was drawn;
 *   undefined disposals 4..7 are surfaced raw but composite like disposal 1
 *   ("do not dispose"), matching common viewer behavior.
 *
 * Images may extend past the logical screen (they are clipped, as viewers
 * do). Decoding is deterministic and allocation-safe: every read goes through
 * [BinaryReader] bound checks.
 */
object GifDecoder {

    /** GIF block-introducing byte: extension. */
    private const val BLOCK_EXTENSION = 0x21

    /** GIF block-introducing byte: image descriptor. */
    private const val BLOCK_IMAGE = 0x2C

    /** GIF trailer. */
    private const val BLOCK_TRAILER = 0x3B

    /** LZW dictionary capacity; codes fit in 12 bits. */
    private const val MAX_DICT_CODES = 4096

    /** Highest LZW code width. */
    private const val MAX_CODE_WIDTH = 12

    /** Interlace pass row starts (index = pass). */
    private val PASS_ROW_START = intArrayOf(0, 4, 2, 1)

    /** Interlace pass row steps (index = pass). */
    private val PASS_ROW_STEP = intArrayOf(8, 8, 4, 2)

    /**
     * Decodes a complete GIF byte stream.
     *
     * @param bytes GIF87a or GIF89a file contents.
     * @return the logical screen size, composited frames, global palette,
     * loop count and transparency flag.
     * @throws GifDecodeException on any structural violation, including
     * truncated data, missing color tables, invalid LZW codes and GIFs with
     * no image data. Byte positions in messages are absolute offsets into
     * [bytes].
     */
    fun decode(bytes: ByteArray): GifDecoded {
        try {
            return decodeInternal(bytes)
        } catch (error: GifDecodeException) {
            throw error
        } catch (error: IllegalArgumentException) {
            // Bound-check failures from BinaryReader, re-labelled as GIF errors.
            throw GifDecodeException("Malformed GIF: ${error.message}")
        }
    }

    // ---- top-level block walk ------------------------------------------------

    private fun decodeInternal(bytes: ByteArray): GifDecoded {
        val r = BinaryReader(bytes)
        val header = r.ascii(6)
        if (header != "GIF87a" && header != "GIF89a") {
            throw GifDecodeException("Not a GIF: header \"$header\" (expected GIF87a or GIF89a)")
        }
        val logicalWidth = r.u16Le()
        val logicalHeight = r.u16Le()
        if (logicalWidth == 0 || logicalHeight == 0) {
            throw GifDecodeException(
                "Logical screen ${logicalWidth}x${logicalHeight} has a zero axis"
            )
        }
        val packed = r.u8()
        val gctPresent = (packed and 0x80) != 0
        val gctSize = 2 shl (packed and 0x07)
        r.u8() // background color index: unused, canvas background is transparent
        r.u8() // pixel aspect ratio: unused

        val globalColors: IntArray? = if (gctPresent) readColorTable(r, gctSize) else null

        var loopCount: Int? = null
        val frames = ArrayList<GifFrame>()
        var sawTransparency = false

        // Per-image GCE state (applies to the image that follows it).
        var pendingDisposal = 0
        var pendingDelay = 0
        var pendingTransparent: Int? = null

        // Compositing state across images.
        var canvas = IntArray(logicalWidth * logicalHeight)
        var prevRect: Rect? = null
        var prevSnapshot: IntArray? = null
        var prevDisposal = -1
        var imageCount = 0

        walk@ while (true) {
            val sentinel = r.u8()
            when (sentinel) {
                BLOCK_TRAILER -> break@walk

                BLOCK_EXTENSION -> {
                    val label = r.u8()
                    when (label) {
                        0xF9 -> { // Graphic Control Extension
                            val size = r.u8()
                            if (size != 4) {
                                throw GifDecodeException(
                                    "Graphic Control Extension block size $size != 4 at offset ${r.position - 1}"
                                )
                            }
                            val gcePacked = r.u8()
                            pendingDisposal = (gcePacked shr 2) and 0x07
                            pendingDelay = r.u16Le()
                            val transparentFlag = gcePacked and 0x01
                            val transparentByte = r.u8()
                            pendingTransparent = if (transparentFlag != 0) transparentByte else null
                            if (transparentFlag != 0) sawTransparency = true
                            r.u8() // block terminator
                        }

                        0xFF -> { // Application Extension
                            val size = r.u8()
                            if (size != 11) {
                                throw GifDecodeException(
                                    "Application Extension block size $size != 11 at offset ${r.position - 1}"
                                )
                            }
                            val appId = r.ascii(8)
                            r.bytes(3) // authentication code
                            val blocks = readSubBlocks(r, "Application Extension \"$appId\"")
                            if (appId == "NETSCAPE" && blocks.size == 1 &&
                                blocks[0].size == 3 && blocks[0][0] == 0x01.toByte()
                            ) {
                                loopCount =
                                    (blocks[0][1].toInt() and 0xFF) or
                                        ((blocks[0][2].toInt() and 0xFF) shl 8)
                            }
                        }

                        0xFE, 0x01 -> readSubBlocks(r, "extension 0x${label.toString(16)}")

                        else -> readSubBlocks(r, "unknown extension 0x${label.toString(16)}")
                    }
                }

                BLOCK_IMAGE -> {
                    val left = r.u16Le()
                    val top = r.u16Le()
                    val width = r.u16Le()
                    val height = r.u16Le()
                    if (width == 0 || height == 0) {
                        throw GifDecodeException(
                            "Image Descriptor ${width}x${height} has a zero axis at offset ${r.position - 4}"
                        )
                    }
                    val imagePacked = r.u8()
                    val lctPresent = (imagePacked and 0x80) != 0
                    val interlaced = (imagePacked and 0x40) != 0
                    val lctSize = 2 shl (imagePacked and 0x07)
                    val colors: IntArray = when {
                        lctPresent -> readColorTable(r, lctSize)
                        globalColors != null -> globalColors
                        else -> throw GifDecodeException(
                            "Image at offset ${r.position - 9} has neither local nor global color table"
                        )
                    }
                    val minCodeSize = r.u8()
                    if (minCodeSize !in 2..8) {
                        throw GifDecodeException(
                            "LZW minimum code size $minCodeSize outside 2..8 at offset ${r.position - 1}"
                        )
                    }
                    val lzwData = concat(readSubBlocks(r, "image LZW data"))
                    val indices = lzwDecode(lzwData, width * height, minCodeSize, imageCount, width, height)
                    val ordered = if (interlaced) deInterlace(indices, width, height) else indices

                    // Apply the previous frame's disposal before drawing.
                    when (prevDisposal) {
                        2 -> prevRect?.let { clearRect(canvas, it, logicalWidth) }
                        3 -> prevSnapshot?.let { canvas = it.copyOf() }
                    }
                    val snapshotBefore = if (pendingDisposal == 3) canvas.copyOf() else null

                    val transparentIndex = pendingTransparent
                    drawImage(
                        canvas, logicalWidth, logicalHeight,
                        ordered, width, height, left, top,
                        colors, transparentIndex, imageCount,
                    )
                    frames.add(
                        GifFrame(
                            frame = PixelFrame.of(logicalWidth, logicalHeight, canvas.copyOf()),
                            delayCs = pendingDelay,
                            disposalMethod = pendingDisposal,
                            transparentIndex = transparentIndex,
                        )
                    )
                    prevDisposal = pendingDisposal
                    prevRect = Rect(left, top, width, height)
                    prevSnapshot = snapshotBefore
                    imageCount++

                    // GCE state applies to exactly one image; reset to defaults.
                    pendingDisposal = 0
                    pendingDelay = 0
                    pendingTransparent = null
                }

                else -> throw GifDecodeException(
                    "Unknown block introducer 0x${sentinel.toString(16).padStart(2, '0')} " +
                        "at offset ${r.position - 1}"
                )
            }
        }
        if (imageCount == 0) {
            throw GifDecodeException("GIF contains no image data (trailer reached at offset ${r.position})")
        }
        if (r.remaining() > 0) {
            throw GifDecodeException(
                "${r.remaining()} trailing byte(s) after GIF trailer at offset ${r.position}"
            )
        }
        return GifDecoded(
            logicalWidth = logicalWidth,
            logicalHeight = logicalHeight,
            frames = frames,
            globalColors = globalColors?.toList(),
            loopCount = loopCount,
            hasTransparency = sawTransparency,
        )
    }

    // ---- helpers -------------------------------------------------------------

    /** Reads a color table of [size] entries as opaque ARGB integers. */
    private fun readColorTable(r: BinaryReader, size: Int): IntArray {
        val table = IntArray(size)
        for (i in 0 until size) {
            val red = r.u8()
            val green = r.u8()
            val blue = r.u8()
            table[i] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return table
    }

    /**
     * Reads a chain of data sub-blocks: `[len][len bytes]...[0x00]`. The
     * terminator is mandatory — a chain that ends mid-length fails.
     */
    private fun readSubBlocks(r: BinaryReader, what: String): List<ByteArray> {
        val parts = ArrayList<ByteArray>()
        while (true) {
            val size = r.u8()
            if (size == 0) return parts
            parts.add(r.bytes(size))
            if (parts.size > 1_000_000) {
                throw GifDecodeException("More than 1,000,000 sub-blocks in $what at offset ${r.position}")
            }
        }
    }

    /** Concatenates sub-block payloads into one flat array. */
    private fun concat(parts: List<ByteArray>): ByteArray {
        var total = 0
        for (part in parts) total += part.size
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    /** Clipping rectangle in logical-screen coordinates. */
    private class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    /** Clears [rect] on [canvas] (row stride [stride]) to transparent. */
    private fun clearRect(canvas: IntArray, rect: Rect, stride: Int) {
        val right = minOf(stride, rect.x + rect.width)
        val bottom = minOf(canvas.size / stride, rect.y + rect.height)
        for (y in maxOf(0, rect.y) until bottom) {
            val rowStart = y * stride
            for (x in maxOf(0, rect.x) until right) canvas[rowStart + x] = 0
        }
    }

    /**
     * Draws one decoded image's index raster onto [canvas] at (`left`,`top`),
     * clipping to the logical screen. Out-of-range palette indices are a
     * structural error (strict), transparent indices leave the canvas pixel
     * untouched.
     */
    private fun drawImage(
        canvas: IntArray,
        canvasWidth: Int,
        canvasHeight: Int,
        indices: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        left: Int,
        top: Int,
        colors: IntArray,
        transparentIndex: Int?,
        imageIndex: Int,
    ) {
        for (y in 0 until imageHeight) {
            val canvasY = top + y
            if (canvasY < 0 || canvasY >= canvasHeight) continue
            val rowStart = canvasY * canvasWidth
            for (x in 0 until imageWidth) {
                val canvasX = left + x
                if (canvasX < 0 || canvasX >= canvasWidth) continue
                val index = indices[y * imageWidth + x].toInt() and 0xFF
                if (index == transparentIndex) continue
                if (index >= colors.size) {
                    throw GifDecodeException(
                        "Palette index $index out of range for ${colors.size}-entry table " +
                            "in image $imageIndex at pixel ($x, $y)"
                    )
                }
                canvas[rowStart + canvasX] = colors[index]
            }
        }
    }

    /**
     * De-interlaces a row-major index raster using the four standard GIF
     * passes: rows 0,8,16..., then 4,12,20..., then 2,6,10..., then 1,3,5...
     */
    private fun deInterlace(indices: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(indices.size)
        var sourceRow = 0
        for (pass in PASS_ROW_START.indices) {
            var y = PASS_ROW_START[pass]
            while (y < height) {
                System.arraycopy(indices, sourceRow * width, out, y * width, width)
                sourceRow++
                y += PASS_ROW_STEP[pass]
            }
        }
        require(sourceRow == height) { "interlace passes consumed $sourceRow of $height rows" }
        return out
    }

    // ---- LZW -----------------------------------------------------------------

    /**
     * GIF-LZW decompressor for one image's concatenated sub-block payload.
     *
     * Codes are packed LSB-first; the initial code width is `minCodeSize + 1`
     * and grows to 12 bits as the dictionary fills. The decoder's dictionary
     * trails the encoder's by one entry, so the width switch happens exactly
     * when `next >= 1 shl width` — the mirror of the encoder's
     * `nextCode > 1 shl width` test. KwKwK codes (`code == next`) are
     * expanded as `string(old) + first(string(old))`. A full dictionary
     * (4096 entries) simply stops growing until the next CLEAR.
     *
     * Decoding stops after [expectedPixels] bytes: a stream that runs out of
     * bits or hits EOI first is truncated and rejected; a stream producing
     * too many bytes is rejected by the per-byte overflow check.
     */
    private fun lzwDecode(
        data: ByteArray,
        expectedPixels: Int,
        minCodeSize: Int,
        imageIndex: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val out = ByteArray(expectedPixels)
        var outPos = 0
        var codeWidth = minCodeSize + 1
        var next = eoi + 1
        var oldCode = -1
        val prefix = IntArray(MAX_DICT_CODES)
        val suffix = ByteArray(MAX_DICT_CODES)
        val stack = ByteArray(MAX_DICT_CODES)
        var bitPos = 0
        val totalBits = data.size * 8L

        fun readCode(): Int {
            if (bitPos + codeWidth > totalBits) {
                throw GifDecodeException(
                    "LZW stream of image $imageIndex (${width}x${height}) truncated at bit $bitPos " +
                        "(needs $codeWidth more of $totalBits bits)"
                )
            }
            val byteIndex = bitPos shr 3
            val bitOffset = bitPos and 7
            var value = (data[byteIndex].toInt() and 0xFF) ushr bitOffset
            if (codeWidth + bitOffset > 8) {
                value = value or ((data[byteIndex + 1].toInt() and 0xFF) shl (8 - bitOffset))
            }
            if (codeWidth + bitOffset > 16) {
                value = value or ((data[byteIndex + 2].toInt() and 0xFF) shl (16 - bitOffset))
            }
            bitPos += codeWidth
            return value and ((1 shl codeWidth) - 1)
        }

        while (outPos < expectedPixels) {
            val code = readCode()
            if (code == eoi) {
                throw GifDecodeException(
                    "LZW EOI before all ${expectedPixels} pixels of image $imageIndex " +
                        "(${width}x${height}) were produced ($outPos decoded)"
                )
            }
            if (code == clear) {
                codeWidth = minCodeSize + 1
                next = eoi + 1
                oldCode = -1
                continue
            }
            if (oldCode == -1) {
                if (code >= clear) {
                    throw GifDecodeException(
                        "First LZW code 0x${code.toString(16)} after CLEAR is not a root " +
                            "(roots are 0..${clear - 1}) in image $imageIndex"
                    )
                }
                out[outPos++] = code.toByte()
                oldCode = code
                continue
            }
            if (code >= next && code != next) {
                throw GifDecodeException(
                    "LZW code $code exceeds the dictionary frontier $next in image $imageIndex"
                )
            }
            if (code == next && next >= MAX_DICT_CODES) {
                throw GifDecodeException(
                    "LZW code $code references the reserved frontier in a full dictionary " +
                        "in image $imageIndex"
                )
            }

            val isKwKwK = code == next
            val walk = if (isKwKwK) oldCode else code
            var stackPos = 0
            var cur = walk
            while (cur >= clear) {
                stack[stackPos++] = suffix[cur]
                if (stackPos >= MAX_DICT_CODES) {
                    throw GifDecodeException("LZW string chain overflow in image $imageIndex")
                }
                cur = prefix[cur]
            }
            val firstChar = cur
            stack[stackPos++] = firstChar.toByte() // root byte: first char of the string
            while (stackPos > 0) {
                if (outPos >= expectedPixels) {
                    throw GifDecodeException(
                        "LZW stream produces more than ${expectedPixels} bytes in image $imageIndex"
                    )
                }
                out[outPos++] = stack[--stackPos]
            }
            if (isKwKwK) {
                if (outPos >= expectedPixels) {
                    throw GifDecodeException(
                        "LZW stream produces more than ${expectedPixels} bytes in image $imageIndex"
                    )
                }
                out[outPos++] = firstChar.toByte()
            }

            if (next < MAX_DICT_CODES) {
                prefix[next] = oldCode
                suffix[next] = firstChar.toByte()
                next++
                if (next >= (1 shl codeWidth) && codeWidth < MAX_CODE_WIDTH) {
                    codeWidth++
                }
            }
            oldCode = code
        }
        return out
    }
}
