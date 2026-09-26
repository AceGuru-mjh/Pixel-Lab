package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.core.describe.AsciiRenderOptions
import com.pixellab.core.describe.AsciiRenderer
import com.pixellab.core.describe.AsciiStyle
import com.pixellab.core.describe.ColorCensus
import com.pixellab.core.describe.DescribeOptions
import com.pixellab.core.describe.FrameDescriber
import com.pixellab.core.describe.FrameDiff
import com.pixellab.core.describe.RegionFormat
import com.pixellab.core.describe.RegionReader
import com.pixellab.core.describe.RegionRequest
import com.pixellab.core.describe.StructureAnalyzer
import com.pixellab.core.describe.SymmetryAnalyzer
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.mcp.json.JsonNull
import com.pixellab.mcp.json.JsonNumber
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonarray
import com.pixellab.mcp.json.jsonobj
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fifth-tier MCP tool registry for Pixel Lab: **agent vision** — the eleven
 * read-only tools that let an MCP client *see* a canvas instead of drawing
 * blind.
 *
 * Before this tier, every one of the 150+ tools pushed pixels onto the
 * canvas; the only read-back was `pick_color` (a single pixel). An agent
 * could draw but never verify, describe or compare. This registry closes
 * that loop:
 *
 *  * **v5-read** (4) — [canvas_read] region dumps in four formats (hex
 *    grid, palette indices, RLE, sketch-with-legend), [pixel_probe] with
 *    neighborhood context, [canvas_ascii] ASCII-art rendering (LETTERS /
 *    SHADES / BLOCKS) and [canvas_legend] full-canvas sketch round-trips.
 *  * **v5-describe** (4) — [canvas_describe] natural-language summaries,
 *    [canvas_stats] occupancy/geometry numbers, [canvas_colors] the color
 *    census with palette coverage, [canvas_frames_summary] per-frame
 *    animation overviews.
 *  * **v5-interpret** (3) — [canvas_structure] connected-region reading
 *    with a layout fingerprint, [canvas_symmetry] axis probes and
 *    [canvas_diff] before/after comparisons (vs the pre-last-operation
 *    snapshot, another frame, or another session).
 *
 * Every tool is read-only: none mutates the session project or records
 * history, so a vision call never disturbs undo state. Handlers validate
 * parameters eagerly (`IllegalArgumentException` → [McpToolException] →
 * JSON-RPC `-32602`), and execution is serialized by one mutex for
 * consistency with the other tiers even though reads are side-effect free.
 *
 * ## Hosting
 *
 * Same strategy as v2–v4: a *standalone dispatcher* chained into
 * [McpToolRouter]; unknown tool names throw [McpToolException] with
 * `JsonRpc.METHOD_NOT_FOUND` so the router keeps walking the chain.
 *
 * @param lab shared engine facade — used for `peekBefore` (last-op diffs);
 *   kept even when unused for wiring parity with v1–v4.
 */
class McpToolRegistryV5(private val lab: PixelLab = PixelLab.create()) {

    private val mutex = Mutex()

    /** Tool descriptors in registration order. */
    val tools: List<McpTool>

    /** JSON schema map by tool name (for `tools/list`). */
    val schemas: Map<String, JsonObject>

    init {
        val built = buildTools()
        tools = built.first
        schemas = built.second
    }

    /**
     * Dispatches one `tools/call`. Throws [McpToolException] with
     * `METHOD_NOT_FOUND` for unknown names (the tier-chain contract).
     */
    suspend fun execute(name: String, args: JsonObject, store: PixelSessionStore): JsonObject {
        val tool = tools.firstOrNull { it.name == name }
            ?: throw McpToolException("unknown v5 tool '$name'", JsonRpc.METHOD_NOT_FOUND)
        return try {
            mutex.withLock { tool.handler(args, store) }
        } catch (error: IllegalArgumentException) {
            throw McpToolException(error.message ?: "invalid parameters for tool '$name'")
        }
    }

