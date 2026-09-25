package com.pixellab.core.animation

import com.pixellab.core.PixelLabConfig
import com.pixellab.core.model.AnimationTag
import com.pixellab.core.model.Frame
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject

/**
 * Stateless animation engine for [SpriteProject]: frame list management
 * (add / clone / delete / reorder), playback timing (project fps plus per-frame
 * duration overrides), animation tags, onion-skin and playback previews, and
 * the procedural [breathe] effect.
 *
 * The engine owns no history and holds no mutable state: every operation
 * validates its arguments first and then returns a **new** [SpriteProject]
 * built through the model's immutable `with*` copy family; the input project
 * is never modified. Undo/redo history is owned by `PixelEngine`, not by this
 * class — hosts that want undo simply push the returned project onto their own
 * stack. Because all state lives in the projects themselves, one engine
 * instance can be shared freely, including across threads.
 *
 * All operations are deterministic: the same input project always yields the
 * same output project.
 *
 * @param config library configuration; used solely as the diagnostics sink
 * ([PixelLabConfig.effectiveLogger]) for the debug emission of [breathe].
 */
class AnimationEngine(private val config: PixelLabConfig) {

    // ---- frame list ----------------------------------------------------------

    /**
     * Adds a new frame right after [afterIndex] (default: after the last
     * frame) and makes the new frame the active one.
     *
     * Cel sharing semantics: the new frame starts from the pixel content of
     * the frame currently at [afterIndex]. Every **non-null** cel of that
     * source frame is shared by reference with the new frame. This is safe
     * because [PixelFrame] is deeply immutable — its pixel array is never
     * mutated after construction and every editing operation is
     * copy-on-write — so drawing on the new frame's cel later on produces a
     * fresh `PixelFrame` (via `SpriteProject.withActiveCel`) and leaves the
     * source frame's cel untouched, and vice versa. Sharing keeps frame
     * addition O(1) in memory, which matches the typical editor flow of
     * starting a frame from the previous frame's pixels. Layers that have no
     * cel in the source frame (rendered as transparent by the model) keep
     * having no cel in the new frame.
     *
     * The new frame receives a freshly allocated frame id and its `durationMs`
     * override is reset to null, so it plays at the project-wide default
     * (`SpriteProject.effectiveFrameDuration`).
     *
     * @param project project to extend; never mutated.
     * @param afterIndex index of the frame the new frame is based on; the new
     * frame is inserted at `afterIndex + 1`.
     * @return a new project whose active frame is the newly added frame.
     * @throws IllegalArgumentException if [afterIndex] is out of bounds.
     */
    fun addFrame(project: SpriteProject, afterIndex: Int = project.frameCount - 1): SpriteProject {
        require(afterIndex in project.frames.indices) {
            "afterIndex $afterIndex out of bounds (${project.frameCount} frames)"
        }
        val source = project.frames[afterIndex]
        val added = Frame(
            id = project.allocateFrameId(),
            cels = source.cels,
            durationMs = null,
        )
        val frames = project.frames.toMutableList()
        frames.add(afterIndex + 1, added)
        return project.withFrames(frames).withActiveFrameIndex(afterIndex + 1)
    }

    /**
     * Duplicates the frame at [frameIndex]: the clone is inserted immediately
     * after its source and becomes the active frame.
     *
     * The clone is value-complete: it carries the source frame's full cel map
     * and its `durationMs` override. Cel [PixelFrame] instances are shared by
     * reference (see [addFrame] for why deep immutability makes this safe —
     * the clone equals the source pixel-for-pixel without copying arrays),
     * while the frame itself gets a freshly allocated id so frame ids stay
     * unique across the session.
     *
     * @param project project to extend; never mutated.
     * @param frameIndex index of the frame to clone.
     * @return a new project with `frameCount + 1` frames; the clone at index
     * `frameIndex + 1` is active.
     * @throws IllegalArgumentException if [frameIndex] is out of bounds.
     */
    fun cloneFrame(project: SpriteProject, frameIndex: Int): SpriteProject {
        require(frameIndex in project.frames.indices) {
            "frameIndex $frameIndex out of bounds (${project.frameCount} frames)"
        }
        val source = project.frames[frameIndex]
        val clone = Frame(
            id = project.allocateFrameId(),
            cels = source.cels,
            durationMs = source.durationMs,
        )
        val frames = project.frames.toMutableList()
        frames.add(frameIndex + 1, clone)
        return project.withFrames(frames).withActiveFrameIndex(frameIndex + 1)
    }

