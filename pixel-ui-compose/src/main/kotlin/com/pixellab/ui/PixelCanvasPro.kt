package com.pixellab.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Screen size of one canvas pixel at zoom 1; `cellSize = 12 * zoom`. */
private const val BasePixelCellSize = 12f

/** Screen checkerboard tile (in screen px) below [PerPixelCheckerZoom]. */
private const val ScreenCheckerTile = 8f

/** Zoom at which the checkerboard switches to one tile per canvas pixel. */
private const val PerPixelCheckerZoom = 6f

/** Zoom at which the per-pixel grid appears (when grid display is on). */
private const val GridZoomThreshold = 4f

/** Alpha of the grid gray, the only literal color in this file. */
private const val GridAlpha = 0.3f

/** Draw alpha of the onion-skin frame before the active one. */
private const val OnionPreviousAlpha = 0.4f

/** Draw alpha of the onion-skin frame after the active one. */
private const val OnionNextAlpha = 0.25f

/** Draw alpha of stroke-preview blocks. */
private const val StrokePreviewAlpha = 0.5f

/** Draw alpha of shape-tool preview blocks. */
private const val ShapePreviewAlpha = 0.85f

/** Draw alpha of the symmetry guide dashes. */
private const val SymmetryAxisAlpha = 0.6f

/** Draw alpha of the cursor crosshair lines. */
private const val CrosshairAlpha = 0.35f

/** Max time between taps of a double tap. */
private const val DoubleTapTimeoutMillis = 280L

/** Max screen distance between taps of a double tap. */
private const val DoubleTapSlopPx = 32f

/** Half-period of the selection marching-ants phase animation. */
private const val AntsPhaseMillis = 700L

/** Color submitted for eraser strokes (fully transparent). */
private const val EraserColor = 0x00000000

/** Default stroke color (opaque black). */
private const val DefaultStrokeColor = 0xFF000000.toInt()

/** Zoom rungs cycled through by double taps. */
private val ZoomLadder = floatArrayOf(4f, 12f, 32f)

/** The active kind of a single-finger gesture. */
private sealed interface GestureMode {
    /** Pencil/eraser stroke: cells accumulate until commit. */
    data class Stroke(val tool: DrawTool) : GestureMode

    /** Line/rect/ellipse drag from [anchor] to the current cell. */
    data class Shape(val anchor: PixelPoint, val tool: DrawTool) : GestureMode

    /** Selection rectangle drag from [anchor]. */
    data class SelectRect(val anchor: PixelPoint) : GestureMode

    /** Translation of [original] by the drag delta, started at [startCell]. */
    data class MoveSelection(val startCell: PixelPoint, val original: Rect) : GestureMode

    /** No-op drag: fill/picker already fired, or the down was out of frame. */
    data object Passive : GestureMode
}

