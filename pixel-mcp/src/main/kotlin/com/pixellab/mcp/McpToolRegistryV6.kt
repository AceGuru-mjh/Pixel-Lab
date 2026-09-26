package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.core.batch.BatchOp
import com.pixellab.core.batch.DrawingBatch
import com.pixellab.core.describe.FrameDiff
import com.pixellab.core.describe.FrameFingerprintComputer
import com.pixellab.core.engine.CheckpointTracker
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.project.ProjectCodec
import com.pixellab.core.store.ProjectStore
import com.pixellab.core.store.SlotStore
import com.pixellab.mcp.json.JsonArray
import com.pixellab.mcp.json.JsonBoolean
import com.pixellab.mcp.json.JsonNull
import com.pixellab.mcp.json.JsonNumber
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonarray
import com.pixellab.mcp.json.jsonobj
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/**
 * Persistence handle the host wires into the server when session
 * durability is wanted: a [ProjectStore] for the pixels plus the
 * [SlotStore] name layer on top.
 *
 * The MCP server constructs both from one root directory; tiers that need
 * them receive this record. A null persistence (the default) keeps the
 * tools honest: every persistence tool answers with an actionable
 * configuration error instead of silently no-oping.
 */
data class McpPersistence(
    /** Project pixel storage (owns the `projects/` layout). */
    val projects: ProjectStore,
    /** Named-slot documents (owns the `slots/` layout). */
    val slots: SlotStore,
)

/**
 * Sixth-tier MCP tool registry for Pixel Lab: **agent ergonomics** — the
 * twelve tools that make long pixel-art sessions over a transport
 * practical.
 *
 * What was still missing after tier 5 (vision):
 *
 *  * **Latency** — one network round trip per primitive. A 20-op sketch
 *    means 20 calls, 20 chances for a mid-sequence parameter error.
 *    [draw_batch] validates *all* ops up front ([DrawingBatch]), then
 *    applies them in one call, returning per-op outcomes plus an
 *    aggregate [FrameDiff] change summary and before/after checksums.
 *    `dry_run: true` validates without mutating.
 *  * **Durability** — sessions died with the process. [session_save] /
 *    [session_load] / [session_saved_list] / [session_saved_delete] put a
 *    named-slot layer ([SlotStore]) over [ProjectStore] persistence.
 *  * **Recoverable experiments** — undo-by-N is guesswork.
 *    [checkpoint_set] / [checkpoint_list] / [checkpoint_rollback] /
 *    [checkpoint_delete] mark known-good depths of the engine history
 *    ([CheckpointTracker]) and roll back to them in one call.
 *  * **Cheap verification** — [canvas_checksum] is a constant-size
 *    frame fingerprint; [project_export_json] closes the v3 import/export
 *    asymmetry by emitting the round-trippable wire document.
 *
 * Every mutating handler writes the resulting project back through the
 * session store (the tier 1–5 discipline). Handlers validate parameters
 * eagerly; execution is serialized through one mutex like the other tiers.
 *
 * ## Hosting
 *
 * Standalone dispatcher chained into [McpToolRouter] as tier 6; unknown
 * tool names throw [McpToolException] with `METHOD_NOT_FOUND` so the
 * router keeps walking.
 *
 * @param lab shared engine facade (engine history powers checkpoints).
 * @param persistence optional disk persistence; null keeps the server
 *   purely in-memory and makes the four persistence tools fail with a
 *   configuration hint.
 */
