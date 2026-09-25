package com.pixellab.core.template

import com.pixellab.core.model.Frame
import com.pixellab.core.model.Layer
import com.pixellab.core.model.MutablePixelFrame
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.BuiltInPalettes

/**
 * Registry and factory of the built-in pixel-art templates.
 *
 * Every template is a [TemplateSpec]: metadata plus one deterministic painter
 * function that produces, for a given layer name and frame index, the layer's
 * pixel content. Painting is pure Kotlin over [MutablePixelFrame] — shapes
 * are composed in a boolean [Region] raster, colored directly, and never
 * touch a random number generator, so the same template id always yields
 * byte-identical art.
 *
 * [apply] assembles a spec into a [SpriteProject]: the layer stack is built
 * bottom-up in the spec's layer order, frames are numbered `0..n-1`, and a
 * cel is only stored for layers that have at least one non-transparent pixel
 * in that frame (blank layers stay cel-less and simply render nothing).
 *
 * Public surface used by [TemplateEngine]: [ids], [apply]; [spec] is internal
 * for metadata queries within the module.
 */
object TemplateLibrary {

    // ------------------------------------------------------------------ spec

    /**
     * One registered template: descriptive metadata plus the painter that
     * rasterizes a single layer of a single frame.
     *
     * @property id stable machine id, e.g. `"clawd-pet"`.
     * @property name human-readable name used as the project name.
     * @property width canvas width in pixels.
     * @property height canvas height in pixels.
     * @property description short summary of the sprite and its animation.
     * @property frameCount number of animation frames, `>= 1`.
     * @property layerNames layer stack bottom-up (index 0 renders first).
     * @property defaultPalette palette attached to projects built from this
     *   spec when [apply] receives no override.
     * @property painter maps `(layerName, frameIndex)` to that layer's frame;
     *   may return an all-transparent frame for empty layers.
     */
    internal class TemplateSpec(
        val id: String,
        val name: String,
        val width: Int,
        val height: Int,
        val description: String,
        val frameCount: Int,
        val layerNames: List<String>,
        val defaultPalette: Palette,
        val painter: (layer: String, frameIndex: Int) -> PixelFrame,
    ) {
        init {
            require(width > 0 && height > 0) { "Template '$id' size must be positive" }
            require(frameCount >= 1) { "Template '$id' needs at least one frame" }
            require(layerNames.isNotEmpty()) { "Template '$id' needs at least one layer" }
            require(layerNames.toSet().size == layerNames.size) {
                "Template '$id' has duplicate layer names: $layerNames"
            }
        }
    }

    /** Fluent spec constructor used by the registry below. */
    private class TemplateBuilder(private val templateId: String) {
        var name: String = templateId
        var width: Int = 16
        var height: Int = 16
        var description: String = ""
        var frames: Int = 1
        var layers: List<String> = listOf("layer")
        var palette: Palette = BuiltInPalettes.PICO8
        private var paintFn: ((String, Int) -> PixelFrame)? = null

        /** Installs the per-layer painter: `painter { layer, frame -> ... }`. */
        fun painter(fn: (layer: String, frameIndex: Int) -> PixelFrame) {
            paintFn = fn
        }

        fun build(): TemplateSpec {
            val fn = paintFn
                ?: throw IllegalStateException("Template '$templateId' is missing a painter")
            return TemplateSpec(
                id = templateId, name = name, width = width, height = height,
                description = description, frameCount = frames, layerNames = layers,
                defaultPalette = palette, painter = fn,
            )
        }
    }

    /** Registry entry helper: `template("id") { ... }`. */
    private fun template(id: String, init: TemplateBuilder.() -> Unit): TemplateSpec =
        TemplateBuilder(id).apply(init).build()

    // ------------------------------------------------------- template colors

    /** Clawd warm ramp (same colors as [BuiltInPalettes.CLAWD]). */
    private val CLAWD_BODY = 0xFFFFA300.toInt()       // orange round body
    private val CLAWD_DARK = 0xFFAB5236.toInt()       // brown outline / ears
    private val CLAWD_EYE = 0xFF1D2B53.toInt()        // navy eyes and mouth
    private val CLAWD_WHITE = 0xFFFFF1E8.toInt()      // cream eye glint
    private val CLAWD_BLUSH = 0xFFFF77A8.toInt()      // full blush (happy)
    private val CLAWD_BLUSH_SOFT = 0x80FF77A8.toInt() // 50% blush (idle)

