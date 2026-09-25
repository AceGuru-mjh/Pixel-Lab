package com.pixellab.core.template

import com.pixellab.core.model.MutablePixelFrame
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.model.SpriteProject

/**
 * Horizontal placement of text lines inside the rendered frame.
 *
 * The alignment reference is the *text block*: the bounding box of the widest
 * line of the layout (see [TextStyler.measure]). Narrower lines slide inside
 * that box; the box itself never moves, so left- and right-aligned layouts of
 * the same text share identical outer dimensions.
 */
enum class TextAlign {
    /** Every line starts at the left edge of the text block. */
    LEFT,

    /** Every line is centered inside the text block (odd slack rounds down). */
    CENTER,

    /** Every line ends at the right edge of the text block. */
    RIGHT,
}

/**
 * Immutable 2D size in whole pixels — a tiny local counterpart of the classic
 * `IntSize`, kept JVM-pure because the core module must not depend on UI
 * geometry types. Used as the return type of [TextStyler.measure].
 *
 * Both dimensions are non-negative. [equals], [hashCode] and [toString] come
 * from the data-class machinery.
 */
data class IntSize(
    /** Extent along the x axis in pixels; never negative. */
    val width: Int,
    /** Extent along the y axis in pixels; never negative. */
    val height: Int,
) {
    init {
        require(width >= 0 && height >= 0) {
            "IntSize dimensions must be non-negative (${width}x${height})"
        }
    }

    /** Total number of cells covered by this size (`width * height`). */
    val area: Int get() = width * height

    /** True when either dimension is zero (a degenerate size). */
    val isEmpty: Boolean get() = width == 0 || height == 0

    override fun toString(): String = "${width}x${height}"
}

/**
 * Full styling options for [TextStyler]: fonts, spacing, alignment, integer
 * zoom and the three decoration effects (outline, glow, drop shadow).
 *
 * Two unit systems coexist and are documented per property:
 *
 *  * *font units* — the unscaled glyph grid, exactly as
 *    [TemplateEngine.generateText] treats its `spacing` argument. Values in
 *    font units are multiplied by [scale] when the body is rasterized.
 *  * *device pixels* — final canvas pixels. All effect geometry
 *    ([outlineThickness], [glowRadius], [shadowOffsetX]/[shadowOffsetY]) is
 *    expressed in device pixels so decorations stay hairline-crisp at any
 *    zoom level instead of thickening with [scale].
 *
 * The class is an immutable value type: every knob is a `val` and mutation
 * happens through `copy()`. Validation happens once in `init`, so an instance
 * that exists is always safe to render with.
 *
 * @property font bitmap font used for every glyph; unsupported characters
 *   fall back to the space glyph at raster time.
 * @property letterSpacing horizontal gap between two glyphs of the same line,
 *   in font units; `0` glues glyphs together. Must be `>= 0`.
 * @property lineSpacing vertical gap between two lines, in font units.
 *   Must be `>= 0`.
 * @property alignment how each line is placed inside the text block; see
 *   [TextAlign]. A single-line layout is alignment-invariant.
 * @property scale integer pixel zoom of the glyph body; every font pixel
 *   expands to a `scale x scale` block. Must be `>= 1`.
 * @property outlineColor solid ARGB color of the 4-neighborhood dilation
 *   outline drawn *under* the glyphs, or `null` to disable the outline.
 * @property outlineThickness how many dilation passes build the outline, in
 *   device pixels; `1` is the classic one-pixel pixel-art border. Must be
 *   `>= 0` (0 disables painting even when a color is set).
 * @property glowColor RGB base color of the 8-neighborhood dilation halo
 *   painted *under everything*, or `null` to disable the glow. The alpha
 *   channel of this value is ignored — [glowOpacity] owns the alpha.
 * @property glowRadius how many 8-neighborhood dilation passes build the
 *   halo, in device pixels. Must be `>= 0`.
 * @property glowOpacity uniform alpha of every glow pixel, `0..255`
 *   (`0x60` — about 38% — by default). The halo uses one flat alpha for the
 *   whole dilated area: a distance fade is deliberately traded away for
 *   byte-exact determinism, and the result still reads as a bloom because the
 *   glyph, outline and shadow layers later cover the core of the halo.
 * @property shadowColor solid ARGB color of the displaced copy of the glyph
 *   body that forms the drop shadow, or `null` to disable it.
 * @property shadowOffsetX horizontal displacement of the shadow copy in
 *   device pixels; negative values throw the shadow to the left.
 * @property shadowOffsetY vertical displacement of the shadow copy in device
 *   pixels; negative values throw the shadow upward.
 */
