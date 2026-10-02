package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.mcp.json.Json
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonobj
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Round-2 audit (5-c) regression suite: the V4 undo-orphan fix, the raster
 * budget family, protocol version negotiation, unknown-tool error codes,
 * anim_tag spans and the session get-or-create race.
 */
class McpUndoBudgetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val lab = PixelLab.create()
    private val store = PixelSessionStore()
    private val v1 = McpToolRegistry(lab)
    private val v3 = McpToolRegistryV3(lab)
    private val v4 = McpToolRegistryV4(lab)

    private fun run(vararg pairs: Pair<String, Any>): JsonObject = jsonobj {
        for ((key, value) in pairs) {
            when (value) {
                is String -> put(key, JsonString(value))
                is Int -> put(key, com.pixellab.mcp.json.JsonNumber(value.toDouble(), value.toString()))
            }
        }
    }

    private fun v1Tool(name: String, params: JsonObject): JsonObject =
        runBlocking { v1.execute(name, params, store) }

    private fun v3Tool(name: String, params: JsonObject): JsonObject =
        runBlocking { v3.execute(name, params, store) }

    private fun v4Tool(name: String, params: JsonObject): JsonObject =
        runBlocking { v4.execute(name, params, store) }

    private fun expectIAE(action: () -> Unit): McpToolException {
        try {
            action()
            throw AssertionError("expected an McpToolException (invalid params)")
        } catch (expected: McpToolException) {
            return expected
        }
    }

    // ---- V4 undo coverage (was: undo orphans) ----------------------------------

    @Test
    fun `v4 frame mutations are undoable through project_undo`() {
        val session = v1Tool("canvas_create", run("session_id" to "undo-v4"))
        val sessionId = (session.raw("session_id") as JsonString).value

        // Two distinct luma values so equalize has something to stretch.
        v1Tool("draw_pixel", run("session_id" to sessionId, "x" to 0, "y" to 0, "color" to "#FF101010"))
        v1Tool("draw_pixel", run("session_id" to sessionId, "x" to 1, "y" to 0, "color" to "#FFE0E0E0"))

        val before = v5ReadPixel(sessionId, 0, 0)
        val equalized = v4Tool("frame_equalize", run("session_id" to sessionId))
        assertEquals("frame_equalize", (equalized.raw("operation") as JsonString).value)
        val after = v5ReadPixel(sessionId, 0, 0)
        assertTrue("equalize must change the pixel ($before -> $after)", before != after)

        // The audit finding: project_undo used to answer undone=false because
        // V4 wrote through withActiveCel, bypassing the engine history.
        val undone = v1Tool("project_undo", run("session_id" to sessionId))
        assertEquals("V4 frame_equalize must be undoable", true, (undone.raw("undone") as? com.pixellab.mcp.json.JsonBoolean)?.value)
        assertEquals("undo must restore the pre-equalize pixel", before, v5ReadPixel(sessionId, 0, 0))
    }

    /** Reads one pixel hex via the v5 pixel_probe tool. */
    private fun v5ReadPixel(sessionId: String, x: Int, y: Int): String {
        val v5 = McpToolRegistryV5(lab)
        val read = runBlocking {
            v5.execute(
                "pixel_probe",
                jsonobj {
                    put("session_id", JsonString(sessionId))
                    put("x", com.pixellab.mcp.json.JsonNumber(x.toDouble(), x.toString()))
                    put("y", com.pixellab.mcp.json.JsonNumber(y.toDouble(), y.toString()))
                    put("radius", com.pixellab.mcp.json.JsonNumber(1.0, "1"))
                },
                store,
            )
        }
        return (read.raw("hex") as? JsonString)?.value ?: read.toString()
    }

    // ---- raster budget family ---------------------------------------------------

    @Test
    fun `io_export_qoi scale is bounded by the output pixel budget`() {
        val session = v1Tool("canvas_create", run("session_id" to "qoi-scale", "width" to 1024, "height" to 1024))
        val sessionId = (session.raw("session_id") as JsonString).value
        // 1024x1024 (canvas budget legal) x scale 4 = 4096^2 = 16.7M px.
        val error = expectIAE {
            v3Tool("io_export_qoi", run("session_id" to sessionId, "scale" to 4))
        }
        assertTrue(error.message!!, error.message!!.contains("raster budget"))
    }

    @Test
    fun `frame_resample is bounded by the output pixel budget`() {
        val session = v1Tool("canvas_create", run("session_id" to "resample"))
        val sessionId = (session.raw("session_id") as JsonString).value
        val error = expectIAE {
            v4Tool("frame_resample", run("session_id" to sessionId, "width" to 8192, "height" to 8192))
        }
        assertTrue(error.message!!, error.message!!.contains("raster budget"))
    }

    @Test
    fun `gen_texture is bounded by the output pixel budget`() {
        val error = expectIAE {
            v3Tool("gen_texture", run("type" to "clouds", "width" to 4096, "height" to 4096))
        }
        assertTrue(error.message!!, error.message!!.contains("raster budget"))
    }

    // ---- anim contracts ----------------------------------------------------------

    @Test
    fun `anim_tag rejects negative and reversed spans`() {
        val session = v1Tool("canvas_create", run("session_id" to "tag"))
        val sessionId = (session.raw("session_id") as JsonString).value
        val negative = expectIAE {
            v1Tool("anim_tag", run("session_id" to sessionId, "name" to "bad", "start_frame" to -5, "end_frame" to 0))
        }
        assertTrue(negative.message!!, negative.message!!.contains("startFrame"))

        val reversed = expectIAE {
            v1Tool("anim_tag", run("session_id" to sessionId, "name" to "bad", "start_frame" to 5, "end_frame" to 2))
        }
        // AnimationTag's own init catches the reversed span first with its
        // phrasing; either message proves the span contract is enforced.
        assertTrue(reversed.message!!, reversed.message!!.contains("startFrame"))
    }

    @Test
    fun `anim_set_fps rejects values the project would silently coerce`() {
        val session = v1Tool("canvas_create", run("session_id" to "fps"))
        val sessionId = (session.raw("session_id") as JsonString).value
        val error = expectIAE {
            v1Tool("anim_set_fps", run("session_id" to sessionId, "fps" to 59000))
        }
        assertTrue(error.message!!, error.message!!.contains("[1, 24]"))
    }

    // ---- wire level: negotiation and error codes ---------------------------------

    @Test
    fun `initialize negotiates the protocol version instead of echoing`() {
        val server = PixelMcpServer(persistenceRoot = tmp.newFolder())
        val handle = server.start(0)
        try {
            val payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"," +
                "\"params\":{\"protocolVersion\":\"1999-01-01\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"0\"}}}"
            val (status, body) = httpExchange(handle.port, handle.authToken, payload)
            assertTrue(status.contains("200"))
            val parsed = Json.parse(body) as JsonObject
            val result = parsed.raw("result") as JsonObject
            val version = (result.raw("protocolVersion") as JsonString).value
            assertEquals("server must answer with its supported version", "2024-11-05", version)

            // And a matching request keeps the requested (== supported) version.
            val payload2 = payload.replace("1999-01-01", "2024-11-05")
            val (_, body2) = httpExchange(handle.port, handle.authToken, payload2)
            val result2 = (Json.parse(body2) as JsonObject).raw("result") as JsonObject
            assertEquals("2024-11-05", (result2.raw("protocolVersion") as JsonString).value)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `unknown tools answer -32602 not -32601`() {
        val server = PixelMcpServer(persistenceRoot = tmp.newFolder())
        val handle = server.start(0)
        try {
            val payload = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," +
                "\"params\":{\"name\":\"definitely_not_a_tool\",\"arguments\":{}}}"
            val (status, body) = httpExchange(handle.port, handle.authToken, payload)
            assertTrue(status.contains("200"))
            val parsed = Json.parse(body) as JsonObject
            val error = parsed.raw("error") as JsonObject
            val code = (error.raw("code") as com.pixellab.mcp.json.JsonNumber).value.toInt()
            assertEquals("unknown tools/call tool must map to invalid params", -32602, code)
        } finally {
            server.stop()
        }
    }

    // ---- session store race --------------------------------------------------------

    @Test
    fun `concurrent get-or-create never displaces a live session`() {
        val raced = PixelSessionStore(
            onSessionDiscarded = { _ -> throw AssertionError("a live session was displaced") },
        )
        val results = (1..16).map { i ->
            Thread {
                repeat(64) {
                    raced.get("shared-id", create = true)
                }
            }.apply { start() }
        }
        results.forEach { it.join() }
        assertNotNull(raced.get("shared-id", create = false))
    }

    // ---- tiny raw HTTP helper --------------------------------------------------------

    private fun httpExchange(port: Int, token: String, payload: String): Pair<String, String> {
        val request = "POST /messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer $token\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n" +
            payload
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
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n < 0) break
                read += n
            }
            return statusLine to String(bytes, Charsets.UTF_8)
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) return builder.toString().trim()
            builder.append(b.toChar())
        }
    }
}
