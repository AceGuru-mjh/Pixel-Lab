package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.core.PixelResult
import com.pixellab.core.convert.ConvertRequest
import com.pixellab.core.convert.DitherAlgorithm
import com.pixellab.core.convert.ImageData
import com.pixellab.core.convert.QuantizeAlgorithm
import com.pixellab.core.convert.RefineInstructions
import com.pixellab.core.export.SpritesheetLayout
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.core.template.Font5x7
import com.pixellab.core.template.Font8x8
import com.pixellab.core.template.PixelFont
import com.pixellab.mcp.json.JsonArray
import com.pixellab.mcp.json.JsonElement
import com.pixellab.mcp.json.JsonNull
import com.pixellab.mcp.json.JsonNumber
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonarray
import com.pixellab.mcp.json.jsonobj
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Upper bound for a canvas edge accepted from MCP requests (entry guard). */
private const val MAX_CANVAS_EDGE: Int = 8192

/**
 * One MCP tool: snake_case [name], one-line [description], grouping [category]
 * and the [handler] closure mapping a parameter [JsonObject] plus the session
 * store to a result [JsonObject].
 */
data class McpTool(
    val name: String,
    val description: String,
    val category: String,
    val handler: suspend (JsonObject, PixelSessionStore) -> JsonObject,
)

/**
 * Parameter/validation failure inside a tool handler; carries the JSON-RPC
 * error code the server should answer with (default [JsonRpc.INVALID_PARAMS]).
 */
class McpToolException(message: String, val code: Int = JsonRpc.INVALID_PARAMS) : Exception(message)

/**
 * Registry and dispatcher of every pixel-mcp tool — the full §8 contract list:
 * `canvas_*`, `draw_*`, `layer_*`, `frame_*`, `palette_*`, `convert_*`,
 * `anim_*`, `text_*`, `template_*`, `export_*` and `project_*` (55 tools).
 *
 * Handlers are defensive: missing or mistyped parameters raise
 * [IllegalArgumentException], which [execute] surfaces as
 * [McpToolException] (JSON-RPC `-32602`); engine validation errors flow the
 * same way, and any other failure propagates to the server, which reports it
 * as an MCP `isError: true` envelope. Mutating handlers always write the
 * resulting project back into the [PixelSessionStore].
 *
 * [execute] serializes tool runs through one mutex because the
 * [com.pixellab.core.engine.PixelEngine] undo history is instance state and
 * not thread-safe; the export/convert work itself still suspends properly.
 */
class McpToolRegistry(private val lab: PixelLab) {

    private val mutex = Mutex()
    private val byName: Map<String, McpTool>
    private val schemas: Map<String, JsonObject>

    init {
        val built = buildTools()
        byName = built.first.associateBy { it.name }
        schemas = built.second
    }

    /** All registered tools in registration (contract) order. */
    val tools: List<McpTool> = byName.values.toList()

    /** The tool registered as [name], or null. */
    fun tool(name: String): McpTool? = byName[name]

    /** Simplified JSON-schema description of a tool's parameters. */
    fun inputSchema(name: String): JsonObject = schemas[name] ?: jsonobj {
        put("type", "object")
        put("properties", jsonobj { })
    }

    /**
     * Runs the tool [name] with [params] against [store]. Throws
     * [McpToolException] for unknown tools and parameter errors; other
     * exceptions signal tool execution failures.
     */
    suspend fun execute(name: String, params: JsonObject, store: PixelSessionStore): JsonObject {
        val tool = byName[name] ?: throw McpToolException("unknown tool '$name'", JsonRpc.METHOD_NOT_FOUND)
        return try {
            mutex.withLock { tool.handler(params, store) }
        } catch (error: IllegalArgumentException) {
            throw McpToolException(error.message ?: "invalid parameters for tool '$name'")
        }
    }

    // ---- shared parameter helpers -----------------------------------------

    /** Session referenced by the `session_id` parameter (auto-created when unknown). */
    private fun sessionOf(params: JsonObject, store: PixelSessionStore): PixelSessionStore.SessionState {
        val id = params.string("session_id")
        return requireNotNull(store.get(id, create = true)) { "session '$id' unavailable" }
    }

    /** Canvas-size entry guard: bounds each edge to [1, 8192] so no request
     * can steer the session store into degenerate dimensions. */
    private fun requireSize(width: Int, height: Int) {
        require(width in 1..MAX_CANVAS_EDGE && height in 1..MAX_CANVAS_EDGE) {
            "canvas edges must be in [1, $MAX_CANVAS_EDGE] (was ${width}x${height})"
        }
    }

    /** Runs [op] on the session's project and writes the result back. */
    private fun mutate(
        params: JsonObject,
        store: PixelSessionStore,
        op: (SpriteProject) -> SpriteProject,
    ): JsonObject {
        val session = sessionOf(params, store)
        val next = op(session.project)
        store.update(session.id, next)
        return projectSummary(session, next)
    }

    /** Optional nullable int parameter (absent or JSON null → null). */
    private fun optionalInt(params: JsonObject, key: String): Int? {
        val raw = params.raw(key) ?: return null
        if (raw is JsonNull) return null
        return params.int(key)
    }

