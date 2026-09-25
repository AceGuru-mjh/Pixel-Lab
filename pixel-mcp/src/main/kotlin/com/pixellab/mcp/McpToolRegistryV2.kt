package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.core.animation.AnimationEffects
import com.pixellab.core.export.AsepriteMetadata
import com.pixellab.core.export.SpritesheetLayout
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.core.palette.ColorHarmony
import com.pixellab.core.palette.PaletteIO
import com.pixellab.core.palette.PaletteLibrary
import com.pixellab.core.project.ProjectCodec
import com.pixellab.core.template.Font5x7
import com.pixellab.core.template.Font8x8
import com.pixellab.core.tools.Anchor
import com.pixellab.core.tools.BlendMode
import com.pixellab.core.tools.BrushShape
import com.pixellab.core.tools.BrushSpec
import com.pixellab.core.tools.GeometryShapes
import com.pixellab.core.tools.PixelToolbox
import com.pixellab.core.tools.Selection
import com.pixellab.core.tools.SelectionOps
import com.pixellab.core.tools.SymmetryEngine
import com.pixellab.core.tools.SymmetryMode
import com.pixellab.core.tools.TransformOps
import com.pixellab.mcp.json.JsonArray
import com.pixellab.mcp.json.JsonBoolean
import com.pixellab.mcp.json.JsonElement
import com.pixellab.mcp.json.JsonNull
import com.pixellab.mcp.json.JsonNumber
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonarray
import com.pixellab.mcp.json.jsonobj
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Second-tier MCP tool registry for Pixel Lab: 35 additional tools covering
 * advanced shapes, brushes, selections, cel transforms, blend modes,
 * symmetry, animation effects, the extended palette library, styled text,
 * templates, project (de)serialization and Aseprite metadata export.
 *
 * The registry reuses the [McpTool] data class, the [PixelSessionStore]
 * session model and the defensive style of [McpToolRegistry] (v1): handlers
 * validate parameters eagerly (raising [IllegalArgumentException], which
 * [execute] maps to a [McpToolException] / JSON-RPC `-32602`), mutating
 * handlers always write the resulting project back into the store, and tool
 * execution is serialized through one mutex because the undo history inside
 * the engine is per-instance state.
 *
 * This object is self-hosting: it lazily creates its own [PixelLab]
 * (plus a [PixelToolbox] facade and an [AnimationEffects] dispatcher), so
 * hosts that only want the second tier do not need to wire anything beyond
 * a [PixelSessionStore].
 */
object McpToolRegistryV2 {

    /** Maximum characters of serialized text returned inline by tools. */
    private const val TEXT_RESULT_LIMIT: Int = 4000

    /** Upper bound for point lists echoed back to callers. */
    private const val POINT_ECHO_LIMIT: Int = 2048

    /** Shared lab instance backing every v2 handler (created on first use). */
    private val lab: PixelLab by lazy { PixelLab.create() }

    /** High-level toolbox over the shared engine (one undo entry per op). */
    private val toolbox: PixelToolbox by lazy { PixelToolbox(lab.engine) }

    /** Name-dispatched animation effects over the shared config. */
    private val effects: AnimationEffects by lazy { AnimationEffects(lab.config) }

    /** Serializes tool runs, mirroring v1 (engine undo state is not thread-safe). */
    private val mutex = Mutex()

    private val byName: Map<String, McpTool>
    private val schemas: Map<String, JsonObject>

    init {
        val built = buildTools()
        byName = built.first.associateBy { it.name }
        schemas = built.second
    }

    /** All v2 tools in registration order. */
    fun tools(): List<McpTool> = byName.values.toList()

    /** The v2 tool registered as [name], or null. */
    fun tool(name: String): McpTool? = byName[name]

    /** Simplified JSON-schema description of a tool's parameters. */
    fun inputSchema(name: String): JsonObject = schemas[name] ?: jsonobj {
        put("type", "object")
        put("properties", jsonobj { })
    }

    /**
     * Runs the v2 tool [name] with [params] against [store]. Unknown tools
     * and parameter errors throw [McpToolException]; other exceptions signal
     * tool execution failures (surfaced as MCP `isError` envelopes).
     */
    suspend fun execute(name: String, params: JsonObject, store: PixelSessionStore): JsonObject {
        val tool = byName[name] ?: throw McpToolException("unknown tool '$name'", JsonRpc.METHOD_NOT_FOUND)
        return try {
            mutex.withLock { tool.handler(params, store) }
        } catch (error: IllegalArgumentException) {
            throw McpToolException(error.message ?: "invalid parameters for tool '$name'")
        }
    }

    // ------------------------------------------------------------------
    // Shared parameter helpers (v1 style, defensive)
    // ------------------------------------------------------------------

