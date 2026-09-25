package com.pixellab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.history.HistoryEntry
import com.pixellab.ui.theme.PixelTheme
import com.pixellab.ui.theme.argbColor

/** Height of one history row (44dp touch target). */
private val HistoryRowHeight = 44.dp

/** Maximum height of the scrollable history region. */
private val HistoryMaxHeight = 320.dp

/** Width of the custom scrollbar gutter. */
private val ScrollbarWidth = 3.dp

/** Radius of the unsaved-changes dot. */
private val DirtyDotSize = 8.dp

/** Diameter of the depth marker disc. */
private val DepthMarkerSize = 10.dp

/**
 * Undo/redo history browser.
 *
 * Shows the committed [HistoryEntry] list **newest first**, each as a 44dp
 * row: a depth marker, the command label and an elapsed `mm:ss` timestamp
 * (relative to the oldest retained entry — wall-clock stamps only exist for
 * display). Above the list sit the undo/redo transport buttons and the
 * unsaved-changes dot.
 *
 * *Jump-to-state*: tapping a row asks the host (via [onJumpTo]) to walk the
 * timeline so exactly [depth] entries end up applied — the host performs the
 * repeated undo/redo. Row depths are `0..entries.size` where `0` is the
 * "Original" (pre-history) state and `entries.size` is the newest state.
 * [selectedDepth] highlights the row matching the current position.
 *
 * Scroll region: the list lives in a max-[HistoryMaxHeight] window with a
 * custom-drawn scrollbar. The compile-only stub family exposes no
 * `LazyListState`, so the thumb encodes only the *visible fraction*
 * (`min(1, viewport/rows)`) pinned to the top — a visual affordance, not a
 * live offset (documented stub limitation).
 *
 * @param theme active theme.
 * @param entries committed entries, oldest first
 *   (as returned by [EditorSession.historyEntries]).
 * @param canUndo whether undo is currently possible.
 * @param canRedo whether redo is currently possible.
 * @param selectedDepth number of currently applied entries
 *   (`null` hides the highlight).
 * @param onUndo invoked for the undo transport button.
 * @param onRedo invoked for the redo transport button.
 * @param onJumpTo invoked with the target applied-entry count.
 * @param modified when true, renders the unsaved-changes dot.
 * @param modifier host modifier.
 */
@Composable
fun HistoryPanel(
    theme: PixelTheme,
    entries: List<HistoryEntry>,
    canUndo: Boolean,
    canRedo: Boolean,
    selectedDepth: Int?,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onJumpTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
    modified: Boolean = false,
) {
    val newestFirst = remember(entries) { entries.asReversed() }
    val baseTimestamp = remember(entries) { entries.firstOrNull()?.timestampMs ?: 0L }

    Column(modifier = modifier) {
        // ---- transport header -------------------------------------------
        Row(verticalAlignment = Alignment.Center) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(imageVector = Icons.Filled.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(imageVector = Icons.Filled.Redo, contentDescription = "Redo")
            }
            Text(
                text = "History (${entries.size})",
                modifier = Modifier.padding(horizontal = 4.dp),
                fontSize = 12.sp,
                color = theme.textPrimary,
            )
            if (modified) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(DirtyDotSize)
                        .background(theme.danger, RoundedCornerShape(DirtyDotSize / 2f)),
                ) {}
            }
        }

        // ---- scrollable list with custom scrollbar -------------------------
        Box(modifier = Modifier.heightIn(max = HistoryMaxHeight)) {
            LazyColumn {
                // "Original" pseudo-row: the state before the first entry.
                item(key = "history-original") {
                    HistoryRow(
                        theme = theme,
                        depth = 0,
                        label = "Original",
                        timestamp = null,
                        selected = selectedDepth == 0,
                        onClick = { onJumpTo(0) },
                    )
                }
                items(newestFirst.size, key = { i -> newestFirst[i].timestampMs * 31 + i }) { i ->
                    val entry = newestFirst[i]
                    val depth = entries.size - i
                    HistoryRow(
                        theme = theme,
                        depth = depth,
                        label = entry.command.label,
                        timestamp = formatElapsed(entry.timestampMs - baseTimestamp),
                        selected = selectedDepth == depth,
                        onClick = { onJumpTo(depth) },
                    )
                }
            }
            ScrollbarGutter(theme, rows = newestFirst.size + 1)
        }
    }
}

/**
 * One history row: depth marker disc, command label, `mm:ss` stamp,
 * primary ring when selected.
 */
@Composable
private fun HistoryRow(
    theme: PixelTheme,
    depth: Int,
    label: String,
    timestamp: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Center,
        modifier = Modifier
            .height(HistoryRowHeight)
            .padding(horizontal = ScrollbarWidth + 4.dp)
            .background(
                if (selected) theme.elevated else theme.background,
                RoundedCornerShape(4.dp),
            )
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) theme.primary else theme.background,
                shape = RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .size(DepthMarkerSize)
                .background(
                    if (selected) theme.primary else theme.accent,
                    RoundedCornerShape(DepthMarkerSize / 2f),
                ),
        ) {}
        Text(
            text = label,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            fontSize = 12.sp,
            color = if (selected) theme.textPrimary else theme.textSecondary,
        )
        Text(
            text = timestamp ?: "--:--",
            fontSize = 10.sp,
            color = theme.textDisabled,
        )
        Text(
            text = " $depth",
            fontSize = 10.sp,
            color = theme.textDisabled,
        )
    }
}

/**
 * Custom-drawn scrollbar gutter on the right edge of the list window. The
 * thumb height encodes the visible fraction of rows (top-pinned) — see the
 * [HistoryPanel] KDoc for the stub limitation.
 */
@Composable
private fun ScrollbarGutter(theme: PixelTheme, rows: Int) {
    Canvas(modifier = Modifier.size(ScrollbarWidth, HistoryMaxHeight)) {
        drawRect(color = theme.surface, size = Size(ScrollbarWidth.value, HistoryMaxHeight.value))
        val visibleFraction = (MaxVisibleRows.toFloat() / rows.coerceAtLeast(1)).coerceAtMost(1f)
        drawRect(
            color = theme.textDisabled,
            topLeft = Offset.Zero,
            size = Size(ScrollbarWidth.value, HistoryMaxHeight.value * visibleFraction),
        )
    }
}

/** Rows visible inside [HistoryMaxHeight] at 44dp each. */
private val MaxVisibleRows = (HistoryMaxHeight.value / HistoryRowHeight.value).toInt()

/**
 * Formats elapsed milliseconds as `mm:ss` (clamped at `99:59`).
 */
internal fun formatElapsed(deltaMs: Long): String {
    val totalSeconds = (deltaMs / 1000L).coerceIn(0L, 99L * 60L + 59L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
