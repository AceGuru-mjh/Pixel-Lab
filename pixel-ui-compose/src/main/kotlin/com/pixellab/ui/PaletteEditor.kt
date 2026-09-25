package com.pixellab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.Palette
import com.pixellab.core.tools.OutlineShading
import com.pixellab.ui.theme.PixelTheme
import com.pixellab.ui.theme.argbColor
import kotlin.math.roundToInt

/** Columns of the swatch grid. */
private const val GridColumns = 6

/** Side length of one swatch cell. */
private val SwatchSize = 40.dp

/** Corner radius of one swatch. */
private val SwatchCorner = 4.dp

/** Height of the palette action button row (44dp touch target). */
private val ActionRowHeight = 44.dp

/** Number of steps of the generated ramp. */
private const val RampSteps = 5

/** Height of the scrollable swatch grid region. */
private val GridMaxHeight = 216.dp

/**
 * Pure palette ordering helpers for the palette editor. Both sorts are
 * **stable**: colors with equal keys keep their original relative order
 * (Kotlin's `sortedBy`/`sortedByDescending` are stable merges over the input
 * order), which keeps re-sorting deterministic — a required property for
 * undo-able palette edits.
 */
object PaletteSort {

    /**
     * Sorts [colors] by HSV hue ascending (`0..360`), achromatic colors
     * (gray/black/white, hue 0) first. Pure black/white/gray all share the
     * neutral bucket and keep their input order among themselves.
     */
    fun byHue(colors: IntArray): IntArray =
        colors.withIndex()
            .sortedBy { ColorMath.argbToHsv(it.value)[0] }
            .map { it.value }
            .toIntArray()

    /**
     * Sorts [colors] by perceived luminance ([ColorMath.luminance])
     * descending — brightest first. Equal luminances keep input order
     * (stability).
     */
    fun byLuminance(colors: IntArray): IntArray =
        colors.withIndex()
            .sortedByDescending { ColorMath.luminance(it.value) }
            .map { it.value }
            .toIntArray()
}

/**
 * Working palette editor: swatch grid with index badges and a selected ring,
 * per-palette actions (add current color, delete/duplicate selected, sort by
 * hue / luminance / original order, generate a 5-step ramp around the
 * selected color), an inline color replacement mode backed by [ColorSliders]
 * and a stats line (count + average luminance).
 *
 * `selected` is the *index* of the selected swatch (`-1` = none); [onSelect]
 * reports the tapped index. All mutations are expressed as whole-palette
 * replacements through [onPaletteChange] built with [Palette.withColor] /
 * [Palette.withoutIndex], so the host can route them through
 * [EditorSession.edit] as single undo steps.
 *
 * Ramp: [OutlineShading.ramp] with [RampSteps] steps is generated from the
 * selected color and *appended* to the palette (never overwrites — the
 * source swatch survives; documented deviation from a destructive ramp).
 *
 * @param theme active theme.
 * @param palette the palette being edited.
 * @param selected index of the selected swatch, or -1 for none.
 * @param onSelect invoked with the tapped swatch index.
 * @param onPaletteChange invoked with the full replacement palette.
 * @param modifier host modifier.
 * @param currentColor the editor's active draw color, used by "add color"
 *   and ramp generation when nothing is selected.
 */