    /** Kimi cool ramp (same colors as [BuiltInPalettes.KIMI]). */
    private val ORB_BODY = 0xFF29ADFF.toInt()         // orb fill
    private val ORB_RIM = 0xFF1D2B53.toInt()          // orb outline
    private val ORB_LIGHT = 0xFFDAF3FF.toInt()        // highlight glint
    private val ORB_SHADOW = 0xFF1B1B2E.toInt()       // ground shadow

    /** Grass block faces. */
    private val GRASS_1 = 0xFFA8E05F.toInt()
    private val GRASS_2 = 0xFF79C33E.toInt()
    private val GRASS_3 = 0xFF548C34.toInt()
    private val GRASS_4 = 0xFF2F5D25.toInt()
    private val DIRT_1 = 0xFFAD8051.toInt()
    private val DIRT_2 = 0xFF8A5F3B.toInt()
    private val DIRT_3 = 0xFF5E3F26.toInt()

    /** Default palette for the grass block template (not a named built-in). */
    private val GRASS_BLOCK_PALETTE = Palette(
        id = "grass-block", name = "Grass Block", source = PaletteSource.CUSTOM,
        colors = intArrayOf(
            GRASS_1, GRASS_2, GRASS_3, GRASS_4, DIRT_1, DIRT_2, DIRT_3,
        ),
    )

    /** Heart colors (PICO-8 reds). */
    private val HEART_RED = 0xFFFF004D.toInt()
    private val HEART_DARK = 0xFF7E2553.toInt()
    private val HEART_LIGHT = 0xFFFFF1E8.toInt()

    /** Sword colors (PICO-8 metal and gold). */
    private val SWORD_BLADE = 0xFFC2C3C7.toInt()
    private val SWORD_RIDGE = 0xFFFFF1E8.toInt()
    private val SWORD_GUARD = 0xFFFFA300.toInt()
    private val SWORD_HANDLE = 0xFFAB5236.toInt()
    private val SWORD_POMMEL = 0xFFFFA300.toInt()

    /** Coin colors (PICO-8 golds). */
    private val COIN_GOLD = 0xFFFFA300.toInt()
    private val COIN_EDGE = 0xFFAB5236.toInt()
    private val COIN_BRIGHT = 0xFFFFEC27.toInt()

    // -------------------------------------------------------------- registry

    /** All built-in templates in stable, documented order. */
    private val REGISTRY: List<TemplateSpec> = listOf(
        template("clawd-pet") {
            name = "Clawd Pet"
            description = "Round Clawd-orange pet: idle breathing (frames 0-1), " +
                "blink (2-3) and a beaming, blush-deepened smile (4-5)."
            frames = 6
            layers = listOf("body", "face", "blush")
            palette = BuiltInPalettes.CLAWD
            painter { layer, frame -> clawdPetLayer(layer, frame) }
        },
        template("kimi-orb") {
            name = "Kimi Orb"
            description = "Kimi-blue light orb bobbing a pixel above its " +
                "ground shadow, highlight glint sliding with the float."
            frames = 4
            layers = listOf("shadow", "orb")
            palette = BuiltInPalettes.KIMI
            painter { layer, frame -> kimiOrbLayer(layer, frame) }
        },
        template("minecraft-block") {
            name = "Grass Block"
            description = "Isometric grass block: four-step green top, " +
                "three-step dirt sides and a jagged grass lip overhang."
            frames = 1
            layers = listOf("top", "sides")
            palette = GRASS_BLOCK_PALETTE
            painter { layer, frame -> grassBlockLayer(layer, frame) }
        },
        template("heart-pixel") {
            name = "Pixel Heart"
            width = 8
            height = 8
            description = "Classic 8x8 heart; frame 1 grows a one-pixel " +
                "pulse outline around the same beat."
            frames = 2
            layers = listOf("heart", "pulse")
            palette = BuiltInPalettes.PICO8
            painter { layer, frame -> heartLayer(layer, frame) }
        },
        template("sword-pixel") {
            name = "Pixel Sword"
            description = "Diagonal pixel sword: light-gray blade with a " +
                "white center ridge, gold crossguard, brown grip, gold pommel."
            frames = 1
            layers = listOf("blade", "grip")
            palette = BuiltInPalettes.PICO8
            painter { layer, frame -> swordLayer(layer, frame) }
        },
        template("coin-spin") {
            name = "Spin Coin"
            width = 8
            height = 8
            description = "Four-frame spinning coin: face with cent mark, " +
                "narrow edge, 2px rim bar and lit back face."
            frames = 4
            layers = listOf("coin", "mark")
            palette = BuiltInPalettes.PICO8
            painter { layer, frame -> coinLayer(layer, frame) }
        },
    )

