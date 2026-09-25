package com.pixellab.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.history.HistoryEntry
import com.pixellab.core.engine.FloodFill
import com.pixellab.core.model.Layer
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject
import com.pixellab.ui.theme.PixelPanel
import com.pixellab.ui.theme.PixelTheme
import com.pixellab.ui.theme.PixelThemes
import kotlinx.coroutines.delay

/** Below this window width (dp) the scaffold stacks into a single column. */
private const val CompactWidthBreakpoint = 600f

/** Width of the left tool rail in the wide layout. */
private val ToolRailWidth = 232.dp

/** Width of the right inspector column in the wide layout. */
private val SidePanelWidth = 292.dp

/** Maximum iterations of a history jump loop (position safety guard). */
private const val MaxJumpIterations = 1024

/** Minimum playback tick, ms (keeps the timer loop schedulable). */
private const val MinTickMs = 16

/**
 * The assembled pixel-art editor: every PR10 component wired to one
 * [EditorSession].
 *
 * ## State ownership map
 *
 * | Slice | Owner |
 * | --- | --- |
 * | Document ([SpriteProject]), history, dirty flag | [EditorSession] (remembered per project id) |
 * | `liveProject` mirror | snapshot `mutableStateOf` fed by the session listener — the only recomposition driver of the document |
 * | Zoom / pan / tool / brush / grid / selection | [CanvasState] (shared by [PixelCanvasPro], [CanvasToolbar], [ZoomControls]) |
 * | Draw colors | `primaryColor` / `secondaryColor` snapshot states (swap via `X`) |
 * | Selected palette swatch | `paletteIndex` snapshot state |
 * | Playback (frame, loop, onion) | [PlaybackState] snapshot state, advanced by [TimelineController] |
 * | Cursor readout | `cursor` snapshot state fed by [PixelCanvasPro] |
 * | History panel visibility, CRT preview | local toggle states |
 *
 * Every mutation goes through `session.edit(...)` (single undo step) — frame
 * selection coalesces into one rolling entry so switching frames does not
 * flood the history. [PixelCanvasPro] gesture callbacks implement paint /
 * erase / fill / pick directly on the active cel (`FloodFill.flood`, no
 * engine instance — the session already owns history). The
 * [ShortcutLayer] wraps the whole layout and routes tool / brush / zoom /
 * color-swap / nudge / palette-slot / save / undo / redo actions.
 *
 * Playback: a `LaunchedEffect` loop ticks every
 * [SpriteProject.effectiveFrameDuration] of the current frame and folds the
 * tick through [TimelineController.advance] — loop modes, per-frame duration
 * overrides and ping-pong direction live there. While playing, the canvas
 * locks input (`PixelCanvasPro(playing = ...)`).
 *
 * Responsive: below [CompactWidthBreakpoint] (measured with
 * [BoxWithConstraints]) the layout stacks tool rail, canvas, inspector
 * column, timeline and status bar into one scroll-free column; the status
 * bar switches to its compact segment set.
 *
 * @param project the initial document; a *different project id* resets the
 *   session (fresh history) — live editing keeps the same instance.
 * @param theme theme override; defaults to [PixelThemes.DARK].
 * @param onProjectChange invoked after every effective edit/undo/redo with
 *   the new document.
 * @param onExport optional export hook (toolbar button).
 * @param onSave optional save hook; `Ctrl+S` marks the session saved and
 *   invokes this.
 * @param modifier host modifier.
 */