    /**
     * Removes the frame at [frameIndex]. A project must always keep at least
     * one frame, so deleting the last remaining frame is rejected.
     *
     * Active-frame selection: when the active frame is not the one being
     * deleted it stays active (the engine tracks it by frame id and reselects
     * it after the removal). When the active frame itself is deleted, the
     * frame that slides into its index becomes active — or, for a deletion at
     * the end of the timeline, the new last frame.
     *
     * @param project project to shrink; never mutated.
     * @param frameIndex index of the frame to remove.
     * @return a new project with `frameCount - 1` frames.
     * @throws IllegalArgumentException if [frameIndex] is out of bounds or if
     * [project] has only one frame.
     */
    fun deleteFrame(project: SpriteProject, frameIndex: Int): SpriteProject {
        require(frameIndex in project.frames.indices) {
            "frameIndex $frameIndex out of bounds (${project.frameCount} frames)"
        }
        require(project.frameCount > 1) {
            "Cannot delete the only remaining frame (a project must keep at least one)"
        }
        val activeId = project.frames[project.activeFrameIndex].id
        val remaining = project.frames.toMutableList()
        remaining.removeAt(frameIndex)
        val next = project.withFrames(remaining)
        val activeNow = next.frames.indexOfFirst { it.id == activeId }
        return if (activeNow >= 0) next.withActiveFrameIndex(activeNow) else next
    }

    /**
     * Moves the frame at [fromIndex] so that it ends up at index [toIndex]:
     * it is removed from [fromIndex] and re-inserted at [toIndex] of the
     * shortened list (standard "drag frame to slot" semantics).
     *
     * The active frame follows the reorder by frame id: whatever frame was
     * active before the move (moved or not) is still active afterwards. If
     * [fromIndex] equals [toIndex] the call is a no-op and the original
     * project instance is returned unchanged.
     *
     * @param project project whose timeline is reordered; never mutated.
     * @param fromIndex current index of the frame to move.
     * @param toIndex target index of the frame after the move.
     * @return a new project with the reordered frame list.
     * @throws IllegalArgumentException if either index is out of bounds.
     */
    fun moveFrame(project: SpriteProject, fromIndex: Int, toIndex: Int): SpriteProject {
        require(fromIndex in project.frames.indices) {
            "fromIndex $fromIndex out of bounds (${project.frameCount} frames)"
        }
        require(toIndex in project.frames.indices) {
            "toIndex $toIndex out of bounds (${project.frameCount} frames)"
        }
        if (fromIndex == toIndex) return project
        val activeId = project.frames[project.activeFrameIndex].id
        val frames = project.frames.toMutableList()
        val moving = frames.removeAt(fromIndex)
        frames.add(toIndex, moving)
        val next = project.withFrames(frames)
        val activeNow = next.frames.indexOfFirst { it.id == activeId }
        return if (activeNow >= 0) next.withActiveFrameIndex(activeNow) else next
    }

    // ---- playback timing -----------------------------------------------------

    /**
     * Sets the project-wide playback frame rate used by every frame that has
     * no `durationMs` override.
     *
     * The accepted input domain is `1..60000` (the animation contract's
     * domain); the value actually stored is clamped into the frozen project
     * invariant `1..24` by [SpriteProject.withFps], which the model defines as
     * clamping rather than throwing. Passing a value already equal to the
     * project's fps returns the original instance.
     *
     * @param project project to retime; never mutated.
     * @param fps desired frame rate; stored clamped to `1..24`.
     * @return a new project with the updated fps.
     * @throws IllegalArgumentException if [fps] is outside `1..60000`.
     */
    fun setFps(project: SpriteProject, fps: Int): SpriteProject {
        require(fps in 1..60_000) { "fps must be in [1, 60000] (was $fps)" }
        if (project.fps == fps) return project
        return project.withFps(fps)
    }