    /** True when [name] is one of this registry's tools. */
    operator fun contains(name: String): Boolean = tools.any { it.name == name }

    private fun num(value: Double): JsonNumber = JsonNumber(value, null)
    private fun num(value: Long): JsonNumber = JsonNumber(value.toDouble(), value.toString())
    private fun num(value: Int): JsonNumber = JsonNumber(value.toDouble(), value.toString())

    // ------------------------------------------------------------------
    // Session helpers
    // ------------------------------------------------------------------

    private fun sessionOf(params: JsonObject, store: PixelSessionStore): PixelSessionStore.SessionState {
        val id = params.string("session_id")
        return requireNotNull(store.get(id, create = true)) { "session '$id' unavailable" }
    }

    /**
     * Composited frame for reading: `frame_index` parameter when present
     * (validated), else the session's active frame.
     */
    private fun readFrame(params: JsonObject, project: SpriteProject): PixelFrame {
        val index = optionalInt(params, "frame_index") ?: project.activeFrameIndex
        if (index < 0 || index >= project.frameCount) {
            throw IllegalArgumentException(
                "frame_index $index outside 0..${project.frameCount - 1} (project has ${project.frameCount} frames)",
            )
        }
        return project.compositeFrame(index)
    }

    /** Optional nullable int parameter (absent or JSON null → null). */
    private fun optionalInt(params: JsonObject, key: String): Int? {
        val raw = params.raw(key) ?: return null
        if (raw is JsonNull) return null
        return params.int(key)
    }

    /** Optional built-in palette parameter (null when absent or JSON null). */
    private fun paletteParam(params: JsonObject, key: String = "palette_id"): Palette? {
        val raw = params.raw(key) ?: return null
        if (raw is JsonNull) return null
        val id = (raw as? JsonString)?.value
            ?: throw IllegalArgumentException("parameter '$key' must be a palette id string")
        return BuiltInPalettes.byId(id)
            ?: throw IllegalArgumentException("unknown palette '$id' (see palette_list)")
    }

    /** Frame size echo shared by every v5 result envelope. */
    private fun frameMeta(sessionId: String, frame: PixelFrame, frameIndex: Int): JsonObject =
        jsonobj {
            put("session_id", sessionId)
            put("width", frame.width)
            put("height", frame.height)
            put("frame_index", frameIndex)
        }

    // ------------------------------------------------------------------
    // Registry
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

        // ---- v5-read: what is on the canvas? ------------------------------

