package com.pixellab.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.core.store.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Canvas edge of a newly created gallery project. */
private const val NewProjectSize: Int = 32

/**
 * The two-screen navigation of the sample app: gallery ↔ editor.
 *
 * Navigation state is one nullable project id (no back stack — the editor
 * itself reports "leave" through [EditorScreen]'s unsaved-changes guard,
 * which is the only back edge that needs confirmation). Selecting a
 * gallery card (or creating a project) sets the id; leaving the editor
 * clears it and the gallery re-reads its summary list.
 *
 * `remember { mutableStateOf<String?>(null) }` keeps the id across
 * recompositions but not across process death — [ProjectStore] is the
 * durable state, the nav state is ephemeral by design.
 *
 * New projects are persisted off the main thread before navigation: a
 * failed save keeps the user in the gallery (nothing was created on disk)
 * instead of opening an editor for a phantom document.
 *
 * @param store the persistence root shared by both screens.
 */
@Composable
fun AppNav(store: ProjectStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openProjectId by remember { mutableStateOf<String?>(null) }
    val current = openProjectId
    if (current == null) {
        GalleryScreen(
            projectStore = store,
            onOpen = { id -> openProjectId = id },
            onNew = {
                val project = SpriteFactory.create(
                    "untitled",
                    NewProjectSize,
                    NewProjectSize,
                    BuiltInPalettes.PICO8,
                )
                // Disk write off the main thread; navigate only after it
                // lands, so the editor never opens a project id that does
                // not exist on disk (its load would only show the error
                // screen).
                scope.launch {
                    val saved = runCatching {
                        withContext(Dispatchers.IO) { store.save(project) }
                    }.isSuccess
                    if (saved) openProjectId = project.id
                }
            },
            onOpenMcp = {
                // The MCP panel owns its own activity so the server's
                // lifecycle (activity-scoped in the sample) is explicit.
                context.startActivity(
                    android.content.Intent(context, McpServerActivity::class.java),
                )
            },
        )
    } else {
        EditorScreen(
            projectStore = store,
            projectId = current,
            onClose = { openProjectId = null },
        )
    }
}
