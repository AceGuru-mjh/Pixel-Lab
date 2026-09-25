package com.pixellab.core.export

import com.pixellab.core.model.PixelFrame
import java.io.ByteArrayOutputStream

/**
 * Pure-Kotlin APNG (animated PNG) writer.
 *
 * Chunk sequence:
 * 1. PNG signature (delegated to [PngCodec]);
 * 2. `IHDR` describing the canvas — bit depth 8, color type 6 RGBA;
 * 3. `acTL` — animation control: `num_frames` and `num_plays` (u32 BE,
 *    0 = loop forever);
 * 4. for the first frame: `fcTL` (sequence number 0) immediately followed by
 *    the default image's single `IDAT`;
 * 5. for every following frame: `fcTL` + `fdAT`, where `fdAT` data is a u32
 *    BE sequence number plus the very byte stream [PngCodec.idatStream]
 *    produces for that frame;
 * 6. `IEND`.
 *
 * `sequence_number` increments continuously across `fcTL` and `fdAT` chunks
 * (0, 1, 2, ...), never resetting. Each `fcTL` is 26 data bytes with
 * full-canvas geometry, `delay_num = delay milliseconds` (clamped to u16),
 * `delay_den = 1000` so delays resolve to whole milliseconds, dispose op 0
 * (none) and blend op 0 (source).
 *
 * The encoder is a pure function of its arguments: identical input produces
 * byte-identical output, which makes APNG exports reproducible and
 * cacheable.
 */
object ApngEncoder {

    /**
     * Encodes [frames] as an APNG byte stream.
     *
     * @param width canvas width in pixels (must equal every frame's width).
     * @param height canvas height in pixels (must equal every frame's height).
     * @param frames the animation frames, row-major ARGB rasters.
     * @param delaysMs per-frame delay in milliseconds; each value is clamped
     * into the u16 `delay_num` field and combined with `delay_den = 1000`.
     * @param loopCount `num_plays`; 0 loops forever. Negative values clamp
     * to 0.
     * @return complete APNG file bytes.
     * @throws IllegalArgumentException if [frames] is empty,
     * [delaysMs].size differs from [frames].size, the canvas dimensions are
     * non-positive, or any frame's dimensions differ from the canvas.
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
        require(width > 0 && height > 0) {
            "APNG canvas dimensions must be positive (${width}x${height})"
        }
        for (index in frames.indices) {
            val frame = frames[index]
            require(frame.width == width && frame.height == height) {
                "Frame $index is ${frame.width}x${frame.height}, expected ${width}x${height}"
            }
        }

        val frameStreams = Array(frames.size) { PngCodec.idatStream(frames[it]) }
        val capacityHint = (1024L + 128L * frames.size +
            frameStreams.fold(0L) { acc, stream -> acc + stream.size })
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val out = ByteArrayOutputStream(capacityHint)

        PngCodec.writeSignature(out)
        PngCodec.writeIhdr(out, width, height)

        // acTL: animation control. Must precede the first fcTL/IDAT.
        val animationControl = ByteArray(8)
        PngCodec.putU32BE(animationControl, 0, frames.size)
        PngCodec.putU32BE(animationControl, 4, loopCount.coerceIn(0, Int.MAX_VALUE))
        PngCodec.writeChunk(out, "acTL", animationControl)

        // First animation frame: fcTL directly before the default image IDAT.
        var sequenceNumber = 0
        PngCodec.writeChunk(out, "fcTL", fctlData(sequenceNumber++, width, height, delaysMs[0]))
        PngCodec.writeChunk(out, "IDAT", frameStreams[0])

        for (index in 1 until frames.size) {
            PngCodec.writeChunk(
                out,
                "fcTL",
                fctlData(sequenceNumber++, width, height, delaysMs[index]),
            )
            val stream = frameStreams[index]
            val frameData = ByteArray(4 + stream.size)
            PngCodec.putU32BE(frameData, 0, sequenceNumber++)
            System.arraycopy(stream, 0, frameData, 4, stream.size)
            PngCodec.writeChunk(out, "fdAT", frameData)
        }

        PngCodec.writeIend(out)
        return out.toByteArray()
    }

    /**
     * Builds the 26-byte `fcTL` payload: sequence number, frame geometry
     * (full canvas, no offsets), delay numerator (milliseconds, u16),
     * denominator 1000, dispose op 0 (none), blend op 0 (source).
     */
    private fun fctlData(sequenceNumber: Int, width: Int, height: Int, delayMs: Int): ByteArray {
        val data = ByteArray(26)
        PngCodec.putU32BE(data, 0, sequenceNumber)
        PngCodec.putU32BE(data, 4, width)
        PngCodec.putU32BE(data, 8, height)
        PngCodec.putU32BE(data, 12, 0) // x offset
        PngCodec.putU32BE(data, 16, 0) // y offset
        PngCodec.putU16BE(data, 20, delayMs.coerceIn(0, 0xFFFF)) // delay_num (ms)
        PngCodec.putU16BE(data, 22, 1000) // delay_den: delay_num / 1000 seconds
        data[24] = 0x00 // dispose op: none
        data[25] = 0x00 // blend op: source
        return data
    }
}
