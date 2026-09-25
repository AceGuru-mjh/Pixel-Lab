package com.pixellab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.Frame
import com.pixellab.core.model.SpriteProject
import com.pixellab.ui.theme.PixelTheme
import com.pixellab.ui.theme.argbColor

/** Outer width of one frame card. */
private val FrameCardWidth = 64.dp

/** Side length of a frame thumbnail. */
private val FrameThumbSize = 48.dp

/** Height of the tag color bar at the bottom edge of a frame card. */
private val TagBarHeight = 4.dp

/** Corner radius of a frame card. */
private val FrameCardCorner = 4.dp

/** Lowest/highest playable fps (matches [SpriteProject] contract). */
private const val MinFps = 1
private const val MaxFps = 24

/** Slider steps between fps 1 and 24. */
private const val FpsSteps = 22

/** Longest legal per-frame duration override, ms. */
private const val MaxDurationMs = 60_000

/**
 * How the playhead behaves when it reaches the last frame.
 */
enum class LoopMode {
    /** Stop after the last frame (playing becomes false). */
    NONE,
    /** Wrap around to frame 0 forever. */
    LOOP,
    /** Reverse direction at both ends, forever. */
    PING_PONG,
}

/**
 * Playback/preview state of the timeline. Pure data — the render layer owns
 * it (an [EditorSession] deliberately does *not*), and [TimelineController]
 * is the only thing that advances it.
 *
 * @param playing true while the animation runs.
 * @param frameIndex the frame the playhead shows right now.
 * @param fps playback frames per second (`1..24`); per-frame `durationMs`
 *   overrides still win for a given frame.
 * @param loop wrap behavior; see [LoopMode].
 * @param onionSkin whether onion-skin ghosts are shown.
 * @param onionAlpha ghost opacity `0..255`.
 * @param forward ping-pong direction flag (true = towards higher indices);
 *   carried along so [LoopMode.PING_PONG] survives state copies.
 */
data class PlaybackState(
    val playing: Boolean = false,
    val frameIndex: Int = 0,
    val fps: Int = 12,
    val loop: LoopMode = LoopMode.LOOP,
    val onionSkin: Boolean = false,
    val onionAlpha: Int = 77,
    val forward: Boolean = true,
) {
    init {
        require(fps in MinFps..MaxFps) { "fps must be in [$MinFps, $MaxFps] (was $fps)" }
        require(onionAlpha in 0..255) { "onionAlpha must be in 0..255 (was $onionAlpha)" }
        require(frameIndex >= 0) { "frameIndex must be >= 0 (was $frameIndex)" }
    }
}

/**
 * Pure playback clock. [advance] folds a wall-clock delta into a
 * [PlaybackState] honoring per-frame duration overrides
 * ([SpriteProject.effectiveFrameDuration]), the fps fallback and the loop
 * mode — no Compose, no timers, fully unit-testable.
 *
 * Step semantics: a frame's duration is consumed *before* leaving it; if the
 * remaining delta is smaller than the current frame's duration the playhead
 * parks mid-frame (partial progress is discarded — documented simplification
 * that keeps the state machine integer-exact). [LoopMode.NONE] stops at the
 * last frame with `playing = false`; [LoopMode.LOOP] wraps modulo the frame
 * count; [LoopMode.PING_PONG] flips [PlaybackState.forward] at both ends.
 * A stopped (`playing = false`) or non-positive-delta call returns the state
 * unchanged.
 */
class TimelineController {

    /**
     * Advances a playing [state] by [deltaMs] over [project].
     *
     * @param project supplies frame count and durations; not mutated.
     * @param state the current playback state.
     * @param deltaMs elapsed milliseconds to fold in.
     * @return the next state (the same instance when nothing can advance).
     */
    fun advance(project: SpriteProject, state: PlaybackState, deltaMs: Int): PlaybackState {
        if (!state.playing || deltaMs <= 0 || project.frameCount < 1) return state
        val last = project.frameCount - 1
        var frame = state.frameIndex.coerceIn(0, last)
        var forward = state.forward
        var playing = true
        var remaining = deltaMs
        // Guard: each loop iteration consumes >= 1ms of a duration (min 1ms),
        // so deltaMs bounds the iterations; the extra headroom is paranoia.
        var guard = deltaMs + 8
        while (remaining > 0 && guard-- > 0) {
            val duration = project.effectiveFrameDuration(frame)
            if (remaining < duration) break
            remaining -= duration
            when (state.loop) {
                LoopMode.NONE -> {
                    if (frame == last) {
                        playing = false
                        break
                    }
                    frame += 1
                }
                LoopMode.LOOP -> frame = (frame + 1) % project.frameCount
                LoopMode.PING_PONG -> {
                    if (forward) {
                        if (frame == last) {
                            forward = false
                            if (frame > 0) frame -= 1 else forward = true
                        } else {
                            frame += 1
                        }
                    } else {
                        if (frame == 0) {
                            forward = true
                            if (frame < last) frame += 1 else forward = false
                        } else {
                            frame -= 1
                        }
                    }
                }
            }
        }
        if (frame == state.frameIndex.coerceIn(0, last) &&
            forward == state.forward &&
            playing == state.playing
        ) {
            return state
        }
        return state.copy(playing = playing, frameIndex = frame, forward = forward)
    }
}

