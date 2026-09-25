package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.core.analysis.AuditConfig
import com.pixellab.core.analysis.AuditSeverity
import com.pixellab.core.analysis.ChannelHistogram
import com.pixellab.core.analysis.ConnectedComponentOps
import com.pixellab.core.analysis.Connectivity
import com.pixellab.core.analysis.ConvolutionAlphaMode
import com.pixellab.core.analysis.ConvolutionEdgeMode
import com.pixellab.core.analysis.ConvolutionKernel
import com.pixellab.core.analysis.ConvolutionOps
import com.pixellab.core.analysis.HistogramOps
import com.pixellab.core.analysis.ImageMetrics
import com.pixellab.core.analysis.MorphShape
import com.pixellab.core.analysis.MorphologyOps
import com.pixellab.core.analysis.OtsuThreshold
import com.pixellab.core.analysis.PixelAuditor
import com.pixellab.core.analysis.StructElement
import com.pixellab.core.color.ColorNamer
import com.pixellab.core.color.ContrastAudit
import com.pixellab.core.color.ColorTemperature
import com.pixellab.core.color.ColorVision
import com.pixellab.core.gen.BiomePainter
import com.pixellab.core.gen.CaveGen
import com.pixellab.core.gen.DungeonGen
import com.pixellab.core.gen.LSystem
import com.pixellab.core.gen.MapTile
import com.pixellab.core.gen.SeededRng
import com.pixellab.core.motion.EmitterConfigView
import com.pixellab.core.motion.ParticleSystem
import com.pixellab.core.gen.WorleyNoise
import com.pixellab.core.history.EditorCommand
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.transform.EpxScale
import com.pixellab.core.transform.Resample
import com.pixellab.core.transform.RotateBoundsMode
import com.pixellab.core.transform.RotSprite
import com.pixellab.core.transform.Scale2x
import com.pixellab.core.transform.XbrScale
import com.pixellab.core.vector.MarchingSquares
import com.pixellab.core.vector.SvgExporter
import com.pixellab.mcp.json.JsonArray
import com.pixellab.mcp.json.JsonNull
import com.pixellab.mcp.json.JsonNumber
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonarray
import com.pixellab.mcp.json.jsonobj
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fourth-tier MCP tool registry for Pixel Lab: 32 additional tools
 * exposing the round-4 capability suites to agents —
 *
 *  * **v4-analysis** (10) — histograms, Otsu thresholds, equalize /
 *    auto-levels / binarize, convolution, morphology, connected
 *    components, image metrics (MAE/PSNR) and the pixel-art craft
 *    auditor with its 0-100 score.
 *  * **v4-transform** (6) — RotSprite arbitrary-angle rotation, the
 *    AdvMAME/EPX/xBR integer upscales, half-pixel-centered resampling
 *    and mipmap chains.
 *  * **v4-worldgen** (6) — Worley noise, BSP dungeons, cellular caves,
 *    biome painting, L-system plants and deterministic particle sims.
 *  * **v4-color** (7) — color-vision deficiency simulation, WCAG
 *    contrast auditing and suggestions, CSS color naming, Kelvin
 *    temperature and gray-world white balance.
 *  * **v4-vector** (3) — marching-squares contours and SVG export
 *    (static runs/outlines + SMIL-animated projects).
 *
 * ## Hosting (identical strategy to v2/v3)
 *
 * [PixelMcpServer] wires only the v1 registry; each later tier is a
 * *standalone dispatcher* constructed next to it. Dispatch tries
 * `v4.execute(name, args, store)` in the tier chain; unknown names throw
 * [McpToolException] with `JsonRpc.METHOD_NOT_FOUND` so a simple
 * try-chain routes any tool. The shared [PixelSessionStore] ties the
 * tiers together.
 *
 * ## Conventions
 *
 * Handlers validate parameters eagerly (`IllegalArgumentException` →
 * [McpToolException] → JSON-RPC `-32602`), mutating handlers write the
 * project back through [PixelSessionStore.update] and record a history
 * entry, execution is serialized by one mutex, and every tool that
 * produces pixels either updates the session project (frame tools) or
 * mints a fresh single-frame project (generators) — matching v3.
 *
 * @param lab shared engine facade, kept for parity with v1–v3 wiring.
 */
class McpToolRegistryV4(private val lab: PixelLab = PixelLab.create()) {

