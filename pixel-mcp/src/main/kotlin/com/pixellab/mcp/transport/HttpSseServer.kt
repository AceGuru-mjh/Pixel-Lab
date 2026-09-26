package com.pixellab.mcp.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal HTTP/1.1 + Server-Sent-Events transport built on [java.net.ServerSocket].
 *
 * The server speaks just enough HTTP for an MCP streamable-SSE style session:
 *
 *  * `GET /sse` — responds `200 text/event-stream` and holds the connection
 *    open. The registered client keeps receiving `event:`/`data:` frames
 *    pushed by [sendEvent] until it disconnects; a write failure removes the
 *    client automatically.
 *  * `POST /messages` (and the alias `POST /mcp`) — reads a JSON body,
 *    forwards it to [onMessage] together with a [respond] callback. The
 *    callback writes an HTTP `200 application/json` response (with
 *    `Content-Length`) **and** mirrors the payload onto every live SSE
 *    stream, so both transports observe the same JSON-RPC reply. An empty
 *    string produces a body-less `202 Accepted` (used for notifications).
 *  * Anything else — `404` (unknown path) or `400` (malformed request).
 *
 * The socket accept loop and every connection handler run on [Dispatchers.IO]
 * through a [SupervisorJob] scope, so one slow client never fails the others.
 * [stop] is idempotent: it closes the listening socket, cancels the scope and
 * shuts every SSE stream down.
 */