data class TextOptions(
    val font: PixelFont = Font5x7,
    val letterSpacing: Int = 1,
    val lineSpacing: Int = 1,
    val alignment: TextAlign = TextAlign.LEFT,
    val scale: Int = 1,
    val outlineColor: Int? = null,
    val outlineThickness: Int = 1,
    val glowColor: Int? = null,
    val glowRadius: Int = 1,
    val glowOpacity: Int = 0x60,
    val shadowColor: Int? = null,
    val shadowOffsetX: Int = 1,
    val shadowOffsetY: Int = 1,
) {
    init {
        require(letterSpacing >= 0) { "letterSpacing must be >= 0 (was $letterSpacing)" }
        require(lineSpacing >= 0) { "lineSpacing must be >= 0 (was $lineSpacing)" }
        require(scale >= 1) { "scale must be >= 1 (was $scale)" }
        require(outlineThickness >= 0) { "outlineThickness must be >= 0 (was $outlineThickness)" }
        require(glowRadius >= 0) { "glowRadius must be >= 0 (was $glowRadius)" }
        require(glowOpacity in 0..255) { "glowOpacity must be in 0..255 (was $glowOpacity)" }
    }
}

/**
 * Advanced text layout and decoration engine — the styled superset of
 * [TemplateEngine.generateText].
 *
 * Where `generateText` bakes a plain monochrome string, [TextStyler] adds:
 *
 *  * multi-line alignment ([TextAlign]) and independent letter/line spacing;
 *  * integer zoom ([TextOptions.scale]) with crisp nearest-neighbor blocks;
 *  * three stacked decoration effects rendered in a fixed layer order —
 *    **glow → shadow → outline → body** (first painted = bottom-most):
 *      1. *glow* — the glyph mask dilated [TextOptions.glowRadius] times with
 *         an 8-neighborhood (Chebyshev ring), painted in
 *         [TextOptions.glowColor] at a uniform [TextOptions.glowOpacity];
 *      2. *shadow* — a solid copy of the glyph body displaced by
 *         (`shadowOffsetX`, `shadowOffsetY`);
 *      3. *outline* — the glyph mask dilated [TextOptions.outlineThickness]
 *         times with a 4-neighborhood (Manhattan ring), painted solid and
 *         only where the body itself has no pixel, so it reads as a border
 *         hugging the glyphs;
 *      4. *body* — the glyph pixels in the requested color.
 *  * word wrapping ([wrapLines], [renderWrapped]) in character columns;
 *  * auto-fit ([fitText]) that steps the scale down until the frame fits a
 *    maximum width;
 *  * vertical (top-to-bottom) layout ([renderVertical]);
 *  * direct hand-off to the editor model ([toSpriteProject]).
 *
 * Determinism contract: every function is a pure function of its arguments.
 * There is no random number generation, no time dependence and no hidden
 * state, so identical inputs always produce byte-identical frames. This holds
 * for effect dilation too — the dilation kernels are plain integer rasters.
 *
 * Geometry contract: [measure] and [render] agree exactly — for any `text`,
 * `color` and `options`,
 * `measure(text, options) == IntSize(render(text, color, options).width, ...)`.
 * The frame is sized as the body bounding box grown by the effect extents:
 * glow and outline pad all four sides by their radius/thickness, the shadow
 * pads only the sides it is displaced towards. Empty text is special-cased to
 * a fully transparent 1x1 frame, matching [TemplateEngine.generateText].
 */
object TextStyler {

    // ------------------------------------------------------------ public API