class McpToolRegistryV6(
    private val lab: PixelLab = PixelLab.create(),
    private val persistence: McpPersistence? = null,
) {

    private val mutex = Mutex()
    private val checkpoints = CheckpointTracker()

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
            ?: throw McpToolException("unknown v6 tool '$name'", JsonRpc.METHOD_NOT_FOUND)
        return try {
            mutex.withLock { tool.handler(args, store) }
        } catch (error: IllegalArgumentException) {
            throw McpToolException(error.message ?: "invalid parameters for tool '$name'")
        }
    }

    /** True when [name] is one of this registry's tools. */
    operator fun contains(name: String): Boolean = tools.any { it.name == name }

    /** Drops tier-local per-session checkpoint state for [sessionId]. */
    fun clearSessionState(sessionId: String) {
        checkpoints.clearFor(sessionId)
    }

    // ---- shared helpers ------------------------------------------------------

    private fun num(value: Int): JsonNumber = JsonNumber(value.toDouble(), value.toString())
    private fun num(value: Long): JsonNumber = JsonNumber(value.toDouble(), value.toString())

    private fun sessionOf(params: JsonObject, store: PixelSessionStore): PixelSessionStore.SessionState {
        val id = params.string("session_id")
        return requireNotNull(store.get(id, create = true)) { "session '$id' unavailable" }
    }

    /** Optional nullable int parameter (absent or JSON null → null). */
    private fun optionalInt(params: JsonObject, key: String): Int? {
        val raw = params.raw(key) ?: return null
        if (raw is JsonNull) return null
        return params.int(key)
    }

    /** Optional nullable string parameter (absent or JSON null → null). */
    private fun optionalString(params: JsonObject, key: String): String? {
        val raw = params.raw(key) ?: return null
        if (raw is JsonNull) return null
        return (raw as? JsonString)?.value
            ?: throw IllegalArgumentException("parameter '$key' must be a string")
    }

    /** Optional boolean parameter (absent → [default]). */
    private fun optionalBool(params: JsonObject, key: String, default: Boolean): Boolean {
        val raw = params.raw(key) ?: return default
        if (raw is JsonNull) return default
        return (raw as? JsonBoolean)?.value
            ?: throw IllegalArgumentException("parameter '$key' must be a boolean")
    }

    /** Composited frame for reading: `frame_index` when present (validated),
     *  else the session's active frame. */
    private fun readFrame(params: JsonObject, project: SpriteProject): com.pixellab.core.model.PixelFrame {
        val index = optionalInt(params, "frame_index") ?: project.activeFrameIndex
        if (index < 0 || index >= project.frameCount) {
            throw IllegalArgumentException(
                "frame_index $index outside 0..${project.frameCount - 1} (project has ${project.frameCount} frames)",
            )
        }
        return project.compositeFrame(index)
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
            else -> throw IllegalArgumentException("parameter '$key' must be a color string or ARGB integer")
        }
    }

    private fun colorParam(op: JsonObject, key: String, index: Int): Int {
        return try {
            colorParam(op, key)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("op[$index]: ${error.message}")
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

    private fun hexArgb(argb: Int): String = "#" + "%08x".format(argb)

    /** Project summary envelope shared by mutating tools. */
    private fun projectSummary(sessionId: String, project: SpriteProject): JsonObject = jsonobj {
        put("session_id", sessionId)
        put("project_id", project.id)
        put("name", project.name)
        put("width", project.width)
        put("height", project.height)
        put("frames", project.frameCount)
        put("layers", project.layerCount)
        put("active_frame_index", project.activeFrameIndex)
        put("active_layer_id", project.activeLayerId)
        put("palette_id", project.palette.id)
    }

    /** Frame-diff change summary as a compact JSON object. */
    private fun changeEnvelope(diff: com.pixellab.core.describe.FrameDiffResult): JsonObject = jsonobj {
        put("added", diff.added)
        put("removed", diff.removed)
        put("changed", diff.changed)
        put("total_changes", diff.totalChanges)
        diff.addedBox?.let { put("added_box", boxEnvelope(it)) }
        diff.removedBox?.let { put("removed_box", boxEnvelope(it)) }
        diff.changedBox?.let { put("changed_box", boxEnvelope(it)) }
    }

    private fun boxEnvelope(box: com.pixellab.core.describe.ChangeBox): JsonObject = jsonobj {
        put("x", box.x)
        put("y", box.y)
        put("w", box.w)
        put("h", box.h)
        put("count", box.count)
    }

    private fun requirePersistence(): McpPersistence {
        return persistence ?: throw McpToolException(
            "session persistence is not configured on this server " +
                "(the host must start PixelMcpServer with a persistenceRoot directory)",
        )
    }

    // ---- registry ------------------------------------------------------------

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

        // ---- v6-batch: one call, many primitives ---------------------------

        add(
            "draw_batch",
            "Executes a list of drawing operations in ONE call: ops is an array of {op: 'pixel'|'line'|'rect'|'circle'|'stroke'|'fill'|'replace'|'erase', ...primitive params}. All ops are validated BEFORE any is applied, so a bad op never leaves a half-drawn batch. Returns per-op applied/noop outcomes, an aggregate change summary (added/removed/changed pixels + bounding boxes) and before/after checksums. dry_run=true validates and reports without mutating. Use this instead of many draw_* calls — one round trip, atomic validation.",
            "v6-batch",
            "session_id" to "string", "ops" to "array", "dry_run" to "boolean",
            "frame_index" to "integer",
            required = listOf("session_id", "ops"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val opsArray = params.array("ops")
            val dryRun = optionalBool(params, "dry_run", false)
            if (opsArray.size < 1) {
                throw IllegalArgumentException("ops must contain at least one operation")
            }
            if (opsArray.size > DrawingBatch.MAX_OPS) {
                throw IllegalArgumentException(
                    "ops has ${opsArray.size} entries (max ${DrawingBatch.MAX_OPS}); split into several draw_batch calls",
                )
            }
            val ops = ArrayList<BatchOp>(opsArray.size)
            for ((index, element) in opsArray.items.withIndex()) {
                val op = element as? JsonObject
                    ?: throw IllegalArgumentException("op[$index] must be an object")
                ops.add(parseBatchOp(op, index))
            }
            val project = session.project
            val beforeComposite = readFrame(params, project)
            val beforeFingerprint = FrameFingerprintComputer.of(beforeComposite)

            val outcomes: List<com.pixellab.core.batch.OpOutcome>
            var finalProject = project
            if (dryRun) {
                DrawingBatch.validate(ops, project)
                outcomes = ops.mapIndexed { index, op ->
                    com.pixellab.core.batch.OpOutcome(index, op, false, DrawingBatch.labelOf(op))
                }
            } else {
                val result = DrawingBatch.validateAndApply(ops, project, lab.engine)
                outcomes = result.outcomes
                finalProject = result.project
                store.update(session.id, finalProject)
            }

            val afterComposite = if (dryRun) beforeComposite else readFrame(params, finalProject)
            val diff = FrameDiff.diff(beforeComposite, afterComposite)
            val afterFingerprint = FrameFingerprintComputer.of(afterComposite)

            jsonobj {
                put("session_id", session.id)
                put("project_id", finalProject.id)
                put("dry_run", dryRun)
                put("ops_total", outcomes.size)
                put("ops_applied", outcomes.count { it.applied })
                put("ops_noop", outcomes.count { !it.applied })
                put(
                    "outcomes",
                    jsonarray {
                        for (outcome in outcomes) {
                            add(jsonobj {
                                put("index", outcome.index)
                                put("op", kindOf(outcome.op))
                                put("applied", outcome.applied)
                            })
                        }
                    },
                )
                put("changes", changeEnvelope(diff))
                put(
                    "checksum_before", beforeFingerprint.digest,
                )
                put("checksum_after", afterFingerprint.digest)
                put("history_entries_added", if (dryRun) 0 else outcomes.count { it.applied })
            }
        }

        // ---- v6-verify: cheap change verification --------------------------

        add(
            "canvas_checksum",
            "Returns a constant-size fingerprint (FNV-1a 64 hex) of the composited frame plus visible-pixel count. Compare digests before/after any operation to verify a change landed — far cheaper than canvas_read when you only need 'did it change?'. Sensitive to a single pixel; stable across restarts.",
            "v6-verify",
            "session_id" to "string", "frame_index" to "integer",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val frame = readFrame(params, session.project)
            val fingerprint = FrameFingerprintComputer.of(frame)
            jsonobj {
                put("session_id", session.id)
                put("frame_index", session.project.activeFrameIndex)
                put("digest", fingerprint.digest)
                put("width", fingerprint.width)
                put("height", fingerprint.height)
                put("visible_pixels", fingerprint.visiblePixels)
            }
        }

        // ---- v6-checkpoints: recoverable experiments -----------------------

        add(
            "checkpoint_set",
            "Marks a named checkpoint of the current undo depth ('this state is known good'). Then try risky edits — rollback in one call via checkpoint_rollback instead of guessing undo counts. Setting the same name again replaces the checkpoint.",
            "v6-checkpoint",
            "session_id" to "string", "name" to "string",
            required = listOf("session_id", "name"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val name = params.string("name")
            val checkpoint = checkpoints.set(session.id, name, session.project.id, lab.engine)
            jsonobj {
                put("session_id", session.id)
                put("name", checkpoint.name)
                put("depth", checkpoint.depth)
                checkpoint.nextLabel?.let { put("next_label", it) }
                put("created_at_ms", num(checkpoint.createdAtMs))
            }
        }

        add(
            "checkpoint_list",
            "Lists this session's checkpoints (name, depth, creation time) in creation order.",
            "v6-checkpoint",
            "session_id" to "string",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val list = checkpoints.list(session.id)
            jsonobj {
                put("session_id", session.id)
                put("count", list.size)
                put(
                    "checkpoints",
                    jsonarray {
                        for (checkpoint in list) {
                            add(jsonobj {
                                put("name", checkpoint.name)
                                put("depth", checkpoint.depth)
                                put("project_id", checkpoint.projectId)
                                checkpoint.nextLabel?.let { put("next_label", it) }
                                put("created_at_ms", num(checkpoint.createdAtMs))
                            })
                        }
                    },
                )
            }
        }

        add(
            "checkpoint_rollback",
            "Undoes every change committed after a checkpoint (one call) and installs the restored snapshot as the session's working project. Idempotent: rolling back to an already-reached checkpoint reports zero steps. Fails with a clear error when the project lineage swapped since the checkpoint was taken.",
            "v6-checkpoint",
            "session_id" to "string", "name" to "string",
            required = listOf("session_id", "name"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val name = params.string("name")
            val result = checkpoints.rollback(session.id, name, session.project.id, lab.engine)
            val restored = result.restoredProject
            if (restored != null) {
                store.update(session.id, restored)
            }
            val current = store.get(session.id, create = false)?.project ?: session.project
            jsonobj {
                put("session_id", session.id)
                put("name", name)
                put("rewound", result.rewound)
                put("steps", result.steps)
                result.topRemainingLabel?.let { put("top_remaining_label", it) }
                put("project", projectSummary(session.id, current))
            }
        }

        add(
            "checkpoint_delete",
            "Removes one checkpoint marker (the history itself is untouched).",
            "v6-checkpoint",
            "session_id" to "string", "name" to "string",
            required = listOf("session_id", "name"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val name = params.string("name")
            val removed = checkpoints.remove(session.id, name)
            if (!removed) {
                throw IllegalArgumentException("checkpoint '$name' not found in session (see checkpoint_list)")
            }
            jsonobj {
                put("session_id", session.id)
                put("name", name)
                put("removed", true)
            }
        }

        // ---- v6-persistence: sessions that survive restarts ----------------

        add(
            "session_save",
            "Persists the session's current project to disk under a slot name (letters/digits/._-, max 64 chars) and records provenance. Saving the same name again overwrites the slot. Survives server restarts; list with session_saved_list, restore with session_load.",
            "v6-persist",
            "session_id" to "string", "name" to "string",
            required = listOf("session_id", "name"),
        ) { params, store ->
            val persistence = requirePersistence()
            val session = sessionOf(params, store)
            val name = params.string("name")
            val summary = persistence.slots.save(name, session.project, session.id, persistence.projects)
            slotEnvelope(session.id, summary, saved = true)
        }

        add(
            "session_load",
            "Restores a saved slot into a session: with session_id the current session's project is replaced (undo history of the old project is kept but unreachable through it); without session_id a fresh session is created. Returns the new/updated session_id plus the project summary.",
            "v6-persist",
            "session_id" to "string", "name" to "string",
            required = listOf("name"),
        ) { params, store ->
            val persistence = requirePersistence()
            val name = params.string("name")
            val project = persistence.slots.load(name, persistence.projects)
            val sessionId = optionalString(params, "session_id")
            val session = if (sessionId != null) {
                requireNotNull(store.get(sessionId, create = true)) { "session '$sessionId' unavailable" }
            } else {
                store.newSession(palette = project.palette, id = "sess-" + java.lang.Long.toString(System.nanoTime(), 36))
            }
            store.update(session.id, project)
            jsonobj {
                put("session_id", session.id)
                put("name", name)
                put("loaded", true)
                put("project", projectSummary(session.id, project))
            }
        }

        add(
            "session_saved_list",
            "Lists saved session slots (newest first) with name, project summary, save time, saving session, and whether the pointed-at project still exists on disk.",
            "v6-persist",
        ) { _, _ ->
            val persistence = requirePersistence()
            val known = persistence.projects.list().map { it.id }.toHashSet()
            val slots = persistence.slots.list()
            jsonobj {
                put("count", slots.size)
                put(
                    "slots",
                    jsonarray {
                        for (slot in slots) {
                            add(jsonobj {
                                for ((key, value) in slotJsonEntries(slot)) {
                                    put(key, value)
                                }
                                put("project_exists", slot.readable && slot.projectId in known)
                            })
                        }
                    },
                )
            }
        }

        add(
            "session_saved_delete",
            "Deletes a saved slot by name. The underlying project pixels are untouched (delete them via the gallery/store separately).",
            "v6-persist",
            "name" to "string",
            required = listOf("name"),
        ) { params, _ ->
            val persistence = requirePersistence()
            val name = params.string("name")
            val deleted = persistence.slots.delete(name)
            if (!deleted) {
                throw IllegalArgumentException("no saved session named '$name' (see session_saved_list)")
            }
            jsonobj {
                put("name", name)
                put("deleted", true)
            }
        }

        // ---- v6-hygiene: naming and export ---------------------------------

        add(
            "session_rename",
            "Renames the session's project (display name shown in project_list and saved slots). Does not touch pixels and does not create an undo entry.",
            "v6-hygiene",
            "session_id" to "string", "name" to "string",
            required = listOf("session_id", "name"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val name = params.string("name").trim()
            require(name.isNotEmpty()) { "name must not be blank" }
            require(name.length <= 120) { "name longer than 120 characters" }
            val renamed = session.project.withName(name)
            store.update(session.id, renamed)
            jsonobj {
                put("session_id", session.id)
                put("name", renamed.name)
                put("renamed", true)
            }
        }

        add(
            "project_export_json",
            "Exports the session project as the version-2 wire document (data_b64, UTF-8 JSON) — the exact format io_import_project reads back. Round-trips pixel-perfect; use it to migrate sessions between servers or embed projects in prompts.",
            "v6-hygiene",
            "session_id" to "string",
            required = listOf("session_id"),
        ) { params, store ->
            val session = sessionOf(params, store)
            val document = ProjectCodec.save(session.project)
            val bytes = document.toByteArray(Charsets.UTF_8)
            jsonobj {
                put("session_id", session.id)
                put("project_id", session.project.id)
                put("data_b64", Base64.getEncoder().encodeToString(bytes))
                put("byte_count", num(bytes.size.toLong()))
                put("wire_version", ProjectCodec.VERSION)
            }
        }

        return tools to schemas
    }

    // ---- batch parsing -------------------------------------------------------

    /** Parses one batch op object, prefixing every parameter error with the op index. */
    private fun parseBatchOp(op: JsonObject, index: Int): BatchOp {
        val kind = (op.raw("op") as? JsonString)?.value
            ?: throw IllegalArgumentException("op[$index]: missing 'op' kind")
        return when (kind.trim().lowercase()) {
            "pixel" -> BatchOp.Pixel(
                op.int("x"), op.int("y"), colorParam(op, "color", index),
            )
            "line" -> BatchOp.Line(
                op.int("x0"), op.int("y0"), op.int("x1"), op.int("y1"),
                colorParam(op, "color", index), op.opt("thickness", 1),
            )
            "rect" -> BatchOp.Rect(
                op.int("x"), op.int("y"), op.int("width"), op.int("height"),
                colorParam(op, "color", index), op.opt("filled", false),
            )
            "circle" -> BatchOp.Circle(
                op.int("cx"), op.int("cy"), op.int("radius"),
                colorParam(op, "color", index), op.opt("filled", false),
            )
            "stroke" -> BatchOp.Stroke(
                pointsParam(op, index), colorParam(op, "color", index), op.opt("thickness", 1),
            )
            "fill" -> BatchOp.Fill(
                op.int("x"), op.int("y"), colorParam(op, "color", index), op.opt("tolerance", 0),
            )
            "replace" -> BatchOp.Replace(
                colorParam(op, "from", index), colorParam(op, "to", index), op.opt("tolerance", 0),
            )
            "erase" -> BatchOp.Erase(pointsParam(op, index))
            else -> throw IllegalArgumentException(
                "op[$index]: unknown kind '$kind' (pixel, line, rect, circle, stroke, fill, replace, erase)",
            )
        }
    }

    private fun pointsParam(op: JsonObject, index: Int): List<PixelPoint> {
        val raw = op.raw("points") as? JsonArray
            ?: throw IllegalArgumentException("op[$index]: 'points' must be an array of {x, y}")
        if (raw.size < 1) throw IllegalArgumentException("op[$index]: 'points' must not be empty")
        val points = ArrayList<PixelPoint>(raw.size)
        for (element in raw.items) {
            val point = element as? JsonObject
                ?: throw IllegalArgumentException("op[$index]: points entries must be objects")
            points.add(PixelPoint(point.int("x"), point.int("y")))
        }
        return points
    }

    private fun kindOf(op: BatchOp): String = when (op) {
        is BatchOp.Pixel -> "pixel"
        is BatchOp.Line -> "line"
        is BatchOp.Rect -> "rect"
        is BatchOp.Circle -> "circle"
        is BatchOp.Stroke -> "stroke"
        is BatchOp.Fill -> "fill"
        is BatchOp.Replace -> "replace"
        is BatchOp.Erase -> "erase"
    }

    // ---- slot envelopes ------------------------------------------------------

    private fun slotEnvelope(sessionId: String, slot: SlotStore.SlotSummary, saved: Boolean): JsonObject =
        jsonobj {
            put("session_id", sessionId)
            put("name", slot.name)
            put("saved", saved)
            for ((key, value) in slotJsonEntries(slot)) {
                put(key, value)
            }
        }

    private fun slotJsonEntries(slot: SlotStore.SlotSummary): Map<String, com.pixellab.mcp.json.JsonElement> =
        linkedMapOf(
            "name" to JsonString(slot.name),
            "project_id" to JsonString(slot.projectId),
            "project_name" to JsonString(slot.projectName),
            "width" to num(slot.width),
            "height" to num(slot.height),
            "frames" to num(slot.frames),
            "layers" to num(slot.layers),
            "palette_id" to JsonString(slot.paletteId),
            "saved_by_session" to JsonString(slot.savedBySession),
            "saved_at_ms" to num(slot.savedAtMs),
            "readable" to JsonBoolean(slot.readable),
        )
}