@Composable
fun PixelEditorScaffold(
    project: SpriteProject,
    theme: PixelTheme = PixelThemes.DARK,
    onProjectChange: (SpriteProject) -> Unit = {},
    onExport: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // ---- document state --------------------------------------------------
    var liveProject by remember { mutableStateOf(project) }
    var historyMeta by remember { mutableStateOf(HistorySnapshot.empty()) }
    val session = remember(project.id) {
        EditorSession(project).also { s ->
            s.setOnProjectChanged { next ->
                liveProject = next
                historyMeta = HistorySnapshot.of(s)
                onProjectChange(next)
            }
        }
    }
    if (liveProject.id != project.id) {
        // Host swapped the document: adopt it and resync the mirrors.
        liveProject = project
        historyMeta = HistorySnapshot.of(session)
    }

    // ---- view state -------------------------------------------------------
    val canvasState = remember { CanvasState() }
    var primaryColor by remember { mutableStateOf(session.project.palette[0]) }
    var secondaryColor by remember { mutableStateOf(0xFF000000.toInt()) }
    var paletteIndex by remember { mutableStateOf(0) }
    var cursor by remember { mutableStateOf<PixelPointerInfo?>(null) }
    var playback by remember {
        mutableStateOf(
            PlaybackState(frameIndex = project.activeFrameIndex, fps = project.fps),
        )
    }
    val controller = remember { TimelineController() }
    val shortcutMap = remember { ShortcutMap() }
    var lastShortcut by remember { mutableStateOf<Shortcut?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var previewOpen by remember { mutableStateOf(false) }

    if (playback.onionSkin != canvasState.onionSkinEnabled) {
        canvasState.onionSkinEnabled = playback.onionSkin
    }

    /** Records one session edit from a UI callback (single funnel). */
    fun commit(label: String, coalesceKey: String? = null, transform: (SpriteProject) -> SpriteProject) {
        session.edit(label, coalesceKey, transform)
    }

    fun save() {
        session.markSaved()
        historyMeta = HistorySnapshot.of(session)
        onSave?.invoke()
    }

    // ---- canvas gestures ----------------------------------------------------
    fun applyStroke(points: List<PixelPoint>, color: Int) {
        if (points.isEmpty()) return
        commit(if (color == 0) "Erase" else "Paint") { p ->
            val cel = p.activeCel() ?: PixelFrame.blank(p.width, p.height)
            p.withActiveCel(cel.withPixels(points, color))
        }
    }

    // ---- playback clock ---------------------------------------------------
    LaunchedEffect(playback.playing, liveProject) {
        while (playback.playing) {
            val index = playback.frameIndex.coerceIn(0, liveProject.frameCount - 1)
            val tick = liveProject.effectiveFrameDuration(index).coerceAtLeast(MinTickMs)
            delay(tick.toLong())
            playback = controller.advance(liveProject, playback, tick)
        }
    }

    // ---- shortcut routing ----------------------------------------------------
    val handleAction: (ShortcutAction) -> Unit = { action ->
        when (action) {
            ShortcutAction.UNDO -> session.undo()
            ShortcutAction.REDO -> session.redo()
            ShortcutAction.SAVE -> save()
            ShortcutAction.BRUSH_GROW -> canvasState.brushSize = canvasState.brushSize + 1
            ShortcutAction.BRUSH_SHRINK -> canvasState.brushSize = canvasState.brushSize - 1
            ShortcutAction.ZOOM_IN -> canvasState.zoom = canvasState.zoom * 1.5f
            ShortcutAction.ZOOM_OUT -> canvasState.zoom = canvasState.zoom / 1.5f
            ShortcutAction.SWAP_COLORS -> {
                val swap = primaryColor
                primaryColor = secondaryColor
                secondaryColor = swap
            }
            ShortcutAction.NUDGE_LEFT -> canvasState.selection = canvasState.selection?.nudged(-1f, 0f)
            ShortcutAction.NUDGE_RIGHT -> canvasState.selection = canvasState.selection?.nudged(1f, 0f)
            ShortcutAction.NUDGE_UP -> canvasState.selection = canvasState.selection?.nudged(0f, -1f)
            ShortcutAction.NUDGE_DOWN -> canvasState.selection = canvasState.selection?.nudged(0f, 1f)
            ShortcutAction.SELECT_PALETTE_SLOT -> {
                val slot = lastShortcut?.key?.toIntOrNull()
                if (slot != null && slot in 1..liveProject.palette.size) {
                    paletteIndex = slot - 1
                    primaryColor = liveProject.palette[slot - 1]
                }
            }
            else -> ShortcutMap.toolFor(action)?.let { tool -> canvasState.tool = tool }
        }
    }

    ShortcutLayer(
        theme = theme,
        map = shortcutMap,
        onAction = handleAction,
        onShortcut = { lastShortcut = it },
    ) {
        BoxWithConstraints(modifier = modifier) {
            val wide = maxWidth.value >= CompactWidthBreakpoint

            // ---- shared content builders ------------------------------------
            val toolRail: @Composable () -> Unit = {
                PixelPanel(theme = theme, title = "Tools") {
                    CanvasToolbar(
                        state = canvasState,
                        onToolChange = { canvasState.tool = it },
                        onBrushSizeChange = { canvasState.brushSize = it },
                        onGridToggle = { canvasState.showGrid = !canvasState.showGrid },
                        onSymmetryChange = { canvasState.symmetry = it },
                    )
                }
            }
            val inspector: @Composable () -> Unit = {
                Column {
                    PixelPanel(theme = theme, title = "Color") {
                        ColorSliders(
                            theme = theme,
                            argb = primaryColor,
                            onColorChange = { primaryColor = it },
                        )
                    }
                    PixelPanel(theme = theme, title = "Palette") {
                        PaletteEditor(
                            theme = theme,
                            palette = liveProject.palette,
                            selected = paletteIndex,
                            onSelect = { index ->
                                paletteIndex = index
                                primaryColor = liveProject.palette[index]
                            },
                            onPaletteChange = { next ->
                                commit("Palette edit") { it.withPalette(next) }
                            },
                            currentColor = primaryColor,
                        )
                    }
                    PixelPanel(theme = theme, title = "Layers") {
                        LayerPanel(
                            project = liveProject,
                            onActiveLayerChange = { id ->
                                commit("Select layer", coalesceKey = "layer-select") {
                                    it.withActiveLayer(id)
                                }
                            },
                            onAddLayer = { name -> commit("Add layer") { addLayer(it, name) } },
                            onRemoveLayer = { id -> commit("Remove layer") { removeLayer(it, id) } },
                            onRenameLayer = { id, name -> commit("Rename layer") { renameLayer(it, id, name) } },
                            onMoveLayer = { id, target ->
                                commit("Move layer") { p -> moveLayerTo(p, id, target) }
                            },
                            onLayerOpacityChange = { id, value ->
                                commit("Layer opacity", coalesceKey = "opacity-$id") {
                                    setLayerOpacity(it, id, value)
                                }
                            },
                            onLayerVisibleChange = { id, value ->
                                commit("Layer visibility") { setLayerVisible(it, id, value) }
                            },
                            onLayerLockedChange = { id, value ->
                                commit("Layer lock") { setLayerLocked(it, id, value) }
                            },
                        )
                    }
                    if (historyOpen) {
                        PixelPanel(theme = theme, title = "History") {
                            HistoryPanel(
                                theme = theme,
                                entries = historyMeta.entries,
                                canUndo = historyMeta.canUndo,
                                canRedo = historyMeta.canRedo,
                                selectedDepth = historyMeta.entries.size,
                                modified = historyMeta.modified,
                                onUndo = { session.undo() },
                                onRedo = { session.redo() },
                                onJumpTo = { depth -> jumpHistoryTo(session, depth) },
                            )
                        }
                    }
                }
            }
            val timeline: @Composable () -> Unit = {
                TimelinePro(
                    theme = theme,
                    project = liveProject,
                    playback = playback,
                    onFrameSelect = { index ->
                        playback = playback.copy(frameIndex = index)
                        commit("Select frame", coalesceKey = "frame-select") {
                            it.withActiveFrameIndex(index)
                        }
                    },
                    onAddFrame = { after -> commit("Add frame") { addFrame(it, after) } },
                    onDuplicateFrame = { index -> commit("Duplicate frame") { duplicateFrame(it, index) } },
                    onDeleteFrame = { index -> commit("Delete frame") { deleteFrame(it, index) } },
                    onMoveFrame = { from, to -> commit("Move frame") { moveFrame(it, from, to) } },
                    onFpsChange = { fps ->
                        playback = playback.copy(fps = fps)
                        commit("Set fps") { it.withFps(fps) }
                    },
                    onDurationChange = { index, ms ->
                        commit("Frame duration") { setFrameDuration(it, index, ms) }
                    },
                    onPlaybackChange = { playback = it },
                )
            }
            val status: @Composable () -> Unit = {
                StatusBar(
                    theme = theme,
                    dims = liveProject.width to liveProject.height,
                    cursor = cursor?.let { PixelPoint(it.x, it.y) },
                    zoom = canvasState.zoom,
                    activeLayer = liveProject.activeLayer.name,
                    toolName = canvasState.tool.name.lowercase(),
                    projectModified = historyMeta.modified,
                    compact = !wide,
                    extra = if (liveProject.tags.isEmpty()) null else "${liveProject.tags.size} tags",
                )
            }
            val transportBar: @Composable () -> Unit = {
                Row {
                    ZoomControls(
                        state = canvasState,
                        onZoomChange = { canvasState.zoom = it },
                        onReset = {
                            canvasState.zoom = 8f
                            canvasState.panX = 0f
                            canvasState.panY = 0f
                        },
                    )
                    TextButton(onClick = { historyOpen = !historyOpen }) {
                        Text(
                            text = if (historyOpen) "Hide history" else "History",
                            fontSize = 12.sp,
                            color = theme.textSecondary,
                        )
                    }
                    TextButton(onClick = { previewOpen = !previewOpen }) {
                        Text(
                            text = if (previewOpen) "Hide preview" else "Preview",
                            fontSize = 12.sp,
                            color = theme.textSecondary,
                        )
                    }
                    if (onExport != null) {
                        TextButton(onClick = onExport) {
                            Text(text = "Export", fontSize = 12.sp, color = theme.accent)
                        }
                    }
                    if (onSave != null) {
                        TextButton(onClick = { save() }) {
                            Text(text = "Save", fontSize = 12.sp, color = theme.primary)
                        }
                    }
                }
            }
            val canvas: @Composable (Modifier) -> Unit = { canvasModifier ->
                PixelCanvasPro(
                    project = liveProject,
                    frameIndex = playback.frameIndex,
                    modifier = canvasModifier,
                    state = canvasState,
                    onDrawPixels = { points, color -> applyStroke(points, color) },
                    onFill = { point ->
                        commit("Fill") { p ->
                            val cel = p.activeCel() ?: PixelFrame.blank(p.width, p.height)
                            val pixels = FloodFill.flood(
                                p.width, p.height, cel.pixels,
                                point.x, point.y, primaryColor,
                            )
                            p.withActiveCel(PixelFrame.of(p.width, p.height, pixels))
                        }
                    },
                    onPick = { point ->
                        val frame = liveProject.compositeFrame(playback.frameIndex.coerceIn(0, liveProject.frameCount - 1))
                        primaryColor = frame[point.x, point.y]
                        paletteIndex = liveProject.palette.findClosest(primaryColor)
                    },
                    onSelectionChange = { canvasState.selection = it },
                    onCursorMove = { cursor = it },
                    strokeColor = primaryColor,
                    playing = playback.playing,
                )
            }
            val preview: @Composable () -> Unit = {
                if (previewOpen) {
                    val frames = remember(liveProject) {
                        (0 until liveProject.frameCount).map { liveProject.compositeFrame(it) }
                    }
                    PixelArtPreview(
                        theme = theme,
                        frames = frames,
                        style = PreviewStyle(scale = 4),
                        playback = playback,
                        modifier = Modifier
                            .height(160.dp)
                            .padding(vertical = theme.metrics.panelGap),
                    )
                }
            }

            // ---- responsive layout ------------------------------------------
            if (wide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.width(ToolRailWidth)) { toolRail() }
                    Column(modifier = Modifier.weight(1f)) {
                        transportBar()
                        canvas(Modifier.weight(1f))
                        preview()
                        timeline()
                        status()
                    }
                    Column(modifier = Modifier.width(SidePanelWidth)) { inspector() }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    toolRail()
                    transportBar()
                    canvas(Modifier.weight(1f))
                    preview()
                    timeline()
                    status()
                    inspector()
                }
            }
        }
    }
}

