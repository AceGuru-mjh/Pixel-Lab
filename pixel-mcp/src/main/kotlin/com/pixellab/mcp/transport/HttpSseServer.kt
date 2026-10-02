package com.pixellab.mcp.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
 * ## Access control
 *
 * Loopback binding alone does not isolate an Android app: every other app
 * on the device shares 127.0.0.1, and a browser page can fire a
 * `Content-Type: text/plain` simple request at it cross-origin. When
 * [authToken] is non-null every request must carry
 * `Authorization: Bearer <token>` and POSTs must declare
 * `Content-Type: application/json` — anything else answers `401`. The host
 * generates the token at start and hands it to its MCP client out of band.
 *
 * ## Resource discipline
 *
 *  * Bodies are read incrementally with an 8 MB cap (no up-front
 *    allocation of the declared size).
 *  * Total in-flight connections are capped; the accept loop runs on its
 *    own thread so the shared IO pool can never starve it.
 *  * SSE clients write through bounded queues on dedicated writer threads —
 *    a client that stops reading is dropped, never allowed to block the
 *    request path (the historical synchronous flush could wedge the whole
 *    server behind one full TCP window).
 *
 * [stop] is idempotent: it closes the listening socket, cancels the scope and
 * shuts every SSE stream down.
 */
class HttpSseServer(
    private val onMessage: suspend (body: String, respond: (String) -> Unit) -> Unit,
    /**
     * Initial bearer token requirement, or null to accept unauthenticated
     * requests. [start] accepts a fresh token per lifetime (the server
     * instance survives stop/start cycles).
     */
    authToken: String? = null,
) {

    /** Current bearer requirement; null accepts unauthenticated requests. */
    @Volatile
    private var authToken: String? = authToken

    /**
     * One registered SSE client: a bounded send queue drained by a dedicated
     * writer thread. Producers never touch the socket; a full queue or a
     * failed write drops the client.
     */
    private class SseClient(val socket: Socket) {
        /** Bounded frame queue drained by the writer thread. */
        val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(SSE_QUEUE_CAPACITY)

        /** Set when the client is dropped; the writer thread exits on it. */
        val dead = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private companion object {
        /** Guards on start/stop state transitions. */
        private val LOCK = Any()

        /** Upper bound for one request line or header line. */
        private const val MAX_LINE_BYTES: Int = 8 * 1024

        /** Upper bound for header count per request. */
        private const val MAX_HEADERS: Int = 64

        /**
         * Upper bound for one request body (pixel arrays can be sizable).
         * 8 MB: the MCP layer caps inline pixel payloads at 4 M entries and
         * image bytes at 2 MB, so legitimate traffic stays far below; the
         * historical 32 MB pre-allocation was ~6 concurrent requests away
         * from killing an Android app heap before a single byte arrived.
         */
        private const val MAX_BODY_BYTES: Int = 8 * 1024 * 1024

        /** Read timeout while receiving the request head (ms). */
        private const val HEAD_TIMEOUT_MS: Int = 30_000

        /**
         * TOTAL wall-clock budget for one request (head + body): soTimeout
         * bounds each individual read(), so a 1-byte-per-29s drip kept a
         * handler thread "live" forever (64 drip connections starve the
         * whole IO pool — slow-loris). The deadline aborts the request.
         */
        private const val REQUEST_DEADLINE_MS: Long = 60_000

        /** Hard cap on simultaneously registered SSE clients. */
        private const val MAX_SSE_CLIENTS: Int = 32

        /** Bounded SSE send queue depth per client (frames). */
        private const val SSE_QUEUE_CAPACITY: Int = 128

        /** Hard cap on total in-flight connections (SSE + POST). */
        private const val MAX_CONNECTIONS: Int = 128

        /** Maximum trailer lines after the terminal chunk. */
        private const val MAX_TRAILER_LINES: Int = 64

        /** Idle SSE heartbeat interval (ms) keeping streams open across proxies. */
        private const val SSE_HEARTBEAT_MS: Long = 15_000
    }

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val clients = CopyOnWriteArrayList<SseClient>()

    @Volatile
    private var running = false

    /** In-flight connections (SSE + request handlers), for the global cap. */
    private val liveConnections = java.util.concurrent.atomic.AtomicInteger(0)

    /** Dedicated accept thread: immune to IO-pool starvation. */
    private var acceptThread: Thread? = null

    /**
     * Starts listening on [port] (0 = pick a free ephemeral port). A non-null
     * [token] replaces the auth requirement for this lifetime (null keeps
     * the constructor's setting).
     */
    fun start(port: Int, token: String? = null) {
        synchronized(LOCK) {
            if (running) throw IllegalStateException("HttpSseServer is already running on port ${currentPort()}")
            if (token != null) authToken = token
            val socket = ServerSocket(port, 64, InetAddress.getLoopbackAddress())
            serverSocket = socket
            running = true
            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = serverScope
            // A dedicated accept thread cannot be starved by connection
            // handlers occupying every Dispatchers.IO thread (the shared
            // pool tops out at 64; 65 slow connections used to wedge the
            // accept loop itself).
            acceptThread = Thread({ acceptLoop(socket) }, "http-sse-accept").apply {
                isDaemon = true
                start()
            }
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
            acceptThread = null
            for (client in clients) {
                killClient(client)
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
     * required by the SSE framing.
     *
     * The write is *queued*, never blocking: each client's dedicated writer
     * thread drains its bounded queue, and a client whose queue is full (it
     * stopped reading) or whose socket rejects the write is dropped. The
     * request path can therefore never stall behind a slow SSE consumer —
     * the historical synchronous flush held the POST handler's thread until
     * the client's TCP window drained.
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
            enqueueFrame(client, frame)
        }
    }

    /** Offers [frame] to [client]'s queue; drops the client when full or dead. */
    private fun enqueueFrame(client: SseClient, frame: ByteArray) {
        if (client.dead.get()) return
        if (!client.queue.offer(frame)) {
            killClient(client)
        }
    }

    /** Marks the client dead, removes it and closes its socket + writer. */
    private fun killClient(client: SseClient) {
        if (client.dead.compareAndSet(false, true)) {
            clients.remove(client)
            client.queue.clear()
            closeQuietly(client.socket)
        }
    }

    // ---- accept loop ------------------------------------------------------

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (error: IOException) {
                break // listening socket closed by stop()
            }
            // Global cap: counts SSE streams and one-shot requests alike;
            // over-cap sockets are closed immediately so slow-connection
            // floods cannot occupy unbounded threads.
            if (liveConnections.incrementAndGet() > MAX_CONNECTIONS) {
                liveConnections.decrementAndGet()
                closeQuietly(client)
                continue
            }
            // Between stop() cancelling the scope and the accept throwing,
            // one more connection can slip through: a null/cancelled scope
            // makes this launch a no-op and the accepted socket would never
            // be closed (leaked FD). The launch body's finally decrements
            // the live-connection counter either way.
            val active = scope?.takeIf { it.isActive }
            if (active == null) {
                liveConnections.decrementAndGet()
                closeQuietly(client)
                continue
            }
            active.launch {
                try {
                    handleConnection(client)
                } finally {
                    liveConnections.decrementAndGet()
                }
            }
        }
    }

    // ---- request handling -------------------------------------------------

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = HEAD_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return // client hung up immediately
            // Exactly three tokens and a known HTTP version: HTTP/0.9-style
            // two-token requests and unknown versions used to be served as
            // 1.1 silently.
            val parts = requestLine.trim().split(" +".toRegex())
            if (parts.size != 3 || (parts[2] != "HTTP/1.0" && parts[2] != "HTTP/1.1")) {
                writeStatus(socket, 400, "{\"error\":\"bad_request\"}")
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val headers = readHeaders(input) ?: run {
                writeStatus(socket, 400, "{\"error\":\"bad_request\"}")
                return
            }
            // Bearer-token gate (see the class KDoc): without it every app
            // on the device shares 127.0.0.1 and browser pages reach the
            // server through text/plain simple requests.
            if (!authorized(headers)) {
                writeStatus(socket, 401, "{\"error\":\"unauthorized\"}")
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
        } catch (error: Throwable) {
            // OOM and friends must not ride a connection handler into the
            // process default handler; the connection dies, the server
            // lives. Memory pressure here means a request hit a path the
            // size caps did not cover — 500 over a dropped socket.
            try {
                writeStatus(socket, 500, "{\"error\":\"internal_error\"}")
            } catch (ignored: IOException) {
                // Socket already unusable.
            }
        } finally {
            if (!isSseSocket(socket)) closeQuietly(socket)
        }
    }

    /** Bearer-token + (for POSTs) JSON content-type check. */
    private fun authorized(headers: Map<String, String>): Boolean {
        val token = authToken ?: return true
        val header = headers["authorization"] ?: return false
        val expected = "Bearer $token"
        return header.length == expected.length && constantTimeEquals(header, expected)
    }

    /** Comparison independent of the matching-prefix length. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
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
            val key = line.substring(0, separator).trim().lowercase()
            // Hop-by-hop framing headers may not repeat and may not combine
            // (RFC 9112 §6.3/§6.1 request smuggling vectors): a plain map
            // overwrite used to resolve duplicates last-wins silently.
            if (key == "content-length" || key == "transfer-encoding") {
                if (headers.containsKey(key) || (key == "content-length" && headers.containsKey("transfer-encoding")) ||
                    (key == "transfer-encoding" && headers.containsKey("content-length"))
                ) {
                    return null
                }
            }
            headers[key] = line.substring(separator + 1).trim()
        }
    }

    /** POST /messages (and /mcp): body in, JSON-RPC response out (HTTP + SSE). */
    private suspend fun handlePost(socket: Socket, input: InputStream, headers: Map<String, String>) {
        // CSRF defense: a browser "simple request" carries text/plain and
        // skips the CORS preflight entirely; only application/json is
        // accepted. MCP clients always send JSON.
        val contentType = headers["content-type"]
        if (contentType == null || !contentType.substringBefore(';').trim().equals("application/json", ignoreCase = true)) {
            writeStatus(socket, 415, "{\"error\":\"unsupported_media_type\"}")
            return
        }
        val body: String
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val rawLength = headers["content-length"]
        // Total request budget: per-read soTimeout alone let a 1-byte drip
        // hold the handler forever (see REQUEST_DEADLINE_MS).
        val deadline = System.currentTimeMillis() + REQUEST_DEADLINE_MS
        if (chunked) {
            honorExpect100Continue(socket, headers)
            body = readChunkedBody(input, deadline) ?: run {
                writeStatus(socket, 400, "{\"error\":\"bad_chunked_body\"}")
                return
            }
        } else {
            // RFC 9112 §6.3: a malformed or duplicated disagreeing
            // Content-Length must be rejected, not silently treated as 0
            // (which swallowed the request body).
            if (rawLength == null) {
                writeStatus(socket, 411, "{\"error\":\"length_required\"}")
                return
            }
            val length = rawLength.trim().toIntOrNull()
            if (length == null || length < 0 || length > MAX_BODY_BYTES) {
                writeStatus(socket, 400, "{\"error\":\"bad_request\"}")
                return
            }
            honorExpect100Continue(socket, headers)
            body = if (length == 0) "" else readBody(input, length, deadline) ?: run {
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
        // Registration, cap check AND a running re-check happen under one
        // lock: a stop() racing this registration used to leave a zombie
        // SSE client reading forever on a socket nobody owned.
        val registered = synchronized(LOCK) {
            if (!running || clients.size >= MAX_SSE_CLIENTS) {
                null
            } else {
                SseClient(socket).also { clients.add(it) }
            }
        }
        if (registered == null) {
            writeStatus(socket, 503, "{\"error\":\"too_many_sse_clients\"}")
            closeQuietly(socket)
            return
        }
        val client = registered
        socket.soTimeout = 0
        // Dedicated writer thread drains the bounded queue; write failures
        // (client gone, TCP window full) kill the client instead of ever
        // blocking a producer.
        Thread({
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
                while (true) {
                    val frame = client.queue.poll(1, java.util.concurrent.TimeUnit.SECONDS)
                    if (frame != null) {
                        out.write(frame)
                        out.flush()
                    }
                    if (client.dead.get()) break
                }
            } catch (error: InterruptedException) {
                // stop() or killClient interrupted the drain.
            } catch (error: IOException) {
                killClient(client)
            }
        }, "sse-writer").apply {
            isDaemon = true
            start()
        }
        enqueueFrame(client, "event: endpoint\ndata: /messages\n\n".toByteArray(StandardCharsets.UTF_8))
        // Hold the stream open until the client disconnects; discards any bytes.
        val heartbeat = scope?.launch {
            while (running) {
                kotlinx.coroutines.delay(SSE_HEARTBEAT_MS)
                // Idle comment frame keeps intermediaries from reaping the stream.
                if (!client.dead.get()) enqueueFrame(client, ": keepalive\n\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
        try {
            val discard = ByteArray(1024)
            while (running && !client.dead.get() && input.read(discard) != -1) {
                // Client input is ignored; the read only detects disconnects.
            }
        } catch (error: IOException) {
            // Disconnect: fall through to cleanup.
        } finally {
            heartbeat?.cancel()
            killClient(client)
        }
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
            401 -> "Unauthorized"
            404 -> "Not Found"
            411 -> "Length Required"
            415 -> "Unsupported Media Type"
            500 -> "Internal Server Error"
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

    /**
     * Reads one CRLF/LF-terminated line (UTF-8); null on EOF or oversized
     * line. A CR is only legal immediately before the LF terminator — a CR
     * anywhere else (the classic `Content-Length: 2\r6` request-smuggling
     * primitive) fails the line.
     */
    private fun readLine(input: InputStream): String? {
        var bytes = ByteArray(128)
        var count = 0
        var sawCr = false
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            if (b == '\r'.code) {
                if (sawCr) return null // CR CR — only one terminator CR is legal
                sawCr = true
                continue
            }
            if (sawCr) return null // CR followed by something other than LF
            if (count >= MAX_LINE_BYTES) return null
            if (count == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[count++] = b.toByte()
        }
        return String(bytes, 0, count, StandardCharsets.UTF_8)
    }

    /**
     * Reads exactly [length] bytes; null when the stream ends early.
     *
     * The buffer grows with the bytes that actually arrive (doubling, capped
     * at [MAX_BODY_BYTES]) — a `Content-Length: 8388608` header followed by
     * nothing used to allocate the full 8 MB before the first read.
     * Malformed UTF-8 fails the body rather than silently decoding to
     * U+FFFD replacement characters.
     */
    private fun readBody(input: InputStream, length: Int, deadline: Long = Long.MAX_VALUE): String? {
        val raw = readBodyBytes(input, length, deadline) ?: return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    /**
     * Decodes a `Transfer-Encoding: chunked` request body (RFC 9112 §7.1):
     * repeated `<hex-size>[;ext] CRLF data CRLF` chunks terminated by the
     * zero-size chunk and trailer section. Null on malformed framing or
     * when the reassembled body would exceed [MAX_BODY_BYTES].
     */
    private fun readChunkedBody(input: InputStream, deadline: Long = Long.MAX_VALUE): String? {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            if (System.currentTimeMillis() > deadline) return null
            val sizeLine = readLine(input) ?: return null
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: return null
            // Long-domain guard: an Int + Int sum overflows negative for
            // chunk headers like `7FFFFFFF` after a first small chunk, which
            // would slip past an Int comparison and attempt a ~2 GB
            // allocation in readBodyBytes below (OutOfMemoryError escapes the
            // Exception-based connection guard — a one-request DoS).
            if (size < 0 || out.size().toLong() + size.toLong() > MAX_BODY_BYTES) return null
            if (size == 0) {
                // Trailer section: consume lines until the blank terminator,
                // bounded — an attacker drip-feeding one trailer line per
                // 29 s used to pin the connection thread forever.
                var trailerLines = 0
                while (true) {
                    val trailer = readLine(input) ?: return null
                    if (trailer.isEmpty()) break
                    if (++trailerLines > MAX_TRAILER_LINES) return null
                }
                // Strict UTF-8 (identity-body semantics): lenient decoding
                // turned malformed sequences into U+FFFD replacement chars
                // that reached the JSON parser as garbage instead of being
                // rejected at the boundary.
                val decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                return try {
                    decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
                } catch (error: java.nio.charset.CharacterCodingException) {
                    null
                }
            }
            val chunk = readBodyBytes(input, size, deadline) ?: return null
            out.write(chunk)
            val crlf = readLine(input) ?: return null
            if (crlf.isNotEmpty()) return null
        }
    }

    /**
     * Reads exactly [length] raw bytes, allocating incrementally; null on
     * early EOF or when [deadline] (wall-clock millis) passes mid-read —
     * the slow-loris guard: each read() restarts the 30s soTimeout, so a
     * byte-per-29s drip otherwise pinned the handler thread forever.
     */
    private fun readBodyBytes(input: InputStream, length: Int, deadline: Long = Long.MAX_VALUE): ByteArray? {
        var bytes = ByteArray(minOf(length, 64 * 1024))
        var offset = 0
        while (offset < length) {
            if (System.currentTimeMillis() > deadline) return null
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read == -1) return null
            offset += read
            if (offset == bytes.size && offset < length) {
                bytes = bytes.copyOf(minOf(bytes.size.toLong() * 2, length.toLong()).toInt())
            }
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
