package com.pixellab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixellab.ui.theme.PixelTheme
import com.pixellab.ui.theme.argbColor

/** Width of the two-character slider labels (R, G, B, H, S, V, A). */
private val SliderLabelWidth = 18.dp

/** Width of the numeric value readout after each slider. */
private val SliderValueWidth = 34.dp

/** Height of the current/previous swatch pair. */
private val SwatchHeight = 32.dp

/** Side length of one swatch of the current/previous pair. */
private val SwatchSize = 44.dp

/** Radius of the swatch pair. */
private val SwatchCorner = 4.dp

/**
 * Pure color math shared by the editor suite (sliders, swatches, palette
 * editor). Deliberately free of Compose so it is unit-testable on the JVM.
 *
 * HSV convention: hue in degrees `0..360` (wraps), saturation and value in
 * `0..1` — the hexahedron ("color cube") model, the same family the HSV wheel
 * of [ColorPickerPanel] uses. Round-trips through [argbToHsv] and
 * [hsvToArgb] are exact for the sRGB vertices and within ±1 per channel for
 * arbitrary colors (integer rounding on the way back in).
 */
object ColorMath {

    /**
     * Converts packed ARGB to HSV.
     *
     * @param argb packed color (alpha ignored).
     * @return `FloatArray(3)`: `[h 0..360, s 0..1, v 0..1]`; achromatic
     *   colors report `h = 0, s = 0`.
     */
    fun argbToHsv(argb: Int): FloatArray {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val v = max / 255f
        val s = if (max == 0) 0f else delta / max.toFloat()
        val h = when {
            delta == 0 -> 0f
            max == r -> 60f * ((g - b) / delta.toFloat())
            max == g -> 60f * ((b - r) / delta.toFloat() + 2f)
            else -> 60f * ((r - g) / delta.toFloat() + 4f)
        }
        return floatArrayOf((h + 360f) % 360f, s, v)
    }