/**
 * Premium pixel-art canvas for [SpriteProject] frames, driven by a shared
 * [CanvasState].
 *
 * Rendering (bottom to top): adaptive transparency checkerboard (8px screen
 * tiles below 6x zoom, one tile per canvas pixel from 6x up) -> onion skins
 * (previous frame 0.4, next frame 0.25 alpha) -> the composited frame
 * (nearest-neighbor) -> per-pixel grid (from 4x when enabled) -> stroke
 * preview blocks -> dashed shape preview -> marching-ants selection ->
 * symmetry guides -> cursor crosshair. All colors come from
 * [MaterialTheme.colorScheme]; the grid gray is the only literal color.
 *
 * Gestures:
 * * single finger performs the [DrawTool] action — pencil/eraser strokes are
 *   sampled cell by cell (brush stamped, deduplicated) and committed as one
 *   `onDrawPixels` batch on release; line/rect/ellipse drag a live preview
 *   from the down cell and commit the full point set on release; fill/picker
 *   fire on down; select drags a marquee; move translates the marquee inside
 *   the current selection;
 * * two fingers pan and zoom (clamped by [CanvasState.clampZoom]);
 * * double tap cycles zoom through 4x/12x/32x anchored at the tap point
 *   (the first tap of the pair still performs its tool action).
 *
 * When a second finger lands mid-gesture, pencil/eraser strokes are
 * committed as drawn so far, while shape/select/move gestures are cancelled
 * (select/move restore the pre-gesture selection).
 *
 * The cursor readout ([onCursorMove]) fires for hover and drag alike; hosts
 * typically render [PixelCursorReadout] with the last value. When [playing]
 * is true the component renders read-only and ignores all input.
 *
 * @param project project whose frame is displayed; its dimensions define the
 * pixel grid. The frame is centered once per project id on the first
 * non-empty measurement.
 * @param frameIndex index into [SpriteProject.frames] to display; clamped.
 * @param modifier host modifier; the canvas fills the incoming constraints.
 * @param state shared view state (zoom, pan, tool, toggles, selection); held
 * in a host `remember` and shared with the toolbar/zoom controls.
 * @param onDrawPixels invoked once per committed stroke with the complete
 * point list and the ARGB color to paint — [strokeColor] for pencil/shapes,
 * fully transparent for the eraser.
 * @param onFill invoked with the tapped cell for flood fill.
 * @param onPick invoked with the tapped cell for color sampling.
 * @param onSelectionChange invoked on selection-gesture completion with the
 * new half-open pixel-coords rectangle, or null when the selection was
 * cleared by a select-tool tap. Move gestures report the translated rect;
 * hosts compute the content shift from the previous selection.
 * @param onCursorMove invoked with the cell and composited color under the
 * pointer on hover and drag; null when the pointer leaves the grid.
 * @param strokeColor ARGB color of strokes, previews and shape commits.
 * @param strokePreview extra in-progress stroke cells from the host (e.g.
 * symmetry ghosts), rendered semi-transparent.
 * @param shapePreview host-computed shape preview cells, rendered dashed;
 * only used while no internal shape gesture is active.
 * @param playing when true, locks interaction (read-only rendering).
 */
