# Pixel Lab — API Contracts (v2, base build)

Single source of truth for module signatures during base construction.
Files under `model/`, `palette/`, root config files are **frozen** (written by
the coordinator). Agents implement their section verbatim. Kotlin 2.0.21,
JDK 17, 4-space indent, KDoc on all public APIs, no TODOs, no test code in
repo, no git operations, do not modify any file outside your task list.

Package roots: `com.pixellab.core` (pixel-core), `com.pixellab.ui`
(pixel-ui-compose), `com.pixellab.mcp` (pixel-mcp), `com.pixellab.app`
(sample-app). JNI classes live in `com.pixellab.core.nativelib` (`native` is a
Kotlin keyword, never use it as a package name). Native library name:
`pixel_lab_native`.

Frozen files (read, never modify): `model/PixelFrame.kt`, `model/Palette.kt`,
`model/Layer.kt`, `model/Frame.kt`, `model/SpriteProject.kt`,
`model/CodexPetSpec.kt`, `PixelResult.kt`, `PixelLabConfig.kt`,
`PixelLab.kt`, `palette/LabColor.kt`, `palette/BuiltInPalettes.kt`,
`convert/ConvertTypes.kt`, all of `nativelib/`, `template/FontData.kt`
(delivered separately by the coordinator).

---

## §1 engine/ — PixelEngine (files: PixelEngine.kt, DrawOps.kt, FloodFill.kt)

```kotlin
package com.pixellab.core.engine

/** Stateful drawing engine; per-project undo history; NOT thread-safe. */
class PixelEngine(private val config: com.pixellab.core.PixelLabConfig) {

    // --- canvas-level (edit the active layer's cel of the active frame) ---
    fun drawPixel(project: SpriteProject, x: Int, y: Int, argb: Int): SpriteProject
    fun drawPixels(project: SpriteProject, points: List<PixelPoint>, argb: Int): SpriteProject
    fun erasePixels(project: SpriteProject, points: List<PixelPoint>): SpriteProject
    fun drawLine(project: SpriteProject, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int, thickness: Int = 1): SpriteProject
    fun drawRect(project: SpriteProject, x: Int, y: Int, w: Int, h: Int, argb: Int, filled: Boolean): SpriteProject
    fun drawCircle(project: SpriteProject, cx: Int, cy: Int, r: Int, argb: Int, filled: Boolean): SpriteProject
    fun fill(project: SpriteProject, x: Int, y: Int, argb: Int, tolerance: Int = 0): SpriteProject
    fun pickColor(project: SpriteProject, x: Int, y: Int): Int          // throws IAE out of bounds
    fun replaceColor(project: SpriteProject, from: Int, to: Int, tolerance: Int = 0): SpriteProject
    fun clearCanvas(project: SpriteProject): SpriteProject              // active cel only
    fun applyFrame(project: SpriteProject, frame: PixelFrame): SpriteProject  // size must match project
    fun drawStroke(project: SpriteProject, points: List<PixelPoint>, argb: Int, thickness: Int = 1): SpriteProject
    fun shiftCanvas(project: SpriteProject, dx: Int, dy: Int): SpriteProject
    fun flipCanvasHorizontal(project: SpriteProject): SpriteProject
    fun flipCanvasVertical(project: SpriteProject): SpriteProject
    fun rotateCanvas90(project: SpriteProject): SpriteProject  // square projects only (else IAE)
    fun outlineCanvas(project: SpriteProject, argb: Int): SpriteProject  // 4-neighbor outline of opaque px

    // --- layers ---
    fun addLayer(project: SpriteProject, name: String): SpriteProject          // new layer becomes active
    fun removeLayer(project: SpriteProject, layerId: Int): SpriteProject       // >=1 layer must remain
    fun renameLayer(project: SpriteProject, layerId: Int, name: String): SpriteProject
    fun moveLayer(project: SpriteProject, layerId: Int, toIndex: Int): SpriteProject
    fun setLayerOpacity(project: SpriteProject, layerId: Int, opacity: Float): SpriteProject
    fun setLayerVisible(project: SpriteProject, layerId: Int, visible: Boolean): SpriteProject
    fun setLayerLocked(project: SpriteProject, layerId: Int, locked: Boolean): SpriteProject
    fun clearLayer(project: SpriteProject, layerId: Int): SpriteProject

    // --- history (keyed by project.id; engine is the only history owner) ---
    data class HistoryEntry(val label: String, val timestamp: Long)
    fun canUndo(projectId: String): Boolean
    fun canRedo(projectId: String): Boolean
    fun undo(projectId: String): SpriteProject?      // null when nothing to undo
    fun redo(projectId: String): SpriteProject?      // null when nothing to redo
    fun historyInfo(projectId: String): List<HistoryEntry>   // oldest..newest, undo stack only
    fun clearHistory(projectId: String)
}
```

