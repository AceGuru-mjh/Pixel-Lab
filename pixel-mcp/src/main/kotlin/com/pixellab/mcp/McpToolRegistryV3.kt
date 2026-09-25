package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.core.atlas.AtlasBuilder
import com.pixellab.core.atlas.AtlasFormat
import com.pixellab.core.atlas.AtlasInput
import com.pixellab.core.atlas.AtlasMetadata
import com.pixellab.core.atlas.TextureOps
import com.pixellab.core.export.PngCodec
import com.pixellab.core.PixelResult
import com.pixellab.core.gen.ValueNoise
import com.pixellab.core.gen.SpriteGen
import com.pixellab.core.gen.TextureGen
import com.pixellab.core.history.CommandHistory
import com.pixellab.core.history.EditorCommand
import com.pixellab.core.io.ImageFormat
import com.pixellab.core.io.ImageImporter
import com.pixellab.core.io.ImportedFrames
import com.pixellab.core.io.IcoEncoder
import com.pixellab.core.io.BmpCodec
import com.pixellab.core.io.QoiCodec
import com.pixellab.core.model.Frame
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.core.palette.PaletteLibrary
import com.pixellab.core.pipeline.PipelineRunner
import com.pixellab.core.pipeline.Recipe
import com.pixellab.core.pipeline.RecipeParser
import com.pixellab.core.project.ProjectCodec
import com.pixellab.core.tilemap.Autotiler
import com.pixellab.core.tilemap.BlobMode
import com.pixellab.core.tilemap.DemoTilesets
import com.pixellab.core.tilemap.Isometric
import com.pixellab.core.tilemap.TileLayer
import com.pixellab.core.tilemap.TileMap
import com.pixellab.core.tools.Anchor
import com.pixellab.core.tools.Connectivity
import com.pixellab.core.tools.OutlineMode
import com.pixellab.core.tools.OutlineShading
import com.pixellab.mcp.json.JsonArray
import com.pixellab.mcp.json.JsonBoolean
import com.pixellab.mcp.json.JsonElement
import com.pixellab.mcp.json.JsonNull
import com.pixellab.mcp.json.JsonNumber
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonarray
import com.pixellab.mcp.json.jsonobj
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Third-tier MCP tool registry for Pixel Lab: 29 additional tools covering
 * the PR7 import/export codecs (`io_*`), the PR8 generators and cel effects
 * (`gen_*`, `outline`, `shade`, `ramp`, `flip_rotate`, `canvas_resize`),
 * the PR9 tilemap and atlas machinery (`tilemap_*`, `io_export_atlas`,
 * `texture_*`), guarded [CommandHistory] editing (`history_*`) and the PR8
 * pipeline recipes (`pipeline_*`).
 *
 * ## Hosting (identical strategy to [McpToolRegistryV2])
 *
 * [PixelMcpServer] wires only the v1 [McpToolRegistry]; v2 solved the "no
 * existing-file edits" constraint by being a *standalone dispatcher* that
 * hosts embed next to the server. This registry copies that solution
 * exactly: construct `McpToolRegistryV3()` (self-hosting, default lab),
 * then dispatch `tools/call` by trying `v3.execute(name, args, store)`
 * before (or after) the v1/v2 registries — every tier throws
 * [McpToolException] with `JsonRpc.METHOD_NOT_FOUND` for unknown names, so
 * a simple try-chain routes any tool. The shared [PixelSessionStore]
 * instance is what ties the tiers together.
 *
 * ## Session-scoped side registries
 *
 * Beyond the project store, v3 keeps two registries keyed by session id:
 *
 *  * **Tile maps** — `ConcurrentHashMap<sessionId, MutableMap<mapId,
 *    TileMap>>`. [TileMap] values (tileset + layers) are heavier than a
 *    project and never flow through [PixelSessionStore]; they live here
 *    and die with the registry instance. `tilemap_create` mints
 *    `map-<n>` ids, `tilemap_autotile`/`tilemap_render` look them up.
 *  * **History** — `ConcurrentHashMap<sessionId, CommandHistory>`. Every
 *    mutating v3 tool records its before/after project pair, so
 *    `history_undo` / `history_redo` revert exactly the v3 edits. The
 *    guard semantics of [CommandHistory] (undo requires the current
 *    project to equal the recorded `after`) mean v1/v2 mutations made in
 *    between simply make undo fail cleanly instead of corrupting state.
 *
 * ## Conventions inherited from v1/v2
 *
 * Handlers validate parameters eagerly (`IllegalArgumentException` →
 * [McpToolException] → JSON-RPC `-32602`), mutating handlers write the
 * project back through [PixelSessionStore.update], and execution is
 * serialized by one mutex. **Binary convention (new in v3):** inputs take
 * base64 bytes under `data_b64` (v1/v2 never accepted binary input; their
 * exports only reported `byte_count`). V3 export tools keep that envelope
 * (`format`, `byte_count`, `width`, `height`, `session_id`) *and* inline
 * the bytes as `data_b64`, so agents can save the artifacts directly.
 *
 * @param lab shared engine facade; reserved for parity with v1/v2 wiring.
 *   All v3 machinery (TextureGen, SpriteGen, OutlineShading, TileMap,
 *   AtlasBuilder, PipelineRunner, codecs) is object-level and never touches
 *   the engine, but hosts that already own a [PixelLab] can pass it in.
 */
class McpToolRegistryV3(private val lab: PixelLab = PixelLab.create()) {

    /** Maximum characters of serialized text (metadata JSON) inlined. */
    private companion object {
        private const val TEXT_RESULT_LIMIT: Int = 4000

        /** Upper bound of one binary payload inlined as base64 (2 MB). */
        private const val MAX_INLINE_BYTES: Int = 2 * 1024 * 1024

        /** Tile size of the demo grass tilesets (only supported size). */
        private const val DEMO_TILE_SIZE: Int = 8

        /** Tile size of the demo stone Wang tileset. */
        private const val WANG_TILE_SIZE: Int = 4
    }

    /** Serializes tool runs, mirroring v1/v2 (history state is not thread-safe). */
    private val mutex = Mutex()

    /** V3 tile maps, keyed by session id then map id. */
    private val maps = ConcurrentHashMap<String, MutableMap<String, TileMap>>()

    /** V3 command histories, keyed by session id. */
    private val histories = ConcurrentHashMap<String, CommandHistory>()

    /** Monotonic map id source (`map-1`, `map-2`, …). */
    private val mapCounter = AtomicInteger(0)

    private val byName: Map<String, McpTool>
    private val schemas: Map<String, JsonObject>

    init {
        val built = buildTools()
        byName = built.first.associateBy { it.name }
        schemas = built.second
    }