@Composable
fun PixelCanvasPro(
    project: SpriteProject,
    frameIndex: Int = project.activeFrameIndex,
    modifier: Modifier = Modifier,
    state: CanvasState = remember { CanvasState() },
    onDrawPixels: (List<PixelPoint>, Int) -> Unit = { _, _ -> },
    onFill: (PixelPoint) -> Unit = {},
    onPick: (PixelPoint) -> Unit = {},
    onSelectionChange: (Rect?) -> Unit = {},
    onCursorMove: (PixelPointerInfo?) -> Unit = {},
    strokeColor: Int = DefaultStrokeColor,
    strokePreview: List<PixelPoint> = emptyList(),
    shapePreview: List<PixelPoint>? = null,
    playing: Boolean = false,
) {
    val currentProject = rememberUpdatedState(project)
    // MutableState (not rememberUpdatedState) because the pointer handlers
    // read it lazily while composition keeps it fresh for every frame.
    val currentFrame = remember { mutableStateOf<PixelFrame?>(null) }
    val currentOnDrawPixels = rememberUpdatedState(onDrawPixels)
    val currentOnFill = rememberUpdatedState(onFill)
    val currentOnPick = rememberUpdatedState(onPick)
    val currentOnSelectionChange = rememberUpdatedState(onSelectionChange)
    val currentOnCursorMove = rememberUpdatedState(onCursorMove)
    val currentPlaying = rememberUpdatedState(playing)
    val currentStrokeColor = rememberUpdatedState(strokeColor)

    val frameIdx = frameIndex.coerceIn(0, project.frameCount - 1)
    val composited = remember(frameIdx, project) { project.compositeFrame(frameIdx) }
    currentFrame.value = composited
    val frameBitmap = remember(composited) { composited.toImageBitmap() }

    // Keep the shared state's grid bounds in sync so screenToPixel works.
    if (state.canvasWidth != project.width) state.canvasWidth = project.width
    if (state.canvasHeight != project.height) state.canvasHeight = project.height

    val onionSkins = remember(project, frameIdx, state.onionSkinEnabled) {
        if (state.onionSkinEnabled && project.frameCount > 1) {
            val prev = if (frameIdx > 0) project.compositeFrame(frameIdx - 1).toImageBitmap() else null
            val next = if (frameIdx < project.frameCount - 1) project.compositeFrame(frameIdx + 1).toImageBitmap() else null
            prev to next
        } else {
            null to null
        }
    }

    // Live gesture data: internal stroke/shape previews and hover position.
    val liveStroke = remember { mutableStateListOf<PixelPoint>() }
    val strokeKeys = remember { HashSet<Long>() }
    var liveShape by remember { mutableStateOf<List<PixelPoint>>(emptyList()) }
    var hoverPosition by remember { mutableStateOf<Offset?>(null) }
    val activePointers = remember { mutableStateOf(0) }
    val gestureStartSelection = remember { mutableStateOf<Rect?>(null) }
    val lastTap = remember { mutableStateOf<Pair<Long, Offset>?>(null) }
    val centeredFor = remember { mutableStateOf<String?>(null) }

    // Marching-ants phase: a 0<->1 target flip animated by animateFloatAsState.
    var antsTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            antsTarget = 1f
            delay(AntsPhaseMillis)
            antsTarget = 0f
            delay(AntsPhaseMillis)
        }
    }
    val antsPhase = animateFloatAsState(
        targetValue = antsTarget,
        label = "selectionAntsPhase",
    )

    val scheme = MaterialTheme.colorScheme
    val checkerLight = scheme.surface
    val checkerDark = scheme.surfaceVariant
    val gridColor = Color.Gray.copy(alpha = GridAlpha)

    /**
     * Maps a canvas-local screen position to the grid cell under it (live
     * zoom/pan), or null outside the frame.
     */
    fun hitCell(position: Offset): PixelPoint? {
        val cellSize = BasePixelCellSize * state.zoom
        return state.screenToPixel(position.x, position.y, cellSize)
    }

    /** Publishes the hover position and the cursor readout for it. */
    fun emitCursor(position: Offset) {
        hoverPosition = position
        val cell = hitCell(position)
        val info = cell?.let { PixelPointerInfo(it.x, it.y, currentFrame.value?.get(it.x, it.y) ?: 0) }
        currentOnCursorMove.value.invoke(info)
    }

    /** Expands a cell into the brush-sized square, clipped to the frame. */
    fun brushStamp(center: PixelPoint, brush: Int): List<PixelPoint> {
        val p = currentProject.value
        if (brush <= 1) {
            return if (center.x < p.width && center.y < p.height) listOf(center) else emptyList()
        }
        val startX = center.x - (brush - 1) / 2
        val startY = center.y - (brush - 1) / 2
        val out = ArrayList<PixelPoint>(brush * brush)
        for (dy in 0 until brush) {
            for (dx in 0 until brush) {
                val px = startX + dx
                val py = startY + dy
                if (px in 0 until p.width && py in 0 until p.height) out.add(PixelPoint(px, py))
            }
        }
        return out
    }

    /** Adds cells to the live stroke, skipping already-covered cells. */
    fun addStrokeCells(cells: List<PixelPoint>) {
        for (p in cells) {
            val key = (p.x.toLong() shl 16) or p.y.toLong()
            if (strokeKeys.add(key)) liveStroke.add(p)
        }
    }

    /** ARGB color a committed stroke paints with. */
    fun paintColor(tool: DrawTool): Int =
        if (tool == DrawTool.ERASER) EraserColor else currentStrokeColor.value

    /** Stamps [points] through the brush, deduplicated, frame-clipped. */
    fun stampedShape(points: List<PixelPoint>): List<PixelPoint> {
        val brush = state.brushSize
        val seen = HashSet<Long>()
        val out = ArrayList<PixelPoint>()
        for (p in points) {
            for (c in brushStamp(p, brush)) {
                val key = (c.x.toLong() shl 16) or c.y.toLong()
                if (seen.add(key)) out.add(c)
            }
        }
        return out
    }

    /** Rebuilds the live shape preview from [anchor] to [current]. */
    fun updateShapePreview(mode: GestureMode.Shape, current: PixelPoint) {
        val raw = when (mode.tool) {
            DrawTool.LINE -> linePoints(mode.anchor, current)
            DrawTool.RECT -> rectOutlinePoints(mode.anchor, current)
            DrawTool.ELLIPSE -> ellipseOutlinePoints(mode.anchor, current)
            else -> linePoints(mode.anchor, current)
        }
        liveShape = stampedShape(raw)
    }

    /**
     * Cycles the zoom ladder anchored at [anchor] so the point under the
     * finger stays fixed.
     */
    fun cycleZoomAnchored(anchor: Offset) {
        val current = state.zoom
        val nearest = ZoomLadder.indices.minByOrNull { abs(ZoomLadder[it] - current) } ?: 0
        val target = ZoomLadder[(nearest + 1) % ZoomLadder.size]
        val old = state.zoom
        state.zoom = target
        val applied = state.zoom
        if (applied != old) {
            val k = applied / old
            state.panX = anchor.x - (anchor.x - state.panX) * k
            state.panY = anchor.y - (anchor.y - state.panY) * k
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0 && centeredFor.value != project.id) {
                    centeredFor.value = project.id
                    val cellSize = BasePixelCellSize * state.zoom
                    state.panX = (size.width - currentProject.value.width * cellSize) / 2f
                    state.panY = (size.height - currentProject.value.height * cellSize) / 2f
                }
            }
            .pointerInput(state) {
                // Hover (unpressed moves): updates the crosshair + readout.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (currentPlaying.value) continue
                        val change = event.changes.firstOrNull() ?: continue
                        if (!change.pressed) emitCursor(change.position)
                    }
                }
            }
            .pointerInput(state) {
                // Single-finger tool gestures + double-tap zoom cycling.
                awaitEachGesture {
                    if (currentPlaying.value) return@awaitEachGesture
                    val down = awaitFirstDown(requireUnconsumed = false)
                    emitCursor(down.position)

                    val last = lastTap.value
                    val now = System.currentTimeMillis()
                    val isDoubleTap = last != null &&
                        now - last.first <= DoubleTapTimeoutMillis &&
                        abs(down.position.x - last.second.x) <= DoubleTapSlopPx &&
                        abs(down.position.y - last.second.y) <= DoubleTapSlopPx
                    if (isDoubleTap) {
                        lastTap.value = null
                        cycleZoomAnchored(down.position)
                        // Swallow the rest of the double-tap gesture.
                        var swallowing = true
                        while (swallowing) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) swallowing = false
                        }
                        return@awaitEachGesture
                    }

                    activePointers.value = 1
                    val downCell = hitCell(down.position)
                    val tool = state.tool

                    // ---- gesture start ------------------------------------------------
                    var mode: GestureMode = GestureMode.Passive
                    when {
                        downCell == null -> mode = GestureMode.Passive
                        tool == DrawTool.PENCIL || tool == DrawTool.ERASER -> {
                            liveStroke.clear()
                            strokeKeys.clear()
                            addStrokeCells(brushStamp(downCell, state.brushSize))
                            mode = GestureMode.Stroke(tool)
                        }
                        tool == DrawTool.FILL -> currentOnFill.value.invoke(downCell)
                        tool == DrawTool.PICKER -> currentOnPick.value.invoke(downCell)
                        tool == DrawTool.LINE || tool == DrawTool.RECT || tool == DrawTool.ELLIPSE -> {
                            liveShape = stampedShape(listOf(downCell))
                            mode = GestureMode.Shape(downCell, tool)
                        }
                        tool == DrawTool.SELECT -> {
                            gestureStartSelection.value = state.selection
                            state.selection = null
                            mode = GestureMode.SelectRect(downCell)
                        }
                        tool == DrawTool.MOVE -> {
                            val sel = state.selection
                            val inside = sel != null &&
                                downCell.x >= sel.left && downCell.x < sel.right &&
                                downCell.y >= sel.top && downCell.y < sel.bottom
                            if (inside) {
                                gestureStartSelection.value = sel
                                mode = GestureMode.MoveSelection(downCell, sel!!)
                            }
                        }
                    }
                    var moved = false
                    var lastCell = downCell
                    var lastPos = down.position
                    var handedOff = false
                    var tracking = true

                    // ---- gesture update / end -----------------------------------------
                    while (tracking) {
                        val event = awaitPointerEvent()
                        activePointers.value = event.changes.count { it.pressed }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            if (event.changes.none { it.pressed }) tracking = false
                            continue
                        }
                        if (change.position != lastPos) {
                            lastPos = change.position
                            if (!handedOff) emitCursor(change.position)
                        }
                        val pressedCount = event.changes.count { it.pressed }

                        if (handedOff) {
                            if (!change.pressed || pressedCount == 0) tracking = false
                            continue
                        }

                        if (!change.pressed) {
                            // ---- release: commit ------------------------------------------
                            when (mode) {
                                is GestureMode.Stroke -> {
                                    if (liveStroke.isNotEmpty()) {
                                        currentOnDrawPixels.value.invoke(liveStroke.toList(), paintColor(mode.tool))
                                    }
                                    liveStroke.clear()
                                    strokeKeys.clear()
                                }
                                is GestureMode.Shape -> {
                                    if (liveShape.isNotEmpty()) {
                                        currentOnDrawPixels.value.invoke(liveShape.toList(), paintColor(mode.tool))
                                    }
                                    liveShape = emptyList()
                                }
                                is GestureMode.SelectRect -> {
                                    val committed = if (moved) state.selection else null
                                    currentOnSelectionChange.value.invoke(committed)
                                }
                                is GestureMode.MoveSelection -> {
                                    currentOnSelectionChange.value.invoke(state.selection)
                                }
                                GestureMode.Passive -> Unit
                            }
                            lastTap.value = System.currentTimeMillis() to change.position
                            tracking = false
                        } else if (pressedCount >= 2) {
                            // ---- two-finger takeover --------------------------------------
                            handedOff = true
                            when (mode) {
                                is GestureMode.Stroke -> {
                                    if (liveStroke.isNotEmpty()) {
                                        currentOnDrawPixels.value.invoke(liveStroke.toList(), paintColor(mode.tool))
                                    }
                                    liveStroke.clear()
                                    strokeKeys.clear()
                                }
                                is GestureMode.Shape -> liveShape = emptyList()
                                is GestureMode.SelectRect -> state.selection = gestureStartSelection.value
                                is GestureMode.MoveSelection -> state.selection = gestureStartSelection.value
                                GestureMode.Passive -> Unit
                            }
                        } else {
                            // ---- single-finger drag ----------------------------------------
                            val cell = hitCell(change.position)
                            if (cell != null && cell != lastCell) {
                                moved = true
                                lastCell = cell
                                when (mode) {
                                    is GestureMode.Stroke -> addStrokeCells(brushStamp(cell, state.brushSize))
                                    is GestureMode.Shape -> updateShapePreview(mode, cell)
                                    is GestureMode.SelectRect -> state.selection = rectFromCells(mode.anchor, cell)
                                    is GestureMode.MoveSelection -> state.selection = translatedSelection(
                                        mode,
                                        cell,
                                        currentProject.value.width,
                                        currentProject.value.height,
                                    )
                                    GestureMode.Passive -> Unit
                                }
                            }
                        }
                    }
                    activePointers.value = 0
                }
            }
            .pointerInput(state) {
                // Two-finger pan/zoom; pan applies only with 2+ pointers down.
                detectTransformGestures { _, pan, zoomFactor, _ ->
                    if (currentPlaying.value) return@detectTransformGestures
                    state.zoom = state.clampZoom(state.zoom * zoomFactor)
                    if (activePointers.value >= 2) {
                        state.panX += pan.x
                        state.panY += pan.y
                    }
                }
            },
    ) {
        val zoomNow = state.zoom
        val cell = BasePixelCellSize * zoomNow
        val p = currentProject.value
        val origin = Offset(state.panX, state.panY)
        val frameW = p.width * cell
        val frameH = p.height * cell
        val dstTopLeft = IntOffset(origin.x.toInt(), origin.y.toInt())
        val dstSize = IntSize(frameW.toInt(), frameH.toInt())

        // 1. Adaptive transparency checkerboard over the frame region.
        if (state.showCheckerboard) {
            val tile = if (zoomNow < PerPixelCheckerZoom) ScreenCheckerTile else cell
            var row = 0
            var y = origin.y
            while (y < origin.y + frameH) {
                var column = 0
                var x = origin.x
                while (x < origin.x + frameW) {
                    val tileColor = if ((row + column) % 2 == 0) checkerLight else checkerDark
                    drawRect(
                        color = tileColor,
                        topLeft = Offset(x, y),
                        size = Size(
                            minOf(tile, origin.x + frameW - x),
                            minOf(tile, origin.y + frameH - y),
                        ),
                    )
                    x += tile
                    column++
                }
                y += tile
                row++
            }
        }

        // 2. Onion skins ghosted underneath the main frame.
        onionSkins.first?.let {
            drawImage(
                it,
                dstOffset = dstTopLeft,
                dstSize = dstSize,
                alpha = OnionPreviousAlpha,
                filterQuality = FilterQuality.None,
            )
        }
        onionSkins.second?.let {
            drawImage(
                it,
                dstOffset = dstTopLeft,
                dstSize = dstSize,
                alpha = OnionNextAlpha,
                filterQuality = FilterQuality.None,
            )
        }

        // 3. The composited frame, crisp nearest-neighbor.
        drawImage(
            frameBitmap,
            dstOffset = dstTopLeft,
            dstSize = dstSize,
            filterQuality = FilterQuality.None,
        )

        // 4. Per-pixel grid from 4x zoom when enabled.
        if (zoomNow >= GridZoomThreshold && state.showGrid) {
            for (i in 0..p.width) {
                val gx = origin.x + i * cell
                drawLine(
                    color = gridColor,
                    start = Offset(gx, origin.y),
                    end = Offset(gx, origin.y + frameH),
                    strokeWidth = 1f,
                )
            }
            for (j in 0..p.height) {
                val gy = origin.y + j * cell
                drawLine(
                    color = gridColor,
                    start = Offset(origin.x, gy),
                    end = Offset(origin.x + frameW, gy),
                    strokeWidth = 1f,
                )
            }
        }

        // 5. Stroke preview: host cells + the live internal stroke, merged.
        if (strokePreview.isNotEmpty() || liveStroke.isNotEmpty()) {
            val previewColor = Color(currentStrokeColor.value)
            val merged = LinkedHashSet(strokePreview)
            merged.addAll(liveStroke)
            for (point in merged) {
                drawRect(
                    color = previewColor,
                    topLeft = Offset(origin.x + point.x * cell, origin.y + point.y * cell),
                    size = Size(cell, cell),
                    alpha = StrokePreviewAlpha,
                )
            }
        }

        // 6. Shape-tool preview: every other cell for a dashed feel.
        val shape = if (liveShape.isNotEmpty()) liveShape else (shapePreview ?: emptyList())
        if (shape.isNotEmpty()) {
            val shapeColor = Color(currentStrokeColor.value)
            for ((index, point) in shape.withIndex()) {
                if (index % 2 == 0) {
                    drawRect(
                        color = shapeColor,
                        topLeft = Offset(origin.x + point.x * cell, origin.y + point.y * cell),
                        size = Size(cell, cell),
                        alpha = ShapePreviewAlpha,
                    )
                }
            }
        }

        // 7. Selection marching ants: theme near-black/white alternating
        // cells along the boundary, phase-shifted for the flow animation.
        val selection = state.selection
        if (selection != null) {
            val left = selection.left.toInt().coerceIn(0, p.width)
            val top = selection.top.toInt().coerceIn(0, p.height)
            val right = selection.right.toInt().coerceIn(0, p.width)
            val bottom = selection.bottom.toInt().coerceIn(0, p.height)
            if (right > left && bottom > top) {
                val perimeter = (right - left) * 2 + (bottom - top) * 2
                val shift = (antsPhase.value * perimeter).toInt()
                val antDark = scheme.inverseSurface
                val antLight = scheme.surface
                fun antCell(x: Int, y: Int, index: Int) {
                    val color = if ((index + shift) % 2 == 0) antDark else antLight
                    drawRect(
                        color = color,
                        topLeft = Offset(origin.x + x * cell, origin.y + y * cell),
                        size = Size(cell, cell),
                    )
                }
                var k = 0
                for (x in left until right) {
                    antCell(x, top, k++)
                    antCell(x, bottom - 1, k++)
                }
                for (y in top + 1 until bottom - 1) {
                    antCell(left, y, k++)
                    antCell(right - 1, y, k++)
                }
            }
        }

        // 8. Symmetry guide axes: two-color dashed lines through the middle.
        if (state.symmetry != CanvasSymmetry.OFF) {
            val axisA = scheme.primary
            val axisB = scheme.tertiary
            val thickness = max(2f, cell / 6f)
            if (state.symmetry == CanvasSymmetry.HORIZONTAL || state.symmetry == CanvasSymmetry.FOUR_WAY) {
                val ay = origin.y + (p.height / 2f) * cell
                for (i in 0 until p.width) {
                    val color = when (i % 4) {
                        0, 1 -> axisA
                        2 -> axisB
                        else -> null
                    }
                    if (color != null) {
                        drawRect(
                            color = color,
                            topLeft = Offset(origin.x + i * cell, ay - thickness / 2f),
                            size = Size(cell, thickness),
                            alpha = SymmetryAxisAlpha,
                        )
                    }
                }
            }
            if (state.symmetry == CanvasSymmetry.VERTICAL || state.symmetry == CanvasSymmetry.FOUR_WAY) {
                val ax = origin.x + (p.width / 2f) * cell
                for (j in 0 until p.height) {
                    val color = when (j % 4) {
                        0, 1 -> axisA
                        2 -> axisB
                        else -> null
                    }
                    if (color != null) {
                        drawRect(
                            color = color,
                            topLeft = Offset(ax - thickness / 2f, origin.y + j * cell),
                            size = Size(thickness, cell),
                            alpha = SymmetryAxisAlpha,
                        )
                    }
                }
            }
        }

        // 9. Cursor crosshair: full-height/width hairlines in translucent primary.
        val hover = hoverPosition
        if (hover != null && !playing) {
            val crosshairColor = scheme.primary.copy(alpha = CrosshairAlpha)
            drawLine(
                color = crosshairColor,
                start = Offset(hover.x, 0f),
                end = Offset(hover.x, size.height),
                strokeWidth = 1f,
            )
            drawLine(
                color = crosshairColor,
                start = Offset(0f, hover.y),
                end = Offset(size.width, hover.y),
                strokeWidth = 1f,
            )
        }
    }
}

