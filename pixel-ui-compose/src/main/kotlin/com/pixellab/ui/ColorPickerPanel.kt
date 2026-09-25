package com.pixellab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.core.model.Palette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Side of the HSV hue wheel. */
private val WheelSize = 140.dp

/** Ring radius as a fraction of the wheel radius. */
private const val WheelRingRadiusFactor = 0.80f

/** Side of one hue block as a fraction of the wheel radius. */
private const val WheelBlockFactor = 0.24f

/** Side of the center saturation/value grid as a fraction of the radius. */
private const val WheelGridFactor = 0.66f

/** Inner radius (hit zone start) of the hue ring, as a fraction of radius. */
private const val WheelRingInnerFactor = 0.55f

/** Number of hue segments around the wheel. */
private const val HueSegmentCount = 12

/** Sampling resolution of the center saturation/value grid. */
private const val SvGridResolution = 8

/** Sampling steps of the brightness preview strip. */
private const val BrightnessStripSteps = 24

/** Side of the big current-color swatch. */
private val CurrentSwatchSize = 56.dp

/** Side of one recent-color swatch. */
private val RecentSwatchSize = 32.dp

/** Number of recent-color swatches shown. */
private const val RecentSwatchCount = 8

/** Side of the visual palette swatch. */
private val PaletteSwatchVisualSize = 24.dp

/** Touch wrapper around one palette swatch. */
private val PaletteSwatchTouchSize = 28.dp

/** Corner radius of every swatch. */
private val SwatchCorner = 4.dp

/** Maximum height of the scrollable palette grid. */
private val PaletteGridMaxHeight = 224.dp

/** Height of the brightness preview strip. */
private val BrightnessStripHeight = 12.dp

/** Width of the selection border around swatches and wheel markers. */
private val SelectedBorderWidth = 2.dp

/** `#RRGGBB` validation pattern of the hex field. */
private val HexPattern = Regex("^#[0-9a-fA-F]{6}$")

/**
 * Full HSV color picker panel: a self-drawn hue wheel (12 blocks around a
 * ring, an 8x8 saturation/value pixel grid in the center, selection markers
 * as rings), a brightness slider with a live gradient preview strip, a
 * validated `#RRGGBB` hex field, the host's recent colors and the full
 * [Palette] swatch grid, headed by the big current-color swatch and its hex
 * label.
 *
 * Wheel and grid are directly draggable: tapping or dragging the ring picks
 * the nearest hue segment, dragging the center grid sets saturation
 * (horizontal) and value (vertical, bright at the top). Every pick goes
 * through [onColorSelect] with the alpha of [selectedColor] preserved.
 *
 * The hex field updates the selection while the text is a valid `#RRGGBB`
 * value; invalid input keeps the last valid selection, is never reported
 * through [onColorSelect], and shows a hint line instead.
 *
 * @param selectedColor currently selected ARGB color.
 * @param onColorSelect invoked with every picked ARGB color.
 * @param palette the palette rendered as a swatch grid.
 * @param modifier host modifier.
 * @param recentColors up to [RecentSwatchCount] recently used colors,
 * newest first, rendered as a horizontal row of swatches.
 */
