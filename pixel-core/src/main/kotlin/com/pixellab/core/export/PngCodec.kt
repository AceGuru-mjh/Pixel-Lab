package com.pixellab.core.export

import com.pixellab.core.model.PixelFrame
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Pure-JVM PNG writer for [PixelFrame] rasters.
 *
 * Output layout (fully self-contained and deterministic for identical input):
 * 1. the 8-byte PNG signature;
 * 2. one `IHDR` chunk — bit depth 8, color type 6 (truecolor with alpha);
 * 3. one `IDAT` chunk holding the complete zlib stream (deflate level
 *    [Deflater.BEST_COMPRESSION]);
 * 4. one empty `IEND` chunk.
 *
 * Every chunk is `length (u32 BE) + type (4 ASCII) + data + CRC32 (u32 BE)`
 * with the CRC covering `type + data`. The CRC-32 table is computed by hand
 * (reflected, polynomial `0xEDB88320`) instead of delegating to
 * [java.util.zip.CRC32], keeping the byte layout auditable end to end.
 *
 * Raw scanline payload: each row is a `0x00` (None) filter prefix followed by
 * `width * scale` RGBA quadruples in `R, G, B, A` byte order. Integer
 * upscaling is nearest-neighbor by construction — the 4 RGBA bytes of each
 * source pixel are repeated `scale` times horizontally and each source row is
 * emitted `scale` times — so scaled output keeps crisp pixel-art edges with
 * no interpolation or resampling.
 */
object PngCodec {

    /** PNG file signature: the 8 magic bytes every decoder checks first. */
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Inclusive range of accepted integer upscale factors. */
    private val SCALE_RANGE = 1..16

    /** Deflate working-buffer size. */
    private const val DEFLATE_BUFFER_SIZE = 65536

    /**
     * CRC-32 lookup table (reflected, polynomial `0xEDB88320`), built once at
     * first use. Hand-rolled so the codec never depends on
     * [java.util.zip.CRC32].
     */
    private val CRC_TABLE: IntArray = IntArray(256) { n ->
        var c = n
        for (bit in 0 until 8) {
            c = if (c and 1 == 1) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1
        }
        c
    }

    /**
     * Encodes [frame] as a complete PNG file (signature through `IEND`).
     *
     * @param frame raster to encode; pixels are packed `0xAARRGGBB`, stored
     * row-major.
     * @param scale integer nearest-neighbor upscale factor, `1..16`.
     * @return PNG bytes: signature, `IHDR`, a single `IDAT`, `IEND`.
     * @throws IllegalArgumentException if [scale] is outside `1..16` or the
     * scaled raster would overflow the 31-bit PNG dimension limit or the
     * `ByteArray` size limit.
     */
    fun encode(frame: PixelFrame, scale: Int = 1): ByteArray {
        val raw = rawRgbaRows(frame, scale)
        val idat = deflate(raw)
        // rawRgbaRows has already validated the scaled dimensions, so the
        // Int multiplications below cannot overflow.
        val scaledWidth = frame.width * scale
        val scaledHeight = frame.height * scale
        val out = ByteArrayOutputStream(64 + idat.size)
        writeSignature(out)
        writeIhdr(out, scaledWidth, scaledHeight)
        writeChunk(out, "IDAT", idat)
        writeIend(out)
        return out.toByteArray()
    }

    /**
     * Returns the raw deflate (zlib) payload of the single `IDAT` chunk that
     * [encode] emits for [frame] at [scale].
     *
     * APNG writers reuse this stream as the frame-data body of `fdAT` chunks
     * (prefixed with a 4-byte sequence number), which guarantees APNG frame
     * data and plain PNG `IDAT` data stay byte-identical for the same raster
     * and scale.
     *
     * @param frame raster whose scanlines should be compressed.
     * @param scale integer nearest-neighbor upscale factor, `1..16`.
     * @return the exact bytes [encode] writes into its `IDAT` chunk data.
     * @throws IllegalArgumentException under the same conditions as [encode].
     */
    fun idatStream(frame: PixelFrame, scale: Int = 1): ByteArray =
        deflate(rawRgbaRows(frame, scale))

    /**
     * Appends the 8-byte PNG signature to [out]. Shared with the APNG writer
     * so both formats agree on the prologue.
     */
    internal fun writeSignature(out: ByteArrayOutputStream) {
        out.write(PNG_SIGNATURE)
    }