/**
 * Half-open selection rectangle covering cells [a] and [b]:
 * `[min.x, max.x + 1) x [min.y, max.y + 1)`, so a single-cell drag yields a
 * 1x1 selection.
 */
private fun rectFromCells(a: PixelPoint, b: PixelPoint): Rect {
    val left = min(a.x, b.x).toFloat()
    val right = (max(a.x, b.x) + 1).toFloat()
    val top = min(a.y, b.y).toFloat()
    val bottom = (max(a.y, b.y) + 1).toFloat()
    return Rect(left, top, right, bottom)
}

/**
 * Translates the [GestureMode.MoveSelection.original] rectangle by the cell
 * delta from [GestureMode.MoveSelection.startCell] to [current], clamped so
 * the selection stays inside the [maxWidth] x [maxHeight] frame.
 */
private fun translatedSelection(
    mode: GestureMode.MoveSelection,
    current: PixelPoint,
    maxWidth: Int,
    maxHeight: Int,
): Rect {
    val frame = mode.original
    val width = (frame.right - frame.left).coerceAtMost(maxWidth.toFloat())
    val height = (frame.bottom - frame.top).coerceAtMost(maxHeight.toFloat())
    val dx = (current.x - mode.startCell.x).toFloat()
    val dy = (current.y - mode.startCell.y).toFloat()
    val left = (frame.left + dx).coerceIn(0f, maxWidth.toFloat() - width)
    val top = (frame.top + dy).coerceIn(0f, maxHeight.toFloat() - height)
    return Rect(left, top, left + width, top + height)
}

