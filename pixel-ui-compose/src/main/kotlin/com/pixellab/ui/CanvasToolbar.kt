package com.pixellab.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.PixelPoint
import kotlin.math.roundToInt

/** Side of the pixel-glyph icons, in dp. */
private val ToolGlyphSize = 18.dp

/** Minimum touch height of every chip and segment, per the 44dp rule. */
private val MinTouchHeight = 44.dp

/** Side of one symmetry segment button, per the 44dp rule. */
private val SegmentSize = 44.dp

/** Corner radius of swatches and segment buttons. */
private val SegmentCorner = 4.dp

/** Visual size of the animated brush-size preview square. */
private val BrushPreviewBox = 28.dp

/** Base side of the animated brush square at size 1, in dp units. */
private const val BrushPreviewBase = 6f

/** Screen-dp growth of the brush square per brush size step. */
private const val BrushPreviewStep = 2.2f

/** Pencil glyph: diagonal shaft with a tip at the bottom-left. */
private val PencilGlyph = listOf(
    "....XX.",
    "....XX.",
    "...XX..",
    "...XX..",
    "..XX...",
    "XX.....",
    "X......",
)

/** Eraser glyph: tilted block with motion strokes below. */
private val EraserGlyph = listOf(
    "...XXX.",
    "..XXXXX",
    "..XXXXX",
    ".XXXXX.",
    "XXXXX..",
    "XXX....",
    ".......",
)

/** Fill glyph: bucket body with a drip. */
private val FillGlyph = listOf(
    "...X...",
    "..XXX..",
    ".XXXXX.",
    "XXXXXXX",
    "XXXXXX.",
    ".XXXX..",
    "...X.X.",
)

/** Picker glyph: dropper with a round bulb on top. */
private val PickerGlyph = listOf(
    "....XX.",
    "...XXXX",
    "...XX..",
    "..XX...",
    "..XX...",
    ".XX....",
    "X......",
)

/** Line glyph: plain diagonal. */
private val LineGlyph = listOf(
    "......X",
    ".....X.",
    ".....X.",
    "....X..",
    "...X...",
    "..X....",
    "X......",
)

/** Rect glyph: square outline. */
private val RectGlyph = listOf(
    "XXXXXXX",
    "X.....X",
    "X.....X",
    "X.....X",
    "X.....X",
    "X.....X",
    "XXXXXXX",
)

/** Ellipse glyph: circular ring. */
private val EllipseGlyph = listOf(
    "..XXX..",
    ".X...X.",
    "X.....X",
    "X.....X",
    "X.....X",
    ".X...X.",
    "..XXX..",
)

/** Select glyph: dashed square (marching-ants marquee). */
private val SelectGlyph = listOf(
    "XX.XX.X",
    ".......",
    "X.....X",
    ".......",
    "X.....X",
    ".......",
    "XX.XX.X",
)

/** Move glyph: four chevrons pointing out from the center. */
private val MoveGlyph = listOf(
    "...X...",
    "..XXX..",
    ".X...X.",
    "XX...XX",
    ".X...X.",
    "..XXX..",
    "...X...",
)

/** Grid toggle glyph: fine grid. */
private val GridGlyph = listOf(
    "XXXXXXX",
    "X.X.X.X",
    "XXXXXXX",
    "X.X.X.X",
    "XXXXXXX",
    "X.X.X.X",
    "XXXXXXX",
)

/** Symmetry-off glyph: cross mark. */
private val SymmetryOffGlyph = listOf(
    "X.....X",
    ".X...X.",
    "..X.X..",
    "...X...",
    "..X.X..",
    ".X...X.",
    "X.....X",
)

/** Horizontal-mirror glyph: one dashed axis through the middle. */
private val SymmetryHGlyph = listOf(
    ".......",
    ".......",
    ".......",
    "XX.XX.X",
    ".......",
    ".......",
    ".......",
)

/** Vertical-mirror glyph: one dashed axis through the middle. */
private val SymmetryVGlyph = listOf(
    "...X...",
    "...X...",
    ".......",
    "...X...",
    "...X...",
    ".......",
    "...X...",
)

/** Four-way mirror glyph: both dashed axes. */
private val SymmetryFourWayGlyph = listOf(
    "...X...",
    "...X...",
    ".......",
    "XX.XX.X",
    ".......",
    "...X...",
    "...X...",
)

/** Short chip labels of the tools. */
private fun toolLabel(tool: DrawTool): String = when (tool) {
    DrawTool.PENCIL -> "Pen"
    DrawTool.ERASER -> "Erase"
    DrawTool.FILL -> "Fill"
    DrawTool.PICKER -> "Pick"
    DrawTool.LINE -> "Line"
    DrawTool.RECT -> "Rect"
    DrawTool.ELLIPSE -> "Ellipse"
    DrawTool.SELECT -> "Select"
    DrawTool.MOVE -> "Move"
}

/** 7x7 pixel glyph of each tool. */
private fun toolGlyph(tool: DrawTool): List<String> = when (tool) {
    DrawTool.PENCIL -> PencilGlyph
    DrawTool.ERASER -> EraserGlyph
    DrawTool.FILL -> FillGlyph
    DrawTool.PICKER -> PickerGlyph
    DrawTool.LINE -> LineGlyph
    DrawTool.RECT -> RectGlyph
    DrawTool.ELLIPSE -> EllipseGlyph
    DrawTool.SELECT -> SelectGlyph
    DrawTool.MOVE -> MoveGlyph
}

