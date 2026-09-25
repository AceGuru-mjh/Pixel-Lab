package com.pixellab.core.io

import com.pixellab.core.model.PixelFrame
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Raised by [PngDecoder] when the byte stream violates the PNG or APNG
 * structure: bad signature, bad CRC, missing IHDR/PLTE/IDAT/IEND, invalid
 * color type / bit depth combinations, filter bytes outside `0..4`, zlib
 * errors or wrong inflated sizes. Messages name the offending chunk and its
 * absolute file offset.
 */
class PngDecodeException(message: String) : IllegalArgumentException(message)

/**
 * One decoded frame of a PNG (static) or APNG (animated) file.
 */
data class PngFrame(
    /** Frame raster sized `IHDR.width x IHDR.height`, row-major ARGB. */
    val frame: PixelFrame,
    /**
     * `fcTL` delay in whole milliseconds, or null for a static PNG
     * (no `fcTL`) — `delay_num / delay_den` with `delay_den == 0` read as
     * the spec default 100, rounded half-up.
     */
    val delayMs: Int?,
)

/**
 * Result of decoding a PNG byte stream: one frame for a plain PNG, the
 * animation frames (composited onto the full IHDR canvas) for an APNG.
 */
data class PngDecoded(
    /** Decoded frames in playback order; a plain PNG yields exactly one. */
    val frames: List<PngFrame>,
)

/**
 * Pure-JVM PNG decoder — the reading counterpart of
 * `com.pixellab.core.export.PngCodec` / `ApngEncoder`.
 *
 * Color types: 0 (grayscale), 2 (truecolor RGB), 3 (indexed + `PLTE`),
 * 4 (gray + alpha), 6 (RGBA). Bit depths 1/2/4/8 for indexed and grayscale,
 * 8/16 for the others; 16-bit samples scale to 8-bit with `>> 8`.
 *
 * * All five scanline filters (None/Sub/Up/Average/Paeth with the standard
 *   Paeth predictor) are implemented; the filter unit is
 *   `ceil(channels * depth / 8)` bytes (1 byte for sub-byte depths).
 * * Adam7 interlacing is fully supported: seven passes with their own
 *   (x start, x step, y start, y step) geometry, per-pass filtered rows and
 *   scatter into the final raster.
 * * `tRNS` is honored for all three color types it applies to: per-index
 *   alpha tables for indexed images and single gray / RGB color keys for
 *   color types 0 and 2 (keys compared at source depth).
 * * Every chunk's CRC32 is validated with [java.util.zip.CRC32] (covering
 *   `type + data`); unknown ancillary chunks are skipped by length.
 * * APNG: `acTL` / `fcTL` / `fdAT` are parsed with strict sequence numbers
 *   (0, 1, 2... across both chunk kinds). When the first `fcTL` precedes the
 *   first `IDAT`, the default image is animation frame 0 (the layout our
 *   `ApngEncoder` writes); otherwise the default image is a fallback poster
 *   and the animation comes from the `fdAT` frames alone, exactly as the
 *   APNG specification prescribes. Frame compositing honors `dispose_op`
 *   (none / background / previous) and `blend_op` (source / over) onto the
 *   full IHDR canvas.
 *
 * Structural strictness: `IHDR` must be the first chunk and `IEND` the last
 * (nothing may follow `IEND`); critical chunks may not repeat; `IDAT` chunks
 * must be contiguous; inflated sizes must match the layout exactly.
 */
object PngDecoder {

    /** PNG file signature. */
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Adam7 pass geometry: x start, x step, y start, y step. */
    private val ADAM7 = arrayOf(
        intArrayOf(0, 8, 0, 8),
        intArrayOf(4, 8, 0, 8),
        intArrayOf(0, 4, 4, 8),
        intArrayOf(2, 4, 0, 4),
        intArrayOf(0, 2, 2, 4),
        intArrayOf(1, 2, 0, 2),
        intArrayOf(0, 1, 1, 2),
    )

