package com.pixellab.core.io

import java.util.zip.CRC32
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hostile-input regression tests for the decoder memory ceilings.
 *
 * Every decoder ingests untrusted bytes (the MCP io_import tools feed
 * client base64 straight in), so a tiny file must never be able to ask
 * for a multi-gigabyte raster. Each test crafts the *minimal* file that
 * used to reach the giant allocation and asserts the decoder now fails
 * with its own decode exception instead of OOM/Overflow errors.
 */
class IoHardeningTest {

    // ---- PNG -----------------------------------------------------------------

    /** Minimal PNG: signature + one chunk + IEND, with correct CRCs. */
    private fun pngWithIhdr(width: Int, height: Int, colorType: Int = 6, depth: Int = 8): ByteArray {
        var out = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        fun chunk(type: String, payload: ByteArray) {
            val len = payload.size
            val header = byteArrayOf(
                (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
            ) + type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32()
            crc.update(type.toByteArray(Charsets.US_ASCII))
            crc.update(payload)
            val crcValue = crc.value.toInt()
            out += header + payload + byteArrayOf(
                (crcValue ushr 24).toByte(), (crcValue ushr 16).toByte(),
                (crcValue ushr 8).toByte(), crcValue.toByte(),
            )
        }
        val ihdr = byteArrayOf(
            (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
            depth.toByte(), colorType.toByte(), 0, 0, 0,
        )
        chunk("IHDR", ihdr)
        chunk("IEND", byteArrayOf())
        return out
    }

    @Test
    fun `png with oversized ihdr is rejected by the budget, not by an OOM`() {
        try {
            PngDecoder.decode(pngWithIhdr(20000, 20000))
            throw AssertionError("20000x20000 IHDR must be rejected")
        } catch (expected: PngDecodeException) {
            assertTrue(expected.message!!.contains("20000"))
        }
    }

    @Test
    fun `png with an oversized single edge is rejected`() {
        try {
            PngDecoder.decode(pngWithIhdr(17000, 1))
            throw AssertionError("17000-wide IHDR must be rejected")
        } catch (expected: PngDecodeException) {
            assertTrue(expected.message!!.contains("16384"))
        }
    }

    // ---- GIF -----------------------------------------------------------------

    /** Minimal GIF header carrying a logical screen of w x h. */
    private fun gifHeader(width: Int, height: Int): ByteArray {
        val out = "GIF89a".toByteArray(Charsets.US_ASCII)
        fun u16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())
        return out + u16(width) + u16(height) + byteArrayOf(0, 0, 0) + byteArrayOf(0x3B)
    }

    @Test
    fun `gif with a 65535x65535 logical screen is rejected from a 14 byte file`() {
        try {
            GifDecoder.decode(gifHeader(65535, 65535))
            throw AssertionError("65535x65535 logical screen must be rejected")
        } catch (expected: GifDecodeException) {
            // The budget fired instead of the historical IntArray overflow /
            // 4.3 GB allocation request.
            assertTrue(expected.message!!.contains("budget") || expected.message!!.contains("ceiling"))
        }
    }

    @Test
    fun `gif with an oversized logical screen is rejected`() {
        try {
            GifDecoder.decode(gifHeader(17000, 1))
            throw AssertionError("17000-wide logical screen must be rejected")
        } catch (expected: GifDecodeException) {
            assertTrue(expected.message!!.contains("16384"))
        }
    }

    // ---- QOI -----------------------------------------------------------------

    private fun qoiHeader(width: Int, height: Int): ByteArray {
        fun u32(value: Int) = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
        )
        val end = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1)
        return "qoif".toByteArray(Charsets.US_ASCII) + u32(width) + u32(height) + byteArrayOf(4, 0) + end
    }

    @Test
    fun `qoi header claiming 40 million pixels is rejected`() {
        try {
            QoiCodec.decode(qoiHeader(65536, 65536))
            throw AssertionError("65536x65536 QOI must be rejected")
        } catch (expected: QoiDecodeException) {
            assertTrue(expected.message!!.contains("exceeds"))
        }
    }

    // ---- BMP -----------------------------------------------------------------

    private fun bmpHeader(width: Int, height: Int, dibSize: Int = 40, fileSize: Int? = null): ByteArray {
        val declared = fileSize ?: (14 + dibSize)
        fun u32(value: Long) = byteArrayOf(
            (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
        )
        fun u16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())
        // BITMAPINFOHEADER layout with the declared size field first (the
        // V4/V5 cases must declare 108/124 or the truncation probe lies).
        val dib = u32(dibSize.toLong()) + u32(width.toLong()) + u32(height.toLong()) +
            u16(1) + u16(32) + u32(0L) + u32(0L) + u32(1L) + u32(1L) +
            u32(0L) + u32(0L)
        var header = byteArrayOf(0x42, 0x4D) + u32(declared.toLong()) + u32(0L) + u32((14 + dibSize).toLong())
        header += dib
        // Pad to the declared DIB size (V4/V5 keep extra mask fields).
        while (header.size < 14 + dibSize) header += 0.toByte()
        return header
    }

    @Test
    fun `bmp with a 2^28-wide raster is rejected`() {
        // 0x10000000 * 32bpp wrapped rowSize to 0 historically, defeating
        // the truncation check and allocating a huge raster.
        try {
            BmpCodec.decode(bmpHeader(0x10000000, 1, dibSize = 40, fileSize = 14 + 40))
            throw AssertionError("2^28-wide BMP must be rejected")
        } catch (expected: BmpDecodeException) {
            assertTrue(expected.message!!.contains("16384") || expected.message!!.contains("ceiling"))
        }
    }

    @Test
    fun `bmp v5 header truncated before the mask fields is a decode error`() {
        // Declares a 124-byte DIB but only ships the 40-byte core: the mask
        // reads used to run past the buffer (AIOOBE escaping the contract).
        // Build the full 138-byte V5 header, declare only the 62 bytes we
        // actually ship, and cut the rest: declared size matches, the
        // pixel offset stays inside the file, and the DIB header claims
        // 124 bytes that never arrive.
        val file = bmpHeader(4, 4, dibSize = 124, fileSize = 14 + 40 + 8).copyOf(14 + 40 + 8)
        file[10] = 54.toByte(); file[11] = 0; file[12] = 0; file[13] = 0
        try {
            BmpCodec.decode(file)
            throw AssertionError("truncated V5 header must be rejected")
        } catch (expected: BmpDecodeException) {
            assertTrue(expected.message!!.contains("truncated"))
        }
    }

    // ---- Aseprite ------------------------------------------------------------

    private fun aseHeader(width: Int, height: Int): ByteArray {
        fun u16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())
        val out = ArrayList<Byte>()
        fun add(bytes: ByteArray) = bytes.forEach { out.add(it) }
        add(byteArrayOf(128.toByte(), 0, 0, 0)) // file size (ignored by the parser)
        add(u16(0xA5E0)) // magic
        add(u16(1)) // frames
        add(u16(width))
        add(u16(height))
        add(u16(32)) // depth
        add(ByteArray(4)) // flags
        add(u16(100)) // speed
        add(ByteArray(16)) // reserved
        add(u16(0)) // transparent palette index
        add(ByteArray(8)) // color hints
        add(u16(32)) // color count
        add(u16(1)) // pixel ratio w
        add(u16(1)) // pixel ratio h
        add(ByteArray(4)) // grid x
        add(ByteArray(4)) // grid y
        add(u16(16)) // grid w
        add(u16(16)) // grid h
        while (out.size < 128) out.add(0)
        return out.toByteArray()
    }

    @Test
    fun `aseprite with an oversized canvas is rejected`() {
        try {
            AsepriteImporter.import(aseHeader(16385, 1))
            throw AssertionError("16385-wide Aseprite canvas must be rejected")
        } catch (expected: AsepriteDecodeException) {
            assertTrue(expected.message!!.contains("16384") || expected.message!!.contains("ceiling"))
        }
    }
}