Rules: every mutating method validates args first, computes the next project,
then pushes history **after** success (exception paths never pollute history).
All ops reject writes when the active layer `locked == true` (IAE). History
capacity = `config.maxUndoDepth`; oldest evicted. Undo/redo redo-stacks
transfer semantics: `undo` pushes the current state onto the redo stack; new
drawing clears the redo stack. `DrawOps` (object) provides pure geometry:
`line(x0,y0,x1,y1): List<PixelPoint>`, `thickLine(...thickness)`,
`rect(x,y,w,h,filled)`, `circle(cx,cy,r,filled)`, `ellipse(x,y,w,h,filled)`,
`outline(points: Set<PixelPoint>): List<PixelPoint>` (4-neighbor boundary of a
point set). `FloodFill` object: `flood(frameWidth, frameHeight, pixels:
IntArray, x, y, replacement, tolerance): IntArray` — scanline algorithm,
returns a new array (or the same instance when nothing changed); tolerance is
max abs per-channel diff (A/R/G/B, alpha included); out-of-bounds seed returns
input array unchanged. Fill dispatches to native via
`com.pixellab.core.nativelib.NativePixelOps.floodFill` when
`NativeLib.load()` succeeds, else Kotlin. `shiftCanvas`/`flip*`/`rotate90`/
`outlineCanvas` operate on the active cel only (other cels untouched);
missing cel = blank frame first.

## §2 animation/ — AnimationEngine (file: AnimationEngine.kt)

```kotlin
package com.pixellab.core.animation

class AnimationEngine(private val config: com.pixellab.core.PixelLabConfig) {
    fun addFrame(project: SpriteProject, afterIndex: Int = project.frameCount - 1): SpriteProject
    fun cloneFrame(project: SpriteProject, frameIndex: Int): SpriteProject
    fun deleteFrame(project: SpriteProject, frameIndex: Int): SpriteProject  // >=1 frame must remain
    fun moveFrame(project: SpriteProject, fromIndex: Int, toIndex: Int): SpriteProject
    fun setFps(project: SpriteProject, fps: Int): SpriteProject
    fun setFrameDuration(project: SpriteProject, frameIndex: Int, durationMs: Int?): SpriteProject
    fun setTag(project: SpriteProject, name: String, startFrame: Int, endFrame: Int): SpriteProject
    fun removeTag(project: SpriteProject, name: String): SpriteProject

    data class OnionSkinData(val previous: PixelFrame?, val next: PixelFrame?)
    fun onionSkin(project: SpriteProject, range: Int = 1): OnionSkinData
    // previous = frame(active-1) scaled to alpha 0x66, next = frame(active+1) alpha 0x33;
    // out-of-range side -> null; clamps to farthest available frame in that direction.

    fun previewFrames(project: SpriteProject): List<PixelFrame>  // all frames composited
    fun breathe(project: SpriteProject, amplitudePx: Int = 1): SpriteProject
    // Appends 2x n frames: +amplitude shifted, -amplitude shifted (total 3n),
    // preserving per-cel structure and durations; new tag "breathe" over the range.
}
```

## §3 template/ — fonts + TemplateEngine

Frozen font files will exist at
`template/FontData.kt` (package `com.pixellab.core.template`, objects
`Font5x7`, `Font8x8` with `glyphHeight`, `glyphWidth`, `fun glyph(char): IntArray?`
returning column-encoded rows, plus `fun renderText(...)` helpers). Agents do
not write font data; coordinator delivers it.