    /**
     * Sets or clears the per-frame duration override of the frame at
     * [frameIndex].
     *
     * @param project project to retime; never mutated.
     * @param frameIndex index of the frame whose override changes.
     * @param durationMs explicit duration in milliseconds in `1..60000`, or
     * null to clear the override so the frame falls back to `1000 / fps`.
     * @return a new project with the updated frame. Setting a value the frame
     * already has returns the original instance.
     * @throws IllegalArgumentException if [frameIndex] is out of bounds or
     * [durationMs] is non-null and outside `1..60000`.
     */
    fun setFrameDuration(project: SpriteProject, frameIndex: Int, durationMs: Int?): SpriteProject {
        require(frameIndex in project.frames.indices) {
            "frameIndex $frameIndex out of bounds (${project.frameCount} frames)"
        }
        durationMs?.let {
            require(it in 1..60_000) { "Frame duration must be in [1, 60000] ms (was $it)" }
        }
        if (project.frames[frameIndex].durationMs == durationMs) return project
        val frames = project.frames.toMutableList()
        frames[frameIndex] = frames[frameIndex].withDuration(durationMs)
        return project.withFrames(frames)
    }

    // ---- tags ----------------------------------------------------------------

    /**
     * Creates or replaces the tag [name] spanning frames [startFrame] to
     * [endFrame], both inclusive.
     *
     * Name blankness, `startFrame >= 0` and `startFrame <= endFrame` are
     * validated by the [AnimationTag] constructor, to which this method
     * delegates. Additionally [endFrame] must address an existing frame of
     * [project]. A tag with the same name is replaced (there is at most one
     * tag per name); other tags keep their order.
     *
     * @param project project to tag; never mutated.
     * @param name tag name; must not be blank.
     * @param startFrame first frame index of the span (>= 0).
     * @param endFrame last frame index of the span (>= [startFrame], <
     * `frameCount`).
     * @return a new project with the tag installed.
     * @throws IllegalArgumentException on any invalid span or blank name.
     */
    fun setTag(project: SpriteProject, name: String, startFrame: Int, endFrame: Int): SpriteProject {
        val tag = AnimationTag(name = name, startFrame = startFrame, endFrame = endFrame)
        require(endFrame < project.frameCount) {
            "Tag endFrame $endFrame out of bounds (${project.frameCount} frames)"
        }
        val others = project.tags.filterNot { it.name == tag.name }
        return project.withTags(others + tag)
    }

    /**
     * Removes the tag named [name].
     *
     * Idempotent: when no tag with that name exists the original project
     * instance is returned unchanged, so repeated calls are always safe.
     *
     * @param project project to untag; never mutated.
     * @param name tag name to remove.
     * @return a new project without the tag, or the original instance when the
     * tag was absent.
     */
    fun removeTag(project: SpriteProject, name: String): SpriteProject {
        if (project.tags.none { it.name == name }) return project
        return project.withTags(project.tags.filterNot { it.name == name })
    }

    // ---- previews ------------------------------------------------------------

    /**
     * Onion-skin preview pair around the project's active frame.
     *
     * [previous] is the fully composited frame at distance [range] before the
     * active frame with its overall alpha scaled to 0x66 (40%); [next] is the
     * composited frame at distance [range] after it, scaled to 0x33 (20%).
     * When fewer than [range] frames exist on a side, the farthest frame that
     * does exist on that side is used; when a side has no frames at all (the
     * active frame is at the boundary) that side is null.
     *
     * Alpha scaling is exact integer math per pixel: for a source alpha `a`
     * and target alpha `t`, the result is `((a * t) / 255) shl 24 or rgb`
     * with fully-transparent pixels (`0x00000000`) kept as `0`. The returned
     * frames are fresh composited rasters — they are neither the project's
     * cels nor aliased to any other frame.
     *
     * @param project project to inspect; never mutated.
     * @param range distance in frames from the active frame; >= 1.
     * @return the scaled neighbor pair; both sides may be null at the
     * timeline boundaries.
     * @throws IllegalArgumentException if [range] < 1.
     */
    fun onionSkin(project: SpriteProject, range: Int = 1): OnionSkinData {
        require(range >= 1) { "range must be >= 1 (was $range)" }
        val active = project.activeFrameIndex
        val last = project.frameCount - 1
        val previous = if (active > 0) {
            val index = maxOf(0, active - range)
            scaleFrameAlpha(project.compositeFrame(index), PREVIOUS_ALPHA)
        } else {
            null
        }
        val next = if (active < last) {
            val index = minOf(last, active + range)
            scaleFrameAlpha(project.compositeFrame(index), NEXT_ALPHA)
        } else {
            null
        }
        return OnionSkinData(previous, next)
    }

