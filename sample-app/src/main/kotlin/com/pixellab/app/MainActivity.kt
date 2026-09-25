package com.pixellab.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pixellab.core.PixelLab
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.ui.LayerPanel
import com.pixellab.ui.PalettePanel
import com.pixellab.ui.PixelCanvas
import com.pixellab.ui.TimelineStrip
import kotlinx.coroutines.launch

/** Near-white ARGB color previewed for eraser strokes (eraser writes 0x00000000). */
private const val EraserPreviewColor = 0xFFFFF1E8.toInt()

/** Agent-command prefix that renders free text through the template engine. */
private const val AgentTextPrefix = "text:"

/** Agent-command prefix that instantiates a built-in template. */
private const val AgentTemplatePrefix = "template:"

/** Agent keyword running the breathe effect on the current project. */
private const val AgentBreatheKeyword = "breathe"

/** Agent keyword clearing the active cel of the active frame. */
private const val AgentClearKeyword = "clear"

/** Usage hint surfaced as a snackbar for unrecognized agent commands. */
private const val AgentUsageHint = "text:<msg> | template:<id> | breathe | clear"

/** Fixed width of the palette panel beside the canvas. */
private val PalettePanelWidth = 96.dp

/** Minimum height of the palette + canvas row. */
private val CanvasRowMinHeight = 240.dp

/** Maximum height of the palette + canvas row. */
private val CanvasRowMaxHeight = 360.dp

/** Maximum height of the collapsible layer panel. */
private val LayerPanelMaxHeight = 176.dp

/** Outer padding of the whole sample app content. */
private val ContentPadding = 8.dp

/** Horizontal padding separating the toolbar chips. */
private val ChipPadding = 4.dp

/**
 * Editing tools offered on the sample app toolbar.
 *
 * @property label text shown on the toolbar chip.
 */
private enum class Tool(val label: String) {
    /** Freehand brush painting the selected color. */
    PENCIL("Pencil"),

    /** Freehand brush erasing pixels to fully transparent. */
    ERASER("Eraser"),

    /** Flood fill starting at the tapped pixel, in the selected color. */
    FILL("Fill"),

    /** Eyedropper sampling the composited canvas back into the selection. */
    PICKER("Picker"),
}

/**
 * Sample app entry point for the Pixel Lab library.
 *
 * Installs a single Compose tree: a dark Material3 [MaterialTheme] wrapping a
 * [Scaffold] that hosts [PixelLabSampleApp]. All editing state lives inside
 * the composable; the activity itself holds nothing.
 */
class MainActivity : ComponentActivity() {

    /**
     * Installs the Compose content.
     *
     * The suppression exists only for the compile-only activity stub, whose
     * `onCreate` is — unlike the real androidx `ComponentActivity` — a final
     * method; against the real Android dependency this is a plain, standard
     * override.
     */
    @Suppress("OVERRIDING_FINAL_MEMBER")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold { PixelLabSampleApp() }
            }
        }
    }
}

/**
 * Root composable of the Pixel Lab sample app.
 *
 * Hosts every piece of the library's demo surface in one column: the tool bar
 * (tool chips, undo/redo, export menu), the palette + canvas row, the
 * animation timeline, a collapsible layer panel and the local agent-command
 * line. Everything operates on a single [SpriteProject][com.pixellab.core.model.SpriteProject]
 * state created once and threaded through [PixelLab]'s engines; every engine
 * mutation replaces the project (immutable value semantics), which drives
 * recomposition.
 *
 * Drawing state machine: Pencil and Eraser strokes are collected point by
 * point (deduplicated) and committed with exactly one engine call on pointer
 * up, so a whole stroke forms a single undo unit. While the stroke is in
 * flight the points preview live on the canvas — the eraser previews in a
 * near-white tint while it actually writes transparent black. Fill and Picker
 * act immediately on pointer down.
 */