/** 7x7 pixel glyph of each symmetry mode. */
private fun symmetryGlyph(symmetry: CanvasSymmetry): List<String> = when (symmetry) {
    CanvasSymmetry.OFF -> SymmetryOffGlyph
    CanvasSymmetry.HORIZONTAL -> SymmetryHGlyph
    CanvasSymmetry.VERTICAL -> SymmetryVGlyph
    CanvasSymmetry.FOUR_WAY -> SymmetryFourWayGlyph
}

/**
 * Converts 7-row ASCII art rows into glyph cells: every `'X'` character maps
 * to one filled cell.
 */
private fun glyphPoints(rows: List<String>): List<PixelPoint> {
    val points = ArrayList<PixelPoint>()
    for ((y, row) in rows.withIndex()) {
        for ((x, ch) in row.withIndex()) {
            if (ch == 'X') points.add(PixelPoint(x, y))
        }
    }
    return points
}

/**
 * Compact toolbar for [PixelCanvasPro]: one horizontally scrollable row of
 * tool chips (one [FilterChip] per [DrawTool] entry, pixel-glyph icon plus
 * short label, 44dp touch height) with the grid toggle at its end, and one
 * second row with the brush-size slider (animated square preview + value
 * badge) and a segmented four-way symmetry selector.
 *
 * The toolbar reads and writes the shared [state] directly — chip selection
 * always mirrors [CanvasState.tool] — and additionally reports every change
 * through [onToolChange], [onBrushSizeChange], [onGridToggle] and
 * [onSymmetryChange] so hosts can persist or react to edits.
 *
 * @param state shared canvas view state.
 * @param onToolChange invoked with the newly selected tool.
 * @param onBrushSizeChange invoked with the new brush size (1..8).
 * @param onGridToggle invoked after the grid display flips.
 * @param onSymmetryChange invoked with the newly selected symmetry mode.
 * @param modifier host modifier.
 */
@Composable
fun CanvasToolbar(
    state: CanvasState,
    onToolChange: (DrawTool) -> Unit = {},
    onBrushSizeChange: (Int) -> Unit = {},
    onGridToggle: () -> Unit = {},
    onSymmetryChange: (CanvasSymmetry) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    Column(modifier = modifier) {
        // Row 1: tool chips + grid toggle, horizontally scrollable.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Center,
        ) {
            for (tool in DrawTool.entries) {
                val selected = state.tool == tool
                FilterChip(
                    selected = selected,
                    onClick = {
                        state.tool = tool
                        onToolChange(tool)
                    },
                    label = {
                        Row(verticalAlignment = Alignment.Center) {
                            PixelGlyph(
                                glyph = toolGlyph(tool),
                                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = toolLabel(tool),
                                fontSize = 12.sp,
                                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                            )
                        }
                    },
                    modifier = Modifier.heightIn(min = MinTouchHeight),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            val gridSelected = state.showGrid
            FilterChip(
                selected = gridSelected,
                onClick = {
                    state.showGrid = !state.showGrid
                    onGridToggle()
                },
                label = {
                    Row(verticalAlignment = Alignment.Center) {
                        PixelGlyph(
                            glyph = GridGlyph,
                            tint = if (gridSelected) scheme.primary else scheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Grid",
                            fontSize = 12.sp,
                            color = if (gridSelected) scheme.onSurface else scheme.onSurfaceVariant,
                        )
                    }
                },
                modifier = Modifier.heightIn(min = MinTouchHeight),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 2: brush size slider with animated square preview + badge,
        // then the segmented symmetry selector.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Center,
        ) {
            // Animated brush square: the side grows with the brush size.
            val previewSide = animateFloatAsState(
                targetValue = BrushPreviewBase + state.brushSize * BrushPreviewStep,
                label = "brushSizePreview",
            )
            Box(
                modifier = Modifier.size(BrushPreviewBox),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(previewSide.dp)
                        .background(scheme.primary, RoundedCornerShape(1.dp)),
                    content = {},
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${state.brushSize}px",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Slider(
                value = state.brushSize.toFloat(),
                onValueChange = { value ->
                    val size = value.roundToInt().coerceIn(1, 8)
                    state.brushSize = size
                    onBrushSizeChange(size)
                },
                valueRange = 1f..8f,
                steps = 6,
                modifier = Modifier
                    .weight(1f)
                    .height(MinTouchHeight)
                    .padding(horizontal = 4.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            SymmetrySelector(state = state, onSymmetryChange = onSymmetryChange)
        }
    }
}

/**
 * Segmented-button-style four-way selector for [CanvasSymmetry]: a row of
 * 44dp pixel-glyph segments; the selected one gets a container background
 * with a primary border.
 */
@Composable
private fun SymmetrySelector(
    state: CanvasState,
    onSymmetryChange: (CanvasSymmetry) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(SegmentCorner)
    Row(verticalAlignment = Alignment.Center) {
        for (symmetry in CanvasSymmetry.entries) {
            val selected = state.symmetry == symmetry
            Box(
                modifier = Modifier
                    .size(SegmentSize)
                    .background(
                        if (selected) scheme.secondaryContainer else scheme.surfaceVariant,
                        shape,
                    )
                    .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
                    .clickable {
                        state.symmetry = symmetry
                        onSymmetryChange(symmetry)
                    },
                contentAlignment = Alignment.Center,
            ) {
                PixelGlyph(
                    glyph = symmetryGlyph(symmetry),
                    tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A small pixel-art icon: the `'X'` cells of [glyph] drawn as equal squares
 * filling the available size, tinted with [tint].
 */
@Composable
private fun PixelGlyph(
    glyph: List<String>,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val points = remember(glyph) { glyphPoints(glyph) }
    Canvas(modifier = modifier.size(ToolGlyphSize)) {
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