    /** All v3 tools in registration order. */
    fun tools(): List<McpTool> = byName.values.toList()

    /** The v3 tool registered as [name], or null. */
    fun tool(name: String): McpTool? = byName[name]

    /** Simplified JSON-schema description of a tool's parameters. */
    fun inputSchema(name: String): JsonObject = schemas[name] ?: jsonobj {
        put("type", "object")
        put("properties", jsonobj { })
    }

    /** Number of live v3 tile maps for [sessionId] (diagnostics). */
    fun mapCount(sessionId: String): Int = maps[sessionId]?.size ?: 0

    /**
     * Runs the v3 tool [name] with [params] against [store]. Unknown tools
     * and parameter errors throw [McpToolException]; other exceptions
     * signal tool execution failures (surfaced as MCP `isError`).
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
    // Shared state helpers
    // ------------------------------------------------------------------

    /** Session referenced by the `session_id` parameter (auto-created when unknown). */
    private fun sessionOf(params: JsonObject, store: PixelSessionStore): PixelSessionStore.SessionState {
        val id = params.string("session_id")
        return requireNotNull(store.get(id, create = true)) { "session '$id' unavailable" }
    }

    /** The (lazily created) v3 history of [sessionId]. */
    private fun historyFor(sessionId: String): CommandHistory =
        histories.getOrPut(sessionId) { CommandHistory() }

    /** Records a v3 edit into the session history and the session store. */
    private fun commit(
        store: PixelSessionStore,
        session: PixelSessionStore.SessionState,
        label: String,
        before: SpriteProject,
        after: SpriteProject,
    ): SpriteProject {
        historyFor(session.id).record(V3Command(label), before, after)
        store.update(session.id, after)
        return after
    }

    /**
     * Runs [op] on the session's project, records the transition under
     * [label] in the v3 history and returns the summary envelope.
     */
    private fun mutate(
        params: JsonObject,
        store: PixelSessionStore,
        label: String,
        op: (SpriteProject) -> SpriteProject,
    ): JsonObject {
        val session = sessionOf(params, store)
        val before = session.project
        val after = commit(store, session, label, before, op(before))
        return projectSummary(session, after)
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
            put("undo_depth", historyFor(session.id).undoDepth())
        }

    /** The active cel; missing cels are a parameter-level error in v3. */
    private fun requireActiveCel(project: SpriteProject): PixelFrame {
        return project.activeCel()
            ?: throw IllegalArgumentException(
                "session project has no active cel (frame ${project.activeFrameIndex}, layer ${project.activeLayerId})",
            )
    }

    /** The map registered as [mapId] under [sessionId]'s side registry. */
    private fun requireMap(sessionId: String, mapId: String): TileMap {
        return maps[sessionId]?.get(mapId)
            ?: throw IllegalArgumentException("unknown map '$mapId' in session '$sessionId' (see tilemap_create)")
    }

    // ------------------------------------------------------------------
    // Parameter helpers (v1/v2 style, defensive)
    // ------------------------------------------------------------------

