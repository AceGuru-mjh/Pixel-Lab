package com.pixellab.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.io.ImageImporter
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.store.ProjectStore
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
 * Thumbnails decode from the store's `.thumb.png` bytes through
 * [ImageImporter] (PNG branch) and render with a plain pixel-rect
 * [Canvas] — no bitmap factory needed on this path.
 *
 * ## Deleting
 *
 * The trash button arms a confirm [AlertDialog] (the store delete is
 * irreversible); confirming drops the directory and refreshes the list.
 *
 * @param projectStore the persistence root shared with [EditorScreen].
 * @param onOpen opens a project id in the editor.
 * @param onNew creates a fresh project and opens it.
 */
@Composable
fun GalleryScreen(
    projectStore: ProjectStore,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    var refreshToken by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<ProjectStore.ProjectSummary?>(null) }
    val summaries = remember(refreshToken) { projectStore.list() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ScreenPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Center,
        ) {
            Text(text = "Pixel Lab Gallery", fontSize = 20.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${summaries.size} project" + if (summaries.size == 1) "" else "s",
                fontSize = 14.sp,
                color = Color.Gray,
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onNew) {
                Text(text = "New project")
            }
        }
        Spacer(modifier = Modifier.height(CardSpacing))

        if (summaries.isEmpty()) {
            Text(
                text = "Nothing here yet — tap \"New project\" to start a 32x32 canvas.",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else {
            LazyColumn {
                items(count = summaries.size, key = { index -> summaries[index].id }) { index ->
                    val summary = summaries[index]
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(CardSpacing),
                            verticalAlignment = Alignment.Center,
                        ) {
                            ProjectThumb(
                                bytes = projectStore.thumbnail(summary.id),
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
                        projectStore.delete(summary.id)
                        pendingDelete = null
                        refreshToken += 1
                    },
                ) {
                    Text(text = "Delete")
                }
            },
        )
    }
}

/**
 * Renders one `.thumb.png` payload as a raw pixel-rect canvas.
 *
 * Decoding happens once per content ([remember] keyed on the byte array
 * identity); missing or undecodable thumbnails render a crossed box. The
 * pixels are drawn nearest-scaled into whatever size the modifier gives —
 * the gallery requests a [ThumbEdge] square.
 */
@Composable
private fun ProjectThumb(bytes: ByteArray?, modifier: Modifier = Modifier) {
    val frame = remember(bytes) {
        if (bytes == null || bytes.isEmpty()) null else decodeThumb(bytes)
    }
    val fallback = Color(0xFF2A2A33.toInt())
    Canvas(modifier = modifier) {
        if (frame == null) {
            drawRect(color = fallback)
            return@Canvas
        }
        val cellW = size.width / frame.width
        val cellH = size.height / frame.height
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val argb = frame[x, y]
                if (argb ushr 24 == 0) continue
                drawRect(
                    color = Color(argb),
                    topLeft = Offset(x * cellW, y * cellH),
                    size = Size(cellW + 0.5f, cellH + 0.5f),
                )
            }
        }
    }
}

/** PNG bytes → first [PixelFrame] (null on any decode failure). */
private fun decodeThumb(bytes: ByteArray): PixelFrame? = try {
    val imported = ImageImporter.importFrames(bytes)
    imported.frames.firstOrNull()
} catch (error: Exception) {
    null
}