/**
 * Inserts a blank frame after [afterIndex]. New frame ids are allocated as
 * `max(existing ids) + 1` (the project's internal counter is inaccessible
 * from this module; [SpriteProject.withFrames] re-syncs it from the list).
 *
 * @param afterIndex insertion point; clamped into the frame list.
 * @return the new project, or the same instance when the index is invalid.
 */
fun addFrame(project: SpriteProject, afterIndex: Int): SpriteProject {
    if (project.frameCount < 1) return project
    val at = afterIndex.coerceIn(0, project.frameCount - 1)
    val frames = project.frames.toMutableList()
    frames.add(at + 1, Frame(id = project.frames.maxOf { it.id } + 1, cels = emptyMap()))
    return project.withFrames(frames)
}

/**
 * Duplicates the frame at [index] (cels map shared — frames are deeply
 * immutable — and the duration override copied) and inserts the copy right
 * behind it.
 */
fun duplicateFrame(project: SpriteProject, index: Int): SpriteProject {
    if (project.frameCount < 1) return project
    val at = index.coerceIn(0, project.frameCount - 1)
    val source = project.frames[at]
    val frames = project.frames.toMutableList()
    frames.add(at + 1, source.copy(id = project.frames.maxOf { it.id } + 1))
    return project.withFrames(frames)
}

/**
 * Removes the frame at [index]. Refuses (returns the same project) when it is
 * the only frame. Tag spans that referenced the removed tail are clamped to
 * the new last index; tags whose span collapses are dropped — deleting a
 * frame shrinks the index space tags must live in.
 */
fun deleteFrame(project: SpriteProject, index: Int): SpriteProject {
    if (project.frameCount < 2) return project
    val at = index.coerceIn(0, project.frameCount - 1)
    val frames = project.frames.toMutableList()
    frames.removeAt(at)
    val newCount = frames.size
    val tags = project.tags.mapNotNull { tag ->
        val end = tag.endFrame.coerceAtMost(newCount - 1)
        val start = tag.startFrame.coerceAtMost(end)
        if (start == end && tag.length > 1) null else tag.copy(startFrame = start, endFrame = end)
    }
    return project.withTags(tags).withFrames(frames)
}

/**
 * Moves the frame at [from] so it lands at index [to] (both clamped).
 * The active frame follows the moved frame.
 *
 * @return the reordered project, or the same instance for no-op moves.
 */
fun moveFrame(project: SpriteProject, from: Int, to: Int): SpriteProject {
    if (project.frameCount < 2) return project
    val src = from.coerceIn(0, project.frameCount - 1)
    val dst = to.coerceIn(0, project.frameCount - 1)
    if (src == dst) return project
    val moved = project.frames[src]
    val frames = project.frames.toMutableList()
    frames.removeAt(src)
    frames.add(dst, moved)
    val newActive = frames.indexOfFirst { it.id == moved.id }.coerceAtLeast(0)
    return project.withFrames(frames).withActiveFrameIndex(newActive)
}

/**
 * Sets (or with `null` clears) the duration override of the frame at [index].
 *
 * @throws IllegalArgumentException when [durationMs] is outside `1..60000`.
 */
fun setFrameDuration(project: SpriteProject, index: Int, durationMs: Int?): SpriteProject {
    require(durationMs == null || durationMs in 1..MaxDurationMs) {
        "durationMs must be in [1, $MaxDurationMs] or null (was $durationMs)"
    }
    val at = index.coerceIn(0, project.frameCount - 1)
    val frames = project.frames.toMutableList()
    frames[at] = frames[at].withDuration(durationMs)
    return project.withFrames(frames)
}

