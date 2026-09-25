package com.pixellab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.PixelPoint
import kotlin.math.roundToInt

/** Touch target of every zoom button, per the 44dp rule. */
private val ZoomButtonSize = 44.dp

/** Zoom factor of one step in/out. */
private const val ZoomStep = 1.5f

/** "Reset" zoom level, 100%. */
private const val ResetZoom = 1f

/** Corner brackets glyph of the fit-canvas button. */
private val FitGlyph = listOf(
    "XX...XX",
    "X.....X",
    ".......",
    ".......",
    ".......",
    "X.....X",
    "XX...XX",
)

/**
 * Zoom controls for [PixelCanvasPro]: zoom-out, live percentage readout,
 * zoom-in, reset-to-100% and fit-canvas buttons in one compact row.
 *
 * * zoom in/out steps the zoom by x[ZoomStep] / x1.5, clamped through
 *   [CanvasState.clampZoom] and reported via [onZoomChange];
 * * reset sets the zoom back to 100% (also through [onZoomChange]);
 * * fit invokes [onReset] — only the host knows the viewport, so it owns the
 *   fit computation (typically centering the frame and picking a zoom that
 *   shows the whole canvas, possibly resetting the pan too).
 *
 * All buttons use 44dp touch targets. The percentage label recomposes
 * automatically because [CanvasState.zoom] is snapshot state.
 *
 * @param state shared canvas view state; read for the current zoom and
 * clamping.
 * @param onZoomChange invoked with the new zoom for in/out/reset steps.
 * @param onReset invoked when the fit-canvas button is pressed.
 * @param modifier host modifier.
 */
@Composable
fun ZoomControls(
    state: CanvasState,
    onZoomChange: (Float) -> Unit = {},
    onReset: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = { onZoomChange(state.clampZoom(state.zoom / ZoomStep)) },
            modifier = Modifier.size(ZoomButtonSize),
        ) {
            Icon(
                imageVector = Icons.Filled.ZoomOut,
                contentDescription = "Zoom out",
                tint = scheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${(state.zoom * 100f).roundToInt()}%",
            fontSize = 12.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(
            onClick = { onZoomChange(state.clampZoom(state.zoom * ZoomStep)) },
            modifier = Modifier.size(ZoomButtonSize),
        ) {
            Icon(
                imageVector = Icons.Filled.ZoomIn,
                contentDescription = "Zoom in",
                tint = scheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = { onZoomChange(ResetZoom) },
            modifier = Modifier.size(ZoomButtonSize),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Reset zoom to 100%",
                tint = scheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onReset,
            modifier = Modifier.size(ZoomButtonSize),
        ) {
            PixelGlyphIcon(
                glyph = FitGlyph,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * A small pixel-art icon (see [CanvasToolbar]): the `'X'` cells of [glyph]
 * drawn as equal squares, tinted with [tint].
 */
@Composable
private fun PixelGlyphIcon(
    glyph: List<String>,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val points = ArrayList<PixelPoint>()
    for ((y, row) in glyph.withIndex()) {
        for ((x, ch) in row.withIndex()) {
            if (ch == 'X') points.add(PixelPoint(x, y))
        }
    }
    Canvas(modifier = modifier) {
        val cols = glyph.maxOf { it.length }
        val rows = glyph.size
        val cellW = size.width / cols
        val cellH = size.height / rows
        for (point in points) {
            drawRect(
                color = tint,
                topLeft = Offset(point.x * cellW, point.y * cellH),
                size = Size(cellW, cellH),
            )
        }
    }
}
