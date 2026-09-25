package com.pixellab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Total height of the status strip (a one-line 3dp-padded bar). */
private val ReadoutHeight = 20.dp

/** Vertical padding inside the strip, the compact "3dp" body padding. */
private val ReadoutBodyPadding = 3.dp

/** Side of the color swatch in the strip. */
private val ReadoutSwatchSize = 12.dp

/**
 * Bottom status strip that renders the live cursor readout of
 * [PixelCanvasPro]: the pointer coordinates as `"(x, y) / w x h"`, a small
 * color swatch and its `#RRGGBB` hex value, all in a monospace face.
 *
 * With [info] null (pointer outside the grid) the strip degrades to a dash
 * plus the canvas size. Colors come from [MaterialTheme.colorScheme].
 *
 * @param info last [PixelPointerInfo] reported by the canvas, or null.
 * @param canvasWidth canvas width in pixels.
 * @param canvasHeight canvas height in pixels.
 * @param modifier host modifier.
 */
@Composable
fun PixelCursorReadout(
    info: PixelPointerInfo?,
    canvasWidth: Int,
    canvasHeight: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ReadoutHeight)
            .background(scheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = ReadoutBodyPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coords = info?.let { "(${it.x}, ${it.y})" } ?: "—"
        Text(
            text = coords,
            fontSize = 10.sp,
            color = scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "/ ${canvasWidth}×${canvasHeight}",
            fontSize = 10.sp,
            color = scheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.width(6.dp))
        if (info != null) {
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(ReadoutSwatchSize)
                    .background(Color(info.color), RoundedCornerShape(2.dp))
                    .border(1.dp, scheme.outline, RoundedCornerShape(2.dp)),
                content = {},
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = hexLabel(info.color),
                fontSize = 10.sp,
                color = scheme.onSurface,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** `"#RRGGBB"` (lowercase) label of [argb]. */
private fun hexLabel(argb: Int): String =
    "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