```kotlin
package com.pixellab.core.template

class TemplateEngine(private val config: com.pixellab.core.PixelLabConfig) {
    /** Renders text as a pixel frame using the 5x7 font. */
    fun generateText(text: String, color: Int, font: PixelFont = Font5x7,
                     spacing: Int = 1, scale: Int = 1): PixelFrame
    /** Full project from a built-in template id. */
    fun apply(templateId: String, palette: Palette? = null): SpriteProject
    fun templateIds(): List<String>
    fun templateInfo(id: String): TemplateInfo?

    data class TemplateInfo(
        val id: String, val name: String, val width: Int, val height: Int,
        val description: String, val frames: Int, val layers: Int,
    )
}

/** Abstraction over the built-in bitmap fonts. */
interface PixelFont {
    val glyphWidth: Int
    val glyphHeight: Int
    fun glyph(c: Char): IntArray?   // column-encoded, [glyphWidth] entries, 7/8 row bits
}
```

Templates to ship (each a deterministic Kotlin-painted sprite):
`"clawd-pet"` 16x16 6-frame idle+blink pet (Clawd colors),
`"kimi-orb"` 16x16 4-frame floating orb, `"minecraft-block"` 16x16 1-frame
grass block, `"heart-pixel"` 8x8 2-frame pulsing heart, `"sword-pixel"` 16x16,
`"coin-spin"` 8x8 4-frame. Registered in a `TemplateLibrary` object with
builder DSL. Paint with `MutablePixelFrame` — no randomness, all deterministic.

## §4 convert/ — types, quantizer, ditherer, pipeline

```kotlin
package com.pixellab.core.convert

/** Frozen in convert/ConvertTypes.kt — identical definitions, do not redeclare. */
// QuantizeAlgorithm { MEDIAN_CUT(0), KMEANS(1), OCTREE(2) }
// DitherAlgorithm { NONE(0), FLOYD_STEINBERG(1), ATKINSON(2), BAYER_2X2(3), BAYER_4X4(4), BAYER_8X8(5), CHECKERBOARD(6) }
// QuantizeResult(palette: IntArray, mappedPixels: IntArray)
// ImageData(width: Int, height: Int, pixels: IntArray)

data class ConvertRequest(
    val image: ImageData,
    val targetWidth: Int,
    val targetHeight: Int,
    val palette: Palette? = null,          // null -> extract
    val colorCount: Int = 16,
    val algorithm: QuantizeAlgorithm = QuantizeAlgorithm.MEDIAN_CUT,
    val dither: DitherAlgorithm = DitherAlgorithm.FLOYD_STEINBERG,
    val ditherIntensity: Float = 1f,
    val edgeCleanup: Boolean = true,
    val alphaThreshold: Int = 128,
)
// ConvertResult / AnalysisResult / RefineInstructions: frozen in ConvertTypes.kt,
// identical to the definitions below — do not redeclare.

class ImageConverter(private val config: com.pixellab.core.PixelLabConfig) {
    suspend fun convert(request: ConvertRequest): PixelResult<ConvertResult>
    suspend fun analyze(image: ImageData): PixelResult<AnalysisResult>
    suspend fun refine(frame: PixelFrame, instructions: RefineInstructions): PixelResult<PixelFrame>
}
```