    /** Base64 payload parameter (`data_b64`); returns the decoded bytes. */
    private fun bytesParam(params: JsonObject, key: String = "data_b64"): ByteArray {
        val text = params.string(key).trim()
        require(text.isNotEmpty()) { "parameter '$key' must be a non-empty base64 string" }
        val bytes = try {
            Base64.getDecoder().decode(text)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("parameter '$key' is not valid base64: ${error.message}")
        }
        require(bytes.size <= MAX_INLINE_BYTES) {
            "parameter '$key' decodes to ${bytes.size} bytes, above the $MAX_INLINE_BYTES byte cap"
        }
        return bytes
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

    /** Hex color list rendering of ARGB ints. */
    private fun hexList(colors: List<Int>): JsonArray = jsonarray {
        for (color in colors) add(JsonString(hexArgb(color)))
    }

    /** Hex colors array parameter (at least [min] entries when present). */
    private fun hexColorsParam(params: JsonObject, key: String, min: Int): IntArray? {
        val raw = params.raw(key) ?: return null
        if (raw is JsonNull) return null
        val array = raw as? JsonArray
            ?: throw IllegalArgumentException("parameter '$key' must be an array of '#RRGGBB' colors")
        val out = IntArray(array.size)
        for (i in array.items.indices) {
            val entry = array.items[i] as? JsonString
                ?: throw IllegalArgumentException("'$key[$i]' must be a '#RRGGBB' color string")
            out[i] = parseHexColor(entry.value)
        }
        require(out.size >= min) { "'$key' needs at least $min colors (was ${out.size})" }
        return out
    }

    /** Int list parameter (e.g. ICO sizes, iso height offsets). */
    private fun intArrayParam(params: JsonObject, key: String): IntArray {
        val array = params.array(key)
        val out = IntArray(array.size)
        for (i in array.items.indices) {
            val entry = array.items[i] as? JsonNumber
                ?: throw IllegalArgumentException("'$key[$i]' must be an integer")
            val v = entry.value
            require(v == Math.floor(v) && v >= Int.MIN_VALUE.toDouble() && v <= Int.MAX_VALUE.toDouble()) {
                "'$key[$i]' must be a 32-bit integer"
            }
            out[i] = v.toInt()
        }
        return out
    }

    /** Palette resolved from `palette_id` or `colors`, falling back to PICO-8. */
    private fun paletteParam(params: JsonObject): Palette {
        hexColorsParam(params, "colors", min = 1)?.let { colors ->
            return Palette("custom", "Custom", colors, PaletteSource.CUSTOM)
        }
        val raw = params.raw("palette_id")
        if (raw != null && raw !is JsonNull) {
            val id = (raw as? JsonString)?.value
                ?: throw IllegalArgumentException("parameter 'palette_id' must be a palette id string")
            return PaletteLibrary.byId(id)
                ?: throw IllegalArgumentException("unknown palette '$id' (see palette_library_list)")
        }
        return BuiltInPalettes.PICO8
    }

    /** Build envelope shared by every binary-returning v3 tool. */
    private fun binaryEnvelope(
        session: PixelSessionStore.SessionState?,
        format: String,
        bytes: ByteArray,
        width: Int,
        height: Int,
    ): JsonObject = jsonobj {
        session?.let { put("session_id", it.id) }
        put("format", format)
        put("byte_count", bytes.size)
        put("width", width)
        put("height", height)
        put("data_b64", Base64.getEncoder().encodeToString(bytes))
    }

    /** Thumbnail-style nearest downscale so the longest edge fits [maxSize]. */
    private fun fitWithin(frame: PixelFrame, maxSize: Int): PixelFrame {
        val longest = maxOf(frame.width, frame.height)
        if (longest <= maxSize) return frame
        val scale = longest.toDouble() / maxSize
        val w = maxOf(1, (frame.width / scale).toInt())
        val h = maxOf(1, (frame.height / scale).toInt())
        return frame.transformed(w, h) { x, y ->
            val sx = minOf(frame.width - 1, (x * frame.width / w))
            val sy = minOf(frame.height - 1, (y * frame.height / h))
            frame[sx, sy]
        }
    }

    /** Distinct opaque colors (first-appearance order, capped at [cap]). */
    private fun distinctColors(frames: List<PixelFrame>, cap: Int): IntArray? {
        val seen = LinkedHashSet<Int>()
        for (frame in frames) {
            for (argb in frame.pixels) {
                if (argb ushr 24 == 0) continue
                seen.add(argb)
                if (seen.size > cap) return null
            }
        }
        return if (seen.isEmpty()) null else seen.toIntArray()
    }

    // ------------------------------------------------------------------
    // Tool construction
    // ------------------------------------------------------------------

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

        // ---- v3-io: PR7 codecs -------------------------------------------------

        add("io_import_frames", "Sniffs base64 image bytes (PNG/APNG/GIF/QOI/BMP/ASE) and reports format, dims, frame count, delays and palette hint.", "v3-io",
            "data_b64" to "string", "name" to "string",
            required = listOf("data_b64")) { params, _ ->
            val bytes = bytesParam(params)
            val name = params.opt("name", "")
            val imported = ImageImporter.importFrames(bytes)
            jsonobj {
                put("name", name)
                put("format", imported.format.name.lowercase())
                put("format_label", imported.format.humanName())
                put("width", imported.width)
                put("height", imported.height)
                put("frame_count", imported.frames.size)
                if (imported.delaysMs != null) {
                    put(
                        "delays_ms",
                        jsonarray {
                            for (delay in imported.delaysMs.orEmpty()) add(JsonNumber.of(delay.toLong()))
                        },
                    )
                } else {
                    put("delays_ms", JsonNull)
                }
                put(
                    "palette_hint",
                    imported.paletteHint?.let { hint -> hexList(hint.colors.toList()) } ?: JsonNull,
                )
                put("byte_count", bytes.size)
            }
        }

        add("io_import_project", "Rebuilds a session project from base64 project JSON (version 2 wire format).", "v3-io",
            "session_id" to "string", "data_b64" to "string", "name" to "string",
            required = listOf("session_id", "data_b64")) { params, store ->
            val session = sessionOf(params, store)
            val text = String(bytesParam(params), Charsets.UTF_8)
            val decoded = ProjectCodec.load(text)
            val project = decoded.copy(name = params.opt("name", decoded.name))
            commit(store, session, "io_import_project", session.project, project)
            projectSummary(session, project)
        }

        add("io_export_qoi", "Encodes one composited frame as QOI 1.0 bytes (data_b64 + byte_count).", "v3-io",
            "session_id" to "string", "frame_index" to "integer", "scale" to "integer",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val frameIndex = params.opt("frame_index", project.activeFrameIndex)
            val scale = params.opt("scale", 1)
            require(frameIndex in project.frames.indices) { "frame_index $frameIndex out of bounds (${project.frameCount} frames)" }
            require(scale in 1..64) { "'scale' must be in [1, 64] (was $scale)" }
            val frame = project.compositeFrame(frameIndex)
            val target = if (scale == 1) frame else frame.scaledNearest(scale)
            val bytes = QoiCodec.encode(target)
            jsonobj {
                put("session_id", session.id)
                put("format", "qoi")
                put("byte_count", bytes.size)
                put("frame_index", frameIndex)
                put("width", target.width)
                put("height", target.height)
                put("data_b64", Base64.getEncoder().encodeToString(bytes))
            }
        }

        add("io_export_bmp", "Encodes one composited frame as a 32-bit Windows BMP (data_b64 + byte_count).", "v3-io",
            "session_id" to "string", "frame_index" to "integer", "scale" to "integer",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val frameIndex = params.opt("frame_index", project.activeFrameIndex)
            val scale = params.opt("scale", 1)
            require(frameIndex in project.frames.indices) { "frame_index $frameIndex out of bounds (${project.frameCount} frames)" }
            require(scale in 1..64) { "'scale' must be in [1, 64] (was $scale)" }
            val frame = project.compositeFrame(frameIndex)
            val target = if (scale == 1) frame else frame.scaledNearest(scale)
            val bytes = BmpCodec.encode(target)
            jsonobj {
                put("session_id", session.id)
                put("format", "bmp")
                put("byte_count", bytes.size)
                put("frame_index", frameIndex)
                put("width", target.width)
                put("height", target.height)
                put("data_b64", Base64.getEncoder().encodeToString(bytes))
            }
        }

        add("io_export_ico", "Encodes one composited frame as a multi-resolution Windows ICO (sizes default [16, 32, 48]).", "v3-io",
            "session_id" to "string", "frame_index" to "integer", "sizes" to "array",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val frameIndex = params.opt("frame_index", project.activeFrameIndex)
            require(frameIndex in project.frames.indices) { "frame_index $frameIndex out of bounds (${project.frameCount} frames)" }
            val sizes = if (params.has("sizes") && params.raw("sizes") !is JsonNull) {
                val declared = intArrayParam(params, "sizes")
                require(declared.isNotEmpty()) { "'sizes' must not be empty" }
                for (size in declared) require(size in 1..256) { "ico size $size out of [1, 256]" }
                require(declared.toSet().size == declared.size) { "'sizes' must not repeat entries" }
                declared
            } else {
                intArrayOf(16, 32, 48)
            }
            val frame = project.compositeFrame(frameIndex)
            val bytes = IcoEncoder.encode(sizes.toList(), frame)
            jsonobj {
                put("session_id", session.id)
                put("format", "ico")
                put("byte_count", bytes.size)
                put("frame_index", frameIndex)
                put("width", frame.width)
                put("height", frame.height)
                put("sizes_count", sizes.size)
                put("data_b64", Base64.getEncoder().encodeToString(bytes))
            }
        }

        add("project_thumbnail", "Renders a small PNG (longest edge <= max_size, default 64) of one composited frame.", "v3-io",
            "session_id" to "string", "frame_index" to "integer", "max_size" to "integer",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val frameIndex = params.opt("frame_index", project.activeFrameIndex)
            val maxSize = params.opt("max_size", 64)
            require(frameIndex in project.frames.indices) { "frame_index $frameIndex out of bounds (${project.frameCount} frames)" }
            require(maxSize in 8..1024) { "'max_size' must be in [8, 1024] (was $maxSize)" }
            val thumb = fitWithin(project.compositeFrame(frameIndex), maxSize)
            val bytes = PngCodec.encode(thumb)
            binaryEnvelope(session, "png", bytes, thumb.width, thumb.height)
        }

        // ---- v3-gen: generators and cel effects ---------------------------------

        add("gen_texture", "Generates a procedural texture (clouds/wood/stone/marble/bricks/checker) into a new single-frame session project.", "v3-gen",
            "type" to "string", "width" to "integer", "height" to "integer",
            "palette_id" to "string", "colors" to "array", "seed" to "integer", "cell" to "integer",
            "rings" to "integer", "veins" to "integer", "brick_w" to "integer", "brick_h" to "integer", "mortar" to "integer",
            required = listOf("type", "width", "height")) { params, store ->
            val type = params.opt("type", "").trim().lowercase()
            val width = params.int("width")
            val height = params.int("height")
            require(width in 1..4096) { "'width' must be in [1, 4096] (was $width)" }
            require(height in 1..4096) { "'height' must be in [1, 4096] (was $height)" }
            val seed = params.opt("seed", 0L)
            val palette = paletteParam(params)
            val colors = palette.colors
            val frame = when (type) {
                "clouds" -> TextureGen.clouds(width, height, colors, seed)
                "wood" -> TextureGen.wood(width, height, colors, seed, params.opt("rings", 6))
                "stone" -> TextureGen.stone(width, height, colors, seed)
                "marble" -> TextureGen.marble(width, height, colors, seed, params.opt("veins", 5))
                "bricks" -> TextureGen.bricks(
                    width, height, colors, seed,
                    params.opt("brick_w", 8), params.opt("brick_h", 4), params.opt("mortar", 1),
                )

                "checker" -> {
                    val cell = params.opt("cell", 8)
                    val a = colors[0]
                    val b = colors.getOrElse(1) { colors[0] }
                    TextureGen.checker(width, height, cell, a, b)
                }

                else -> throw IllegalArgumentException(
                    "'type' must be one of clouds, wood, stone, marble, bricks, checker (was '$type')",
                )
            }
            val session = sessionOf(params, store)
            val project = SpriteFactory.create("texture-$type", width, height, palette)
                .withActiveCel(frame)
            commit(store, session, "gen_texture ($type)", session.project, project)
            jsonobj {
                put("session_id", session.id)
                put("project_id", project.id)
                put("name", project.name)
                put("type", type)
                put("width", width)
                put("height", height)
                put("frames", 1)
                put("layers", 1)
                put("palette_id", palette.id)
                put("seed", seed)
                put("undo_depth", historyFor(session.id).undoDepth())
            }
        }

        add("gen_sprite", "Generates a procedural sprite (tree/rock/gem/ship) into a new single-frame session project.", "v3-gen",
            "type" to "string", "size" to "integer", "width" to "integer", "height" to "integer",
            "colors" to "array", "seed" to "integer",
            required = listOf("type")) { params, store ->
            val type = params.opt("type", "").trim().lowercase()
            val explicitWidth = params.raw("width")
            val explicitHeight = params.raw("height")
            val size = params.opt("size", 16)
            require(size in 6..256) { "'size' must be in [6, 256] (was $size)" }
            val width = if (explicitWidth is JsonNumber) params.int("width") else size
            val height = if (explicitHeight is JsonNumber) params.int("height") else size
            val seed = params.opt("seed", 0L)
            val colors = hexColorsParam(params, "colors", min = 1)
            val frame = when (type) {
                "tree" -> SpriteGen.tree(
                    SpriteGen.TreeSpec(
                        width, height,
                        trunkColor = colors?.getOrNull(0) ?: 0xFF6B4226.toInt(),
                        leafRamp = intArrayOf(
                            colors?.getOrNull(1) ?: 0xFF1E7A2E.toInt(),
                            colors?.getOrNull(2) ?: 0xFF38B04A.toInt(),
                            colors?.getOrNull(3) ?: 0xFF7BD36B.toInt(),
                        ),
                        seed = seed,
                    ),
                )

                "rock" -> SpriteGen.rock(
                    SpriteGen.RockSpec(
                        width, height,
                        baseColor = colors?.getOrNull(0) ?: 0xFF8A8A93.toInt(),
                        highlightColor = colors?.getOrNull(1) ?: 0xFFC9C9D4.toInt(),
                        shadowColor = colors?.getOrNull(2) ?: 0xFF4F4F58.toInt(),
                        seed = seed,
                    ),
                )

                "gem" -> SpriteGen.gem(
                    SpriteGen.GemSpec(
                        width, height,
                        gemColor = colors?.getOrNull(0) ?: 0xFF2CE8F5.toInt(),
                        sparkleColor = colors?.getOrNull(1) ?: 0xFFFFFFFF.toInt(),
                        seed = seed,
                    ),
                )

                "ship" -> SpriteGen.ship(
                    SpriteGen.ShipSpec(
                        width, height,
                        hullColor = colors?.getOrNull(0) ?: 0xFFB13E53.toInt(),
                        cockpitColor = colors?.getOrNull(1) ?: 0xFF41A6F6.toInt(),
                        engineColor = colors?.getOrNull(2) ?: 0xFFFFCD75.toInt(),
                        seed = seed,
                    ),
                )

                else -> throw IllegalArgumentException(
                    "'type' must be one of tree, rock, gem, ship (was '$type')",
                )
            }
            val session = sessionOf(params, store)
            val palette = Palette(
                "gen-$type", "Generated $type",
                distinctColors(listOf(frame), 256) ?: BuiltInPalettes.PICO8.colors,
                PaletteSource.EXTRACTED,
            )
            val project = SpriteFactory.create("sprite-$type", frame.width, frame.height, palette)
                .withActiveCel(frame)
            commit(store, session, "gen_sprite ($type)", session.project, project)
            jsonobj {
                put("session_id", session.id)
                put("project_id", project.id)
                put("name", project.name)
                put("type", type)
                put("width", frame.width)
                put("height", frame.height)
                put("frames", 1)
                put("layers", 1)
                put("palette_id", palette.id)
                put("seed", seed)
                put("undo_depth", historyFor(session.id).undoDepth())
            }
        }

        add("gen_noise_field", "Generates a tileable fBm value-noise grayscale field into a new single-frame session project.", "v3-gen",
            "width" to "integer", "height" to "integer", "seed" to "integer",
            "tile" to "integer", "octaves" to "integer", "session_id" to "string",
            required = listOf("width", "height")) { params, store ->
            val width = params.int("width")
            val height = params.int("height")
            require(width in 1..4096) { "'width' must be in [1, 4096] (was $width)" }
            require(height in 1..4096) { "'height' must be in [1, 4096] (was $height)" }
            val seed = params.opt("seed", 0L)
            val tile = params.opt("tile", 0)
            require(tile >= 0) { "'tile' must be >= 0 (was $tile)" }
            val octaves = params.opt("octaves", 4)
            val noise = ValueNoise(seed, tile)
            val pixels = IntArray(width * height)
            var i = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val v = noise.fbm(x.toFloat(), y.toFloat(), octaves).coerceIn(0f, 1f)
                    val gray = (v * 255f + 0.5f).toInt()
                    pixels[i++] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
            val frame = PixelFrame.of(width, height, pixels)
            val session = sessionOf(params, store)
            val palette = Palette(
                "noise-gray", "Noise grayscale",
                distinctColors(listOf(frame), 256) ?: intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
                PaletteSource.EXTRACTED,
            )
            val project = SpriteFactory.create("noise-field", width, height, palette)
                .withActiveCel(frame)
            commit(store, session, "gen_noise_field", session.project, project)
            jsonobj {
                put("session_id", session.id)
                put("project_id", project.id)
                put("name", project.name)
                put("width", width)
                put("height", height)
                put("frames", 1)
                put("layers", 1)
                put("seed", seed)
                put("tile", tile)
                put("octaves", octaves)
                put("undo_depth", historyFor(session.id).undoDepth())
            }
        }

        add("gen_silhouette", "Recolors every opaque pixel of the active cel to one flat color (alpha preserved).", "v3-gen",
            "session_id" to "string", "color" to "string",
            required = listOf("session_id", "color")) { params, store ->
            val color = colorParam(params, "color")
            mutate(params, store, "gen_silhouette") { project ->
                project.withActiveCel(SpriteGen.silhouette(requireActiveCel(project), color))
            }
        }

        add("outline", "Applies an outer/inner 4- or 8-connected outline in one color to the active cel.", "v3-gen",
            "session_id" to "string", "color" to "string", "mode" to "string", "connectivity" to "string",
            required = listOf("session_id", "color")) { params, store ->
            val color = colorParam(params, "color")
            val mode = when (params.opt("mode", "outer").trim().lowercase()) {
                "outer" -> OutlineMode.OUTER
                "inner" -> OutlineMode.INNER
                else -> throw IllegalArgumentException("'mode' must be outer or inner")
            }
            val connectivity = when (params.opt("connectivity", "8").trim().lowercase()) {
                "4", "four" -> Connectivity.FOUR
                "8", "eight" -> Connectivity.EIGHT
                else -> throw IllegalArgumentException("'connectivity' must be 4 or 8")
            }
            mutate(params, store, "outline ($mode)") { project ->
                project.withActiveCel(
                    OutlineShading.outline(requireActiveCel(project), color, mode, connectivity),
                )
            }
        }

        add("shade", "Applies directional light shading (light angle degrees, strength 0..255) to the active cel.", "v3-gen",
            "session_id" to "string", "light_angle" to "number", "strength" to "integer",
            required = listOf("session_id")) { params, store ->
            val angle = params.opt("light_angle", 315.0).toFloat()
            require(angle.isFinite()) { "'light_angle' must be finite (was $angle)" }
            val strength = params.opt("strength", 128)
            require(strength in 0..255) { "'strength' must be in [0, 255] (was $strength)" }
            mutate(params, store, "shade") { project ->
                project.withActiveCel(
                    OutlineShading.shade(requireActiveCel(project), angle, strength = strength),
                )
            }
        }

        add("ramp", "Builds a Lab-lightness color ramp from one base color; pure, no session required.", "v3-gen",
            "base_color" to "string", "steps" to "integer", "lighten" to "integer", "darken" to "integer",
            required = listOf("base_color", "steps")) { params, _ ->
            val base = colorParam(params, "base_color")
            val steps = params.int("steps")
            require(steps in 2..64) { "'steps' must be in [2, 64] (was $steps)" }
            val lighten = params.opt("lighten", 0)
            val darken = params.opt("darken", 0)
            require(lighten >= 0) { "'lighten' must be >= 0 (was $lighten)" }
            require(darken >= 0) { "'darken' must be >= 0 (was $darken)" }
            val colors = OutlineShading.ramp(base, steps, lighten, darken)
            jsonobj {
                put("base_color", hexArgb(base))
                put("steps", steps)
                put("colors", hexList(colors))
            }
        }

        add("flip_rotate", "Flips or rotates the active cel (rotations need a square canvas).", "v3-gen",
            "session_id" to "string", "op" to "string",
            required = listOf("session_id", "op")) { params, store ->
            val op = params.opt("op", "").trim().lowercase()
            val rotates = op == "rot90cw" || op == "rot90ccw"
            mutate(params, store, "flip_rotate ($op)") { project ->
                val cel = requireActiveCel(project)
                val next = when (op) {
                    "fliph" -> cel.mirroredHorizontally()
                    "flipv" -> cel.mirroredVertically()
                    "rot90cw" -> cel.rotated90Cw()
                    "rot90ccw" -> cel.rotated90Ccw()
                    "rot180" -> cel.rotated180()
                    else -> throw IllegalArgumentException(
                        "'op' must be fliph, flipv, rot90cw, rot90ccw or rot180 (was '$op')",
                    )
                }
                if (rotates && project.width != project.height) {
                    throw IllegalArgumentException(
                        "rotation '$op' needs a square canvas (${project.width}x${project.height}); " +
                            "resize to square first (canvas_resize)",
                    )
                }
                project.withActiveCel(next)
            }
        }

        add("canvas_resize", "Resizes the project canvas (all cels re-anchored top-left/center/bottom-right).", "v3-gen",
            "session_id" to "string", "width" to "integer", "height" to "integer", "anchor" to "string",
            required = listOf("session_id", "width", "height")) { params, store ->
            val width = params.int("width")
            val height = params.int("height")
            require(width in 1..4096) { "'width' must be in [1, 4096] (was $width)" }
            require(height in 1..4096) { "'height' must be in [1, 4096] (was $height)" }
            val anchor = when (params.opt("anchor", "center").trim().lowercase()) {
                "top_left", "topleft", "corner" -> Anchor.TOP_LEFT
                "center" -> Anchor.CENTER
                "bottom_right", "bottomright" -> Anchor.BOTTOM_RIGHT
                else -> throw IllegalArgumentException("'anchor' must be top_left, center or bottom_right")
            }
            mutate(params, store, "canvas_resize") { project ->
                val newFrames = project.frames.map { frame ->
                    val cels = frame.cels.mapValues { (_, cel) ->
                        TextureOps.resizeCanvas(cel, width, height, anchor)
                    }
                    Frame(frame.id, cels, frame.durationMs)
                }
                project.copy(width = width, height = height, frames = newFrames)
            }
        }

        // ---- v3-map: tilemap ----------------------------------------------------

        add("tilemap_create", "Builds a tile map from terrain rows (strings of '0'/'1') over the demo grass blob-47 tileset.", "v3-map",
            "session_id" to "string", "width" to "integer", "height" to "integer",
            "tile_size" to "integer", "rows" to "array", "map_id" to "string",
            required = listOf("session_id", "rows")) { params, store ->
            val session = sessionOf(params, store)
            val rowsRaw = params.raw("rows")
                ?: throw IllegalArgumentException("missing parameter 'rows'")
            val rowsArray = rowsRaw as? JsonArray
                ?: throw IllegalArgumentException("parameter 'rows' must be an array of '0101' strings")
            val rows = rowsArray.items.map { item ->
                (item as? JsonString)?.value
                    ?: throw IllegalArgumentException("'rows' entries must be strings of '0'/'1' characters")
            }
            require(rows.isNotEmpty()) { "'rows' must not be empty" }
            val width = if (params.has("width") && params.raw("width") is JsonNumber) {
                params.int("width")
            } else {
                rows[0].length
            }
            val height = if (params.has("height") && params.raw("height") is JsonNumber) {
                params.int("height")
            } else {
                rows.size
            }
            require(width in 1..1024) { "'width' must be in [1, 1024] (was $width)" }
            require(height in 1..1024) { "'height' must be in [1, 1024] (was $height)" }
            require(rows.size == height) { "'rows' has ${rows.size} entries, expected height $height" }
            val tile_size = params.opt("tile_size", DEMO_TILE_SIZE)
            require(tile_size == DEMO_TILE_SIZE) {
                "'tile_size' must be $DEMO_TILE_SIZE (the demo grass tileset); got $tile_size"
            }
            val cells = IntArray(width * height)
            var solid = 0
            for (y in 0 until height) {
                val row = rows[y]
                require(row.length == width) { "rows[$y] length ${row.length} does not match width $width" }
                for (x in 0 until width) {
                    val c = row[x]
                    require(c == '0' || c == '1') { "rows[$y][$x] must be '0' or '1' (was '$c')" }
                    if (c == '1') {
                        cells[y * width + x] = 46 // blob-47 interior tile, terrain tag 1
                        solid++
                    } else {
                        cells[y * width + x] = -1
                    }
                }
            }
            val layer = TileLayer.of("terrain", width, height, cells)
            val mapId = params.opt("map_id", "").ifBlank { "map-${mapCounter.incrementAndGet()}" }
            val map = TileMap.of(mapId, DemoTilesets.grassBlob47(), width, height, listOf(layer))
            maps.getOrPut(session.id) { ConcurrentHashMap() }[mapId] = map
            jsonobj {
                put("session_id", session.id)
                put("map_id", mapId)
                put("width", width)
                put("height", height)
                put("tile_size", tile_size)
                put("tileset", "grass-blob47")
                put("terrain_tag", 1)
                put("solid_cells", solid)
                put("layer", layer.name)
            }
        }

        add("tilemap_autotile", "Autotiles the map's terrain layer (blob47 / simple16 / wang) and stores the new map version.", "v3-map",
            "session_id" to "string", "map_id" to "string", "terrain_tag" to "integer", "mode" to "string",
            required = listOf("session_id", "map_id")) { params, store ->
            val session = sessionOf(params, store)
            val mapId = params.string("map_id")
            val map = requireMap(session.id, mapId)
            val modeRaw = params.opt("mode", "blob47").trim().lowercase()
            val layer = map.layers.firstOrNull()
                ?: throw IllegalArgumentException("map '$mapId' has no layers to autotile")
            val (tileset, mode, defaultTag) = when (modeRaw) {
                "blob47", "blob", "full47" -> Triple(DemoTilesets.grassBlob47(), "blob47", 1)
                "simple16", "simple" -> Triple(DemoTilesets.grassSimple16(), "simple16", 1)
                "wang", "wang16", "edges" -> Triple(DemoTilesets.stoneWang16(), "wang", 2)
                else -> throw IllegalArgumentException(
                    "'mode' must be blob47, simple16 or wang (was '$modeRaw')",
                )
            }
            val terrainTag = if (params.has("terrain_tag") && params.raw("terrain_tag") is JsonNumber) {
                params.int("terrain_tag")
            } else {
                defaultTag
            }
            require(terrainTag >= 0) { "'terrain_tag' must be >= 0 (was $terrainTag)" }
            val autotiler = Autotiler(tileset)
            val autotiled = when (mode) {
                "blob47" -> autotiler.autotile(layer, terrainTag, BlobMode.FULL_47)
                "simple16" -> autotiler.autotile(layer, terrainTag, BlobMode.SIMPLE_16)
                else -> autotiler.wangEdges(layer, terrainTag)
            }
            val next = TileMap.of(map.name, tileset, map.width, map.height, listOf(autotiled))
            maps.getOrPut(session.id) { ConcurrentHashMap() }[mapId] = next
            val solid = autotiled.cells().count { it.second.tileIndex >= 0 }
            jsonobj {
                put("session_id", session.id)
                put("map_id", mapId)
                put("mode", mode)
                put("tileset", if (mode == "wang") "stone-wang16" else "grass")
                put("tile_size", if (mode == "wang") WANG_TILE_SIZE else DEMO_TILE_SIZE)
                put("terrain_tag", terrainTag)
                put("layer", autotiled.name)
                put("solid_cells", solid)
            }
        }

        add("tilemap_render", "Renders the map to a PNG (data_b64) at an integer scale factor.", "v3-map",
            "session_id" to "string", "map_id" to "string", "scale" to "integer",
            required = listOf("session_id", "map_id")) { params, store ->
            val session = sessionOf(params, store)
            val map = requireMap(session.id, params.string("map_id"))
            val scale = params.opt("scale", 1)
            require(scale in 1..16) { "'scale' must be in [1, 16] (was $scale)" }
            val rendered = map.render()
            val target = if (scale == 1) rendered else rendered.scaledNearest(scale)
            val bytes = PngCodec.encode(target)
            binaryEnvelope(session, "png", bytes, target.width, target.height)
        }

        add("tilemap_iso_render", "Renders the map as an isometric diamond projection (PNG data_b64, optional per-row height offsets).", "v3-map",
            "session_id" to "string", "map_id" to "string", "tile_w" to "integer",
            "tile_h" to "integer", "height_offsets" to "array",
            required = listOf("session_id", "map_id")) { params, store ->
            val session = sessionOf(params, store)
            val map = requireMap(session.id, params.string("map_id"))
            val tileW = params.opt("tile_w", 32)
            val tileH = params.opt("tile_h", 16)
            require(tileW in 2..256) { "'tile_w' must be in [2, 256] (was $tileW)" }
            require(tileH in 1..256) { "'tile_h' must be in [1, 256] (was $tileH)" }
            val offsets = if (params.has("height_offsets") && params.raw("height_offsets") !is JsonNull) {
                intArrayParam(params, "height_offsets").also {
                    require(it.size == map.height) {
                        "'height_offsets' needs ${map.height} entries (one per row), got ${it.size}"
                    }
                }
            } else {
                null
            }
            val rendered = Isometric.renderIso(map, tileW, tileH, offsets)
            val bytes = PngCodec.encode(rendered)
            binaryEnvelope(session, "png", bytes, rendered.width, rendered.height)
        }

        add("iso_diamond_mask", "Renders one isometric diamond footprint mask (PNG data_b64) — the tile shape artists align to.", "v3-map",
            "tile_w" to "integer", "tile_h" to "integer",
            required = emptyList()) { params, _ ->
            val tileW = params.opt("tile_w", 32)
            val tileH = params.opt("tile_h", 16)
            require(tileW in 2..256) { "'tile_w' must be in [2, 256] (was $tileW)" }
            require(tileH in 1..256) { "'tile_h' must be in [1, 256] (was $tileH)" }
            val mask = Isometric.isoDiamondMask(tileW, tileH)
            val bytes = PngCodec.encode(mask)
            binaryEnvelope(null, "png", bytes, mask.width, mask.height)
        }

        // ---- v3-atlas -----------------------------------------------------------

        add("io_export_atlas", "Packs all composited frames into one texture atlas PNG + engine metadata (generic/godot3/godot4/tiled/phaser).", "v3-atlas",
            "session_id" to "string", "padding" to "integer", "extrude" to "integer",
            "trim" to "boolean", "scale" to "integer", "format" to "string",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val padding = params.opt("padding", 1)
            val extrude = params.opt("extrude", 0)
            val trim = params.opt("trim", false)
            val scale = params.opt("scale", 1)
            val formatRaw = params.opt("format", "generic").trim().lowercase()
            require(padding in 0..64) { "'padding' must be in [0, 64] (was $padding)" }
            require(extrude in 0..16) { "'extrude' must be in [0, 16] (was $extrude)" }
            require(scale in 1..16) { "'scale' must be in [1, 16] (was $scale)" }
            val format = when (formatRaw) {
                "generic", "pixellab" -> AtlasFormat.GENERIC
                "godot3", "godot-3" -> AtlasFormat.GODOT3
                "godot4", "godot-4" -> AtlasFormat.GODOT4
                "tiled" -> AtlasFormat.TILED
                "phaser" -> AtlasFormat.PHASER
                else -> throw IllegalArgumentException(
                    "'format' must be generic, godot3, godot4, tiled or phaser (was '$formatRaw')",
                )
            }
            val inputs = (0 until project.frameCount).map { index ->
                val frame = project.compositeFrame(index)
                AtlasInput("frame-$index", if (scale == 1) frame else frame.scaledNearest(scale))
            }
            val result = AtlasBuilder.build(inputs, padding, extrude, trim)
            val metadata = AtlasMetadata.toJson(result, format)
            val bytes = PngCodec.encode(result.atlas)
            jsonobj {
                put("session_id", session.id)
                put("format", "png")
                put("byte_count", bytes.size)
                put("width", result.width)
                put("height", result.height)
                put("regions", result.regions.size)
                put("metadata_format", formatRaw)
                put("metadata_char_count", metadata.length)
                put("truncated", metadata.length > TEXT_RESULT_LIMIT)
                if (metadata.length <= TEXT_RESULT_LIMIT) {
                    put("metadata", metadata)
                } else {
                    put("note", "metadata has ${metadata.length} characters, exceeding the $TEXT_RESULT_LIMIT inline limit")
                }
                put("data_b64", Base64.getEncoder().encodeToString(bytes))
            }
        }

        add("texture_trim", "Crops the active cel to its opaque content bounds; returns the crop offsets.", "v3-atlas",
            "session_id" to "string",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val cel = requireActiveCel(project)
            val bounds = TextureOps.contentBounds(cel)
                ?: throw IllegalArgumentException("active cel is fully transparent — nothing to trim")
            val rect = com.pixellab.core.atlas.Rect(bounds.x, bounds.y, bounds.w, bounds.h)
            val next = if (bounds.w == project.width && bounds.h == project.height) {
                project
            } else {
                // Cropping changes the cel geometry, so the canvas (and every
                // sibling cel) is cropped by the same rectangle — content
                // stays pixel-aligned because bounds come from the active cel.
                val newFrames = project.frames.map { frame ->
                    val cels = frame.cels.mapValues { (_, c) -> TextureOps.crop(c, rect) }
                    Frame(frame.id, cels, frame.durationMs)
                }
                project.copy(width = bounds.w, height = bounds.h, frames = newFrames)
            }
            commit(store, session, "texture_trim", project, next)
            jsonobj {
                put("session_id", session.id)
                put("width", next.width)
                put("height", next.height)
                put("frames", next.frameCount)
                put("layers", next.layerCount)
                put("undo_depth", historyFor(session.id).undoDepth())
                put("trimmed_to", jsonobj {
                    put("x", bounds.x)
                    put("y", bounds.y)
                    put("w", bounds.w)
                    put("h", bounds.h)
                })
            }
        }

        add("texture_extrude", "Replicates the active cel's edge pixels outward by `amount` (anti-seam halo).", "v3-atlas",
            "session_id" to "string", "amount" to "integer",
            required = listOf("session_id")) { params, store ->
            val amount = params.opt("amount", 1)
            require(amount in 1..16) { "'amount' must be in [1, 16] (was $amount)" }
            mutate(params, store, "texture_extrude") { project ->
                val cel = requireActiveCel(project)
                val grown = TextureOps.extrudeEdges(cel, amount)
                if (grown.width != project.width || grown.height != project.height) {
                    // Extrusion grows the cel; grow the canvas to match.
                    val newFrames = project.frames.map { frame ->
                        val cels = frame.cels.mapValues { (_, cel) ->
                            TextureOps.extrudeEdges(cel, amount)
                        }
                        Frame(frame.id, cels, frame.durationMs)
                    }
                    project.copy(width = grown.width, height = grown.height, frames = newFrames)
                } else {
                    project.withActiveCel(grown)
                }
            }
        }

        // ---- v3-history ---------------------------------------------------------

        add("history_status", "Reports the v3 command history of a session: depths, entry labels, modified flag.", "v3-history",
            "session_id" to "string",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val history = historyFor(session.id)
            jsonobj {
                put("session_id", session.id)
                put("undo_depth", history.undoDepth())
                put("redo_depth", history.redoDepth())
                put("modified", history.isModified)
                put("entry_limit", 64)
                put(
                    "entries",
                    jsonarray {
                        for (entry in history.entries()) {
                            add(
                                jsonobj {
                                    put("label", entry.command.label)
                                    put("timestamp_ms", entry.timestampMs)
                                },
                            )
                        }
                    },
                )
                put("maps", mapCount(session.id))
            }
        }

        add("history_undo", "Undoes the newest v3 edit of a session and restores the project snapshot.", "v3-history",
            "session_id" to "string",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val history = historyFor(session.id)
            val restored = history.undo(session.project)
                ?: throw IllegalArgumentException(
                    "nothing to undo (depth ${history.undoDepth()}, or the project diverged from the history)",
                )
            store.update(session.id, restored)
            projectSummary(session, restored)
        }

        add("history_redo", "Re-applies the newest undone v3 edit of a session.", "v3-history",
            "session_id" to "string",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val history = historyFor(session.id)
            val reapplied = history.redo(session.project)
                ?: throw IllegalArgumentException(
                    "nothing to redo (depth ${history.redoDepth()}, or the project diverged from the history)",
                )
            store.update(session.id, reapplied)
            projectSummary(session, reapplied)
        }

        add("history_mark_saved", "Marks the current v3 timeline position as the session's save point.", "v3-history",
            "session_id" to "string",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val history = historyFor(session.id)
            history.markSaved()
            jsonobj {
                put("session_id", session.id)
                put("modified", history.isModified)
                put("undo_depth", history.undoDepth())
                put("redo_depth", history.redoDepth())
            }
        }

        // ---- v3-pipeline --------------------------------------------------------

        add("pipeline_run", "Runs a recipe JSON over the session's composited frames and stores the transformed project.", "v3-pipeline",
            "session_id" to "string", "recipe" to "string",
            required = listOf("session_id", "recipe")) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            val recipeText = params.string("recipe")
            val recipe = RecipeParser.parse(recipeText)
            val frames = (0 until project.frameCount).map { project.compositeFrame(it) }
            val result = PipelineRunner.run(recipe, frames)
            val transformed = when (result) {
                is PixelResult.Ok -> result.value
                is PixelResult.Err -> throw McpToolException(
                    "pipeline failed: ${result.failure.kind}: ${result.failure.message}",
                )
            }
            require(transformed.isNotEmpty()) { "pipeline produced no frames" }
            val imported = ImportedFrames(
                ImageFormat.PNG,
                transformed[0].width,
                transformed[0].height,
                transformed,
                project.frames.map { it.durationMs ?: 0 },
                project.palette,
            )
            val rebuilt = ImageImporter.importProjectFromFrames(imported, project.name)
                .copy(id = project.id, name = project.name, palette = project.palette)
            commit(store, session, "pipeline_run '${recipe.name}'", session.project, rebuilt)
            jsonobj {
                put("session_id", session.id)
                put("project_id", rebuilt.id)
                put("name", rebuilt.name)
                put("width", rebuilt.width)
                put("height", rebuilt.height)
                put("frames", rebuilt.frameCount)
                put("layers", 1)
                put("recipe", recipe.name)
                put("steps", recipe.steps.size)
                put("undo_depth", historyFor(session.id).undoDepth())
            }
        }

