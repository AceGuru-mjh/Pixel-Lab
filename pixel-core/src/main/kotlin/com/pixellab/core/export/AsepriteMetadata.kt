package com.pixellab.core.export

import com.pixellab.core.model.AnimationTag
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject

/**
 * Aseprite-compatible spritesheet metadata (JSON) generation for Pixel Lab.
 *
 * [Aseprite](https://aseprite.org) exports texture atlases together with a
 * JSON sidecar describing where each animation frame lives on the sheet, how
 * long it plays and which frame spans form named tags. Game engines and
 * sprite tools (Unity, Godot, Phaser, PixiJS, TexturePacker consumers) speak
 * that dialect — this object emits it, so a sheet rasterized by
 * [SpritesheetExporter] can be paired with a pixel-lab-generated sidecar
 * instead of requiring Aseprite itself.
 *
 * ## Layout parity
 *
 * The frame rectangles mirror [SpritesheetExporter.compose] exactly: cells
 * are uniform, sized `maxFrameWidth * scale + 2 * margin` by
 * `maxFrameHeight * scale + 2 * margin`; [SpritesheetLayout.HORIZONTAL]
 * lays frames in one row, [VERTICAL] in one column, and [GRID] wraps after
 * [columns] cells (default 8). Frame `i`'s content sits at
 * `(column * cellWidth + margin, row * cellHeight + margin)` with size
 * `frame.width * scale x frame.height * scale`.
 *
 * ## Output shape
 *
 * ```json
 * {"frames":{"sheet 0.aseprite":{"frame":{"x":0,"y":0,"w":16,"h":16},
 *   "rotated":false,"trimmed":false,
 *   "spriteSourceSize":{"x":0,"y":0,"w":16,"h":16},
 *   "sourceSize":{"w":16,"h":16},"duration":83}},
 *  "meta":{"app":"pixel-lab","version":"1.0","image":"spritesheet.png",
 *   "format":"RGBA8888","size":{"w":128,"h":64},"scale":"1",
 *   "frameTags":[{"name":"idle","from":0,"to":3,"direction":"forward"}]}}
 * ```
 *
 * Frame keys follow the Aseprite export convention `"sheet {i}.aseprite"`.
 * `spriteSourceSize`/`sourceSize` describe the **unscaled** sprite (nothing
 * is trimmed, so both carry the full frame size and the source offset is
 * zero), while `frame` is in sheet pixels. `meta.scale` is written as the
 * string of the [scale] factor, exactly like Aseprite. Output is fully
 * deterministic: same inputs, byte-identical JSON.
 */
object AsepriteMetadata {

    /** App identifier written to `meta.app`. */
    private const val APP: String = "pixel-lab"

    /** Generator version written to `meta.version`. */
    private const val VERSION: String = "1.0"

    /** Sheet image file name written to `meta.image`. */
    private const val IMAGE: String = "spritesheet.png"

    /** Pixel format written to `meta.format` (RGBA_8888 in Aseprite terms). */
    private const val FORMAT: String = "RGBA8888"

    /** Tag playback direction written to every `frameTags` entry. */
    private const val TAG_DIRECTION: String = "forward"

    /**
     * One frame's placement on the spritesheet: the rectangle in sheet
     * pixels (top-left corner `x`/`y`, size `w`x`h`) and the frame's
     * playback duration in milliseconds.
     */
    data class AsepriteFrame(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val durationMs: Int,
    )

