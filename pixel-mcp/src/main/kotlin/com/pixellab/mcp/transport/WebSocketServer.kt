package com.pixellab.mcp.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A frame-level protocol violation detected while decoding a client frame.
 *
 * The [code] is the RFC 6455 close status the server should echo back before
 * dropping the connection, e.g.:
 *
 *  * `1002` — protocol error (unmasked client frame, unknown opcode, stray
 *    continuation frame, reserved bits set, fragmented control frame);
 *  * `1003` — the server only accepts text payloads (binary rejected);
 *  * `1007` — payload is not valid UTF-8 once unmasked;
 *  * `1009` — reassembled message exceeds the 1 MB cap;
 *  * `1011` — the message handler itself failed (unexpected server error).
 *
 * Transport-level failures (EOF, reset) surface as plain [IOException]
 * instead — there is nobody left to answer a close frame.
 */
internal class WebSocketDecodeException(val code: Int, reason: String) : Exception(reason)

/**
 * Hand-rolled RFC 6455 WebSocket server — zero dependencies, one file.
 *
 * ## Scope and philosophy
 *
 * [HttpSseServer] covers the streamable-SSE MCP transport; this class adds
 * the WebSocket variant for hosts that prefer a persistent bidirectional
 * channel. Just like the SSE server it speaks only what the protocol
 * actually needs:
 *
 *  * **Handshake** — reads the HTTP request head (up to [MAX_HANDSHAKE_BYTES]
 *    bytes), requires `Upgrade: websocket` plus a 24-character base64
 *    `Sec-WebSocket-Key`, and answers `101 Switching Protocols` with
 *    `Sec-WebSocket-Accept = base64(SHA1(key + "258EAFA5-E914-47DA-95CA-
 *    C5AB0DC85B11"))` (RFC 6455 §4.2.2). Any non-WebSocket request gets a
 *    `400 Bad Request` and the socket is closed.
 *  * **Data frames** — text only. Binary frames are refused with close code
 *    `1003` (JSON-RPC never needs them). Client frames MUST be masked per
 *    RFC 6455 §5.1 — unmasked frames are refused with `1002`.
 *  * **Control frames** — `ping` is answered with a `pong` carrying the same
 *    (≤ 125 byte) payload; `pong` is ignored; `close` is echoed with the
 *    client's status code and the socket is closed.
 *  * **Fragmentation** — incoming fragmented messages (opcode 0
 *    continuations) are reassembled up to [MAX_MESSAGE_BYTES] (1 MB),
 *    larger messages are refused with `1009`. Outgoing replies larger than
 *    [OUTGOING_FRAGMENT_BYTES] (64 KB) are split into continuation frames.
 *  * **Lengths** — 7-bit, 16-bit and 64-bit payload lengths are all parsed;
 *    64-bit lengths that exceed [MAX_MESSAGE_BYTES] fail fast with `1009`
 *    before any allocation.
 *
 * ## Threading model
 *
 * One daemon thread per connection plus one daemon accept thread. The
 * constructor binds the [ServerSocket] and starts the accept loop
 * immediately (this matches the constructor signature `WebSocketServer(port,
 * handler, logger)` — unlike [HttpSseServer]'s `start(port)` lifecycle, the
 * port is fixed at construction so `port 0` can resolve through [port]).
 * [handler] runs on the connection's thread; replies are written under a
 * per-connection lock so a slow reader serializes behind its own TCP
 * backpressure without blocking other connections. [close] is idempotent:
 * it shuts the listening socket and every live connection down.
 *
 * The server binds the loopback address only (same policy as
 * [HttpSseServer]): it is an agent-local transport, not a public one.
 *
 * ## Handshake worked example (RFC 6455 §1.3, verified by the smoke suite)
 *
 * ```
 * C→S: GET / HTTP/1.1
 *      Host: localhost
 *      Upgrade: websocket
 *      Connection: Upgrade
 *      Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
 *      Sec-WebSocket-Version: 13
 *
 * S→C: HTTP/1.1 101 Switching Protocols
 *      Upgrade: websocket
 *      Connection: Upgrade
 *      Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xUo=
 * ```
 *
 * SHA-1 via [java.security.MessageDigest] and base64 via [java.util.Base64]
 * are the only JCA pieces used — no TLS, no extension negotiation
 * (`Sec-WebSocket-Extensions` is ignored), no subprotocol enforcement
 * (`Sec-WebSocket-Protocol` is not inspected).
 */