    /**
     * Decodes a complete PNG or APNG byte stream.
     *
     * @param bytes PNG file contents (signature through `IEND`).
     * @return the decoded frames; exactly one for a plain PNG.
     * @throws PngDecodeException on any structural violation described in
     * the class documentation.
     */
    fun decode(bytes: ByteArray): PngDecoded {
        try {
            return decodeInternal(bytes)
        } catch (error: PngDecodeException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw PngDecodeException("Malformed PNG: ${error.message}")
        }
    }

    // ---- chunk walk ----------------------------------------------------------

    /** Parsed IHDR fields plus derived layout constants. */
    private class Layout(
        val width: Int,
        val height: Int,
        val depth: Int,
        val colorType: Int,
        val interlaced: Boolean,
    ) {
        val channels: Int = when (colorType) {
            0 -> 1 // grayscale
            2 -> 3 // truecolor
            3 -> 1 // indexed
            4 -> 2 // gray + alpha
            6 -> 4 // RGBA
            else -> throw PngDecodeException("Invalid IHDR color type $colorType")
        }
        val bitsPerPixel: Int = channels * depth

        /** Filter unit in bytes: one for sub-byte depths, packed otherwise. */
        val filterBpp: Int = (bitsPerPixel + 7) / 8

        /** Maximum raw sample value for this depth (scaling + tRNS masks). */
        val maxRaw: Int = (1 shl depth) - 1

        /** Bytes of a packed scanline holding [pixels] pixels. */
        fun rowBytes(pixels: Int): Int = (pixels * bitsPerPixel + 7) / 8

        /** Total filtered byte count of a `pixels x rows` raster. */
        fun rawSize(pixels: Int, rows: Int): Long {
            if (!interlaced) return (rowBytes(pixels).toLong() + 1L) * rows
            var total = 0L
            for (pass in ADAM7) {
                val pw = passPixels(pixels, pass[0], pass[1])
                val ph = passPixels(rows, pass[2], pass[3])
                if (pw == 0 || ph == 0) continue
                total += (rowBytes(pw).toLong() + 1L) * ph
            }
            return total
        }

        /** Number of pixels a pass covers on an axis of [total] pixels. */
        private fun passPixels(total: Int, start: Int, step: Int): Int =
            if (total <= start) 0 else (total - start + step - 1) / step
    }

    /** `fcTL` chunk payload, fully validated. */
    private class FrameControl(
        val width: Int,
        val height: Int,
        val xOffset: Int,
        val yOffset: Int,
        val delayMs: Int,
        val disposeOp: Int,
        val blendOp: Int,
    )

    /** One animation frame's data: its `fcTL` plus raw data parts. */
    private class AnimSource(val control: FrameControl) {
        val parts = ArrayList<ByteArray>()
        var fromIdat = false
    }

    /** `tRNS` payload decoded per color type. */
    private sealed interface Trns {
        /** Indexed: per-index alpha (missing entries = 255). */
        class Indexed(val alpha: ByteArray) : Trns

        /** Grayscale key at source depth. */
        class GrayKey(val value: Int) : Trns

        /** RGB key at source depth. */
        class RgbKey(val r: Int, val g: Int, val b: Int) : Trns
    }