Internal objects (Kotlin fallbacks, `internal` visibility):
`KotlinQuantizer` (`fun quantize(pixels: IntArray, targetColors: Int, algorithm:
QuantizeAlgorithm): QuantizeResult` — deterministic; KMEANS uses fixed seed
`java.util.Random(0x504958)`), `KotlinDitherer` (`fun dither(pixels: IntArray,
width: Int, height: Int, palette: IntArray, algorithm: DitherAlgorithm,
intensity: Float): IntArray`). Facades `Quantizer` / `Ditherer` (objects,
`@Volatile var preferNative: Boolean = true`) dispatch to
`com.pixellab.core.nativelib` when `NativeLib.load()` succeeds, falling back
to Kotlin on `UnsatisfiedLinkError`/`IllegalArgumentException`.
Semantics (both paths identical): pixels with `alpha < 0x80` are transparent,
excluded from stats, and preserved in output; palette entries always opaque
(`alpha = 0xFF`); target colors clamped to `[2, 256]`; `NONE` dither = Lab
nearest-neighbor; FS/Atkinson error diffusion left-to-right, no serpentine;
Bayer threshold `(v + 0.5) / n^2` scaled by `63.75 * intensity`;
checkerboard `± intensity * 32` per channel; intensity NaN-safe clamped
`[0, 1]`. `convert` order: nearest-neighbor resample -> alpha binarization ->
quantize/map -> dither (on resampled pre-map pixels) -> edge cleanup ->
`PixelFrame`. All pure JVM (no android imports in pixel-core convert).

## §5 export/ — codecs and exporters

```kotlin
package com.pixellab.core.export

object PngCodec {          // PNG writer, no external deps (java.util.zip.Deflater)
    fun encode(frame: PixelFrame, scale: Int = 1): ByteArray
    fun idatStream(frame: PixelFrame, scale: Int = 1): ByteArray   // raw deflate payload for APNG fdAT reuse
}
object KotlinGifEncoder {  // GIF89a; slot 0 of the GCT is transparent
    fun encode(width: Int, height: Int, frames: List<PixelFrame>, delaysMs: List<Int>, loopCount: Int = 0): ByteArray
}
object ApngEncoder {
    fun encode(width: Int, height: Int, frames: List<PixelFrame>, delaysMs: List<Int>, loopCount: Int = 0): ByteArray
}
object BitmapIo {          // the ONLY file allowed to import android.graphics
    fun toPixels(bitmap: android.graphics.Bitmap): com.pixellab.core.convert.ImageData
    fun frameToBitmap(frame: PixelFrame, scale: Int = 1): android.graphics.Bitmap
}

enum class SpritesheetLayout { HORIZONTAL, VERTICAL, GRID }

class Exporter(private val config: com.pixellab.core.PixelLabConfig) {
    suspend fun exportPng(frame: PixelFrame, scale: Int = 1): PixelResult<ByteArray>
    suspend fun exportPng(project: SpriteProject, frameIndex: Int = project.activeFrameIndex, scale: Int = 1): PixelResult<ByteArray>
    suspend fun exportSpritesheet(project: SpriteProject, scale: Int = 1, layout: SpritesheetLayout = SpritesheetLayout.GRID, columns: Int = 8, margin: Int = 0): PixelResult<ByteArray>
    suspend fun exportGif(project: SpriteProject, loopCount: Int = 0, dither: DitherAlgorithm = DitherAlgorithm.FLOYD_STEINBERG): PixelResult<ByteArray>
    suspend fun exportApng(project: SpriteProject, loopCount: Int = 0): PixelResult<ByteArray>
    suspend fun exportWebp(frame: PixelFrame, scale: Int = 1, lossless: Boolean = true): PixelResult<ByteArray>
    suspend fun exportCodexPet(petName: String, project: SpriteProject, trackMap: Map<String, IntRange>): PixelResult<ByteArray>  // ZIP: spritesheet.png + pet.json
    suspend fun renderToBitmap(frame: PixelFrame, scale: Int = 1): PixelResult<android.graphics.Bitmap>
}
```

Rules: scale `1..16` (IAE outside, thrown before dispatcher switch); all
suspend entry points `withContext(Dispatchers.Default)`; deterministic output
(fixed ZIP entry timestamps `1577836800000L`); GIF prefers native
(`NativeGifEncoder`) then falls back to `KotlinGifEncoder`; APNG per-frame
delay = frame `durationMs ?: 1000 / fps`; Codex sheet = 1536x1872, cells
192x208 bottom-aligned, `pet.json` lists all 9 tracks in
`CodexPetSpec.TRACKS` order (missing track -> `frames: 0`), ZIP entries
`spritesheet.png` then `pet.json`, JSON strings manually escaped.

## §6 nativelib/ — JNI bridges (Kotlin side; C++ delivered by coordinator)

