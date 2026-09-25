package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.core.PixelLabConfig
import com.pixellab.mcp.json.Json
import com.pixellab.mcp.json.JsonElement
import com.pixellab.mcp.json.JsonNull
import com.pixellab.mcp.json.JsonNumber
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonParseException
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonarray
import com.pixellab.mcp.json.jsonobj
import com.pixellab.mcp.transport.HttpSseServer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Model Context Protocol server for Pixel Lab (contract §8).
 *
 * The server exposes the pixel-core engines over JSON-RPC 2.0 with an
 * HTTP + Server-Sent-Events transport ([HttpSseServer]):
 *
 *  * `initialize` — handshake answering `serverInfo` (`pixel-lab-mcp` 1.0.0)
 *    plus `capabilities.tools`.
 *  * `tools/list` — every registered tool with name, description, category
 *    and a simplified `inputSchema`.
 *  * `tools/call` — executes a tool; success returns
 *    `content: [{type: "text", text: <json>}]`, tool crashes return the same
 *    content shape with `isError: true`, and parameter failures answer with
 *    the JSON-RPC error object (`-32602`).
 *  * `ping` — liveness probe.
 *  * Anything else — `-32601` method not found; JSON-RPC notifications (no
 *    `id`) are accepted and acknowledged with a body-less 202.
 *
 * Every request and response is logged through
 * [PixelLabConfig.effectiveLogger] at debug level, and each response written
 * to the HTTP connection is also pushed onto the SSE stream so both channels
 * observe the same traffic. [start] is guarded (a second start throws
 * [IllegalStateException]) and [stop] is idempotent.
 *
 * Sessions live in an in-memory [PixelSessionStore]; calling [stop] drops
 * them, which makes restarts deterministic.
 */
class PixelMcpServer(private val config: PixelLabConfig = PixelLabConfig.default()) {

    /** Tool identity triple exposed by [listTools]. */
    data class McpToolInfo(val name: String, val description: String, val category: String)

    /** Start receipt: the bound port and the wall-clock start time. */
    data class PixelMcpHandle(val port: Int, val startedAtMs: Long)

    private val lab: PixelLab = PixelLab.create(config)
    private val registry: McpToolRegistry = McpToolRegistry(lab)
    private val store: PixelSessionStore = PixelSessionStore()
    private val transport: HttpSseServer = HttpSseServer { body, respond -> handleMessage(body, respond) }
    private val stateLock = Any()
    private var handle: PixelMcpHandle? = null

    /**
     * Starts listening on [port] (0 = ephemeral port).
     *
     * @throws IllegalStateException when the server is already running.
     */
    fun start(port: Int): PixelMcpHandle {
        synchronized(stateLock) {
            handle?.let { throw IllegalStateException("PixelMcpServer is already running on port ${it.port}") }
            transport.start(port)
            val boundPort = transport.port ?: port
            val started = PixelMcpHandle(boundPort, System.currentTimeMillis())
            handle = started
            config.effectiveLogger.i(TAG, "started on port $boundPort with ${registry.tools.size} tools")
            return started
        }
    }

    /** Stops the server and drops all sessions; safe to call repeatedly. */
    fun stop() {
        val previous: PixelMcpHandle
        synchronized(stateLock) {
            previous = handle ?: return
            handle = null
        }
        transport.stop()
        store.clear()
        config.effectiveLogger.i(TAG, "stopped (was listening on port ${previous.port})")
    }

    /** Whether the transport is currently accepting connections. */
    val isRunning: Boolean get() = transport.isRunning

    /** The bound port while running, null while stopped. */
    val port: Int? get() = transport.port

    /** Number of registered tools. */
    fun toolCount(): Int = registry.tools.size

    /** Registered tool identities in contract order. */
    fun listTools(): List<McpToolInfo> = registry.tools.map { McpToolInfo(it.name, it.description, it.category) }

    /** The backing session store (for hosts embedding the server in-process). */
    fun sessionStore(): PixelSessionStore = store

    // ---- JSON-RPC routing --------------------------------------------------