    /** Color parameter: `"#RRGGBB"`, `"#AARRGGBB"` or an ARGB integer. */
    private fun colorParam(params: JsonObject, key: String): Int {
        return when (val raw = params.raw(key)) {
            null -> throw IllegalArgumentException("missing parameter '$key'")
            is JsonString -> parseHexColor(raw.value)
            is JsonNumber -> {
                if (raw.value != Math.floor(raw.value) || raw.value < Int.MIN_VALUE.toDouble() || raw.value > Int.MAX_VALUE.toDouble()) {
                    throw IllegalArgumentException("parameter '$key' must be an ARGB integer")
                }
                raw.value.toInt()
            }
            else -> throw IllegalArgumentException("parameter '$key' must be '#RRGGBB'/'#AARRGGBB' or an ARGB integer")
        }
    }

    /** Parses `#RRGGBB` / `#AARRGGBB` (the `#` is optional) into an ARGB int. */
    private fun parseHexColor(text: String): Int {
        val trimmed = text.trim().removePrefix("#").lowercase()
        val padded = when (trimmed.length) {
            6 -> "ff$trimmed"
            8 -> trimmed
            else -> throw IllegalArgumentException("color '$text' must be #RRGGBB or #AARRGGBB")
        }
        val value = padded.toLongOrNull(16)
            ?: throw IllegalArgumentException("color '$text' contains non-hex characters")
        return (value or 0xFF000000L).toInt()
    }

    /** `"#AARRGGBB"` rendering of [argb]. */
    private fun hexArgb(argb: Int): String = "#" + "%08x".format(argb)

    /** Optional built-in palette id parameter; null when absent. */
    private fun paletteParam(params: JsonObject, key: String = "palette_id"): Palette? {
        val raw = params.raw(key) ?: return null
        if (raw is JsonNull) return null
        val id = (raw as? JsonString)?.value
            ?: throw IllegalArgumentException("parameter '$key' must be a palette id string")
        return BuiltInPalettes.byId(id)
            ?: throw IllegalArgumentException("unknown palette '$id' (see palette_list)")
    }

    /** Point list parameter: `[{"x": 1, "y": 2}, ...]`. */
    private fun pointsParam(params: JsonObject, key: String = "points"): List<PixelPoint> {
        val array = params.array(key)
        val out = ArrayList<PixelPoint>(array.size)
        for (item in array) {
            val entry = item as? JsonObject
                ?: throw IllegalArgumentException("'$key' entries must be objects like {\"x\": 0, \"y\": 0}")
            out.add(PixelPoint(entry.int("x"), entry.int("y")))
        }
        return out
    }

    /** Quantize algorithm by wire name; defaults to median cut. */
    private fun quantizeAlgorithm(params: JsonObject): QuantizeAlgorithm {
        return when (params.opt("algorithm", "median_cut").trim().uppercase()) {
            "MEDIAN_CUT", "MEDIANCUT" -> QuantizeAlgorithm.MEDIAN_CUT
            "KMEANS", "K_MEANS" -> QuantizeAlgorithm.KMEANS
            "OCTREE" -> QuantizeAlgorithm.OCTREE
            else -> throw IllegalArgumentException("algorithm must be median_cut, kmeans or octree")
        }
    }

    /** Dither algorithm by wire name. */
    private fun ditherAlgorithm(params: JsonObject, default: String = "none"): DitherAlgorithm {
        return when (params.opt("dither", default).trim().uppercase()) {
            "NONE" -> DitherAlgorithm.NONE
            "FLOYD_STEINBERG", "FLOYDSTEINBERG" -> DitherAlgorithm.FLOYD_STEINBERG
            "ATKINSON" -> DitherAlgorithm.ATKINSON
            "BAYER_2X2", "BAYER2X2" -> DitherAlgorithm.BAYER_2X2
            "BAYER_4X4", "BAYER4X4" -> DitherAlgorithm.BAYER_4X4
            "BAYER_8X8", "BAYER8X8" -> DitherAlgorithm.BAYER_8X8
            "CHECKERBOARD" -> DitherAlgorithm.CHECKERBOARD
            else -> throw IllegalArgumentException("dither must be a known dither algorithm name")
        }
    }

    /** Font parameter: `"5x7"` (default) or `"8x8"`. */
    private fun fontParam(params: JsonObject): PixelFont = when (params.opt("font", "5x7").trim()) {
        "5x7" -> Font5x7
        "8x8" -> Font8x8
        else -> throw IllegalArgumentException("font must be '5x7' or '8x8'")
    }