    /**
     * Builds the Aseprite JSON sidecar for [frames] played with
     * [durations] on a sheet packed with [layout]/[columns]/[margin]/[scale]
     * (the exact geometry of [SpritesheetExporter.compose]).
     *
     * @param frames composited frames in playback order; must not be empty,
     *   may have differing dimensions (the cell follows the largest frame).
     * @param durations per-frame duration in ms, `1..60000`, same size as
     *   [frames].
     * @param layout sheet layout; see [SpritesheetLayout].
     * @param columns cells per row for [SpritesheetLayout.GRID] (`>= 1`,
     *   ignored by the single-row/column layouts).
     * @param margin transparent padding around the content inside every
     *   cell (`>= 0`).
     * @param scale integer upscale factor (`>= 1`) applied to content and
     *   cell size, reported as `meta.scale`.
     * @param tags named frame spans; each must address frames inside
     *   `0 until frames.size`.
     * @return compact, deterministic Aseprite JSON text.
     * @throws IllegalArgumentException when any argument violates the
     *   constraints above or the sheet exceeds the 31-bit dimension limit.
     */
    fun build(
        frames: List<PixelFrame>,
        durations: List<Int>,
        layout: SpritesheetLayout = SpritesheetLayout.GRID,
        columns: Int = 8,
        margin: Int = 0,
        scale: Int = 1,
        tags: List<AnimationTag> = emptyList(),
    ): String {
        val rects = frameRects(frames, durations, layout, columns, margin, scale)
        val sheet = sheetSize(frames, layout, columns, margin, scale)
        for (tag in tags) {
            require(tag.endFrame < frames.size) {
                "tag '${tag.name}' endFrame ${tag.endFrame} out of bounds (${frames.size} frames)"
            }
        }
        val out = StringBuilder(256 + rects.size * 160 + tags.size * 64)
        out.append("{\"frames\":{")
        for ((index, rect) in rects.withIndex()) {
            if (index > 0) out.append(',')
            val frame = frames[index]
            out.append("\"sheet ").append(index).append(".aseprite\":{")
            out.append("\"frame\":{\"x\":").append(rect.x)
            out.append(",\"y\":").append(rect.y)
            out.append(",\"w\":").append(rect.w)
            out.append(",\"h\":").append(rect.h).append("},")
            out.append("\"rotated\":false,\"trimmed\":false,")
            out.append("\"spriteSourceSize\":{\"x\":0,\"y\":0,\"w\":").append(frame.width)
            out.append(",\"h\":").append(frame.height).append("},")
            out.append("\"sourceSize\":{\"w\":").append(frame.width)
            out.append(",\"h\":").append(frame.height).append("},")
            out.append("\"duration\":").append(rect.durationMs)
            out.append('}')
        }
        out.append("},\"meta\":{\"app\":\"").append(APP)
        out.append("\",\"version\":\"").append(VERSION)
        out.append("\",\"image\":\"").append(IMAGE)
        out.append("\",\"format\":\"").append(FORMAT)
        out.append("\",\"size\":{\"w\":").append(sheet.first)
        out.append(",\"h\":").append(sheet.second)
        out.append("},\"scale\":\"").append(scale)
        out.append("\",\"frameTags\":[")
        for ((index, tag) in tags.withIndex()) {
            if (index > 0) out.append(',')
            out.append("{\"name\":")
            writeEscaped(out, tag.name)
            out.append(",\"from\":").append(tag.startFrame)
            out.append(",\"to\":").append(tag.endFrame)
            out.append(",\"direction\":\"").append(TAG_DIRECTION).append("\"}")
        }
        out.append("]}}")
        return out.toString()
    }

    /**
     * Builds the Aseprite JSON sidecar of a whole [project]: every frame is
     * composited (all visible layers, bottom-up), durations come from
     * [SpriteProject.effectiveFrameDuration] (per-frame override or the
     * project fps) and tags come from the project's animation tags.
     *
     * @param project source project; must have at least one frame.
     * @param layout sheet layout; see [SpritesheetLayout].
     * @param columns cells per row for [SpritesheetLayout.GRID] (`>= 1`).
     * @param margin transparent padding inside every cell (`>= 0`).
     * @param scale integer upscale factor (`>= 1`).
     * @return compact, deterministic Aseprite JSON text.
     * @throws IllegalArgumentException propagated from [build].
     */
    fun build(
        project: SpriteProject,
        layout: SpritesheetLayout = SpritesheetLayout.GRID,
        columns: Int = 8,
        margin: Int = 0,
        scale: Int = 1,
    ): String {
        val frames = (0 until project.frameCount).map { project.compositeFrame(it) }
        val durations = (0 until project.frameCount).map { project.effectiveFrameDuration(it) }
        return build(frames, durations, layout, columns, margin, scale, project.tags)
    }