/** Mirrors the session's externally visible history state for recomposition. */
private data class HistorySnapshot(
    val entries: List<HistoryEntry>,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val modified: Boolean,
) {
    companion object {
        fun of(session: EditorSession): HistorySnapshot = HistorySnapshot(
            entries = session.historyEntries(),
            canUndo = session.canUndo,
            canRedo = session.canRedo,
            modified = session.isModified,
        )

        fun empty(): HistorySnapshot = HistorySnapshot(emptyList(), false, false, false)
    }
}

/**
 * Walks the timeline so exactly [depth] entries end up applied, using
 * repeated undo/redo. Guarded by [MaxJumpIterations]; every successful step
 * fires the session listener so the UI follows along.
 */
private fun jumpHistoryTo(session: EditorSession, depth: Int) {
    var iterations = 0
    while (session.historyEntries().size > depth && session.canUndo && iterations++ < MaxJumpIterations) {
        if (session.undo() == null) break
    }
    iterations = 0
    while (session.historyEntries().size < depth && session.canRedo && iterations++ < MaxJumpIterations) {
        if (session.redo() == null) break
    }
}

/** Shifts a half-open selection rect by whole pixels. */
private fun Rect.nudged(dx: Float, dy: Float): Rect = Rect(left + dx, top + dy, right + dx, bottom + dy)

