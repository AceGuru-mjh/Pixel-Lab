package com.pixellab.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.SpriteProject
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Width of the fixed control column on the left of the strip. */
private val ControlsColumnWidth = 140.dp

/** Outer width of one frame card. */
private val FrameCardWidth = 56.dp

/** Size of a frame thumbnail inside its card. */
private val FrameThumbSize = 48.dp

/** Corner radius of a frame card. */
private val FrameCardCorner = 4.dp

/** Inner padding of a frame card. */
private val FrameCardPadding = 4.dp

/** Width of the selection border of the active frame card. */
private val SelectedBorderWidth = 1.dp

/** Lowest selectable frames-per-second value. */
private const val MinFps = 1

/** Highest selectable frames-per-second value. */
private const val MaxFps = 24

/** Slider steps between [MinFps] and [MaxFps] (24 discrete values). */
private const val FpsSteps = 22

/**
 * Animation timeline: playback controls on the left, frame cards on the right.
 *
 * The control column offers play/pause, add/copy/delete frame actions (delete
 * is disabled while only one frame remains, matching the engine rule that a
 * project always keeps one frame), the project fps slider (`1..24`), and an
 * onion-skin toggle chip. The frame strip lists every frame as a card with a
 * composited thumbnail ([SpriteProject.compositeFrame] of that index, cached
 * by pixel content) plus its 1-based number; the card of [activeFrameIndex]
 * carries a primary border and tapping a card selects it.
 *
 * Playback: the strip owns an internal play/pause state seeded — and re-synced
 * whenever it changes — from the [playing] parameter, since the API contract
 * provides no play-toggle callback. While playing, a loop advances the frame
 * every `1000 / fps` milliseconds through [onFrameSelect], cycling
 * `(current + 1) % frameCount` around an internal playhead that follows
 * [activeFrameIndex] whenever the selection changes elsewhere.
 *
 * @param project the project whose frames are listed; frame add/copy/delete
 * callbacks receive no arguments and operate on the active frame.
 * @param activeFrameIndex index of the frame highlighted as active.
 * @param playing host-provided playback seed; the internal toggle re-syncs
 * to it whenever the value changes.
 * @param onFrameSelect invoked with the index of a tapped frame or of the
 * next playback frame.
 * @param onAddFrame invoked when the user requests a new frame.
 * @param onCloneFrame invoked when the user requests a copy of the active frame.
 * @param onDeleteFrame invoked when the user requests deletion of the active
 * frame; only reachable while more than one frame exists.
 * @param onFpsChange invoked with the new fps value (`1..24`) while dragging.
 * @param onOnionSkinChange invoked with the new onion-skin enabled state.
 * @param modifier host modifier.
 */
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
) {
    val frameCount = project.frameCount
    val safeActiveIndex = activeFrameIndex.coerceIn(0, frameCount - 1)
    val currentOnFrameSelect = rememberUpdatedState(onFrameSelect)

    var isPlaying by remember { mutableStateOf(playing) }
    LaunchedEffect(playing) { isPlaying = playing }

    var playhead by remember { mutableStateOf(safeActiveIndex) }
    LaunchedEffect(activeFrameIndex) { playhead = activeFrameIndex.coerceIn(0, frameCount - 1) }

    var onionEnabled by remember { mutableStateOf(false) }

    // Playback loop: one frame per 1000/fps milliseconds, cycling around the
    // internal playhead; restarting whenever play state, fps or frame count
    // changes.
    LaunchedEffect(isPlaying, project.fps, frameCount) {
        if (!isPlaying || frameCount < 1) return@LaunchedEffect
        while (true) {
            delay((1000f / project.fps).toLong().coerceAtLeast(1L))
            val next = (playhead + 1) % frameCount
            playhead = next
            currentOnFrameSelect.value.invoke(next)
        }
    }

    Row(modifier = modifier) {
        Column(modifier = Modifier.width(ControlsColumnWidth)) {
            Row {
                IconButton(onClick = { isPlaying = !isPlaying }) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause playback" else "Play playback",
                    )
                }
                IconButton(onClick = onAddFrame) {
                    Icon(Icons.Filled.Add, contentDescription = "Add frame")
                }
            }
            Row {
                TextButton(onClick = onCloneFrame) {
                    Text(text = "Copy")
                }
                IconButton(
                    onClick = onDeleteFrame,
                    enabled = frameCount > 1,
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete frame")
                }
            }
            Text(
                text = "FPS ${project.fps}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = project.fps.toFloat(),
                onValueChange = { onFpsChange(it.roundToInt().coerceIn(MinFps, MaxFps)) },
                valueRange = MinFps.toFloat()..MaxFps.toFloat(),
                steps = FpsSteps,
            )
            FilterChip(
                selected = onionEnabled,
                onClick = {
                    onionEnabled = !onionEnabled
                    onOnionSkinChange(onionEnabled)
                },
                label = { Text(text = "Onion") },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            LazyRow {
                items(frameCount) { index ->
                    TimelineFrameCard(
                        bitmap = remember(index, project) {
                            project.compositeFrame(index).toImageBitmap()
                        },
                        label = (index + 1).toString(),
                        selected = index == safeActiveIndex,
                        onClick = { currentOnFrameSelect.value.invoke(index) },
                    )
                }
            }
        }
    }
}

/**
 * One frame card: 56dp wide with a 48dp composited thumbnail and the 1-based
 * frame number underneath. The active card is outlined with a primary border.
 */
@Composable
private fun TimelineFrameCard(
    bitmap: ImageBitmap,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = MaterialTheme.colorScheme.primary
    val cardShape = RoundedCornerShape(FrameCardCorner)
    Column(
        modifier = Modifier
            .width(FrameCardWidth)
            .clickable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.border(SelectedBorderWidth, borderColor, cardShape)
                } else {
                    Modifier
                },
            )
            .padding(FrameCardPadding),
        horizontalAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "Frame $label",
            modifier = Modifier.size(FrameThumbSize),
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
