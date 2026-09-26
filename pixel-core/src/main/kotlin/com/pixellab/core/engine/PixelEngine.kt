package com.pixellab.core.engine

import com.pixellab.core.model.Layer
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.nativelib.NativeLib
import com.pixellab.core.nativelib.NativePixelOps

/**
 * Stateful drawing engine for sprite projects: canvas drawing operations,
 * layer management and per-project undo/redo history.
 *
 * [PixelEngine] is the single owner of undo history, keyed by
 * [SpriteProject.id]. Mutations are functional: every mutating method
 * validates its arguments first, computes the next [SpriteProject], and only
 * records history after success — exception paths never pollute the stacks.
 * Operations that leave the project content unchanged return the input
 * project itself without pushing a history entry.
 *
 * Canvas-level operations edit the *active cel* — the active layer's cel of
 * the active frame; a missing cel is materialized from a blank frame on
 * demand, and a result that is entirely blank never materializes one. Pixel
 * writes are rejected with [IllegalArgumentException] when the receiving
 * layer is locked. Layer management operations are structural (not pixel
 * writes) and stay usable on locked layers — otherwise a locked layer could
 * never be unlocked.
 *
 * Callers are expected to feed the project returned by the previous
 * operation back into the next one (the standard editor loop). Not
 * thread-safe; serialize access per project.
 */
class PixelEngine(private val config: com.pixellab.core.PixelLabConfig) {

    /** One recorded change: the states before and after, plus provenance. */
    private class ChangeRecord(
        val before: SpriteProject,
        val after: SpriteProject,
        val label: String,
        val timestamp: Long,
    )

    /** Per-project undo/redo stacks, both ordered oldest-first. */
    private class ProjectHistory {
        val undo = ArrayDeque<ChangeRecord>()
        val redo = ArrayDeque<ChangeRecord>()
    }

    private companion object {
        private const val TAG = "PixelEngine"
    }

    private val histories = HashMap<String, ProjectHistory>()

    // ---- canvas-level operations (active cel of the active frame) ----------

    /**
     * Sets the pixel at (`x`, `y`) to [argb]. Out-of-bounds coordinates are
     * skipped silently (canvas draw semantics).
     *
     * @throws IllegalArgumentException when the active layer is locked.
     */
    fun drawPixel(project: SpriteProject, x: Int, y: Int, argb: Int): SpriteProject =
        editActiveCel(project, "drawPixel") { it.withPixel(x, y, argb) }

    /**
     * Sets every point in [points] to [argb]. Out-of-bounds points are
     * skipped; an empty list is a no-op.
     *
     * @throws IllegalArgumentException when the active layer is locked.
     */
    fun drawPixels(project: SpriteProject, points: List<PixelPoint>, argb: Int): SpriteProject =
        editActiveCel(project, "drawPixels") { it.withPixels(points, argb) }

    /**
     * Erases every point in [points] (sets it to fully transparent
     * `0x00000000`). Out-of-bounds points are skipped; an empty list is a
     * no-op.
     *
     * @throws IllegalArgumentException when the active layer is locked.
     */
    fun erasePixels(project: SpriteProject, points: List<PixelPoint>): SpriteProject =
        editActiveCel(project, "erasePixels") { it.withPixels(points, 0) }

    /**
     * Draws a Bresenham line from (`x0`, `y0`) to (`x1`, `y1`) with the given
     * [thickness] (parallel-offset merge, see [DrawOps.thickLine]).
     * Out-of-bounds points are clipped.
     *
     * @throws IllegalArgumentException when [thickness] is below 1 or the
     *   active layer is locked.
     */
    fun drawLine(
        project: SpriteProject,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        argb: Int,
        thickness: Int = 1,
    ): SpriteProject {
        require(thickness >= 1) { "thickness must be >= 1 (was $thickness)" }
        val points = DrawOps.thickLine(x0, y0, x1, y1, thickness)
        return editActiveCel(project, "drawLine") { it.withPixels(points, argb) }
    }

