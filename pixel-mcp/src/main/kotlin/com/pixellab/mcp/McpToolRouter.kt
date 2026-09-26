package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.mcp.json.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tier-chain composite over the four MCP tool registries.
 *
 * The registries were built incrementally: [McpToolRegistry] (v1, canvas /
 * draw / layer / frame / palette / animation / export tools),
 * [McpToolRegistryV2] (styled text, convert pipeline, brushes, symmetry),
 * [McpToolRegistryV3] (tilemaps, atlases, history, worldgen) and
 * [McpToolRegistryV4] (analysis, transforms, color science, vector export).
 * Historically only v1 was reachable through [PixelMcpServer] — the newer
 * tiers compiled but were never dispatched, which silently hid 96 tools
 * from every MCP client. This router closes that gap.
 *
 * Dispatch order is v1 → v2 → v3 → v4: each registry answers unknown names
 * with [McpToolException] carrying [JsonRpc.METHOD_NOT_FOUND], which the
 * router treats as "not in this tier, keep walking". Any other failure —
 * parameter errors (`-32602`), tool crashes — is re-thrown immediately so
 * the tier that owns the name reports the real error, never a fallback's.
 *
 * Construction is eager and validates:
 *  * every tier's tool list is materialized once (no lazy races),
 *  * tool names are globally unique across tiers — a duplicate is a
 *    programming error and fails fast with [IllegalStateException].
 *
 * Execution is serialized through one [Mutex] because the v1 engine's undo
 * history is per-instance state (same discipline as each tier's own
 * registry; the router just extends it across the chain).
 *
 * @param lab shared Pixel Lab instance injected into every tier that takes
 * one; the v2 object hosts its own lab by design (see its KDoc) and is
 * therefore the single deliberate exception.
 */
class McpToolRouter(lab: PixelLab) {

    /** One registry tier bound into the chain. */
    private class Tier(
        val number: Int,
        val names: Set<String>,
        val toolsInOrder: List<McpTool>,
        val schema: (String) -> JsonObject,
        val execute: suspend (String, JsonObject, PixelSessionStore) -> JsonObject,
    )

    /** Ordered chain: tier 1 first, tier 4 last. */
    private val tiers: List<Tier>

    /** Direct references for session-state cleanup dispatch. */
    private val v3: McpToolRegistryV3
    private val v4: McpToolRegistryV4

    /** All tools in tier order (the order `tools/list` reports). */
    val tools: List<McpTool>

    /** Schemas by tool name, merged across tiers (names are unique). */
    private val schemas: Map<String, JsonObject>

    /** Serializes tool runs across tiers (engine undo state is not thread-safe). */
    private val mutex = Mutex()

    init {
        val v1 = McpToolRegistry(lab)
        val v2 = McpToolRegistryV2
        val tier3 = McpToolRegistryV3(lab)
        val tier4 = McpToolRegistryV4(lab)
        v3 = tier3
        v4 = tier4
        tiers = listOf(
            Tier(1, v1.tools.map { it.name }.toSet(), v1.tools, { v1.inputSchema(it) }, { n, p, s -> v1.execute(n, p, s) }),
            Tier(2, v2.tools().map { it.name }.toSet(), v2.tools(), { v2.inputSchema(it) }, { n, p, s -> v2.execute(n, p, s) }),
            Tier(3, tier3.tools().map { it.name }.toSet(), tier3.tools(), { tier3.inputSchema(it) }, { n, p, s -> tier3.execute(n, p, s) }),
            Tier(4, tier4.tools.map { it.name }.toSet(), tier4.tools, { tier4.schemas[it] ?: emptySchema() }, { n, p, s -> tier4.execute(n, p, s) }),
        )
        val ordered = ArrayList<McpTool>()
        val byName = LinkedHashMap<String, Int>()
        val merged = HashMap<String, JsonObject>()
        for (tier in tiers) {
            for (tool in tier.toolsInOrder) {
                val previous = byName[tool.name]
                require(previous == null) {
                    "tool name '${tool.name}' registered by both tier $previous and tier ${tier.number}"
                }
                byName[tool.name] = tier.number
                ordered.add(tool)
                merged[tool.name] = tier.schema(tool.name)
            }
        }
        tools = ordered
        schemas = merged
    }

    /** Total number of dispatchable tools across all tiers. */
    fun toolCount(): Int = tools.size

    /** The registry tier (1..4) that owns [name], or null when unknown. */
    fun tierOf(name: String): Int? = tiers.firstOrNull { name in it.names }?.number

    /** JSON schema of [name]; an empty object schema for unknown names. */
    fun inputSchema(name: String): JsonObject = schemas[name] ?: emptySchema()

    /**
     * Runs the tool [name] with [params] against [store], walking the tier
     * chain until one tier owns the name. Unknown tools (no tier claims
     * them) re-throw the final `METHOD_NOT_FOUND` so the server answers
     * with the standard `-32601` error, exactly as before.
     */
    suspend fun execute(name: String, params: JsonObject, store: PixelSessionStore): JsonObject {
        val tier = tiers.firstOrNull { name in it.names }
            ?: throw McpToolException("unknown tool '$name'", JsonRpc.METHOD_NOT_FOUND)
        return mutex.withLock { tier.execute(name, params, store) }
    }

    /** Drops tier-local per-session state (v3/v4 command histories and tile
     * maps) for [sessionId] — called by the host when the session leaves the
     * store, so evicted conversations do not leak project snapshots. */
    fun clearSessionState(sessionId: String) {
        v3.clearSessionState(sessionId)
        v4.clearSessionState(sessionId)
    }

    /** Empty object schema reported for unregistered names. */
    private fun emptySchema(): JsonObject = com.pixellab.mcp.json.jsonobj {
        put("type", "object")
        put("properties", com.pixellab.mcp.json.jsonobj { })
    }
}