    private val mutex = Mutex()
    private val histories = ConcurrentHashMap<String, com.pixellab.core.history.CommandHistory>()

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
            ?: throw McpToolException("unknown v4 tool '$name'", JsonRpc.METHOD_NOT_FOUND)
        return mutex.withLock { tool.handler(args, store) }
    }

    /** True when [name] is one of this registry's tools. */
    operator fun contains(name: String): Boolean = tools.any { it.name == name }

    // ------------------------------------------------------------------
    // Session helpers (v3 pattern, condensed)
    // ------------------------------------------------------------------

    private fun sessionOf(params: JsonObject, store: PixelSessionStore): PixelSessionStore.SessionState {
        val id = params.string("session_id")
        return requireNotNull(store.get(id, create = true)) { "session '$id' unavailable" }
    }

    private fun historyFor(sessionId: String): com.pixellab.core.history.CommandHistory =
        histories.getOrPut(sessionId) { com.pixellab.core.history.CommandHistory() }

    private fun requireActiveCel(project: SpriteProject): PixelFrame {
        return project.activeCel()
            ?: throw IllegalArgumentException(
                "session project has no active cel (frame ${project.activeFrameIndex}, layer ${project.activeLayerId})",
            )
    }

    /** Runs [op] on the active cel, commits the project transition. */
    private fun mutateCel(
        params: JsonObject,
        store: PixelSessionStore,
        label: String,
        op: (PixelFrame) -> PixelFrame,
    ): JsonObject {
        val session = sessionOf(params, store)
        val before = session.project
        val cel = requireActiveCel(before)
        val after = before.withActiveCel(op(cel))
        historyFor(session.id).record(V4Command(label), before, after)
        store.update(session.id, after)
        return celSummary(session, after, label)
    }

    /** Standard mutating-tool response envelope. */
    private fun celSummary(session: PixelSessionStore.SessionState, project: SpriteProject, label: String): JsonObject =
        jsonobj {
            put("session_id", session.id)
            put("project_id", project.id)
            put("name", project.name)
            put("width", project.width)
            put("height", project.height)
            put("frames", project.frameCount)
            put("layers", project.layerCount)
            put("active_frame_index", project.activeFrameIndex)
            put("operation", label)
            put("undo_depth", historyFor(session.id).undoDepth())
        }

    /** Mints a fresh single-frame project from [frame] and commits it. */
    private fun commitNewProject(
        params: JsonObject,
        store: PixelSessionStore,
        name: String,
        frame: PixelFrame,
        palette: Palette,
    ): JsonObject {
        val session = sessionOf(params, store)
        val before = session.project
        val after = SpriteFactory.create(name, frame.width, frame.height, palette)
            .withActiveCel(frame)
        historyFor(session.id).record(V4Command(name), before, after)
        store.update(session.id, after)
        return jsonobj {
            put("session_id", session.id)
            put("project_id", after.id)
            put("name", after.name)
            put("width", after.width)
            put("height", after.height)
            put("frames", 1)
            put("layers", 1)
            put("operation", name)
            put("undo_depth", historyFor(session.id).undoDepth())
        }
    }

    /** The palette of the session project (tools that recolor need it). */
    private fun paletteOf(project: SpriteProject): Palette = project.palette

    /** Hex color rendering. */
    private fun hexArgb(argb: Int): String = "#" + "%08x".format(argb)

    private fun hexList(colors: List<Int>): JsonArray = jsonarray {
        for (color in colors) add(JsonString(hexArgb(color)))
    }

    /** Parses `#RRGGBB` / `#AARRGGBB` / ARGB-int parameter values. */
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

    /** Parses a hex color array parameter; null when absent. */
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

    private fun doubleParam(params: JsonObject, key: String, default: Double): Double = when (val raw = params.raw(key)) {
        is JsonNumber -> raw.value
        is JsonString -> raw.value.toDoubleOrNull() ?: throw IllegalArgumentException("'$key' must be a number")
        else -> default
    }

    private fun intParam(params: JsonObject, key: String, default: Int): Int = when (val raw = params.raw(key)) {
        is JsonNumber -> {
            if (raw.value != Math.floor(raw.value)) throw IllegalArgumentException("'$key' must be an integer")
            raw.value.toInt()
        }
        else -> default
    }

    private fun strParam(params: JsonObject, key: String, default: String): String = when (val raw = params.raw(key)) {
        is JsonString -> raw.value.trim().lowercase()
        else -> default
    }

    /** Chooses the seed (default 0) with 64-bit range. */
    private fun seedParam(params: JsonObject): Long = when (val raw = params.raw("seed")) {
        is JsonNumber -> raw.value.toLong()
        is JsonString -> raw.value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun num(value: Double): JsonNumber = JsonNumber(value, null)
    private fun num(value: Long): JsonNumber = JsonNumber(value.toDouble(), value.toString())
    private fun num(value: Int): JsonNumber = JsonNumber(value.toDouble(), value.toString())

    private class V4Command(override val label: String) : EditorCommand

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

        // ---- v4-analysis: histograms & point ops --------------------------

        add("frame_histogram", "Read-only per-channel statistics (R/G/B/A/Rec.709 luma) of the session's active cel: bin counts, mean, variance, entropy, percentiles and transparent ratio.", "v4-analysis",
            "session_id" to "string",
            required = listOf("session_id")) { params, store ->
            val project = sessionOf(params, store).project
            val frame = requireActiveCel(project)
            val h = HistogramOps.compute(frame)
            jsonobj {
                put("session_id", params.string("session_id"))
                put("width", frame.width)
                put("height", frame.height)
                put("sample_count", num(h.sampleCount.toLong()))
                put("transparent_ratio", num(h.transparentRatio))
                put("channels", jsonobj {
                    put("red", channelStats(h.red))
                    put("green", channelStats(h.green))
                    put("blue", channelStats(h.blue))
                    put("alpha", channelStats(h.alpha))
                    put("luma", channelStats(h.luma))
                })
            }
        }

        add("frame_otsu", "Otsu's optimal luma threshold for the active cel (single-level or multi-level DP); optionally binarizes the cel.", "v4-analysis",
            "session_id" to "string", "levels" to "integer", "binarize" to "boolean",
            required = listOf("session_id")) { params, store ->
            val levels = intParam(params, "levels", 2).coerceIn(2, 8)
            val binarize = strParam(params, "binarize", "false") == "true" || params.raw("binarize")?.let { it is com.pixellab.mcp.json.JsonBoolean && it.value } == true
            if (binarize && levels > 2) {
                throw IllegalArgumentException("'binarize' requires levels=2 (was $levels)")
            }
            if (binarize) {
                mutateCel(params, store, "frame_otsu") { frame ->
                    val threshold = OtsuThreshold.single(HistogramOps.compute(frame).luma)
                    HistogramOps.binarize(frame, threshold)
                }
            } else {
                val project = sessionOf(params, store).project
                val frame = requireActiveCel(project)
                val histogram = HistogramOps.compute(frame).luma
                val thresholds = if (levels == 2) {
                    intArrayOf(OtsuThreshold.single(histogram))
                } else {
                    OtsuThreshold.multi(histogram, levels)
                }
                jsonobj {
                    put("session_id", params.string("session_id"))
                    put("levels", num(levels))
                    put("thresholds", jsonarray { for (t in thresholds) add(num(t)) })
                    put("luma_stats", channelStats(histogram))
                }
            }
        }

        add("frame_equalize", "Hue-preserving Rec.709 luma histogram equalization of the active cel.", "v4-analysis",
            "session_id" to "string",
            required = listOf("session_id")) { params, store ->
            mutateCel(params, store, "frame_equalize") { HistogramOps.equalize(it) }
        }

        add("frame_autolevels", "Percentile contrast stretch (auto levels) of the active cel's luma.", "v4-analysis",
            "session_id" to "string", "low_pct" to "number", "high_pct" to "number",
            required = listOf("session_id")) { params, store ->
            val low = doubleParam(params, "low_pct", 0.01).coerceIn(0.0, 1.0)
            val high = doubleParam(params, "high_pct", 0.99).coerceIn(0.0, 1.0)
            mutateCel(params, store, "frame_autolevels") { HistogramOps.stretch(it, low, high) }
        }

        add("frame_convolve", "Convolves the active cel with a named kernel (box3/box5/gaussian3/gaussian5/sharpen/sharpen_mild/emboss/laplacian/sobel_x/sobel_y/prewitt_x/prewitt_y/identity) with edge and alpha modes.", "v4-analysis",
            "session_id" to "string", "kernel" to "string", "edge" to "string", "alpha" to "string",
            required = listOf("session_id", "kernel")) { params, store ->
            val kernel = kernelByName(strParam(params, "kernel", "box3"))
            val edge = when (strParam(params, "edge", "clamp")) {
                "clamp" -> ConvolutionEdgeMode.CLAMP
                "wrap" -> ConvolutionEdgeMode.WRAP
                "transparent" -> ConvolutionEdgeMode.TRANSPARENT
                else -> throw IllegalArgumentException("'edge' must be clamp, wrap or transparent")
            }
            val alpha = when (strParam(params, "alpha", "premultiplied")) {
                "premultiplied" -> ConvolutionAlphaMode.PREMULTIPLIED
                "straight" -> ConvolutionAlphaMode.STRAIGHT
                "alpha_only" -> ConvolutionAlphaMode.ALPHA_ONLY
                else -> throw IllegalArgumentException("'alpha' must be premultiplied, straight or alpha_only")
            }
            mutateCel(params, store, "frame_convolve(${kernelName(kernel)})") { ConvolutionOps.convolve(it, kernel, edge, alpha) }
        }

        add("frame_morphology", "Morphological cleanup of the active cel: dilate/erode/open/close/remove_isolated/fill_holes/outline/cleanup (square3/cross3/square5 elements).", "v4-analysis",
            "session_id" to "string", "op" to "string", "element" to "string",
            required = listOf("session_id", "op")) { params, store ->
            val element = when (strParam(params, "element", "square3")) {
                "square3" -> StructElement.square3()
                "cross3" -> StructElement.cross3()
                "square5" -> StructElement.square5()
                else -> throw IllegalArgumentException("'element' must be square3, cross3 or square5")
            }
            val op = strParam(params, "op", "")
            mutateCel(params, store, "frame_morphology($op)") { frame ->
                when (op) {
                    "dilate" -> MorphologyOps.dilate(frame, element)
                    "erode" -> MorphologyOps.erode(frame, element)
                    "open" -> MorphologyOps.open(frame, element)
                    "close" -> MorphologyOps.close(frame, element)
                    "remove_isolated" -> MorphologyOps.removeIsolatedPixels(frame)
                    "fill_holes" -> MorphologyOps.fillEnclosedHoles(frame)
                    "outline" -> MorphologyOps.outlineMask(frame, element)
                    "cleanup" -> MorphologyOps.cleanupSprite(frame)
                    else -> throw IllegalArgumentException(
                        "'op' must be dilate, erode, open, close, remove_isolated, fill_holes, outline or cleanup (was '$op')",
                    )
                }
            }
        }

        add("frame_components", "Connected-component analysis of the active cel (opaque or same-color mode, 4/8 connectivity): blob list with area, bounding box, perimeter, holes and border flags.", "v4-analysis",
            "session_id" to "string", "connectivity" to "string", "mode" to "string",
            required = listOf("session_id")) { params, store ->
            val project = sessionOf(params, store).project
            val frame = requireActiveCel(project)
            val conn = when (strParam(params, "connectivity", "four")) {
                "four" -> Connectivity.FOUR
                "eight" -> Connectivity.EIGHT
                else -> throw IllegalArgumentException("'connectivity' must be four or eight")
            }
            val colorMode = when (strParam(params, "mode", "opaque")) {
                "opaque" -> com.pixellab.core.analysis.ComponentColorMode.OPAQUE
                "same_color" -> com.pixellab.core.analysis.ComponentColorMode.SAME_COLOR
                else -> throw IllegalArgumentException("'mode' must be opaque or same_color")
            }
            val cc = ConnectedComponentOps.label(frame, conn, colorMode)
            jsonobj {
                put("session_id", params.string("session_id"))
                put("count", num(cc.count.toLong()))
                put("blobs", jsonarray {
                    for (blob in cc.blobs) {
                        add(jsonobj {
                            put("id", num(blob.id.toLong()))
                            put("area", num(blob.area.toLong()))
                            put("x", num(blob.minX.toLong()))
                            put("y", num(blob.minY.toLong()))
                            put("w", num(blob.width.toLong()))
                            put("h", num(blob.height.toLong()))
                            put("centroid_x", num(blob.centroidX))
                            put("centroid_y", num(blob.centroidY))
                            put("perimeter", num(blob.perimeter.toLong()))
                            put("holes", num(blob.holes.toLong()))
                            put("touches_border", blob.touchesBorder)
                        })
                    }
                })
            }
        }

        add("frame_metrics", "Read-only comparison of the session's active cel against another frame in the same project: MAE per channel, PSNR, changed-pixel ratio and changed bounds.", "v4-analysis",
            "session_id" to "string", "frame_index_a" to "integer", "frame_index_b" to "integer", "tolerance" to "integer",
            required = listOf("session_id", "frame_index_b")) { params, store ->
            val project = sessionOf(params, store).project
            val aIndex = intParam(params, "frame_index_a", project.activeFrameIndex)
            val bIndex = intParam(params, "frame_index_b", -1)
            require(aIndex in project.frames.indices) { "frame_index_a $aIndex out of bounds (${project.frameCount} frames)" }
            require(bIndex in project.frames.indices) { "frame_index_b $bIndex out of bounds (${project.frameCount} frames)" }
            val a = project.compositeFrame(aIndex)
            val b = project.compositeFrame(bIndex)
            val stats = ImageMetrics.compare(a, b)
            val bounds = ImageMetrics.changedBounds(a, b)
            jsonobj {
                put("session_id", params.string("session_id"))
                put("frame_a", num(aIndex.toLong()))
                put("frame_b", num(bIndex.toLong()))
                put("mae", num(stats.mae))
                put("mae_r", num(stats.maeR))
                put("mae_g", num(stats.maeG))
                put("mae_b", num(stats.maeB))
                put("psnr_db", num(ImageMetrics.psnr(a, b)))
                put("changed_pixels", num(stats.changedPixels))
                put("changed_ratio", num(stats.changedRatio))
                put("opacity_mismatches", num(stats.opacityMismatches))
                put("max_channel_delta", num(stats.maxChannelDelta.toLong()))
                put("changed_bounds", bounds?.let {
                    jsonarray { for (v in it) add(num(v.toLong())) }
                } ?: JsonNull)
            }
        }

        add("frame_audit", "Pixel-art craft audit of the active cel: dust pixels, broken corners, accidental checkerboards, enclosed holes, tiny clusters and 1-px zigzag chains, with a 0-100 score and actionable suggestions.", "v4-analysis",
            "session_id" to "string", "tiny_cluster_area" to "integer", "similar_tolerance" to "integer",
            required = listOf("session_id")) { params, store ->
            val project = sessionOf(params, store).project
            val frame = requireActiveCel(project)
            val config = AuditConfig(
                tinyClusterArea = intParam(params, "tiny_cluster_area", 2),
                similarColorTolerance = intParam(params, "similar_tolerance", 24),
            )
            val report = PixelAuditor.audit(frame, config)
            jsonobj {
                put("session_id", params.string("session_id"))
                put("score", num(report.score.toLong()))
                put("is_clean", report.isClean)
                put("findings", jsonarray {
                    for (f in report.findings) {
                        add(jsonobj {
                            put("rule", f.rule.name.lowercase())
                            put("x", num(f.x.toLong()))
                            put("y", num(f.y.toLong()))
                            put("severity", f.severity.name.lowercase())
                            put("message", JsonString(f.message))
                        })
                    }
                })
                put("stats", jsonobj {
                    put("opaque_pixels", num(report.stats.opaquePixels))
                    put("transparent_pixels", num(report.stats.transparentPixels))
                    put("distinct_colors", num(report.stats.distinctColors.toLong()))
                    put("components", num(report.stats.componentCount.toLong()))
                    put("largest_component", num(report.stats.largestComponent.toLong()))
                    put("holes", num(report.stats.holeCount.toLong()))
                })
                put("suggestions", jsonarray { for (s in report.suggestions) add(JsonString(s)) })
            }
        }

        add("frame_remove_small", "Despeckles the active cel by removing connected components below a minimum area.", "v4-analysis",
            "session_id" to "string", "min_area" to "integer", "connectivity" to "string",
            required = listOf("session_id", "min_area")) { params, store ->
            val minArea = intParam(params, "min_area", 2)
            val conn = when (strParam(params, "connectivity", "four")) {
                "four" -> Connectivity.FOUR
                "eight" -> Connectivity.EIGHT
                else -> throw IllegalArgumentException("'connectivity' must be four or eight")
            }
            mutateCel(params, store, "frame_remove_small($minArea)") {
                ConnectedComponentOps.removeSmall(it, minArea, conn)
            }
        }

        // ---- v4-transform: rotation & scaling -----------------------------

        add("frame_rotate", "Rotates the active cel by any angle using RotSprite (three-shear decomposition, pixel-art-safe). Quarter turns are lossless. 'crop' edits in place; 'expand' (default) grows the canvas, so the result lands in a fresh single-frame project (non-destructive).", "v4-transform",
            "session_id" to "string", "degrees" to "number", "bounds" to "string",
            required = listOf("session_id", "degrees")) { params, store ->
            val degrees = doubleParam(params, "degrees", 0.0)
            val bounds = when (strParam(params, "bounds", "expand")) {
                "expand" -> RotateBoundsMode.EXPAND
                "crop" -> RotateBoundsMode.CROP
                else -> throw IllegalArgumentException("'bounds' must be expand or crop")
            }
            if (bounds == RotateBoundsMode.CROP) {
                mutateCel(params, store, "frame_rotate(${"%.1f".format(degrees)}° crop)") {
                    RotSprite.rotate(it, degrees, bounds)
                }
            } else {
                val session = sessionOf(params, store)
                val cel = requireActiveCel(session.project)
                val rotated = RotSprite.rotate(cel, degrees, bounds)
                commitNewProject(params, store, "rotate-${"%.1f".format(degrees)}deg", rotated, paletteOf(session.project))
            }
        }

        add("frame_scale2x", "Upscales the active cel 2x with AdvMAME Scale2x (or the corner variant), or 3x with Scale3x — edge-aware, no color blending. The result lands in a fresh single-frame project.", "v4-transform",
            "session_id" to "string", "factor" to "integer", "variant" to "string",
            required = listOf("session_id")) { params, store ->
            val factor = intParam(params, "factor", 2)
            val variant = strParam(params, "variant", "plain")
            val session = sessionOf(params, store)
            val cel = requireActiveCel(session.project)
            val scaled = when (factor) {
                2 -> if (variant == "corners") Scale2x.scale2xCorners(cel) else Scale2x.scale2x(cel)
                3 -> Scale2x.scale3x(cel)
                else -> throw IllegalArgumentException("'factor' must be 2 or 3 (was $factor)")
            }
            commitNewProject(params, store, "scale${factor}x-$variant", scaled, paletteOf(session.project))
        }

        add("frame_epx", "Upscales the active cel 2x or 3x with EPX (corner-detection integer scaler); result in a fresh single-frame project.", "v4-transform",
            "session_id" to "string", "factor" to "integer",
            required = listOf("session_id")) { params, store ->
            val factor = intParam(params, "factor", 2)
            val session = sessionOf(params, store)
            val cel = requireActiveCel(session.project)
            val scaled = when (factor) {
                2 -> EpxScale.epx2x(cel)
                3 -> EpxScale.epx3x(cel)
                else -> throw IllegalArgumentException("'factor' must be 2 or 3 (was $factor)")
            }
            commitNewProject(params, store, "epx${factor}x", scaled, paletteOf(session.project))
        }

        add("frame_xbr", "Upscales the active cel 2x with xBR (level-1 rules, luminance-weighted edge direction); result in a fresh single-frame project.", "v4-transform",
            "session_id" to "string",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val cel = requireActiveCel(session.project)
            commitNewProject(params, store, "xbr2x", XbrScale.xbr2x(cel), paletteOf(session.project))
        }

        add("frame_resample", "Resamples the active cel to explicit dimensions with nearest-neighbor or premultiplied box filtering (half-pixel centers); result in a fresh single-frame project.", "v4-transform",
            "session_id" to "string", "width" to "integer", "height" to "integer", "mode" to "string",
            required = listOf("session_id", "width", "height")) { params, store ->
            val width = intParam(params, "width", 0)
            val height = intParam(params, "height", 0)
            require(width in 1..8192) { "'width' must be in [1, 8192] (was $width)" }
            require(height in 1..8192) { "'height' must be in [1, 8192] (was $height)" }
            val mode = strParam(params, "mode", "nearest")
            val session = sessionOf(params, store)
            val cel = requireActiveCel(session.project)
            val resampled = when (mode) {
                "nearest" -> Resample.nearest(cel, width, height)
                "box" -> Resample.boxResample(cel, width, height)
                else -> throw IllegalArgumentException("'mode' must be nearest or box (was '$mode')")
            }
            commitNewProject(params, store, "resample-$mode-${width}x${height}", resampled, paletteOf(session.project))
        }

        add("frame_mipmap", "Read-only mipmap chain of the active cel (halving until 1px or max_levels): each level's dimensions and a base64 PNG for direct download. Projects keep one frame size, so levels are returned as artifacts instead of frames.", "v4-transform",
            "session_id" to "string", "max_levels" to "integer",
            required = listOf("session_id")) { params, store ->
            val session = sessionOf(params, store)
            val cel = requireActiveCel(session.project)
            val chain = Resample.mipmapChain(cel, intParam(params, "max_levels", 9))
            jsonobj {
                put("session_id", session.id)
                put("levels", num(chain.size.toLong()))
                put("mipmaps", jsonarray {
                    for (level in chain) {
                        add(jsonobj {
                            put("width", num(level.width.toLong()))
                            put("height", num(level.height.toLong()))
                            val png = com.pixellab.core.export.PngCodec.encode(level)
                            put("byte_count", num(png.size.toLong()))
                            put("data_b64", JsonString(Base64.getEncoder().encodeToString(png)))
                        })
                    }
                })
            }
        }

        // ---- v4-worldgen: procedural worlds --------------------------------

        add("gen_worley", "Renders Worley/cellular noise (F1/F2/border/cell features, adjustable contrast and cell size) into a new single-frame session project.", "v4-worldgen",
            "session_id" to "string", "width" to "integer", "height" to "integer",
            "seed" to "integer", "feature" to "string", "cell_size" to "number", "contrast" to "number",
            required = listOf("session_id", "width", "height")) { params, store ->
            val width = intParam(params, "width", 64)
            val height = intParam(params, "height", 64)
            require(width in 1..2048 && height in 1..2048) { "dimensions must be in [1, 2048]" }
            val feature = when (strParam(params, "feature", "nearest")) {
                "nearest", "f1" -> WorleyNoise.Feature.NEAREST
                "second", "f2" -> WorleyNoise.Feature.SECOND
                "border" -> WorleyNoise.Feature.BORDER
                "cell" -> WorleyNoise.Feature.CELL
                else -> throw IllegalArgumentException("'feature' must be nearest, second, border or cell")
            }
            val frame = WorleyNoise(seedParam(params)).render(
                width, height,
                cellSize = doubleParam(params, "cell_size", 8.0),
                feature = feature,
                contrast = doubleParam(params, "contrast", 1.0),
            )
            commitNewProject(params, store, "worley", frame, paletteOf(sessionOf(params, store).project))
        }

        add("gen_dungeon", "Generates a BSP dungeon (rooms + L corridors + doors) as a new single-frame session project rendered with the default tile colors.", "v4-worldgen",
            "session_id" to "string", "width" to "integer", "height" to "integer",
            "seed" to "integer", "min_leaf" to "integer",
            required = listOf("session_id", "width", "height")) { params, store ->
            val width = intParam(params, "width", 64)
            val height = intParam(params, "height", 48)
            require(width in 24..512 && height in 24..512) { "dungeon size must be in [24, 512]" }
            val map = DungeonGen(SeededRng(seedParam(params))).generate(
                width, height,
                minLeaf = intParam(params, "min_leaf", 12),
            )
            val frame = map.toFrame()
            commitNewProject(params, store, "dungeon", frame, paletteOf(sessionOf(params, store).project))
        }

        add("gen_cave", "Generates a cellular-automata cave (4-5 smoothing, optional drunkard walk) as a new single-frame session project.", "v4-worldgen",
            "session_id" to "string", "width" to "integer", "height" to "integer",
            "seed" to "integer", "fill_chance" to "number", "smooth_passes" to "integer", "walk_tiles" to "integer",
            required = listOf("session_id", "width", "height")) { params, store ->
            val width = intParam(params, "width", 64)
            val height = intParam(params, "height", 48)
            require(width in 16..512 && height in 16..512) { "cave size must be in [16, 512]" }
            val map = CaveGen(SeededRng(seedParam(params))).generate(
                width, height,
                fillChance = doubleParam(params, "fill_chance", 0.46).toFloat(),
                smoothPasses = intParam(params, "smooth_passes", 4),
                walkTiles = intParam(params, "walk_tiles", 0),
            )
            commitNewProject(params, store, "cave", map.toFrame(), paletteOf(sessionOf(params, store).project))
        }

        add("gen_biome", "Paints a biome world map from seeded value noise (overworld/volcanic/frozen themes) into a new single-frame session project.", "v4-worldgen",
            "session_id" to "string", "width" to "integer", "height" to "integer",
            "seed" to "integer", "theme" to "string", "height_scale" to "number", "moisture_scale" to "number",
            required = listOf("session_id", "width", "height")) { params, store ->
            val width = intParam(params, "width", 96)
            val height = intParam(params, "height", 72)
            require(width in 4..2048 && height in 4..2048) { "dimensions must be in [4, 2048]" }
            val theme = when (strParam(params, "theme", "overworld")) {
                "overworld" -> BiomePainter.Theme.OVERWORLD
                "volcanic" -> BiomePainter.Theme.VOLCANIC
                "frozen" -> BiomePainter.Theme.FROZEN
                else -> throw IllegalArgumentException("'theme' must be overworld, volcanic or frozen")
            }
            val frame = BiomePainter.paintFromNoise(
                width, height,
                seed = seedParam(params),
                theme = theme,
                heightScale = doubleParam(params, "height_scale", 0.02),
                moistureScale = doubleParam(params, "moisture_scale", 0.035),
            )
            commitNewProject(params, store, "biome", frame, paletteOf(sessionOf(params, store).project))
        }

        add("gen_lsystem", "Renders an L-system plant (bush/binary_tree/koch/fern presets or custom axiom+rules) into a new single-frame session project.", "v4-worldgen",
            "session_id" to "string", "preset" to "string", "axiom" to "string", "rule" to "string",
            "iterations" to "integer", "step" to "number", "angle" to "number", "seed" to "integer",
            required = listOf("session_id")) { params, store ->
            val preset = strParam(params, "preset", "")
            val ls = when {
                preset.isNotEmpty() && preset != "custom" -> when (preset) {
                    "bush" -> LSystem.bush()
                    "binary_tree", "binarytree", "tree" -> LSystem.binaryTree()
                    "koch" -> LSystem.koch()
                    "fern" -> LSystem.fern()
                    else -> throw IllegalArgumentException("'preset' must be bush, binary_tree, koch, fern or custom")
                }
                else -> {
                    val axiom = params.string("axiom").ifBlank { "F" }
                    val ruleText = params.string("rule").ifBlank { "F=FF-[-F+F]+[+F-F]" }
                    val rules = HashMap<Char, String>()
                    for (part in ruleText.split(',')) {
                        val eq = part.indexOf('=')
                        require(eq == 1) { "'rule' entries must look like F=... (was '$part')" }
                        rules[part[0]] = part.substring(2)
                    }
                    LSystem.ofDeterministic(axiom, rules)
                }
            }
            val frame = ls.render(
                step = doubleParam(params, "step", 3.0),
                angleDeg = doubleParam(params, "angle", 25.0),
                iterations = intParam(params, "iterations", 4),
                rng = if (preset == "bush" || params.raw("seed") != null) SeededRng(seedParam(params)) else null,
            )
            commitNewProject(params, store, "lsystem-$preset", frame, paletteOf(sessionOf(params, store).project))
        }

        add("sim_particles", "Simulates a deterministic particle effect (fire/rain/starfield/explosion/smoke presets) for a duration and renders the last frame into a new single-frame session project.", "v4-worldgen",
            "session_id" to "string", "preset" to "string", "seconds" to "number", "seed" to "integer",
            "width" to "integer", "height" to "integer", "rate" to "number", "soft" to "boolean",
            required = listOf("session_id", "preset")) { params, store ->
            val preset = strParam(params, "preset", "")
            val session = sessionOf(params, store)
            val width = intParam(params, "width", 64)
            val height = intParam(params, "height", 64)
            val originX = width / 2.0
            val originY = height / 2.0
            val base = when (preset) {
                "fire" -> EmitterConfigView.fire(originX, originY)
                "rain" -> EmitterConfigView.rain(0.0, 0.0)
                "starfield" -> EmitterConfigView.starfield(originX, originY)
                "explosion" -> EmitterConfigView.explosion(originX, originY)
                "smoke" -> EmitterConfigView.smoke(originX, originY)
                else -> throw IllegalArgumentException("'preset' must be fire, rain, starfield, explosion or smoke")
            }
            val rate = params.raw("rate")
            val config = if (rate is JsonNumber) withRate(base, rate.value) else base
            val system = ParticleSystem(config, seed = seedParam(params), capacity = 512)
            val seconds = doubleParam(params, "seconds", 1.0).coerceIn(0.0, 30.0)
            val soft = strParam(params, "soft", "false") == "true" || params.raw("soft")?.let { it is com.pixellab.mcp.json.JsonBoolean && it.value } == true
            // Fixed 60 Hz steps for determinism.
            val stepCount = (seconds * 60.0).toInt()
            repeat(stepCount) { system.step(1.0 / 60.0) }
            val frame = system.render(width, height, soft)
            jsonobj {
                val created = commitNewProject(params, store, "particles-$preset", frame, paletteOf(session.project))
                for ((k, v) in created.entries) put(k, v)
                put("preset", JsonString(preset))
                put("simulated_seconds", num(seconds))
                put("live_particles", num(system.liveParticles.toLong()))
                put("total_spawned", num(system.totalSpawned))
            }
        }

        // ---- v4-color: accessibility & science ------------------------------

        add("color_simulate", "Simulates color-vision deficiency (protan/deutan/tritan/achromat, Machado model with severity, or Brettel) on the active cel.", "v4-color",
            "session_id" to "string", "deficiency" to "string", "severity" to "number", "model" to "string",
            required = listOf("session_id", "deficiency")) { params, store ->
            val deficiency = when (val d = strParam(params, "deficiency", "")) {
                "protan", "protanopia" -> ColorVision.Deficiency.PROTAN
                "deutan", "deuteranopia" -> ColorVision.Deficiency.DEUTAN
                "tritan", "tritanopia" -> ColorVision.Deficiency.TRITAN
                "achromat", "achromatopsia", "mono" -> ColorVision.Deficiency.ACHROMAT
                else -> throw IllegalArgumentException("'deficiency' must be protan, deutan, tritan or achromat (was '$d')")
            }
            val severity = doubleParam(params, "severity", 1.0).coerceIn(0.0, 1.0)
            val model = strParam(params, "model", "machado")
            mutateCel(params, store, "color_simulate(${deficiency.name.lowercase()})") { frame ->
                if (model == "brettel") {
                    frame.map { ColorVision.simulateBrettel(it, deficiency) }
                } else {
                    ColorVision.simulate(frame, deficiency, severity)
                }
            }
        }

        add("color_contrast_audit", "Read-only WCAG contrast audit of the session palette: every pair's ratio, worst confusable pair and failing set for AA/AAA.", "v4-color",
            "session_id" to "string", "level" to "string",
            required = listOf("session_id")) { params, store ->
            val project = sessionOf(params, store).project
            val level = when (strParam(params, "level", "aa")) {
                "aa" -> ContrastAudit.Level.AA
                "aaa" -> ContrastAudit.Level.AAA
                else -> throw IllegalArgumentException("'level' must be aa or aaa")
            }
            val colors = project.palette.colors
            val report = ContrastAudit.auditPalette(colors, level)
            jsonobj {
                put("session_id", params.string("session_id"))
                put("palette_id", JsonString(project.palette.id))
                put("palette_size", num(colors.size.toLong()))
                put("level", JsonString(level.name))
                put("all_pass", report.allPass)
                put("worst_ratio", report.worstPair?.let { num(it.ratio) } ?: JsonNull)
                put("failing_count", num(report.failing.size.toLong()))
                put("worst_pairs", jsonarray {
                    for (pair in report.pairs.take(10)) {
                        add(jsonobj {
                            put("color_a", JsonString(hexArgb(pair.colorA)))
                            put("color_b", JsonString(hexArgb(pair.colorB)))
                            put("ratio", num(pair.ratio))
                            put("index_a", num(pair.indexA.toLong()))
                            put("index_b", num(pair.indexB.toLong()))
                        })
                    }
                })
            }
        }

        add("color_contrast_suggest", "Suggests an accessible replacement for one color against another (binary-searched luminance shift preserving hue) at AA/AAA normal or large-text targets.", "v4-color",
            "color" to "string", "against" to "string", "level" to "string", "large" to "boolean",
            required = listOf("color", "against")) { params, _ ->
            val color = colorParam(params, "color")
            val against = colorParam(params, "against")
            val level = when (strParam(params, "level", "aa")) {
                "aa" -> ContrastAudit.Level.AA
                "aaa" -> ContrastAudit.Level.AAA
                else -> throw IllegalArgumentException("'level' must be aa or aaa")
            }
            val large = strParam(params, "large", "false") == "true" || params.raw("large")?.let { it is com.pixellab.mcp.json.JsonBoolean && it.value } == true
            val target = if (large) level.largeTextRatio else level.normalTextRatio
            val current = ContrastAudit.contrastRatio(color, against)
            val suggestion = ContrastAudit.suggestAccessible(color, against, target)
            jsonobj {
                put("color", JsonString(hexArgb(color)))
                put("against", JsonString(hexArgb(against)))
                put("current_ratio", num(current))
                put("target_ratio", num(target))
                put("suggestion", suggestion?.let { JsonString(hexArgb(it)) } ?: JsonNull)
                put("suggestion_ratio", suggestion?.let { num(ContrastAudit.contrastRatio(it, against)) } ?: JsonNull)
                put("feasible", suggestion != null)
            }
        }

        add("color_name", "Names colors: exact CSS matches plus nearest-CSS-color naming with perceptual distance; accepts one color or the whole session palette.", "v4-color",
            "color" to "string", "session_id" to "string",
            required = listOf()) { params, store ->
            val single = params.raw("color")
            if (single != null) {
                val argb = colorParam(params, "color")
                val exact = ColorNamer.exactName(argb)
                val nearest = ColorNamer.nearestName(argb)
                jsonobj {
                    put("color", JsonString(hexArgb(argb)))
                    put("exact_names", jsonarray { for (n in exact) add(JsonString(n)) })
                    put("nearest", JsonString(nearest.name))
                    put("nearest_color", JsonString(hexArgb(nearest.argb)))
                    put("delta_e", num(nearest.distance))
                }
            } else {
                val project = sessionOf(params, store).project
                val names = ColorNamer.nameFrameColors(project.compositeFrame(project.activeFrameIndex))
                jsonobj {
                    put("session_id", params.string("session_id"))
                    put("colors", jsonarray {
                        for (entry in names.take(64)) {
                            add(jsonobj {
                                put("color", JsonString(hexArgb(entry.argb)))
                                put("name", JsonString(entry.name))
                                put("delta_e", num(entry.deltaE))
                                put("pixel_count", num(entry.pixelCount.toLong()))
                            })
                        }
                    })
                    put("distinct_colors", num(names.size.toLong()))
                }
            }
        }

        add("color_temperature", "Applies a Kelvin color temperature (1000–40000K, strength-blended) to the active cel, or converts a single Kelvin value to its RGB color.", "v4-color",
            "session_id" to "string", "kelvin" to "number", "strength" to "number",
            required = listOf("kelvin")) { params, store ->
            val kelvin = doubleParam(params, "kelvin", 6500.0)
            if (params.raw("session_id") == null) {
                jsonobj {
                    put("kelvin", num(kelvin))
                    put("color", JsonString(hexArgb(ColorTemperature.kelvinToRgb(kelvin))))
                }
            } else {
                val strength = doubleParam(params, "strength", 1.0).coerceIn(0.0, 1.0)
                mutateCel(params, store, "color_temperature(${kelvin.toInt()}K)") {
                    ColorTemperature.applyTemperature(it, kelvin, strength)
                }
            }
        }

        add("color_white_balance", "Gray-world automatic white balance of the active cel (strength-blended).", "v4-color",
            "session_id" to "string", "strength" to "number",
            required = listOf("session_id")) { params, store ->
            val strength = doubleParam(params, "strength", 1.0).coerceIn(0.0, 1.0)
            mutateCel(params, store, "color_white_balance") {
                ColorTemperature.autoWhiteBalance(it, strength)
            }
        }

        add("color_comparison_strip", "Renders the active cel plus its four deficiency simulations side by side (one-glance accessibility review) into a fresh single-frame project.", "v4-color",
            "session_id" to "string", "severity" to "number", "cell_width" to "integer", "cell_height" to "integer",
            required = listOf("session_id")) { params, store ->
            val severity = doubleParam(params, "severity", 1.0).coerceIn(0.0, 1.0)
            val session = sessionOf(params, store)
            val cel = requireActiveCel(session.project)
            val strip = ColorVision.comparisonStrip(
                cel, severity,
                intParam(params, "cell_width", 24).coerceIn(4, 256),
                intParam(params, "cell_height", 24).coerceIn(4, 256),
            )
            commitNewProject(params, store, "cvd-strip", strip, paletteOf(session.project))
        }

        // ---- v4-vector: contours & SVG -------------------------------------

        add("frame_contours", "Read-only marching-squares contour tracing of the active cel (opacity mask or a specific color with tolerance): closed corner-lattice rings, holes flagged.", "v4-vector",
            "session_id" to "string", "color" to "string", "tolerance" to "integer", "simplify" to "boolean",
            required = listOf("session_id")) { params, store ->
            val project = sessionOf(params, store).project
            val frame = requireActiveCel(project)
            val tolerance = intParam(params, "tolerance", 0).coerceIn(0, 255)
            val simplify = strParam(params, "simplify", "true") != "false"
            val contours = if (params.raw("color") != null) {
                MarchingSquares.traceColor(frame, colorParam(params, "color"), tolerance, simplify)
            } else {
                MarchingSquares.traceOpacity(frame, simplify)
            }
            jsonobj {
                put("session_id", params.string("session_id"))
                put("count", num(contours.size.toLong()))
                put("contours", jsonarray {
                    for (c in contours.take(64)) {
                        add(jsonobj {
                            put("is_hole", c.isHole)
                            put("points", num(c.size.toLong()))
                            put("path", JsonString(pointsToPath(c)))
                        })
                    }
                })
            }
        }

        add("export_svg", "Exports the active cel as an SVG document (runs mode: one path per color of merged horizontal rectangles; outline mode: marching-squares polygons).", "v4-vector",
            "session_id" to "string", "mode" to "string", "title" to "string",
            required = listOf("session_id")) { params, store ->
            val project = sessionOf(params, store).project
            val frame = requireActiveCel(project)
            val mode = when (strParam(params, "mode", "runs")) {
                "runs" -> SvgExporter.ShapeMode.RUNS
                "outline" -> SvgExporter.ShapeMode.OUTLINE
                else -> throw IllegalArgumentException("'mode' must be runs or outline")
            }
            val svg = SvgExporter.export(
                frame,
                SvgExporter.Options(mode = mode, title = params.opt("title", "")),
            )
            jsonobj {
                put("session_id", params.string("session_id"))
                put("format", "svg")
                put("mode", JsonString(mode.name.lowercase()))
                put("width", num(frame.width.toLong()))
                put("height", num(frame.height.toLong()))
                put("byte_count", num(svg.toByteArray().size.toLong()))
                put("data_b64", JsonString(Base64.getEncoder().encodeToString(svg.toByteArray())))
            }
        }

        add("export_svg_animated", "Exports the whole session project as a SMIL-animated SVG (one <g> per frame, discrete opacity drivers — plays in any plain SVG renderer, no scripts).", "v4-vector",
            "session_id" to "string", "frame_duration_ms" to "integer", "loop" to "boolean", "title" to "string",
            required = listOf("session_id")) { params, store ->
            val project = sessionOf(params, store).project
            val duration = intParam(params, "frame_duration_ms", 120).coerceIn(10, 10_000)
            val loop = strParam(params, "loop", "true") != "false"
            val svg = SvgExporter.exportAnimated(
                project,
                frameDurationMs = duration,
                loop = loop,
                options = SvgExporter.Options(title = params.opt("title", "")),
            )
            jsonobj {
                put("session_id", params.string("session_id"))
                put("format", "svg")
                put("animated", true)
                put("frames", num(project.frameCount.toLong()))
                put("frame_duration_ms", num(duration.toLong()))
                put("loop", loop)
                put("byte_count", num(svg.toByteArray().size.toLong()))
                put("data_b64", JsonString(Base64.getEncoder().encodeToString(svg.toByteArray())))
            }
        }

        return tools.toList() to schemas
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun channelStats(h: ChannelHistogram): JsonObject = jsonobj {
        put("total", num(h.total))
        put("distinct", num(h.distinctValues.toLong()))
        put("mean", num(h.mean))
        put("stdev", num(h.standardDeviation))
        put("entropy_bits", num(h.entropy))
        put("mode", num(h.mode.toLong()))
        put("p5", num(h.percentile(0.05).toLong()))
        put("p50", num(h.percentile(0.5).toLong()))
        put("p95", num(h.percentile(0.95).toLong()))
    }

    private fun kernelByName(name: String): ConvolutionKernel = when (name) {
        "identity" -> ConvolutionKernel.IDENTITY
        "box3" -> ConvolutionKernel.BOX_BLUR_3
        "box5" -> ConvolutionKernel.BOX_BLUR_5
        "gaussian3" -> ConvolutionKernel.GAUSSIAN_3
        "gaussian5" -> ConvolutionKernel.GAUSSIAN_5
        "sharpen" -> ConvolutionKernel.SHARPEN
        "sharpen_mild" -> ConvolutionKernel.SHARPEN_MILD
        "emboss" -> ConvolutionKernel.EMBOSS
        "laplacian" -> ConvolutionKernel.LAPLACIAN
        "sobel_x" -> ConvolutionKernel.SOBEL_X
        "sobel_y" -> ConvolutionKernel.SOBEL_Y
        "prewitt_x" -> ConvolutionKernel.PREWITT_X
        "prewitt_y" -> ConvolutionKernel.PREWITT_Y
        else -> throw IllegalArgumentException(
            "'kernel' must be identity, box3, box5, gaussian3, gaussian5, sharpen, sharpen_mild, emboss, laplacian, sobel_x, sobel_y, prewitt_x or prewitt_y (was '$name')",
        )
    }

    private fun kernelName(kernel: ConvolutionKernel): String = when (kernel) {
        ConvolutionKernel.IDENTITY -> "identity"
        ConvolutionKernel.BOX_BLUR_3 -> "box3"
        ConvolutionKernel.BOX_BLUR_5 -> "box5"
        ConvolutionKernel.GAUSSIAN_3 -> "gaussian3"
        ConvolutionKernel.GAUSSIAN_5 -> "gaussian5"
        ConvolutionKernel.SHARPEN -> "sharpen"
        ConvolutionKernel.SHARPEN_MILD -> "sharpen_mild"
        ConvolutionKernel.EMBOSS -> "emboss"
        ConvolutionKernel.LAPLACIAN -> "laplacian"
        ConvolutionKernel.SOBEL_X -> "sobel_x"
        ConvolutionKernel.SOBEL_Y -> "sobel_y"
        ConvolutionKernel.PREWITT_X -> "prewitt_x"
        ConvolutionKernel.PREWITT_Y -> "prewitt_y"
        else -> "custom"
    }

    /** Renders a contour as a compact `Mx yLx y…Z` path string. */
    private fun pointsToPath(c: MarchingSquares.Contour): String {
        val sb = StringBuilder(c.size * 8)
        for (i in 0 until c.size) {
            if (i == 0) sb.append('M').append(c.x[i]).append(' ').append(c.y[i])
            else sb.append('L').append(c.x[i]).append(' ').append(c.y[i])
        }
        sb.append('Z')
        return sb.toString()
    }

    private fun withRate(base: EmitterConfigView, rate: Double): EmitterConfigView = EmitterConfigView(
        rate = rate,
        angleDeg = base.angleDeg, spreadDeg = base.spreadDeg,
        speed = base.speed, speedJitter = base.speedJitter,
        lifetime = base.lifetime, lifetimeJitter = base.lifetimeJitter,
        originX = base.originX, originY = base.originY, originJitter = base.originJitter,
        gravity = base.gravity, turbulence = base.turbulence,
        turbulenceSpeed = base.turbulenceSpeed, drag = base.drag,
        colorRamp = base.colorRamp,
    )
}
