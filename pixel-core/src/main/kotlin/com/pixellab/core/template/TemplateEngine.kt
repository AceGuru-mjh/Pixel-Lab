package com.pixellab.core.template

import com.pixellab.core.model.MutablePixelFrame
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject

/**
 * Text renderer and built-in template front-end for Pixel Lab.
 *
 * The engine exposes two independent services:
 *
 *  * [generateText] lays a string out with one of the frozen bitmap fonts
 *    ([Font5x7], [Font8x8]) and bakes it into a single [PixelFrame]. Newlines
 *    split the text into stacked lines; unsupported characters fall back to
 *    the space glyph, so any input renders deterministically.
 *
 *  * [apply] instantiates one of the six built-in sprites registered in
 *    [TemplateLibrary] (multi-frame, multi-layer, deterministic pixel art) as
 *    a fully assembled [SpriteProject] ready for editing.
 *
 * The engine itself is stateless: every call is a pure function of its
 * arguments, which makes it safe to share between threads. All output is
 * deterministic — identical inputs produce identical pixel bytes, with no
 * random number generation anywhere in the pipeline. The injected
 * [com.pixellab.core.PixelLabConfig] is only used as a diagnostics sink.
 */
class TemplateEngine(private val config: com.pixellab.core.PixelLabConfig) {

    /**
     * Renders [text] into a pixel frame using the bitmap [font].
     *
     * Layout rules:
     *  * `\n` splits the text into lines; the gap between two lines is
     *    [spacing] pixels (in font units, like every other measurement).
     *  * Characters advance by `glyphWidth + spacing` in font units.
     *  * A line's width is `n * glyphWidth + spacing * (n - 1)` where `n` is
     *    its character count; the frame width is the widest line.
     *  * The frame height is `lines * glyphHeight + spacing * (lines - 1)`.
     *  * Every measurement (widths, heights, gaps) is multiplied by [scale],
     *    and each font pixel expands to a `scale x scale` block.
     *
     * Characters without a glyph (anything outside the font's ASCII coverage)
     * render as the space glyph. Empty text yields a 1x1 fully transparent
     * frame; text whose lines are all empty keeps a non-negative width of 1.
     * Inside a glyph column byte, bit `k` (bit 0 = top row) lights the pixel
     * at row `k`, exactly as documented on [PixelFont].
     *
     * @param text the string to render; may be empty or contain newlines.
     * @param color ARGB color painted for every lit font pixel.
     * @param font the bitmap font; defaults to the classic [Font5x7].
     * @param spacing gap in font units between characters and lines;
     *   must be `>= 0`.
     * @param scale integer pixel zoom of the result; must be `>= 1`.
     * @throws IllegalArgumentException for negative [spacing] or [scale] < 1.
     */
    fun generateText(
        text: String,
        color: Int,
        font: PixelFont = Font5x7,
        spacing: Int = 1,
        scale: Int = 1,
    ): PixelFrame {
        require(spacing >= 0) { "spacing must be >= 0 (was $spacing)" }
        require(scale >= 1) { "scale must be >= 1 (was $scale)" }
        if (text.isEmpty()) return PixelFrame.blank(1, 1)

        val glyphW = font.glyphWidth
        val glyphH = font.glyphHeight
        val spaceGlyph = font.glyph(' ') ?: IntArray(glyphW)
        val lines: List<List<IntArray>> = text.split('\n').map { line ->
            line.map { ch -> font.glyph(ch) ?: spaceGlyph }
        }

        val contentWidth = lines.maxOf { glyphs ->
            glyphs.size * glyphW + spacing * maxOf(0, glyphs.size - 1)
        }
        val contentHeight = lines.size * glyphH + spacing * (lines.size - 1)
        val width = maxOf(1, contentWidth * scale)
        val height = maxOf(1, contentHeight * scale)

        val out = MutablePixelFrame(width, height)
        for ((lineIndex, glyphs) in lines.withIndex()) {
            var penX = 0
            for (glyph in glyphs) {
                for (col in 0 until glyphW) {
                    val bits = if (col < glyph.size) glyph[col] else 0
                    for (row in 0 until glyphH) {
                        if (bits and (1 shl row) != 0) {
                            val px = (penX + col) * scale
                            val py = (lineIndex * (glyphH + spacing) + row) * scale
                            for (sy in 0 until scale) {
                                for (sx in 0 until scale) {
                                    out[px + sx, py + sy] = color
                                }
                            }
                        }
                    }
                }
                penX += glyphW + spacing
            }
        }
        config.effectiveLogger.d(TAG, "generateText len=${text.length} -> ${width}x${height}")
        return out.toImmutable()
    }

    /**
     * Instantiates a built-in template as an editable [SpriteProject].
     *
     * The returned project carries the template's frames, layer stack and
     * pixel content. Cels are only created for layers that actually have
     * content in a given frame (an all-transparent layer renders nothing, so
     * it stays cel-less). The project id is derived from the template id
     * (`"tpl-" + templateId`), so repeated applications of the same template
     * produce equal projects — deterministic by construction.
     *
     * @param templateId one of [templateIds], e.g. `"clawd-pet"`.
     * @param palette optional override for the project's palette; `null`
     *   keeps the template's default palette. The override changes the
     *   project palette only — already painted pixels are not re-mapped.
     * @throws IllegalArgumentException when [templateId] is unknown.
     */
    fun apply(templateId: String, palette: Palette? = null): SpriteProject {
        config.effectiveLogger.d(
            TAG,
            "apply template=$templateId palette=${palette?.id ?: "default"}",
        )
        return TemplateLibrary.apply(templateId, palette)
    }

    /**
     * All registered template ids in stable registration order:
     * `clawd-pet`, `kimi-orb`, `minecraft-block`, `heart-pixel`,
     * `sword-pixel`, `coin-spin`.
     */
    fun templateIds(): List<String> = TemplateLibrary.ids()

    /**
     * Metadata about a single template, or `null` when [id] is unknown.
     * The returned [TemplateInfo.frames] and [TemplateInfo.layers] values
     * always match the frame count and layer count of the project produced
     * by [apply].
     */
    fun templateInfo(id: String): TemplateInfo? = TemplateLibrary.spec(id)?.let {
        TemplateInfo(
            id = it.id,
            name = it.name,
            width = it.width,
            height = it.height,
            description = it.description,
            frames = it.frameCount,
            layers = it.layerNames.size,
        )
    }

    /**
     * Static description of one built-in template.
     *
     * @property id machine id passed to [apply], e.g. `"coin-spin"`.
     * @property name human-readable display name.
     * @property width frame width in pixels.
     * @property height frame height in pixels.
     * @property description short summary of the sprite and its animation.
     * @property frames number of animation frames.
     * @property layers number of paint layers (bottom-up stack).
     */
    data class TemplateInfo(
        val id: String,
        val name: String,
        val width: Int,
        val height: Int,
        val description: String,
        val frames: Int,
        val layers: Int,
    )

    private companion object {
        /** Logging tag for the diagnostics sink. */
        const val TAG: String = "TemplateEngine"
    }
}