    /**
     * Draws a rectangle with top-left corner (`x`, `y`) and size `w x h`,
     * filled or as a clockwise outline.
     *
     * @throws IllegalArgumentException when [w] or [h] is below 1 or the active
     *   layer is locked.
     */
    fun drawRect(
        project: SpriteProject,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        argb: Int,
        filled: Boolean,
    ): SpriteProject {
        require(w >= 1) { "Rect width must be >= 1 (was $w)" }
        require(h >= 1) { "Rect height must be >= 1 (was $h)" }
        val points = DrawOps.rect(x, y, w, h, filled)
        return editActiveCel(project, "drawRect") { it.withPixels(points, argb) }
    }

    /**
     * Draws a midpoint circle around (`cx`, `cy`) with radius [r], filled
     * (row-span derivation, no holes) or as an 8-way symmetric outline.
     *
     * @throws IllegalArgumentException when [r] is negative or the active
     *   layer is locked.
     */
    fun drawCircle(
        project: SpriteProject,
        cx: Int,
        cy: Int,
        r: Int,
        argb: Int,
        filled: Boolean,
    ): SpriteProject {
        require(r >= 0) { "Circle radius must be >= 0 (was $r)" }
        val points = DrawOps.circle(cx, cy, r, filled)
        return editActiveCel(project, "drawCircle") { it.withPixels(points, argb) }
    }

    /**
     * Flood-fills the 4-connected region containing (`x`, `y`) on the active
     * cel with [argb], comparing against the seed color with per-channel
     * [tolerance] (alpha included). Dispatches to
     * [NativePixelOps.floodFill] when the native library is loaded, falling
     * back to the Kotlin [FloodFill] on link or argument failure. An
     * out-of-bounds seed is a no-op.
     *
     * @throws IllegalArgumentException when [tolerance] is negative or the
     *   active layer is locked.
     */
    fun fill(project: SpriteProject, x: Int, y: Int, argb: Int, tolerance: Int = 0): SpriteProject {
        require(tolerance >= 0) { "tolerance must be >= 0 (was $tolerance)" }
        requireWritableActiveLayer(project)
        if (x < 0 || y < 0 || x >= project.width || y >= project.height) return project
        val base = project.activeCel() ?: PixelFrame.blank(project.width, project.height)
        val pixels = floodFillDispatch(base.pixels, project.width, project.height, x, y, argb, tolerance)
        val next = if (pixels === base.pixels) base else PixelFrame.of(project.width, project.height, pixels)
        if (next == base) return project
        return commit(project, project.withActiveCel(next), "fill")
    }