    private fun decodeInternal(bytes: ByteArray): PngDecoded {
        val r = BinaryReader(bytes)
        val signature = r.bytes(8)
        if (!signature.contentEquals(SIGNATURE)) {
            throw PngDecodeException("Not a PNG: bad 8-byte signature")
        }

        var layout: Layout? = null
        var palette: IntArray? = null
        var trns: Trns? = null
        val idatParts = ArrayList<ByteArray>()
        var sawIdat = false
        var lastWasIdat = false
        var sawIend = false
        var animationCount = 0 // acTL num_frames
        var sawAcTl = false
        var expectedSequence = 0
        val frameSources = ArrayList<AnimSource>()
        val crc = CRC32()
        var chunkIndex = 0

        while (!sawIend) {
            val chunkOffset = r.position
            r.requireRemaining(8, "chunk header at offset $chunkOffset")
            val length = r.u32Be().toInt()
            if (length < 0 || length > r.remaining() - 4) {
                throw PngDecodeException(
                    "Chunk at offset $chunkOffset declares $length data bytes but only " +
                        "${r.remaining() - 4} remain"
                )
            }
            val type = r.ascii(4)
            val data = r.bytes(length)
            val storedCrc = r.u32Be()
            crc.reset()
            crc.update(type.toByteArray(Charsets.US_ASCII))
            crc.update(data)
            if (crc.value != storedCrc) {
                throw PngDecodeException(
                    "CRC mismatch in \"$type\" chunk at offset $chunkOffset: " +
                        "stored 0x${storedCrc.toString(16)}, computed 0x${crc.value.toString(16)}"
                )
            }

            when (type) {
                "IHDR" -> {
                    if (chunkIndex != 0) throw PngDecodeException("IHDR must be the first chunk (found at offset $chunkOffset)")
                    if (length != 13) throw PngDecodeException("IHDR length $length != 13")
                    if (layout != null) throw PngDecodeException("Duplicate IHDR")
                    layout = parseIhdr(data)
                }

                "PLTE" -> {
                    if (layout == null) throw PngDecodeException("PLTE before IHDR")
                    if (palette != null) throw PngDecodeException("Duplicate PLTE")
                    if (length == 0 || length % 3 != 0 || length > 768) {
                        throw PngDecodeException("PLTE length $length is not a positive multiple of 3 up to 768")
                    }
                    val colors = IntArray(length / 3)
                    for (i in colors.indices) {
                        colors[i] = (0xFF shl 24) or
                            ((data[i * 3].toInt() and 0xFF) shl 16) or
                            ((data[i * 3 + 1].toInt() and 0xFF) shl 8) or
                            (data[i * 3 + 2].toInt() and 0xFF)
                    }
                    palette = colors
                }

                "tRNS" -> {
                    if (layout == null) throw PngDecodeException("tRNS before IHDR")
                    if (trns != null) throw PngDecodeException("Duplicate tRNS")
                    trns = parseTrns(data, layout, palette)
                }

                "IDAT" -> {
                    if (layout == null) throw PngDecodeException("IDAT before IHDR")
                    if (sawIdat && !lastWasIdat) {
                        throw PngDecodeException("IDAT chunks must be contiguous (gap before offset $chunkOffset)")
                    }
                    sawIdat = true
                    idatParts.add(data)
                    val current = frameSources.lastOrNull()
                    if (current != null) {
                        if (current.parts.isNotEmpty() && !current.fromIdat) {
                            throw PngDecodeException("IDAT after fdAT data of frame at offset $chunkOffset")
                        }
                        // First fcTL before any data: the default image is frame 0.
                        current.fromIdat = true
                        current.parts.add(data)
                    }
                }

                "IEND" -> {
                    if (length != 0) throw PngDecodeException("IEND length $length != 0")
                    sawIend = true
                }

                "acTL" -> {
                    if (layout == null) throw PngDecodeException("acTL before IHDR")
                    if (sawAcTl) throw PngDecodeException("Duplicate acTL")
                    if (sawIdat) throw PngDecodeException("acTL after IDAT (must precede all frame chunks)")
                    if (length != 8) throw PngDecodeException("acTL length $length != 8")
                    animationCount = ((data[0].toInt() and 0xFF) shl 24) or
                        ((data[1].toInt() and 0xFF) shl 16) or
                        ((data[2].toInt() and 0xFF) shl 8) or
                        (data[3].toInt() and 0xFF)
                    if (animationCount < 0) throw PngDecodeException("acTL num_frames is negative")
                    sawAcTl = true
                }

                "fcTL" -> {
                    if (layout == null) throw PngDecodeException("fcTL before IHDR")
                    if (!sawAcTl) throw PngDecodeException("fcTL without preceding acTL")
                    val previous = frameSources.lastOrNull()
                    if (previous != null && previous.parts.isEmpty()) {
                        throw PngDecodeException("fcTL at offset $chunkOffset follows fcTL without frame data")
                    }
                    if (length != 26) throw PngDecodeException("fcTL length $length != 26")
                    val control = parseFctl(data, layout, chunkOffset)
                    validateSequence(data, 0, 4, expectedSequence, "fcTL", chunkOffset)
                    expectedSequence++
                    frameSources.add(AnimSource(control))
                }

                "fdAT" -> {
                    if (layout == null) throw PngDecodeException("fdAT before IHDR")
                    if (!sawAcTl) throw PngDecodeException("fdAT without preceding acTL")
                    val frame = frameSources.lastOrNull()
                        ?: throw PngDecodeException("fdAT at offset $chunkOffset without preceding fcTL")
                    if (frame.fromIdat) {
                        throw PngDecodeException("fdAT after IDAT data of frame at offset $chunkOffset")
                    }
                    if (length < 4) throw PngDecodeException("fdAT length $length < 4 (no sequence number)")
                    validateSequence(data, 0, 4, expectedSequence, "fdAT", chunkOffset)
                    expectedSequence++
                    frame.parts.add(data.copyOfRange(4, length))
                }

                else -> {
                    // Unknown / ancillary chunk (sBIT, pHYs, gAMA, tEXt...): skipped.
                }
            }
            lastWasIdat = type == "IDAT"
            chunkIndex++
        }
        if (layout == null) throw PngDecodeException("PNG has no IHDR chunk")
        if (!sawIdat) throw PngDecodeException("PNG has no IDAT chunk")
        if (r.remaining() > 0) {
            throw PngDecodeException("${r.remaining()} byte(s) after IEND chunk at offset ${r.position}")
        }

        val animated = sawAcTl && animationCount > 0 && frameSources.isNotEmpty()
        if (animated && frameSources.size != animationCount) {
            throw PngDecodeException(
                "acTL declares $animationCount frames but ${frameSources.size} fcTL frame(s) were found"
            )
        }
        for (source in frameSources) {
            if (source.parts.isEmpty()) {
                throw PngDecodeException("APNG frame without fdAT/IDAT data")
            }
        }

        val finalLayout = layout
        val finalPalette = palette
        if (finalLayout.colorType == 3 && finalPalette == null) {
            throw PngDecodeException("Indexed PNG (color type 3) without PLTE chunk")
        }

        val frames: List<PngFrame> = if (animated) {
            decodeAnimation(finalLayout, finalPalette, trns, frameSources)
        } else {
            val raster = decodeRaster(
                finalLayout, finalPalette, trns,
                inflate(concat(idatParts), finalLayout.rawSize(finalLayout.width, finalLayout.height), "IDAT"),
                finalLayout.width, finalLayout.height,
            )
            listOf(PngFrame(PixelFrame.of(finalLayout.width, finalLayout.height, raster), null))
        }
        return PngDecoded(frames)
    }

