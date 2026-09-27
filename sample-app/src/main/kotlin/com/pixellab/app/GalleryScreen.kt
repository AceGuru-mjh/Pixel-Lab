package com.pixellab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.io.ImageImporter
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.store.ProjectStore
import com.pixellab.ui.toImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Edge of the thumbnail box in the gallery cards. */
private val ThumbEdge = 48.dp

/** Outer padding of the gallery screen. */
private val ScreenPadding = 12.dp

/** Spacing between gallery cards. */
private val CardSpacing = 8.dp

/** Formatting of a summary's save timestamp. */
private val SaveDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

/**
 * The project browser of the sample app: every [ProjectStore.list] entry as
 * a card (thumbnail, name, dims, frame count, save date) plus a delete
 * confirmation dialog and the "New project" action.
 *
 * ## Data flow
 *
 * The summary list is (re)read from the store on every [refreshToken]
 * change — entering the gallery after an editor save shows fresh data
 * because [AppNav] re-composes [EditorScreen] into a *new* gallery visit.
 * The read runs off the main thread ([Dispatchers.IO]): null means "still
 * loading" (progress indicator), a failure shows an inline error line.
 * Thumbnails fetch their `.thumb.png` bytes per project id off the main
 * thread, decode through [ImageImporter] (PNG branch) once per content,
 * and render as a single nearest-neighbor [androidx.compose.ui.graphics.ImageBitmap]
 * blit — no per-pixel rects on this path.
 *
 * ## Deleting
 *
 * The trash button arms a confirm [AlertDialog] (the store delete is
 * irreversible); confirming drops the directory off the main thread and
 * refreshes the list. A failed delete keeps the list on screen with an
 * error line (the next successful reload clears it).
 *
 * @param projectStore the persistence root shared with [EditorScreen].
 * @param onOpen opens a project id in the editor.
 * @param onNew creates a fresh project and opens it.
 * @param onOpenMcp optional action opening the MCP host panel.
 */
@Composable
fun GalleryScreen(
    projectStore: ProjectStore,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onOpenMcp: (() -> Unit)? = null,
) {
    var refreshToken by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ProjectStore.ProjectSummary?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    // null = still loading; both errors reset on every refresh.
    var summaries by remember { mutableStateOf<List<ProjectStore.ProjectSummary>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshToken) {
        listError = null
        deleteError = null
        summaries = try {
            withContext(Dispatchers.IO) { projectStore.list() }
        } catch (error: Exception) {
            listError = error.message ?: "cannot read the project store"
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ScreenPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Pixel Lab Gallery", fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${summaries?.size ?: 0} project" +
                    if ((summaries?.size ?: 0) == 1) "" else "s",
                fontSize = 14.sp,
                color = Color.Gray,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (onOpenMcp != null) {
                OutlinedButton(onClick = onOpenMcp) {
                    Text(text = "MCP server")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Button(onClick = onNew) {
                Text(text = "New project")
            }
        }
        Spacer(modifier = Modifier.height(CardSpacing))

        // Non-fatal delete failure: the (stale) list stays on screen below
        // the message; the next successful reload clears it.
        deleteError?.let { message ->
            Text(
                text = "Delete failed: $message",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        val error = listError
        val loaded = summaries
        when {
            error != null -> Text(
                text = "Gallery error: $error",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 24.dp),
            )

            loaded == null -> Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 24.dp))
            }

            loaded.isEmpty() -> Text(
                text = "Nothing here yet — tap \"New project\" to start a 32x32 canvas.",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 24.dp),
            )

            else -> LazyColumn {
                items(count = loaded.size, key = { index -> loaded[index].id }) { index ->
                    val summary = loaded[index]
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(CardSpacing),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProjectThumb(
                                projectStore = projectStore,
                                id = summary.id,
                                modifier = Modifier.size(ThumbEdge),
                            )
                            Spacer(modifier = Modifier.width(CardSpacing))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = summary.name, fontSize = 16.sp)
                                Text(
                                    text = "${summary.width}x${summary.height} · " +
                                        "${summary.frameCount} frames · ${summary.layerCount} layers",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                )
                                Text(
                                    text = "saved " + SaveDateFormat.format(Date(summary.savedAtMs)),
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                )
                            }
                            TextButton(onClick = { onOpen(summary.id) }) {
                                Text(text = "Open")
                            }
                            TextButton(onClick = { pendingDelete = summary }) {
                                Text(text = "Delete")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(CardSpacing))
                }
            }
        }
    }

    pendingDelete?.let { summary ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = "Delete '${summary.name}'?") },
            text = { Text(text = "The project file, metadata and thumbnail are removed permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            val outcome = runCatching {
                                withContext(Dispatchers.IO) { projectStore.delete(summary.id) }
                            }
                            if (outcome.isFailure) {
                                // No refresh: the error line stays visible
                                // next to the (unchanged) list.
                                deleteError = outcome.exceptionOrNull()?.message ?: "unknown error"
                            } else {
                                refreshToken += 1
                            }
                        }
                    },
                ) {
                    Text(text = "Delete")
                }
            },
        )
    }
}

/**
 * Loads one `.thumb.png` payload off the main thread and renders it.
 *
 * The bytes are fetched once per project id ([remember]-keyed state, so
 * recomposition — including LazyColumn recycling — never re-reads the
 * file) and handed to the [ProjectThumb] that decodes and draws them.
 * While the read is in flight the fallback box below shows.
 */
@Composable
private fun ProjectThumb(projectStore: ProjectStore, id: String, modifier: Modifier = Modifier) {
    var bytes by remember(id) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(id) {
        bytes = try {
            withContext(Dispatchers.IO) { projectStore.thumbnail(id) }
        } catch (error: Exception) {
            // Missing/undecodable → the fallback box in the renderer.
            null
        }
    }
    ProjectThumb(bytes = bytes, modifier = modifier)
}

/**
 * Renders one decoded `.thumb.png` payload as a nearest-neighbor bitmap.
 *
 * Decoding happens once per content ([remember] keyed on the byte array
 * identity) and the [PixelFrame] converts through the shared,
 * content-keyed [com.pixellab.ui.toImageBitmap] cache. Missing or
 * undecodable thumbnails render the plain fallback box. The bitmap blits
 * into whatever size the modifier gives — the gallery requests a
 * [ThumbEdge] square.
 */
@Composable
private fun ProjectThumb(bytes: ByteArray?, modifier: Modifier = Modifier) {
    val frame = remember(bytes) {
        if (bytes == null || bytes.isEmpty()) null else decodeThumb(bytes)
    }
    val bitmap = remember(frame) { frame?.toImageBitmap() }
    val fallback = Color(0xFF2A2A33.toInt())
    Canvas(modifier = modifier) {
        if (bitmap == null) {
            drawRect(color = fallback)
            return@Canvas
        }
        drawImage(
            image = bitmap,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

/** PNG bytes → first [PixelFrame] (null on any decode failure). */
private fun decodeThumb(bytes: ByteArray): PixelFrame? = try {
    val imported = ImageImporter.importFrames(bytes)
    imported.frames.firstOrNull()
} catch (error: Exception) {
    null
}