```kotlin
package com.pixellab.core.nativelib

object NativeLib {
    fun load(): Boolean          // System.loadLibrary("pixel_lab_native"), true once
    val available: Boolean       // true after successful load
    val version: String?         // native build version or null
}
object NativeQuantizer {
    fun quantize(pixels: IntArray, targetColors: Int, algorithmId: Int): com.pixellab.core.convert.QuantizeResult
    fun isAvailable: Boolean
}
object NativeDitherer {
    fun applyDither(pixels: IntArray, width: Int, height: Int, palette: IntArray, algorithmId: Int, intensity: Float): IntArray
    fun isAvailable: Boolean
}
object NativePixelOps {
    fun setPixelsBatch(pixels: IntArray, width: Int, height: Int, points: IntArray /* x,y pairs */, argb: Int): IntArray
    fun floodFill(pixels: IntArray, width: Int, height: Int, x: Int, y: Int, replacement: Int, tolerance: Int): IntArray
    fun compositeLayers(layerPixels: Array<IntArray>, widths: IntArray, heights: IntArray, opacities: FloatArray): IntArray
    fun isAvailable: Boolean
}
object NativeGifEncoder {
    fun encode(width: Int, height: Int, framePixels: Array<IntArray>, delaysMs: IntArray, loopCount: Int, quantAlgorithmId: Int, ditherId: Int): ByteArray
    fun isAvailable: Boolean
}
```

## §7 pixel-ui-compose/ — Compose components

```kotlin
package com.pixellab.ui

fun PixelFrame.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap  // Bitmap32-backed, cached (content-keyed, cap 256 -> clear)

@Composable
fun PixelCanvas(
    project: SpriteProject,
    frameIndex: Int = project.activeFrameIndex,
    modifier: Modifier = Modifier,
    scale: Float = 12f,
    showGrid: Boolean? = null,               // null = auto (grid when scale >= 4)
    onionSkin: SpriteProject? = null,        // rendered from this project's neighbors
    strokePreview: List<PixelPoint> = emptyList(),
    strokePreviewColor: Int = 0xFFFFFFFF.toInt(),
    onPixelDown: (PixelPoint) -> Unit = {},
    onPixelDrag: (PixelPoint) -> Unit = {},
    onPixelUp: (PixelPoint) -> Unit = {},
)

@Composable
fun PalettePanel(
    palette: Palette,
    selectedColor: Int,
    onColorSelect: (Int) -> Unit = {},
    onPaletteSwitch: (Palette) -> Unit = {},
    modifier: Modifier = Modifier,
)

@Composable
fun TimelineStrip(
    project: SpriteProject,
    activeFrameIndex: Int,
    playing: Boolean = false,
    onFrameSelect: (Int) -> Unit = {},
    onAddFrame: () -> Unit = {},
    onCloneFrame: () -> Unit = {},
    onDeleteFrame: () -> Unit = {},
    onFpsChange: (Int) -> Unit = {},
    onOnionSkinChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
)

@Composable
fun LayerPanel(
    project: SpriteProject,
    onActiveLayerChange: (Int) -> Unit = {},
    onAddLayer: (String) -> Unit = {},
    onRemoveLayer: (Int) -> Unit = {},
    onRenameLayer: (Int, String) -> Unit = {},
    onMoveLayer: (Int, Int) -> Unit = {},
    onLayerOpacityChange: (Int, Float) -> Unit = {},
    onLayerVisibleChange: (Int, Boolean) -> Unit = {},
    onLayerLockedChange: (Int, Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
)
```

Rules: no hardcoded colors except low-alpha grid gray — everything else
`MaterialTheme.colorScheme`; 44dp min touch targets; single-finger = draw,
two-finger = pan/zoom (pan/zoom applies only while >= 2 pointers active);
pixel hit test `floor((pos - offset) / scale)`; composited frame via
`remember(frameIndex, project) { project.compositeFrame(frameIndex) }`;
onion skin prev alpha 0.4 / next 0.25 drawn under the main frame; stroke
preview drawn as semi-transparent blocks; checkerboard 8px under the canvas
region using `surface`/`surfaceVariant`.