    /**
     * Converts HSV to packed ARGB with an explicit alpha.
     *
     * @param h hue in degrees; wrapped into `0..360`.
     * @param s saturation, clamped to `0..1`.
     * @param v value, clamped to `0..1`.
     * @param a alpha `0..255`, clamped.
     * @return the packed color.
     */
    fun hsvToArgb(h: Float, s: Float, v: Float, a: Int = 0xFF): Int {
        val hue = ((h % 360f) + 360f) % 360f
        val sat = s.coerceIn(0f, 1f)
        val val0 = v.coerceIn(0f, 1f)
        val alpha = a.coerceIn(0, 255)
        val c = val0 * sat
        val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
        val m = val0 - c
        val (r, g, b) = when {
            hue < 60f -> listOf(c, x, 0f)
            hue < 120f -> listOf(x, c, 0f)
            hue < 180f -> listOf(0f, c, x)
            hue < 240f -> listOf(0f, x, c)
            hue < 300f -> listOf(x, 0f, c)
            else -> listOf(c, 0f, x)
        }
        fun ch(f: Float): Int = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /**
     * Parses a hex color string.
     *
     * Accepted forms: `#RRGGBB`, `#AARRGGBB`, with the leading `#` optional
     * and hex digits case-insensitive. Six-digit forms are fully opaque.
     *
     * @param text candidate string; surrounding whitespace is tolerated.
     * @return the packed ARGB color, or null when the text is malformed.
     */
    fun parseHex(text: String): Int? {
        val cleaned = text.trim().removePrefix("#").lowercase()
        if (cleaned.length != 6 && cleaned.length != 8) return null
        val value = cleaned.toLongOrNull(16) ?: return null
        return if (cleaned.length == 6) {
            (0xFFL shl 24 or value).toInt()
        } else {
            value.toInt()
        }
    }

    /**
     * Formats a packed color as hex.
     *
     * @param argb the color.
     * @param withAlpha true for `#aarrggbb`, false for `#rrggbb`.
     * @return lowercase hex with leading `#`.
     */
    fun toHex(argb: Int, withAlpha: Boolean = false): String {
        val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0')
        return if (withAlpha) {
            "#" + (argb ushr 24).toString(16).padStart(2, '0') + rgb
        } else {
            "#$rgb"
        }
    }

    /**
     * Perceived luminance of a color, `0..1`, using the sRGB->linear-free
     * Rec. 601 luma weights (0.2126 R + 0.7152 G + 0.0722 B) normalized to
     * `0..255`. Transparent colors read by their RGB payload (alpha ignored),
     * which matches how swatches are composited over panel surfaces.
     */
    fun luminance(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    /**
     * Ink color that stays readable on a swatch of [argb]: near-black on
     * bright swatches, warm white on dark ones. Threshold 0.55 keeps mid
     * grays on the dark side where the luma weights still read "dark".
     */
    fun contrastSafeTextColor(argb: Int): Int =
        if (luminance(argb) > 0.55f) 0xFF14161A.toInt() else 0xFFF2EDE4.toInt()
}

/**
 * Full color mixer: RGB sliders, HSV sliders, an alpha slider and a hex
 * field, plus the current/previous swatch pair.
 *
 * Every slider round-trips through [ColorMath] against the [argb] parameter;
 * changes are published through [onColorChange] with the full packed color
 * (alpha preserved unless the alpha slider moved). The hex field validates
 * locally — malformed text colors the field with the theme danger color and
 * publishes nothing until it parses again.
 *
 * The `previous` swatch of the pair remembers the last *externally* different
 * color: slider drags update the current swatch live, and once the host
 * commits a different color the old one becomes the comparison swatch (tap
 * target for a future "restore" behavior; hosts read it from the pair).
 *
 * @param theme active theme.
 * @param argb current packed ARGB color.
 * @param onColorChange invoked with the new packed color on every valid
 *   slider or hex edit.
 * @param modifier host modifier.
 * @param showAlpha whether the alpha slider (0..255) is rendered.
 */
@Composable
fun ColorSliders(
    theme: PixelTheme,
    argb: Int,
    onColorChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showAlpha: Boolean = true,
) {
    // Previous distinct externally-seen color, for the comparison swatch.
    var previousColor by remember { mutableStateOf(argb) }
    var lastExternal by remember { mutableStateOf(argb) }
    if (argb != lastExternal) {
        previousColor = lastExternal
        lastExternal = argb
    }

    // Hex field state: text mirrors the color unless the user is mid-edit;
    // the LaunchedEffect key only fires when the color actually changed, so
    // half-typed text is never clobbered (same pattern as ColorPickerPanel).
    var hexText by remember { mutableStateOf(ColorMath.toHex(argb)) }
    var hexError by remember { mutableStateOf(false) }
    LaunchedEffect(argb) {
        val parsed = ColorMath.parseHex(hexText)
        if (parsed == null || parsed != argb) {
            hexText = ColorMath.toHex(argb)
            hexError = false
        }
    }

    val hsv = remember(argb) { ColorMath.argbToHsv(argb) }

    Column(modifier = modifier) {
        // ---- current / previous swatch pair -----------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            SwatchPair(theme, current = argb, previous = previousColor)
        }

        // ---- RGB sliders -------------------------------------------------
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        SliderRow(
            theme = theme,
            label = "R",
            valueText = r.toString(),
            fraction = r / 255f,
        ) { f ->
            onColorChange((argb and 0xFF00FFFF.toInt()) or (channel(f) shl 16))
        }
        SliderRow(
            theme = theme,
            label = "G",
            valueText = g.toString(),
            fraction = g / 255f,
        ) { f ->
            onColorChange((argb and 0xFF00FFFF.toInt()) or (channel(f) shl 8))
        }
        SliderRow(
            theme = theme,
            label = "B",
            valueText = b.toString(),
            fraction = b / 255f,
        ) { f ->
            onColorChange((argb and 0xFFFFFF00.toInt()) or channel(f))
        }
        if (showAlpha) {
            SliderRow(
                theme = theme,
                label = "A",
                valueText = ((argb ushr 24) and 0xFF).toString(),
                fraction = ((argb ushr 24) and 0xFF) / 255f,
            ) { f ->
                onColorChange((argb and 0x00FFFFFF) or (channel(f) shl 24))
            }
        }

        // ---- HSV sliders -------------------------------------------------
        SliderRow(
            theme = theme,
            label = "H",
            valueText = hsv[0].toInt().toString(),
            fraction = hsv[0] / 360f,
        ) { f ->
            onColorChange(
                (argb and 0xFF000000.toInt()) or
                    (ColorMath.hsvToArgb(f * 360f, hsv[1], hsv[2]) and 0xFFFFFF),
            )
        }
        SliderRow(
            theme = theme,
            label = "S",
            valueText = (hsv[1] * 100f).toInt().toString(),
            fraction = hsv[1],
        ) { f ->
            onColorChange(
                (argb and 0xFF000000.toInt()) or
                    (ColorMath.hsvToArgb(hsv[0], f, hsv[2]) and 0xFFFFFF),
            )
        }
        SliderRow(
            theme = theme,
            label = "V",
            valueText = (hsv[2] * 100f).toInt().toString(),
            fraction = hsv[2],
        ) { f ->
            onColorChange(
                (argb and 0xFF000000.toInt()) or
                    (ColorMath.hsvToArgb(hsv[0], hsv[1], f) and 0xFFFFFF),
            )
        }

        // ---- hex field ----------------------------------------------------
        Spacer(modifier = Modifier.height(theme.metrics.panelGap))
        OutlinedTextField(
            value = hexText,
            onValueChange = { text ->
                hexText = text
                val parsed = ColorMath.parseHex(text)
                if (parsed == null) {
                    hexError = true
                } else {
                    hexError = false
                    if (parsed != argb) onColorChange(parsed)
                }
            },
            singleLine = true,
        )
        if (hexError) {
            Text(
                text = "Expected #RRGGBB or #AARRGGBB",
                fontSize = 10.sp,
                color = theme.danger,
            )
        }
    }
}

/** Maps a `0..1` slider fraction to a `0..255` channel value. */
private fun channel(fraction: Float): Int = (fraction * 255f + 0.5f).toInt().coerceIn(0, 255)

/**
 * One labeled slider row: two-character label, the slider itself, and the
 * numeric readout.
 */
@Composable
private fun SliderRow(
    theme: PixelTheme,
    label: String,
    valueText: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(theme.metrics.controlHeight),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(SliderLabelWidth),
            fontSize = 12.sp,
            color = theme.textSecondary,
        )
        Slider(
            value = fraction,
            onValueChange = onFraction,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .weight(1f),
        )
        Text(
            text = valueText,
            modifier = Modifier.width(SliderValueWidth),
            fontSize = 11.sp,
            color = theme.textPrimary,
        )
    }
}

/**
 * The current/previous swatch pair: big current swatch with hex readout and
 * the smaller previous swatch behind it.
 */
@Composable
private fun SwatchPair(theme: PixelTheme, current: Int, previous: Int) {
    Box(modifier = Modifier.height(SwatchHeight)) {
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .size(SwatchSize)
                .background(previous.argbColor(), RoundedCornerShape(SwatchCorner)),
        ) {}
        Column(
            modifier = Modifier
                .size(SwatchSize)
                .background(current.argbColor(), RoundedCornerShape(SwatchCorner))
                .padding(4.dp),
        ) {
            Text(
                text = ColorMath.toHex(current),
                fontSize = 9.sp,
                color = ColorMath.contrastSafeTextColor(current).argbColor(),
            )
        }
    }
}
