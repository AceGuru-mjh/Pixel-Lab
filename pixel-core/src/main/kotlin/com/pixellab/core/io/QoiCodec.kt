package com.pixellab.core.io

import com.pixellab.core.model.PixelFrame

/**
 * Raised by [QoiCodec] when a QOI byte stream violates the 1.0
 * specification: bad magic, unsupported channels/colorspace, a missing or
 * wrong end marker, opcodes running past the pixel budget or trailing
 * garbage. Messages carry the absolute byte offset where decoding failed.
 */
class QoiDecodeException(message: String) : IllegalArgumentException(message)

/**
 * Result of decoding a QOI file with its header metadata.
 */
data class QoiDecoded(
    /** Decoded raster, `width x height`, row-major ARGB. */
    val frame: PixelFrame,
    /** Header channels field: 3 (RGB) or 4 (RGBA). */
    val channels: Int,
    /** Header colorspace field: 0 (sRGB with linear alpha) or 1 (all linear). */
    val colorspace: Int,
)

/**
 * Pure-JVM QOI 1.0 codec — encoder and decoder in one object, byte-true to
 * the reference specification (`qoi.h`).
 *
 * File layout: 14-byte header (`qoif` magic, width/height u32 BE, channels,
 * colorspace), a stream of 2-bit-tagged opcodes, and the 8-byte end marker
 * `00 00 00 00 00 00 00 01`.
 *
 * Opcodes (tags in the two top bits):
 * * `11xxxxxx` **RUN** — repeat the previous pixel `(x & 0x3F) + 1` times
 *   (the decoder accepts runs of 1..64; the encoder emits at most 62, the
 *   reference encoder's cap);
 * * `00xxxxxx` **INDEX** — the pixel at 64-entry cache slot `x`;
 * * `01xxxxxx` **DIFF** — 2-bit biased deltas `-2..1` for R, G and B;
 * * `10xxxxxx` **LUMA** — 6-bit biased green delta `-32..31` plus a second
 *   byte with 4-bit biased R/B deltas relative to green;
 * * `11111110` **RGB** — three literal bytes, alpha fixed at 255;
 * * `11111111` **RGBA** — four literal bytes.
 *
 * The 64-slot index cache is keyed by
 * `(r*3 + g*5 + b*7 + a*11) mod 64`, starts as all-zero colors and is
 * updated after every literal / diff / luma / run pixel exactly once.
 *
 * The decoder is a strict byte-exact state machine: it produces exactly
 * `width * height` pixels, consumes every opcode byte, and then requires the
 * 8-byte end marker with nothing after it. The encoder mirrors the reference
 * implementation's choice order (RUN, INDEX, RGBA-if-alpha-changed, DIFF,
 * LUMA, RGB) and is a pure function of the pixel content, so
 * `encode(decode(encode(f)))` is byte-identical to `encode(f)`.
 */
object QoiCodec {

    /** 4-byte QOI magic. */
    private val MAGIC = "qoif".toByteArray(Charsets.US_ASCII)