        add("pipeline_recipe_validate", "Parses a recipe JSON and summarizes its steps; pure, no session required.", "v3-pipeline",
            "recipe" to "string",
            required = listOf("recipe")) { params, _ ->
            val recipe = RecipeParser.parse(params.string("recipe"))
            jsonobj {
                put("name", recipe.name)
                put("steps", recipe.steps.size)
                put(
                    "step_summary",
                    jsonarray {
                        for ((index, step) in recipe.steps.withIndex()) {
                            add(
                                jsonobj {
                                    put("index", index)
                                    put("type", stepTypeName(step))
                                    put("description", describeStep(step))
                                },
                            )
                        }
                    },
                )
            }
        }

        return tools to schemas
    }

    /** Wire type name of a recipe step. */
    private fun stepTypeName(step: com.pixellab.core.pipeline.RecipeStep): String = when (step) {
        is com.pixellab.core.pipeline.RecipeStep.ScaleStep -> "scale"
        is com.pixellab.core.pipeline.RecipeStep.QuantizeStep -> "quantize"
        is com.pixellab.core.pipeline.RecipeStep.DitherStep -> "dither"
        is com.pixellab.core.pipeline.RecipeStep.PaletteMapStep -> "palette-map"
        is com.pixellab.core.pipeline.RecipeStep.OutlineStep -> "outline"
        is com.pixellab.core.pipeline.RecipeStep.TrimStep -> "trim"
        is com.pixellab.core.pipeline.RecipeStep.PosterizeStep -> "posterize"
        is com.pixellab.core.pipeline.RecipeStep.BgRemoveStep -> "bg-remove"
    }

    /** One-line description of a recipe step for validation summaries. */
    private fun describeStep(step: com.pixellab.core.pipeline.RecipeStep): String = when (step) {
        is com.pixellab.core.pipeline.RecipeStep.ScaleStep -> "scale by ${step.factor}x"
        is com.pixellab.core.pipeline.RecipeStep.QuantizeStep ->
            "quantize to ${step.maxColors} colors (${step.algorithm})"
        is com.pixellab.core.pipeline.RecipeStep.DitherStep ->
            "dither ${step.algorithm} (strength ${step.strength})"
        is com.pixellab.core.pipeline.RecipeStep.PaletteMapStep ->
            "map to palette ${step.paletteId ?: "(frames' own)"}"
        is com.pixellab.core.pipeline.RecipeStep.OutlineStep ->
            "outline ${hexArgb(step.color)} ${step.mode.name.lowercase()}/${step.connectivity.name.lowercase()}"
        is com.pixellab.core.pipeline.RecipeStep.TrimStep -> "trim to union content bounds"
        is com.pixellab.core.pipeline.RecipeStep.PosterizeStep -> "posterize to ${step.levels} levels"
        is com.pixellab.core.pipeline.RecipeStep.BgRemoveStep -> "remove background (tolerance ${step.tolerance})"
    }

    /** V3 history command: a label and never-coalescing identity. */
    private class V3Command(override val label: String) : EditorCommand {
        override val coalesceKey: String? get() = null
    }
}