@Composable
fun PixelLabSampleApp() {
    val lab = remember { PixelLab.create() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var project by remember {
        mutableStateOf(SpriteFactory.create("sample", 32, 32, BuiltInPalettes.PICO8))
    }
    var tool by remember { mutableStateOf(Tool.PENCIL) }
    var selectedColor by remember { mutableStateOf(BuiltInPalettes.PICO8[7]) }
    val strokePoints = remember { mutableStateListOf<PixelPoint>() }
    var playing by remember { mutableStateOf(false) }
    var onionSkinEnabled by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var layersExpanded by remember { mutableStateOf(false) }
    var agentInput by remember { mutableStateOf("") }

    val activeFrameIndex = project.activeFrameIndex
    val strokePreviewColor = if (tool == Tool.ERASER) EraserPreviewColor else selectedColor
    // The canvas ghosts the neighbors of the project it receives, so the
    // engine's onion-skin data doubles as the gate for the canvas overlay.
    val onionSkinData = remember(project, onionSkinEnabled, activeFrameIndex) {
        if (onionSkinEnabled) lab.animation.onionSkin(project) else null
    }

    /** Posts [message] as a snackbar on this composition's scope. */
    fun report(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    /** Handles a pointer press: starts a stroke or fires the one-shot tools. */
    fun handleDown(point: PixelPoint) {
        when (tool) {
            Tool.PENCIL, Tool.ERASER -> {
                strokePoints.clear()
                strokePoints.add(point)
            }
            Tool.FILL -> try {
                project = lab.engine.fill(project, point.x, point.y, selectedColor)
            } catch (error: IllegalArgumentException) {
                report("Fill failed: ${error.message}")
            }
            Tool.PICKER -> try {
                selectedColor = lab.engine.pickColor(project, point.x, point.y)
            } catch (error: IllegalArgumentException) {
                report("Pick failed: ${error.message}")
            }
        }
    }

    /** Extends the in-flight stroke by a newly entered cell, deduplicated. */
    fun handleDrag(point: PixelPoint) {
        if (tool == Tool.PENCIL || tool == Tool.ERASER) {
            if (strokePoints.lastOrNull() != point) strokePoints.add(point)
        }
    }

    /**
     * Ends the stroke: the release cell joins the point list and the whole
     * stroke is committed with one engine call, making it one undo unit.
     */
    fun handleUp(point: PixelPoint) {
        if (tool != Tool.PENCIL && tool != Tool.ERASER) return
        if (strokePoints.lastOrNull() != point) strokePoints.add(point)
        if (strokePoints.isEmpty()) return
        try {
            project = if (tool == Tool.PENCIL) {
                lab.engine.drawPixels(project, strokePoints.toList(), selectedColor)
            } else {
                lab.engine.erasePixels(project, strokePoints.toList())
            }
        } catch (error: IllegalArgumentException) {
            report("Draw failed: ${error.message}")
        }
        strokePoints.clear()
    }

    /**
     * Runs one local agent command (prefix-parsed). Success clears the input;
     * failures keep it and surface the message as a snackbar.
     */
    fun runAgentCommand(raw: String) {
        val command = raw.trim()
        if (command.isEmpty()) return
        try {
            when {
                command.startsWith(AgentTextPrefix) -> {
                    val frame = lab.template.generateText(
                        command.removePrefix(AgentTextPrefix),
                        color = selectedColor,
                    )
                    report("Text frame: ${frame.width}x${frame.height}")
                }
                command.startsWith(AgentTemplatePrefix) -> {
                    val id = command.removePrefix(AgentTemplatePrefix).trim()
                    project = lab.template.apply(id)
                    report("Template '$id' loaded")
                }
                command == AgentBreatheKeyword -> {
                    project = lab.animation.breathe(project)
                    report("Breathe: ${project.frameCount} frames")
                }
                command == AgentClearKeyword -> {
                    project = lab.engine.clearCanvas(project)
                    report("Canvas cleared")
                }
                else -> report(AgentUsageHint)
            }
            agentInput = ""
        } catch (error: IllegalArgumentException) {
            report("Agent error: ${error.message}")
        } catch (error: IllegalStateException) {
            report("Agent error: ${error.message}")
        }
    }

    /** Exports the current project in [kind] format into the external dir. */
    fun requestExport(kind: ExportKind) {
        exportMenuOpen = false
        val dir = context.getExternalFilesDir(null)
        if (dir == null) {
            report("External storage unavailable")
            return
        }
        scope.launch {
            try {
                val result = doExport(kind, lab, project, dir)
                report("${kind.label} saved: ${result.file.name} (${result.byteCount} B)")
            } catch (error: Exception) {
                report("Export failed: ${error.message}")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(ContentPadding),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Tool bar: tool chips, undo/redo and the export menu.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Center,
            ) {
                for (candidate in Tool.entries) {
                    FilterChip(
                        selected = tool == candidate,
                        onClick = { tool = candidate },
                        label = { Text(text = candidate.label) },
                        modifier = Modifier.padding(horizontal = ChipPadding),
                    )
                }
                IconButton(
                    onClick = { lab.engine.undo(project.id)?.let { project = it } },
                    enabled = lab.engine.canUndo(project.id),
                ) {
                    Icon(imageVector = Icons.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(
                    onClick = { lab.engine.redo(project.id)?.let { project = it } },
                    enabled = lab.engine.canRedo(project.id),
                ) {
                    Icon(imageVector = Icons.Filled.Redo, contentDescription = "Redo")
                }
                Box {
                    TextButton(onClick = { exportMenuOpen = true }) {
                        Text(text = "Export")
                    }
                    DropdownMenu(
                        expanded = exportMenuOpen,
                        onDismissRequest = { exportMenuOpen = false },
                    ) {
                        for (kind in ExportKind.entries) {
                            DropdownMenuItem(
                                text = { Text(text = kind.label) },
                                onClick = { requestExport(kind) },
                            )
                        }
                    }
                }
            }

            // 2. Palette + canvas row. Row children default to top alignment,
            // which places the palette column and the canvas at the top edge
            // while the canvas still fills the row height.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = CanvasRowMinHeight, max = CanvasRowMaxHeight),
            ) {
                PalettePanel(
                    palette = project.palette,
                    selectedColor = selectedColor,
                    onColorSelect = { selectedColor = it },
                    onPaletteSwitch = { project = project.withPalette(it) },
                    modifier = Modifier.width(PalettePanelWidth),
                )
                PixelCanvas(
                    project = project,
                    frameIndex = activeFrameIndex,
                    onionSkin = onionSkinData?.let { project },
                    strokePreview = strokePoints,
                    strokePreviewColor = strokePreviewColor,
                    onPixelDown = { handleDown(it) },
                    onPixelDrag = { handleDrag(it) },
                    onPixelUp = { handleUp(it) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }

            // 3. Animation timeline.
            TimelineStrip(
                project = project,
                activeFrameIndex = activeFrameIndex,
                playing = playing,
                onFrameSelect = { project = project.withActiveFrameIndex(it) },
                onAddFrame = { project = lab.animation.addFrame(project) },
                onCloneFrame = { project = lab.animation.cloneFrame(project, activeFrameIndex) },
                onDeleteFrame = { project = lab.animation.deleteFrame(project, activeFrameIndex) },
                onFpsChange = { project = lab.animation.setFps(project, it) },
                onOnionSkinChange = { onionSkinEnabled = it },
                modifier = Modifier.fillMaxWidth(),
            )

            // 4. Collapsible layer panel.
            TextButton(onClick = { layersExpanded = !layersExpanded }) {
                Text(text = if (layersExpanded) "Hide layers" else "Show layers")
            }
            if (layersExpanded) {
                LayerPanel(
                    project = project,
                    onActiveLayerChange = { project = project.withActiveLayer(it) },
                    onAddLayer = { project = lab.engine.addLayer(project, it) },
                    onRemoveLayer = { project = lab.engine.removeLayer(project, it) },
                    onRenameLayer = { layerId, name ->
                        project = lab.engine.renameLayer(project, layerId, name)
                    },
                    onMoveLayer = { layerId, toIndex ->
                        project = lab.engine.moveLayer(project, layerId, toIndex)
                    },
                    onLayerOpacityChange = { layerId, opacity ->
                        project = lab.engine.setLayerOpacity(project, layerId, opacity)
                    },
                    onLayerVisibleChange = { layerId, visible ->
                        project = lab.engine.setLayerVisible(project, layerId, visible)
                    },
                    onLayerLockedChange = { layerId, locked ->
                        project = lab.engine.setLayerLocked(project, layerId, locked)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = LayerPanelMaxHeight),
                )
            }

            // 5. Local agent command line.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Center,
            ) {
                OutlinedTextField(
                    value = agentInput,
                    onValueChange = { agentInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(text = "Agent command") },
                    placeholder = { Text(text = AgentUsageHint) },
                )
                IconButton(onClick = { runAgentCommand(agentInput) }) {
                    Icon(imageVector = Icons.Filled.Send, contentDescription = "Run agent command")
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState)
    }
}