    // ------------------------------------------------------------ public API

    /** Template ids in registration order. */
    fun ids(): List<String> = REGISTRY.map { it.id }

    /** Looks up a spec by id; null when unknown. */
    internal fun spec(id: String): TemplateSpec? = REGISTRY.firstOrNull { it.id == id }

    /**
     * Builds a fresh [SpriteProject] from the template [templateId].
     *
     * @param templateId one of [ids].
     * @param palette optional palette override for the project; `null` keeps
     *   the template's default. Painted pixels are never recolored.
     * @throws IllegalArgumentException when [templateId] is unknown.
     */
    fun apply(templateId: String, palette: Palette? = null): SpriteProject {
        val found = spec(templateId) ?: throw IllegalArgumentException(
            "Unknown template id '$templateId'. Available templates: " +
                ids().joinToString(", "),
        )
        return buildProject(found, palette ?: found.defaultPalette)
    }

    /**
     * Assembles the project: layers bottom-up with ids `0..n-1`, frames
     * `0..m-1`, cels only for layers with visible content, active layer on
     * top, and a deterministic project id `tpl-<templateId>`.
     */
    private fun buildProject(spec: TemplateSpec, palette: Palette): SpriteProject {
        val layers = spec.layerNames.mapIndexed { index, layerName ->
            Layer(id = index, name = layerName)
        }
        val frames = (0 until spec.frameCount).map { frameIndex ->
            val cels = HashMap<Int, PixelFrame>()
            for ((index, layerName) in spec.layerNames.withIndex()) {
                val cel = spec.painter(layerName, frameIndex)
                if (cel.pixels.any { it ushr 24 != 0 }) cels[index] = cel
            }
            Frame(id = frameIndex, cels = cels)
        }
        return SpriteFactory.create(
            name = spec.name,
            width = spec.width,
            height = spec.height,
            palette = palette,
            id = "tpl-" + spec.id,
        )
            .withLayers(layers)
            .withActiveLayer(layers.last().id)
            .withFrames(frames)
    }

    // ------------------------------------------------- painter: clawd-pet ----