/**
 * Bresenham line from [a] to [b] inclusive; both endpoints are in-frame so
 * all output cells are non-negative.
 */
private fun linePoints(a: PixelPoint, b: PixelPoint): List<PixelPoint> {
    val points = ArrayList<PixelPoint>()
    var x0 = a.x
    var y0 = a.y
    val x1 = b.x
    val y1 = b.y
    val dx = abs(x1 - x0)
    val dy = -abs(y1 - y0)
    val sx = if (x0 < x1) 1 else -1
    val sy = if (y0 < y1) 1 else -1
    var err = dx + dy
    while (true) {
        points.add(PixelPoint(x0, y0))
        if (x0 == x1 && y0 == y1) break
        val e2 = 2 * err
        if (e2 >= dy) {
            err += dy
            x0 += sx
        }
        if (e2 <= dx) {
            err += dx
            y0 += sy
        }
    }
    return points
}

/**
 * Rectangle outline covering cells [a] and [b]: the perimeter of the
 * bounding box, corner cells included once.
 */
private fun rectOutlinePoints(a: PixelPoint, b: PixelPoint): List<PixelPoint> {
    val x0 = min(a.x, b.x)
    val x1 = max(a.x, b.x)
    val y0 = min(a.y, b.y)
    val y1 = max(a.y, b.y)
    val top = linePoints(PixelPoint(x0, y0), PixelPoint(x1, y0))
    val bottom = linePoints(PixelPoint(x0, y1), PixelPoint(x1, y1))
    val left = linePoints(PixelPoint(x0, y0), PixelPoint(x0, y1))
    val right = linePoints(PixelPoint(x1, y0), PixelPoint(x1, y1))
    val seen = LinkedHashSet<Long>()
    for (p in top + bottom + left + right) {
        seen.add((p.x.toLong() shl 16) or p.y.toLong())
    }
    return seen.map { PixelPoint((it shr 16).toInt(), (it and 0xFFFF).toInt()) }
}

