package com.pixellab.core.atlas

/**
 * Consumer dialect selector for [AtlasMetadata.toJson].
 */
enum class AtlasFormat {
    /**
     * Pixel Lab's own generic dialect: a `frames` array (name + rectangle +
     * rotation flag), the image name, and a `meta` block with the canvas
     * size and frame count. The default format.
     */
    GENERIC,

    /**
     * Godot 3-flavored sidecar: `texture` + a `regions` array of
     * `[name, region-as-[x,y,w,h]]` entries. Assumption documented in the
     * class docs: Godot itself consumes `.tres`/`.import` resources — this
     * JSON mirrors the import-time structure (a path to the texture and the
     * atlas regions in Godot's `Rect2` array form) so tooling can convert.
     */
    GODOT3,

    /**
     * Godot 4-flavored sidecar: `path` + `atlas_regions` (same region shape
     * as GODOT3, plus the rotation flag) + a `size` block. Assumption as
     * above: a conversion-friendly projection of the Godot 4
     * `AtlasTexture`/importer data, not a file Godot loads verbatim.
     */
    GODOT4,

    /**
     * Tiled tileset JSON (`"type":"tileset"`). Requires the atlas to be a
     * **uniform grid**: all regions the same size, evenly spaced rows and
     * columns. `margin`/`spacing` are then *derived from the geometry*
     * (margin = the left/top edge inset of the first region, spacing = the
     * horizontal/vertical gap between region origins minus the tile size) —
     * note that with [AtlasPacker]'s padding semantics two adjacent regions
     * sit `2 * padding` apart, so a SHELF pack with `padding = 1` produces
     * `spacing = 2`.
     */
    TILED,

    /**
     * Phaser/TexturePacker dialect: a `frames` **object** keyed by sprite
     * name, each carrying `frame` (the atlas rectangle), `rotated`,
     * `trimmed`, `spriteSourceSize` (the trimmed content's placement inside
     * the original) and `sourceSize` (the untrimmed size).
     */
    PHASER,
}

/**
 * Deterministic JSON sidecars for [AtlasBuilder.AtlasResult].
 *
 * Output matches the style of `SpritesheetExporter`/`AsepriteMetadata`:
 * compact single-line JSON (no whitespace variance, no trailing newline),
 * fixed key order per format, and full string escaping (quotes, backslash,
 * short escapes, `\uXXXX` for the remaining control characters). Two calls
 * with the same [AtlasBuilder.AtlasResult] produce byte-identical text.
 *
 * Regions serialize in [AtlasBuilder.AtlasResult.regions] order (input
 * order). The image file name is the fixed `"atlas.png"`.
 */
object AtlasMetadata {

    /** Image file name written by every format. */
    private const val IMAGE: String = "atlas.png"

