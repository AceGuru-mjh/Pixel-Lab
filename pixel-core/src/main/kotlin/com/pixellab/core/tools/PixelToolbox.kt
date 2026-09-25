package com.pixellab.core.tools

import com.pixellab.core.engine.PixelEngine
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.model.compositePixel

/**
 * High-level facade wiring the advanced tools (brushes, symmetry, blend
 * modes, transforms, selections) into [SpriteProject] editing sessions on
 * top of an existing [PixelEngine].
 *
 * Undo ownership: [PixelEngine] is the **only** history owner (see the API
 * contracts). Every toolbox operation that changes project content is routed
 * through exactly one engine call, so it records exactly one undo entry:
 *
 *  * [drawShape] and [drawWithBrush] -> `PixelEngine.drawPixels`
 *    (label `"drawPixels"`), one entry per call — a whole multi-segment
 *    stroke with symmetry is one undo unit;
 *  * [applyBlend] and [selectAndMove] -> `PixelEngine.applyFrame`
 *    (label `"applyFrame"`), one entry per call;
 *  * [transformCel] with unchanged dimensions -> `PixelEngine.applyFrame`;
 *    with changed dimensions -> **no history entry** (the engine has no
 *    canvas-resize op), see [transformCel].
 *
 * Exceptions never pollute history: validation runs before any engine call,
 * mirroring the engine's own contract. Not thread-safe; serialize access per
 * project like the engine itself.
 */
class PixelToolbox(private val engine: PixelEngine) {

    /**
     * Paints an arbitrary point set ([points] — typically rasterized by
     * [GeometryShapes] or any custom geometry) with [argb] onto the active
     * cel of the active frame, delegating to `PixelEngine.drawPixels`.
     *
     * Undo: one `"drawPixels"` entry per call (see class KDoc); out-of-bounds
     * points are clipped by the engine; an empty point list is a no-op that
     * records nothing; a locked active layer throws
     * [IllegalArgumentException] via the engine.
     */
    fun drawShape(project: SpriteProject, points: List<PixelPoint>, argb: Int): SpriteProject =
        engine.drawPixels(project, points, argb)

    /**
     * Paints a brush stroke: [strokePoints] (the drag path) are connected
     * with [BrushEngine.stroke] under [spec], the result is expanded by
     * [SymmetryEngine.reflectPoints] for [symmetry], and the final point set
     * is drawn with [argb] in one `PixelEngine.drawPixels` call.
     *
     * Determinism: a fresh [BrushEngine] with the default fixed seed is used
     * per call, so identical arguments always produce identical pixels —
     * including [BrushShape.NOISE] speckle. Use [BrushEngine] directly for
     * evolving (session-advancing) noise.
     *
     * Undo: one `"drawPixels"` entry per call — the entire stroke, with all
     * its symmetry copies, is one undo unit.
     *
     * @throws IllegalArgumentException when [spec] is invalid (guarded at
     *   [BrushSpec] construction) or the active layer is locked (engine).
     */
    fun drawWithBrush(
        project: SpriteProject,
        strokePoints: List<PixelPoint>,
        spec: BrushSpec,
        argb: Int,
        symmetry: SymmetryMode = SymmetryMode.OFF,
        pixelPerfect: Boolean = false,
    ): SpriteProject {
        val brush = BrushEngine()
        val stroke = brush.stroke(strokePoints, spec, pixelPerfect)
        val canvas = SymmetryEngine(project.width, project.height)
        return engine.drawPixels(project, canvas.reflectPoints(stroke, symmetry), argb)
    }