/**
 * Ellipse outline inscribed in the bounding box of cells [a] and [b]:
 * dense parametric sampling of `(cx + rx*cos t, cy + ry*sin t)` rounded to
 * the nearest cell, deduplicated; degenerate boxes collapse to a line.
 */
private fun ellipseOutlinePoints(a: PixelPoint, b: PixelPoint): List<PixelPoint> {
    val x0 = min(a.x, b.x)
    val x1 = max(a.x, b.x)
    val y0 = min(a.y, b.y)
    val y1 = max(a.y, b.y)
    if (x0 == x1 && y0 == y1) return listOf(PixelPoint(x0, y0))
    if (x0 == x1 || y0 == y1) return linePoints(PixelPoint(x0, y0), PixelPoint(x1, y1))
    val cx = (x0 + x1) / 2f
    val cy = (y0 + y1) / 2f
    val rx = (x1 - x0) / 2f + 0.5f
    val ry = (y1 - y0) / 2f + 0.5f
    val steps = max(24, ((rx + ry) * 6f).toInt())
    val seen = LinkedHashSet<Long>()
    for (i in 0 until steps) {
        val t = (i.toFloat() / steps) * 2f * PI.toFloat()
        val px = (cx + rx * cos(t)).roundToInt().coerceIn(x0, x1)
        val py = (cy + ry * sin(t)).roundToInt().coerceIn(y0, y1)
        seen.add((px.toLong() shl 16) or py.toLong())
    }
    return seen.map { PixelPoint((it shr 16).toInt(), (it and 0xFFFF).toInt()) }
}