class WebSocketServer(
    port: Int,
    private val handler: (String) -> String?,
    private val logger: ((String) -> Unit)? = null,
) {

    /**
     * One live client connection: its socket, streams and write lock.
     *
     * All reads happen on the connection thread; writes from *any* thread
     * (a pong, a reply, a close) synchronize on [writeLock] so frame bytes
     * never interleave.
     */
    private class Connection(
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream,
        val id: Int,
    ) {
        val writeLock = Any()
    }

    /** A single decoded frame header + already-unmasked payload. */
    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private companion object {
        /** RFC 6455 §1.3: the magic GUID appended to the client key. */
        private const val WEBSOCKET_GUID: String = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Upper bound of the HTTP request head (up to the blank line). */
        private const val MAX_HANDSHAKE_BYTES: Int = 16 * 1024

        /** Upper bound of one reassembled text message (1 MB). */
        private const val MAX_MESSAGE_BYTES: Int = 1024 * 1024

        /** Outgoing messages above this size are split into continuations. */
        private const val OUTGOING_FRAGMENT_BYTES: Int = 64 * 1024

        /** Header read timeout while waiting for the handshake (ms). */
        private const val HANDSHAKE_TIMEOUT_MS: Int = 30_000

        /** Read timeout for idle established connections (ms). */
        private const val IDLE_TIMEOUT_MS: Int = 0 // 0 = forever, RFC keeps WS open

        /** Maximum decoded close reason echoed back / logged. */
        private const val MAX_CLOSE_REASON_BYTES: Int = 123

        /** Opcodes (RFC 6455 §5.2). */
        private const val OP_CONTINUATION: Int = 0x0
        private const val OP_TEXT: Int = 0x1
        private const val OP_BINARY: Int = 0x2
        private const val OP_CLOSE: Int = 0x8
        private const val OP_PING: Int = 0x9
        private const val OP_PONG: Int = 0xA
    }

    private val serverSocket: ServerSocket = ServerSocket(port, 64, InetAddress.getLoopbackAddress())
    private val connections = CopyOnWriteArrayList<Connection>()
    private val closed = AtomicBoolean(false)
    private val nextConnectionId = AtomicInteger(0)

    init {
        val acceptThread = Thread({ acceptLoop() }, "pixel-ws-accept-${serverSocket.localPort}")
        acceptThread.isDaemon = true
        acceptThread.start()
        logger?.invoke("listening on loopback port ${serverSocket.localPort}")
    }

    /** The bound port (constructor port 0 resolves to the ephemeral port). */
    val port: Int get() = serverSocket.localPort

    /** True until [close] shuts the accept loop down. */
    val isRunning: Boolean get() = !closed.get()

    /** Number of connections currently in the message loop. */
    val connectionCount: Int get() = connections.size

    /**
     * Shuts the server down: the accept loop terminates (its
     * [ServerSocket] closes), every live connection is closed and future
     * connects are refused. Safe to call repeatedly; does not wait for the
     * per-connection threads to drain (they are daemons dying with their
     * sockets).
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeQuietly(serverSocket)
        for (connection in connections) {
            closeQuietly(connection.socket)
        }
        connections.clear()
        logger?.invoke("closed")
    }

    // ---- accept loop ------------------------------------------------------

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (error: IOException) {
                break // listening socket closed by close()
            }
            if (closed.get()) {
                closeQuietly(socket)
                break
            }
            val id = nextConnectionId.incrementAndGet()
            val thread = Thread({ serve(socket, id) }, "pixel-ws-conn-$id")
            thread.isDaemon = true
            thread.start()
        }
    }

    // ---- per-connection lifecycle -----------------------------------------

    /** Handshake, then the frame message loop, then cleanup. */
    private fun serve(socket: Socket, id: Int) {
        val connection: Connection
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            if (!handshake(socket, input, output)) {
                closeQuietly(socket)
                return
            }
            socket.soTimeout = IDLE_TIMEOUT_MS
            connection = Connection(socket, input, output, id)
            connections.add(connection)
            logger?.invoke("conn#$id: handshake accepted")
            messageLoop(connection)
            // messageLoop only returns for a close handshake — the peer
            // expects the TCP half-close to follow immediately.
            closeQuietly(socket)
        } catch (error: WebSocketDecodeException) {
            // Protocol violation: answer with the close code, then drop.
            logger?.invoke("conn#$id: decode error ${error.code}: ${error.message}")
            try {
                writeCloseFrame(socket, error.code, error.message ?: "")
            } catch (ignored: IOException) {
                // Peer already gone.
            }
            closeQuietly(socket)
        } catch (error: IOException) {
            // Client vanished mid-handshake or mid-message: nothing to answer.
            logger?.invoke("conn#$id: i/o error: ${error.message}")
            closeQuietly(socket)
        } catch (error: Exception) {
            // Unexpected server bug: best-effort 1011 close.
            logger?.invoke("conn#$id: internal error: ${error.message}")
            try {
                writeCloseFrame(socket, 1011, "internal error")
            } catch (ignored: IOException) {
                // Already unusable.
            }
            closeQuietly(socket)
        } finally {
            connections.removeAll { it.id == id }
        }
    }

    // ---- handshake ---------------------------------------------------------

    /**
     * Reads the request head and performs the opening handshake.
     *
     * @return true when the upgrade succeeded (101 written); false when the
     *   request was refused with a 400 (not a WebSocket request) — the
     *   caller closes the socket either way.
     */
    private fun handshake(socket: Socket, input: InputStream, output: OutputStream): Boolean {
        val head = readUntilBlankLine(input) ?: throw WebSocketDecodeException(
            1002,
            "request head truncated or exceeds $MAX_HANDSHAKE_BYTES bytes",
        )
        val text = String(head, StandardCharsets.UTF_8)
        val lines = text.split("\r\n").map { it.trim() }
        val requestLine = lines.firstOrNull() ?: ""
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        val method = requestLine.split(" ").firstOrNull()?.uppercase() ?: ""
        val upgrade = headers["upgrade"]?.lowercase() ?: ""
        val key = headers["sec-websocket-key"] ?: ""

        if (method != "GET" || !upgrade.contains("websocket") || !isWebSocketKey(key)) {
            val reason = when {
                method != "GET" -> "method_not_get"
                !upgrade.contains("websocket") -> "missing_upgrade"
                else -> "missing_or_bad_sec_websocket_key"
            }
            writeHttpResponse(
                output,
                "HTTP/1.1 400 Bad Request\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${reason.length}\r\n" +
                    "Connection: close\r\n\r\n$reason",
            )
            logger?.invoke("handshake refused: $reason")
            return false
        }

        val accept = acceptKey(key)
        writeHttpResponse(
            output,
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n" +
                "\r\n",
        )
        return true
    }

    /** RFC 6455 §4.2.2: exactly 24 base64 characters (16 decoded bytes; `=` padding allowed). */
    private fun isWebSocketKey(key: String): Boolean {
        if (key.length != 24) return false
        return key.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
        }
    }

    /** `base64(SHA1(key + GUID))` — RFC 6455 §1.3's worked example is the test vector. */
    private fun acceptKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val hashed = digest.digest((key + WEBSOCKET_GUID).toByteArray(StandardCharsets.US_ASCII))
        return Base64.getEncoder().encodeToString(hashed)
    }

    /** Reads bytes until the first `\r\n\r\n`, capped at [MAX_HANDSHAKE_BYTES]. */
    private fun readUntilBlankLine(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(256)
        var window = 0 // rolling 4-byte tail; shl drops the high bits naturally
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (buffer.size() >= MAX_HANDSHAKE_BYTES) return null
            buffer.write(b)
            window = (window shl 8) or (b and 0xFF)
            if (window == 0x0D0A0D0A) return buffer.toByteArray() // \r\n\r\n
        }
    }

    // ---- message loop -------------------------------------------------------

    /**
     * The frame pump: decodes frames, reassembles fragmented text messages,
     * answers pings, echoes closes and feeds complete text messages to
     * [handler]. The reply (non-null) goes out as one (possibly fragmented)
     * text frame.
     */
    private fun messageLoop(connection: Connection) {
        var messageOpcode = -1 // -1 = no fragmented message in progress
        val fragments = ByteArrayOutputStream(1024)
        while (true) {
            val frame = readFrame(connection.input)

            // Control frames never fragment and stay <= 125 payload bytes.
            when (frame.opcode) {
                OP_CLOSE -> {
                    val (code, reason) = parseClosePayload(frame.payload)
                    logger?.invoke("conn#${connection.id}: close $code '$reason'")
                    sendClose(connection, if (code == null) 1000 else code)
                    return
                }

                OP_PING -> {
                    sendPong(connection, frame.payload)
                    continue
                }

                OP_PONG -> continue // unsolicited pongs are ignored

                OP_TEXT, OP_BINARY, OP_CONTINUATION -> Unit // handled below
                else -> throw WebSocketDecodeException(1002, "unknown opcode 0x${frame.opcode.toString(16)}")
            }

            when (frame.opcode) {
                OP_BINARY -> throw WebSocketDecodeException(1003, "binary frames are not accepted (text only)")

                OP_TEXT -> {
                    if (messageOpcode != -1) {
                        throw WebSocketDecodeException(1002, "new data frame while a fragmented message is open")
                    }
                    if (frame.fin) {
                        deliver(connection, frame.payload)
                    } else {
                        messageOpcode = OP_TEXT
                        fragments.reset()
                        fragments.write(frame.payload)
                    }
                }

                OP_CONTINUATION -> {
                    if (messageOpcode == -1) {
                        throw WebSocketDecodeException(1002, "continuation frame without an open message")
                    }
                    fragments.write(frame.payload)
                    if (fragments.size() > MAX_MESSAGE_BYTES) {
                        throw WebSocketDecodeException(1009, "message exceeds $MAX_MESSAGE_BYTES bytes")
                    }
                    if (frame.fin) {
                        val message = fragments.toByteArray()
                        fragments.reset()
                        messageOpcode = -1
                        deliver(connection, message)
                    }
                }
            }
        }
    }

    /** Decodes one complete message and dispatches the reply. */
    private fun deliver(connection: Connection, payload: ByteArray) {
        if (payload.size > MAX_MESSAGE_BYTES) {
            throw WebSocketDecodeException(1009, "message exceeds $MAX_MESSAGE_BYTES bytes")
        }
        val message = decodeUtf8(payload)
        logger?.invoke("conn#${connection.id}: -> ${message.length} chars")
        val reply = try {
            handler(message)
        } catch (error: Exception) {
            logger?.invoke("conn#${connection.id}: handler failed: ${error.message}")
            sendClose(connection, 1011, "handler error")
            // RFC 6455 §5.5.1: after sending a close frame the endpoint MUST
            // NOT send any further data frames — terminate the pump so the
            // connection cannot linger half-closed.
            throw IOException("connection closed after handler error")
        }
        if (reply != null) {
            logger?.invoke("conn#${connection.id}: <- ${reply.length} chars")
            sendText(connection, reply)
        }
    }

    /** Strict UTF-8 decode (invalid sequences → close 1007). */
    private fun decodeUtf8(payload: ByteArray): String {
        val charset = StandardCharsets.UTF_8
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(payload)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw WebSocketDecodeException(1007, "payload is not valid UTF-8")
        }
    }

    // ---- frame decoding ------------------------------------------------------

    /**
     * Reads exactly one frame, unmasking its payload.
     *
     * Layout (RFC 6455 §5.2):
     * ```
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-------+-+-------------+-------------------------------+
     * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
     * |I|S|S|S|  (4)  |A|     (7)     |            (16/64)            |
     * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
     * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - - +
     * |    Masking-key (0 or 4 bytes)  |    Masked payload data       |
     * ```
     *
     * @throws WebSocketDecodeException for every protocol violation listed
     *   in that class's KDoc (mask required, reserved bits, control frame
     *   fragmentation, oversized payloads …).
     * @throws IOException when the peer disconnects mid-frame.
     */
    private fun readFrame(input: InputStream): Frame {
        val header = readExactly(input, 2)
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF

        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        if ((b0 and 0x70) != 0) {
            throw WebSocketDecodeException(1002, "reserved bits (RSV1-3) must be zero")
        }

        val masked = (b1 and 0x80) != 0
        if (!masked) {
            throw WebSocketDecodeException(1002, "client frames must be masked (RFC 6455 §5.1)")
        }
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = readExactly(input, 2).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        } else if (length == 127L) {
            length = readExactly(input, 8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            if (length < 0) {
                throw WebSocketDecodeException(1002, "64-bit length has the high bit set")
            }
        }

        val isControl = opcode >= OP_CLOSE
        if (isControl) {
            if (!fin) {
                throw WebSocketDecodeException(1002, "control frames must not be fragmented")
            }
            if (length > 125) {
                throw WebSocketDecodeException(1002, "control frame payload exceeds 125 bytes")
            }
        }
        if (length > MAX_MESSAGE_BYTES) {
            throw WebSocketDecodeException(1009, "frame payload exceeds $MAX_MESSAGE_BYTES bytes")
        }

        val maskKey = readExactly(input, 4)
        val payload = readExactly(input, length.toInt())
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
        }
        return Frame(fin, opcode, payload)
    }

    /** Reads exactly [count] bytes; EOF mid-read is an [IOException]. */
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read == -1) throw IOException("stream ended mid-frame")
            offset += read
        }
        return out
    }

    /** Close payload: 2-byte status (optional) + UTF-8 reason (optional). */
    private fun parseClosePayload(payload: ByteArray): Pair<Int?, String> {
        if (payload.size == 1) throw WebSocketDecodeException(1002, "close payload of 1 byte is invalid")
        if (payload.isEmpty()) return null to ""
        val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        val reasonBytes = payload.copyOfRange(2, payload.size)
        val reason = reasonBytes.copyOf(minOf(reasonBytes.size, MAX_CLOSE_REASON_BYTES))
            .toString(StandardCharsets.UTF_8)
        return code to reason
    }

    // ---- frame writing -----------------------------------------------------

    /** Sends one text message, fragmenting payloads above 64 KB. */
    private fun sendText(connection: Connection, text: String) {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        if (payload.size <= OUTGOING_FRAGMENT_BYTES) {
            writeFrame(connection, fin = true, opcode = OP_TEXT, payload = payload)
            return
        }
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val chunk = minOf(OUTGOING_FRAGMENT_BYTES, payload.size - offset)
            val slice = payload.copyOfRange(offset, offset + chunk)
            val last = offset + chunk >= payload.size
            writeFrame(connection, fin = last, opcode = if (first) OP_TEXT else OP_CONTINUATION, payload = slice)
            first = false
            offset += chunk
        }
    }

    /** Pong mirrors the ping payload (RFC 6455 §5.5.3). */
    private fun sendPong(connection: Connection, payload: ByteArray) {
        writeFrame(connection, fin = true, opcode = OP_PONG, payload = payload)
    }

    /** Close frame with [code] + reason, then the caller drops the socket. */
    private fun sendClose(connection: Connection, code: Int, reason: String = "") {
        val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)
            .copyOf(minOf(reason.toByteArray(StandardCharsets.UTF_8).size, MAX_CLOSE_REASON_BYTES))
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((code shr 8) and 0xFF).toByte()
        payload[1] = (code and 0xFF).toByte()
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.size)
        writeFrame(connection, fin = true, opcode = OP_CLOSE, payload = payload)
    }

    /** Writes one unmasked server frame under the connection write lock. */
    private fun writeFrame(connection: Connection, fin: Boolean, opcode: Int, payload: ByteArray) {
        val header = ByteArrayOutputStream(10)
        header.write((if (fin) 0x80 else 0x00) or opcode)
        when {
            payload.size <= 125 -> header.write(payload.size)
            payload.size <= 0xFFFF -> {
                header.write(126)
                header.write((payload.size shr 8) and 0xFF)
                header.write(payload.size and 0xFF)
            }

            else -> {
                header.write(127)
                var remaining = payload.size.toLong()
                repeat(8) {
                    header.write(((remaining shr 56) and 0xFF).toInt())
                    remaining = remaining shl 8
                }
            }
        }
        synchronized(connection.writeLock) {
            connection.output.write(header.toByteArray())
            connection.output.write(payload)
            connection.output.flush()
        }
    }

    /** Best-effort close frame on a raw socket (decode error path). */
    private fun writeCloseFrame(socket: Socket, code: Int, reason: String) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val connection = Connection(socket, socket.getInputStream(), out, -1)
        sendClose(connection, code, reason)
    }

    /** Writes a complete HTTP response head/body and flushes it. */
    private fun writeHttpResponse(output: OutputStream, response: String) {
        output.write(response.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    /** Best-effort socket close. */
    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (ignored: IOException) {
            // Cleanup only.
        }
    }

    private fun closeQuietly(socket: ServerSocket?) {
        try {
            socket?.close()
        } catch (ignored: IOException) {
            // Cleanup only.
        }
    }
}
