package com.pixellab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.PixelPoint
import com.pixellab.ui.theme.PixelTheme

/** Height of the status bar. */
private val BarHeight = 24.dp

/** Horizontal padding of the bar content. */
private val BarPadding = 8.dp

/** Radius of the unsaved-changes dot. */
private val ModifiedDotSize = 6.dp

/**
 * Bottom status bar: canvas dimensions, pointer cell, zoom percentage,
 * active layer and tool names, an unsaved-changes dot and a free-form
 * [extra] text slot.
 *
 * Truncation contract: every segment renders compact monospaced text;
 * segments are dropped from the *right* when the host sets [compact] (narrow
 * windows) — first [extra], then layer/tool, keeping coordinates and zoom,
 * which the status bar deems the most load-bearing. The host derives
 * `compact` from its width bucket (see [PixelEditorScaffold]); the bar
 * itself measures nothing.
 *
 * @param theme active theme.
 * @param dims canvas dimensions `(width, height)` in pixels.
 * @param cursor last reported cursor cell, or null (renders `—, —`).
 * @param zoom canvas zoom multiplier; rendered as a percentage.
 * @param activeLayer display name of the active layer, or null to hide.
 * @param toolName display name of the active tool, or null to hide.
 * @param projectModified renders the danger dot when true.
 * @param modifier host modifier.
 * @param extra free-form trailing text (export status, hints), or null.
 * @param compact drops the low-priority trailing segments; see above.
 */
@Composable
fun StatusBar(
    theme: PixelTheme,
    dims: Pair<Int, Int>,
    cursor: PixelPoint?,
    zoom: Float,
    activeLayer: String?,
    toolName: String?,
    projectModified: Boolean,
    modifier: Modifier = Modifier,
    extra: String? = null,
    compact: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(BarHeight)
            .background(theme.background)
            .padding(horizontal = BarPadding),
    ) {
        Segment(theme, "${dims.first}×${dims.second}")
        Segment(theme, cursorText(cursor))
        Segment(theme, "${(zoom * 100f + 0.5f).toInt()}%")
        if (!compact) {
            if (activeLayer != null) Segment(theme, activeLayer)
            if (toolName != null) Segment(theme, toolName)
            if (extra != null) Segment(theme, extra)
        }
        if (projectModified) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .size(ModifiedDotSize)
                    .background(theme.danger, RoundedCornerShape(ModifiedDotSize / 2f)),
            ) {}
        }
    }
}

/** Cursor segment: `(x, y)` inside the grid, `(—, —)` when null. */
private fun cursorText(cursor: PixelPoint?): String =
    if (cursor == null) "(—, —)" else "(${cursor.x}, ${cursor.y})"

/**
 * One monospaced status segment, separated by a leading middle dot from its
 * left neighbor (the first segment renders without the dot by host layout).
 */
@Composable
private fun Segment(theme: PixelTheme, text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = theme.textSecondary,
        fontFamily = FontFamily.Monospace,
    )
}