    // ---- chunk payload parsers ----------------------------------------------

    /** Parses and validates the 13-byte IHDR payload. */
    private fun parseIhdr(data: ByteArray): Layout {
        val width = ((data[0].toInt() and 0xFF) shl 24) or ((data[1].toInt() and 0xFF) shl 16) or
            ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val height = ((data[4].toInt() and 0xFF) shl 24) or ((data[5].toInt() and 0xFF) shl 16) or
            ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val depth = data[8].toInt() and 0xFF
        val colorType = data[9].toInt() and 0xFF
        val compression = data[10].toInt() and 0xFF
        val filterMethod = data[11].toInt() and 0xFF
        val interlace = data[12].toInt() and 0xFF
        if (width <= 0 || height <= 0) {
            throw PngDecodeException("IHDR dimensions ${width}x${height} are non-positive")
        }
        val allowedDepths = when (colorType) {
            0 -> intArrayOf(1, 2, 4, 8, 16)
            2 -> intArrayOf(8, 16)
            3 -> intArrayOf(1, 2, 4, 8)
            4 -> intArrayOf(8, 16)
            6 -> intArrayOf(8, 16)
            else -> throw PngDecodeException("IHDR color type $colorType outside 0..6")
        }
        if (depth !in allowedDepths) {
            throw PngDecodeException(
                "IHDR bit depth $depth invalid for color type $colorType (allowed: ${allowedDepths.contentToString()})"
            )
        }
        if (compression != 0) throw PngDecodeException("IHDR compression method $compression != 0 (zlib)")
        if (filterMethod != 0) throw PngDecodeException("IHDR filter method $filterMethod != 0")
        if (interlace !in 0..1) throw PngDecodeException("IHDR interlace method $interlace outside 0..1")
        return Layout(width, height, depth, colorType, interlace == 1)
    }