// ---- pure layer operations (project -> project) ---------------------------

/**
 * Appends a new empty layer on top of the stack and activates it. New ids
 * are `max(existing) + 1`; [SpriteProject.withLayers] re-syncs the internal
 * counter from the list.
 */
private fun addLayer(project: SpriteProject, name: String): SpriteProject {
    val id = project.layers.maxOf { it.id } + 1
    val layer = Layer(id = id, name = name.ifBlank { "Layer ${id + 1}" })
    return project.withLayers(project.layers + layer).withActiveLayer(id)
}

/**
 * Removes a layer and strips its cels from every frame (the project
 * invariant requires cels to reference live layers). Refuses to remove the
 * last layer. The active layer falls back to the top of the remaining stack.
 */
private fun removeLayer(project: SpriteProject, layerId: Int): SpriteProject {
    if (project.layerCount < 2 || project.layer(layerId) == null) return project
    // Strip the layer's cels from every frame FIRST: the project invariant
    // requires cels to reference live layers, so constructing a project whose
    // cels still point at a removed layer would throw.
    val strippedFrames = project.frames.map { it.withCel(layerId, null) }
    val remaining = project.layers.filterNot { it.id == layerId }
    val next = project.withFrames(strippedFrames).withLayers(remaining)
    val active = if (remaining.any { it.id == project.activeLayerId }) project.activeLayerId else remaining.last().id
    return next.withActiveLayer(active)
}