    /**
     * Serializes [result] as [format] JSON.
     *
     * @param result the atlas to describe.
     * @param format target dialect (default [AtlasFormat.GENERIC]).
     * @return compact, deterministic JSON text (no trailing newline).
     * @throws IllegalArgumentException for [AtlasFormat.TILED] when the
     *   regions do not form a uniform grid (see [AtlasFormat.TILED]).
     */
    fun toJson(result: AtlasBuilder.AtlasResult, format: AtlasFormat = AtlasFormat.GENERIC): String {
        val out = StringBuilder(128 + result.regions.size * 96)
        when (format) {
            AtlasFormat.GENERIC -> writeGeneric(out, result)
            AtlasFormat.GODOT3 -> writeGodot3(out, result)
            AtlasFormat.GODOT4 -> writeGodot4(out, result)
            AtlasFormat.TILED -> writeTiled(out, result)
            AtlasFormat.PHASER -> writePhaser(out, result)
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // GENERIC
    // ------------------------------------------------------------------

    // """
    // {"frames":[{"name":"hero","x":0,"y":0,"w":16,"h":16,"rotated":false}],
    //  "image":"atlas.png",
    //  "meta":{"size":{"w":64,"h":32},"count":1}}
    // """
    private fun writeGeneric(out: StringBuilder, result: AtlasBuilder.AtlasResult) {
        out.append("{\"frames\":[")
        for ((i, region) in result.regions.withIndex()) {
            if (i > 0) out.append(',')
            out.append("{\"name\":")
            writeEscaped(out, region.name)
            out.append(",\"x\":").append(region.x)
            out.append(",\"y\":").append(region.y)
            out.append(",\"w\":").append(region.w)
            out.append(",\"h\":").append(region.h)
            out.append(",\"rotated\":").append(region.rotated)
            out.append('}')
        }
        out.append("],\"image\":\"").append(IMAGE)
        out.append("\",\"meta\":{\"size\":{\"w\":").append(result.width)
        out.append(",\"h\":").append(result.height)
        out.append("},\"count\":").append(result.regions.size)
        out.append("}}")
    }

    // ------------------------------------------------------------------
    // Godot 3 / Godot 4
    // ------------------------------------------------------------------

    // {"texture":"atlas.png","regions":[{"name":"hero","region":[0,0,16,16]}]}
    private fun writeGodot3(out: StringBuilder, result: AtlasBuilder.AtlasResult) {
        out.append("{\"texture\":\"").append(IMAGE).append("\",\"regions\":[")
        for ((i, region) in result.regions.withIndex()) {
            if (i > 0) out.append(',')
            out.append("{\"name\":")
            writeEscaped(out, region.name)
            out.append(",\"region\":[").append(region.x).append(',')
            out.append(region.y).append(',').append(region.w).append(',')
            out.append(region.h).append("]}")
        }
        out.append("]}")
    }

    // {"path":"atlas.png","atlas_regions":[{"name":"hero","region":[0,0,16,16],
    //  "rotated":false}],"size":{"w":64,"h":32}}
    private fun writeGodot4(out: StringBuilder, result: AtlasBuilder.AtlasResult) {
        out.append("{\"path\":\"").append(IMAGE).append("\",\"atlas_regions\":[")
        for ((i, region) in result.regions.withIndex()) {
            if (i > 0) out.append(',')
            out.append("{\"name\":")
            writeEscaped(out, region.name)
            out.append(",\"region\":[").append(region.x).append(',')
            out.append(region.y).append(',').append(region.w).append(',')
            out.append(region.h).append("],\"rotated\":").append(region.rotated)
            out.append('}')
        }
        out.append("],\"size\":{\"w\":").append(result.width)
        out.append(",\"h\":").append(result.height).append("}}")
    }

    // ------------------------------------------------------------------
    // Tiled
    // ------------------------------------------------------------------

    // "{"columns":8,"image":"atlas.png","imageheight":32,"imagewidth":128,
    //  "margin":1,"spacing":2,"tilecount":16,"tileheight":8,"tilewidth":8,
    //  "type":"tileset"}"
    private fun writeTiled(out: StringBuilder, result: AtlasBuilder.AtlasResult) {
        val grid = uniformGrid(result)
        out.append("{\"columns\":").append(grid.columns)
        out.append(",\"image\":\"").append(IMAGE)
        out.append("\",\"imageheight\":").append(result.height)
        out.append(",\"imagewidth\":").append(result.width)
        out.append(",\"margin\":").append(grid.margin)
        out.append(",\"spacing\":").append(grid.spacing)
        out.append(",\"tilecount\":").append(result.regions.size)
        out.append(",\"tileheight\":").append(grid.tileH)
        out.append(",\"tilewidth\":").append(grid.tileW)
        out.append(",\"type\":\"tileset\"}")
    }

    /** Parsed uniform-grid geometry (see [AtlasFormat.TILED]). */
    private class Grid(
        val columns: Int,
        val tileW: Int,
        val tileH: Int,
        val margin: Int,
        val spacing: Int,
    )

    /** Sentinel for "single row/column: no step constraint on this axis". */
    private const val NO_STEP: Int = Int.MIN_VALUE

    /**
     * Validates that the regions form a uniform grid and derives its
     * geometry: uniform tile size, uniform x-step and y-step, unique
     * (x, y) origins, `columns x rows == regions`. The spacing is taken
     * from whichever axis has neighbors (x preferred) and must agree when
     * both do; a single region yields `spacing = 0`.
     */
    private fun uniformGrid(result: AtlasBuilder.AtlasResult): Grid {
        val regions = result.regions
        require(regions.isNotEmpty()) { "TILED format needs at least one region" }
        val tileW = regions[0].w
        val tileH = regions[0].h
        for (region in regions) {
            require(region.w == tileW && region.h == tileH) {
                "TILED format requires uniform tile sizes " +
                    "(got ${region.w}x${region.h}, expected ${tileW}x${tileH})"
            }
            require(!region.rotated) { "TILED format cannot express rotated regions" }
        }
        val xs = sortedSetOf<Int>()
        val ys = sortedSetOf<Int>()
        for (region in regions) {
            xs.add(region.x)
            ys.add(region.y)
        }
        require(xs.size * ys.size == regions.size) {
            "TILED format requires a complete grid: ${xs.size} distinct x by " +
                "${ys.size} distinct y origins does not match ${regions.size} regions"
        }
        val stepX = if (xs.size >= 2) uniformStep(xs, "x") else NO_STEP
        val stepY = if (ys.size >= 2) uniformStep(ys, "y") else NO_STEP
        val spacing = when {
            stepX != NO_STEP && stepY != NO_STEP -> {
                require(stepX - tileW == stepY - tileH) {
                    "TILED format requires equal spacing on both axes " +
                        "(x spacing ${stepX - tileW}, y spacing ${stepY - tileH})"
                }
                stepX - tileW
            }
            stepX != NO_STEP -> stepX - tileW
            stepY != NO_STEP -> stepY - tileH
            else -> 0
        }
        require(spacing >= 0) { "TILED spacing derived negative ($spacing): regions overlap?" }
        val margin = minOf(xs.first(), ys.first())
        return Grid(xs.size, tileW, tileH, margin, spacing)
    }

    /** The uniform step of sorted [values] (at least two); [axis] names errors. */
    private fun uniformStep(values: Set<Int>, axis: String): Int {
        val sorted = values.sorted()
        val step = sorted[1] - sorted[0]
        for (i in 2 until sorted.size) {
            require(sorted[i] - sorted[i - 1] == step) {
                "TILED format requires evenly spaced $axis origins " +
                    "(step $step breaks at ${sorted[i]})"
            }
        }
        return step
    }

    // ------------------------------------------------------------------
    // Phaser
    // ------------------------------------------------------------------

    // {"frames":{"hero":{"frame":{"x":0,"y":0,"w":16,"h":16},"rotated":false,
    //  "trimmed":false,"spriteSourceSize":{"x":0,"y":0,"w":16,"h":16},
    //  "sourceSize":{"w":16,"h":16}}}}
    private fun writePhaser(out: StringBuilder, result: AtlasBuilder.AtlasResult) {
        out.append("{\"frames\":{")
        for ((i, region) in result.regions.withIndex()) {
            if (i > 0) out.append(',')
            writeEscaped(out, region.name)
            out.append(":{\"frame\":{\"x\":").append(region.x)
            out.append(",\"y\":").append(region.y)
            out.append(",\"w\":").append(region.w)
            out.append(",\"h\":").append(region.h)
            out.append("},\"rotated\":").append(region.rotated)
            val original = region.original
            if (original != null) {
                out.append(",\"trimmed\":true,\"spriteSourceSize\":{\"x\":").append(original.offsetX)
                out.append(",\"y\":").append(original.offsetY)
                out.append(",\"w\":").append(region.w)
                out.append(",\"h\":").append(region.h)
                out.append("},\"sourceSize\":{\"w\":").append(original.originalW)
                out.append(",\"h\":").append(original.originalH)
                out.append('}')
            } else {
                out.append(",\"trimmed\":false,\"spriteSourceSize\":{\"x\":0,\"y\":0,\"w\":")
                out.append(region.w).append(",\"h\":").append(region.h)
                out.append("},\"sourceSize\":{\"w\":").append(region.w)
                out.append(",\"h\":").append(region.h).append('}')
            }
            out.append('}')
        }
        out.append("}}")
    }

    // ------------------------------------------------------------------
    // Escaping (same pattern as AsepriteMetadata.writeEscaped)
    // ------------------------------------------------------------------

    /** Writes [value] as a quoted, fully escaped JSON string literal. */
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
