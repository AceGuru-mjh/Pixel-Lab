package com.pixellab.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import com.pixellab.core.model.PixelPoint
import kotlin.math.abs
import kotlin.math.floor

/**
 * Tools of [PixelCanvasPro].
 *
 * * [PENCIL] / [ERASER] paint / erase with the current brush size,
 * * [FILL] floods the tapped region, [PICKER] samples the tapped color,
 * * [LINE] / [RECT] / [ELLIPSE] drag-out raster shapes,
 * * [SELECT] drags out a rectangular selection, [MOVE] translates it.
 */
enum class DrawTool {
    PENCIL,
    ERASER,
    FILL,
    PICKER,
    LINE,
    RECT,
    ELLIPSE,
    SELECT,
    MOVE,
}

/**
 * Mirror symmetry guide of [PixelCanvasPro]. The canvas draws the reference
 * axes for every mode other than [OFF]; hosts apply the actual mirrored
 * painting when committing strokes.
 */
enum class CanvasSymmetry {
    /** No symmetry. */
    OFF,
    /** One horizontal axis through the vertical middle. */
    HORIZONTAL,
    /** One vertical axis through the horizontal middle. */
    VERTICAL,
    /** Both axes: horizontal + vertical. */
    FOUR_WAY,
}

/** Lowest interactive zoom level of the canvas. */
private const val MinZoom = 1f

/** Highest interactive zoom level of the canvas. */
private const val MaxZoom = 64f

/** Lowest brush size in pixels. */
private const val MinBrush = 1

/** Highest brush size in pixels. */
private const val MaxBrush = 8

/** Zoom used by a freshly created state. */
private const val InitialZoom = 8f

/**
 * How close (relative to the ladder step) a zoom value must sit to a
 * power-of-two rung to snap onto it; keeps pinch-zoom feeling logarithmic.
 */
private const val ZoomSnapTolerance = 0.10f

/**
 * Cursor readout payload published by [PixelCanvasPro] through
 * `onCursorMove`: the grid cell under the pointer and the composited frame
 * color at that cell.
 */
data class PixelPointerInfo(
    /** Grid column under the pointer, `0`-based. */
    val x: Int,
    /** Grid row under the pointer, `0`-based. */
    val y: Int,
    /** Composited ARGB color of the frame at (`x`, `y`). */
    val color: Int,
)

/**
 * Shared, mutable view state of the premium canvas family
 * ([PixelCanvasPro], [CanvasToolbar], [ZoomControls]).
 *
 * State is hoisted into a single instance the host owns (typically
 * `remember { CanvasState() }`) and hands to every component. All properties
 * are backed by snapshot state, so Compose recomposes and redraws whenever a
 * component mutates them — the host does not need to wire any change
 * callbacks. Property setters validate their input: [zoom] runs through
 * [clampZoom] on every write and [brushSize] is coerced into `1..8`.
 *
 * Pan values are canvas-local pixel offsets of the frame origin; zoom is a
 * multiplier over the [BasePixelCellSize] used by [PixelCanvasPro]
 * (`cellSize = 12 * zoom` screen pixels per canvas pixel).
 *
 * [selection] is expressed in canvas pixel coordinates as a half-open
 * rectangle `[left, right) x [top, bottom)`.
 */
class CanvasState(
    /** Initial zoom factor; clamped into `1..64`. */
    initialZoom: Float = InitialZoom,
    /** Initial active tool. */
    initialTool: DrawTool = DrawTool.PENCIL,
) {

    private val zoomBacking = mutableStateOf(clampZoom(initialZoom))

    /** Interactive zoom multiplier, always kept inside `1..64`. */
    var zoom: Float
        get() = zoomBacking.value
        set(value) {
            zoomBacking.value = clampZoom(value)
        }

    /** Horizontal canvas-local offset of the frame origin, in screen pixels. */
    var panX: Float by mutableStateOf(0f)

    /** Vertical canvas-local offset of the frame origin, in screen pixels. */
    var panY: Float by mutableStateOf(0f)

    /** Tool used for the next single-finger gesture. */
    var tool: DrawTool by mutableStateOf(initialTool)

    private val brushBacking = mutableStateOf(MinBrush)

    /** Brush size in canvas pixels, always kept inside `1..8`. */
    var brushSize: Int
        get() = brushBacking.value
        set(value) {
            brushBacking.value = value.coerceIn(MinBrush, MaxBrush)
        }

    /** Whether the per-pixel grid is drawn (once zoom passes 4x). */
    var showGrid: Boolean by mutableStateOf(true)

    /** Whether the transparency checkerboard is drawn under the frame. */
    var showCheckerboard: Boolean by mutableStateOf(true)

    /** Whether onion-skin ghosts of the neighboring frames are drawn. */
    var onionSkinEnabled: Boolean by mutableStateOf(false)

    /** Symmetry guides to draw (and for hosts to mirror strokes with). */
    var symmetry: CanvasSymmetry by mutableStateOf(CanvasSymmetry.OFF)

    /** Current selection in half-open canvas pixel coords, or null. */
    var selection: Rect? by mutableStateOf(null)

    /**
     * Width of the canvas pixel grid being edited. [PixelCanvasPro] syncs
     * this from its [com.pixellab.core.model.SpriteProject]; hosts and
     * [screenToPixel] read it.
     */
    var canvasWidth: Int by mutableStateOf(0)

    /** Height of the canvas pixel grid being edited; see [canvasWidth]. */
    var canvasHeight: Int by mutableStateOf(0)

    /**
     * Clamps [z] into the interactive zoom range `1..64` with a logarithmic
     * feel: values that land within [ZoomSnapTolerance] (10%) of a
     * power-of-two rung snap onto that rung, so long pinch gestures settle on
     * the classic 1/2/4/8/16/32/64 ladder instead of arbitrary factors.
     *
     * @return the clamped, optionally snapped zoom, always inside `1..64`.
     */
    fun clampZoom(z: Float): Float {
        val clamped = if (z.isNaN()) MinZoom else z.coerceIn(MinZoom, MaxZoom)
        var rung = MinZoom
        while (rung <= MaxZoom) {
            if (abs(clamped - rung) <= rung * ZoomSnapTolerance) {
                return rung
            }
            rung *= 2f
        }
        return clamped
    }

    /**
     * Maps a canvas-local screen position to the grid cell under it, using
     * the live [panX]/[panY] and the given [cellSize]
     * (`cellSize = 12 * zoom` for [PixelCanvasPro]).
     *
     * @param x canvas-local screen x in pixels.
     * @param y canvas-local screen y in pixels.
     * @param cellSize screen size of one canvas pixel; non-positive values
     * yield null.
     * @return the grid cell, or null when the position falls outside the
     * `canvasWidth x canvasHeight` grid (or the grid is not synced yet).
     */
    fun screenToPixel(x: Float, y: Float, cellSize: Float): PixelPoint? {
        if (cellSize <= 0f || canvasWidth <= 0 || canvasHeight <= 0) return null
        val gx = floor((x - panX) / cellSize)
        val gy = floor((y - panY) / cellSize)
        if (gx < 0f || gy < 0f || gx >= canvasWidth || gy >= canvasHeight) return null
        return PixelPoint(gx.toInt(), gy.toInt())
    }
}
