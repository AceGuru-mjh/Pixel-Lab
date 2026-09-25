package com.pixellab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.Layer
import com.pixellab.core.model.SpriteProject
import kotlin.math.roundToInt

/** Vertical padding of the layer footer row. */
private val FooterPadding = 4.dp

/** Font size of a layer name. */
private val LayerNameFontSize = 14.sp

/** Font size of the opacity percentage and secondary labels. */
private val LayerMetaFontSize = 12.sp

/**
 * Layer stack panel listing [SpriteProject.layers] top-down (the bottom-up
 * list reversed, so the visually topmost layer is the first row) in a
 * scrollable column.
 *
 * Every row shows the layer's visibility toggle (eye / eye-off), its name,
 * and a small lock icon when the layer is locked; tapping a row makes it
 * active through [onActiveLayerChange] and gives it a `primaryContainer`
 * background. The active row expands with its controls:
 *
 * - an opacity slider (`0..1`, continuously reported through
 *   [onLayerOpacityChange]) with the current percentage;
 * - a lock toggle (lock / lock-open) reporting [onLayerLockedChange];
 * - move-up / move-down buttons reporting [onMoveLayer] with the layer id and
 *   the target index in the bottom-up stack (`idx + 1` moves the layer up,
 *   `idx - 1` moves it down); the up button is disabled for the top layer and
 *   the down button for the bottom layer.
 *
 * A footer row offers add (proposing the name `"Layer <n + 1>"` to
 * [onAddLayer]) and delete (removing the active layer through
 * [onRemoveLayer], disabled while only one layer remains because the engine
 * requires at least one).
 *
 * @param project the project whose layer stack is displayed.
 * @param onActiveLayerChange invoked with the layer id of the tapped row.
 * @param onAddLayer invoked with a proposed name for the new layer.
 * @param onRemoveLayer invoked with the id of the active layer to delete.
 * @param onRenameLayer contract callback; this panel intentionally renders
 * the layer name as static text, so the parameter is accepted but unused.
 * @param onMoveLayer invoked with (layer id, target bottom-up index).
 * @param onLayerOpacityChange invoked with (layer id, new opacity in 0..1).
 * @param onLayerVisibleChange invoked with (layer id, new visibility).
 * @param onLayerLockedChange invoked with (layer id, new locked state).
 * @param modifier host modifier.
 */
@Composable
fun LayerPanel(
    project: SpriteProject,
    onActiveLayerChange: (Int) -> Unit = {},
    onAddLayer: (String) -> Unit = {},
    onRemoveLayer: (Int) -> Unit = {},
    // Two-parameter defaults need explicit unused-parameter lambdas: Kotlin
    // cannot coerce the literal `{}` to a Function2 receiver.
    onRenameLayer: (Int, String) -> Unit = { _, _ -> },
    onMoveLayer: (Int, Int) -> Unit = { _, _ -> },
    onLayerOpacityChange: (Int, Float) -> Unit = { _, _ -> },
    onLayerVisibleChange: (Int, Boolean) -> Unit = { _, _ -> },
    onLayerLockedChange: (Int, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val layers = project.layers
    val activeLayerId = project.activeLayerId

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        // Top-down: iterate the bottom-up stack from its last index down.
        for (index in layers.indices.reversed()) {
            val layer = layers[index]
            LayerRow(
                layer = layer,
                layerIndex = index,
                selected = layer.id == activeLayerId,
                canMoveUp = index < layers.lastIndex,
                canMoveDown = index > 0,
                onActiveLayerChange = onActiveLayerChange,
                onMoveLayer = onMoveLayer,
                onLayerOpacityChange = onLayerOpacityChange,
                onLayerVisibleChange = onLayerVisibleChange,
                onLayerLockedChange = onLayerLockedChange,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FooterPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onAddLayer("Layer ${layers.size + 1}") }) {
                Icon(Icons.Filled.Add, contentDescription = "Add layer")
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { onRemoveLayer(activeLayerId) },
                enabled = layers.size > 1,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete active layer")
            }
        }
    }
}

/**
 * One layer row: visibility toggle, name and lock marker; the whole row is
 * clickable and selects the layer. The selected row additionally renders the
 * expanded controls (opacity slider + percentage, lock toggle, move up/down).
 */
@Composable
private fun LayerRow(
    layer: Layer,
    layerIndex: Int,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onActiveLayerChange: (Int) -> Unit,
    onMoveLayer: (Int, Int) -> Unit,
    onLayerOpacityChange: (Int, Float) -> Unit,
    onLayerVisibleChange: (Int, Boolean) -> Unit,
    onLayerLockedChange: (Int, Boolean) -> Unit,
) {
    val rowBackground = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .clickable(onClick = { onActiveLayerChange(layer.id) }),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onLayerVisibleChange(layer.id, !layer.visible) }) {
                Icon(
                    imageVector = if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (layer.visible) "Hide layer" else "Show layer",
                )
            }
            Text(
                text = layer.name,
                fontSize = LayerNameFontSize,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (layer.locked) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "Layer locked",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onLayerLockedChange(layer.id, !layer.locked) }) {
                    Icon(
                        imageVector = if (layer.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (layer.locked) "Unlock layer" else "Lock layer",
                    )
                }
                IconButton(
                    onClick = { onMoveLayer(layer.id, layerIndex + 1) },
                    enabled = canMoveUp,
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move layer up")
                }
                IconButton(
                    onClick = { onMoveLayer(layer.id, layerIndex - 1) },
                    enabled = canMoveDown,
                ) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move layer down")
                }
                Text(
                    text = "${(layer.opacity * 100).roundToInt()}%",
                    fontSize = LayerMetaFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = layer.opacity,
                onValueChange = { onLayerOpacityChange(layer.id, it) },
                valueRange = 0f..1f,
            )
        }
    }
}
