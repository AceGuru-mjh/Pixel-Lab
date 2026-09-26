package com.pixellab.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.io.OutputStream
import java.io.InputStream

/**
 * Loopback integration tests for the transport-hardening fixes:
 *
 *  * **Chunked-size overflow** — a chunk header of `7FFFFFFF` after a small
 *    first chunk used to slip past an Int-sum bound check, attempt a ~2 GB
 *    allocation and kill the process with an un-catchable
 *    OutOfMemoryError. The fixed server rejects the framing (400) and
 *    keeps serving.
 *  * **JSON-RPC id echo** — an error reply must never echo a non-scalar
 *    request id (object/array/bool) back onto the wire; JSON-RPC 2.0
 *    response ids are string/number/null only.
 *  * **WebSocket handshake** — RFC 6455 §4.2.1 demands
 *    `Sec-WebSocket-Version: 13` and a `Connection: Upgrade` token;
 *    drafts and omissions get 400 (with a version negotiation header),
 *    not a 101.
 *
 * Each test boots a real PixelMcpServer on an ephemeral loopback port and
 * speaks raw HTTP/1.1 over a socket, mirroring the CI's JVM gates (pure
 * JDK networking, no Android framework).
 */
class TransportHardeningTest {

    /** Boots a server on an ephemeral port; returns it plus the HTTP port. */
    private fun bootServer(): Pair<PixelMcpServer, Int> {
        val server = PixelMcpServer()
        val handle = server.start(0)
        return server to handle.port
    }

    /** Raw HTTP request over a fresh socket; returns status line + body. */
    private fun httpExchange(port: Int, request: String): Pair<String, String> {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15_000
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
                headers["content-length"]?.toIntOrNull() ?: 0 > 0 -> {
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
            readLine(input) // trailing CRLF
        }
        return out.toString("UTF-8")
    }

    // ---- chunked overflow guard -------------------------------------------

    @Test
    fun `chunked overflow header is rejected without a 2GB allocation`() {
        val (server, port) = bootServer()
        try {
            // 1-byte legal chunk, then a 0x7FFFFFFF chunk header: with the
            // old Int-sum bound this produced 1 + 2147483647 = overflow,
            // passed the check and attempted ByteArray(2147483647).
            val body = "1\r\nA\r\n7FFFFFFF\r\n"
            val request = "POST /messages HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n" +
                body
            val (status, _) = httpExchange(port, request)
            assertTrue(
                "expected a 400 rejection, got '$status'",
                status.contains("400"),
            )
            // The server must still be healthy afterwards.
            val payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"
            val (okStatus, okBody) = httpExchange(
                port,
                "POST /messages HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$port\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n" +
                    "Connection: close\r\n\r\n" +
                    payload,
            )
            assertTrue(okStatus.contains("200"))
            assertTrue(okBody.contains("tools"))
        } finally {
            server.stop()
        }
    }

    // ---- JSON-RPC id echo --------------------------------------------------

    @Test
    fun `error replies never echo a non-scalar id`() {
        val (server, port) = bootServer()
        try {
            val payload = "{\"jsonrpc\":\"1.0\",\"id\":{\"x\":1},\"method\":\"ping\"}"
            val request = "POST /messages HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" +
                payload
            val (status, body) = httpExchange(port, request)
            assertTrue(status.contains("200") || status.contains("400"))
            // The echoed id must be null, never the object.
            assertTrue(
                "body should contain \"id\":null, was: $body",
                body.contains("\"id\":null"),
            )
            assertTrue(!body.contains("\"id\":{\"x\":1}"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `valid request still round trips its id`() {
        val (server, port) = bootServer()
        try {
            val payload = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/list\"}"
            val request = "POST /messages HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" + payload
            val (status, body) = httpExchange(port, request)
            assertTrue(status.contains("200"))
            assertTrue(body.contains("\"id\":42"))
        } finally {
            server.stop()
        }
    }

    // ---- WebSocket handshake strictness ------------------------------------

    @Test
    fun `handshake without websocket version is refused with negotiation header`() {
        val ws = com.pixellab.mcp.transport.WebSocketServer(0, handler = { "" }, logger = null)
        try {
            val wsPort = ws.port
            Socket("127.0.0.1", wsPort).use { socket ->
                socket.soTimeout = 15_000
                val out = socket.getOutputStream()
                // Version header deliberately absent (and Connection lacks
                // the Upgrade token): RFC 6455 §4.2.1 requires both.
                val request = "GET / HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$wsPort\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "\r\n"
                out.write(request.toByteArray(Charsets.UTF_8))
                out.flush()
                val input = socket.getInputStream()
                val status = readLine(input) ?: ""
                val headerText = StringBuilder()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    headerText.append(line).append('\n')
                }
                assertTrue("expected 400, got '$status'", status.contains("400"))
                assertTrue(
                    "expected Sec-WebSocket-Version negotiation header",
                    headerText.toString().lowercase().contains("sec-websocket-version: 13"),
                )
            }
        } finally {
            ws.close()
        }
    }

    @Test
    fun `close frame with illegal code is answered with 1002`() {
        val ws = com.pixellab.mcp.transport.WebSocketServer(0, handler = { "" }, logger = null)
        try {
            val wsPort = ws.port
            Socket("127.0.0.1", wsPort).use { socket ->
                socket.soTimeout = 15_000
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                // Handshake (version 13 present).
                val request = "GET / HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$wsPort\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n"
                out.write(request.toByteArray(Charsets.UTF_8))
                out.flush()
                val status = readLine(input) ?: ""
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                }
                assertTrue(status.contains("101"))
                // Close frame with an illegal code (999), masked per RFC.
                val payload = byteArrayOf(0x03, 0xE7.toByte())
                val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
                val masked = ByteArray(payload.size)
                for (i in payload.indices) masked[i] = (payload[i].toInt() xor mask[i].toInt() and 0xFF).toByte()
                val frame = ByteArrayOutputStream()
                frame.write(0x88) // FIN + close opcode
                frame.write(0x80 or payload.size) // masked, length 2
                frame.write(mask)
                frame.write(masked)
                out.write(frame.toByteArray())
                out.flush()
                // Server reply: a close frame (FIN, opcode 8), unmasked, 2 bytes.
                val header1 = input.read()
                val header2 = input.read()
                assertEquals(0x88, header1)
                val length = header2 and 0x7F
                assertEquals(2, length)
                val codeHigh = input.read()
                val codeLow = input.read()
                val code = (codeHigh shl 8) or codeLow
                assertEquals(1002, code)
            }
        } finally {
            ws.close()
        }
    }

    // ---- start/stop partial failure ---------------------------------------

    @Test
    fun `start with a busy websocket port rolls the http transport back`() {
        // Occupy a loopback port so the WS bind fails after HTTP started.
        val squatter = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val busyPort = squatter.localPort
        val server = PixelMcpServer()
        try {
            server.start(0, websocketPort = busyPort)
            throw AssertionError("expected start() to fail on the busy WS port")
        } catch (expected: Exception) {
            assertNotNull(expected.message)
        } finally {
            server.stop() // must be a real rollback, not a no-op leak
        }
        squatter.close()
        // After the rollback the server can start again cleanly.
        val restarted = server.start(0)
        assertTrue(restarted.port > 0)
        server.stop()
    }
}
