package com.pixellab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject
import kotlin.math.floor

/** Lowest interactive zoom level. */
private const val MinZoom = 1f

/** Highest interactive zoom level. */
private const val MaxZoom = 64f

/** Side length of the transparency-checkerboard squares, in canvas pixels. */
private const val CheckerTile = 8f

/** Zoom level at which the pixel grid becomes visible when [PixelCanvas] grid mode is auto. */
private const val AutoGridScale = 4f

/** Alpha of the low-alpha gray used for grid lines (the only literal color here). */
private const val GridAlpha = 0.35f

/** Width of grid lines, in canvas pixels. */
private const val GridStrokeWidth = 1f

/** Draw alpha of the onion-skin frame *before* the active one. */
private const val OnionPreviousAlpha = 0.4f

/** Draw alpha of the onion-skin frame *after* the active one. */
private const val OnionNextAlpha = 0.25f

/** Draw alpha of the stroke-preview blocks. */
private const val StrokePreviewAlpha = 0.5f

/**
 * Interactive pixel-art canvas for a [SpriteProject] frame.
 *
 * The canvas renders the **composited** frame (all layers blended bottom-up
 * with their opacity, invisible layers skipped) of [project] at [frameIndex],
 * scaled by the interactive zoom that starts at [scale]. Layered under the
 * frame, from bottom to top:
 *
 * 1. an 8px transparency checkerboard covering exactly the frame region,
 *    painted with `surface` / `surfaceVariant` from the Material theme;
 * 2. the onion-skin neighbors (previous frame at alpha 0.4, next frame at
 *    alpha 0.25) when [onionSkin] is non-null;
 * 3. the composited frame itself, nearest-neighbor filtered
 *    ([FilterQuality.None]) so pixels stay crisp;
 * 4. the pixel grid, shown when [showGrid] forces it or — when null —
 *    automatically once the zoom reaches 4x;
 * 5. semi-transparent [strokePreview] blocks in [strokePreviewColor].
 *
 * Gestures: one finger draws, two fingers pan and zoom. Pan is applied only
 * while at least two pointers are active; the zoom is clamped to
 * `1..64`. Pixel hit testing uses
 * `gx = floor((x - offset.x) / zoom)`, `gy = floor((y - offset.y) / zoom)`;
 * out-of-bounds positions are ignored and a pointer that stays inside the
 * same grid cell does not re-emit events. The release always reports
 * [onPixelUp] once a stroke started, using the release cell or — when the
 * finger lifts outside the frame — the last valid cell. When a second finger
 * lands mid-stroke the stroke is cancelled (reported through [onPixelUp])
 * and the gesture hands control over to pan/zoom.
 *
 * The frame is centered once, on the first non-empty size measurement.
 *
 * @param project project whose frame is displayed; its dimensions define the
 * pixel grid and the composited raster.
 * @param frameIndex index into [SpriteProject.frames] of the frame to show;
 * defaults to the project's active frame and is clamped into range.
 * @param modifier host modifier; the canvas fills the incoming constraints,
 * so size it from the outside.
 * @param scale initial zoom factor (pixels per frame pixel); the value is
 * clamped to `1..64`. Later changes of this parameter reset the zoom.
 * @param showGrid `null` = automatic (grid when zoom >= 4), otherwise forces
 * the grid on or off.
 * @param onionSkin when non-null, the neighboring composited frames of this
 * project (typically the same project being edited) are ghosted underneath;
 * null disables onion skinning.
 * @param strokePreview grid points of an in-progress stroke, drawn as
 * semi-transparent blocks on top of everything.
 * @param strokePreviewColor ARGB color of the preview blocks.
 * @param onPixelDown invoked with the grid cell under the first pointer down.
 * @param onPixelDrag invoked when the pointer moves into a new grid cell.
 * @param onPixelUp invoked when the stroke ends, always after a started
 * stroke, even for the cell the drag last visited.
 */
