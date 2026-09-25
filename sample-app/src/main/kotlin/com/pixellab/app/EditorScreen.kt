package com.pixellab.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.store.ProjectStore
import com.pixellab.ui.PixelEditorScaffold
import kotlinx.coroutines.launch

/** Outer padding of the editor screen. */
private val ScreenPadding = 8.dp

/** Spacing between the top bar actions. */
private val BarSpacing = 8.dp

/**
 * The file-backed editing screen of the sample app: a [PixelEditorScaffold]
 * (the whole PR10 editor) whose document is loaded from — and explicitly
 * saved back to — a [ProjectStore].
 *
 * ## State ownership
 *
 *  * The **document** lives in a `mutableStateOf` seeded by
 *    [ProjectStore.load]; every scaffold mutation flows back through
 *    `onProjectChange`, which mirrors the project and raises the dirty
 *    flag. The scaffold's *internal* [com.pixellab.ui.EditorSession]
 *    (undo/redo/coalescing) is owned by the scaffold itself — this screen
 *    never touches it.
 *  * **Saving** is explicit: the top-bar button (and the scaffold's own
 *    Ctrl+S hook, wired through `onSave`) call [ProjectStore.save] with the
 *    current project and clear the flag. No auto-save on every stroke —
 *    atomic renames make each save a full document write.
 *  * **Leaving** while dirty arms a confirm dialog (the same "unsaved
 *    changes" guard every desktop editor has); a clean back navigates
 *    immediately.
 *
 * ## Error handling
 *
 * Load failures (corrupt document, missing directory) surface as a
 * centered message with a back action instead of a crash; save failures
 * (read-only volume) surface as a snackbar on the screen's own host.
 *
 * @param projectStore the persistence root shared with [GalleryScreen].
 * @param projectId the id of the directory under `projects/`.
 * @param onClose leaves the editor (back to the gallery).
 */
@Composable
fun EditorScreen(
    projectStore: ProjectStore,
    projectId: String,
    onClose: () -> Unit,
) {
    var loadError by remember(projectId) { mutableStateOf<String?>(null) }
    var project by remember(projectId) { mutableStateOf<SpriteProject?>(null) }
    var dirty by remember(projectId) { mutableStateOf(false) }
    var confirmLeave by remember(projectId) { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (project == null && loadError == null) {
        try {
            project = projectStore.load(projectId)
        } catch (error: Exception) {
            loadError = error.message ?: "cannot load project '$projectId'"
        }
    }

    /** Persists the current project and clears the dirty flag. */
    fun saveCurrent(current: SpriteProject) {
        try {
            projectStore.save(current)
            dirty = false
            scope.launch { snackbarHostState.showSnackbar("Saved ${current.name}") }
        } catch (error: Exception) {
            scope.launch { snackbarHostState.showSnackbar("Save failed: ${error.message}") }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val loaded = project
                val error = loadError
                when {
                    error != null -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(ScreenPadding),
                    ) {
                        Text(text = "Cannot open '$projectId'", fontSize = 18.sp)
                        Text(text = error, fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(BarSpacing))
                        TextButton(onClick = onClose) {
                            Text(text = "Back to gallery")
                        }
                    }

                    loaded != null -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(ScreenPadding),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { if (dirty) confirmLeave = true else onClose() }) {
                                Text(text = "< Gallery")
                            }
                            Spacer(modifier = Modifier.width(BarSpacing))
                            Text(
                                text = loaded.name + if (dirty) " •" else "",
                                fontSize = 16.sp,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = { saveCurrent(loaded) },
                                enabled = dirty,
                            ) {
                                Text(text = if (dirty) "Save" else "Saved")
                            }
                        }
                        PixelEditorScaffold(
                            project = loaded,
                            onProjectChange = { next ->
                                project = next
                                dirty = true
                            },
                            onSave = { saveCurrent(loaded) },
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                        )
                    }

                    else -> Text(
                        text = "Loading…",
                        modifier = Modifier.padding(ScreenPadding),
                    )
                }
                SnackbarHost(hostState = snackbarHostState)
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(text = "Unsaved changes") },
            text = { Text(text = "Leave '${project?.name ?: projectId}' without saving?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLeave = false
                        onClose()
                    },
                ) {
                    Text(text = "Leave")
                }
            },
        )
    }
}