    /** Entry point fed by the transport: one JSON-RPC message in, one reply out. */
    private suspend fun handleMessage(body: String, respond: (String) -> Unit) {
        val request = try {
            val element = Json.parse(body)
            element as? JsonObject ?: run {
                respond(JsonRpc.buildError(null, JsonRpc.INVALID_REQUEST, "request must be a JSON object"))
                return
            }
        } catch (error: JsonParseException) {
            respond(JsonRpc.buildError(null, JsonRpc.PARSE_ERROR, "invalid JSON: ${error.message}"))
            return
        }
        val version = (request.raw("jsonrpc") as? JsonString)?.value
        val id = request.raw("id")
        if (version != "2.0") {
            respond(JsonRpc.buildError(id, JsonRpc.INVALID_REQUEST, "jsonrpc must be \"2.0\""))
            return
        }
        if (id != null && id !is JsonString && id !is JsonNumber && id !is JsonNull) {
            respond(JsonRpc.buildError(id, JsonRpc.INVALID_REQUEST, "id must be a string, number or null"))
            return
        }
        val method = try {
            request.string("method")
        } catch (error: IllegalArgumentException) {
            respond(JsonRpc.buildError(id, JsonRpc.INVALID_REQUEST, error.message ?: "missing method"))
            return
        }
        config.effectiveLogger.d(TAG, "-> $method (${body.length} chars)")
        if (!request.has("id")) {
            // JSON-RPC notification: no reply body (202), no side effects.
            config.effectiveLogger.d(TAG, "<- notification '$method' acknowledged without response")
            respond("")
            return
        }
        when (method) {
            "initialize" -> reply(respond, id, initializeResult(request), method)
            "ping" -> reply(respond, id, jsonobj { }, method)
            "tools/list" -> reply(respond, id, toolsListResult(), method)
            "tools/call" -> respondToolsCall(respond, id, request, method)
            else -> {
                val text = JsonRpc.buildError(id, JsonRpc.METHOD_NOT_FOUND, "method '$method' not found")
                config.effectiveLogger.w(TAG, "<- $method: -32601")
                respond(text)
            }
        }
    }

    /** Success shortcut: logs and answers with a result envelope. */
    private fun reply(respond: (String) -> Unit, id: JsonElement?, result: JsonObject, method: String) {
        val text = JsonRpc.buildResponse(id, result)
        config.effectiveLogger.d(TAG, "<- $method ok (${text.length} chars)")
        respond(text)
    }

    /** `tools/call` execution with the three failure shapes of MCP. */
    private suspend fun respondToolsCall(respond: (String) -> Unit, id: JsonElement?, request: JsonObject, method: String) {
        val params = request.raw("params") as? JsonObject
        if (params == null) {
            respond(JsonRpc.buildError(id, JsonRpc.INVALID_PARAMS, "tools/call requires a params object"))
            return
        }
        val name = try {
            params.string("name")
        } catch (error: IllegalArgumentException) {
            respond(JsonRpc.buildError(id, JsonRpc.INVALID_PARAMS, error.message ?: "missing tool name"))
            return
        }
        val arguments = (params.raw("arguments") as? JsonObject) ?: JsonObject(emptyMap())
        try {
            val result = registry.execute(name, arguments, store)
            val envelope = jsonobj {
                put(
                    "content",
                    jsonarray {
                        add(
                            jsonobj {
                                put("type", "text")
                                put("text", Json.write(result))
                            },
                        )
                    },
                )
            }
            reply(respond, id, envelope, "$method/$name")
        } catch (error: CancellationException) {
            throw error
        } catch (error: McpToolException) {
            val text = JsonRpc.buildError(id, error.code, error.message ?: "tool '$name' failed")
            config.effectiveLogger.w(TAG, "<- $method/$name: ${error.code} ${error.message}")
            respond(text)
        } catch (error: Exception) {
            val envelope = jsonobj {
                put(
                    "content",
                    jsonarray {
                        add(
                            jsonobj {
                                put("type", "text")
                                put("text", "tool '$name' failed: ${error.message ?: error.javaClass.simpleName}")
                            },
                        )
                    },
                )
                put("isError", true)
            }
            config.effectiveLogger.w(TAG, "<- $method/$name: isError (${error.message})")
            reply(respond, id, envelope, "$method/$name")
        }
    }

    /** Handshake result: protocol version, capabilities and server identity. */
    private fun initializeResult(request: JsonObject): JsonObject {
        val requested = (request.raw("params") as? JsonObject)?.raw("protocolVersion") as? JsonString
        return jsonobj {
            put("protocolVersion", requested?.value ?: PROTOCOL_VERSION)
            put(
                "capabilities",
                jsonobj {
                    put("tools", jsonobj { })
                },
            )
            put(
                "serverInfo",
                jsonobj {
                    put("name", SERVER_NAME)
                    put("version", SERVER_VERSION)
                },
            )
        }
    }

    /** `tools/list` result: one entry per registered tool. */
    private fun toolsListResult(): JsonObject = jsonobj {
        put(
            "tools",
            jsonarray {
                for (tool in registry.tools) {
                    add(
                        jsonobj {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("category", tool.category)
                            put("inputSchema", registry.inputSchema(tool.name))
                        },
                    )
                }
            },
        )
        put("toolCount", registry.tools.size)
    }

    private companion object {
        private const val TAG: String = "PixelMcpServer"
        private const val SERVER_NAME: String = "pixel-lab-mcp"
        private const val SERVER_VERSION: String = "1.0.0"
        private const val PROTOCOL_VERSION: String = "2024-11-05"
    }
}