    /**
     * Samples the color at (`x`, `y`) from the *composited* active frame (the
     * color the canvas displays at that pixel, all visible layers merged).
     *
     * @throws IllegalArgumentException when (`x`, `y`) is outside the canvas.
     */
    fun pickColor(project: SpriteProject, x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= project.width || y >= project.height) {
            throw IllegalArgumentException(
                "pickColor($x, $y) is outside the ${project.width}x${project.height} canvas",
            )
        }
        return project.compositeActiveFrame().pixels[y * project.width + x]
    }

    /**
     * Replaces every pixel of the active cel that is within [tolerance] of
     * [from] (per-channel, alpha included) with [to].
     *
     * @throws IllegalArgumentException when [tolerance] is negative or the
     *   active layer is locked.
     */
    fun replaceColor(project: SpriteProject, from: Int, to: Int, tolerance: Int = 0): SpriteProject {
        require(tolerance >= 0) { "tolerance must be >= 0 (was $tolerance)" }
        return editActiveCel(project, "replaceColor") { replaceInCel(it, from, to, tolerance) }
    }

    /**
     * Clears the active cel (removes it from the active frame, which renders
     * as fully transparent). Other cels are untouched.
     *
     * @throws IllegalArgumentException when the active layer is locked.
     */
    fun clearCanvas(project: SpriteProject): SpriteProject {
        requireWritableActiveLayer(project)
        val cel = project.activeCel() ?: return project
        if (cel.pixels.none { it != 0 }) return project
        return commit(project, project.withCel(project.activeLayerId, project.activeFrameIndex, null), "clearCanvas")
    }

    /**
     * Overwrites the active cel with [frame] pixel-for-pixel.
     *
     * @throws IllegalArgumentException when [frame] does not match the canvas
     *   size or the active layer is locked.
     */
    fun applyFrame(project: SpriteProject, frame: PixelFrame): SpriteProject {
        require(frame.width == project.width && frame.height == project.height) {
            "Frame ${frame.width}x${frame.height} does not match canvas ${project.width}x${project.height}"
        }
        return editActiveCel(project, "applyFrame") { frame }
    }

    /**
     * Draws a multi-point stroke: consecutive points are connected with
     * Bresenham segments of the given [thickness]; a single-point stroke
     * stamps a `thickness x thickness` dot. An empty point list is a no-op.
     *
     * @throws IllegalArgumentException when [thickness] is below 1 or the
     *   active layer is locked.
     */
    fun drawStroke(
        project: SpriteProject,
        points: List<PixelPoint>,
        argb: Int,
        thickness: Int = 1,
    ): SpriteProject {
        require(thickness >= 1) { "thickness must be >= 1 (was $thickness)" }
        if (points.isEmpty()) return project
        val stroke = strokePoints(points, thickness)
        return editActiveCel(project, "drawStroke") { it.withPixels(stroke, argb) }
    }

    /**
     * Shifts the active cel by (`dx`, `dy`); content shifted out is lost and
     * the vacated strip becomes transparent.
     *
     * @throws IllegalArgumentException when the active layer is locked.
     */
    fun shiftCanvas(project: SpriteProject, dx: Int, dy: Int): SpriteProject =
        editActiveCel(project, "shiftCanvas") { it.shifted(dx, dy) }

    /**
     * Mirrors the active cel left-right.
     *
     * @throws IllegalArgumentException when the active layer is locked.
     */
    fun flipCanvasHorizontal(project: SpriteProject): SpriteProject =
        editActiveCel(project, "flipCanvasHorizontal") { it.mirroredHorizontally() }

    /**
     * Mirrors the active cel top-bottom.
     *
     * @throws IllegalArgumentException when the active layer is locked.
     */
    fun flipCanvasVertical(project: SpriteProject): SpriteProject =
        editActiveCel(project, "flipCanvasVertical") { it.mirroredVertically() }

    /**
     * Rotates the active cel 90 degrees clockwise.
     *
     * @throws IllegalArgumentException when the canvas is not square or the
     *   active layer is locked.
     */
    fun rotateCanvas90(project: SpriteProject): SpriteProject {
        require(project.width == project.height) {
            "rotateCanvas90 requires a square canvas (was ${project.width}x${project.height})"
        }
        return editActiveCel(project, "rotateCanvas90") { it.rotated90Cw() }
    }

    /**
     * Draws a 4-neighbor outline in [argb] around the non-transparent
     * (alpha != 0) pixels of the active cel. Outline pixels are strictly
     * outside the outlined content, so no content pixel is overwritten.
     *
     * @throws IllegalArgumentException when the active layer is locked.
     */
    fun outlineCanvas(project: SpriteProject, argb: Int): SpriteProject =
        editActiveCel(project, "outlineCanvas") { cel ->
            val content = HashSet<PixelPoint>()
            cel.forEach { x, y, value -> if ((value ushr 24) != 0) content.add(PixelPoint(x, y)) }
            val ring = DrawOps.outline(content)
            if (ring.isEmpty()) cel else cel.withPixels(ring, argb)
        }

    // ---- layer management ---------------------------------------------------

    /**
     * Appends a new layer with [name] on top of the stack (it renders above
     * everything) and makes it the active layer. The layer starts with no
     * cels.
     *
     * @throws IllegalArgumentException when [name] is blank.
     */
    fun addLayer(project: SpriteProject, name: String): SpriteProject {
        require(name.isNotBlank()) { "Layer name must not be blank" }
        val layer = Layer(id = project.allocateLayerId(), name = name)
        val next = project.withLayers(project.layers + layer).withActiveLayer(layer.id)
        return commit(project, next, "addLayer")
    }

    /**
     * Removes the layer with [layerId] together with all of its cels across
     * every frame. At least one layer must remain. When the active layer is
     * removed, the layer directly below becomes active (or the lowest
     * remaining layer when the bottom one was removed).
     *
     * @throws IllegalArgumentException when no layer has [layerId] or it is
     *   the last remaining layer.
     */
    fun removeLayer(project: SpriteProject, layerId: Int): SpriteProject {
        val index = project.layerIndex(layerId)
        require(index >= 0) { "No layer with id $layerId" }
        require(project.layers.size > 1) { "Cannot remove the last remaining layer" }
        val frames = if (project.frames.any { it.cels.containsKey(layerId) }) {
            project.frames.map { it.withCel(layerId, null) }
        } else {
            project.frames
        }
        val layers = project.layers.filterNot { it.id == layerId }
        val activeLayerId = if (layerId == project.activeLayerId) {
            if (index > 0) project.layers[index - 1].id else layers[0].id
        } else {
            project.activeLayerId
        }
        // Retarget activity before the layer list shrinks: [SpriteProject]
        // validates activeLayerId against the layer stack on every copy.
        val retargeted = project.copy(activeLayerId = activeLayerId)
        val next = retargeted.withLayers(layers).copy(frames = frames)
        return commit(project, next, "removeLayer")
    }

    /**
     * Renames the layer with [layerId].
     *
     * @throws IllegalArgumentException when no layer has [layerId] or [name]
     *   is blank.
     */
    fun renameLayer(project: SpriteProject, layerId: Int, name: String): SpriteProject {
        require(name.isNotBlank()) { "Layer name must not be blank" }
        return updateLayer(project, layerId, "renameLayer") { it.withName(name) }
    }

    /**
     * Moves the layer with [layerId] to stack index [toIndex] (0 renders
     * first / bottommost).
     *
     * @throws IllegalArgumentException when no layer has [layerId] or
     *   [toIndex] is outside `0..layerCount-1`.
     */
    fun moveLayer(project: SpriteProject, layerId: Int, toIndex: Int): SpriteProject {
        val from = project.layerIndex(layerId)
        require(from >= 0) { "No layer with id $layerId" }
        require(toIndex in project.layers.indices) {
            "Layer index $toIndex out of bounds (0..${project.layers.size - 1})"
        }
        if (toIndex == from) return project
        val layers = project.layers.toMutableList()
        val layer = layers.removeAt(from)
        layers.add(toIndex, layer)
        return commit(project, project.withLayers(layers), "moveLayer")
    }

    /**
     * Sets the opacity of the layer with [layerId].
     *
     * @throws IllegalArgumentException when no layer has [layerId] or
     *   [opacity] is outside `[0, 1]`.
     */
    fun setLayerOpacity(project: SpriteProject, layerId: Int, opacity: Float): SpriteProject {
        require(opacity in 0f..1f) { "Layer opacity must be in [0, 1] (was $opacity)" }
        return updateLayer(project, layerId, "setLayerOpacity") { it.withOpacity(opacity) }
    }

    /**
     * Shows or hides the layer with [layerId] (hidden layers keep their
     * data).
     *
     * @throws IllegalArgumentException when no layer has [layerId].
     */
    fun setLayerVisible(project: SpriteProject, layerId: Int, visible: Boolean): SpriteProject =
        updateLayer(project, layerId, "setLayerVisible") { it.withVisible(visible) }

    /**
     * Locks or unlocks the layer with [layerId]. Locking is structural — a
     * locked layer can always be unlocked through this method.
     *
     * @throws IllegalArgumentException when no layer has [layerId].
     */
    fun setLayerLocked(project: SpriteProject, layerId: Int, locked: Boolean): SpriteProject =
        updateLayer(project, layerId, "setLayerLocked") { it.withLocked(locked) }

    /**
     * Clears the layer with [layerId]: removes its cels from every frame
     * (layer-scoped, unlike [clearCanvas] which touches the active cel only).
     *
     * @throws IllegalArgumentException when no layer has [layerId] or that
     *   layer is locked.
     */
    fun clearLayer(project: SpriteProject, layerId: Int): SpriteProject {
        val index = project.layerIndex(layerId)
        require(index >= 0) { "No layer with id $layerId" }
        val layer = project.layers[index]
        require(!layer.locked) { "Layer '${layer.name}' (id=$layerId) is locked" }
        if (project.frames.none { it.cels.containsKey(layerId) }) return project
        val frames = project.frames.map { it.withCel(layerId, null) }
        return commit(project, project.copy(frames = frames), "clearLayer")
    }

    // ---- history ------------------------------------------------------------

    /**
     * Provenance of one undoable change: the mutating operation's label and
     * the wall-clock time it was committed.
     */
    data class HistoryEntry(val label: String, val timestamp: Long)

    /** True when [undo] would return a project for [projectId]. */
    fun canUndo(projectId: String): Boolean = histories[projectId]?.undo?.isNotEmpty() == true

    /** True when [redo] would return a project for [projectId]. */
    fun canRedo(projectId: String): Boolean = histories[projectId]?.redo?.isNotEmpty() == true

    /**
     * Undoes the latest change of [projectId]: the pre-change project is
     * returned and the change record transfers to the redo stack (its
     * post-state is the version being replaced).
     *
     * @return the restored (older) project, or null when there is nothing to
     *   undo.
     */
    fun undo(projectId: String): SpriteProject? {
        val history = histories[projectId] ?: return null
        val record = history.undo.removeLastOrNull() ?: return null
        history.redo.addLast(record)
        return record.before
    }

    /**
     * Redoes the latest undone change of [projectId]: the post-change project
     * is returned and the change record returns to the undo stack.
     *
     * @return the re-applied (newer) project, or null when there is nothing
     *   to redo.
     */
    fun redo(projectId: String): SpriteProject? {
        val history = histories[projectId] ?: return null
        val record = history.redo.removeLastOrNull() ?: return null
        history.undo.addLast(record)
        return record.after
    }

    /**
     * Labels and timestamps of the undoable changes of [projectId], ordered
     * oldest to newest. The redo stack is not included.
     */
    fun historyInfo(projectId: String): List<HistoryEntry> {
        val history = histories[projectId] ?: return emptyList()
        return history.undo.map { HistoryEntry(it.label, it.timestamp) }
    }

    /**
     * Non-mutating read of the state *before* the most recent change of
     * [projectId] — the snapshot [undo] would restore — without touching
     * either stack. Powers read-only inspections such as "what did my last
     * operation actually change?" diffs.
     *
     * @return the pre-change project, or null when there is nothing to undo.
     */
    fun peekBefore(projectId: String): SpriteProject? =
        histories[projectId]?.undo?.lastOrNull()?.before

    /** Drops all undo and redo records of [projectId]. */
    fun clearHistory(projectId: String) {
        histories.remove(projectId)
    }

    // ---- internals ----------------------------------------------------------

    /**
     * Records a successful change: pushes a [ChangeRecord] onto the undo
     * stack (evicting the oldest beyond [PixelLabConfig.maxUndoDepth]) and
     * clears the redo stack. Returns [after] as the new working project, or
     * [before] itself when nothing structurally changed.
     */
    private fun commit(before: SpriteProject, after: SpriteProject, label: String): SpriteProject {
        if (after == before) return before
        val history = histories.getOrPut(before.id) { ProjectHistory() }
        history.undo.addLast(ChangeRecord(before, after, label, System.currentTimeMillis()))
        while (history.undo.size > config.maxUndoDepth) history.undo.removeFirst()
        history.redo.clear()
        return after
    }

    /** Rejects pixel writes while the active layer is locked. */
    private fun requireWritableActiveLayer(project: SpriteProject) {
        val layer = project.activeLayer
        require(!layer.locked) { "Active layer '${layer.name}' (id=${layer.id}) is locked" }
    }

    /**
     * Applies [transform] to the active cel (a blank frame stands in when the
     * cel is missing) and commits the result. A transform that yields the
     * input content is a no-op: the project is returned unchanged with no
     * history entry, and an all-blank result never materializes a cel.
     */
    private fun editActiveCel(
        project: SpriteProject,
        label: String,
        transform: (PixelFrame) -> PixelFrame,
    ): SpriteProject {
        requireWritableActiveLayer(project)
        val base = project.activeCel() ?: PixelFrame.blank(project.width, project.height)
        val next = transform(base)
        if (next == base) return project
        return commit(project, project.withActiveCel(next), label)
    }

    /** Replaces every cel pixel within [tolerance] of [from] with [to]. */
    private fun replaceInCel(cel: PixelFrame, from: Int, to: Int, tolerance: Int): PixelFrame {
        val out = IntArray(cel.pixels.size)
        var changed = false
        for (i in out.indices) {
            val value = cel.pixels[i]
            if (tolerance == 0 && value == from || tolerance > 0 && FloodFill.withinTolerance(value, from, tolerance)) {
                out[i] = to
                if (value != to) changed = true
            } else {
                out[i] = value
            }
        }
        return if (changed) PixelFrame.of(cel.width, cel.height, out) else cel
    }

    /**
     * Connects the stroke points with thick Bresenham segments, merging and
     * de-duplicating while preserving first-occurrence order.
     */
    private fun strokePoints(points: List<PixelPoint>, thickness: Int): List<PixelPoint> {
        if (points.size == 1) {
            val only = points[0]
            return DrawOps.thickLine(only.x, only.y, only.x, only.y, thickness)
        }
        val unique = LinkedHashSet<PixelPoint>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            unique.addAll(DrawOps.thickLine(a.x, a.y, b.x, b.y, thickness))
        }
        return unique.toList()
    }

    /**
     * Flood-fill backend choice: native [NativePixelOps.floodFill] when
     * [NativeLib.load] links the shared library (the contract's dispatch
     * condition), else the Kotlin [FloodFill]. Native link or argument
     * failures degrade to the Kotlin path with identical semantics.
     */
    private fun floodFillDispatch(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        replacement: Int,
        tolerance: Int,
    ): IntArray {
        if (NativeLib.load() && NativePixelOps.isAvailable) {
            try {
                return NativePixelOps.floodFill(pixels, width, height, x, y, replacement, tolerance)
            } catch (error: UnsatisfiedLinkError) {
                config.effectiveLogger.w(TAG, "Native flood fill unavailable, using Kotlin fallback: $error")
            } catch (error: IllegalArgumentException) {
                config.effectiveLogger.w(TAG, "Native flood fill rejected input, using Kotlin fallback: $error")
            }
        }
        return FloodFill.flood(width, height, pixels, x, y, replacement, tolerance)
    }

    /** Applies [update] to the layer with [layerId] and commits. */
    private fun updateLayer(
        project: SpriteProject,
        layerId: Int,
        label: String,
        update: (Layer) -> Layer,
    ): SpriteProject {
        val index = project.layerIndex(layerId)
        require(index >= 0) { "No layer with id $layerId" }
        val layers = project.layers.mapIndexed { i, layer -> if (i == index) update(layer) else layer }
        return commit(project, project.withLayers(layers), label)
    }
}