/**
 * Professional animation timeline: playback transport, fps stepper, loop and
 * onion-skin controls, a scrollable frame strip with composited thumbnails,
 * per-frame operations (add / duplicate / delete / move left / move right)
 * and the duration override editor of the active frame.
 *
 * Frame operations are delivered as index-based callbacks; the pure
 * project-level helpers ([addFrame], [duplicateFrame], [deleteFrame],
 * [moveFrame], [setFrameDuration]) implement them and the
 * [PixelEditorScaffold] routes them through [EditorSession.edit] so every
 * timeline edit is one undo step. Drag-reorder is intentionally *not*
 * implemented — the compile-only stub family has no list-drag APIs — and is
 * replaced by the explicit move-left/move-right buttons (documented
 * deviation).
 *
 * Tag rendering: [com.pixellab.core.model.AnimationTag] spans paint a
 * deterministic colored bar along the bottom edge of every covered frame
 * card, with the tag name on the card where the span starts — perfectly
 * aligned with the strip and immune to scroll drift.
 *
 * @param theme active theme.
 * @param project the animated project (frames, fps, tags).
 * @param playback current playback state; the transport reflects and mutates
 *   it via [onPlaybackChange].
 * @param modifier host modifier.
 * @param onFrameSelect invoked with the tapped frame index.
 * @param onAddFrame invoked with the index to insert a blank frame after.
 * @param onDuplicateFrame invoked with the index of the frame to duplicate.
 * @param onDeleteFrame invoked with the index of the frame to delete.
 * @param onMoveFrame invoked with (from, to) for a frame reorder.
 * @param onFpsChange invoked with the new project fps (`1..24`).
 * @param onDurationChange invoked with (frameIndex, durationMs-or-null).
 * @param onPlaybackChange invoked with the updated playback state whenever
 *   the transport is touched (play/pause/stop, loop cycle, onion toggle,
 *   onion alpha, fps preview).
 */
@Composable
fun TimelinePro(
    theme: PixelTheme,
    project: SpriteProject,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
    onFrameSelect: (Int) -> Unit = {},
    onAddFrame: (Int) -> Unit = {},
    onDuplicateFrame: (Int) -> Unit = {},
    onDeleteFrame: (Int) -> Unit = {},
    onMoveFrame: (Int, Int) -> Unit = { _, _ -> },
    onFpsChange: (Int) -> Unit = {},
    onDurationChange: (Int, Int?) -> Unit = { _, _ -> },
    onPlaybackChange: (PlaybackState) -> Unit = {},
) {
    val active = playback.frameIndex.coerceIn(0, project.frameCount - 1)

    Column(modifier = modifier) {
        // ---- transport row -------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onPlaybackChange(playback.copy(playing = !playback.playing)) },
            ) {
                Icon(
                    imageVector = if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playback.playing) "Pause" else "Play",
                )
            }
            IconButton(
                onClick = { onPlaybackChange(playback.copy(playing = false, frameIndex = 0, forward = true)) },
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Stop")
            }
            TextButton(
                onClick = {
                    val next = when (playback.loop) {
                        LoopMode.NONE -> LoopMode.LOOP
                        LoopMode.LOOP -> LoopMode.PING_PONG
                        LoopMode.PING_PONG -> LoopMode.NONE
                    }
                    onPlaybackChange(playback.copy(loop = next))
                },
            ) {
                Text(
                    text = when (playback.loop) {
                        LoopMode.NONE -> "Loop off"
                        LoopMode.LOOP -> "Loop"
                        LoopMode.PING_PONG -> "Ping-pong"
                    },
                    fontSize = 12.sp,
                    color = theme.accent,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "FPS", fontSize = 11.sp, color = theme.textSecondary)
                IconButton(
                    onClick = { onFpsChange((project.fps - 1).coerceAtLeast(MinFps)) },
                ) {
                    Text(text = "−", fontSize = 16.sp, color = theme.textPrimary)
                }
                Text(
                    text = project.fps.toString(),
                    fontSize = 12.sp,
                    color = theme.textPrimary,
                )
                IconButton(
                    onClick = { onFpsChange((project.fps + 1).coerceAtMost(MaxFps)) },
                ) {
                    Text(text = "+", fontSize = 16.sp, color = theme.textPrimary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Onion", fontSize = 11.sp, color = theme.textSecondary)
                Switch(
                    checked = playback.onionSkin,
                    onCheckedChange = { onPlaybackChange(playback.copy(onionSkin = it)) },
                )
            }
            Text(
                text = (playback.onionAlpha * 100 / 255).toString() + "%",
                fontSize = 11.sp,
                color = theme.textSecondary,
            )
            Slider(
                value = playback.onionAlpha / 255f,
                onValueChange = { f ->
                    onPlaybackChange(playback.copy(onionAlpha = (f * 255f + 0.5f).toInt().coerceIn(0, 255)))
                },
                modifier = Modifier.width(96.dp),
            )
        }

        // ---- frame ops row --------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onAddFrame(active) }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Add frame")
            }
            IconButton(onClick = { onDuplicateFrame(active) }) {
                Text(text = "⧉", fontSize = 14.sp, color = theme.textPrimary)
            }
            IconButton(
                onClick = { if (project.frameCount > 1) onDeleteFrame(active) },
                enabled = project.frameCount > 1,
            ) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete frame")
            }
            IconButton(
                onClick = { if (active > 0) onMoveFrame(active, active - 1) },
                enabled = active > 0,
            ) {
                Text(text = "◀", fontSize = 12.sp, color = theme.textPrimary)
            }
            IconButton(
                onClick = { if (active < project.frameCount - 1) onMoveFrame(active, active + 1) },
                enabled = active < project.frameCount - 1,
            ) {
                Text(text = "▶", fontSize = 12.sp, color = theme.textPrimary)
            }
        }

        // ---- frame strip ----------------------------------------------------
        Box(modifier = Modifier.height(FrameCardWidth + 8.dp)) {
            LazyRow {
                items(project.frames.size) { index ->
                    FrameCard(
                        theme = theme,
                        project = project,
                        index = index,
                        active = index == active,
                        onClick = { onFrameSelect(index) },
                    )
                }
            }
        }

        // ---- duration override editor ----------------------------------------
        val frame = project.frames[active]
        val durationText = frame.durationMs?.toString() ?: ""
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Frame ${active + 1} duration (ms)",
                fontSize = 11.sp,
                color = theme.textSecondary,
            )
            OutlinedTextField(
                value = durationText,
                onValueChange = { text ->
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) {
                        onDurationChange(active, null)
                    } else {
                        trimmed.toIntOrNull()?.let { ms ->
                            if (ms in 1..MaxDurationMs) onDurationChange(active, ms)
                        }
                    }
                },
                modifier = Modifier.width(88.dp),
                singleLine = true,
                placeholder = { Text(text = "auto", fontSize = 11.sp) },
            )
            if (frame.durationMs != null) {
                TextButton(onClick = { onDurationChange(active, null) }) {
                    Text(text = "Reset", fontSize = 11.sp, color = theme.danger)
                }
            }
        }
    }
}