@Composable
fun PixelCanvas(
    project: SpriteProject,
    frameIndex: Int = project.activeFrameIndex,
    modifier: Modifier = Modifier,
    scale: Float = 12f,
    showGrid: Boolean? = null,
    onionSkin: SpriteProject? = null,
    strokePreview: List<PixelPoint> = emptyList(),
    strokePreviewColor: Int = 0xFFFFFFFF.toInt(),
    onPixelDown: (PixelPoint) -> Unit = {},
    onPixelDrag: (PixelPoint) -> Unit = {},
    onPixelUp: (PixelPoint) -> Unit = {},
) {
    val currentProject = rememberUpdatedState(project)
    val currentOnPixelDown = rememberUpdatedState(onPixelDown)
    val currentOnPixelDrag = rememberUpdatedState(onPixelDrag)
    val currentOnPixelUp = rememberUpdatedState(onPixelUp)

    // Interactive view state: zoom starts from the scale parameter and pan
    // starts centered; both are plain remembered state so gestures own them.
    var zoom by remember(scale) { mutableStateOf(scale.coerceIn(MinZoom, MaxZoom)) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var activePointerCount by remember { mutableStateOf(0) }
    val centered = remember { mutableStateOf(false) }

    val frameIdx = frameIndex.coerceIn(0, project.frameCount - 1)
    val compositedFrame = remember(frameIdx, project) { project.compositeFrame(frameIdx) }
    val frameBitmap = remember(compositedFrame) { compositedFrame.toImageBitmap() }
    val onionSkins = remember(onionSkin, frameIdx) { onionFrames(onionSkin, frameIdx) }

    val checkerLight = MaterialTheme.colorScheme.surface
    val checkerDark = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = Color.Gray.copy(alpha = GridAlpha)

    /**
     * Maps a pointer position to the grid cell under it, or null when the
     * position falls outside the frame region. Reads live state so it stays
     * correct across pan/zoom.
     */
    fun hitCell(position: Offset): PixelPoint? {
        val p = currentProject.value
        val gx = floor((position.x - panOffset.x) / zoom)
        val gy = floor((position.y - panOffset.y) / zoom)
        if (gx < 0f || gy < 0f || gx >= p.width || gy >= p.height) return null
        return PixelPoint(gx.toInt(), gy.toInt())
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size ->
                // One-shot centering: the first real measurement places the
                // frame in the middle of the canvas area.
                if (!centered.value && size.width > 0 && size.height > 0) {
                    centered.value = true
                    val p = currentProject.value
                    panOffset = Offset(
                        (size.width - p.width * zoom) / 2f,
                        (size.height - p.height * zoom) / 2f,
                    )
                }
            }
            .pointerInput(Unit) {
                // Single-finger stroke tracking (and multi-touch detection).
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    activePointerCount = 1
                    var lastCell: PixelPoint? = hitCell(down.position)
                    lastCell?.let { currentOnPixelDown.value.invoke(it) }
                    var strokeCancelled = false
                    var tracking = true
                    while (tracking) {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        activePointerCount = pressedCount
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change != null && !change.pressed) {
                            // Tracked pointer lifted: always emit the up event.
                            if (!strokeCancelled) {
                                val upCell = hitCell(change.position) ?: lastCell
                                if (upCell != null) currentOnPixelUp.value.invoke(upCell)
                            }
                            tracking = false
                        } else if (pressedCount >= 2) {
                            // Two-finger pan/zoom takes over: end the stroke once.
                            if (!strokeCancelled) {
                                strokeCancelled = true
                                lastCell?.let { currentOnPixelUp.value.invoke(it) }
                            }
                        } else if (pressedCount == 1 && change != null) {
                            // Pure single-finger drag: emit new cells only.
                            val cell = hitCell(change.position)
                            if (cell != null && cell != lastCell) {
                                lastCell = cell
                                currentOnPixelDrag.value.invoke(cell)
                            }
                        }
                        if (pressedCount == 0) {
                            tracking = false
                            activePointerCount = 0
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                // Two-finger transform: zoom always clamped, pan applied only
                // while at least two pointers are active.
                detectTransformGestures { _, pan, zoomFactor, _ ->
                    zoom = (zoom * zoomFactor).coerceIn(MinZoom, MaxZoom)
                    if (activePointerCount >= 2) {
                        panOffset += pan
                    }
                }
            },
    ) {
        val frameWidth = currentProject.value.width
        val frameHeight = currentProject.value.height
        val origin = panOffset
        val zoomNow = zoom
        val canvasWidth = frameWidth * zoomNow
        val canvasHeight = frameHeight * zoomNow
        val dstTopLeft = IntOffset(origin.x.toInt(), origin.y.toInt())
        val dstSize = IntSize(canvasWidth.toInt(), canvasHeight.toInt())

        // 1. Transparency checkerboard over the frame region only.
        var row = 0
        var y = origin.y
        while (y < origin.y + canvasHeight) {
            var column = 0
            var x = origin.x
            while (x < origin.x + canvasWidth) {
                val tileColor = if ((row + column) % 2 == 0) checkerLight else checkerDark
                drawRect(
                    color = tileColor,
                    topLeft = Offset(x, y),
                    size = Size(
                        minOf(CheckerTile, origin.x + canvasWidth - x),
                        minOf(CheckerTile, origin.y + canvasHeight - y),
                    ),
                )
                x += CheckerTile
                column++
            }
            y += CheckerTile
            row++
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

        // 4. Pixel grid.
        val gridVisible = showGrid ?: (zoomNow >= AutoGridScale)
        if (gridVisible) {
            for (i in 0..frameWidth) {
                val gx = origin.x + i * zoomNow
                drawLine(
                    color = gridColor,
                    start = Offset(gx, origin.y),
                    end = Offset(gx, origin.y + canvasHeight),
                    strokeWidth = GridStrokeWidth,
                )
            }
            for (j in 0..frameHeight) {
                val gy = origin.y + j * zoomNow
                drawLine(
                    color = gridColor,
                    start = Offset(origin.x, gy),
                    end = Offset(origin.x + canvasWidth, gy),
                    strokeWidth = GridStrokeWidth,
                )
            }
        }

        // 5. In-progress stroke preview.
        if (strokePreview.isNotEmpty()) {
            val previewColor = Color(strokePreviewColor)
            for (point in strokePreview) {
                if (point.x < frameWidth && point.y < frameHeight) {
                    drawRect(
                        color = previewColor,
                        topLeft = Offset(
                            origin.x + point.x * zoomNow,
                            origin.y + point.y * zoomNow,
                        ),
                        size = Size(zoomNow, zoomNow),
                        alpha = StrokePreviewAlpha,
                    )
                }
            }
        }
    }
}

/**
 * Computes the onion-skin pair for [source] around [frameIndex]: the
 * composited frame before and after it, converted to bitmaps; both sides are
 * null when the frame sits at the corresponding timeline boundary or when
 * [source] itself is null. The index is clamped into [source]'s frame range
 * so a reference project of different length stays usable.
 */
private fun onionFrames(source: SpriteProject?, frameIndex: Int): Pair<ImageBitmap?, ImageBitmap?> {
    if (source == null) return null to null
    val idx = frameIndex.coerceIn(0, source.frameCount - 1)
    val previous = if (idx > 0) source.compositeFrame(idx - 1).toImageBitmap() else null
    val next = if (idx < source.frameCount - 1) source.compositeFrame(idx + 1).toImageBitmap() else null
    return previous to next
}