    /** Parses and validates a tRNS payload against the layout. */
    private fun parseTrns(data: ByteArray, layout: Layout, palette: IntArray?): Trns {
        when (layout.colorType) {
            0 -> {
                if (data.size != 2) throw PngDecodeException("tRNS length ${data.size} != 2 for grayscale")
                val value = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                return Trns.GrayKey(value)
            }

            2 -> {
                if (data.size != 6) throw PngDecodeException("tRNS length ${data.size} != 6 for truecolor")
                fun sample(i: Int) = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                return Trns.RgbKey(sample(0), sample(2), sample(4))
            }

            3 -> {
                val limit = palette?.size ?: throw PngDecodeException("tRNS before PLTE in indexed image")
                if (data.size > limit) {
                    throw PngDecodeException("tRNS length ${data.size} exceeds palette size $limit")
                }
                return Trns.Indexed(data)
            }

            else -> throw PngDecodeException(
                "tRNS chunk not allowed for color type ${layout.colorType}"
            )
        }
    }

    /** Parses and validates the 26-byte fcTL payload. */
    private fun parseFctl(data: ByteArray, layout: Layout, chunkOffset: Int): FrameControl {
        fun u32(at: Int): Int = ((data[at].toInt() and 0xFF) shl 24) or
            ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or
            (data[at + 3].toInt() and 0xFF)
        val width = u32(4)
        val height = u32(8)
        val xOffset = u32(12)
        val yOffset = u32(16)
        val delayNum = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        val delayDen = ((data[22].toInt() and 0xFF) shl 8) or (data[23].toInt() and 0xFF)
        val disposeOp = data[24].toInt() and 0xFF
        val blendOp = data[25].toInt() and 0xFF
        if (width <= 0 || height <= 0) {
            throw PngDecodeException("fcTL at offset $chunkOffset has non-positive ${width}x${height} region")
        }
        if (xOffset.toLong() + width > layout.width || yOffset.toLong() + height > layout.height) {
            throw PngDecodeException(
                "fcTL region ${width}x${height} at ($xOffset, $yOffset) exceeds IHDR " +
                    "${layout.width}x${layout.height} canvas"
            )
        }
        if (disposeOp > 2) throw PngDecodeException("fcTL dispose_op $disposeOp outside 0..2")
        if (blendOp > 1) throw PngDecodeException("fcTL blend_op $blendOp outside 0..1")
        val denominator = if (delayDen == 0) 100 else delayDen
        val delayMs = (delayNum * 1000L + denominator / 2) / denominator
        return FrameControl(width, height, xOffset, yOffset, delayMs.toInt(), disposeOp, blendOp)
    }

    /** Checks a big-endian u32 sequence number at [offset] of [data]. */
    private fun validateSequence(data: ByteArray, offset: Int, size: Int, expected: Int, what: String, chunkOffset: Int) {
        var value = 0
        for (i in 0 until size) value = (value shl 8) or (data[offset + i].toInt() and 0xFF)
        if (value != expected) {
            throw PngDecodeException(
                "$what sequence number $value (expected $expected) at offset $chunkOffset"
            )
        }
    }

    // ---- animation -----------------------------------------------------------

