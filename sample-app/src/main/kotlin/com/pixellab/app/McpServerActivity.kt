package com.pixellab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.store.SlotStore
import com.pixellab.core.store.SlotSummary
import com.pixellab.mcp.PixelMcpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Default HTTP port of the sample MCP server. The WebSocket transport,
 * when enabled, binds [WebsocketPort] next to it.
 *
 * A fixed port (not ephemeral) keeps the host-side connection config
 * stable: an agent such as Android-Guru-Agent saves the URL once and
 * reconnects across app restarts.
 */
private const val ServerPort: Int = 8901

/** WebSocket transport port (JSON-RPC over RFC 6455 frames). */
private const val WebsocketPort: Int = 8902

/** Subdirectory of the app's private files dir backing persistence. */
private const val StoreDirName: String = "pixel-lab"

/**
 * On-device MCP host panel: boots the Pixel Lab JSON-RPC server with
 * **disk persistence** and shows everything an external agent needs to
 * connect.
 *
 * This is the second half of the host-integration story (the first half
 * is the gallery/editor in [AppRoot]): Android-Guru-Agent — or any
 * MCP-capable client on the same device — points at
 * `http://127.0.0.1:8901/mcp` and gains all 183 tools. The persistence
 * root is the **same** directory [AppRoot] uses, so art saved through the
 * agent (`session_save`) appears in the human gallery, and vice versa.
 *
 * Activity-scoped lifecycle: the server runs while this screen exists and
 * stops in [onDestroy]. That is the *sample* lifecycle — production hosts
 * should own the server from a foreground service (see
 * docs/HOST-INTEGRATION.md) so it survives navigation. A failed boot (a
 * port already held by another process, for instance) does not crash the
 * activity: the failure message is kept as Compose state and rendered by
 * the panel in the theme's error color.
 */
class McpServerActivity : ComponentActivity() {

    private var server: PixelMcpServer? = null

    /** Last start failure message, or null; snapshot state so the panel re-renders. */
    private var startError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    McpServerPanel(
                        persistenceRoot = File(filesDir, StoreDirName),
                        serverAccessor = { server },
                        onStart = { start() },
                        onStop = { stop() },
                        startError = startError,
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun start() {
        if (server != null) return
        startError = null
        val instance = PixelMcpServer(
            persistenceRoot = File(filesDir, StoreDirName),
        )
        try {
            instance.start(ServerPort, WebsocketPort)
            server = instance
        } catch (error: Throwable) {
            // A port already bound (or a transport that failed mid-boot)
            // must surface in the UI instead of killing the activity. The
            // handle is only published on success, so the panel's running
            // state stays truthful; a partially-booted instance tears
            // itself down inside PixelMcpServer.start's rollback.
            startError = error.message ?: error.javaClass.simpleName
        }
    }

    private fun stop() {
        server?.stop()
        server = null
    }
}

/**
 * Status + control panel for the sample MCP server.
 *
 * Pure presentation: the activity owns the server instance; the panel
 * reads state through [serverAccessor] (the running handle or null) and
 * the last [startError] (rendered in the theme error color in place of
 * the "Running" detail rows), and calls [onStart]/[onStop].
 *
 * @param persistenceRoot root the slot store lists sessions from.
 * @param serverAccessor reads the activity-owned running handle.
 * @param onStart boots the server (failures land in [startError]).
 * @param onStop stops a running server.
 * @param startError last boot failure message, or null.
 */
@Composable
fun McpServerPanel(
    persistenceRoot: File,
    serverAccessor: () -> PixelMcpServer?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    startError: String? = null,
) {
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    var running by remember { mutableStateOf(serverAccessor() != null) }

    // The toggle drives the activity-owned server; `running` re-reads the
    // real handle — which start() only publishes on success — so the
    // status card never shows a stale snapshot after a failed boot.
    fun flip(wantRunning: Boolean) {
        if (wantRunning) onStart() else onStop()
        running = serverAccessor() != null
    }

    // Slot listing moved OFF the main thread: the remember block ran
    // directory scans + JSON header reads on the UI thread (StrictMode
    // disk-read violation, jank on cold start with many slots).
    var slots by remember { mutableStateOf<List<SlotSummary>>(emptyList()) }
    LaunchedEffect(persistenceRoot, running) {
        slots = if (running || persistenceRoot.isDirectory) {
            withContext(Dispatchers.IO) {
                runCatching { SlotStore(persistenceRoot).list() }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pixel Lab · MCP Server", style = MaterialTheme.typography.headlineSmall)

        // ---- status card --------------------------------------------------

        Card(colors = CardDefaults.cardColors()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (running) "Running" else "Stopped",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = running,
                        onCheckedChange = { wantRunning -> flip(wantRunning) },
                    )
                    Icon(
                        if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (running) "Stop server" else "Start server",
                    )
                }
                if (startError != null) {
                    Text(
                        "Start failed: $startError",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (running) {
                    StatusRow("HTTP (JSON-RPC)", "127.0.0.1:$ServerPort · POST /mcp · GET /sse")
                    StatusRow("WebSocket", "127.0.0.1:$WebsocketPort · RFC 6455")
                    StatusRow("Persistence", persistenceRoot.absolutePath)
                } else {
                    Text(
                        "Flip the switch to boot the server with 183 tools and disk persistence.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ---- connect card -------------------------------------------------

        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connect from Android-Guru-Agent", style = MaterialTheme.typography.titleMedium)
                Text(
                    "In the host agent, add an MCP server (marketplace, mcp_servers.json import, " +
                        "or the mcp_connect tool in chat) with:",
                    style = MaterialTheme.typography.bodySmall,
                )
                val url = "http://127.0.0.1:$ServerPort/mcp"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        url,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy URL")
                    }
                }
                // Bearer token: regenerated on every start, so the agent
                // connection config must be re-supplied after each boot.
                val token = serverAccessor()?.authToken
                if (running && token != null) {
                    Text(
                        "Every request must carry the Authorization header generated at start:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Authorization: Bearer $token",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { clipboard.setText(AnnotatedString(token)) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy auth token")
                        }
                    }
                }
                Text(
                    "Example chat prompt for the agent: “连接 MCP 服务器 " + url +
                        "（请求头带 Authorization: Bearer <token>）然后列出可用工具”.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ---- saved slots card ---------------------------------------------

        Card {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Saved sessions (${slots.size})", style = MaterialTheme.typography.titleMedium)
                if (slots.isEmpty()) {
                    Text(
                        "Sessions saved through session_save land here and in the gallery — " +
                            "the agent and the human editor share one store.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    for (slot in slots.take(8)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(slot.name, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            Text(
                                "${slot.width}×${slot.height} · ${slot.frames}f" +
                                    if (slot.readable) "" else " · unreadable",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Text(
            "Sample lifecycle: the server stops when this screen closes. Production hosts " +
                "should run it from a foreground service (docs/HOST-INTEGRATION.md).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(0.35f))
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}
