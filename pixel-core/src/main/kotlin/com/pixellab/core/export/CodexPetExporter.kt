package com.pixellab.core.export

import com.pixellab.core.model.CodexPetSpec
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the Codex companion-pet pack: a fixed 1536 x 1872 transparent
 * spritesheet plus the `pet.json` track manifest, zipped together.
 *
 * Sheet geometry follows [CodexPetSpec]: 8 columns x 9 rows of 192 x 208 cells,
 * one animation track per row in [CodexPetSpec.TRACKS] order. Each frame is
 * composited from the project, uniformly scaled by
 * `max(1, min(192 / w, 208 / h))`, horizontally centered in its cell and
 * bottom-aligned to the cell baseline (`y0 = row * 208 + (208 - h * scale)`),
 * matching the standing-on-the-floor look Codex expects.
 *
 * Frames larger than a cell keep `scale = 1` and are cropped instead:
 * horizontally around the center, vertically by dropping the top so the feet
 * stay on the baseline.
 *
 * Track mapping: `trackMap` keys are track names; keys outside
 * [CodexPetSpec.TRACKS] are ignored and tracks absent from the map leave their
 * row empty. Every provided range is intersected with `[0, lastFrame]` and
 * then truncated to its first 8 frames (one sheet row). A provided range that
 * is empty after clamping is recorded like a missing track
 * (`frames = 0`, `startFrame = 0`, `endFrame = -1`).
 *
 * Output is fully deterministic: identical inputs produce byte-identical
 * ZIPs (entry timestamps pinned to 2020-01-01T00:00:00Z, JSON hand-serialized
 * from a fixed template).
 */
internal object CodexPetExporter {

    /** Pinned entry timestamp: 2020-01-01T00:00:00Z (1577836800000 ms). */
    private const val FIXED_ENTRY_TIME_MS = 1577836800000L

    /** Lowercase hex digits for `\u00xx` control-character escapes. */
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Exports the pet pack for [petName].
     *
     * @param petName pet display name written into `pet.json`; any characters
     * are JSON-escaped, so callers need not pre-sanitize.
     * @param project source project; frames are composited (all visible
     * layers) row by row.
     * @param trackMap animation track name to project frame range; missing
     * tracks stay empty rows and unknown keys are ignored.
     * @return ZIP bytes containing `spritesheet.png` (1536 x 1872 PNG) first
     * and `pet.json` second.
     */
    fun export(petName: String, project: SpriteProject, trackMap: Map<String, IntRange>): ByteArray {
        val sheet = IntArray(CodexPetSpec.SHEET_WIDTH * CodexPetSpec.SHEET_HEIGHT)
        val records = ArrayList<TrackRecord>(CodexPetSpec.TRACKS.size)
        val lastFrame = project.frameCount - 1
        for ((row, trackName) in CodexPetSpec.TRACKS.withIndex()) {
            var start = 0
            var end = -1
            val provided = trackMap[trackName]
            if (provided != null) {
                val clampedStart = maxOf(provided.first, 0)
                val clampedEnd = minOf(provided.last, lastFrame)
                if (clampedStart <= clampedEnd) {
                    start = clampedStart
                    end = minOf(clampedEnd, clampedStart + CodexPetSpec.COLUMNS - 1)
                    for (offset in 0..(end - start)) {
                        drawFrame(sheet, project, start + offset, row, offset)
                    }
                }
            }
            records.add(TrackRecord(trackName, row, start, end))
        }

        val png = PngCodec.encode(
            PixelFrame.of(CodexPetSpec.SHEET_WIDTH, CodexPetSpec.SHEET_HEIGHT, sheet),
        )
        val json = buildPetJson(petName, records)

        val out = ByteArrayOutputStream(png.size + json.size + 128)
        ZipOutputStream(out, Charsets.UTF_8).use { zip ->
            writeEntry(zip, CodexPetSpec.SHEET_FILE, png)
            writeEntry(zip, CodexPetSpec.METADATA_FILE, json)
        }
        return out.toByteArray()
    }