@Composable
fun ColorPickerPanel(
    selectedColor: Int,
    onColorSelect: (Int) -> Unit,
    palette: Palette,
    modifier: Modifier = Modifier,
    recentColors: List<Int> = emptyList(),
) {
    val scheme = MaterialTheme.colorScheme
    val currentOnColorSelect = rememberUpdatedState(onColorSelect)
    val currentSelected = rememberUpdatedState(selectedColor)

    // HSV of the current color; hue in degrees, s/v in 0..1.
    val hsv = remember(selectedColor) { argbToHsv(selectedColor) }
    val hue = hsv[0]
    val saturation = hsv[1]
    val value = hsv[2]
    val alpha = (selectedColor ushr 24) and 0xFF

    // Hex field state: follows external selection changes, validates input.
    var hexText by remember { mutableStateOf(hexOf(selectedColor)) }
    var hexInvalid by remember { mutableStateOf(false) }
    LaunchedEffect(selectedColor) {
        val parsed = parseHex(hexText)
        if (parsed == null || parsed != (0xFF shl 24) or (selectedColor and 0xFFFFFF)) {
            hexText = hexOf(selectedColor)
            hexInvalid = false
        }
    }

    // Wheel hit-testing needs the measured wheel size in pixels.
    var wheelSize by remember { mutableStateOf(IntSize.Zero) }

    /**
     * Applies a wheel tap/drag at [position]: ring hits pick the hue
     * segment, center-grid hits pick saturation/value. Reads the current
     * selection live so drag sessions keep the freshest color components.
     */
    fun applyWheel(position: Offset) {
        val px = wheelSize.width
        if (px <= 0) return
        val liveAlpha = (currentSelected.value ushr 24) and 0xFF
        val c = px / 2f
        val dx = position.x - c
        val dy = position.y - c
        val dist = sqrt(dx * dx + dy * dy)
        val gridSide = c * 2f * WheelGridFactor
        val gridLeft = c - gridSide / 2f
        val gridTop = c - gridSide / 2f
        val hsvNow = argbToHsv(currentSelected.value)
        if (position.x >= gridLeft && position.x <= gridLeft + gridSide &&
            position.y >= gridTop && position.y <= gridTop + gridSide
        ) {
            val s = ((position.x - gridLeft) / gridSide).coerceIn(0f, 1f)
            val v = (1f - (position.y - gridTop) / gridSide).coerceIn(0f, 1f)
            currentOnColorSelect.value.invoke(hsvToArgb(liveAlpha, hsvNow[0], s, v))
        } else if (dist >= c * WheelRingInnerFactor) {
            // Angle from twelve o'clock, clockwise, in degrees.
            val angle = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
            val degrees = ((angle + 360.0) % 360.0)
            val segment = ((degrees + 15.0) / 30.0).toInt() % HueSegmentCount
            currentOnColorSelect.value.invoke(
                hsvToArgb(liveAlpha, segment * 30f, hsvNow[1], hsvNow[2]),
            )
        }
    }

    Column(modifier = modifier) {
        // Header: big current-color swatch + hex + HSV readout.
        Row(verticalAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(CurrentSwatchSize)
                    .background(Color(selectedColor), RoundedCornerShape(SwatchCorner))
                    .border(SelectedBorderWidth, scheme.outline, RoundedCornerShape(SwatchCorner)),
                content = {},
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = hexOf(selectedColor),
                    fontSize = 14.sp,
                    color = scheme.onSurface,
                )
                Text(
                    text = "H ${hue.roundToInt()}°  S ${(saturation * 100).roundToInt()}%  V ${(value * 100).roundToInt()}%",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // HSV wheel: hue ring + center saturation/value grid.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .size(WheelSize)
                    .onSizeChanged { size -> wheelSize = size }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            applyWheel(down.position)
                            var watching = true
                            while (watching) {
                                val event = awaitPointerEvent()
                                if (event.changes.none { it.pressed }) {
                                    watching = false
                                } else {
                                    event.changes.firstOrNull()?.let { applyWheel(it.position) }
                                }
                            }
                        }
                    },
            ) {
                val c = size.width / 2f
                val radius = c
                val block = radius * WheelBlockFactor
                val halfBlock = block / 2f
                val selectedSegment = ((hue + 15f) / 30f).toInt() % HueSegmentCount
                for (k in 0 until HueSegmentCount) {
                    val angle = Math.toRadians((k * 30.0))
                    val bx = c + (sin(angle) * radius * WheelRingRadiusFactor).toFloat() - halfBlock
                    val by = c - (cos(angle) * radius * WheelRingRadiusFactor).toFloat() - halfBlock
                    if (k == selectedSegment) {
                        // Selection marker: primary ring under the block.
                        drawRect(
                            color = scheme.primary,
                            topLeft = Offset(bx - 2f, by - 2f),
                            size = Size(block + 4f, block + 4f),
                        )
                    }
                    drawRect(
                        color = Color(hsvToArgb(0xFF, k * 30f, 1f, 1f)),
                        topLeft = Offset(bx, by),
                        size = Size(block, block),
                    )
                }
                // Center saturation/value grid: s grows right, v grows up.
                val gridSide = radius * 2f * WheelGridFactor
                val gridLeft = c - gridSide / 2f
                val gridTop = c - gridSide / 2f
                val cell = gridSide / SvGridResolution
                for (j in 0 until SvGridResolution) {
                    for (i in 0 until SvGridResolution) {
                        val s = i.toFloat() / (SvGridResolution - 1)
                        val v = 1f - j.toFloat() / (SvGridResolution - 1)
                        val isSel = abs(s - saturation) < 1f / (SvGridResolution - 1) / 2f + 0.001f &&
                            abs(v - value) < 1f / (SvGridResolution - 1) / 2f + 0.001f
                        if (isSel) {
                            drawRect(
                                color = scheme.primary,
                                topLeft = Offset(gridLeft + i * cell - 2f, gridTop + j * cell - 2f),
                                size = Size(cell + 4f, cell + 4f),
                            )
                        }
                        drawRect(
                            color = Color(hsvToArgb(0xFF, hue, s, v)),
                            topLeft = Offset(gridLeft + i * cell, gridTop + j * cell),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Brightness preview strip (v gradient at the current h/s) + slider.
        Canvas(modifier = Modifier.fillMaxWidth().height(BrightnessStripHeight)) {
            val stripW = size.width / BrightnessStripSteps
            for (i in 0 until BrightnessStripSteps) {
                val v = i.toFloat() / (BrightnessStripSteps - 1)
                drawRect(
                    color = Color(hsvToArgb(alpha, hue, saturation, v)),
                    topLeft = Offset(i * stripW, 0f),
                    size = Size(stripW, size.height),
                )
            }
        }
        Slider(
            value = value,
            onValueChange = { v ->
                currentOnColorSelect.value.invoke(hsvToArgb(alpha, hue, saturation, v))
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Hex input with validation.
        OutlinedTextField(
            value = hexText,
            onValueChange = { text ->
                hexText = text
                val parsed = parseHex(text)
                hexInvalid = parsed == null
                parsed?.let { currentOnColorSelect.value.invoke(it) }
            },
            singleLine = true,
            label = { Text(text = "HEX", fontSize = 12.sp) },
            placeholder = { Text(text = "#RRGGBB", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (hexInvalid) {
            Text(
                text = "Invalid color — use #RRGGBB",
                fontSize = 11.sp,
                color = scheme.error,
            )
        }

        // Recent colors: up to 8 host-provided swatches.
        if (recentColors.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recent",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
            Row {
                for (color in recentColors.take(RecentSwatchCount)) {
                    ColorSwatch(
                        color = color,
                        selected = color == selectedColor,
                        visualSize = RecentSwatchSize,
                        onClick = { currentOnColorSelect.value.invoke(color) },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Palette swatch grid.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(PaletteSwatchTouchSize),
            modifier = Modifier.heightIn(max = PaletteGridMaxHeight),
        ) {
            items(palette.size) { index ->
                val color = palette[index]
                ColorSwatch(
                    color = color,
                    selected = color == selectedColor,
                    visualSize = PaletteSwatchVisualSize,
                    touchSize = PaletteSwatchTouchSize,
                    onClick = { currentOnColorSelect.value.invoke(color) },
                )
            }
        }
    }
}

/**
 * One color swatch: a rounded [visualSize] block centered inside a
 * [touchSize] touch target, bordered with the theme primary when selected.
 */
@Composable
private fun ColorSwatch(
    color: Int,
    selected: Boolean,
    visualSize: Dp,
    touchSize: Dp = visualSize,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(SwatchCorner)
    Box(
        modifier = Modifier
            .size(touchSize)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(visualSize)
                .background(Color(color), shape)
                .then(
                    if (selected) {
                        Modifier.border(SelectedBorderWidth, MaterialTheme.colorScheme.primary, shape)
                    } else {
                        Modifier
                    },
                ),
            content = {},
        )
    }
}

/**
 * `"#RRGGBB"` (lowercase) representation of [argb]; the alpha channel is
 * dropped, matching [Palette.toHex].
 */
private fun hexOf(argb: Int): String =
    "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')

/**
 * Parses `#RRGGBB` text into an opaque ARGB color.
 *
 * @return the color, or null when the text does not match [HexPattern].
 */
private fun parseHex(text: String): Int? {
    if (!HexPattern.matches(text)) return null
    val rgb = text.substring(1).toInt(16)
    return (0xFF shl 24) or rgb
}

/**
 * Converts an ARGB color to HSV (`h` in degrees `0..360`, `s` and `v` in
 * `0..1`), the standard max/min formulation:
 *
 * * `v = max(r', g', b')` with `r' = r/255` etc.;
 * * `s = (max - min) / max` (0 when `max == 0`);
 * * `h = 60 * ((g' - b')/delta mod 6)` when max is red,
 *   `h = 60 * ((b' - r')/delta + 2)` when max is green,
 *   `h = 60 * ((r' - g')/delta + 4)` when max is blue, `h = 0` for
 *   `delta == 0`; negative results wrap by +360.
 *
 * @return `[h, s, v]` as a three-element float array.
 */
private fun argbToHsv(argb: Int): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val v = max
    val s = if (max <= 0f) 0f else delta / max
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    return floatArrayOf(if (h < 0f) h + 360f else h, s, v)
}

/**
 * Converts HSV to ARGB with the hexagonal (sector) formula:
 *
 * * `c = v * s`, `m = v - c`;
 * * `x = c * (1 - |(h/60 mod 2) - 1|)`;
 * * sector `floor(h/60) mod 6` picks `(r'', g'', b'')` from
 *   `(c,x,0), (x,c,0), (0,c,x), (0,x,c), (x,0,c), (c,0,x)`;
 * * each channel is `(channel'' + m) * 255`, rounded and clamped to 0..255.
 *
 * @param alpha output alpha, 0..255.
 * @param h hue in degrees, any range (wrapped).
 * @param s saturation, clamped to 0..1.
 * @param v value, clamped to 0..1.
 * @return the packed `0xAARRGGBB` color.
 */
private fun hsvToArgb(alpha: Int, h: Float, s: Float, v: Float): Int {
    val sc = s.coerceIn(0f, 1f)
    val vc = v.coerceIn(0f, 1f)
    val hp = (((h % 360f) + 360f) % 360f) / 60f
    val c = vc * sc
    val x = c * (1f - abs((hp % 2f) - 1f))
    val m = vc - c
    val rgb = when (hp.toInt()) {
        0 -> floatArrayOf(c, x, 0f)
        1 -> floatArrayOf(x, c, 0f)
        2 -> floatArrayOf(0f, c, x)
        3 -> floatArrayOf(0f, x, c)
        4 -> floatArrayOf(x, 0f, c)
        else -> floatArrayOf(c, 0f, x)
    }
    val r = ((rgb[0] + m) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((rgb[1] + m) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((rgb[2] + m) * 255f).roundToInt().coerceIn(0, 255)
    return ((alpha and 0xFF) shl 24) or (r shl 16) or (g shl 8) or b
}