/** Renames the layer with [layerId]. */
private fun renameLayer(project: SpriteProject, layerId: Int, name: String): SpriteProject {
    if (name.isBlank() || project.layer(layerId) == null) return project
    return project.withLayers(project.layers.map { if (it.id == layerId) it.withName(name) else it })
}

/**
 * Moves the layer with [layerId] to the bottom-up [targetIndex] (clamped).
 * The active layer follows the moved layer.
 */
private fun moveLayerTo(project: SpriteProject, layerId: Int, targetIndex: Int): SpriteProject {
    val from = project.layerIndex(layerId)
    if (from < 0) return project
    val to = targetIndex.coerceIn(0, project.layerCount - 1)
    if (from == to) return project
    val layers = project.layers.toMutableList()
    val moved = layers.removeAt(from)
    layers.add(to, moved)
    return project.withLayers(layers).withActiveLayer(layerId)
}

/** Sets the whole-layer opacity of [layerId] (`0..1`). */
private fun setLayerOpacity(project: SpriteProject, layerId: Int, opacity: Float): SpriteProject =
    project.withLayers(project.layers.map { if (it.id == layerId) it.withOpacity(opacity) else it })

/** Toggles visibility of [layerId]. */
private fun setLayerVisible(project: SpriteProject, layerId: Int, visible: Boolean): SpriteProject =
    project.withLayers(project.layers.map { if (it.id == layerId) it.withVisible(visible) else it })

/** Toggles the edit lock of [layerId]. */
private fun setLayerLocked(project: SpriteProject, layerId: Int, locked: Boolean): SpriteProject =
    project.withLayers(project.layers.map { if (it.id == layerId) it.withLocked(locked) else it })