        add(
            "canvas_read",
            "Reads a rectangular region of the canvas back as text so the agent can see it: format 'hex' (#rrggbb grid), 'palette_index' (grid of palette indices, needs palette_id), 'rle' (run-length rows) or 'sketch' (char grid + legend, re-editable and drawable via sketch_draw). Out-of-frame cells read as transparent.",
            "v5-read",
            "session_id" to "string", "x" to "integer", "y" to "integer",
            "width" to "integer", "height" to "integer",
            "format" to "string", "frame_index" to "integer", "palette_id" to "string",
            required = listOf("session_id", "width", "height"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val x = params.opt("x", 0)
            val y = params.opt("y", 0)
            val width = params.int("width")
            val height = params.int("height")
            require(width in 1..8192 && height in 1..8192) {
                "region edges must be in [1, 8192] (was ${width}x${height})"
            }
            val format = when (params.opt("format", "hex").trim().lowercase()) {
                "hex" -> RegionFormat.HEX
                "palette_index", "palette", "index" -> RegionFormat.PALETTE_INDEX
                "rle" -> RegionFormat.RLE
                "sketch" -> RegionFormat.SKETCH
                else -> throw IllegalArgumentException(
                    "format must be hex, palette_index, rle or sketch (was '${params.opt("format", "hex")}')",
                )
            }
            val palette = paletteParam(params)
            if (format == RegionFormat.PALETTE_INDEX) {
                requireNotNull(palette) { "format 'palette_index' requires parameter 'palette_id'" }
            }
            val read = RegionReader.read(frame, RegionRequest(x, y, width, height))
            jsonobj {
                for ((k, v) in frameMeta(session.id, frame, params.opt("frame_index", session.project.activeFrameIndex)).entries) put(k, v)
                put("region", jsonobj {
                    put("x", x)
                    put("y", y)
                    put("requested_width", width)
                    put("requested_height", height)
                    put("clipped_x", read.clippedX)
                    put("clipped_y", read.clippedY)
                    put("clipped_width", read.clippedWidth)
                    put("clipped_height", read.clippedHeight)
                    put("out_of_frame_cells", read.outOfFrameCells)
                })
                put("format", format.name.lowercase())
                put("content", RegionReader.format(read, format, palette))
            }
        }

        add(
            "canvas_ascii",
            "Renders the canvas as ASCII art the agent can quote and reason about. style 'letters' (default: one char per color, most frequent = 'A', legend included), 'shades' (luminance ramp ' .:-=+*#%@') or 'blocks' (compact two-rows-per-line). max_width (4..256, default 64) box-samples larger canvases down.",
            "v5-read",
            "session_id" to "string", "style" to "string", "max_width" to "integer",
            "frame_index" to "integer", "palette_id" to "string",
            "transparent_char" to "string", "ascii_only" to "boolean", "invert_shades" to "boolean",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val style = when (params.opt("style", "letters").trim().lowercase()) {
                "letters", "letter" -> AsciiStyle.LETTERS
                "shades", "shade", "luminance" -> AsciiStyle.SHADES
                "blocks", "block", "half" -> AsciiStyle.BLOCKS
                else -> throw IllegalArgumentException(
                    "style must be letters, shades or blocks (was '${params.opt("style", "letters")}')",
                )
            }
            val maxWidth = params.opt("max_width", 64)
            require(maxWidth in 4..256) { "max_width must be in 4..256 (was $maxWidth)" }
            val transparentChar = params.opt("transparent_char", ".").firstOrNull() ?: '.'
            val render = AsciiRenderer.render(
                frame,
                style,
                AsciiRenderOptions(
                    maxWidth = maxWidth,
                    transparentChar = transparentChar,
                    asciiOnly = params.opt("ascii_only", false),
                    invertShades = params.opt("invert_shades", false),
                ),
                paletteParam(params),
            )
            jsonobj {
                for ((k, v) in frameMeta(session.id, frame, params.opt("frame_index", session.project.activeFrameIndex)).entries) put(k, v)
                put("style", style.name.lowercase())
                put("sampled_width", render.width)
                put("sampled_height", render.height)
                put("stride_x", render.strideX)
                put("stride_y", render.strideY)
                put("art", render.art)
                if (render.legend.isNotEmpty()) {
                    put("legend", jsonarray {
                        for (entry in render.legend) {
                            add(jsonobj {
                                put("char", entry.char.toString())
                                put("hex", entry.hex)
                                put("name", entry.name)
                                put("cells", entry.cells)
                            })
                        }
                    })
                }
            }
        }

        add(
            "pixel_probe",
            "Inspects one pixel with context: exact color, nearest CSS name, alpha, and the 3x3 neighborhood grid around it (optionally radius 1..4). Answers 'what is at (x, y)?' precisely.",
            "v5-read",
            "session_id" to "string", "x" to "integer", "y" to "integer",
            "radius" to "integer", "frame_index" to "integer",
            required = listOf("session_id", "x", "y"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val x = params.int("x")
            val y = params.int("y")
            val radius = params.opt("radius", 1)
            require(radius in 1..4) { "radius must be in 1..4 (was $radius)" }
            val argb = frame[x, y]
            jsonobj {
                for ((k, v) in frameMeta(session.id, frame, params.opt("frame_index", session.project.activeFrameIndex)).entries) put(k, v)
                put("x", x)
                put("y", y)
                put("description", FrameDescriber.describePixel(frame, x, y))
                put("in_bounds", x in 0 until frame.width && y in 0 until frame.height)
                put("argb", argb)
                put("hex", ColorCensus.hex(argb))
                put("neighborhood", jsonarray {
                    for (ny in y - radius..y + radius) {
                        add(jsonarray {
                            for (nx in x - radius..x + radius) {
                                add(JsonString(ColorCensus.hex(frame[nx, ny])))
                            }
                        })
                    }
                })
            }
        }

        add(
            "canvas_legend",
            "Dumps the whole canvas as a re-editable sketch: char-to-color legend plus the char grid (transparent = '.'). Edit the text and draw it back with sketch_draw for painless hand-tuning.",
            "v5-read",
            "session_id" to "string", "frame_index" to "integer", "palette_id" to "string",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val read = RegionReader.read(frame, RegionRequest(0, 0, frame.width, frame.height))
            jsonobj {
                for ((k, v) in frameMeta(session.id, frame, params.opt("frame_index", session.project.activeFrameIndex)).entries) put(k, v)
                put("sketch", RegionReader.format(read, RegionFormat.SKETCH, paletteParam(params)))
                put("hint", "edit the grid, keep the legend lines 'C=#hex', then sketch_draw with this text")
            }
        }

        // ---- v5-describe: what does the canvas look like? ------------------

        add(
            "canvas_describe",
            "Natural-language description of the canvas for agents: dimensions, occupancy, dominant colors with names, region structure, symmetry and a coarse layout fingerprint. Optional sections parameter (comma list of colors,structure,symmetry,fingerprint) and palette_id for palette-coverage auditing.",
            "v5-describe",
            "session_id" to "string", "frame_index" to "integer", "palette_id" to "string",
            "sections" to "string", "max_colors" to "integer", "max_blobs" to "integer",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val sectionsParam = params.opt("sections", "colors,structure,symmetry,fingerprint")
            val wanted = sectionsParam.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            for (s in wanted) {
                require(s in setOf("colors", "structure", "symmetry", "fingerprint")) {
                    "unknown section '$s' (valid: colors, structure, symmetry, fingerprint)"
                }
            }
            val options = DescribeOptions(
                includeColors = wanted.isEmpty() || "colors" in wanted,
                includeStructure = wanted.isEmpty() || "structure" in wanted,
                includeSymmetry = wanted.isEmpty() || "symmetry" in wanted,
                includeFingerprint = wanted.isEmpty() || "fingerprint" in wanted,
                maxColors = params.opt("max_colors", 8).coerceIn(1, 32),
                maxBlobs = params.opt("max_blobs", 5).coerceIn(1, 32),
            )
            val palette = paletteParam(params)
            val description = FrameDescriber.describe(frame, options, palette)
            jsonobj {
                for ((k, v) in frameMeta(session.id, frame, params.opt("frame_index", session.project.activeFrameIndex)).entries) put(k, v)
                put("text", description.text)
                description.census?.let { census ->
                    put("unique_colors", census.uniqueColors)
                    put("occupancy", num(census.occupancy))
                    put("transparent_pixels", census.transparentPixels)
                }
                description.structure?.let { structure ->
                    put("regions", structure.blobs.size)
                    put("isolated_pixels", structure.isolatedPixels)
                    structure.contentBox?.let { box ->
                        put("content_box", jsonarray { box.forEach { v -> add(JsonNumber.of(v.toLong())) } })
                    }
                }
                description.symmetry?.let { symmetry ->
                    put("symmetry", jsonarray {
                        for (v in symmetry.verdicts) {
                            add(jsonobj {
                                put("kind", v.kind.name.lowercase())
                                put("holds", v.holds)
                                put("mismatched_pairs", v.mismatchedPairs)
                                put("compared_pairs", v.comparedPairs)
                            })
                        }
                    })
                }
                description.coverage?.let { coverage ->
                    put("palette_coverage", jsonobj {
                        put("palette_id", coverage.paletteId)
                        put("used_entries", coverage.usedEntries)
                        put("palette_size", coverage.paletteSize)
                        put("orphan_shades", coverage.orphanShades)
                    })
                }
            }
        }

        add(
            "canvas_stats",
            "Numeric canvas summary: dimensions, pixel totals, occupancy ratio, visible/transparent/semi-transparent counts, unique colors, content bounding box and isolated-dot count. The cheap 'how full is my canvas' call.",
            "v5-describe",
            "session_id" to "string", "frame_index" to "integer",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val census = ColorCensus.census(frame)
            val structure = StructureAnalyzer.analyze(frame)
            jsonobj {
                for ((k, v) in frameMeta(session.id, frame, params.opt("frame_index", session.project.activeFrameIndex)).entries) put(k, v)
                put("pixel_count", frame.pixelCount)
                put("visible_pixels", census.visiblePixels)
                put("transparent_pixels", census.transparentPixels)
                put("semi_transparent_pixels", census.semiTransparentPixels)
                put("occupancy", num(census.occupancy))
                put("unique_colors", census.uniqueColors)
                put("isolated_pixels", structure.isolatedPixels)
                put("region_count", structure.blobs.size)
                structure.contentBox?.let { box ->
                    put("content_box", jsonobj {
                        put("x", box[0]); put("y", box[1]); put("w", box[2]); put("h", box[3])
                    })
                } ?: put("content_box", JsonNull)
            }
        }

        add(
            "canvas_colors",
            "Color census of the canvas: every distinct color with count, hex and nearest CSS name, sorted most-frequent first, plus family rollup (red/green/blue/...). With palette_id, adds coverage: which palette entries are used and which canvas colors are off-palette.",
            "v5-describe",
            "session_id" to "string", "frame_index" to "integer",
            "max_colors" to "integer", "palette_id" to "string",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val census = ColorCensus.census(frame)
            val maxColors = params.opt("max_colors", 16).coerceIn(1, 64)
            val palette = paletteParam(params)
            jsonobj {
                for ((k, v) in frameMeta(session.id, frame, params.opt("frame_index", session.project.activeFrameIndex)).entries) put(k, v)
                put("unique_colors", census.uniqueColors)
                put("colors", jsonarray {
                    for (t in census.tallies.take(maxColors)) {
                        add(jsonobj {
                            put("hex", t.hex)
                            put("argb", t.argb)
                            put("count", t.count)
                            put("name", t.name)
                            put("family", t.family)
                        })
                    }
                })
                put("families", jsonarray {
                    for (f in census.families) {
                        add(jsonobj {
                            put("family", f.family)
                            put("pixels", f.pixels)
                            put("shades", f.shades)
                        })
                    }
                })
                palette?.let {
                    val coverage = ColorCensus.coverage(census, it)
                    put("coverage", jsonobj {
                        put("palette_id", coverage.paletteId)
                        put("used_entries", coverage.usedEntries)
                        put("palette_size", coverage.paletteSize)
                        put("orphan_shades", coverage.orphanShades)
                        if (coverage.orphanExamples.isNotEmpty()) {
                            put("orphan_examples", jsonarray { coverage.orphanExamples.forEach { e -> add(JsonString(e)) } })
                        }
                        put("per_entry_pixels", jsonarray { coverage.matchedPixels.forEach { p -> add(JsonNumber.of(p.toLong())) } })
                    })
                }
            }
        }

        add(
            "canvas_frames_summary",
            "Per-frame overview of an animation project: for every frame its occupancy, dominant color, unique colors and content box — spot broken frames at a glance without dumping each one.",
            "v5-describe",
            "session_id" to "string",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val project = session.project
            jsonobj {
                put("session_id", session.id)
                put("frame_count", project.frameCount)
                put("fps", project.fps)
                put("frames", jsonarray {
                    for (i in 0 until project.frameCount) {
                        val frame = project.compositeFrame(i)
                        val census = ColorCensus.census(frame)
                        val box = StructureAnalyzer.contentBox(frame)
                        add(jsonobj {
                            put("index", i)
                            put("id", project.frames[i].id)
                            put("effective_ms", project.effectiveFrameDuration(i))
                            put("occupancy", num(census.occupancy))
                            put("unique_colors", census.uniqueColors)
                            census.dominant?.let { put("dominant", it.hex); put("dominant_name", it.name) }
                            box?.let { b -> put("content_box", jsonarray { b.forEach { v -> add(JsonNumber.of(v.toLong())) } }) }
                        })
                    }
                })
            }
        }

        // ---- v5-interpret: what does it mean? -------------------------------

        add(
            "canvas_structure",
            "Structural read of the canvas: connected regions ('blobs') sorted by size with area, bounding box, dominant color, holes and shape verdict ('solid block', 'ring/outline', 'thin outline', ...), plus the coarse occupancy fingerprint grid.",
            "v5-interpret",
            "session_id" to "string", "frame_index" to "integer",
            "max_blobs" to "integer", "connectivity" to "string",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val connectivity = when (params.opt("connectivity", "four").trim().lowercase()) {
                "four", "4" -> com.pixellab.core.analysis.Connectivity.FOUR
                "eight", "8" -> com.pixellab.core.analysis.Connectivity.EIGHT
                else -> throw IllegalArgumentException("connectivity must be 'four' or 'eight'")
            }
            val report = StructureAnalyzer.analyze(
                frame,
                connectivity = connectivity,
                maxBlobs = params.opt("max_blobs", 16).coerceIn(1, 64),
            )
            jsonobj {
                for ((k, v) in frameMeta(session.id, frame, params.opt("frame_index", session.project.activeFrameIndex)).entries) put(k, v)
                put("region_count", report.blobs.size)
                put("isolated_pixels", report.isolatedPixels)
                put("blobs", jsonarray {
                    for (b in report.blobs) {
                        add(jsonobj {
                            put("id", b.id)
                            put("area", b.area)
                            put("box_x", b.boxX); put("box_y", b.boxY)
                            put("box_w", b.boxW); put("box_h", b.boxH)
                            put("dominant_hex", ColorCensus.hex(b.dominantColor))
                            put("dominant_name", b.dominantName)
                            put("holes", b.holes)
                            put("touches_border", b.touchesBorder)
                            put("fill_ratio", num(b.fillRatio))
                            put("shape", StructureAnalyzer.shapeVerdict(b))
                        })
                    }
                })
                put("fingerprint_cols", report.fingerprint.cols)
                put("fingerprint_rows", report.fingerprint.rows)
                put("fingerprint", report.fingerprint.rowsAsText.joinToString("\n"))
            }
        }

        add(
            "canvas_symmetry",
            "Probes the canvas for symmetry: horizontal (left-right), vertical, 180-degree rotation and both diagonals (square canvases only). tolerance is max per-channel color distance (0 = exact). Returns per-axis verdicts plus a one-line summary.",
            "v5-interpret",
            "session_id" to "string", "frame_index" to "integer", "tolerance" to "integer",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val tolerance = params.opt("tolerance", 0)
            require(tolerance in 0..255) { "tolerance must be in 0..255 (was $tolerance)" }
            val report = SymmetryAnalyzer.analyze(frame, tolerance)
            jsonobj {
                for ((k, v) in frameMeta(session.id, frame, params.opt("frame_index", session.project.activeFrameIndex)).entries) put(k, v)
                put("tolerance", tolerance)
                put("summary", SymmetryAnalyzer.summarize(report))
                put("axes", jsonarray {
                    for (v in report.verdicts) {
                        add(jsonobj {
                            put("kind", v.kind.name.lowercase())
                            put("holds", v.holds)
                            put("mismatched_pairs", v.mismatchedPairs)
                            put("compared_pairs", v.comparedPairs)
                            put("mismatch_ratio", num(v.mismatchRatio))
                        })
                    }
                })
            }
        }

        add(
            "canvas_diff",
            "Compares the canvas against another state and reports exactly what changed (added/removed/recolored pixels, bounding boxes, color transitions): mode 'last_op' (vs the state before the most recent operation), 'frames' (frame_a vs frame_b of this session) or 'sessions' (vs other_session_id's active frame).",
            "v5-interpret",
            "session_id" to "string", "mode" to "string",
            "frame_a" to "integer", "frame_b" to "integer",
            "other_session_id" to "string",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val mode = params.opt("mode", "last_op").trim().lowercase()
            val after: PixelFrame
            val before: PixelFrame
            var lastOpLabel: String? = null
            when (mode) {
                "last_op", "last", "lastop" -> {
                    val previous = lab.engine.peekBefore(session.project.id)
                        ?: throw IllegalArgumentException(
                            "no previous operation to diff against (undo history is empty for session '${session.id}')",
                        )
                    before = previous.compositeActiveFrame()
                    after = session.project.compositeActiveFrame()
                    lastOpLabel = lab.engine.historyInfo(session.project.id).lastOrNull()?.label
                }
                "frames", "frame" -> {
                    val a = optionalInt(params, "frame_a") ?: session.project.activeFrameIndex
                    val b = optionalInt(params, "frame_b")
                        ?: throw IllegalArgumentException("mode 'frames' requires parameter 'frame_b'")
                    for ((name, idx) in listOf("frame_a" to a, "frame_b" to b)) {
                        if (idx < 0 || idx >= session.project.frameCount) {
                            throw IllegalArgumentException(
                                "$name $idx outside 0..${session.project.frameCount - 1}",
                            )
                        }
                    }
                    before = session.project.compositeFrame(a)
                    after = session.project.compositeFrame(b)
                }
                "sessions", "session" -> {
                    val otherId = params.opt("other_session_id", "")
                    require(otherId.isNotEmpty()) { "mode 'sessions' requires parameter 'other_session_id'" }
                    val other = requireNotNull(store.get(otherId, create = false)) {
                        "session '$otherId' not found"
                    }
                    before = other.project.compositeActiveFrame()
                    after = session.project.compositeActiveFrame()
                }
                else -> throw IllegalArgumentException(
                    "mode must be last_op, frames or sessions (was '$mode')",
                )
            }
            val diff = FrameDiff.diff(before, after)
            jsonobj {
                put("session_id", session.id)
                put("mode", mode)
                lastOpLabel?.let { put("last_operation", it) }
                put("identical", diff.identical)
                put("added", diff.added)
                put("removed", diff.removed)
                put("changed", diff.changed)
                put("unchanged", diff.unchanged)
                put("summary", FrameDiff.summarize(diff))
                diff.addedBox?.let { put("added_box", jsonobj { put("x", it.x); put("y", it.y); put("w", it.w); put("h", it.h) }) }
                diff.removedBox?.let { put("removed_box", jsonobj { put("x", it.x); put("y", it.y); put("w", it.w); put("h", it.h) }) }
                diff.changedBox?.let { put("changed_box", jsonobj { put("x", it.x); put("y", it.y); put("w", it.w); put("h", it.h) }) }
                if (diff.transitions.isNotEmpty()) {
                    put("transitions", jsonarray {
                        for (t in diff.transitions) {
                            add(jsonobj {
                                put("from", ColorCensus.hex(t.from))
                                put("to", ColorCensus.hex(t.to))
                                put("count", t.count)
                            })
                        }
                    })
                }
            }
        }

        return tools to schemas
    }
}