    /**
     * Appends one ZIP entry named [name] holding [data], with the timestamp
     * pinned to [FIXED_ENTRY_TIME_MS] so repeated exports stay byte-identical.
     */
    private fun writeEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = FIXED_ENTRY_TIME_MS
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    /**
     * Draws project frame [frameIndex] into column [column] of row [row] of
     * the [sheet] raster.
     *
     * Scaling and cropping follow the class-level contract: uniform
     * `max(1, min(192 / w, 208 / h))` scale, horizontal centering and
     * bottom-baseline alignment; oversized frames are center-cropped
     * horizontally and top-cropped vertically at scale 1.
     */
    private fun drawFrame(
        sheet: IntArray,
        project: SpriteProject,
        frameIndex: Int,
        row: Int,
        column: Int,
    ) {
        var frame = project.compositeFrame(frameIndex)
        var width = frame.width
        var height = frame.height
        var scale = maxOf(1, minOf(CodexPetSpec.CELL_WIDTH / width, CodexPetSpec.CELL_HEIGHT / height))
        if (width > CodexPetSpec.CELL_WIDTH || height > CodexPetSpec.CELL_HEIGHT) {
            // Frame larger than the cell: keep scale 1 and crop — horizontally
            // around the center, vertically keeping the bottom (feet on the
            // baseline, top shaved off).
            val cropX = if (width > CodexPetSpec.CELL_WIDTH) (width - CodexPetSpec.CELL_WIDTH) / 2 else 0
            val cropWidth = minOf(width, CodexPetSpec.CELL_WIDTH)
            val cropY = if (height > CodexPetSpec.CELL_HEIGHT) height - CodexPetSpec.CELL_HEIGHT else 0
            val cropHeight = minOf(height, CodexPetSpec.CELL_HEIGHT)
            frame = frame.region(cropX, cropY, cropWidth, cropHeight)
            width = cropWidth
            height = cropHeight
            scale = 1
        }
        val scaled = nearestNeighborScale(frame, scale)
        val x0 = column * CodexPetSpec.CELL_WIDTH + (CodexPetSpec.CELL_WIDTH - width * scale) / 2
        val y0 = row * CodexPetSpec.CELL_HEIGHT + CodexPetSpec.CELL_HEIGHT - height * scale
        for (y in 0 until scaled.height) {
            System.arraycopy(
                scaled.pixels,
                y * scaled.width,
                sheet,
                (y0 + y) * CodexPetSpec.SHEET_WIDTH + x0,
                scaled.width,
            )
        }
    }

    /**
     * Serializes the manifest as pretty-printed UTF-8 JSON: pet name, sheet
     * geometry and all 9 tracks in [CodexPetSpec.TRACKS] order. Strings are
     * escaped by [escapeJson]; numbers are appended as plain decimal literals.
     */
    private fun buildPetJson(petName: String, records: List<TrackRecord>): ByteArray {
        val json = StringBuilder(640)
        json.append("{\n")
        json.append("  \"name\": \"")
        escapeJson(petName, json)
        json.append("\",\n")
        json.append("  \"sheet\": {\n")
        json.append("    \"width\": ").append(CodexPetSpec.SHEET_WIDTH).append(",\n")
        json.append("    \"height\": ").append(CodexPetSpec.SHEET_HEIGHT).append(",\n")
        json.append("    \"columns\": ").append(CodexPetSpec.COLUMNS).append(",\n")
        json.append("    \"rows\": ").append(CodexPetSpec.ROWS).append(",\n")
        json.append("    \"cellWidth\": ").append(CodexPetSpec.CELL_WIDTH).append(",\n")
        json.append("    \"cellHeight\": ").append(CodexPetSpec.CELL_HEIGHT).append("\n")
        json.append("  },\n")
        json.append("  \"tracks\": [")
        for ((index, record) in records.withIndex()) {
            json.append(if (index == 0) "\n" else ",\n")
            json.append("    {\n")
            json.append("      \"name\": \"")
            escapeJson(record.name, json)
            json.append("\",\n")
            json.append("      \"row\": ").append(record.row).append(",\n")
            json.append("      \"startFrame\": ").append(record.startFrame).append(",\n")
            json.append("      \"endFrame\": ").append(record.endFrame).append(",\n")
            json.append("      \"frames\": ").append(record.frames).append("\n")
            json.append("    }")
        }
        json.append("\n  ]\n}\n")
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * JSON-escapes [value] into [out]: `\"`, `\\`, `\n`, `\r`, `\t`, `\b` and
     * `\f` become their two-character escapes; every remaining control
     * character (below `0x20`) becomes `\u00xx` with lowercase hex digits. All
     * other characters pass through unchanged (the final UTF-8 encoding
     * handles them).
     */
    internal fun escapeJson(value: String, out: StringBuilder) {
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> {
                    val code = character.code
                    if (code < 0x20) {
                        out.append("\\u00")
                        out.append(HEX_DIGITS[(code shr 4) and 0xF])
                        out.append(HEX_DIGITS[code and 0xF])
                    } else {
                        out.append(character)
                    }
                }
            }
        }
    }

    /**
     * One `pet.json` track record. `frames` is derived so the empty case is
     * always self-consistent: `startFrame = 0`, `endFrame = -1`,
     * `frames = endFrame - startFrame + 1 = 0`.
     */
    private class TrackRecord(
        val name: String,
        val row: Int,
        val startFrame: Int,
        val endFrame: Int,
    ) {
        val frames: Int get() = endFrame - startFrame + 1
    }
}