    /**
     * Appends an `IHDR` chunk describing an 8-bit RGBA raster of
     * [width] x [height]: bit depth 8, color type 6, zlib compression,
     * adaptive filtering (we always emit type None), no interlace.
     */
    internal fun writeIhdr(out: ByteArrayOutputStream, width: Int, height: Int) {
        val data = ByteArray(13)
        putU32BE(data, 0, width)
        putU32BE(data, 4, height)
        data[8] = 0x08 // bit depth: 8 bits per channel
        data[9] = 0x06 // color type: truecolor with alpha (RGBA)
        data[10] = 0x00 // compression method: zlib/deflate
        data[11] = 0x00 // filter method: standard (per-row filter bytes)
        data[12] = 0x00 // interlace method: none
        writeChunk(out, "IHDR", data)
    }

    /** Appends the terminal `IEND` chunk (empty payload) to [out]. */
    internal fun writeIend(out: ByteArrayOutputStream) {
        writeChunk(out, "IEND", ByteArray(0))
    }

    /**
     * Appends one PNG chunk to [out]: `length (u32 BE) + type + data +
     * CRC32 (u32 BE)`, with the CRC computed over `type + data`.
     *
     * @param type exactly four ASCII bytes, e.g. `"IDAT"`.
     * @param data chunk payload; may be empty.
     */
    internal fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        require(typeBytes.size == 4) { "PNG chunk type must be 4 ASCII bytes: \"$type\"" }
        val chunk = ByteArray(12 + data.size)
        putU32BE(chunk, 0, data.size)
        System.arraycopy(typeBytes, 0, chunk, 4, 4)
        System.arraycopy(data, 0, chunk, 8, data.size)
        putU32BE(chunk, 8 + data.size, crc32(typeBytes, data))
        out.write(chunk)
    }

    /** Writes [value] as an unsigned 16-bit big-endian pair at [offset]. */
    internal fun putU16BE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    /** Writes [value] as an unsigned 32-bit big-endian quad at [offset]. */
    internal fun putU32BE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    /**
     * Builds the unfiltered scanline payload: `height * scale` rows, each one
     * `0x00` filter byte followed by `width * scale` RGBA quadruples. Scaling
     * is pure byte duplication (pixel x `scale`, row x `scale`).
     *
     * All overflow checks run in `Long` space before any `Int` arithmetic.
     */
    private fun rawRgbaRows(frame: PixelFrame, scale: Int): ByteArray {
        require(scale in SCALE_RANGE) { "scale must be in 1..16 (was $scale)" }
        val width = frame.width
        val height = frame.height
        val scaledWidth = width.toLong() * scale
        val scaledHeight = height.toLong() * scale
        require(scaledWidth in 1..Int.MAX_VALUE.toLong() && scaledHeight in 1..Int.MAX_VALUE.toLong()) {
            "Scaled PNG size ${scaledWidth}x${scaledHeight} exceeds the 31-bit dimension limit"
        }
        val totalBytes = (scaledWidth * 4L + 1L) * scaledHeight
        require(totalBytes <= Int.MAX_VALUE.toLong()) {
            "Raw PNG scanline payload of $totalBytes bytes exceeds the ByteArray limit"
        }
        val pixels = frame.pixels
        val out = ByteArray(totalBytes.toInt())
        var o = 0
        for (y in 0 until height) {
            val rowStart = y * width
            for (repetition in 0 until scale) {
                out[o++] = 0x00 // filter type None
                for (x in 0 until width) {
                    val argb = pixels[rowStart + x]
                    val r = ((argb shr 16) and 0xFF).toByte()
                    val g = ((argb shr 8) and 0xFF).toByte()
                    val b = (argb and 0xFF).toByte()
                    val a = ((argb ushr 24) and 0xFF).toByte()
                    var copy = 0
                    while (copy < scale) {
                        out[o++] = r
                        out[o++] = g
                        out[o++] = b
                        out[o++] = a
                        copy++
                    }
                }
            }
        }
        return out
    }

    /**
     * Deflates [raw] into a complete zlib stream at
     * [Deflater.BEST_COMPRESSION]. The deflater is always [Deflater.end]-ed,
     * even on failure.
     */
    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 2 + 64)
            val buffer = ByteArray(DEFLATE_BUFFER_SIZE)
            while (!deflater.finished()) {
                val written = deflater.deflate(buffer)
                if (written > 0) out.write(buffer, 0, written)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** CRC-32 (reflected) of `type + data`, returned as the on-disk Int. */
    private fun crc32(type: ByteArray, data: ByteArray): Int {
        var c = -1 // 0xFFFFFFFF
        for (byte in type) c = CRC_TABLE[(c xor byte.toInt()) and 0xFF] xor (c ushr 8)
        for (byte in data) c = CRC_TABLE[(c xor byte.toInt()) and 0xFF] xor (c ushr 8)
        return c xor -1
    }
}
