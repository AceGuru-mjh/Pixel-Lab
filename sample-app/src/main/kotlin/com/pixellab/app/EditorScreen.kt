package com.pixellab.app

import android.util.Log
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.store.ProjectStore
import com.pixellab.ui.PixelEditorScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outer padding of the editor screen. */
private val ScreenPadding = 8.dp

/** Spacing between the top bar actions. */
private val BarSpacing = 8.dp

/** Log tag of the ON_STOP draft saves. */
private const val DraftSaveTag = "EditorScreen"

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
 *  * **State survival**: the host activity pins `android:configChanges`
 *    (see the manifest), so rotation keeps this whole composition —
 *    document included — alive; the dirty flag is additionally
 *    [rememberSaveable] for the recreations we do not opt out of (dark
 *    mode, locale, process death). Backgrounding while dirty persists the
 *    document on the activity's ON_STOP (the [DisposableEffect] below), so
 *    a later process death cannot lose edits.
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
 * centered message with a back action instead of a crash — the load itself
 * runs off the main thread, with a progress indicator in the meantime.
 * Save failures (read-only volume) surface as a snackbar on the screen's
 * own host.
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

    // Saveable rather than plain remember: rotation is already covered by
    // the manifest's configChanges (the composition survives), and for the
    // recreations we do not opt out of (dark mode, locale, process death)
    // the flag travels with the Bundle, matching the re-loaded document.
    var dirty by rememberSaveable(projectId) { mutableStateOf(false) }
    var confirmLeave by remember(projectId) { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Disk IO stays off the main thread; the screen shows a progress
    // indicator until the document arrives.
    LaunchedEffect(projectId) {
        try {
            project = withContext(Dispatchers.IO) { projectStore.load(projectId) }
        } catch (error: Exception) {
            loadError = error.message ?: "cannot load project '$projectId'"
        }
    }

    // ---- unsaved-work safety net -------------------------------------------

    val lifecycleOwner = LocalLifecycleOwner.current

    // Persist dirty documents when the activity stops: ON_STOP always
    // precedes both destroy and the process death of a backgrounded
    // activity, so the in-memory document cannot vanish with it — the next
    // visit re-loads the saved bytes. Two deliberate trade-offs:
    //
    //  * The spec's ProcessLifecycleOwner needs the `lifecycle-process`
    //    artifact — a new dependency, out of bounds for this fix. Observing
    //    the activity's own lifecycle covers every state this screen can be
    //    in (it fills its host activity).
    //  * The save runs on a detached worker thread, not a coroutine: during
    //    teardown no structured scope of this composition is guaranteed to
    //    run the write to completion, and racing a scope-launched save could
    //    reorder two documents onto the same file (which is why the explicit
    //    save button below stays synchronous). Best-effort beats a dropped
    //    save; failures are logged — the explicit Save button still reports
    //    them through the snackbar.
    //
    // In-app "Leave without saving" stays honest: it navigates without
    // stopping the activity, so this observer never fires for it.
    DisposableEffect(lifecycleOwner, projectId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                val current = project
                if (dirty && current != null) {
                    Thread {
                        try {
                            projectStore.save(current)
                        } catch (error: Exception) {
                            Log.w(DraftSaveTag, "draft save failed for ${current.id}", error)
                        }
                    }.start()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

                    else -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
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
