package com.pixellab.core.export

import com.pixellab.core.model.PixelFrame
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Pure-Kotlin animated GIF89a encoder. Byte-for-byte mirrors the native
 * `NativeGifEncoder` bridge so hosts can switch implementations freely.
 *
 * File layout:
 * - `GIF89a` header;
 * - Logical Screen Descriptor with packed field `0xF7` (global color table
 *   flag, 256-entry GCT) and background/aspect bytes `0x00`;
 * - a 768-byte Global Color Table whose slot 0 is reserved for transparency
 *   (never displayed) while real colors occupy slots `1..255`;
 * - the `NETSCAPE2.0` application extension carrying the loop count
 *   (0 = loop forever);
 * - for every frame: a Graphic Control Extension (disposal 2 "restore to
 *   background", transparency flag on, delay in centiseconds) followed by an
 *   Image Descriptor (full canvas, no local color table, no interlace) and a
 *   GIF-LZW compressed index stream delivered in `<= 255`-byte sub-blocks;
 * - the `0x3B` trailer.
 *
 * Palette construction merges opaque statistics (alpha `>= 0x80`) across all
 * frames, deduplicates colors into RGB 6-6-5 quantization bins and, if the
 * bin count stays within 255, promotes each bin's most frequent exact color
 * directly into the table (frequency descending, then bin key ascending).
 * Images with more than 255 bins are shrunk by a Median-cut style box
 * splitting pass (most populous box, longest axis, weighted median, weighted
 * average color per box) until at most 255 colors remain.
 *
 * Pixel mapping is exact-match cached first; misses resolve to the palette
 * slot with the smallest squared RGB distance (ties pick the lowest slot
 * index, keeping output deterministic). Transparent pixels always map to
 * slot 0.
 *
 * The LZW coder starts at code width 9 (min code size 8), grows the width as
 * the dictionary fills, and emits a CLEAR (code 256) whenever the dictionary
 * reaches 4096 entries; bit packing is LSB-first with a zero-padded final
 * byte.
 */
object KotlinGifEncoder {

    /** LZW clear code: resets the dictionary to its 258-entry boot state. */
    private const val CLEAR_CODE = 256

    /** LZW end-of-information code: terminates the pixel stream. */
    private const val EOI_CODE = 257

    /** First dictionary slot available after CLEAR and EOI. */
    private const val FIRST_FREE_CODE = 258

    /** Total LZW dictionary capacity; a full table forces a CLEAR. */
    private const val MAX_DICT_CODES = 4096

    /** Highest LZW code width (codes 0..4095 fit in 12 bits). */
    private const val MAX_CODE_WIDTH = 12

    /** Root-code bit width; palette indices are 8-bit. */
    private const val MIN_CODE_SIZE = 8

    /** GCT slots usable for real colors; slot 0 is the transparency slot. */
    private const val PALETTE_CAPACITY = 255

    /** GIF dimensions and delay fields are unsigned 16-bit. */
    private const val U16_MAX = 0xFFFF

    /** GIF89a header magic. */
    private val GIF_HEADER = "GIF89a".toByteArray(Charsets.US_ASCII)

    /** NETSCAPE2.0 looping application-extension identifier. */
    private val NETSCAPE_APP_ID = "NETSCAPE2.0".toByteArray(Charsets.US_ASCII)

    /**
     * Encodes [frames] as an animated GIF89a byte stream.
     *
     * @param width canvas width in pixels (must equal every frame's width).
     * @param height canvas height in pixels (must equal every frame's height).
     * @frames the animation frames, row-major ARGB rasters.
     * @param delaysMs per-frame delay in milliseconds; converted to
     * centiseconds via `(ms / 10)` clamped into `2..65535`.
     * @param loopCount NETSCAPE loop count; 0 loops forever. Clamped into the
     * unsigned 16-bit field range.
     * @return complete GIF89a file bytes.
     * @throws IllegalArgumentException if [frames] is empty,
     * [delaysMs].size differs from [frames].size, the canvas is outside
     * `1..65535`, or any frame's dimensions differ from the canvas.
     */
    fun encode(
        width: Int,
        height: Int,
        frames: List<PixelFrame>,
        delaysMs: List<Int>,
        loopCount: Int = 0,
    ): ByteArray {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(delaysMs.size == frames.size) {
            "delaysMs has ${delaysMs.size} entries but there are ${frames.size} frames"
        }
        require(width in 1..U16_MAX && height in 1..U16_MAX) {
            "GIF canvas ${width}x${height} is outside the supported 1..$U16_MAX range"
        }
        for (index in frames.indices) {
            val frame = frames[index]
            require(frame.width == width && frame.height == height) {
                "Frame $index is ${frame.width}x${frame.height}, expected ${width}x${height}"
            }
        }

        val colors = buildPalette(frames)
        // Resolution cache seeded with exact palette hits (lowest slot wins);
        // misses are filled on first use with the nearest-slot result.
        val resolutionCache = HashMap<Int, Int>(colors.size * 2 + 8)
        for (slot in colors.indices) {
            val rgb = colors[slot]
            if (!resolutionCache.containsKey(rgb)) resolutionCache[rgb] = slot + 1
        }

        val out = ByteArrayOutputStream(4096)
        out.write(GIF_HEADER)
        // Logical Screen Descriptor: GCT present, 256 entries.
        writeU16LE(out, width)
        writeU16LE(out, height)
        out.write(0xF7) // GCT flag | color resolution 7 | no sort | GCT size 7 -> 256 slots
        out.write(0x00) // background color index
        out.write(0x00) // pixel aspect ratio: square
        writeGlobalColorTable(out, colors)
        // NETSCAPE2.0 application extension: animation loop count.
        out.write(0x21)
        out.write(0xFF)
        out.write(0x0B)
        out.write(NETSCAPE_APP_ID)
        out.write(0x03)
        out.write(0x01)
        writeU16LE(out, loopCount.coerceIn(0, U16_MAX))
        out.write(0x00)

        for (index in frames.indices) {
            val delayCs = (delaysMs[index] / 10).coerceIn(2, U16_MAX)
            writeGraphicControlExtension(out, delayCs)
            writeImageDescriptor(out, width, height)
            val indices = mapPixels(frames[index], colors, resolutionCache)
            val compressed = lzwCompress(indices)
            out.write(MIN_CODE_SIZE)
            var position = 0
            while (position < compressed.size) {
                val blockLength = minOf(255, compressed.size - position)
                out.write(blockLength)
                out.write(compressed, position, blockLength)
                position += blockLength
            }
            out.write(0x00) // data sub-block terminator
        }
        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    /** Writes an unsigned 16-bit little-endian value (GIF byte order). */
    private fun writeU16LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }

    /**
     * Writes the 768-byte Global Color Table: slot 0 fixed for transparency,
     * real colors in slots `1..[colors].size`, remaining slots zero-filled.
     */
    private fun writeGlobalColorTable(out: ByteArrayOutputStream, colors: IntArray) {
        out.write(0)
        out.write(0)
        out.write(0)
        for (rgb in colors) {
            out.write((rgb shr 16) and 0xFF)
            out.write((rgb shr 8) and 0xFF)
            out.write(rgb and 0xFF)
        }
        var remaining = PALETTE_CAPACITY - colors.size
        while (remaining > 0) {
            out.write(0)
            out.write(0)
            out.write(0)
            remaining--
        }
    }

    /**
     * Writes the Graphic Control Extension: disposal method 2 (restore to
     * background), transparency flag set, transparent index 0 and the frame
     * delay [delayCs] in centiseconds.
     */
    private fun writeGraphicControlExtension(out: ByteArrayOutputStream, delayCs: Int) {
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        out.write(0x09) // (disposal 2) << 2 | transparent-color flag
        writeU16LE(out, delayCs)
        out.write(0x00) // transparent palette index: slot 0
        out.write(0x00) // block terminator
    }

    /** Writes a full-canvas Image Descriptor (no LCT, no interlace). */
    private fun writeImageDescriptor(out: ByteArrayOutputStream, width: Int, height: Int) {
        out.write(0x2C)
        writeU16LE(out, 0) // left
        writeU16LE(out, 0) // top
        writeU16LE(out, width)
        writeU16LE(out, height)
        out.write(0x00) // packed: no local color table, not interlaced
    }

    /** Mutable accumulation state of one RGB 6-6-5 quantization bin. */
    private class Bin(val key: Int) {
        var total: Int = 0
        var bestRgb: Int = 0
        var bestCount: Int = 0
        val exactCounts: HashMap<Int, Int> = HashMap()
    }

    /**
     * Builds the palette (packed `0xRRGGBB`, one entry per GCT slot starting
     * at slot 1) from the opaque pixels of all frames.
     *
     * Bins are keyed `((r shr 2) shl 11) or ((g shr 2) shl 5) or (b shr 3)`
     * (RGB 6-6-5). With at most 255 bins every bin goes straight into the
     * table, ordered by frequency descending then key ascending. Otherwise a
     * Median-cut pass shrinks the population to 255 or fewer colors.
     */
    private fun buildPalette(frames: List<PixelFrame>): IntArray {
        val bins = HashMap<Int, Bin>()
        for (frame in frames) {
            for (argb in frame.pixels) {
                if ((argb ushr 24) < 0x80) continue // transparent: excluded from stats
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val key = ((r shr 2) shl 11) or ((g shr 2) shl 5) or (b shr 3)
                val bin = bins.getOrPut(key) { Bin(key) }
                bin.total++
                val rgb = (r shl 16) or (g shl 8) or b
                val count = bin.exactCounts.getOrDefault(rgb, 0) + 1
                bin.exactCounts[rgb] = count
                // Representative: most frequent exact color; ties -> smaller RGB.
                if (count > bin.bestCount || (count == bin.bestCount && rgb < bin.bestRgb)) {
                    bin.bestCount = count
                    bin.bestRgb = rgb
                }
            }
        }
        if (bins.isEmpty()) return IntArray(0) // fully transparent animation
        if (bins.size <= PALETTE_CAPACITY) {
            val ordered = bins.values.sortedWith(
                compareByDescending<Bin> { it.total }.thenBy { it.key },
            )
            return IntArray(ordered.size) { ordered[it].bestRgb }
        }
        return medianCutPalette(bins.values)
    }

    /**
     * Median-cut style box shrinking for images whose bin count exceeds
     * [PALETTE_CAPACITY].
     *
     * Each bin representative is one population unit weighted by the bin
     * frequency. The most populous box with two or more entries is split
     * along its longest RGB axis (ties resolved R > G > B) at the weighted
     * median — the split minimizing the population difference of the halves —
     * until 255 boxes exist or nothing is splittable. Each box becomes the
     * weighted-average color of its entries. Final order: population
     * descending, then smaller packed RGB. Every comparison chain carries a
     * unique tiebreaker (the bin key), so the result is deterministic
     * independent of hash-map iteration order.
     */
    private fun medianCutPalette(bins: Collection<Bin>): IntArray {
        val entries = ArrayList<BoxEntry>(bins.size)
        for (bin in bins) {
            val rgb = bin.bestRgb
            entries.add(
                BoxEntry(
                    r = (rgb shr 16) and 0xFF,
                    g = (rgb shr 8) and 0xFF,
                    b = rgb and 0xFF,
                    freq = bin.total,
                    key = bin.key,
                ),
            )
        }
        val boxes = ArrayList<List<BoxEntry>>()
        boxes.add(entries)
        while (boxes.size < PALETTE_CAPACITY) {
            var splitIndex = -1
            var splitPopulation = -1L
            for (index in boxes.indices) {
                val box = boxes[index]
                if (box.size < 2) continue // a single entry cannot be split
                val population = population(box)
                if (population > splitPopulation) {
                    splitPopulation = population
                    splitIndex = index
                }
            }
            if (splitIndex < 0) break // every box holds a single entry
            val box = boxes.removeAt(splitIndex)
            val sorted = box.sortedWith(splitComparator(box))
            val total = splitPopulation
            var accumulated = 0L
            var splitAt = 1
            var bestDifference = Long.MAX_VALUE
            for (i in 0 until sorted.size - 1) {
                accumulated += sorted[i].freq
                val difference = abs(accumulated - (total - accumulated))
                if (difference < bestDifference) {
                    bestDifference = difference
                    splitAt = i + 1
                }
            }
            boxes.add(ArrayList(sorted.subList(0, splitAt)))
            boxes.add(ArrayList(sorted.subList(splitAt, sorted.size)))
        }
        val colored = ArrayList<BoxColor>(boxes.size)
        for (box in boxes) {
            var redSum = 0L
            var greenSum = 0L
            var blueSum = 0L
            var total = 0L
            for (entry in box) {
                redSum += entry.r.toLong() * entry.freq
                greenSum += entry.g.toLong() * entry.freq
                blueSum += entry.b.toLong() * entry.freq
                total += entry.freq
            }
            val r = ((redSum + total / 2) / total).toInt()
            val g = ((greenSum + total / 2) / total).toInt()
            val b = ((blueSum + total / 2) / total).toInt()
            colored.add(BoxColor((r shl 16) or (g shl 8) or b, total))
        }
        val ordered = colored.sortedWith(
            compareByDescending<BoxColor> { it.population }.thenBy { it.rgb },
        )
        return IntArray(ordered.size) { ordered[it].rgb }
    }

    /** Total pixel population of a box (sum of entry frequencies). */
    private fun population(box: List<BoxEntry>): Long {
        var total = 0L
        for (entry in box) total += entry.freq
        return total
    }

    /**
     * Comparator for splitting [box]: primary key is the value on the box's
     * longest axis (channel range, ties R > G > B); the remaining channels
     * and the unique bin key guarantee a total, deterministic order.
     */
    private fun splitComparator(box: List<BoxEntry>): Comparator<BoxEntry> {
        var minR = 255
        var maxR = 0
        var minG = 255
        var maxG = 0
        var minB = 255
        var maxB = 0
        for (entry in box) {
            if (entry.r < minR) minR = entry.r
            if (entry.r > maxR) maxR = entry.r
            if (entry.g < minG) minG = entry.g
            if (entry.g > maxG) maxG = entry.g
            if (entry.b < minB) minB = entry.b
            if (entry.b > maxB) maxB = entry.b
        }
        val rRange = maxR - minR
        val gRange = maxG - minG
        val bRange = maxB - minB
        return when {
            rRange >= gRange && rRange >= bRange ->
                compareBy<BoxEntry>({ it.r }, { it.g }, { it.b }, { it.key })
            gRange >= bRange ->
                compareBy<BoxEntry>({ it.g }, { it.r }, { it.b }, { it.key })
            else ->
                compareBy<BoxEntry>({ it.b }, { it.r }, { it.g }, { it.key })
        }
    }

    /** One median-cut population unit: a bin representative weighted by frequency. */
    private class BoxEntry(val r: Int, val g: Int, val b: Int, val freq: Int, val key: Int)

    /** A shrunk box's final palette color plus the pixel population it covers. */
    private class BoxColor(val rgb: Int, val population: Long)

    /**
     * Maps a frame's ARGB pixels to GCT indices. Alpha `&lt; 0x80` becomes the
     * transparency slot 0; opaque pixels resolve through [cache] (exact hits
     * pre-seeded) and otherwise the nearest palette slot by squared RGB
     * distance with the lowest index winning ties.
     */
    private fun mapPixels(frame: PixelFrame, colors: IntArray, cache: HashMap<Int, Int>): ByteArray {
        val pixels = frame.pixels
        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val argb = pixels[i]
            if ((argb ushr 24) < 0x80) {
                indices[i] = 0
                continue
            }
            val rgb = argb and 0x00FFFFFF
            val cached = cache[rgb]
            if (cached != null) {
                indices[i] = cached.toByte()
            } else {
                val slot = nearestSlot(rgb, colors)
                cache[rgb] = slot
                indices[i] = slot.toByte()
            }
        }
        return indices
    }

    /**
     * Index (1-based GCT slot) of the palette entry closest to [rgb] by
     * squared RGB distance; ties resolve to the smallest slot index.
     */
    private fun nearestSlot(rgb: Int, colors: IntArray): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var bestSlot = 1
        var bestDistance = Long.MAX_VALUE
        for (slot in colors.indices) {
            val candidate = colors[slot]
            val dr = r - ((candidate shr 16) and 0xFF)
            val dg = g - ((candidate shr 8) and 0xFF)
            val db = b - (candidate and 0xFF)
            val distance = dr.toLong() * dr + dg.toLong() * dg + db.toLong() * db
            if (distance < bestDistance) {
                bestDistance = distance
                bestSlot = slot + 1
            }
        }
        return bestSlot
    }

    /**
     * GIF-LZW compresses 8-bit palette [indices] into a raw code byte stream
     * (the caller wraps it in `<= 255`-byte sub-blocks).
     *
     * Codes: CLEAR = 256, EOI = 257, first dictionary code 258, widths grow
     * 9 -> 12. The dictionary is a `HashMap` keyed by `(prefix shl 8) or
     * pixel`. When it fills to 4096 entries a CLEAR is emitted and the table
     * restarts. Bit packing is LSB-first; the final partial byte is
     * zero-padded.
     *
     * The width grows one emitted code "late" on purpose: the decoder's
     * table always trails the encoder's by one entry, so the switch points
     * line up on the wire for every standard GIF decoder.
     */
    private fun lzwCompress(indices: ByteArray): ByteArray {
        require(indices.isNotEmpty()) { "LZW input must contain at least one index" }
        val out = ByteArrayOutputStream(indices.size + 64)
        var accumulator = 0
        var bitCount = 0
        var codeWidth = MIN_CODE_SIZE + 1
        var nextCode = FIRST_FREE_CODE
        val dictionary = HashMap<Int, Int>(MAX_DICT_CODES * 2)

        fun emit(code: Int) {
            accumulator = accumulator or (code shl bitCount)
            bitCount += codeWidth
            while (bitCount >= 8) {
                out.write(accumulator and 0xFF)
                accumulator = accumulator ushr 8
                bitCount -= 8
            }
        }

        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val pixel = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or pixel
            val extension = dictionary[key]
            if (extension != null) {
                prefix = extension
                continue
            }
            emit(prefix)
            dictionary[key] = nextCode
            nextCode++
            if (nextCode == MAX_DICT_CODES) {
                // Table full: announce CLEAR and restart the dictionary.
                emit(CLEAR_CODE)
                dictionary.clear()
                nextCode = FIRST_FREE_CODE
                codeWidth = MIN_CODE_SIZE + 1
            } else if (nextCode > (1 shl codeWidth) && codeWidth < MAX_CODE_WIDTH) {
                codeWidth++
            }
            prefix = pixel
        }
        emit(prefix)
        emit(EOI_CODE)
        if (bitCount > 0) {
            out.write(accumulator and 0xFF) // zero-padded flush
        }
        return out.toByteArray()
    }
}
