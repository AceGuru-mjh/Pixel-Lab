package com.pixellab.core.tools

import com.pixellab.core.model.PixelFrame

/**
 * Per-channel blend modes for pixel compositing.
 *
 * Every mode composites a **source** (top) pixel over a **destination**
 * (bottom) pixel:
 *
 *  * The **alpha** channel is always the classic source-over combination
 *    `outA = sa + da * (255 - sa) / 255` (`sa` = source alpha, `da` =
 *    destination alpha), regardless of the mode.
 *  * The **RGB** channels are blended with the mode's own formula, then
 *    weighted exactly like [com.pixellab.core.model.SpriteProject]'s
 *    compositor: `out = (blended * sa + dst * da * (255 - sa) / 255) / outA`.
 *    A fully opaque source therefore shows the pure mode result; a fully
 *    transparent source leaves the destination untouched.
 *
 * All arithmetic is fixed-point integer math with half-up rounding, so results
 * are bit-identical for identical inputs. All modes are pure functions.
 */
enum class BlendMode {

    /**
     * Plain source-over replacement.
     *
     * Formula per channel: `out = s`.
     */
    NORMAL {
        override fun blend(src: Int, dst: Int): Int = over(src, dst) { s, _ -> s }
    },

    /**
     * Multiplicative darkening.
     *
     * Formula per channel: `out = s * d / 255` (white is neutral).
     */
    MULTIPLY {
        override fun blend(src: Int, dst: Int): Int = over(src, dst) { s, d -> (s * d + 127) / 255 }
    },

    /**
     * Screen (inverse-multiply brightening).
     *
     * Formula per channel: `out = 255 - (255 - s) * (255 - d) / 255`
     * (black is neutral).
     */
    SCREEN {
        override fun blend(src: Int, dst: Int): Int =
            over(src, dst) { s, d -> 255 - ((255 - s) * (255 - d) + 127) / 255 }
    },

    /**
     * Linear addition, clamped to the channel maximum.
     *
     * Formula per channel: `out = min(255, s + d)`.
     */
    ADD {
        override fun blend(src: Int, dst: Int): Int = over(src, dst) { s, d -> minOf(255, s + d) }
    },

    /**
     * Linear subtraction of the source from the destination, clamped at zero.
     *
     * Formula per channel: `out = max(0, d - s)`.
     */
    SUBTRACT {
        override fun blend(src: Int, dst: Int): Int = over(src, dst) { s, d -> maxOf(0, d - s) }
    },

    /**
     * Absolute per-channel difference.
     *
     * Formula per channel: `out = abs(s - d)`.
     */
    DIFFERENCE {
        override fun blend(src: Int, dst: Int): Int = over(src, dst) { s, d -> if (s >= d) s - d else d - s }
    },

    /**
     * Keeps the brighter of the two channels (per-channel maximum).
     *
     * Formula per channel: `out = max(s, d)`.
     */
    LIGHTEN {
        override fun blend(src: Int, dst: Int): Int = over(src, dst) { s, d -> maxOf(s, d) }
    },

    /**
     * Keeps the darker of the two channels (per-channel minimum).
     *
     * Formula per channel: `out = min(s, d)`.
     */
    DARKEN {
        override fun blend(src: Int, dst: Int): Int = over(src, dst) { s, d -> minOf(s, d) }
    },

    /**
     * Contrast-preserving combination of multiply and screen, driven by the
     * destination luminance.
     *
     * Formula per channel:
     * `out = 2 * s * d / 255` when `d < 128`, otherwise
     * `out = 255 - 2 * (255 - s) * (255 - d) / 255`.
     */
    OVERLAY {
        override fun blend(src: Int, dst: Int): Int = over(src, dst) { s, d ->
            if (d < 128) (2 * s * d + 127) / 255 else 255 - (2 * (255 - s) * (255 - d) + 127) / 255
        }
    };

    /**
     * Blends the packed ARGB [src] pixel over the packed ARGB [dst] pixel with
     * this mode. See the enum KDoc for the alpha shell and the per-mode
     * formulas. Pure function; neither input is modified (they are immutable
     * `Int`s anyway).
     */
    abstract fun blend(src: Int, dst: Int): Int
}

/**
 * Source-over alpha shell shared by every [BlendMode] constant: computes the
 * output alpha and weights the mode's blended channels exactly like
 * [com.pixellab.core.model.SpriteProject.compositeFrame] does for plain
 * compositing. [channel] is the mode's per-channel formula on 0..255 values.
 */
private fun over(src: Int, dst: Int, channel: (Int, Int) -> Int): Int {
    val sa = src ushr 24
    val da = dst ushr 24
    val outA = sa + da * (255 - sa) / 255
    if (outA == 0) return 0
    val inverseSa = 255 - sa

    fun mix(sourceChannel: Int, dstChannel: Int): Int {
        val blended = channel(sourceChannel, dstChannel).coerceIn(0, 255)
        return (blended * sa + dstChannel * da * inverseSa / 255) / outA
    }

    val r = mix((src shr 16) and 0xFF, (dst shr 16) and 0xFF)
    val g = mix((src shr 8) and 0xFF, (dst shr 8) and 0xFF)
    val b = mix(src and 0xFF, dst and 0xFF)
    return (outA shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Composites the [top] frame over the [bottom] frame with [mode], pixel by
 * pixel, into a fresh frame. Frames must have identical dimensions.
 *
 * [topOpacity] scales the top frame's alpha channel before blending
 * (`0.0` makes the top fully transparent, `1.0` keeps its alpha). RGB
 * channels of the top frame are never scaled, matching the common
 * "opacity slider" behavior of pixel editors.
 *
 * @throws IllegalArgumentException when the frame dimensions differ,
 *   [topOpacity] is outside `[0, 1]` or NaN.
 */
fun compositeWithBlend(
    bottom: PixelFrame,
    top: PixelFrame,
    mode: BlendMode,
    topOpacity: Float = 1f,
): PixelFrame {
    require(bottom.width == top.width && bottom.height == top.height) {
        "compositeWithBlend frames must match: bottom ${bottom.width}x${bottom.height}, " +
            "top ${top.width}x${top.height}"
    }
    require(!topOpacity.isNaN() && topOpacity in 0f..1f) {
        "topOpacity must be in [0, 1] (was $topOpacity)"
    }
    val scale = (topOpacity * 255f + 0.5f).toInt().coerceIn(0, 255)
    val out = IntArray(bottom.pixels.size)
    for (i in out.indices) {
        val src = if (scale == 255) top.pixels[i] else scaleAlpha(top.pixels[i], scale)
        out[i] = mode.blend(src, bottom.pixels[i])
    }
    return PixelFrame.of(bottom.width, bottom.height, out)
}

/** Scales the alpha channel of [argb] by [scale] (0..255), keeping RGB intact. */
private fun scaleAlpha(argb: Int, scale: Int): Int {
    if (scale == 255) return argb
    val a = ((argb ushr 24) * scale + 127) / 255
    if (a == 0) return 0
    return (a shl 24) or (argb and 0x00FFFFFF)
}