    /**
     * The sheet rectangles (and durations) of every frame under the given
     * packing parameters — the same list [build] serializes, useful for
     * callers that want the geometry without re-parsing the JSON.
     */
    fun frameRects(
        frames: List<PixelFrame>,
        durations: List<Int>,
        layout: SpritesheetLayout,
        columns: Int,
        margin: Int,
        scale: Int,
    ): List<AsepriteFrame> {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(scale >= 1) { "scale must be >= 1 (was $scale)" }
        require(columns >= 1) { "columns must be >= 1 (was $columns)" }
        require(margin >= 0) { "margin must be >= 0 (was $margin)" }
        require(durations.size == frames.size) {
            "durations (${durations.size}) must match frames (${frames.size})"
        }
        for ((index, duration) in durations.withIndex()) {
            require(duration in 1..60_000) {
                "durations[$index] must be in [1, 60000] ms (was $duration)"
            }
        }

        var maxWidth = 0
        var maxHeight = 0
        for (frame in frames) {
            if (frame.width > maxWidth) maxWidth = frame.width
            if (frame.height > maxHeight) maxHeight = frame.height
        }
        val cellWidth = maxWidth.toLong() * scale + 2L * margin
        val cellHeight = maxHeight.toLong() * scale + 2L * margin
        require(
            cellWidth in 1..Int.MAX_VALUE.toLong() &&
                cellHeight in 1..Int.MAX_VALUE.toLong(),
        ) {
            "Cell size ${cellWidth}x${cellHeight} exceeds the 31-bit dimension limit"
        }

        val cellW = cellWidth.toInt()
        val cellH = cellHeight.toInt()
        val out = ArrayList<AsepriteFrame>(frames.size)
        for ((index, frame) in frames.withIndex()) {
            val column: Int
            val row: Int
            when (layout) {
                SpritesheetLayout.HORIZONTAL -> {
                    column = index
                    row = 0
                }
                SpritesheetLayout.VERTICAL -> {
                    column = 0
                    row = index
                }
                SpritesheetLayout.GRID -> {
                    column = index % columns
                    row = index / columns
                }
            }
            out.add(
                AsepriteFrame(
                    x = column * cellW + margin,
                    y = row * cellH + margin,
                    w = frame.width * scale,
                    h = frame.height * scale,
                    durationMs = durations[index],
                ),
            )
        }
        return out
    }

    /**
     * Total sheet size in pixels under the given packing parameters, again
     * mirroring [SpritesheetExporter.compose] (uniform cells, grid by
     * layout). First component is the width, second the height.
     */
    fun sheetSize(
        frames: List<PixelFrame>,
        layout: SpritesheetLayout,
        columns: Int,
        margin: Int,
        scale: Int,
    ): Pair<Int, Int> {
        require(frames.isNotEmpty()) { "frames must not be empty" }
        require(scale >= 1) { "scale must be >= 1 (was $scale)" }
        require(columns >= 1) { "columns must be >= 1 (was $columns)" }
        require(margin >= 0) { "margin must be >= 0 (was $margin)" }
        var maxWidth = 0
        var maxHeight = 0
        for (frame in frames) {
            if (frame.width > maxWidth) maxWidth = frame.width
            if (frame.height > maxHeight) maxHeight = frame.height
        }
        val cellWidth = maxWidth.toLong() * scale + 2L * margin
        val cellHeight = maxHeight.toLong() * scale + 2L * margin
        val gridColumns: Long
        val gridRows: Long
        when (layout) {
            SpritesheetLayout.HORIZONTAL -> {
                gridColumns = frames.size.toLong()
                gridRows = 1
            }
            SpritesheetLayout.VERTICAL -> {
                gridColumns = 1
                gridRows = frames.size.toLong()
            }
            SpritesheetLayout.GRID -> {
                gridColumns = columns.toLong()
                gridRows = ((frames.size + columns - 1) / columns).toLong()
            }
        }
        val sheetWidth = gridColumns * cellWidth
        val sheetHeight = gridRows * cellHeight
        require(
            sheetWidth in 1..Int.MAX_VALUE.toLong() &&
                sheetHeight in 1..Int.MAX_VALUE.toLong(),
        ) {
            "Sheet size ${sheetWidth}x${sheetHeight} exceeds the 31-bit dimension limit"
        }
        return Pair(sheetWidth.toInt(), sheetHeight.toInt())
    }

    /**
     * Writes [value] as a JSON string literal with the full escape set
     * (quotes, backslash, short escapes, `\uXXXX` for other control
     * characters) — used for tag names, which are free text.
     */
    private fun writeEscaped(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') {
                    out.append("\\u").append(hex4(ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private fun hex4(code: Int): String = code.toString(16).padStart(4, '0')
}