    /**
     * Decodes all APNG frames, compositing each one onto the full IHDR
     * canvas with the previous frame's dispose op applied first.
     */
    private fun decodeAnimation(
        layout: Layout,
        palette: IntArray?,
        trns: Trns?,
        sources: List<AnimSource>,
    ): List<PngFrame> {
        val canvasWidth = layout.width
        val canvasHeight = layout.height
        var canvas = IntArray(canvasWidth * canvasHeight)
        var prevRect: IntArray? = null // [x, y, w, h] of previous frame
        var prevSnapshot: IntArray? = null
        var prevDispose = 0

        val frames = ArrayList<PngFrame>(sources.size)
        for ((index, source) in sources.withIndex()) {
            val control = source.control
            val region = decodeRaster(
                layout, palette, trns,
                inflate(
                    concat(source.parts),
                    layout.rawSize(control.width, control.height),
                    "frame $index fdAT/IDAT",
                ),
                control.width, control.height,
            )

            when (prevDispose) {
                1 -> clearRegion(canvas, canvasWidth, canvasHeight, prevRect)
                2 -> prevSnapshot?.let { canvas = it.copyOf() }
            }
            val snapshot = if (control.disposeOp == 2) canvas.copyOf() else null

            if (control.blendOp == 0) { // SOURCE: replace
                for (y in 0 until control.height) {
                    val srcRow = y * control.width
                    val dstRow = (control.yOffset + y) * canvasWidth + control.xOffset
                    System.arraycopy(region, srcRow, canvas, dstRow, control.width)
                }
            } else { // OVER: classic alpha composite
                for (y in 0 until control.height) {
                    for (x in 0 until control.width) {
                        val src = region[y * control.width + x]
                        val dst = canvas[(control.yOffset + y) * canvasWidth + control.xOffset + x]
                        canvas[(control.yOffset + y) * canvasWidth + control.xOffset + x] = over(src, dst)
                    }
                }
            }
            frames.add(
                PngFrame(
                    PixelFrame.of(canvasWidth, canvasHeight, canvas.copyOf()),
                    control.delayMs,
                )
            )
            prevDispose = control.disposeOp
            prevRect = intArrayOf(control.xOffset, control.yOffset, control.width, control.height)
            prevSnapshot = snapshot
        }
        return frames
    }

    /** Clears the `x, y, w, h` [rect] region of [canvas] to transparent. */
    private fun clearRegion(canvas: IntArray, width: Int, height: Int, rect: IntArray?) {
        if (rect == null) return
        val right = minOf(width, rect[0] + rect[2])
        val bottom = minOf(height, rect[1] + rect[3])
        for (y in maxOf(0, rect[1]) until bottom) {
            val row = y * width
            for (x in maxOf(0, rect[0]) until right) canvas[row + x] = 0
        }
    }