    /**
     * Onion-skin preview data: the scaled composited frames surrounding the
     * active frame, ready to be drawn underneath it.
     *
     * @property previous frame before the active one at alpha 0x66, or null
     * when the active frame is the first frame.
     * @property next frame after the active one at alpha 0x33, or null when
     * the active frame is the last frame.
     */
    data class OnionSkinData(
        val previous: PixelFrame?,
        val next: PixelFrame?,
    )

    /**
     * Composites every frame of the timeline in playback order: element `i`
     * of the result is `project.compositeFrame(i)` — layers blended bottom-up
     * with their opacity, invisible layers skipped.
     *
     * @param project project to render; never mutated.
     * @return fresh composited frames, one per project frame.
     */
    fun previewFrames(project: SpriteProject): List<PixelFrame> =
        List(project.frameCount) { project.compositeFrame(it) }

    // ---- effects -------------------------------------------------------------

    /**
     * Builds a breathing (idle float) variant of the project's current
     * animation. For each of the existing `n` frames two copies are appended:
     * first the `n` copies shifted **down** by [amplitudePx] pixels, then the
     * `n` copies shifted **up** by [amplitudePx] pixels — for a total of `3n`
     * frames, originals first (`0..n-1`), down copies next (`n..2n-1`), up
     * copies last (`2n..3n-1`).
     *
     * Each copy preserves the source frame's layer structure and duration:
     * every cel of every layer is shifted individually via
     * [PixelFrame.shifted], which moves whole pixel rows, drops rows shifted
     * past the canvas edge and fills the vacated strip with transparent
     * pixels (an amplitude larger than the canvas height therefore yields
     * fully transparent copies, not an error). Each `durationMs` override is
     * carried over and every copy receives a freshly allocated frame id. The
     * active frame is unchanged.
     *
     * A tag named `"breathe"` is set over the whole resulting 3n-frame
     * breathing cycle (frames `0..3n-1`), replacing any previous tag of that
     * name. Exactly one debug log line describing the operation is emitted
     * through [PixelLabConfig.effectiveLogger].
     *
     * @param project project to extend; never mutated.
     * @param amplitudePx vertical shift amplitude in pixels; >= 1.
     * @return a new project with `3n` frames and the `breathe` tag.
     * @throws IllegalArgumentException if [amplitudePx] < 1.
     */
    fun breathe(project: SpriteProject, amplitudePx: Int = 1): SpriteProject {
        require(amplitudePx >= 1) { "amplitudePx must be >= 1 (was $amplitudePx)" }
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        fun shiftedCopies(dy: Int): List<Frame> = project.frames.map { source ->
            Frame(
                id = nextId++,
                cels = source.cels.mapValues { cel -> cel.value.shifted(0, dy) },
                durationMs = source.durationMs,
            )
        }
        val shiftedDown = shiftedCopies(amplitudePx)
        val shiftedUp = shiftedCopies(-amplitudePx)
        val grown = project.withFrames(project.frames + shiftedDown + shiftedUp)
        val result = setTag(grown, BREATHE_TAG, 0, 3 * n - 1)
        config.effectiveLogger.d(
            LOG_TAG,
            "breathe: ${n} frame(s) -> ${3 * n} frames at amplitude ${amplitudePx}px, " +
                "tag '$BREATHE_TAG' spans frames 0..${3 * n - 1}",
        )
        return result
    }

    // ---- internals -----------------------------------------------------------

    /**
     * Scales the overall alpha of every pixel of [frame] to [alpha]
     * (`0..255`) while leaving the RGB channels untouched:
     * `((a * alpha) / 255) shl 24 or rgb`; fully-transparent pixels stay `0`.
     */
    private fun scaleFrameAlpha(frame: PixelFrame, alpha: Int): PixelFrame = frame.map { p ->
        if (p == 0) {
            0
        } else {
            (((p ushr 24) * alpha) / 255 shl 24) or (p and 0x00FFFFFF)
        }
    }

    private companion object {
        /** Debug log tag for this engine. */
        private const val LOG_TAG = "AnimationEngine"

        /** Onion-skin alpha of the frame before the active one. */
        private const val PREVIOUS_ALPHA = 0x66

        /** Onion-skin alpha of the frame after the active one. */
        private const val NEXT_ALPHA = 0x33

        /** Tag name installed by [breathe]. */
        private const val BREATHE_TAG = "breathe"
    }
}