    /**
     * 16x16 Clawd pet. Odd frames shift the whole pet one pixel down
     * (breathing); frames 2-3 blink; frames 4-5 beam — happy arc eyes, open
     * mouth and a doubled blush.
     */
    private fun clawdPetLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        val dy = if (frameIndex % 2 == 1) 1 else 0
        val blink = frameIndex == 2 || frameIndex == 3
        val happy = frameIndex >= 4
        when (layer) {
            "body" -> {
                val body = Region(16, 16)
                body.addRow(3 + dy, 5, 10)
                body.addRow(4 + dy, 4, 11)
                body.addRow(5 + dy, 3, 12)
                body.addRow(6 + dy, 3, 12)
                for (y in 7 + dy..11 + dy) body.addRow(y, 2, 13)
                body.addRow(12 + dy, 3, 12)
                body.addRow(13 + dy, 3, 12)
                body.addRow(14 + dy, 4, 11)
                // triangle ears on top of the head
                body.add(4, 1 + dy)
                body.add(4, 2 + dy)
                body.add(5, 2 + dy)
                body.add(11, 1 + dy)
                body.add(11, 2 + dy)
                body.add(10, 2 + dy)
                paintRegion(f, body, CLAWD_BODY, CLAWD_DARK)
            }
            "face" -> {
                when {
                    blink -> for ((x, y) in listOf(5 to 8, 6 to 8, 9 to 8, 10 to 8)) {
                        f[x, y + dy] = CLAWD_EYE
                    }
                    happy -> for ((x, y) in listOf(4 to 8, 5 to 7, 6 to 8, 9 to 8, 10 to 7, 11 to 8)) {
                        f[x, y + dy] = CLAWD_EYE
                    }
                    else -> {
                        f[5, 7 + dy] = CLAWD_WHITE
                        f[6, 7 + dy] = CLAWD_EYE
                        f[5, 8 + dy] = CLAWD_EYE
                        f[6, 8 + dy] = CLAWD_EYE
                        f[9, 7 + dy] = CLAWD_WHITE
                        f[10, 7 + dy] = CLAWD_EYE
                        f[9, 8 + dy] = CLAWD_EYE
                        f[10, 8 + dy] = CLAWD_EYE
                    }
                }
                if (happy) {
                    for ((x, y) in listOf(7 to 10, 8 to 10, 6 to 11, 7 to 11, 8 to 11, 9 to 11)) {
                        f[x, y + dy] = CLAWD_EYE
                    }
                } else {
                    f[7, 11 + dy] = CLAWD_EYE
                    f[8, 11 + dy] = CLAWD_EYE
                }
            }
            "blush" -> {
                if (happy) {
                    for (x in 3..5) for (y in 9..10) f[x, y + dy] = CLAWD_BLUSH
                    for (x in 10..12) for (y in 9..10) f[x, y + dy] = CLAWD_BLUSH
                } else {
                    f[3, 10 + dy] = CLAWD_BLUSH_SOFT
                    f[4, 10 + dy] = CLAWD_BLUSH_SOFT
                    f[11, 10 + dy] = CLAWD_BLUSH_SOFT
                    f[12, 10 + dy] = CLAWD_BLUSH_SOFT
                }
            }
        }
    }

    // -------------------------------------------------- painter: kimi-orb ----

    /** Per-frame vertical float offsets of the orb (rest, up, rest, down). */
    private val ORB_FLOAT = intArrayOf(0, -1, 0, 1)

    /** Per-frame horizontal drift of the highlight glint. */
    private val ORB_GLINT = intArrayOf(0, 1, 0, -1)

    /**
     * 16x16 Kimi orb: navy-rimmed blue disc (9px wide, 10px tall) with a
     * light glint, floating over a dark ground ellipse that shrinks as the
     * orb rises and spreads as it sinks.
     */
    private fun kimiOrbLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        val dy = ORB_FLOAT[frameIndex]
        when (layer) {
            "shadow" -> {
                val shadow = Region(16, 16)
                shadow.addEllipse(8.0, 14.0, 3.2 + dy, 1.2)
                paintRegion(f, shadow, ORB_SHADOW, ORB_SHADOW)
            }
            "orb" -> {
                val cy = 7.0 + dy
                val orb = Region(16, 16)
                orb.addEllipse(8.0, cy, 4.6, 4.6)
                paintRegion(f, orb, ORB_BODY, ORB_RIM)
                val glintX = 6 + ORB_GLINT[frameIndex]
                f[glintX, (cy - 3).toInt()] = ORB_LIGHT
                f[glintX, (cy - 2).toInt()] = ORB_LIGHT
            }
        }
    }

    // ------------------------------------------- painter: minecraft-block ----

    /**
     * 16x16 isometric grass block. The top face is a 14x7 diamond of four
     * noise-speckled greens; the side faces are two parallelograms of
     * three-step dirt, capped by a grass lip whose third row drips into
     * the dirt deterministically.
     */
    private fun grassBlockLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        when (layer) {
            "top" -> {
                val top = Region(16, 16)
                top.addRow(0, 7, 8)
                top.addRow(1, 5, 10)
                top.addRow(2, 3, 12)
                top.addRow(3, 1, 14)
                top.addRow(4, 3, 12)
                top.addRow(5, 5, 10)
                top.addRow(6, 7, 8)
                for (y in 0..15) for (x in 0..15) {
                    if (top.contains(x, y)) {
                        f[x, y] = if (top.isEdge(x, y)) GRASS_4 else grassTopShade(noise2(x, y, 1))
                    }
                }
            }
            "sides" -> {
                val left = Region(16, 16)
                left.addRow(4, 1, 2)
                left.addRow(5, 1, 4)
                left.addRow(6, 1, 6)
                for (y in 7..15) left.addRow(y, 1, 7)
                val right = Region(16, 16)
                right.addRow(4, 13, 14)
                right.addRow(5, 11, 14)
                right.addRow(6, 9, 14)
                for (y in 7..15) right.addRow(y, 8, 14)
                for (y in 0..15) for (x in 0..15) {
                    val inLeft = left.contains(x, y)
                    val inRight = right.contains(x, y)
                    if (!inLeft && !inRight) continue
                    val depth = y - sideEdgeRow(x)
                    f[x, y] = when {
                        depth <= 1 -> GRASS_4
                        depth == 2 -> GRASS_3
                        depth == 3 && noise2(x, y, 7) < 34 -> GRASS_3
                        inLeft -> dirtShade(noise2(x, y, 3))
                        else -> dirtShadeDark(noise2(x, y, 5))
                    }
                }
            }
        }
    }

    /** Row of the top-face boundary above side column [x] (x in 1..14). */
    private fun sideEdgeRow(x: Int): Int = when {
        x <= 2 || x >= 13 -> 3
        x <= 4 || x >= 11 -> 4
        x <= 6 || x >= 9 -> 5
        else -> 6
    }

    /** Maps a 0..99 noise sample to one of the four grass greens. */
    private fun grassTopShade(n: Int): Int = when {
        n < 15 -> GRASS_1
        n < 60 -> GRASS_2
        n < 85 -> GRASS_3
        else -> GRASS_4
    }

    /** Maps a 0..99 noise sample to one of the three dirt browns (lit side). */
    private fun dirtShade(n: Int): Int = when {
        n < 12 -> DIRT_1
        n < 72 -> DIRT_2
        else -> DIRT_3
    }

    /** Maps a 0..99 noise sample to dirt browns (shadowed right face). */
    private fun dirtShadeDark(n: Int): Int = if (n < 10) DIRT_2 else DIRT_3

    // ---------------------------------------------- painter: heart-pixel ----

    /** 8x8 heart mask: two lobes, full waist, tapered tip. */
    private fun heartRegion(): Region {
        val heart = Region(8, 8)
        heart.addRow(1, 1, 2)
        heart.addRow(1, 5, 6)
        heart.addRow(2, 0, 7)
        heart.addRow(3, 0, 7)
        heart.addRow(4, 1, 6)
        heart.addRow(5, 2, 5)
        heart.addRow(6, 3, 4)
        return heart
    }

    /**
     * 8x8 heart. The `heart` layer is identical in both frames; the `pulse`
     * layer carries the one-pixel dilation ring that enlarges the sprite on
     * frame 1 (and stays empty — cel-less — on frame 0).
     */
    private fun heartLayer(layer: String, frameIndex: Int): PixelFrame = paint(8, 8) { f ->
        val heart = heartRegion()
        when (layer) {
            "heart" -> {
                paintRegion(f, heart, HEART_RED, HEART_DARK)
                f[1, 2] = HEART_LIGHT
                f[2, 2] = HEART_LIGHT
            }
            "pulse" -> {
                if (frameIndex == 1) {
                    val ring = heart.dilated()
                    for (y in 0..7) for (x in 0..7) {
                        if (ring.contains(x, y) && !heart.contains(x, y)) f[x, y] = HEART_DARK
                    }
                }
            }
        }
    }

    // ---------------------------------------------- painter: sword-pixel ----

    /**
     * 16x16 diagonal sword. Blade rows march one pixel left per row down
     * from the tip at (13, 2); each row is gray-white-gray (2px light blade
     * around the white ridge). Gold crossguard on row 10, brown 2px grip,
     * gold pommel at the bottom-left end.
     */
    private fun swordLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        when (layer) {
            "blade" -> {
                f[12, 2] = SWORD_BLADE
                f[13, 2] = SWORD_RIDGE
                for (y in 3..9) {
                    val center = 12 - (y - 3)
                    f[center - 1, y] = SWORD_BLADE
                    f[center, y] = SWORD_RIDGE
                    f[center + 1, y] = SWORD_BLADE
                }
            }
            "grip" -> {
                for (x in 2..8) f[x, 10] = SWORD_GUARD
                for (i in 0..2) {
                    val center = 4 - i
                    f[center, 11 + i] = SWORD_HANDLE
                    f[center + 1, 11 + i] = SWORD_HANDLE
                }
                f[1, 14] = SWORD_POMMEL
                f[2, 14] = SWORD_POMMEL
            }
        }
    }

    // ----------------------------------------------- painter: coin-spin -----

    /**
     * 8x8 spinning coin. Frame 0: full disc (the cent mark lives on the
     * separate `mark` layer); frames 1 and 3: 6px-wide, 8px-tall edge
     * ellipse; frame 2: 2px rim bar; frame 3 adds a two-pixel highlight.
     */
    private fun coinLayer(layer: String, frameIndex: Int): PixelFrame = paint(8, 8) { f ->
        when (layer) {
            "coin" -> when (frameIndex) {
                0 -> {
                    val disc = Region(8, 8)
                    disc.addRow(0, 2, 5)
                    disc.addRow(1, 1, 6)
                    for (y in 2..5) disc.addRow(y, 0, 7)
                    disc.addRow(6, 1, 6)
                    disc.addRow(7, 2, 5)
                    paintRegion(f, disc, COIN_GOLD, COIN_EDGE)
                }
                1, 3 -> {
                    val edge = Region(8, 8)
                    edge.addEllipse(4.0, 4.0, 2.8, 3.9)
                    paintRegion(f, edge, COIN_GOLD, COIN_EDGE)
                    if (frameIndex == 3) {
                        f[2, 2] = COIN_BRIGHT
                        f[2, 3] = COIN_BRIGHT
                    }
                }
                else -> {
                    for (y in 0..7) {
                        val capped = y == 0 || y == 7
                        f[3, y] = if (capped) COIN_EDGE else COIN_GOLD
                        f[4, y] = COIN_EDGE
                    }
                }
            }
            "mark" -> if (frameIndex == 0) {
                for ((x, y) in listOf(
                    3 to 2, 4 to 2, 5 to 3, 5 to 4,
                    3 to 5, 4 to 5, 2 to 3, 2 to 4,
                )) {
                    f[x, y] = COIN_EDGE
                }
            }
        }
    }

    // ------------------------------------------------------------- helpers --

    /** Runs [body] on a fresh workspace and snapshots it to a frame. */
    private inline fun paint(
        width: Int,
        height: Int,
        body: (MutablePixelFrame) -> Unit,
    ): PixelFrame {
        val workspace = MutablePixelFrame(width, height)
        body(workspace)
        return workspace.toImmutable()
    }

    /** Paints [region] into [target]: edge cells [outline], interior [fill]. */
    private fun paintRegion(target: MutablePixelFrame, region: Region, fill: Int, outline: Int) {
        for (y in 0 until region.height) for (x in 0 until region.width) {
            if (region.contains(x, y)) {
                target[x, y] = if (region.isEdge(x, y)) outline else fill
            }
        }
    }

    /**
     * Deterministic 2D hash in `0..99` used for texture speckles. Pure
     * wrapping integer arithmetic (no RNG): identical on every platform and
     * on every run.
     */
    private fun noise2(x: Int, y: Int, seed: Int): Int {
        var h = x * 0x1F123BB5 + y * 0x27D4EB2D + seed * 0x165667B1
        h = h xor (h ushr 15)
        h *= 0x2545F491
        h = h xor (h ushr 13)
        return (h and 0x7FFFFFFF) % 100
    }

    /**
     * Mutable boolean raster for composing sprite silhouettes before they
     * are painted. [isEdge] reports the 4-neighbor boundary band that
     * pixel-art outlines need; [dilated] grows the shape by one pixel for
     * pulse rings.
     */
    private class Region(val width: Int, val height: Int) {

        private val cells = BooleanArray(width * height)

        /** Sets the cell at (x, y); out-of-bounds coordinates are ignored. */
        fun add(x: Int, y: Int) {
            if (x in 0 until width && y in 0 until height) cells[y * width + x] = true
        }

        /** Sets the horizontal run [x0]..[x1] on row [y]. */
        fun addRow(y: Int, x0: Int, x1: Int) {
            for (x in x0..x1) add(x, y)
        }

        /** Unions the axis-aligned ellipse; pixel centers sit at `+0.5`. */
        fun addEllipse(cx: Double, cy: Double, rx: Double, ry: Double) {
            for (y in 0 until height) for (x in 0 until width) {
                val dx = (x + 0.5 - cx) / rx
                val dy = (y + 0.5 - cy) / ry
                if (dx * dx + dy * dy <= 1.0) add(x, y)
            }
        }

        /** True when (x, y) is set. */
        fun contains(x: Int, y: Int): Boolean =
            x in 0 until width && y in 0 until height && cells[y * width + x]

        /** True when (x, y) is set but at least one 4-neighbor is not. */
        fun isEdge(x: Int, y: Int): Boolean =
            contains(x, y) && (!contains(x - 1, y) || !contains(x + 1, y) ||
                !contains(x, y - 1) || !contains(x, y + 1))

        /** 8-neighborhood dilation, clipped to the raster bounds. */
        fun dilated(): Region {
            val out = Region(width, height)
            for (y in 0 until height) for (x in 0 until width) {
                if (contains(x, y)) {
                    for (dy in -1..1) for (dx in -1..1) out.add(x + dx, y + dy)
                }
            }
            return out
        }
    }
}