    /** Standard "source-over" compositing of one ARGB pixel pair. */
    private fun over(src: Int, dst: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0) return dst
        if (sa == 255) return src
        val da = (dst ushr 24) and 0xFF
        val outA = sa + da * (255 - sa) / 255
        if (outA == 0) return 0
        fun mix(sc: Int, dc: Int): Int = (sc * sa + dc * da * (255 - sa) / 255) / outA
        val r = mix((src shr 16) and 0xFF, (dst shr 16) and 0xFF)
        val g = mix((src shr 8) and 0xFF, (dst shr 8) and 0xFF)
        val b = mix(src and 0xFF, dst and 0xFF)
        return (outA shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ---- raster decoding -----------------------------------------------------

    /**
     * Inflates a zlib stream whose decompressed size must be exactly
     * [expected] bytes; truncation, surplus output or trailing input bytes
     * all fail with [PngDecodeException].
     */
    private fun inflate(zlib: ByteArray, expected: Long, what: String): ByteArray {
        if (expected > Int.MAX_VALUE) {
            throw PngDecodeException("$what: raw size $expected exceeds the ByteArray limit")
        }
        val inflater = Inflater()
        try {
            inflater.setInput(zlib)
            val out = ByteArray(expected.toInt())
            var written = 0
            while (written < out.size && !inflater.finished()) {
                val n = inflater.inflate(out, written, out.size - written)
                written += n
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw PngDecodeException("$what: zlib stream truncated ($written of $expected bytes)")
                }
            }
            if (written != out.size) {
                throw PngDecodeException("$what: zlib stream produced $written bytes, expected ${out.size}")
            }
            // The output buffer is full but the zlib trailer (Adler-32) may
            // still be pending: `inflate` with zero remaining capacity never
            // consumes it, which would spin. Probe through a one-byte scratch
            // buffer instead — a conforming stream finishes here, surplus
            // output or truncation fail with precise diagnostics.
            val scratch = ByteArray(1)
            var probes = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(scratch, 0, 1)
                if (n > 0) {
                    throw PngDecodeException("$what: zlib stream produces more than $expected bytes")
                }
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw PngDecodeException("$what: zlib stream truncated after $expected bytes")
                }
                if (++probes > 3) {
                    throw PngDecodeException("$what: zlib stream failed to terminate after $expected bytes")
                }
            }
            if (inflater.remaining > 0) {
                throw PngDecodeException("$what: ${inflater.remaining} trailing byte(s) after zlib stream")
            }
            return out
        } catch (error: java.util.zip.DataFormatException) {
            throw PngDecodeException("$what: corrupt zlib stream (${error.message})")
        } finally {
            inflater.end()
        }
    }

    /** Concatenates chunk payloads into one flat array. */
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

    /**
     * Unfilters and samples a complete (possibly Adam7-interlaced) raster of
     * `targetWidth x targetHeight` pixels into ARGB integers.
     */
    private fun decodeRaster(
        layout: Layout,
        palette: IntArray?,
        trns: Trns?,
        raw: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        val out = IntArray(targetWidth * targetHeight)
        if (!layout.interlaced) {
            val rowSize = layout.rowBytes(targetWidth)
            var rowA = ByteArray(rowSize) // current row
            var rowB = ByteArray(rowSize) // previous row
            var rawPos = 0
            for (y in 0 until targetHeight) {
                val filter = raw[rawPos++].toInt() and 0xFF
                System.arraycopy(raw, rawPos, rowA, 0, rowSize)
                rawPos += rowSize
                unfilterRow(filter, rowA, rowB, layout.filterBpp, y)
                val pixels = rowToPixels(rowA, targetWidth, layout, palette, trns)
                System.arraycopy(pixels, 0, out, y * targetWidth, targetWidth)
                val swap = rowB
                rowB = rowA
                rowA = swap
            }
            return out
        }

        // Adam7: each pass is a self-contained filtered sub-image.
        var rawPos = 0
        for (pass in ADAM7.indices) {
            val xStart = ADAM7[pass][0]
            val xStep = ADAM7[pass][1]
            val yStart = ADAM7[pass][2]
            val yStep = ADAM7[pass][3]
            val passWidth = if (targetWidth <= xStart) 0 else (targetWidth - xStart + xStep - 1) / xStep
            val passHeight = if (targetHeight <= yStart) 0 else (targetHeight - yStart + yStep - 1) / yStep
            if (passWidth == 0 || passHeight == 0) continue
            val rowSize = layout.rowBytes(passWidth)
            var rowA = ByteArray(rowSize)
            var rowB = ByteArray(rowSize)
            for (py in 0 until passHeight) {
                val filter = raw[rawPos++].toInt() and 0xFF
                System.arraycopy(raw, rawPos, rowA, 0, rowSize)
                rawPos += rowSize
                unfilterRow(filter, rowA, rowB, layout.filterBpp, pass)
                val pixels = rowToPixels(rowA, passWidth, layout, palette, trns)
                val targetY = yStart + py * yStep
                for (px in 0 until passWidth) {
                    out[targetY * targetWidth + xStart + px * xStep] = pixels[px]
                }
                val swap = rowB
                rowB = rowA
                rowA = swap
            }
        }
        return out
    }

    /**
     * Reverses one scanline filter in place. [current] holds the filtered
     * bytes of the row being reconstructed, [previous] the already
     * reconstructed row above; [bpp] is the filter unit in bytes.
     */
    private fun unfilterRow(filter: Int, current: ByteArray, previous: ByteArray, bpp: Int, rowDesc: Int) {
        when (filter) {
            0 -> Unit // None

            1 -> for (i in bpp until current.size) { // Sub
                current[i] = (current[i] + current[i - bpp]).toByte()
            }

            2 -> for (i in current.indices) { // Up
                current[i] = (current[i] + previous[i]).toByte()
            }

            3 -> for (i in current.indices) { // Average
                val left = if (i >= bpp) current[i - bpp].toInt() and 0xFF else 0
                current[i] = (current[i] + ((left + (previous[i].toInt() and 0xFF)) ushr 1)).toByte()
            }

            4 -> for (i in current.indices) { // Paeth
                val a = if (i >= bpp) current[i - bpp].toInt() and 0xFF else 0
                val b = previous[i].toInt() and 0xFF
                val c = if (i >= bpp) previous[i - bpp].toInt() and 0xFF else 0
                current[i] = (current[i] + paeth(a, b, c)).toByte()
            }

            else -> throw PngDecodeException("Unknown filter type $filter in row/pass $rowDesc")
        }
    }

    /** Standard Paeth predictor from the PNG specification. */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    /**
     * Samples one unfiltered scanline of [count] pixels into ARGB integers:
     * bit unpacking (depths 1/2/4, MSB-first), 16→8-bit scaling (`ushr 8`),
     * grayscale stretching for sub-byte depths, palette lookup for indexed
     * color, and `tRNS` key/alpha application.
     */
    private fun rowToPixels(
        row: ByteArray,
        count: Int,
        layout: Layout,
        palette: IntArray?,
        trns: Trns?,
    ): IntArray {
        val out = IntArray(count)
        val depth = layout.depth
        val bitCursor = IntArray(1) // boxed bit position shared by sample reads

        fun sample(): Int = when (depth) {
            8 -> row[bitCursor[0]++].toInt() and 0xFF
            16 -> {
                val v = ((row[bitCursor[0]].toInt() and 0xFF) shl 8) or
                    (row[bitCursor[0] + 1].toInt() and 0xFF)
                bitCursor[0] += 2
                v
            }

            else -> {
                val byteIndex = bitCursor[0] shr 3
                val shift = 8 - depth - (bitCursor[0] and 7)
                bitCursor[0] += depth
                ((row[byteIndex].toInt() and 0xFF) ushr shift) and layout.maxRaw
            }
        }

        /** Scales a raw sample to 0..255. */
        fun scale(v: Int): Int = when (depth) {
            16 -> v ushr 8
            8 -> v
            else -> v * 255 / layout.maxRaw
        }

        val grayKey = (trns as? Trns.GrayKey)?.value?.and(layout.maxRaw)
        val rgbKey = trns as? Trns.RgbKey
        val indexedAlpha = (trns as? Trns.Indexed)?.alpha

        for (i in 0 until count) {
            out[i] = when (layout.colorType) {
                0 -> {
                    val v = sample()
                    if (grayKey != null && v == grayKey) 0
                    else {
                        val g = scale(v)
                        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                    }
                }

                2 -> {
                    val rv = sample()
                    val gv = sample()
                    val bv = sample()
                    if (rgbKey != null &&
                        rv == (rgbKey.r and layout.maxRaw) &&
                        gv == (rgbKey.g and layout.maxRaw) &&
                        bv == (rgbKey.b and layout.maxRaw)
                    ) {
                        0
                    } else {
                        (0xFF shl 24) or (scale(rv) shl 16) or (scale(gv) shl 8) or scale(bv)
                    }
                }

                3 -> {
                    val index = sample()
                    val colors = palette
                        ?: throw PngDecodeException("Indexed row without PLTE (cannot happen after validation)")
                    if (index >= colors.size) {
                        throw PngDecodeException(
                            "Palette index $index out of range for ${colors.size}-entry PLTE (pixel $i)"
                        )
                    }
                    val alpha = if (indexedAlpha != null && index < indexedAlpha.size) {
                        indexedAlpha[index].toInt() and 0xFF
                    } else {
                        255
                    }
                    (alpha shl 24) or (colors[index] and 0x00FFFFFF)
                }

                4 -> {
                    val v = sample()
                    val a = sample()
                    val g = scale(v)
                    (scale(a) shl 24) or (g shl 16) or (g shl 8) or g
                }

                6 -> {
                    val r = scale(sample())
                    val g = scale(sample())
                    val b = scale(sample())
                    val a = scale(sample())
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }

                else -> throw PngDecodeException("Unhandled color type ${layout.colorType}")
            }
        }
        return out
    }
}