    /**
     * Computes the exact dimensions [render] would produce for [text] under
     * [options].
     *
     * Layout math (font units, before [TextOptions.scale]):
     *  * line width  = `n * glyphWidth + letterSpacing * (n - 1)` for a line
     *    of `n` characters (0 for an empty line);
     *  * block width = widest line width;
     *  * block height = `lines * glyphHeight + lineSpacing * (lines - 1)`.
     *
     * Device-pixel frame size = effect padding on each side around the scaled
     * block, clamped to a minimum of 1 so degenerate inputs (empty lines,
     * whitespace-only text) still yield a positive frame. Empty text returns
     * `IntSize(1, 1)`.
     *
     * @param text the string to measure; may be empty or contain newlines.
     * @param options styling to measure with; effects contribute padding.
     * @return the would-be frame size — always positive.
     */
    fun measure(text: String, options: TextOptions): IntSize {
        val plan = plan(text, options) ?: return IntSize(1, 1)
        return IntSize(plan.frameWidth, plan.frameHeight)
    }

    /**
     * Renders [text] with full styling into a new immutable [PixelFrame].
     *
     * The canvas is laid out as documented on [measure]. Painting order is
     * glow, then shadow, then outline, then body — later layers overwrite
     * earlier ones where they overlap, which yields the visual stacking
     * body-over-outline-over-shadow-over-glow. Unsupported characters fall
     * back to the space glyph, so any string renders deterministically.
     *
     * Examples (default [Font5x7], no effects):
     *  * `"A"` → 5x7 frame with the `A` glyph in [color];
     *  * `"AB"` → 11x7 (`5 + letterSpacing + 5`);
     *  * `"A\nB"` → 5x15 (`7 + lineSpacing + 7`);
     *  * `"Hi"` with `outlineColor = 0xFF000000` → 13x9 frame: one pixel of
     *    padding on every side plus the 11x7 body.
     *
     * @param text the string to render; may be empty or contain newlines.
     * @param color ARGB color painted for every lit glyph pixel; the alpha
     *   channel is written as given (callers normally pass opaque colors).
     * @param options full styling; see [TextOptions] for the unit systems.
     * @return the rendered frame; a fully transparent 1x1 frame for empty
     *   text.
     */
    fun render(text: String, color: Int, options: TextOptions): PixelFrame {
        val layout = plan(text, options) ?: return PixelFrame.blank(1, 1)
        val canvas = MutablePixelFrame(layout.frameWidth, layout.frameHeight)
        // The body mask covers the WHOLE canvas (not just the body box), so
        // the effect dilations can grow into the padding around the glyphs.
        val mask = BooleanArray(layout.frameWidth * layout.frameHeight)
        rasterizeBody(mask, layout, options)
        paintGlow(canvas, mask, layout, options)
        paintShadow(canvas, mask, layout, options)
        paintOutline(canvas, mask, layout, options)
        paintBody(canvas, mask, layout, color)
        return canvas.toImmutable()
    }

    /**
     * Renders [text] top-to-bottom: every character occupies its own line of
     * the layout, stacked vertically with [TextOptions.lineSpacing] between
     * consecutive characters.
     *
     * *Simplification, declared up front:* glyphs are **not** rotated. A true
     * vertical script would rotate each glyph 90 degrees (a 7x5 cell becomes
     * a 5x7 cell); this renderer instead stacks the upright glyphs, which
     * keeps every font metric code path shared with [render] and matches how
     * pixel-art HUDs usually label vertical elements. Consequences:
     *
     *  * each vertical cell is `glyphWidth` wide and `glyphHeight` tall;
     *  * newline characters in [text] produce one *blank* vertical cell each
     *    (an empty line in the shared layout engine);
     *  * [TextOptions.letterSpacing] has no effect (no glyph ever has a
     *    horizontal neighbor);
     *  * [TextAlign] is effectively inert because every cell has the same
     *    width — the parameter is still honored for future fonts with
     *    variable-width cells;
     *  * outline/glow/shadow effects wrap the whole stacked column exactly
     *    as in [render].
     *
     * @param text the string to stack; may be empty or contain newlines.
     * @param color ARGB color of the glyph pixels.
     * @param options styling; `lineSpacing` acts as the vertical rhythm.
     * @return the vertical frame; a transparent 1x1 frame for empty text.
     */
    fun renderVertical(text: String, color: Int, options: TextOptions): PixelFrame {
        if (text.isEmpty()) return PixelFrame.blank(1, 1)
        val stacked = text.toCharArray().joinToString(separator = "\n")
        return render(stacked, color, options)
    }