    /** 8-byte end marker: seven zero bytes then `0x01`. */
    private val END_MARKER = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)

    /** Index cache slot count. */
    private const val CACHE_SIZE = 64

    /** Maximum run length the encoder emits (reference cap). */
    private const val MAX_RUN = 62

    /** Total pixel count guard: raster must fit a flat IntArray. */
    private const val MAX_PIXELS = Int.MAX_VALUE

    /**
     * Encodes [frame] as a complete QOI file.
     *
     * @param frame raster to encode; pixels are packed `0xAARRGGBB`.
     * @param withAlpha `true` (default) writes a 4-channel header preserving
     * alpha; `false` writes 3 channels and discards alpha — decoded pixels
     * come back fully opaque (documented lossy choice).
     * @return QOI bytes: header, opcode stream, end marker.
     */
    fun encode(frame: PixelFrame, withAlpha: Boolean = true): ByteArray {
        val channels = if (withAlpha) 4 else 3
        val pixels = frame.pixels
        val out = BinaryWriter(16 + pixels.size + 8)
        out.bytes(MAGIC)
        out.u32Be(frame.width)
        out.u32Be(frame.height)
        out.u8(channels)
        out.u8(0) // colorspace: sRGB with linear alpha — the common choice

        val index = IntArray(CACHE_SIZE) // all-zero colors, per spec
        var previous = 0xFF shl 24 // 0,0,0,255 — the spec's initial pixel
        var run = 0

        for (argb in pixels) {
            // In RGB mode the alpha plane is constant: fold it to opaque so
            // run/index/diff comparisons see pure RGB equality.
            val pixel = if (withAlpha) argb else (argb or (0xFF shl 24))
            if (pixel == previous) {
                run++
                if (run == MAX_RUN) {
                    out.u8(0xC0 or (run - 1))
                    run = 0
                }
                continue
            }
            if (run > 0) {
                out.u8(0xC0 or (run - 1))
                run = 0
            }
            val hash = pixelHash(pixel)
            if (index[hash] == pixel) {
                out.u8(0x00 or hash)
            } else {
                index[hash] = pixel
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val a = (pixel ushr 24) and 0xFF
                val prevA = (previous ushr 24) and 0xFF
                if (a != prevA) {
                    out.u8(0xFF)
                    out.u8(r)
                    out.u8(g)
                    out.u8(b)
                    out.u8(a)
                } else {
                    val dr = r - ((previous shr 16) and 0xFF)
                    val dg = g - ((previous shr 8) and 0xFF)
                    val db = b - (previous and 0xFF)
                    if (dr in -2..1 && dg in -2..1 && db in -2..1) {
                        out.u8(0x40 or ((dr + 2) shl 4) or ((dg + 2) shl 2) or (db + 2))
                    } else {
                        val vr = dr - dg
                        val vb = db - dg
                        if (dg in -32..31 && vr in -8..7 && vb in -8..7) {
                            out.u8(0x80 or (dg + 32))
                            out.u8(((vr + 8) shl 4) or (vb + 8))
                        } else {
                            out.u8(0xFE)
                            out.u8(r)
                            out.u8(g)
                            out.u8(b)
                        }
                    }
                }
            }
            previous = pixel
        }
        if (run > 0) out.u8(0xC0 or (run - 1))
        out.bytes(END_MARKER)
        return out.toByteArray()
    }

    /**
     * Decodes a QOI file into its raster (header fields discarded).
     *
     * @throws QoiDecodeException on any spec violation described in the class
     * documentation.
     */
    fun decode(bytes: ByteArray): PixelFrame = decodeWithHeader(bytes).frame

    /**
     * Decodes a QOI file, returning the raster plus the header's channels
     * and colorspace fields.
     *
     * @throws QoiDecodeException on any spec violation.
     */
    fun decodeWithHeader(bytes: ByteArray): QoiDecoded {
        try {
            return decodeInternal(bytes)
        } catch (error: QoiDecodeException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw QoiDecodeException("Malformed QOI: ${error.message}")
        }
    }

    // ---- decoder -------------------------------------------------------------

    private fun decodeInternal(bytes: ByteArray): QoiDecoded {
        if (bytes.size < 14 + 8) {
            throw QoiDecodeException("QOI file too short: ${bytes.size} bytes (minimum 22)")
        }
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw QoiDecodeException("Not a QOI: bad 4-byte magic")
        }
        val width = u32BeAt(bytes, 4)
        val height = u32BeAt(bytes, 8)
        if (width <= 0 || height <= 0) {
            throw QoiDecodeException("QOI dimensions ${width}x${height} are non-positive")
        }
        if (width > MAX_PIXELS || height > MAX_PIXELS || width.toLong() * height > MAX_PIXELS) {
            throw QoiDecodeException("QOI raster $width x $height exceeds the IntArray limit")
        }
        val channels = bytes[12].toInt() and 0xFF
        if (channels != 3 && channels != 4) {
            throw QoiDecodeException("QOI channels $channels outside {3, 4}")
        }
        val colorspace = bytes[13].toInt() and 0xFF
        if (colorspace != 0 && colorspace != 1) {
            throw QoiDecodeException("QOI colorspace $colorspace outside {0, 1}")
        }

        val endStart = bytes.size - 8
        if (!bytes.copyOfRange(endStart, bytes.size).contentEquals(END_MARKER)) {
            throw QoiDecodeException("QOI end marker missing or wrong at offset $endStart")
        }

        val total = (width * height).toInt()
        val out = IntArray(total)
        val index = IntArray(CACHE_SIZE)
        var previous = 0xFF shl 24
        var pos = 14
        var pixelPos = 0

        while (pixelPos < total) {
            if (pos >= endStart) {
                throw QoiDecodeException(
                    "QOI opcodes exhausted at offset $pos before $total pixels were produced ($pixelPos)"
                )
            }
            val b1 = bytes[pos++].toInt() and 0xFF
            val pixel: Int = when {
                b1 == 0xFE -> {
                    if (pos + 3 > endStart) throw QoiDecodeException("Truncated RGB opcode at offset ${pos - 1}")
                    val r = bytes[pos++].toInt() and 0xFF
                    val g = bytes[pos++].toInt() and 0xFF
                    val b = bytes[pos++].toInt() and 0xFF
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                b1 == 0xFF -> {
                    if (pos + 4 > endStart) throw QoiDecodeException("Truncated RGBA opcode at offset ${pos - 1}")
                    val r = bytes[pos++].toInt() and 0xFF
                    val g = bytes[pos++].toInt() and 0xFF
                    val b = bytes[pos++].toInt() and 0xFF
                    val a = bytes[pos++].toInt() and 0xFF
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }

                b1 and 0xC0 == 0x00 -> index[b1 and 0x3F]

                b1 and 0xC0 == 0x40 -> {
                    val pr = (previous shr 16) and 0xFF
                    val pg = (previous shr 8) and 0xFF
                    val pb = previous and 0xFF
                    val pa = previous ushr 24
                    val r = (pr + ((b1 shr 4) and 0x03) - 2) and 0xFF
                    val g = (pg + ((b1 shr 2) and 0x03) - 2) and 0xFF
                    val b = (pb + (b1 and 0x03) - 2) and 0xFF
                    (pa shl 24) or (r shl 16) or (g shl 8) or b
                }

                b1 and 0xC0 == 0x80 -> {
                    if (pos >= endStart) throw QoiDecodeException("Truncated LUMA opcode at offset ${pos - 1}")
                    val b2 = bytes[pos++].toInt() and 0xFF
                    val pr = (previous shr 16) and 0xFF
                    val pg = (previous shr 8) and 0xFF
                    val pb = previous and 0xFF
                    val pa = previous ushr 24
                    val vg = (b1 and 0x3F) - 32
                    val r = (pr + vg - 8 + ((b2 shr 4) and 0x0F)) and 0xFF
                    val g = (pg + vg) and 0xFF
                    val b = (pb + vg - 8 + (b2 and 0x0F)) and 0xFF
                    (pa shl 24) or (r shl 16) or (g shl 8) or b
                }

                else -> { // RUN: repeat the previous pixel
                    val run = (b1 and 0x3F) + 1
                    if (pixelPos + run > total) {
                        throw QoiDecodeException(
                            "QOI RUN of $run overflows the pixel budget at offset ${pos - 1} " +
                                "($pixelPos of $total pixels written)"
                        )
                    }
                    repeat(run) { out[pixelPos++] = previous }
                    continue
                }
            }

            out[pixelPos++] = pixel
            index[pixelHash(pixel)] = pixel
            previous = pixel
        }
        if (pos != endStart) {
            throw QoiDecodeException(
                "QOI stream has ${endStart - pos} unconsumed opcode byte(s) after the last pixel " +
                    "(offset $pos of $endStart)"
            )
        }
        return QoiDecoded(
            frame = PixelFrame.of(width, height, out),
            channels = channels,
            colorspace = colorspace,
        )
    }

    // ---- shared helpers ------------------------------------------------------

    /** QOI index-cache hash of a packed ARGB pixel. */
    private fun pixelHash(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val a = (argb ushr 24) and 0xFF
        return (r * 3 + g * 5 + b * 7 + a * 11) % CACHE_SIZE
    }

    /** Reads a big-endian u32 at [offset] as an Int (may be negative). */
    private fun u32BeAt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
}