    /**
     * Blends the [layerId] layer with the composite of everything **below**
     * it (same bottom-up, visibility/opacity-respecting compositing as
     * `SpriteProject.compositeFrame`) using [mode], and writes the result
     * back into that layer's cel of the **active** frame.
     *
     * Details and caveats (declared semantics):
     *
     *  * [opacity] is the blend's top opacity (see
     *    [compositeWithBlend]); the target layer's own render opacity is
     *    **not** folded in — it keeps applying at composite time, so a
     *    non-opaque target layer still modulates the baked result when the
     *    canvas is rendered. Set the layer opacity to 1 for a pure bake.
     *  * A target layer without a cel is treated as blank; blending blank
     *    over the below-composite bakes a plain copy of the below-composite
     *    into the layer (mathematically consistent with alpha 0 tops).
     *  * The write goes through `PixelEngine.applyFrame`, so the target
     *    layer must be **active**: when [layerId] is not the active layer,
     *    the returned project (and the engine's undo record) has it active
     *    as a side effect. Locked target layers are rejected with
     *    [IllegalArgumentException] by the engine.
     *  * Undo: one `"applyFrame"` entry per call.
     *
     * @throws IllegalArgumentException when no layer has [layerId], [opacity]
     *   is outside `[0, 1]` or NaN, or the target layer is locked.
     */
    fun applyBlend(
        project: SpriteProject,
        layerId: Int,
        mode: BlendMode,
        opacity: Float,
    ): SpriteProject {
        require(!opacity.isNaN() && opacity in 0f..1f) {
            "opacity must be in [0, 1] (was $opacity)"
        }
        val layerIndex = project.layerIndex(layerId)
        require(layerIndex >= 0) { "No layer with id $layerId" }
        val prepared = if (layerId == project.activeLayerId) project else project.withActiveLayer(layerId)

        // Composite of every visible layer strictly below the target layer.
        val below = IntArray(project.width * project.height)
        for (index in 0 until layerIndex) {
            val layer = project.layers[index]
            if (!layer.visible || layer.opacity <= 0f) continue
            val cel = project.activeFrame.cels[layer.id] ?: continue
            val effectiveOpacity = (layer.opacity * 255f).toInt().coerceIn(0, 255)
            for (i in below.indices) {
                val source = cel.pixels[i]
                val sourceAlpha = ((source ushr 24) * effectiveOpacity) / 255
                if (sourceAlpha == 0) continue
                below[i] = compositePixel(source, sourceAlpha, below[i])
            }
        }
        val bottomFrame = PixelFrame.of(project.width, project.height, below)
        val top = prepared.activeCel() ?: PixelFrame.blank(project.width, project.height)
        val blended = compositeWithBlend(bottomFrame, top, mode, opacity)
        return engine.applyFrame(prepared, blended)
    }

    /**
     * Applies [transform] to the **active cel** (a blank frame stands in for
     * a missing cel) and returns the updated project.
     *
     * Declared semantics when the transform keeps the canvas size: the new
     * cel is written via `PixelEngine.applyFrame` — one `"applyFrame"` undo
     * entry, undo/redo fully consistent.
     *
     * Declared semantics when the transform **changes** dimensions (e.g. a
     * 90-degree rotation of a non-square cel): the project is rebuilt at the
     * new canvas size (width/height updated). The transformed cel is kept;
     * every other cel whose size no longer matches the new canvas is
     * **cleared** (removed — the layer renders blank until redrawn); cels
     * that already match the new size survive. Because `PixelEngine` has no
     * canvas-resize operation, this path records **no undo entry** — undo
     * cannot revert a size-changing transform, so callers wanting safety
     * must keep their own snapshot.
     *
     * @throws IllegalArgumentException when the active layer is locked
     *   (checked on both paths, mirroring the engine's write policy).
     */
    fun transformCel(project: SpriteProject, transform: (PixelFrame) -> PixelFrame): SpriteProject {
        val base = project.activeCel() ?: PixelFrame.blank(project.width, project.height)
        val next = transform(base)
        if (next.width == project.width && next.height == project.height) {
            return engine.applyFrame(project, next)
        }
        require(!project.activeLayer.locked) {
            "Active layer '${project.activeLayer.name}' (id=${project.activeLayer.id}) is locked"
        }
        val newFrames = project.frames.mapIndexed { frameIndex, frame ->
            if (frameIndex == project.activeFrameIndex) {
                val cels = HashMap<Int, PixelFrame>()
                for ((layerId, cel) in frame.cels) {
                    if (layerId == project.activeLayerId) continue
                    if (cel.width == next.width && cel.height == next.height) cels[layerId] = cel
                }
                cels[project.activeLayerId] = next
                frame.copy(cels = cels)
            } else {
                frame.copy(cels = frame.cels.filterValues { cel ->
                    cel.width == next.width && cel.height == next.height
                })
            }
        }
        return project.copy(frames = newFrames, width = next.width, height = next.height)
    }

    /**
     * Moves the [sel] region of the **active cel** by (`dx`, `dy`) — see
     * [SelectionOps.move] for the pixel semantics (copy out, clear
     * footprint, paste clipped; destination wins over the cleared area).
     *
     * Undo: the moved cel is written via `PixelEngine.applyFrame` — one
     * `"applyFrame"` entry per call; a no-op move (nothing changed) records
     * nothing.
     *
     * @throws IllegalArgumentException when [sel] is empty (pixel operations
     *   need a non-empty region) or the active layer is locked (engine).
     */
    fun selectAndMove(project: SpriteProject, sel: Selection, dx: Int, dy: Int): SpriteProject {
        require(!sel.isEmpty) { "cannot move an empty selection" }
        val ops = SelectionOps(project.width, project.height)
        val base = project.activeCel() ?: PixelFrame.blank(project.width, project.height)
        val moved = ops.move(base, sel, dx, dy)
        return engine.applyFrame(project, moved)
    }
}