    /**
     * Returns a copy of [options] with the largest [TextOptions.scale] that
     * still fits [text] into [maxWidth] device pixels.
     *
     * The measured width is the full [measure] width — effect padding
     * (outline, glow, shadow) counts towards the budget, which is what a
     * caller compositing the rendered frame into a fixed-width slot needs.
     * The scale is stepped down one unit at a time starting from
     * `options.scale`; it never drops below 1, so text that does not fit even
     * at 1x is returned at 1x (best effort, never an error).
     *
     * @param text the text to fit; the same string later passed to [render].
     * @param maxWidth the inclusive maximum frame width; must be `> 0`.
     * @param options the base styling; only `scale` ever changes.
     * @return a new [TextOptions] with the fitted scale.
     * @throws IllegalArgumentException when [maxWidth] is `<= 0`.
     */
    fun fitText(text: String, maxWidth: Int, options: TextOptions): TextOptions {
        require(maxWidth > 0) { "maxWidth must be > 0 (was $maxWidth)" }
        var fitted = options
        while (fitted.scale > 1 && measure(text, fitted).width > maxWidth) {
            fitted = fitted.copy(scale = fitted.scale - 1)
        }
        return fitted
    }

    /**
     * Greedy word wrapper operating on character columns.
     *
     * Rules, in order:
     *  1. explicit `\n` always breaks the line;
     *  2. empty source lines (including the blank line between `\n\n`) are
     *     preserved as empty strings in the output;
     *  3. within a line, space-separated words are packed greedily: a word is
     *     appended to the current line (joined by a single space) while the
     *     joined length stays `<= maxColumns`, otherwise the line is flushed
     *     and the word starts a new line;
     *  4. a single word longer than [maxColumns] is *hard-cut* into
     *     `maxColumns`-sized chunks; a word that divides evenly leaves no
     *     trailing stub;
     *  5. runs of multiple spaces collapse to the single joining space, and
     *     leading/trailing spaces of a line are dropped (the wrapper never
     *     emits trailing whitespace).
     *
     * Examples: `wrapLines("hello world", 5)` → `["hello", "world"]`;
     * `wrapLines("abcdefgh", 3)` → `["abc", "def", "gh"]`;
     * `wrapLines("a\n\nb", 4)` → `["a", "", "b"]`;
     * `wrapLines("", 4)` → `[""]`.
     *
     * @param text the raw text including any explicit newlines.
     * @param maxColumns maximum characters per output line; must be `>= 1`.
     * @return the wrapped lines; never empty (empty input yields `[""]`).
     * @throws IllegalArgumentException when [maxColumns] is `< 1`.
     */
    fun wrapLines(text: String, maxColumns: Int): List<String> {
        require(maxColumns >= 1) { "maxColumns must be >= 1 (was $maxColumns)" }
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            val words = paragraph.split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                out.add("")
                continue
            }
            var current = ""
            for (word in words) {
                val joined = if (current.isEmpty()) word else "$current $word"
                if (joined.length <= maxColumns) {
                    current = joined
                    continue
                }
                if (current.isNotEmpty()) out.add(current)
                var rest = word
                while (rest.length > maxColumns) {
                    out.add(rest.substring(0, maxColumns))
                    rest = rest.substring(maxColumns)
                }
                current = rest
            }
            if (current.isNotEmpty()) out.add(current)
        }
        return out
    }

    /**
     * Convenience pipeline: [wrapLines] the text to [maxColumns] characters,
     * then [render] the joined result with [options].
     *
     * The wrapped lines are re-joined with `\n` before rendering, so the
     * shared layout engine handles the line stacking and every effect. A
     * text that wraps to nothing (empty input) renders the canonical
     * transparent 1x1 frame.
     *
     * @param text the raw text including any explicit newlines.
     * @param color ARGB color of the glyph pixels.
     * @param maxColumns wrap width in characters; must be `>= 1`.
     * @param options styling applied to the wrapped layout.
     * @return the rendered, wrapped frame.
     * @throws IllegalArgumentException when [maxColumns] is `< 1`.
     */
    fun renderWrapped(text: String, color: Int, maxColumns: Int, options: TextOptions): PixelFrame {
        val lines = wrapLines(text, maxColumns)
        return render(lines.joinToString(separator = "\n"), color, options)
    }

    /**
     * Bakes [text] into a ready-to-edit single-frame, single-layer
     * [SpriteProject].
     *
     * The project dimensions equal the rendered frame dimensions, and the
     * rendered frame becomes the cel of layer `"Layer 1"` in frame 0. The
     * project palette is derived deterministically from the rendered pixels:
     * every distinct non-transparent color, in first-appearance order; if the
     * frame is entirely transparent (empty text), the palette degenerates to
     * the single [color]. The project id is `"text-" + name`, so repeated
     * calls with equal arguments produce equal projects — deterministic by
     * construction, like [TemplateLibrary.apply].
     *
     * @param text the string to bake.
     * @param color ARGB glyph color.
     * @param options styling used for [render].
     * @param name the project and layer-friendly display name.
     * @return a fresh [SpriteProject] with one layer, one frame, one cel.
     */
    fun toSpriteProject(
        text: String,
        color: Int,
        options: TextOptions,
        name: String = "text",
    ): SpriteProject {
        require(name.isNotBlank()) { "name must not be blank (was '$name')" }
        val cel = render(text, color, options)
        val palette = paletteFor(cel, color)
        return SpriteFactory.create(
            name = name,
            width = cel.width,
            height = cel.height,
            palette = palette,
            id = "text-" + name,
        ).withActiveCel(cel)
    }

    // ------------------------------------------------------------- geometry

    /**
     * One resolved line of the layout: the fallback-resolved glyph columns
     * plus the line's advance width in font units.
     */
    private class GlyphLine(val glyphs: List<IntArray>, val widthUnits: Int)

    /**
     * Fully resolved geometry for one render: the glyph lines, the scaled
     * body size and the effect padding around it. [frameWidth] and
     * [frameHeight] are the final canvas dimensions; the body's top-left
     * corner sits at ([padLeft], [padTop]) inside that canvas.
     */
    private class Plan(
        val lines: List<GlyphLine>,
        val bodyWidth: Int,
        val bodyHeight: Int,
        val padLeft: Int,
        val padTop: Int,
        val padRight: Int,
        val padBottom: Int,
    ) {
        val frameWidth: Int get() = maxOf(1, padLeft + bodyWidth + padRight)
        val frameHeight: Int get() = maxOf(1, padTop + bodyHeight + padBottom)
    }

    /**
     * Resolves [text] into a [Plan], or `null` for empty text (the caller
     * substitutes the canonical blank 1x1 frame).
     *
     * Glyph fallback happens here once: any character without a glyph in the
     * options' font becomes the font's space glyph.
     */
    private fun plan(text: String, options: TextOptions): Plan? {
        if (text.isEmpty()) return null
        val font = options.font
        val glyphW = font.glyphWidth
        val spaceGlyph = font.glyph(' ') ?: IntArray(glyphW)
        val lines = text.split('\n').map { raw ->
            val glyphs = raw.map { ch -> font.glyph(ch) ?: spaceGlyph }
            GlyphLine(
                glyphs = glyphs,
                widthUnits = glyphs.size * glyphW +
                    options.letterSpacing * maxOf(0, glyphs.size - 1),
            )
        }
        val contentWidthUnits = lines.maxOf { it.widthUnits }
        val contentHeightUnits = lines.size * font.glyphHeight +
            options.lineSpacing * (lines.size - 1)
        val bodyWidth = maxOf(1, contentWidthUnits * options.scale)
        val bodyHeight = maxOf(1, contentHeightUnits * options.scale)

        val glowPad = if (options.glowColor != null) options.glowRadius else 0
        val outlinePad = if (options.outlineColor != null) options.outlineThickness else 0
        val shadowOn = options.shadowColor != null
        val shadowLeft = if (shadowOn) maxOf(0, -options.shadowOffsetX) else 0
        val shadowRight = if (shadowOn) maxOf(0, options.shadowOffsetX) else 0
        val shadowTop = if (shadowOn) maxOf(0, -options.shadowOffsetY) else 0
        val shadowBottom = if (shadowOn) maxOf(0, options.shadowOffsetY) else 0
        return Plan(
            lines = lines,
            bodyWidth = bodyWidth,
            bodyHeight = bodyHeight,
            padLeft = maxOf(glowPad, outlinePad, shadowLeft),
            padTop = maxOf(glowPad, outlinePad, shadowTop),
            padRight = maxOf(glowPad, outlinePad, shadowRight),
            padBottom = maxOf(glowPad, outlinePad, shadowBottom),
        )
    }

    // ------------------------------------------------------------ rasterizer

    /**
     * Stamps the lit glyph pixels of every line into [mask], a BooleanArray
     * covering the *whole canvas* (`frameWidth x frameHeight` device pixels)
     * with the body origin at (`padLeft`, `padTop`).
     *
     * Per line: the x origin follows [TextOptions.alignment] inside the
     * widest line (in font units, integer arithmetic — CENTER rounds the
     * slack down), the y origin is `lineIndex * (glyphHeight + lineSpacing)`.
     * Each lit font pixel expands into a `scale x scale` block. Bounds are
     * guaranteed by the layout math, so no clipping is needed here.
     */
    private fun rasterizeBody(mask: BooleanArray, plan: Plan, options: TextOptions) {
        val font = options.font
        val glyphW = font.glyphWidth
        val glyphH = font.glyphHeight
        val scale = options.scale
        val contentWidthUnits = plan.lines.maxOf { it.widthUnits }
        for ((index, line) in plan.lines.withIndex()) {
            val lineX = when (options.alignment) {
                TextAlign.LEFT -> 0
                TextAlign.CENTER -> (contentWidthUnits - line.widthUnits) / 2
                TextAlign.RIGHT -> contentWidthUnits - line.widthUnits
            }
            val lineY = index * (glyphH + options.lineSpacing)
            var penX = 0
            for (glyph in line.glyphs) {
                for (col in 0 until minOf(glyphW, glyph.size)) {
                    val bits = glyph[col]
                    if (bits == 0) continue
                    for (row in 0 until glyphH) {
                        if (bits and (1 shl row) != 0) {
                            stamp(
                                mask, plan.frameWidth,
                                plan.padLeft + (lineX + penX + col) * scale,
                                plan.padTop + (lineY + row) * scale,
                                scale,
                            )
                        }
                    }
                }
                penX += glyphW + options.letterSpacing
            }
        }
    }

    /**
     * Writes one `scale x scale` block into [mask] at the device-pixel
     * position (`xPx`, `yPx`).
     */
    private fun stamp(
        mask: BooleanArray,
        maskWidth: Int,
        xPx: Int,
        yPx: Int,
        scale: Int,
    ) {
        for (sy in 0 until scale) {
            val rowStart = (yPx + sy) * maskWidth
            for (sx in 0 until scale) {
                mask[rowStart + xPx + sx] = true
            }
        }
    }

    // --------------------------------------------------------------- effects

    /**
     * Paints the glow halo: the body mask dilated [TextOptions.glowRadius]
     * times with an 8-neighborhood, filled with the flat-alpha blend of
     * [TextOptions.glowColor] and [TextOptions.glowOpacity].
     *
     * The halo is painted first, so the shadow, outline and body layers
     * cover its core; what remains visible is the Chebyshev ring around the
     * text. Painting is skipped entirely when the glow is disabled or its
     * alpha is zero (keeping untouched pixels at the canonical transparent
     * `0x00000000`).
     */
    private fun paintGlow(canvas: MutablePixelFrame, mask: BooleanArray, plan: Plan, options: TextOptions) {
        val glowColor = options.glowColor ?: return
        val alpha = options.glowOpacity
        if (alpha <= 0) return
        val argb = (alpha shl 24) or (glowColor and 0x00FFFFFF)
        val halo = dilate(mask, plan.frameWidth, plan.frameHeight, options.glowRadius, diagonal = true)
        for (y in 0 until plan.frameHeight) {
            for (x in 0 until plan.frameWidth) {
                if (halo[y * plan.frameWidth + x]) {
                    canvas[x, y] = argb
                }
            }
        }
    }

    /**
     * Paints the drop shadow: a solid copy of the body mask displaced by
     * (`shadowOffsetX`, `shadowOffsetY`) in [shadowColor]. Drawn above the
     * glow but under the outline and body. Positive offsets throw the shadow
     * down-right (the pixel-art default); negative offsets flip it.
     */
    private fun paintShadow(canvas: MutablePixelFrame, mask: BooleanArray, plan: Plan, options: TextOptions) {
        val shadowColor = options.shadowColor ?: return
        for (y in 0 until plan.frameHeight) {
            for (x in 0 until plan.frameWidth) {
                if (mask[y * plan.frameWidth + x]) {
                    canvas[x + options.shadowOffsetX, y + options.shadowOffsetY] = shadowColor
                }
            }
        }
    }

    /**
     * Paints the outline: the body mask dilated [TextOptions.outlineThickness]
     * times with a 4-neighborhood, solid [TextOptions.outlineColor], but only
     * on cells the body itself does not occupy — the classic border ring
     * hugging the glyphs. Dilating with the 4-neighborhood (Manhattan metric)
     * keeps the outline chunky and axis-aligned, the pixel-art convention.
     */
    private fun paintOutline(canvas: MutablePixelFrame, mask: BooleanArray, plan: Plan, options: TextOptions) {
        if (options.outlineColor == null || options.outlineThickness <= 0) return
        val ring = dilate(mask, plan.frameWidth, plan.frameHeight, options.outlineThickness, diagonal = false)
        for (y in 0 until plan.frameHeight) {
            for (x in 0 until plan.frameWidth) {
                val i = y * plan.frameWidth + x
                if (ring[i] && !mask[i]) {
                    canvas[x, y] = options.outlineColor
                }
            }
        }
    }

    /**
     * Paints the body itself: every set mask cell in [color], the top-most
     * layer of the stack.
     */
    private fun paintBody(canvas: MutablePixelFrame, mask: BooleanArray, plan: Plan, color: Int) {
        for (y in 0 until plan.frameHeight) {
            for (x in 0 until plan.frameWidth) {
                if (mask[y * plan.frameWidth + x]) {
                    canvas[x, y] = color
                }
            }
        }
    }

    /**
     * Morphological dilation of a Boolean raster: [times] passes, each pass
     * turning on every cell that has an on-neighbor (or is already on).
     *
     * [diagonal] selects the neighborhood: `true` is the 8-neighborhood used
     * by the glow (fills corners, reaches `(times, times)` diagonally), and
     * `false` is the 4-neighborhood used by the outline (axis-aligned ring).
     * After `k` passes the shape grows by exactly `k` cells along every axis
     * direction, which is what the padding math in [plan] relies on.
     *
     * `times <= 0` returns [source] unchanged (callers treat it read-only).
     * The mask passed in by [render] is canvas-sized — body box plus effect
     * padding — so dilated cells grow into the padding instead of being
     * clipped at the glyph bounding box, and the padding math in [plan]
     * always makes room for the full dilation extent.
     */
    private fun dilate(
        source: BooleanArray,
        width: Int,
        height: Int,
        times: Int,
        diagonal: Boolean,
    ): BooleanArray {
        if (times <= 0) return source
        val neighborDx: IntArray
        val neighborDy: IntArray
        if (diagonal) {
            neighborDx = intArrayOf(-1, 0, 1, -1, 1, -1, 0, 1)
            neighborDy = intArrayOf(-1, -1, -1, 0, 0, 1, 1, 1)
        } else {
            neighborDx = intArrayOf(0, -1, 1, 0)
            neighborDy = intArrayOf(-1, 0, 0, 1)
        }
        var current = source
        for (pass in 0 until times) {
            val next = BooleanArray(current.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (current[y * width + x]) {
                        next[y * width + x] = true
                        continue
                    }
                    for (n in neighborDx.indices) {
                        val nx = x + neighborDx[n]
                        val ny = y + neighborDy[n]
                        if (nx >= 0 && nx < width && ny >= 0 && ny < height &&
                            current[ny * width + nx]
                        ) {
                            next[y * width + x] = true
                            break
                        }
                    }
                }
            }
            current = next
        }
        return current
    }

    // -------------------------------------------------------------- helpers

    /**
     * Derives the project palette for [toSpriteProject]: every distinct
     * non-transparent pixel color of [cel] in first-appearance order. Falls
     * back to the single [fallbackColor] when the cel has no visible pixels,
     * because a [Palette] must never be empty.
     */
    private fun paletteFor(cel: PixelFrame, fallbackColor: Int): Palette {
        val seen = LinkedHashSet<Int>()
        for (argb in cel.pixels) {
            if (argb ushr 24 != 0) seen.add(argb)
        }
        if (seen.isEmpty()) seen.add(fallbackColor)
        return Palette(
            id = "text-colors",
            name = "Text Colors",
            source = PaletteSource.CUSTOM,
            colors = seen.toIntArray(),
        )
    }
}