@Composable
fun PaletteEditor(
    theme: PixelTheme,
    palette: Palette,
    selected: Int,
    onSelect: (Int) -> Unit,
    onPaletteChange: (Palette) -> Unit,
    modifier: Modifier = Modifier,
    currentColor: Int = palette[selected.coerceIn(0, palette.size - 1)],
) {
    val safeSelected = if (selected in palette.colors.indices) selected else -1
    val rampBase = if (safeSelected >= 0) palette[safeSelected] else currentColor

    // Inline replacement editor state: draft color + expanded flag.
    var replacing by remember { mutableStateOf(false) }
    var draftColor by remember { mutableStateOf(currentColor) }

    var sortMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // ---- swatch grid --------------------------------------------------
        Box(modifier = Modifier.height(GridMaxHeight)) {
            LazyVerticalGrid(columns = GridCells.Fixed(GridColumns)) {
                items(palette.size) { index ->
                    SwatchCell(
                        theme = theme,
                        color = palette[index],
                        index = index,
                        selected = index == safeSelected,
                        onClick = { onSelect(index) },
                    )
                }
            }
        }

        // ---- action row ----------------------------------------------------
        Row(
            verticalAlignment = Alignment.Center,
            modifier = Modifier.height(ActionRowHeight),
        ) {
            IconButton(onClick = { onPaletteChange(palette.withColor(currentColor)) }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Add current color")
            }
            IconButton(
                onClick = {
                    if (safeSelected >= 0) onPaletteChange(palette.withColor(palette[safeSelected]))
                },
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Duplicate selected")
            }
            IconButton(
                onClick = {
                    if (safeSelected >= 0) onPaletteChange(palette.withoutIndex(safeSelected))
                },
            ) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete selected")
            }
            TextButton(onClick = { sortMenuOpen = true }) {
                Text(text = "Sort", fontSize = 12.sp, color = theme.textSecondary)
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(text = "By hue") },
                    onClick = {
                        sortMenuOpen = false
                        onPaletteChange(palette.withColors(PaletteSort.byHue(palette.colors)))
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = "By luminance") },
                    onClick = {
                        sortMenuOpen = false
                        onPaletteChange(palette.withColors(PaletteSort.byLuminance(palette.colors)))
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = "Original") },
                    onClick = {
                        sortMenuOpen = false
                        onPaletteChange(palette.withColors(palette.colors))
                    },
                )
            }
            TextButton(
                onClick = {
                    val ramp = OutlineShading.ramp(rampBase, RampSteps)
                    if (ramp.isNotEmpty()) onPaletteChange(palette.withColors(palette.colors + ramp))
                },
            ) {
                Text(text = "Ramp", fontSize = 12.sp, color = theme.accent)
            }
            TextButton(
                onClick = {
                    replacing = !replacing
                    if (replacing && safeSelected >= 0) draftColor = palette[safeSelected]
                },
            ) {
                Text(
                    text = if (replacing) "Close" else "Replace",
                    fontSize = 12.sp,
                    color = if (replacing) theme.danger else theme.textSecondary,
                )
            }
        }

        // ---- inline replacement region ------------------------------------
        if (replacing && safeSelected >= 0) {
            ColorSliders(
                theme = theme,
                argb = draftColor,
                onColorChange = { draftColor = it },
                showAlpha = true,
            )
            Row {
                TextButton(
                    onClick = {
                        onPaletteChange(palette.withReplaced(safeSelected, draftColor))
                        replacing = false
                    },
                ) {
                    Text(text = "Apply", fontSize = 12.sp, color = theme.success)
                }
                TextButton(onClick = { replacing = false }) {
                    Text(text = "Cancel", fontSize = 12.sp, color = theme.textSecondary)
                }
            }
        }

        // ---- stats line -----------------------------------------------------
        val avgLum = remember(palette) {
            palette.colors.map { ColorMath.luminance(it) }.average().toFloat()
        }
        Text(
            text = "${palette.size} colors · avg lum ${(avgLum * 100f).roundToInt()}%",
            fontSize = 10.sp,
            color = theme.textSecondary,
        )
    }
}

/**
 * One palette swatch: solid fill, one-based index badge (contrast-safe ink),
 * primary ring when selected.
 */
@Composable
private fun SwatchCell(
    theme: PixelTheme,
    color: Int,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .size(SwatchSize)
            .background(color.argbColor(), RoundedCornerShape(SwatchCorner))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) theme.primary else theme.elevated,
                shape = RoundedCornerShape(SwatchCorner),
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            text = (index + 1).toString(),
            modifier = Modifier.padding(1.dp),
            fontSize = 8.sp,
            color = ColorMath.contrastSafeTextColor(color).argbColor(),
        )
    }
}

/** Rebuilds [Palette] with the same identity and a new color array. */
private fun Palette.withColors(colors: IntArray): Palette =
    Palette(id, name, colors, source)

/** Rebuilds [Palette] with the color at [index] replaced. */
private fun Palette.withReplaced(index: Int, color: Int): Palette {
    val next = colors.copyOf()
    if (index in next.indices) next[index] = color
    return Palette(id, name, next, source)
}