/**
 * One frame card: composited thumbnail ([SpriteProject.compositeFrame], so
 * all visible layers bake in), one-based index badge, effective duration
 * label, primary border on the active card and a tag-color bar on the bottom
 * edge for every [com.pixellab.core.model.AnimationTag] covering the frame.
 */
@Composable
private fun FrameCard(
    theme: PixelTheme,
    project: SpriteProject,
    index: Int,
    active: Boolean,
    onClick: () -> Unit,
) {
    val composited = remember(project, index) { project.compositeFrame(index) }
    val tag = remember(project, index) { project.tags.firstOrNull { index in it } }
    val tagStarts = remember(project, index) {
        tag != null && tag.startFrame == index
    }
    Box(
        modifier = Modifier
            .width(FrameCardWidth)
            .background(theme.elevated, RoundedCornerShape(FrameCardCorner))
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) theme.primary else theme.surface,
                shape = RoundedCornerShape(FrameCardCorner),
            )
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Canvas(modifier = Modifier.size(FrameThumbSize)) {
                drawImage(
                    composited.toImageBitmap(),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(FrameThumbSize.value.toInt(), FrameThumbSize.value.toInt()),
                    filterQuality = FilterQuality.None,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (index + 1).toString(),
                    fontSize = 10.sp,
                    color = if (active) theme.primary else theme.textSecondary,
                )
                Text(
                    text = " ${project.effectiveFrameDuration(index)}ms",
                    fontSize = 9.sp,
                    color = theme.textDisabled,
                )
            }
            if (tag != null) {
                Box(
                    modifier = Modifier
                        .height(TagBarHeight)
                        .padding(horizontal = 12.dp)
                        .background(tagColor(tag.name)),
                ) {}
                if (tagStarts) {
                    Text(text = tag.name, fontSize = 8.sp, color = theme.textSecondary)
                }
            }
        }
    }
}

/**
 * Deterministic tag color derived from the tag name: hue = name hash mod
 * 360, fixed saturation/value. Distinct names almost always get distinct
 * hues and the mapping is stable across recompositions.
 */
private fun tagColor(name: String): androidx.compose.ui.graphics.Color =
    ColorMath.hsvToArgb((name.hashCode().mod(360) + 360) % 360f, 0.65f, 0.92f).argbColor()
