package com.pixellab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pixellab.core.store.ProjectStore
import java.io.File

/** Subdirectory of the app's private files dir backing the store. */
private const val StoreDirName: String = "pixel-lab"

/**
 * The sample app's integration root: one [ProjectStore] wired into
 * [AppNav]'s gallery ↔ editor flow under a dark Material3 theme.
 *
 * This is the **primary integration path** for hosts: any activity of your
 * own can embed `AppRoot(ProjectStore(File(filesDir, "pixel-lab")))` and
 * gains the full gallery + PR10 editor experience without touching
 * [MainActivity] (which stays the frozen v1 demo). [EditorActivity] in
 * this package demonstrates the alternative path — a dedicated activity
 * that hosts nothing but [AppRoot].
 *
 * The store construction is deliberately *outside* `remember` in the
 * composable: hosts pass a configured instance in (the directory is an
 * integration decision, not a composition detail). The composable only
 * wires navigation; [Scaffold]'s content padding is consumed by the Box.
 */
@Composable
fun AppRoot(store: ProjectStore) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                AppNav(store = store)
            }
        }
    }
}

/**
 * Standalone demo activity hosting [AppRoot] — registered in the manifest
 * (non-exported, no launcher intent) so hosts can verify the integration
 * path with an explicit `adb shell am start` or embed [AppRoot] directly
 * in their own activity instead.
 *
 * The suppression exists only for the compile-only activity stub, whose
 * `onCreate` is — unlike the real androidx `ComponentActivity` — a final
 * method; against the real Android dependency this is a plain, standard
 * override.
 */
class EditorActivity : ComponentActivity() {

    @Suppress("OVERRIDING_FINAL_MEMBER")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current
            AppRoot(store = ProjectStore(File(context.getFilesDir(), StoreDirName)))
        }
    }
}
