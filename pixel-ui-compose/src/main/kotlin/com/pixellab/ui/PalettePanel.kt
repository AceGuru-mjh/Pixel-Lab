package com.pixellab.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.Palette
import com.pixellab.core.palette.BuiltInPalettes

/** Size of the big current-color swatch in the header. */
private val CurrentSwatchSize = 48.dp

/** Corner radius of every color swatch. */
private val SwatchCorner = 4.dp

/** Visual size of one palette color swatch. */
private val SwatchVisualSize = 40.dp

/** Touch target of one palette color swatch (44dp minimum touch rule). */
private val SwatchTouchTarget = 44.dp

/** Number of swatch columns in the palette grid. */
private const val SwatchColumnCount = 4

/** Maximum height of the scrollable swatch grid. */
private val SwatchGridMaxHeight = 320.dp

/** Scale factor applied to a swatch while it is pressed. */
private const val SwatchPressScale = 0.92f

/** Width of the selection border around the active swatch. */
private val SelectedBorderWidth = 2.dp

/**
 * Color picker panel for a [Palette].
 *
 * Shows the currently selected color as a large swatch together with its
 * `#RRGGBB` text — resolved through [Palette.toHex] on the palette color
 * closest to [selectedColor], so the label always names a real palette entry.
 * Below the header, all palette colors are laid out in a 4-column scrollable
 * grid of 40dp rounded swatches. The active color (exact [selectedColor]
 * match) carries a 2dp primary border; pressing a swatch scales it to 0.92
 * and releasing selects it through [onColorSelect].
 *
 * The header also hosts a palette switcher menu listing every palette from
 * [BuiltInPalettes.all()]. Because [onPaletteSwitch] is a non-nullable
 * callback with a no-op default (per the API contract), the switcher is
 * always rendered; hosts that do not care about switching can simply ignore
 * the callback.
 *
 * @param palette the palette to display.
 * @param selectedColor the currently selected ARGB color.
 * @param onColorSelect invoked with the ARGB value of the tapped swatch.
 * @param onPaletteSwitch invoked when the user picks a built-in palette.
 * @param modifier host modifier.
 */
@Composable
fun PalettePanel(
    palette: Palette,
    selectedColor: Int,
    onColorSelect: (Int) -> Unit = {},
    onPaletteSwitch: (Palette) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val closestIndex = palette.findClosest(selectedColor)
    val hexLabel = palette.toHex(closestIndex)
    var menuExpanded by remember { mutableStateOf(false) }
    val swatchShape = RoundedCornerShape(SwatchCorner)

    Column(modifier = modifier) {
        // Header: big current-color swatch, hex label and palette switcher.
        Row(verticalAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(CurrentSwatchSize)
                    .background(Color(selectedColor), swatchShape),
                content = {},
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = hexLabel,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = palette.name,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Box {
                Row(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clickable(onClick = { menuExpanded = true })
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Palettes",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Switch palette",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    for (option in BuiltInPalettes.all()) {
                        DropdownMenuItem(
                            text = { Text(text = option.name) },
                            onClick = {
                                menuExpanded = false
                                onPaletteSwitch(option)
                            },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(SwatchColumnCount),
            modifier = Modifier.heightIn(max = SwatchGridMaxHeight),
        ) {
            items(palette.size) { index ->
                PaletteSwatch(
                    color = palette[index],
                    selected = palette[index] == selectedColor,
                    onClick = { onColorSelect(palette[index]) },
                )
            }
        }
    }
}

/**
 * One palette color cell: a 40dp rounded swatch centered inside a 44dp touch
 * target. While pressed the swatch scales to 0.92; when selected it carries a
 * primary border.
 */
@Composable
private fun PaletteSwatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    // The animation stub models the return as the animated value itself, so
    // no State delegation is used here; the call signature matches the real
    // animateFloatAsState(targetValue, label).
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) SwatchPressScale else 1f,
        label = "paletteSwatchPress",
    )
    val borderColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .size(SwatchTouchTarget)
            .pointerInput(Unit) {
                // Press tracking for the squash animation; taps are handled
                // by the clickable on the same box.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var watching = true
                    while (watching) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) {
                            watching = false
                        }
                    }
                    pressed = false
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SwatchVisualSize)
                .scale(pressScale)
                .background(Color(color), RoundedCornerShape(SwatchCorner))
                .then(
                    if (selected) {
                        Modifier.border(SelectedBorderWidth, borderColor, RoundedCornerShape(SwatchCorner))
                    } else {
                        Modifier
                    },
                ),
            content = {},
        )
    }
}