    /** Session referenced by the `session_id` parameter (auto-created when unknown). */
    private fun sessionOf(params: JsonObject, store: PixelSessionStore): PixelSessionStore.SessionState {
        val id = params.string("session_id")
        return requireNotNull(store.get(id, create = true)) { "session '$id' unavailable" }
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
                if (raw.value != Math.floor(raw.value) ||
                    raw.value < Int.MIN_VALUE.toDouble() || raw.value > Int.MAX_VALUE.toDouble()
                ) {
                    throw IllegalArgumentException("parameter '$key' must be an ARGB integer")
                }
                raw.value.toInt()
            }
            else -> throw IllegalArgumentException("parameter '$key' must be '#RRGGBB'/'#AARRGGBB' or an ARGB integer")
        }
    }

    /** Parses `#RRGGBB` / `#AARRGGBB` (the `#` is optional) into an ARGB int. */
    /** Parses an optional color element ("#RRGGBB", ARGB integer or null). */
    private fun optionalColor(raw: JsonElement?): Int? = when (raw) {
        null, is JsonNull -> null
        is JsonString -> parseHexColor(raw.value)
        is JsonNumber -> {
            if (raw.value == Math.floor(raw.value) &&
                raw.value >= Int.MIN_VALUE.toDouble() && raw.value <= Int.MAX_VALUE.toDouble()
            ) {
                raw.value.toInt()
            } else {
                throw IllegalArgumentException("color must be an ARGB integer")
            }
        }
        else -> throw IllegalArgumentException("color must be '#RRGGBB' or an ARGB integer")
    }

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

    /** Rectangular selection parameter from `x`/`y`/`width`/`height`. */
    private fun selectionParam(params: JsonObject): Selection {
        val x = params.int("x")
        val y = params.int("y")
        val width = params.int("width")
        val height = params.int("height")
        require(width >= 1) { "'width' must be >= 1 (was $width)" }
        require(height >= 1) { "'height' must be >= 1 (was $height)" }
        return Selection(x, y, width, height)
    }

    /** Symmetry mode by wire name; defaults to [SymmetryMode.OFF]. */
    private fun symmetryMode(params: JsonObject, default: String = "off"): SymmetryMode =
        when (params.opt("symmetry", default).trim().lowercase()) {
            "off" -> SymmetryMode.OFF
            "horizontal" -> SymmetryMode.HORIZONTAL
            "vertical" -> SymmetryMode.VERTICAL
            "four_way", "fourway", "4way" -> SymmetryMode.FOUR_WAY
            "tile" -> SymmetryMode.TILE
            else -> throw IllegalArgumentException("symmetry must be off, horizontal, vertical, four_way or tile")
        }

    /** Blend mode by wire name; defaults to [BlendMode.NORMAL]. */
    private fun blendModeParam(params: JsonObject): BlendMode =
        when (params.opt("mode", "normal").trim().lowercase()) {
            "normal" -> BlendMode.NORMAL
            "multiply" -> BlendMode.MULTIPLY
            "screen" -> BlendMode.SCREEN
            "add" -> BlendMode.ADD
            "subtract" -> BlendMode.SUBTRACT
            "difference" -> BlendMode.DIFFERENCE
            "lighten" -> BlendMode.LIGHTEN
            "darken" -> BlendMode.DARKEN
            "overlay" -> BlendMode.OVERLAY
            else -> throw IllegalArgumentException("mode must be normal, multiply, screen, add, subtract, difference, lighten, darken or overlay")
        }

    /** Brush shape by wire name; defaults to [BrushShape.SQUARE]. */
    private fun brushShapeParam(params: JsonObject): BrushShape =
        when (params.opt("brush_shape", "square").trim().lowercase()) {
            "square" -> BrushShape.SQUARE
            "circle" -> BrushShape.CIRCLE
            "cross" -> BrushShape.CROSS
            "diagonal" -> BrushShape.DIAGONAL
            "noise" -> BrushShape.NOISE
            else -> throw IllegalArgumentException("brush_shape must be square, circle, cross, diagonal or noise")
        }

    /** Brush spec from `brush_shape`/`brush_size`/`noise_density`. */
    private fun brushSpecParam(params: JsonObject): BrushSpec {
        val size = params.opt("brush_size", 1)
        require(size in 1..32) { "'brush_size' must be in [1, 32] (was $size)" }
        val density = params.opt("noise_density", 0.5)
        require(!density.isNaN() && density in 0.0..1.0) { "'noise_density' must be in [0, 1] (was $density)" }
        return BrushSpec(shape = brushShapeParam(params), size = size, noiseDensity = density)
    }

    /** Zoom anchor by wire name; defaults to [Anchor.CENTER]. */
    private fun anchorParam(params: JsonObject): Anchor =
        when (params.opt("anchor", "center").trim().lowercase()) {
            "top_left", "topleft" -> Anchor.TOP_LEFT
            "center" -> Anchor.CENTER
            "bottom_right", "bottomright" -> Anchor.BOTTOM_RIGHT
            else -> throw IllegalArgumentException("anchor must be top_left, center or bottom_right")
        }

    /** Spritesheet layout by wire name; defaults to [SpritesheetLayout.GRID]. */
    private fun layoutParam(params: JsonObject): SpritesheetLayout =
        when (params.opt("layout", "grid").trim().lowercase()) {
            "grid" -> SpritesheetLayout.GRID
            "horizontal" -> SpritesheetLayout.HORIZONTAL
            "vertical" -> SpritesheetLayout.VERTICAL
            else -> throw IllegalArgumentException("layout must be grid, horizontal or vertical")
        }

    /**
     * Positional effect parameters: an array of scalars. Integral numbers
     * become [Int], fractional ones [Double]; strings, booleans and nulls
     * pass through — exactly the lenient slots [AnimationEffects.apply]
     * understands (easing names, fade directions, hex colors).
     */
    private fun effectParams(params: JsonObject): List<Any?> {
        val raw = params.raw("params") ?: return emptyList()
        if (raw is JsonNull) return emptyList()
        val array = raw as? JsonArray
            ?: throw IllegalArgumentException("parameter 'params' must be an array of scalars")
        return array.items.map { item ->
            when (item) {
                is JsonNumber ->
                    if (item.value == Math.floor(item.value) &&
                        item.value >= Int.MIN_VALUE.toDouble() && item.value <= Int.MAX_VALUE.toDouble()
                    ) {
                        item.value.toInt()
                    } else {
                        item.value
                    }
                is JsonString -> item.value
                is JsonBoolean -> item.value
                JsonNull -> null
                else -> throw IllegalArgumentException(
                    "effect params must be numbers, strings, booleans or null",
                )
            }
        }
    }

    /** The palette addressed by `palette_id`, else the session's, else PICO-8. */
    private fun paletteForExport(params: JsonObject, store: PixelSessionStore): Palette {
        val raw = params.raw("palette_id")
        if (raw != null && raw !is JsonNull) {
            val id = (raw as? JsonString)?.value
                ?: throw IllegalArgumentException("parameter 'palette_id' must be a palette id string")
            return PaletteLibrary.byId(id)
                ?: throw IllegalArgumentException("unknown palette '$id' (see palette_library_list)")
        }
        if (params.has("session_id")) return sessionOf(params, store).project.palette
        return BuiltInPalettes.PICO8
    }

    /** Hex color list rendering of ARGB ints. */
    private fun hexList(colors: List<Int>): JsonArray = jsonarray {
        for (color in colors) add(JsonString(hexArgb(color)))
    }

    /** Non-zero (visible) pixels of [frame]. */
    private fun opaquePixels(frame: PixelFrame): Int = frame.pixels.count { it != 0 }

    /** The active cel, or a blank frame standing in for a missing cel. */
    private fun activeCelOrBlank(project: SpriteProject): PixelFrame =
        project.activeCel() ?: PixelFrame.blank(project.width, project.height)

    /**
     * Ellipse point set with a pure integer inside-test
     * (`dx^2*ry^2 + dy^2*rx^2 <= (rx*ry)^2`, Long math), clipped to the
     * canvas: filled selects the interior, outlined the interior pixels
     * with at least one 4-neighbor outside. Row-major deterministic order.
     */
    private fun ellipsePoints(
        cx: Int,
        cy: Int,
        rx: Int,
        ry: Int,
        filled: Boolean,
        canvasWidth: Int,
        canvasHeight: Int,
    ): List<PixelPoint> {
        val rx2 = rx.toLong() * rx
        val ry2 = ry.toLong() * ry
        val limit = rx2 * ry2

        fun inside(x: Int, y: Int): Boolean {
            val dx = (x - cx).toLong()
            val dy = (y - cy).toLong()
            return dx * dx * ry2 + dy * dy * rx2 <= limit
        }

        val out = ArrayList<PixelPoint>()
        val x0 = maxOf(0, cx - rx)
        val x1 = minOf(canvasWidth - 1, cx + rx)
        val y0 = maxOf(0, cy - ry)
        val y1 = minOf(canvasHeight - 1, cy + ry)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (!inside(x, y)) continue
                val boundary = !inside(x - 1, y) || !inside(x + 1, y) ||
                    !inside(x, y - 1) || !inside(x, y + 1)
                if (filled || boundary) out.add(PixelPoint(x, y))
            }
        }
        return out
    }

    /**
     * Star vertex coordinates (the same math as [GeometryShapes.star]):
     * `tips` outer tips alternating with inner notches, first tip at
     * [rotationDeg]. Returned as raw coordinates (may be negative).
     */
    private fun starCoords(cx: Int, cy: Int, outer: Int, inner: Int, tips: Int, rotationDeg: Double): List<Pair<Int, Int>> {
        val count = tips * 2
        val out = ArrayList<Pair<Int, Int>>(count)
        for (i in 0 until count) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = Math.toRadians(rotationDeg) + i * Math.PI / tips
            out.add(Pair((cx + radius * cos(angle)).roundToInt(), (cy + radius * sin(angle)).roundToInt()))
        }
        return out
    }

    /**
     * Scanline-fills the closed polygon of [coords] (even-odd rule, see
     * [GeometryShapes.fillPolygon]), transparently translating the shape
     * into the non-negative quadrant when vertices sit left/above the
     * canvas and translating the result back, dropping negative-quadrant
     * points ([PixelPoint] invariant).
     */
    private fun filledPolygonPoints(coords: List<Pair<Int, Int>>, canvasWidth: Int, canvasHeight: Int): List<PixelPoint> {
        val shiftX = -minOf(0, coords.minOf { it.first })
        val shiftY = -minOf(0, coords.minOf { it.second })
        val vertices = coords.map { PixelPoint(it.first + shiftX, it.second + shiftY) }
        val filled = GeometryShapes.fillPolygon(vertices, canvasWidth + shiftX, canvasHeight + shiftY)
        val out = ArrayList<PixelPoint>(filled.size)
        for (point in filled) {
            val x = point.x - shiftX
            val y = point.y - shiftY
            if (x >= 0 && y >= 0) out.add(PixelPoint(x, y))
        }
        return out
    }

    /**
     * Mutating draw of a rasterized shape: computes the point set against
     * the current project, paints it with one `drawPixels` undo entry and
     * returns the summary plus the point count.
     */
    private fun mutateShape(
        params: JsonObject,
        store: PixelSessionStore,
        points: (SpriteProject) -> List<PixelPoint>,
    ): JsonObject {
        val color = colorParam(params, "color")
        val session = sessionOf(params, store)
        val project = session.project
        val raster = points(project)
        require(raster.isNotEmpty()) { "the rasterized shape is empty — check the geometry parameters" }
        val next = toolbox.drawShape(project, raster, color)
        store.update(session.id, next)
        return projectSummary(session, next).toMutable()
            .put("points_drawn", raster.size)
            .build()
    }

    // ------------------------------------------------------------------
    // Registry construction
    // ------------------------------------------------------------------

    /** Builds the v2 tool list plus the per-tool input schemas. */
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

        // ---- shape ----

        add("shape_line", "Draws a thick Bresenham line between two points on the active cel.", "shape",
            "session_id" to "string", "x0" to "integer", "y0" to "integer", "x1" to "integer", "y1" to "integer",
            "color" to "string", "thickness" to "integer",
            required = listOf("session_id", "x0", "y0", "x1", "y1", "color")) { params, store ->
            val thickness = params.opt("thickness", 1)
            require(thickness in 1..64) { "'thickness' must be in [1, 64] (was $thickness)" }
            val x0 = params.int("x0")
            val y0 = params.int("y0")
            val x1 = params.int("x1")
            val y1 = params.int("y1")
            require(x0 >= 0 && y0 >= 0 && x1 >= 0 && y1 >= 0) {
                "line coordinates must be non-negative"
            }
            mutateShape(params, store) { GeometryShapes.thickPolyline(listOf(PixelPoint(x0, y0), PixelPoint(x1, y1)), thickness) }
        }

        add("shape_rect", "Draws a rectangle (outlined or filled) at (x, y) of width x height.", "shape",
            "session_id" to "string", "x" to "integer", "y" to "integer", "width" to "integer",
            "height" to "integer", "color" to "string", "filled" to "boolean",
            required = listOf("session_id", "x", "y", "width", "height", "color")) { params, store ->
            val x = params.int("x")
            val y = params.int("y")
            val width = params.int("width")
            val height = params.int("height")
            require(x >= 0 && y >= 0) { "rectangle origin must be non-negative" }
            require(width >= 1) { "'width' must be >= 1 (was $width)" }
            require(height >= 1) { "'height' must be >= 1 (was $height)" }
            val filled = params.opt("filled", false)
            mutateShape(params, store) { project ->
                if (filled) {
                    val corners = listOf(
                        Pair(x, y), Pair(x + width, y),
                        Pair(x + width, y + height), Pair(x, y + height),
                    )
                    filledPolygonPoints(corners, project.width, project.height)
                } else {
                    GeometryShapes.roundedRect(x, y, width, height, 0)
                }
            }
        }

        add("shape_ellipse", "Draws an ellipse around (cx, cy) with separate x/y radii, outlined or filled.", "shape",
            "session_id" to "string", "cx" to "integer", "cy" to "integer", "radius_x" to "integer",
            "radius_y" to "integer", "color" to "string", "filled" to "boolean",
            required = listOf("session_id", "cx", "cy", "radius_x", "radius_y", "color")) { params, store ->
            val cx = params.int("cx")
            val cy = params.int("cy")
            val rx = params.int("radius_x")
            val ry = params.int("radius_y")
            require(rx in 0..4096) { "'radius_x' must be in [0, 4096] (was $rx)" }
            require(ry in 0..4096) { "'radius_y' must be in [0, 4096] (was $ry)" }
            val filled = params.opt("filled", false)
            mutateShape(params, store) { project ->
                ellipsePoints(cx, cy, rx, ry, filled, project.width, project.height)
            }
        }

        add("shape_polygon", "Draws a closed polygon from a vertex list, outlined or scanline-filled.", "shape",
            "session_id" to "string", "points" to "array", "color" to "string", "filled" to "boolean",
            required = listOf("session_id", "points", "color")) { params, store ->
            val vertices = pointsParam(params)
            require(vertices.size >= 3) { "a polygon needs at least 3 vertices (was ${vertices.size})" }
            val filled = params.opt("filled", false)
            mutateShape(params, store) { project ->
                if (filled) {
                    filledPolygonPoints(vertices.map { Pair(it.x, it.y) }, project.width, project.height)
                } else {
                    GeometryShapes.thickPolyline(vertices + vertices[0], 1)
                }
            }
        }

        add("shape_star", "Draws a star with 'tips' tips alternating outer/inner radii, outlined or filled.", "shape",
            "session_id" to "string", "cx" to "integer", "cy" to "integer", "outer_radius" to "integer",
            "inner_radius" to "integer", "tips" to "integer", "color" to "string", "filled" to "boolean",
            "rotation_deg" to "number",
            required = listOf("session_id", "cx", "cy", "outer_radius", "inner_radius", "color")) { params, store ->
            val cx = params.int("cx")
            val cy = params.int("cy")
            val outer = params.int("outer_radius")
            val inner = params.int("inner_radius")
            require(outer in 0..4096) { "'outer_radius' must be in [0, 4096] (was $outer)" }
            require(inner in 0..4096) { "'inner_radius' must be in [0, 4096] (was $inner)" }
            val tips = params.opt("tips", 5)
            require(tips in 2..64) { "'tips' must be in [2, 64] (was $tips)" }
            val rotation = params.opt("rotation_deg", 0.0)
            require(!rotation.isNaN() && rotation.isFinite()) { "'rotation_deg' must be finite" }
            val filled = params.opt("filled", false)
            mutateShape(params, store) { project ->
                if (!filled && rotation % 360.0 == 0.0) {
                    GeometryShapes.star(cx, cy, outer, inner, tips)
                } else {
                    val coords = starCoords(cx, cy, outer, inner, tips, rotation)
                    if (filled) {
                        filledPolygonPoints(coords, project.width, project.height)
                    } else {
                        val path = ArrayList<PixelPoint>(coords.size + 1)
                        for ((x, y) in coords) {
                            if (x >= 0 && y >= 0) path.add(PixelPoint(x, y))
                        }
                        if (path.isNotEmpty()) path.add(path[0])
                        GeometryShapes.thickPolyline(path, 1)
                    }
                }
            }
        }

        add("shape_bezier", "Draws a quadratic (3 points) or cubic (4 points) Bezier curve.", "shape",
            "session_id" to "string", "points" to "array", "color" to "string", "steps" to "integer",
            required = listOf("session_id", "points", "color")) { params, store ->
            val control = pointsParam(params)
            require(control.size == 3 || control.size == 4) {
                "shape_bezier needs 3 points (quadratic) or 4 points (cubic), got ${control.size}"
            }
            val steps = params.opt("steps", 32)
            require(steps in 1..1024) { "'steps' must be in [1, 1024] (was $steps)" }
            mutateShape(params, store) {
                if (control.size == 3) {
                    GeometryShapes.quadraticBezier(control[0], control[1], control[2], steps)
                } else {
                    GeometryShapes.cubicBezier(control[0], control[1], control[2], control[3], steps)
                }
            }
        }

        // ---- brush ----

        add("brush_draw", "Paints a brush stroke (shape/size/noise) with symmetry and pixel-perfect thinning.", "brush",
            "session_id" to "string", "points" to "array", "color" to "string", "brush_shape" to "string",
            "brush_size" to "integer", "noise_density" to "number", "symmetry" to "string",
            "pixel_perfect" to "boolean",
            required = listOf("session_id", "points", "color")) { params, store ->
            val spec = brushSpecParam(params)
            val symmetry = symmetryMode(params)
            val pixelPerfect = params.opt("pixel_perfect", false)
            val stroke = pointsParam(params)
            require(stroke.isNotEmpty()) { "brush_draw needs at least one stroke point" }
            mutate(params, store) { project ->
                toolbox.drawWithBrush(project, stroke, spec, colorParam(params, "color"), symmetry, pixelPerfect)
            }.toMutable()
                .put("brush_shape", spec.shape.name.lowercase())
                .put("brush_size", spec.size)
                .put("symmetry", symmetry.name.lowercase())
                .put("pixel_perfect", pixelPerfect)
                .put("stroke_points", stroke.size)
                .build()
        }

        // ---- selection ----

        add("selection_extract", "Extracts a rectangular region of the active cel as a standalone pixel array.", "selection",
            "session_id" to "string", "x" to "integer", "y" to "integer", "width" to "integer", "height" to "integer",
            required = listOf("session_id", "x", "y", "width", "height")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val sel = selectionParam(params)
            val patch = SelectionOps(project.width, project.height).extract(activeCelOrBlank(project), sel)
            jsonobj {
                put("session_id", session.id)
                put("x", sel.x)
                put("y", sel.y)
                put("width", patch.width)
                put("height", patch.height)
                put("pixels", JsonArray(patch.pixels.map { JsonNumber(it.toDouble(), it.toString()) }))
                put("opaque_pixels", opaquePixels(patch))
            }
        }

        add("selection_move", "Moves the selected pixels of the active cel by (dx, dy).", "selection",
            "session_id" to "string", "x" to "integer", "y" to "integer", "width" to "integer",
            "height" to "integer", "dx" to "integer", "dy" to "integer",
            required = listOf("session_id", "x", "y", "width", "height", "dx", "dy")) { params, store ->
            val dx = params.int("dx")
            val dy = params.int("dy")
            require(dx in -1048576..1048576 && dy in -1048576..1048576) {
                "dx/dy must stay within +-1048576"
            }
            val sel = selectionParam(params)
            mutate(params, store) { project -> toolbox.selectAndMove(project, sel, dx, dy) }
        }

        add("selection_flip_h", "Mirrors the selected region of the active cel left-right in place.", "selection",
            "session_id" to "string", "x" to "integer", "y" to "integer", "width" to "integer", "height" to "integer",
            required = listOf("session_id", "x", "y", "width", "height")) { params, store ->
            val sel = selectionParam(params)
            mutate(params, store) { project ->
                val cel = project.activeCel() ?: return@mutate project
                lab.engine.applyFrame(project, SelectionOps(project.width, project.height).flipSelectionH(cel, sel))
            }
        }

        add("selection_flip_v", "Mirrors the selected region of the active cel top-bottom in place.", "selection",
            "session_id" to "string", "x" to "integer", "y" to "integer", "width" to "integer", "height" to "integer",
            required = listOf("session_id", "x", "y", "width", "height")) { params, store ->
            val sel = selectionParam(params)
            mutate(params, store) { project ->
                val cel = project.activeCel() ?: return@mutate project
                lab.engine.applyFrame(project, SelectionOps(project.width, project.height).flipSelectionV(cel, sel))
            }
        }

        add("selection_rotate", "Rotates the (square) selected region of the active cel by 90 degrees.", "selection",
            "session_id" to "string", "x" to "integer", "y" to "integer", "width" to "integer", "height" to "integer",
            required = listOf("session_id", "x", "y", "width", "height")) { params, store ->
            val sel = selectionParam(params)
            require(sel.w == sel.h) { "selection_rotate requires a square selection (was ${sel.w}x${sel.h})" }
            mutate(params, store) { project ->
                val cel = project.activeCel() ?: return@mutate project
                lab.engine.applyFrame(project, SelectionOps(project.width, project.height).rotateSelection90(cel, sel))
            }
        }

        add("selection_magic", "Magic-wand selection: bounding box of the flood region around a seed pixel.", "selection",
            "session_id" to "string", "x" to "integer", "y" to "integer", "tolerance" to "integer",
            required = listOf("session_id", "x", "y")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val tolerance = params.opt("tolerance", 0)
            require(tolerance >= 0) { "'tolerance' must be >= 0 (was $tolerance)" }
            val sel = SelectionOps(project.width, project.height)
                .magicSelect(activeCelOrBlank(project), params.int("x"), params.int("y"), tolerance)
            jsonobj {
                put("session_id", session.id)
                put("x", sel.x)
                put("y", sel.y)
                put("width", sel.w)
                put("height", sel.h)
                put("tolerance", tolerance)
            }
        }

        // ---- transform ----

        add("transform_rotate", "Rotates the active cel by 90 degree quarter-turns (canvas may resize).", "transform",
            "session_id" to "string", "times" to "integer", required = listOf("session_id")) { params, store ->
            val times = params.opt("times", 1)
            require(times in 1..4) { "'times' must be in [1, 4] (was $times)" }
            mutate(params, store) { project ->
                toolbox.transformCel(project) { TransformOps.rotate90(it, times) }
            }
        }

        add("transform_scale", "Nearest-neighbor scales the active cel by an integer factor (canvas may resize).", "transform",
            "session_id" to "string", "factor" to "integer", "anchor" to "string",
            required = listOf("session_id", "factor")) { params, store ->
            val factor = params.int("factor")
            require(factor in 1..16) { "'factor' must be in [1, 16] (was $factor)" }
            val anchor = anchorParam(params)
            mutate(params, store) { project ->
                toolbox.transformCel(project) { TransformOps.scaleNearest(it, factor, anchor) }
            }.toMutable().put("factor", factor).put("anchor", anchor.name.lowercase()).build()
        }

        add("transform_grow", "Dilates the opaque region of the active cel, painting growth with a color.", "transform",
            "session_id" to "string", "color" to "string", "iterations" to "integer",
            required = listOf("session_id", "color")) { params, store ->
            val iterations = params.opt("iterations", 1)
            require(iterations in 0..1024) { "'iterations' must be in [0, 1024] (was $iterations)" }
            val color = colorParam(params, "color")
            mutate(params, store) { project ->
                toolbox.transformCel(project) { TransformOps.growRegion(it, color, iterations) }
            }.toMutable().put("iterations", iterations).build()
        }

        add("transform_shrink", "Erodes the opaque region of the active cel boundary pixel by pixel.", "transform",
            "session_id" to "string", "iterations" to "integer", required = listOf("session_id")) { params, store ->
            val iterations = params.opt("iterations", 1)
            require(iterations in 0..1024) { "'iterations' must be in [0, 1024] (was $iterations)" }
            mutate(params, store) { project ->
                toolbox.transformCel(project) { TransformOps.shrinkRegion(it, iterations) }
            }.toMutable().put("iterations", iterations).build()
        }

        add("transform_crop", "Crops the active cel (and canvas) to a w x h region at (x, y).", "transform",
            "session_id" to "string", "x" to "integer", "y" to "integer", "width" to "integer", "height" to "integer",
            required = listOf("session_id", "x", "y", "width", "height")) { params, store ->
            val x = params.int("x")
            val y = params.int("y")
            val width = params.int("width")
            val height = params.int("height")
            require(width >= 1) { "'width' must be >= 1 (was $width)" }
            require(height >= 1) { "'height' must be >= 1 (was $height)" }
            mutate(params, store) { project ->
                toolbox.transformCel(project) { TransformOps.crop(it, x, y, width, height) }
            }.toMutable().put("width", width).put("height", height).build()
        }

        add("transform_pad", "Surrounds the active cel (and canvas) with transparent padding.", "transform",
            "session_id" to "string", "left" to "integer", "top" to "integer", "right" to "integer",
            "bottom" to "integer", required = listOf("session_id")) { params, store ->
            val left = params.opt("left", 0)
            val top = params.opt("top", 0)
            val right = params.opt("right", 0)
            val bottom = params.opt("bottom", 0)
            require(left in 0..1024 && top in 0..1024 && right in 0..1024 && bottom in 0..1024) {
                "padding amounts must be in [0, 1024]"
            }
            mutate(params, store) { project ->
                toolbox.transformCel(project) { TransformOps.pad(it, left, top, right, bottom) }
            }.toMutable().put("left", left).put("top", top).put("right", right).put("bottom", bottom).build()
        }

        // ---- blend ----

        add("blend_apply", "Bakes a blend mode of the layer over the composite of everything below it.", "blend",
            "session_id" to "string", "layer_id" to "integer", "mode" to "string", "opacity" to "number",
            required = listOf("session_id", "layer_id")) { params, store ->
            val layerId = params.int("layer_id")
            val mode = blendModeParam(params)
            val opacity = params.opt("opacity", 1.0).toFloat()
            require(!opacity.isNaN() && opacity in 0f..1f) { "'opacity' must be in [0, 1] (was $opacity)" }
            mutate(params, store) { project -> toolbox.applyBlend(project, layerId, mode, opacity) }
                .toMutable().put("layer_id", layerId).put("mode", mode.name.lowercase()).put("opacity", opacity).build()
        }

        // ---- symmetry ----

        add("symmetry_reflect_preview", "Reflects a point set across the canvas symmetry axes; canvas untouched.", "symmetry",
            "session_id" to "string", "points" to "array", "symmetry" to "string",
            required = listOf("session_id", "points", "symmetry")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            if (!params.has("symmetry")) {
                throw IllegalArgumentException("missing parameter 'symmetry'")
            }
            val mode = symmetryMode(params)
            val reflected = SymmetryEngine(project.width, project.height).reflectPoints(pointsParam(params), mode)
            val echoed = reflected.take(POINT_ECHO_LIMIT)
            jsonobj {
                put("session_id", session.id)
                put("symmetry", mode.name.lowercase())
                put("point_count", reflected.size)
                put("truncated", reflected.size > echoed.size)
                put("points", jsonarray {
                    for (point in echoed) add(jsonobj { put("x", point.x); put("y", point.y) })
                })
            }
        }

        // ---- effect ----

        add("effect_apply", "Applies a procedural animation effect by name with positional parameters.", "effect",
            "session_id" to "string", "effect" to "string", "params" to "array",
            required = listOf("session_id", "effect")) { params, store ->
            val name = params.string("effect")
            val args = effectParams(params)
            mutate(params, store) { project -> effects.apply(name, project, *args.toTypedArray()) }
                .toMutable()
                .put("effect", name)
                .put("param_count", args.size)
                .build()
        }

        add("effect_list", "Lists every dispatchable animation effect name.", "effect") { _, _ ->
            val names = effects.effectNames()
            jsonobj {
                put("effects", JsonArray(names.map { JsonString(it) }))
                put("count", names.size)
            }
        }

        // ---- palette ----

        add("palette_library_list", "Lists every palette of the extended library (21 entries) with hex colors.", "palette") { _, _ ->
            val palettes = PaletteLibrary.all()
            jsonobj {
                put(
                    "palettes",
                    jsonarray {
                        for (palette in palettes) {
                            add(
                                jsonobj {
                                    put("id", palette.id)
                                    put("name", palette.name)
                                    put("color_count", palette.size)
                                    put("colors", hexList(palette.colors.toList()))
                                },
                            )
                        }
                    },
                )
                put("count", palettes.size)
            }
        }

        add("palette_harmony", "Builds a color harmony (complementary, triadic, ...) around a base color.", "palette",
            "base" to "string", "kind" to "string", "count" to "integer",
            required = listOf("base", "kind")) { params, _ ->
            val base = colorParam(params, "base")
            val kind = params.opt("kind", "complementary").trim().lowercase()
            val count = optionalInt(params, "count") ?: 5
            require(count in 1..64) { "'count' must be in [1, 64] (was $count)" }
            val colors = when (kind) {
                "complementary" -> ColorHarmony.complementary(base)
                "analogous" -> ColorHarmony.analogous(base, count)
                "triadic" -> ColorHarmony.triadic(base)
                "split_complementary", "splitcomplementary" -> ColorHarmony.splitComplementary(base)
                "tetradic" -> ColorHarmony.tetradic(base)
                "monochromatic", "mono" -> ColorHarmony.monochromatic(base, count)
                else -> throw IllegalArgumentException(
                    "kind must be complementary, analogous, triadic, split_complementary, tetradic or monochromatic",
                )
            }
            jsonobj {
                put("kind", kind)
                put("colors", hexList(colors))
                put("count", colors.size)
            }
        }

        add("palette_gradient", "Builds a perceptual (CIELAB) gradient between two colors.", "palette",
            "from" to "string", "to" to "string", "steps" to "integer",
            required = listOf("from", "to")) { params, _ ->
            val from = colorParam(params, "from")
            val to = colorParam(params, "to")
            val steps = params.opt("steps", 5)
            require(steps in 1..256) { "'steps' must be in [1, 256] (was $steps)" }
            val colors = ColorHarmony.gradient(from, to, steps)
            jsonobj {
                put("colors", hexList(colors))
                put("count", colors.size)
            }
        }

        add("palette_export_jasc", "Exports the session (or library) palette as JASC-PAL text.", "palette",
            "session_id" to "string", "palette_id" to "string") { params, store ->
            val palette = paletteForExport(params, store)
            val text = PaletteIO.toJasc(palette)
            jsonobj {
                put("palette_id", palette.id)
                put("format", "jasc-pal")
                put("color_count", palette.size)
                put("char_count", text.length)
                put("byte_count", text.toByteArray(Charsets.UTF_8).size)
                put("text", text)
            }
        }

        add("palette_export_gpl", "Exports the session (or library) palette as GIMP .gpl text.", "palette",
            "session_id" to "string", "palette_id" to "string") { params, store ->
            val palette = paletteForExport(params, store)
            val text = PaletteIO.toGpl(palette)
            jsonobj {
                put("palette_id", palette.id)
                put("format", "gimp-gpl")
                put("color_count", palette.size)
                put("char_count", text.length)
                put("byte_count", text.toByteArray(Charsets.UTF_8).size)
                put("text", text)
            }
        }

        add("palette_export_hex", "Exports the session (or library) palette as a #rrggbb hex list.", "palette",
            "session_id" to "string", "palette_id" to "string") { params, store ->
            val palette = paletteForExport(params, store)
            val text = PaletteIO.toHexList(palette)
            jsonobj {
                put("palette_id", palette.id)
                put("format", "hex-list")
                put("color_count", palette.size)
                put("char_count", text.length)
                put("byte_count", text.toByteArray(Charsets.UTF_8).size)
                put("text", text)
            }
        }

        // ---- text ----

        add("text_style_render",
            "Renders styled text (font/spacing/scale/color options) and reports size and lit pixel count.",
            "text",
            "text" to "string", "color" to "string", "font" to "string", "spacing" to "integer",
            "scale" to "integer", "options" to "object",
            required = listOf("text")) { params, _ ->
            val optionsRaw = when (val raw = params.raw("options")) {
                null, is JsonNull -> null
                is JsonObject -> raw
                else -> throw IllegalArgumentException("parameter 'options' must be an object")
            }
            fun element(key: String): JsonElement? = optionsRaw?.raw(key) ?: params.raw(key)
            val text = (element("text") as? JsonString)?.value
                ?: throw IllegalArgumentException("missing parameter 'text' (top level or inside 'options')")
            val font = when ((element("font") as? JsonString)?.value ?: "5x7") {
                "5x7" -> Font5x7
                "8x8" -> Font8x8
                else -> throw IllegalArgumentException("font must be '5x7' or '8x8'")
            }
            val color = when (val raw = element("color")) {
                null, is JsonNull -> 0xFFFFFFFF.toInt()
                is JsonString -> parseHexColor(raw.value)
                is JsonNumber -> {
                    if (raw.value != Math.floor(raw.value) ||
                        raw.value < Int.MIN_VALUE.toDouble() || raw.value > Int.MAX_VALUE.toDouble()
                    ) {
                        throw IllegalArgumentException("color must be an ARGB integer")
                    }
                    raw.value.toInt()
                }
                else -> throw IllegalArgumentException("color must be '#RRGGBB' or an ARGB integer")
            }
            fun optInt(key: String, default: Int): Int = when (val raw = element(key)) {
                null, is JsonNull -> default
                is JsonNumber ->
                    if (raw.value == Math.floor(raw.value) &&
                        raw.value >= Int.MIN_VALUE.toDouble() && raw.value <= Int.MAX_VALUE.toDouble()
                    ) {
                        raw.value.toInt()
                    } else {
                        throw IllegalArgumentException("'$key' must be an integer")
                    }
                else -> throw IllegalArgumentException("'$key' must be an integer")
            }
            val spacing = optInt("spacing", 1)
            val scale = optInt("scale", 1)
            require(spacing in 0..64) { "'spacing' must be in [0, 64] (was $spacing)" }
            require(scale in 1..16) { "'scale' must be in [1, 16] (was $scale)" }
            val alignment = when ((element("alignment") as? JsonString)?.value?.lowercase() ?: "left") {
                "left" -> com.pixellab.core.template.TextAlign.LEFT
                "center" -> com.pixellab.core.template.TextAlign.CENTER
                "right" -> com.pixellab.core.template.TextAlign.RIGHT
                else -> throw IllegalArgumentException("alignment must be 'left', 'center' or 'right'")
            }
            val lineSpacing = optInt("line_spacing", 1)
            val outlineColor = optionalColor(element("outline_color"))
            val glowColor = optionalColor(element("glow_color"))
            val shadowColor = optionalColor(element("shadow_color"))
            val options = com.pixellab.core.template.TextOptions(
                font = font,
                letterSpacing = spacing,
                lineSpacing = lineSpacing,
                alignment = alignment,
                scale = scale,
                outlineColor = outlineColor,
                glowColor = glowColor,
                shadowColor = shadowColor,
            )
            val frame = com.pixellab.core.template.TextStyler.render(text, color, options)
            jsonobj {
                put("width", frame.width)
                put("height", frame.height)
                put("lit_pixels", opaquePixels(frame))
                put("font", if (font === Font5x7) "5x7" else "8x8")
                put("spacing", spacing)
                put("scale", scale)
                put("styled", true)
                put("outline", outlineColor != null)
                put("glow", glowColor != null)
                put("shadow", shadowColor != null)
            }
        }

        // ---- template ----

        add("template_v2_list", "Lists the V2 sprite template catalog (mushroom, slime, ghost, explosion, star-spin, shield, potion, chest-open).", "template") { _, _ ->
            val entries = com.pixellab.core.template.TemplateLibraryV2.all()
            jsonobj {
                put(
                    "templates",
                    jsonarray {
                        for (entry in entries) {
                            add(
                                jsonobj {
                                    put("id", entry.id)
                                    put("name", entry.name)
                                    put("description", entry.description)
                                    put("width", entry.width)
                                    put("height", entry.height)
                                    put("frames", entry.frames)
                                    put("layers", entry.layers)
                                    put("palette_id", entry.paletteId)
                                },
                            )
                        }
                    },
                )
                put("count", entries.size)
                put("catalog", "v2")
            }
        }

        add("template_v2_apply", "Instantiates a V2 template (mushroom, slime, ghost, ...) into a session (new session when omitted).", "template",
            "template_id" to "string", "palette_id" to "string", "session_id" to "string",
            required = listOf("template_id")) { params, store ->
            val templateId = params.string("template_id")
            val palette = optionalPaletteParam(params)
            val project = com.pixellab.core.template.TemplateLibraryV2.build(templateId, palette)
            val session = if (params.has("session_id")) {
                val existing = sessionOf(params, store)
                requireNotNull(store.update(existing.id, project)) { "session '${existing.id}' vanished" }
            } else {
                store.newSession(project.palette, project.width, project.height, project.name).also {
                    store.update(it.id, project)
                }
            }
            projectSummary(session, project).toMutable().put("template_id", templateId).build()
        }

        // ---- project ----

        add("project_save",
            "Serializes a session to project JSON (version 2); long documents are summarized, not inlined.",
            "project",
            "session_id" to "string", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val text = ProjectCodec.save(project)
            val charCount = text.length
            val byteCount = text.toByteArray(Charsets.UTF_8).size
            jsonobj {
                put("session_id", session.id)
                put("format", "pixel-lab-project")
                put("version", ProjectCodec.VERSION)
                put("width", project.width)
                put("height", project.height)
                put("frames", project.frameCount)
                put("layers", project.layerCount)
                put("char_count", charCount)
                put("byte_count", byteCount)
                put("truncated", charCount > TEXT_RESULT_LIMIT)
                if (charCount <= TEXT_RESULT_LIMIT) {
                    put("json", text)
                } else {
                    put(
                        "note",
                        "project JSON has $charCount characters ($byteCount UTF-8 bytes), exceeding the " +
                            "$TEXT_RESULT_LIMIT character inline limit; only sizes are reported",
                    )
                }
            }
        }

        add("project_load", "Rebuilds a session from project JSON text (version 2).", "project",
            "json" to "string", "session_id" to "string", required = listOf("json")) { params, store ->
            val project = ProjectCodec.load(params.string("json"))
            val session = if (params.has("session_id")) {
                val existing = sessionOf(params, store)
                requireNotNull(store.update(existing.id, project)) { "session '${existing.id}' vanished" }
            } else {
                store.newSession(project.palette, project.width, project.height, project.name).also {
                    store.update(it.id, project)
                }
            }
            projectSummary(session, project).toMutable().put("loaded", true).build()
        }

        // ---- export ----

        add("export_aseprite_json",
            "Exports Aseprite spritesheet metadata JSON for a session's animation.",
            "export",
            "session_id" to "string", "layout" to "string", "columns" to "integer", "margin" to "integer",
            "scale" to "integer", required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val layout = layoutParam(params)
            val columns = params.opt("columns", 8)
            val margin = params.opt("margin", 0)
            val scale = params.opt("scale", 1)
            require(columns >= 1) { "'columns' must be >= 1 (was $columns)" }
            require(margin >= 0) { "'margin' must be >= 0 (was $margin)" }
            require(scale in 1..16) { "'scale' must be in [1, 16] (was $scale)" }
            val text = AsepriteMetadata.build(project, layout, columns, margin, scale)
            val charCount = text.length
            val byteCount = text.toByteArray(Charsets.UTF_8).size
            jsonobj {
                put("session_id", session.id)
                put("format", "aseprite-json")
                put("layout", layout.name.lowercase())
                put("frames", project.frameCount)
                put("tags", project.tags.size)
                put("char_count", charCount)
                put("byte_count", byteCount)
                put("truncated", charCount > TEXT_RESULT_LIMIT)
                if (charCount <= TEXT_RESULT_LIMIT) {
                    put("json", text)
                } else {
                    put(
                        "note",
                        "Aseprite JSON has $charCount characters ($byteCount UTF-8 bytes), exceeding the " +
                            "$TEXT_RESULT_LIMIT character inline limit",
                    )
                }
            }
        }

        return tools.toList() to schemas
    }

    /** Optional built-in/library palette id parameter; null when absent. */
    private fun optionalPaletteParam(params: JsonObject): Palette? {
        val raw = params.raw("palette_id") ?: return null
        if (raw is JsonNull) return null
        val id = (raw as? JsonString)?.value
            ?: throw IllegalArgumentException("parameter 'palette_id' must be a palette id string")
        return PaletteLibrary.byId(id)
            ?: BuiltInPalettes.byId(id)
            ?: throw IllegalArgumentException("unknown palette '$id' (see palette_library_list)")
    }
}