## §8 pixel-mcp/ — MCP server + tools

```kotlin
package com.pixellab.mcp

class PixelMcpServer(private val config: com.pixellab.core.PixelLabConfig = PixelLabConfig.default()) {
    fun start(port: Int): PixelMcpHandle
    fun stop()
    val isRunning: Boolean
    val port: Int?
    fun toolCount(): Int
    fun listTools(): List<McpToolInfo>
    data class McpToolInfo(val name: String, val description: String, val category: String)
    data class PixelMcpHandle(val port: Int, val startedAtMs: Long)
}
```

Implementation: **zero external dependencies** (module depends only on
pixel-core + coroutines). Transport = `java.net.ServerSocket` with a
hand-rolled minimal HTTP/1.1 parser: `GET /sse` opens a chunked
`text/event-stream` response kept open; `POST /messages` (and `/mcp`) accept
JSON-RPC 2.0 bodies and write responses both to the HTTP response and the SSE
stream. JSON = hand-rolled tree API in `json/Json.kt`
(`Json.parse(text): JsonElement`, `Json.write(element): String`,
`JsonElement` sealed hierarchy: `JsonObject`/`JsonArray`/`JsonString`/
`JsonNumber`/`JsonBoolean`/`JsonNull`, plus `obj { put("k", v) }`-style
builders and `long("k")`/`string("k")`/`int("k")`/`bool("k")`/`array("k")`
accessors). Tool registry of 36+ tools grouped by category: `canvas_*`,
`draw_*`, `layer_*`, `frame_*`, `palette_*`, `convert_*`, `anim_*`,
`export_*`, `text_*`, `template_*`, `project_*`. Tools are pure functions
from JsonObject params to JsonObject results; state lives in
`PixelSessionStore` (in-memory map session id -> SpriteProject). Server never
blocks: socket accept loop + tool execution on `Dispatchers.IO`/`Default`.
GIF/PNG results return byte counts (never base64 blobs). Tool ids are plain
dotted-free snake_case (`draw_pixel`). Handshake: JSON-RPC `initialize`
method, `tools/list`, `tools/call`. Full tool table lives in
`docs/MCP-TOOLS.md` (coordinator-delivered doc file; agents write tools to
match the registry names below):

canvas_create, canvas_info, canvas_clear, canvas_shift, canvas_flip_h,
canvas_flip_v, canvas_rotate, canvas_outline,
draw_pixel, draw_line, draw_rect, draw_circle, draw_pixels, draw_stroke,
fill, pick_color, replace_color, erase_pixels,
layer_add, layer_remove, layer_rename, layer_move, layer_set_opacity,
layer_set_visible, layer_set_locked, layer_list,
frame_add, frame_clone, frame_delete, frame_move, frame_set_duration,
frame_list,
palette_list, palette_switch, palette_closest_color,
convert_image, convert_analyze, convert_refine,
anim_set_fps, anim_tag, anim_breathe, anim_preview_count,
text_generate, template_apply, template_list,
export_png, export_spritesheet, export_gif, export_apng, export_codex_pet,
project_new, project_state, project_list, project_undo, project_redo

## §9 sample-app/

Package `com.pixellab.app`, one `ComponentActivity` named `MainActivity` +
`AppScaffold` composable + `Export.kt` helpers. Uses pixel-ui-compose
components; local agent-command line (`text:`, `template:`, `breathe`,
`clear`); export menu PNG/GIF/APNG/Codex ZIP writing to
`getExternalFilesDir(null)`; undo/redo buttons; Material3 dark theme.

---

## Cross-cutting invariants

1. `pixel-core` Kotlin is **pure JVM**: no `android.*` imports except
   `convert/BitmapIo.kt` and `export/Exporter.kt` (WebP/renderToBitmap paths
   only).
2. All algorithms deterministic: identical input -> identical output bytes.
3. Public APIs have KDoc; 4-space indent; no `TODO`/`FIXME`/empty stubs.
4. Do not create or modify Gradle/CMake/manifest files; do not run git.
5. Line budget per file <= 1200.