    /** Pixel data parameter: `[argb, argb, ...]` row-major plus `width`/`height`. */
    private fun imageParam(params: JsonObject): ImageData? {
        val raw = params.raw("pixels") ?: return null
        if (raw is JsonNull) return null
        val array = raw as? JsonArray
            ?: throw IllegalArgumentException("parameter 'pixels' must be an array of ARGB integers")
        val pixels = IntArray(array.size)
        for ((index, item) in array.items.withIndex()) {
            val number = item as? JsonNumber
                ?: throw IllegalArgumentException("pixels[$index] must be an ARGB integer")
            if (number.value != Math.floor(number.value) || number.value < Int.MIN_VALUE.toDouble() || number.value > Int.MAX_VALUE.toDouble()) {
                throw IllegalArgumentException("pixels[$index] must be an ARGB integer")
            }
            pixels[index] = number.value.toInt()
        }
        val width = params.opt("width", 0)
        val height = params.opt("height", 0)
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("pixel input needs positive 'width' and 'height'")
        }
        return ImageData(width, height, pixels)
    }

    /** Track map parameter for the Codex pet export (`name -> [start, end]`). */
    private fun trackMapParam(params: JsonObject, frameCount: Int): Map<String, IntRange> {
        val raw = params.raw("tracks")
        if (raw == null || raw is JsonNull) {
            return if (frameCount > 0) mapOf("idle" to IntRange(0, frameCount - 1)) else emptyMap()
        }
        val obj = raw as? JsonObject
            ?: throw IllegalArgumentException("parameter 'tracks' must be an object of name -> [start, end]")
        val out = LinkedHashMap<String, IntRange>()
        for ((name, value) in obj.entries) out[name] = parseRange(value, frameCount)
        return out
    }

    private fun parseRange(value: JsonElement, frameCount: Int): IntRange {
        val start: Int
        val end: Int
        when (value) {
            is JsonArray -> {
                if (value.size != 2) throw IllegalArgumentException("track ranges must be [start, end]")
                start = intElement(value.items[0])
                end = intElement(value.items[1])
            }
            is JsonObject -> {
                start = value.int("start")
                end = value.int("end")
            }
            else -> throw IllegalArgumentException("track ranges must be [start, end] or {start, end}")
        }
        if (start < 0 || end < start || end >= frameCount) {
            throw IllegalArgumentException("track range $start..$end outside 0..${frameCount - 1}")
        }
        return start..end
    }

    private fun intElement(element: JsonElement): Int = when (element) {
        is JsonNumber -> element.value.toInt()
        else -> throw IllegalArgumentException("track range entries must be integers")
    }

    /** Unwraps a [PixelResult], converting failures into tool failures. */
    private fun <T> PixelResult<T>.unwrap(label: String): T = when (this) {
        is PixelResult.Ok -> value
        is PixelResult.Err -> throw IllegalStateException("$label failed: ${failure.message}")
    }

    /** Non-zero (visible) pixels of [frame]. */
    private fun opaquePixels(frame: PixelFrame): Int = frame.pixels.count { it != 0 }

    // ---- result helpers ---------------------------------------------------

    /** Standard project summary echoed after mutating operations. */
    private fun projectSummary(session: PixelSessionStore.SessionState, project: SpriteProject): JsonObject =
        jsonobj {
            put("session_id", session.id)
            put("project_id", project.id)
            put("name", project.name)
            put("width", project.width)
            put("height", project.height)
            put("frames", project.frameCount)
            put("layers", project.layerCount)
            put("active_frame_index", project.activeFrameIndex)
            put("active_layer_id", project.activeLayerId)
            put("fps", project.fps)
            put("palette_id", project.palette.id)
        }

    /** Palette descriptor with hex swatches. */
    private fun paletteInfo(palette: Palette): JsonObject = jsonobj {
        put("palette_id", palette.id)
        put("name", palette.name)
        put("color_count", palette.size)
        put("colors", palette.colors.map { hexArgb(it) })
    }

    /** Layer stack listing (bottom-up). */
    private fun layersJson(project: SpriteProject): JsonArray = jsonarray {
        for ((index, layer) in project.layers.withIndex()) {
            add(
                jsonobj {
                    put("index", index)
                    put("id", layer.id)
                    put("name", layer.name)
                    put("opacity", layer.opacity)
                    put("visible", layer.visible)
                    put("locked", layer.locked)
                    put("active", layer.id == project.activeLayerId)
                },
            )
        }
    }

    /** Timeline listing. */
    private fun framesJson(project: SpriteProject): JsonArray = jsonarray {
        for ((index, frame) in project.frames.withIndex()) {
            add(
                jsonobj {
                    put("index", index)
                    put("id", frame.id)
                    frame.durationMs?.let { put("duration_ms", it) }
                    put("effective_ms", project.effectiveFrameDuration(index))
                    put("cel_count", frame.cels.size)
                    put("active", index == project.activeFrameIndex)
                },
            )
        }
    }

    /** Tag listing. */
    private fun tagsJson(project: SpriteProject): JsonArray = jsonarray {
        for (tag in project.tags) {
            add(
                jsonobj {
                    put("name", tag.name)
                    put("start_frame", tag.startFrame)
                    put("end_frame", tag.endFrame)
                },
            )
        }
    }

    // ---- registry construction --------------------------------------------

    /** Builds the tool list plus the per-tool input schemas. */
    private fun buildTools(): Pair<List<McpTool>, Map<String, JsonObject>> {
        val tools = ArrayList<McpTool>()
        val schemas = HashMap<String, JsonObject>()

        fun add(
            name: String,
            description: String,
            category: String,
            vararg props: Pair<String, String>,
            required: List<String> = emptyList(),
            handler: suspend (JsonObject, PixelSessionStore) -> JsonObject,
        ) {
            tools.add(McpTool(name, description, category, handler))
            schemas[name] = jsonobj {
                put("type", "object")
                put(
                    "properties",
                    jsonobj {
                        for ((propName, propType) in props) {
                            put(propName, jsonobj { put("type", propType) })
                        }
                    },
                )
                if (required.isNotEmpty()) put("required", required)
            }
        }

        // ---- canvas ----

        add("canvas_create", "Creates a new blank canvas session (16x16 pico-8 by default; each edge must stay within 1..8192).", "canvas",
            "width" to "integer", "height" to "integer", "palette_id" to "string", "name" to "string") { params, store ->
            val palette = paletteParam(params) ?: BuiltInPalettes.PICO8
            val width = params.opt("width", 16)
            val height = params.opt("height", 16)
            requireSize(width, height)
            val session = store.newSession(palette, width, height, params.opt("name", "untitled"))
            projectSummary(session, session.project)
        }

        add("canvas_info", "Reports canvas dimensions, layer/frame counts, palette and tags of a session.", "canvas",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            jsonobj {
                putAllSummary(projectSummary(session, project))
                put("tags", tagsJson(project))
                put("opaque_pixels", opaquePixels(project.compositeActiveFrame()))
            }
        }

        add("canvas_clear", "Clears the active cel (active layer of the active frame).", "canvas",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            mutate(params, store) { lab.engine.clearCanvas(it) }
        }

        add("canvas_shift", "Shifts the active cel by (dx, dy); shifted-out content is lost.", "canvas",
            "session_id" to "string", "dx" to "integer", "dy" to "integer",
            required = listOf("session_id", "dx", "dy")) { params, store ->
            mutate(params, store) { lab.engine.shiftCanvas(it, params.int("dx"), params.int("dy")) }
        }

        add("canvas_flip_h", "Mirrors the active cel horizontally.", "canvas",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            mutate(params, store) { lab.engine.flipCanvasHorizontal(it) }
        }

        add("canvas_flip_v", "Mirrors the active cel vertically.", "canvas",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            mutate(params, store) { lab.engine.flipCanvasVertical(it) }
        }

        add("canvas_rotate", "Rotates the active cel 90 degrees clockwise per quarter-turn 'times'.", "canvas",
            "session_id" to "string", "times" to "integer", required = listOf("session_id")) { params, store ->
            val times = params.opt("times", 1)
            require(times in 1..64) { "'times' must be between 1 and 64 (was $times)" }
            mutate(params, store) { project ->
                var next = project
                repeat(times) { next = lab.engine.rotateCanvas90(next) }
                next
            }
        }

        add("canvas_outline", "Outlines the active cel's opaque pixels with the given color.", "canvas",
            "session_id" to "string", "color" to "string", required = listOf("session_id", "color")) { params, store ->
            mutate(params, store) { lab.engine.outlineCanvas(it, colorParam(params, "color")) }
        }

        // ---- draw ----

        add("draw_pixel", "Paints one pixel on the active cel. Out-of-range coordinates are skipped and reported via `clipped: true`.", "draw",
            "session_id" to "string", "x" to "integer", "y" to "integer", "color" to "string",
            required = listOf("session_id", "x", "y", "color")) { params, store ->
            val session = sessionOf(params, store)
            val x = params.int("x")
            val y = params.int("y")
            val project = session.project
            val outOfBounds = x !in 0 until project.width || y !in 0 until project.height
            val summary = mutate(params, store) { lab.engine.drawPixel(it, x, y, colorParam(params, "color")) }
            if (outOfBounds) jsonobj {
                for ((key, value) in summary.entries) put(key, value)
                put("clipped", true)
                put("reason", "coordinate ($x, $y) outside ${project.width}x${project.height} canvas; pixel not drawn")
            } else summary
        }

        add("draw_line", "Draws a Bresenham line (x0,y0)-(x1,y1) with optional thickness.", "draw",
            "session_id" to "string", "x0" to "integer", "y0" to "integer", "x1" to "integer", "y1" to "integer",
            "color" to "string", "thickness" to "integer",
            required = listOf("session_id", "x0", "y0", "x1", "y1", "color")) { params, store ->
            mutate(params, store) {
                lab.engine.drawLine(it, params.int("x0"), params.int("y0"), params.int("x1"), params.int("y1"), colorParam(params, "color"), params.opt("thickness", 1))
            }
        }

        add("draw_rect", "Draws a rectangle at (x, y) of width x height, filled or outlined.", "draw",
            "session_id" to "string", "x" to "integer", "y" to "integer", "width" to "integer",
            "height" to "integer", "color" to "string", "filled" to "boolean",
            required = listOf("session_id", "x", "y", "width", "height", "color")) { params, store ->
            mutate(params, store) {
                lab.engine.drawRect(it, params.int("x"), params.int("y"), params.int("width"), params.int("height"), colorParam(params, "color"), params.opt("filled", false))
            }
        }

        add("draw_circle", "Draws a midpoint circle at (cx, cy) with radius r, filled or outlined.", "draw",
            "session_id" to "string", "cx" to "integer", "cy" to "integer", "radius" to "integer",
            "color" to "string", "filled" to "boolean",
            required = listOf("session_id", "cx", "cy", "radius", "color")) { params, store ->
            mutate(params, store) {
                lab.engine.drawCircle(it, params.int("cx"), params.int("cy"), params.int("radius"), colorParam(params, "color"), params.opt("filled", false))
            }
        }

        add("draw_pixels", "Paints a list of {x, y} points with one color.", "draw",
            "session_id" to "string", "points" to "array", "color" to "string",
            required = listOf("session_id", "points", "color")) { params, store ->
            mutate(params, store) { lab.engine.drawPixels(it, pointsParam(params), colorParam(params, "color")) }
        }

        add("draw_stroke", "Draws a connected stroke through a list of {x, y} points.", "draw",
            "session_id" to "string", "points" to "array", "color" to "string", "thickness" to "integer",
            required = listOf("session_id", "points", "color")) { params, store ->
            mutate(params, store) {
                lab.engine.drawStroke(it, pointsParam(params), colorParam(params, "color"), params.opt("thickness", 1))
            }
        }

        add("fill", "Flood-fills the 4-connected region at (x, y) with a color and optional tolerance.", "draw",
            "session_id" to "string", "x" to "integer", "y" to "integer", "color" to "string", "tolerance" to "integer",
            required = listOf("session_id", "x", "y", "color")) { params, store ->
            mutate(params, store) {
                lab.engine.fill(it, params.int("x"), params.int("y"), colorParam(params, "color"), params.opt("tolerance", 0))
            }
        }

        add("pick_color", "Samples the composited color at (x, y) of the active frame.", "draw",
            "session_id" to "string", "x" to "integer", "y" to "integer",
            required = listOf("session_id", "x", "y")) { params, store ->
            val session = sessionOf(params, store)
            val argb = lab.engine.pickColor(session.project, params.int("x"), params.int("y"))
            jsonobj {
                put("session_id", session.id)
                put("argb", argb)
                put("hex", hexArgb(argb))
            }
        }

        add("replace_color", "Replaces pixels within tolerance of 'from' with 'to' on the active cel.", "draw",
            "session_id" to "string", "from" to "string", "to" to "string", "tolerance" to "integer",
            required = listOf("session_id", "from", "to")) { params, store ->
            mutate(params, store) {
                lab.engine.replaceColor(it, colorParam(params, "from"), colorParam(params, "to"), params.opt("tolerance", 0))
            }
        }

        add("erase_pixels", "Erases (makes transparent) a list of {x, y} points.", "draw",
            "session_id" to "string", "points" to "array", required = listOf("session_id", "points")) { params, store ->
            mutate(params, store) { lab.engine.erasePixels(it, pointsParam(params)) }
        }

        // ---- layer ----

        add("layer_add", "Appends a new layer on top of the stack and activates it.", "layer",
            "session_id" to "string", "name" to "string", required = listOf("session_id", "name")) { params, store ->
            mutate(params, store) { lab.engine.addLayer(it, params.string("name")) }
        }

        add("layer_remove", "Removes a layer and all of its cels; one layer must remain.", "layer",
            "session_id" to "string", "layer_id" to "integer", required = listOf("session_id", "layer_id")) { params, store ->
            mutate(params, store) { lab.engine.removeLayer(it, params.int("layer_id")) }
        }

        add("layer_rename", "Renames a layer.", "layer",
            "session_id" to "string", "layer_id" to "integer", "name" to "string",
            required = listOf("session_id", "layer_id", "name")) { params, store ->
            mutate(params, store) { lab.engine.renameLayer(it, params.int("layer_id"), params.string("name")) }
        }

        add("layer_move", "Moves a layer to stack index to_index (0 renders first).", "layer",
            "session_id" to "string", "layer_id" to "integer", "to_index" to "integer",
            required = listOf("session_id", "layer_id", "to_index")) { params, store ->
            mutate(params, store) { lab.engine.moveLayer(it, params.int("layer_id"), params.int("to_index")) }
        }

        add("layer_set_opacity", "Sets a layer opacity in [0, 1].", "layer",
            "session_id" to "string", "layer_id" to "integer", "opacity" to "number",
            required = listOf("session_id", "layer_id", "opacity")) { params, store ->
            mutate(params, store) { lab.engine.setLayerOpacity(it, params.int("layer_id"), params.double("opacity").toFloat()) }
        }

        add("layer_set_visible", "Shows or hides a layer (hidden layers keep their data).", "layer",
            "session_id" to "string", "layer_id" to "integer", "visible" to "boolean",
            required = listOf("session_id", "layer_id", "visible")) { params, store ->
            mutate(params, store) { lab.engine.setLayerVisible(it, params.int("layer_id"), params.bool("visible")) }
        }

        add("layer_set_locked", "Locks or unlocks a layer (locked layers reject pixel writes).", "layer",
            "session_id" to "string", "layer_id" to "integer", "locked" to "boolean",
            required = listOf("session_id", "layer_id", "locked")) { params, store ->
            mutate(params, store) { lab.engine.setLayerLocked(it, params.int("layer_id"), params.bool("locked")) }
        }

        add("layer_list", "Lists the layer stack bottom-up with activity flags.", "layer",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            jsonobj {
                put("session_id", session.id)
                put("layers", layersJson(session.project))
            }
        }

        // ---- frame ----

        add("frame_add", "Adds a frame after after_index (default: last) sharing the source cels.", "frame",
            "session_id" to "string", "after_index" to "integer", required = listOf("session_id")) { params, store ->
            val after = optionalInt(params, "after_index") ?: sessionOf(params, store).project.frameCount - 1
            mutate(params, store) { lab.animation.addFrame(it, after) }
        }

        add("frame_clone", "Duplicates the frame at frame_index and activates the clone.", "frame",
            "session_id" to "string", "frame_index" to "integer", required = listOf("session_id", "frame_index")) { params, store ->
            mutate(params, store) { lab.animation.cloneFrame(it, params.int("frame_index")) }
        }

        add("frame_delete", "Deletes the frame at frame_index; one frame must remain.", "frame",
            "session_id" to "string", "frame_index" to "integer", required = listOf("session_id", "frame_index")) { params, store ->
            mutate(params, store) { lab.animation.deleteFrame(it, params.int("frame_index")) }
        }

        add("frame_move", "Moves the frame at from_index to to_index.", "frame",
            "session_id" to "string", "from_index" to "integer", "to_index" to "integer",
            required = listOf("session_id", "from_index", "to_index")) { params, store ->
            mutate(params, store) { lab.animation.moveFrame(it, params.int("from_index"), params.int("to_index")) }
        }

        add("frame_set_duration", "Sets or clears (null) the per-frame duration override in ms.", "frame",
            "session_id" to "string", "frame_index" to "integer", "duration_ms" to "integer",
            required = listOf("session_id", "frame_index")) { params, store ->
            mutate(params, store) { lab.animation.setFrameDuration(it, params.int("frame_index"), optionalInt(params, "duration_ms")) }
        }

        add("frame_list", "Lists the timeline with per-frame durations and cel counts.", "frame",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            jsonobj {
                put("session_id", session.id)
                put("frames", framesJson(session.project))
            }
        }

        // ---- palette ----

        add("palette_list", "Lists every built-in palette with its hex colors.", "palette") { _, _ ->
            jsonobj {
                put(
                    "palettes",
                    jsonarray {
                        for (palette in BuiltInPalettes.all()) add(paletteInfo(palette))
                    },
                )
            }
        }

        add("palette_switch", "Switches a session's active palette (existing pixels are not remapped).", "palette",
            "session_id" to "string", "palette_id" to "string",
            required = listOf("session_id", "palette_id")) { params, store ->
            val palette = paletteParam(params)
                ?: throw IllegalArgumentException("missing parameter 'palette_id'")
            mutate(params, store) { it.withPalette(palette) }.toMutable()
                .put("palette", paletteInfo(palette))
                .build()
        }

        add("palette_closest_color", "Finds the palette color closest (CIELAB) to a given color.", "palette",
            "color" to "string", "session_id" to "string", required = listOf("color")) { params, store ->
            val color = colorParam(params, "color")
            val palette = if (params.has("session_id")) sessionOf(params, store).project.palette else BuiltInPalettes.PICO8
            val index = palette.findClosest(color)
            jsonobj {
                put("palette_id", palette.id)
                put("index", index)
                put("hex", hexArgb(palette[index]))
                put("argb", palette[index])
            }
        }

        // ---- convert ----

        add("convert_image",
            "Converts a raw pixel array (width x height ARGB ints) into pixel art; without pixels, returns usage instructions.",
            "convert",
            "pixels" to "array", "width" to "integer", "height" to "integer", "target_width" to "integer",
            "target_height" to "integer", "color_count" to "integer", "palette_id" to "string",
            "algorithm" to "string", "dither" to "string") { params, _ ->
            val image = imageParam(params) ?: return@add jsonobj {
                put(
                    "instructions",
                    "Provide 'pixels' (row-major ARGB integer array), 'width', 'height', and optionally 'target_width', " +
                        "'target_height', 'color_count', 'palette_id', 'algorithm' (median_cut|kmeans|octree) and " +
                        "'dither' (none|floyd_steinberg|atkinson|bayer_2x2|bayer_4x4|bayer_8x8|checkerboard)",
                )
                put(
                    "example",
                    jsonobj {
                        put("pixels", jsonarray { add(JsonNumber(0.0, "0")); add(JsonNumber(0.0, "0")); add(JsonNumber(0.0, "0")) })
                        put("width", 1)
                        put("height", 3)
                    },
                )
            }
            val result = lab.converter.convert(
                ConvertRequest(
                    image = image,
                    targetWidth = params.opt("target_width", image.width),
                    targetHeight = params.opt("target_height", image.height),
                    palette = paletteParam(params),
                    colorCount = params.opt("color_count", 16),
                    algorithm = quantizeAlgorithm(params),
                    dither = ditherAlgorithm(params, default = "floyd_steinberg"),
                ),
            ).unwrap("convert_image")
            jsonobj {
                put("width", result.width)
                put("height", result.height)
                put("colors_used", result.colorsUsed)
                put("transparent_pixels", result.transparentPixels)
                put("palette_id", result.palette.id)
                put("palette_color_count", result.palette.size)
                put("note", "output pixels are held in memory only; pass them back with canvas-level tools if needed")
            }
        }

        add("convert_analyze",
            "Analyzes a raw pixel array for suggested conversion settings; without pixels, returns usage instructions.",
            "convert", "pixels" to "array", "width" to "integer", "height" to "integer") { params, _ ->
            val image = imageParam(params) ?: return@add jsonobj {
                put("instructions", "Provide 'pixels' (row-major ARGB integer array), 'width' and 'height' to analyze")
            }
            val analysis = lab.converter.analyze(image).unwrap("convert_analyze")
            jsonobj {
                put("suggested_width", analysis.suggestedWidth)
                put("suggested_height", analysis.suggestedHeight)
                put("suggested_color_count", analysis.suggestedColorCount)
                put("suggested_palette_id", analysis.suggestedPaletteId)
                put("brightness", analysis.brightness)
                put("has_alpha", analysis.hasAlpha)
                put("notes", analysis.notes)
            }
        }

        add("convert_refine", "Cleans up the composited active frame (alpha snap, stray pixel removal).", "convert",
            "session_id" to "string", "snap_alpha_threshold" to "integer", "remove_stray_pixels" to "boolean",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val frame = project.compositeActiveFrame()
            val refined = lab.converter.refine(
                frame,
                RefineInstructions(
                    snapAlphaThreshold = optionalInt(params, "snap_alpha_threshold") ?: 128,
                    removeStrayPixels = params.opt("remove_stray_pixels", true),
                ),
            ).unwrap("convert_refine")
            val next = lab.engine.applyFrame(project, refined)
            store.update(session.id, next)
            jsonobj {
                put("session_id", session.id)
                put("width", refined.width)
                put("height", refined.height)
                put("opaque_pixels", opaquePixels(refined))
            }
        }

        // ---- anim ----

        add("anim_set_fps", "Sets the project-wide playback fps (1..24).", "anim",
            "session_id" to "string", "fps" to "integer", required = listOf("session_id", "fps")) { params, store ->
            mutate(params, store) { lab.animation.setFps(it, params.int("fps")) }
        }

        add("anim_tag", "Sets a named frame-span tag (or removes it with remove=true).", "anim",
            "session_id" to "string", "name" to "string", "start_frame" to "integer", "end_frame" to "integer",
            "remove" to "boolean", required = listOf("session_id", "name")) { params, store ->
            val session = sessionOf(params, store)
            val next = if (params.opt("remove", false)) {
                lab.animation.removeTag(session.project, params.string("name"))
            } else {
                lab.animation.setTag(session.project, params.string("name"), params.int("start_frame"), params.int("end_frame"))
            }
            store.update(session.id, next)
            jsonobj {
                put("session_id", session.id)
                put("tags", tagsJson(next))
            }
        }

        add("anim_breathe", "Appends breathing copies of every frame (3x total) with the 'breathe' tag.", "anim",
            "session_id" to "string", "amplitude_px" to "integer", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val amplitude = params.opt("amplitude_px", 1)
            val next = lab.animation.breathe(session.project, amplitude)
            store.update(session.id, next)
            jsonobj {
                put("session_id", session.id)
                put("frames", next.frameCount)
                put("amplitude_px", amplitude)
            }
        }

        add("anim_preview_count", "Counts the composited playback frames of a session.", "anim",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            jsonobj {
                put("session_id", session.id)
                put("preview_frames", lab.animation.previewFrames(session.project).size)
                put("width", session.project.width)
                put("height", session.project.height)
            }
        }

        // ---- text ----

        add("text_generate", "Renders text with a bitmap font and reports its size and lit pixel count.", "text",
            "text" to "string", "color" to "string", "font" to "string", "spacing" to "integer", "scale" to "integer",
            required = listOf("text")) { params, _ ->
            val frame = lab.template.generateText(
                text = params.string("text"),
                color = if (params.has("color")) colorParam(params, "color") else 0xFFFFFFFF.toInt(),
                font = fontParam(params),
                spacing = params.opt("spacing", 1),
                scale = params.opt("scale", 1),
            )
            jsonobj {
                put("width", frame.width)
                put("height", frame.height)
                put("lit_pixels", opaquePixels(frame))
            }
        }

        // ---- template ----

        add("template_apply", "Instantiates a built-in template into a session (new session when omitted).", "template",
            "template_id" to "string", "palette_id" to "string", "session_id" to "string",
            required = listOf("template_id")) { params, store ->
            val project = lab.template.apply(params.string("template_id"), paletteParam(params))
            val session = if (params.has("session_id")) {
                val existing = sessionOf(params, store)
                requireNotNull(store.update(existing.id, project)) { "session '${existing.id}' vanished" }
            } else {
                store.newSession(project.palette, project.width, project.height, project.name).also {
                    store.update(it.id, project)
                }
            }
            projectSummary(session, project)
        }

        add("template_list", "Lists every built-in sprite template with its metadata.", "template") { _, _ ->
            jsonobj {
                put(
                    "templates",
                    jsonarray {
                        for (id in lab.template.templateIds()) {
                            val info = lab.template.templateInfo(id) ?: continue
                            add(
                                jsonobj {
                                    put("id", info.id)
                                    put("name", info.name)
                                    put("width", info.width)
                                    put("height", info.height)
                                    put("frames", info.frames)
                                    put("layers", info.layers)
                                    put("description", info.description)
                                },
                            )
                        }
                    },
                )
            }
        }

        // ---- export ----

        add("export_png", "Exports one composited frame as PNG; returns the byte count.", "export",
            "session_id" to "string", "frame_index" to "integer", "scale" to "integer",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val frameIndex = params.opt("frame_index", project.activeFrameIndex)
            val scale = params.opt("scale", 1)
            val bytes = lab.exporter.exportPng(project, frameIndex, scale).unwrap("export_png")
            jsonobj {
                put("session_id", session.id)
                put("format", "png")
                put("byte_count", bytes.size)
                put("frame_index", frameIndex)
                put("width", project.width * scale)
                put("height", project.height * scale)
            }
        }

        add("export_spritesheet", "Exports all frames as a PNG spritesheet; returns the byte count.", "export",
            "session_id" to "string", "scale" to "integer", "layout" to "string", "columns" to "integer",
            "margin" to "integer", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val layoutName = params.opt("layout", "grid")
            val layout = when (layoutName.trim().lowercase()) {
                "grid" -> SpritesheetLayout.GRID
                "horizontal" -> SpritesheetLayout.HORIZONTAL
                "vertical" -> SpritesheetLayout.VERTICAL
                else -> throw IllegalArgumentException("layout must be grid, horizontal or vertical")
            }
            val bytes = lab.exporter.exportSpritesheet(
                project, params.opt("scale", 1), layout, params.opt("columns", 8), params.opt("margin", 0),
            ).unwrap("export_spritesheet")
            jsonobj {
                put("session_id", session.id)
                put("format", "png")
                put("layout", layoutName)
                put("byte_count", bytes.size)
                put("frames", project.frameCount)
            }
        }

        add("export_gif", "Exports the animation as GIF89a; returns the byte count.", "export",
            "session_id" to "string", "loop_count" to "integer", "dither" to "string",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val bytes = lab.exporter.exportGif(
                project, params.opt("loop_count", 0), ditherAlgorithm(params, default = "floyd_steinberg"),
            ).unwrap("export_gif")
            jsonobj {
                put("session_id", session.id)
                put("format", "gif")
                put("byte_count", bytes.size)
                put("frames", project.frameCount)
                put("fps", project.fps)
            }
        }

        add("export_apng", "Exports the animation as APNG; returns the byte count.", "export",
            "session_id" to "string", "loop_count" to "integer", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val bytes = lab.exporter.exportApng(project, params.opt("loop_count", 0)).unwrap("export_apng")
            jsonobj {
                put("session_id", session.id)
                put("format", "apng")
                put("byte_count", bytes.size)
                put("frames", project.frameCount)
            }
        }

        add("export_codex_pet",
            "Exports the Codex companion-pet ZIP (spritesheet.png + pet.json); returns the byte count and track summary.",
            "export", "session_id" to "string", "pet_name" to "string", "tracks" to "object",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val petName = params.opt("pet_name", project.name)
            val tracks = trackMapParam(params, project.frameCount)
            val bytes = lab.exporter.exportCodexPet(petName, project, tracks).unwrap("export_codex_pet")
            jsonobj {
                put("session_id", session.id)
                put("format", "zip")
                put("byte_count", bytes.size)
                put("pet_name", petName)
                put(
                    "tracks",
                    jsonarray {
                        for ((name, range) in tracks) {
                            add(
                                jsonobj {
                                    put("name", name)
                                    put("start_frame", range.first)
                                    put("end_frame", range.last)
                                    put("frames", range.last - range.first + 1)
                                },
                            )
                        }
                    },
                )
            }
        }

        // ---- project ----

        add("project_new", "Creates a new blank project session (alias of canvas_create; each edge must stay within 1..8192).", "project",
            "name" to "string", "width" to "integer", "height" to "integer", "palette_id" to "string") { params, store ->
            val palette = paletteParam(params) ?: BuiltInPalettes.PICO8
            val width = params.opt("width", 16)
            val height = params.opt("height", 16)
            requireSize(width, height)
            val session = store.newSession(palette, width, height, params.opt("name", "untitled"))
            projectSummary(session, session.project)
        }

        add("project_state", "Dumps full session state: summary, layers, frames, tags and undo history.", "project",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            jsonobj {
                putAllSummary(projectSummary(session, project))
                put("layers", layersJson(project))
                put("frames", framesJson(project))
                put("tags", tagsJson(project))
                put("can_undo", lab.engine.canUndo(project.id))
                put("can_redo", lab.engine.canRedo(project.id))
                put(
                    "history",
                    jsonarray {
                        for (entry in lab.engine.historyInfo(project.id)) {
                            add(
                                jsonobj {
                                    put("label", entry.label)
                                    put("timestamp", entry.timestamp)
                                },
                            )
                        }
                    },
                )
            }
        }

        add("project_list", "Lists every live session with size and frame summaries.", "project") { _, store ->
            jsonobj {
                put("sessions", JsonArray(store.list()))
                put("count", store.sessionCount)
            }
        }

        add("project_undo", "Undoes the latest change of a session's project.", "project",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val restored = lab.engine.undo(session.project.id)
            jsonobj {
                put("session_id", session.id)
                put("undone", restored != null)
                if (restored != null) {
                    store.update(session.id, restored)
                    putAllSummary(projectSummary(session, restored))
                }
            }
        }

        add("project_redo", "Redoes the latest undone change of a session's project.", "project",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val restored = lab.engine.redo(session.project.id)
            jsonobj {
                put("session_id", session.id)
                put("redone", restored != null)
                if (restored != null) {
                    store.update(session.id, restored)
                    putAllSummary(projectSummary(session, restored))
                }
            }
        }

        return tools.toList() to schemas
    }
}

/** Copies every entry of [summary] into a [com.pixellab.mcp.json.MutableJsonObject] builder. */
private fun com.pixellab.mcp.json.MutableJsonObject.putAllSummary(summary: JsonObject) {
    for ((key, value) in summary.entries) put(key, value)
}