class HttpSseServer(
    private val onMessage: suspend (body: String, respond: (String) -> Unit) -> Unit,
) {

    /** One registered SSE client: its socket, stream and write lock. */
    private class SseClient(val socket: Socket, val out: OutputStream) {
        val writeLock = Any()
    }

    private companion object {
        /** Guards on start/stop state transitions. */
        private val LOCK = Any()

        /** Upper bound for one request line or header line. */
        private const val MAX_LINE_BYTES: Int = 8 * 1024

        /** Upper bound for header count per request. */
        private const val MAX_HEADERS: Int = 64

        /** Upper bound for a request body (pixel arrays can be sizable). */
        private const val MAX_BODY_BYTES: Int = 32 * 1024 * 1024

        /** Read timeout while receiving the request head (ms). */
        private const val HEAD_TIMEOUT_MS: Int = 30_000

        /** Hard cap on simultaneously registered SSE clients. */
        private const val MAX_SSE_CLIENTS: Int = 32

        /** Idle SSE heartbeat interval (ms) keeping streams open across proxies. */
        private const val SSE_HEARTBEAT_MS: Long = 15_000
    }

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val clients = CopyOnWriteArrayList<SseClient>()
    private var running = false

    /** Starts listening on [port] (0 = pick a free ephemeral port). */
    fun start(port: Int) {
        synchronized(LOCK) {
            if (running) throw IllegalStateException("HttpSseServer is already running on port ${currentPort()}")
            val socket = ServerSocket(port, 64, InetAddress.getLoopbackAddress())
            serverSocket = socket
            running = true
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = serverScope
            serverScope.launch { acceptLoop(socket) }
        }
    }

    /** Stops the server; safe to call repeatedly. */
    fun stop() {
        synchronized(LOCK) {
            if (!running) return
            running = false
            scope?.cancel()
            scope = null
            closeQuietly(serverSocket)
            serverSocket = null
            for (client in clients) {
                closeQuietly(client.socket)
            }
            clients.clear()
        }
    }

    /** Whether the accept loop is live. */
    val isRunning: Boolean get() = running

    /** The bound port, or null while stopped (port 0 resolves to the real port). */
    val port: Int? get() = synchronized(LOCK) { if (running) currentPort() else null }

    /** Number of currently registered SSE clients. */
    val sseClientCount: Int get() = clients.size

    /**
     * Pushes an SSE frame `event: [event]` + `data: [data]` to every live
     * client. Multi-line [data] is split across consecutive `data:` lines as
     * required by the SSE framing. Clients whose stream rejects the write are
     * dropped and their sockets closed.
     */
    fun sendEvent(event: String, data: String) {
        val frame = buildString {
            append("event: ").append(event).append('\n')
            for (line in data.split('\n')) {
                append("data: ").append(line).append('\n')
            }
            append('\n')
        }.toByteArray(StandardCharsets.UTF_8)
        for (client in clients) {
            val ok = try {
                synchronized(client.writeLock) {
                    client.out.write(frame)
                    client.out.flush()
                }
                true
            } catch (error: IOException) {
                false
            }
            if (!ok) dropClient(client)
        }
    }

    // ---- accept loop ------------------------------------------------------

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (error: IOException) {
                break // listening socket closed by stop()
            }
            scope?.launch { handleConnection(client) }
        }
    }

    // ---- request handling -------------------------------------------------

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = HEAD_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return // client hung up immediately
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) {
                writeStatus(socket, 400, "{\"error\":\"bad_request\"}")
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = readHeaders(input) ?: run {
                writeStatus(socket, 400, "{\"error\":\"bad_request\"}")
                return
            }
            when {
                method == "GET" && path == "/sse" -> handleSse(socket, input)

                method == "POST" && (path == "/messages" || path == "/mcp") ->
                    handlePost(socket, input, headers)

                else -> writeStatus(socket, 404, "{\"error\":\"not_found\"}")
            }
        } catch (error: IOException) {
            // Client vanished mid-request: nothing to answer, just drop it.
        } catch (error: Exception) {
            try {
                writeStatus(socket, 400, "{\"error\":\"bad_request\"}")
            } catch (ignored: IOException) {
                // Socket already unusable.
            }
        } finally {
            if (!isSseSocket(socket)) closeQuietly(socket)
        }
    }

    /** Reads the header block; null signals a malformed request. */
    private fun readHeaders(input: InputStream): Map<String, String>? {
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) return headers
            if (++count > MAX_HEADERS) return null
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
    }

    /** POST /messages (and /mcp): body in, JSON-RPC response out (HTTP + SSE). */
    private suspend fun handlePost(socket: Socket, input: InputStream, headers: Map<String, String>) {
        val body: String
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (chunked) {
            body = readChunkedBody(input) ?: run {
                writeStatus(socket, 400, "{\"error\":\"bad_chunked_body\"}")
                return
            }
        } else {
            if (length < 0 || length > MAX_BODY_BYTES) {
                writeStatus(socket, 400, "{\"error\":\"bad_request\"}")
                return
            }
            honorExpect100Continue(socket, headers)
            body = if (length == 0) "" else readBody(input, length) ?: run {
                writeStatus(socket, 400, "{\"error\":\"bad_request\"}")
                return
            }
        }
        val responded = AtomicBoolean(false)
        onMessage(body) { text ->
            if (responded.compareAndSet(false, true)) {
                if (text.isEmpty()) {
                    writeStatus(socket, 202, null)
                } else {
                    writeJsonResponse(socket, text)
                }
            }
            if (text.isNotEmpty()) sendEvent("message", text)
        }
        if (!responded.get()) writeStatus(socket, 202, null) // notifications get no body
    }

    /** Interim `100 Continue` so curl sends large bodies without its 1s stall. */
    private fun honorExpect100Continue(socket: Socket, headers: Map<String, String>) {
        val expect = headers["expect"] ?: return
        if (!expect.contains("100-continue", ignoreCase = true)) return
        try {
            // NOTE: deliberately no `use {}` — closing a socket's OutputStream
            // closes the whole socket (JDK contract), which would sever the
            // connection before the request body and response can flow.
            val raw = socket.getOutputStream()
            raw.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
            raw.flush()
        } catch (error: IOException) {
            // The body read below will surface the real failure.
        }
    }

    /** GET /sse: emit the stream head, register the client, hold until disconnect. */
    private suspend fun handleSse(socket: Socket, input: InputStream) {
        if (clients.size >= MAX_SSE_CLIENTS) {
            writeStatus(socket, 503, "{\"error\":\"too_many_sse_clients\"}")
            closeQuietly(socket)
            return
        }
        socket.soTimeout = 0
        val out = BufferedOutputStream(socket.getOutputStream())
        try {
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: keep-alive\r\n\r\n"
                    ).toByteArray(StandardCharsets.UTF_8),
            )
            out.flush()
        } catch (error: IOException) {
            closeQuietly(socket)
            return
        }
        val client = SseClient(socket, out)
        clients.add(client)
        pushToOne(client, "endpoint", "/messages")
        // Hold the stream open until the client disconnects; discards any bytes.
        val heartbeat = scope?.launch {
            while (running) {
                kotlinx.coroutines.delay(SSE_HEARTBEAT_MS)
                // Idle comment frame keeps intermediaries from reaping the stream.
                if (client in clients) pushComment(client)
            }
        }
        try {
            val discard = ByteArray(1024)
            while (running && input.read(discard) != -1) {
                // Client input is ignored; the read only detects disconnects.
            }
        } catch (error: IOException) {
            // Disconnect: fall through to cleanup.
        } finally {
            heartbeat?.cancel()
            dropClient(client)
        }
    }

    /** Writes one SSE comment frame (`: keepalive`) to [client]. */
    private fun pushComment(client: SseClient) {
        try {
            synchronized(client.writeLock) {
                client.out.write(": keepalive\n\n".toByteArray(StandardCharsets.UTF_8))
                client.out.flush()
            }
        } catch (error: IOException) {
            dropClient(client)
        }
    }

    /** Writes a single SSE frame to one client, dropping it on failure. */
    private fun pushToOne(client: SseClient, event: String, data: String) {
        val frame = "event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8)
        try {
            synchronized(client.writeLock) {
                client.out.write(frame)
                client.out.flush()
            }
        } catch (error: IOException) {
            dropClient(client)
        }
    }

    /** Removes a client from the registry and closes its socket. */
    private fun dropClient(client: SseClient) {
        clients.remove(client)
        closeQuietly(client.socket)
    }

    /** True while [socket] is still owned by a registered SSE client. */
    private fun isSseSocket(socket: Socket): Boolean = clients.any { it.socket === socket }

    // ---- HTTP primitives --------------------------------------------------

    /** Writes a `200` JSON response with an exact `Content-Length`. */
    private fun writeJsonResponse(socket: Socket, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        socket.getOutputStream().use { raw ->
            raw.write(head)
            raw.write(bytes)
            raw.flush()
        }
    }

    /** Writes a status line with an optional short JSON body. */
    private fun writeStatus(socket: Socket, code: Int, body: String?) {
        val bytes = (body ?: "").toByteArray(StandardCharsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = (
            "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        socket.getOutputStream().use { raw ->
            raw.write(head)
            raw.write(bytes)
            raw.flush()
        }
    }

    /** Reads one CRLF/LF-terminated line (UTF-8); null on EOF or oversized line. */
    private fun readLine(input: InputStream): String? {
        var bytes = ByteArray(128)
        var count = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                if (count >= MAX_LINE_BYTES) return null
                if (count == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
                bytes[count++] = b.toByte()
            }
        }
        return String(bytes, 0, count, StandardCharsets.UTF_8)
    }

    /** Reads exactly [length] bytes; null when the stream ends early. */
    private fun readBody(input: InputStream, length: Int): String? {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read == -1) return null
            offset += read
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    /**
     * Decodes a `Transfer-Encoding: chunked` request body (RFC 9112 §7.1):
     * repeated `<hex-size>[;ext] CRLF data CRLF` chunks terminated by the
     * zero-size chunk and trailer section. Null on malformed framing or
     * when the reassembled body would exceed [MAX_BODY_BYTES].
     */
    private fun readChunkedBody(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: return null
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (size < 0 || out.size() + size > MAX_BODY_BYTES) return null
            if (size == 0) {
                // Trailer section: consume lines until the blank terminator.
                while (true) {
                    val trailer = readLine(input) ?: return null
                    if (trailer.isEmpty()) break
                }
                return out.toString("UTF-8")
            }
            val chunk = readBodyBytes(input, size) ?: return null
            out.write(chunk)
            val crlf = readLine(input) ?: return null
            if (crlf.isNotEmpty()) return null
        }
    }

    /** Reads exactly [length] raw bytes; null when the stream ends early. */
    private fun readBodyBytes(input: InputStream, length: Int): ByteArray? {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read == -1) return null
            offset += read
        }
        return bytes
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (ignored: IOException) {
            // Best-effort cleanup.
        }
    }

    private fun closeQuietly(socket: ServerSocket?) {
        try {
            socket?.close()
        } catch (ignored: IOException) {
            // Best-effort cleanup.
        }
    }

    private fun currentPort(): Int? = try {
        serverSocket?.localPort
    } catch (error: IllegalArgumentException) {
        null
    }
}
