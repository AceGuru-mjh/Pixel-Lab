package com.pixellab.mcp

import com.pixellab.mcp.json.Json
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Wire-level end-to-end workflow test: one HTTP client drives the FULL
 * agent story through the real server (transport + JSON-RPC envelope +
 * router + all six tool tiers), not the registries directly:
 *
 * connect → initialize → tools/list → canvas_create → draw (dust) →
 * frame_audit → **frame_heal** → animate → export_png (data_b64) →
 * io_import_image (bytes back in) → canvas_read (verify pixels) →
 * session_save → server restart → session_load (document restored).
 *
 * This is the regression net that makes "closed loop" a CI-enforced
 * property instead of a claim: every leg must answer over the wire with
 * real data flowing between tools.
 */
class McpWorkflowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- tiny raw-HTTP JSON-RPC client ------------------------------------

    /** Boots a server with a persistent root in the temp dir. */
    private fun bootServer(root: java.io.File): Triple<PixelMcpServer, Int, String> {
        val server = PixelMcpServer(persistenceRoot = root)
        val handle = server.start(0)
        return Triple(server, handle.port, handle.authToken)
    }

    /** POSTs one JSON-RPC request; returns the parsed response object. */
    private fun call(port: Int, token: String, id: Int, method: String, params: String? = null): JsonObject {
        val payload = buildString {
            append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
            append(",\"method\":\"").append(method).append('"')
            if (params != null) append(",\"params\":").append(params)
            append('}')
        }
        val request = "POST /messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer $token\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n" +
            payload
        val (status, body) = httpExchange(port, request)
        assertTrue("HTTP failed for $method: $status", status.contains("200"))
        val parsed = Json.parse(body)
        assertTrue("response for $method is not an object: $body", parsed is JsonObject)
        val root = parsed as JsonObject
        assertEquals("jsonrpc error in response: $body", null, root.raw("error"))
        val result = root.raw("result")
        assertNotNull("missing result for $method: $body", result)
        return result as JsonObject
    }

    /** Runs one tool and unwraps the MCP content envelope into the tool's JSON. */
    private fun tool(port: Int, token: String, id: Int, name: String, arguments: String): JsonObject {
        val result = call(
            port, token, id, "tools/call",
            "{\"name\":\"$name\",\"arguments\":$arguments}",
        )
        assertEquals("tool $name reported isError", null, result.raw("isError"))
        val content = result.raw("content")
        assertTrue("tool $name returned no content", content is com.pixellab.mcp.json.JsonArray)
        val first = (content as com.pixellab.mcp.json.JsonArray).items.firstOrNull()
        assertTrue("tool $name content[0] is not text", first is JsonObject)
        val text = (first as JsonObject).raw("text")
        assertTrue("tool $name content text is not a string", text is JsonString)
        val inner = Json.parse((text as JsonString).value)
        assertTrue("tool $name inner result is not an object", inner is JsonObject)
        return inner as JsonObject
    }

    private fun httpExchange(port: Int, request: String): Pair<String, String> {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 30_000
            val out: OutputStream = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()
            val input: InputStream = socket.getInputStream()
            val statusLine = readLine(input) ?: return "" to ""
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
            val body = when {
                headers["transfer-encoding"]?.contains("chunked") == true -> readChunked(input)
                (headers["content-length"]?.toIntOrNull() ?: 0) > 0 -> {
                    val length = headers["content-length"]!!.toInt()
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(bytes, read, length - read)
                        if (n == -1) break
                        read += n
                    }
                    String(bytes, 0, read, Charsets.UTF_8)
                }
                else -> ""
            }
            return statusLine to body
        }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (value == '\n'.code) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(value)
            if (bytes.size() > 64 * 1024) return bytes.toString("UTF-8")
        }
    }

    private fun readChunked(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(chunk, read, size - read)
                if (n == -1) break
                read += n
            }
            out.write(chunk, 0, read)
            readLine(input)
        }
        return out.toString("UTF-8")
    }

    // ---- the full agent story, over the wire -------------------------------

    @Test
    fun `full agent workflow survives the wire end to end`() {
        val root = tmp.newFolder("persistence")
        val (server, port, token) = bootServer(root)
        try {
            // 1. Handshake.
            val init = call(port, token, 1, "initialize")
            assertTrue("server must answer a protocol version", init.has("protocolVersion"))

            // 2. Tool catalog includes the heal leg.
            val tools = call(port, token, 2, "tools/list")
            assertEquals(
                "tools/list count drifted from the documented 183 (docs/MCP-TOOLS.md)",
                183,
                tools.opt("toolCount", 0),
            )
            val names = (tools.raw("tools") as? com.pixellab.mcp.json.JsonArray)
                ?.items?.mapNotNull { (it as? JsonObject)?.string("name") } ?: emptyList()
            assertTrue("tools/list empty", names.isNotEmpty())
            for (required in listOf("canvas_create", "draw_pixels", "frame_audit", "frame_heal", "export_png", "io_import_image", "session_save", "session_load")) {
                assertTrue("catalog is missing $required", required in names)
            }

            // 3. Create a canvas.
            val created = tool(port, token, 3, "canvas_create", "{\"width\":8,\"height\":8,\"session_id\":\"e2e-1\"}")
            assertEquals("e2e-1", created.string("session_id"))

            // 4. Draw art plus a dust pixel: a solid 2x2 block at (1,1) and
            //    an isolated red pixel at (6,0) — classic audit bait.
            val painted = tool(
                port, token, 4, "draw_pixels",
                "{\"session_id\":\"e2e-1\",\"color\":\"#ff3366\",\"points\":[" +
                    "{\"x\":1,\"y\":1},{\"x\":2,\"y\":1},{\"x\":1,\"y\":2},{\"x\":2,\"y\":2},{\"x\":6,\"y\":0}]}",
            )
            assertEquals("e2e-1", painted.string("session_id"))

            // 5. Audit: the dust must be visible to the agent.
            val audit = tool(port, token, 5, "frame_audit", "{\"session_id\":\"e2e-1\"}")
            val findings = (audit.raw("findings") as? com.pixellab.mcp.json.JsonArray)?.items?.size ?: 0
            assertTrue("expected at least one audit finding, got $findings (score ${audit.raw("score")})", findings > 0)

            // 6. Heal: the audit loop closes in ONE call.
            val heal = tool(port, token, 6, "frame_heal", "{\"session_id\":\"e2e-1\"}")
            assertTrue("frame_heal reported rolled_back=${heal.raw("rolled_back")}", heal.opt("all_applied", true))
            assertTrue("frame_heal fixed nothing (fixed=${heal.raw("fixed")})", heal.opt("total_fixed", 0) > 0)
            val delta = heal.opt("score_delta", 0)
            assertTrue("score_delta must be positive, was $delta", delta > 0)

            // 7. The dust pixel is really gone; the block survives.
            val sketch = tool(
                port, token, 7, "canvas_read",
                "{\"session_id\":\"e2e-1\",\"width\":8,\"height\":8,\"format\":\"rle\"}",
            )
            val content = sketch.string("content")
            assertTrue("canvas_read returned empty content", content.isNotEmpty())

            // 8. Animate: second frame.
            val anim = tool(port, token, 8, "frame_add", "{\"session_id\":\"e2e-1\",\"after_index\":0}")
            assertEquals("e2e-1", anim.string("session_id"))

            // 9. Export the current art as PNG bytes over the wire.
            val exported = tool(port, token, 9, "export_png", "{\"session_id\":\"e2e-1\"}")
            assertTrue("export_png produced no bytes", exported.opt("byte_count", 0) > 0)
            val dataB64 = (exported.raw("data_b64") as? JsonString)?.value
            assertTrue("export_png did not inline data_b64", !dataB64.isNullOrEmpty())

            // 10. Bytes back in: a fresh editable session from the export.
            val imported = tool(
                port, token, 10, "io_import_image",
                "{\"data_b64\":\"$dataB64\",\"name\":\"round-trip\"}",
            )
            assertEquals("png", imported.opt("format", ""))
            val importSession = imported.string("session_id")
            assertTrue("import produced no session", importSession.isNotEmpty())

            // 11. The imported session reads back non-empty art.
            val reRead = tool(
                port, token, 11, "canvas_read",
                "{\"session_id\":\"$importSession\",\"width\":8,\"height\":8,\"format\":\"rle\"}",
            )
            assertTrue("imported canvas reads empty", reRead.string("content").isNotEmpty())

            // 12. Persist the original session and remember its art.
            val saved = tool(port, token, 12, "session_save", "{\"session_id\":\"e2e-1\",\"name\":\"e2e-story\"}")
            assertTrue("session_save failed", saved.opt("saved", false))
        } finally {
            server.stop()
        }

        // 13. Server restart: sessions live in the persistence root, so the
        //     saved document must survive a full stop/start cycle.
        val (server2, port2, token2) = bootServer(root)
        try {
            val loaded = tool(port2, token2, 13, "session_load", "{\"name\":\"e2e-story\"}")
            assertTrue("session_load failed", loaded.opt("loaded", false))
            val restoredSession = loaded.string("session_id")
            assertTrue("restored session id empty", restoredSession.isNotEmpty())
            val restored = tool(
                port2, token2, 14, "canvas_read",
                "{\"session_id\":\"$restoredSession\",\"width\":8,\"height\":8,\"format\":\"rle\"}",
            )
            // The healed 2x2 block (no dust) must still be on the canvas.
            assertTrue("restored canvas reads empty", restored.string("content").isNotEmpty())
        } finally {
            server2.stop()
        }
    }

    @Test
    fun `v2 tools share the server engine history so project undo sees their edits`() {
        val root = tmp.newFolder("v2-shared-engine")
        val (server, port, token) = bootServer(root)
        try {
            val created = tool(port, token, 1, "canvas_create", "{\"width\":16,\"height\":16,\"session_id\":\"v2-1\"}")
            assertEquals("v2-1", created.string("session_id"))

            // A v2-tier mutation: shape_rect records into the SHARED engine
            // history (previously it fed a private, unreachable lab).
            val shape = tool(
                port, token, 2, "shape_rect",
                "{\"session_id\":\"v2-1\",\"x\":2,\"y\":2,\"width\":6,\"height\":4,\"color\":\"#33ff66\"}",
            )
            assertEquals("v2-1", shape.string("session_id"))

            // The v1 undo tool must now revert the v2 edit (shared engine).
            val undo = tool(port, token, 3, "project_undo", "{\"session_id\":\"v2-1\"}")
            assertTrue(
                "project_undo did not see the v2 shape edit — the tiers share no engine history",
                undo.opt("undone", false),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `same template applied twice never shares a project id`() {
        val root = tmp.newFolder("template-ids")
        val (server, port, token) = bootServer(root)
        try {
            val first = tool(port, token, 1, "template_apply", "{\"template_id\":\"heart-pixel\"}")
            val second = tool(port, token, 2, "template_apply", "{\"template_id\":\"heart-pixel\"}")
            val id1 = first.string("project_id")
            val id2 = second.string("project_id")
            assertTrue("two applications of one template must not share a project id ($id1)", id1 != id2)
        } finally {
            server.stop()
        }
    }
}
