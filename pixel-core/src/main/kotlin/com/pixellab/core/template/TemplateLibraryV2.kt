package com.pixellab.core.template

import com.pixellab.core.model.Frame
import com.pixellab.core.model.Layer
import com.pixellab.core.model.MutablePixelFrame
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.BuiltInPalettes
import kotlin.math.abs

/**
 * Second-generation built-in template library: eight new animated sprites
 * that extend the six registered in [TemplateLibrary] without touching it.
 *
 * Design contract, mirroring the V1 library so both feel native:
 *
 *  * every template is a private [Spec] — metadata plus one deterministic
 *    painter function mapping `(layerName, frameIndex)` to that layer's
 *    pixel content;
 *  * painting is pure Kotlin over [MutablePixelFrame]; shapes are composed
 *    from explicit row spans and coordinate lists, and nothing ever consults
 *    a random number generator, so the same template id yields
 *    byte-identical art on every platform and every run;
 *  * [build] assembles a [SpriteProject] bottom-up from the spec's layer
 *    list; frames are numbered `0..n-1`; a cel is stored only for layers
 *    with at least one non-transparent pixel in that frame (blank layers
 *    stay cel-less and render nothing);
 *  * the project id is `"tpl2-" + templateId` (distinct from V1's
 *    `"tpl-"` prefix), so repeated builds of the same template produce
 *    equal projects — deterministic by construction;
 *  * every template has **at least two frames** (enforced by the spec's
 *    `init`), because a V2 template exists to animate.
 *
 * Palette policy: each template picks the [BuiltInPalettes] entry that best
 * matches its subject; the palette is attached to the built project (and
 * overridable via [build]) but never re-colors painted pixels. *Declared
 * exception to the "no magic colors" rule:* the concrete per-template color
 * constants below are content data of the artwork itself, exactly as the V1
 * painters hard-code their ramps — the palette is a drawing aid, not the
 * source of template colors.
 *
 * Public surface: [all] for metadata and [build] for instantiation. This
 * object deliberately does not re-register or shadow any V1 template id.
 */
object TemplateLibraryV2 {

    // ------------------------------------------------------------------ spec

    /**
     * Metadata about one registered V2 template.
     *
     * Every field matches the project produced by [build] for the same id:
     * [width] x [height] canvas, [frames] animation frames, [layers]-deep
     * layer stack, and [paletteId] as the id of the default palette.
     *
     * @property id stable machine id passed to [build], e.g. `"mushroom"`.
     * @property name human-readable display name of the template.
     * @property description short summary of the sprite and its animation.
     * @property width frame width in pixels.
     * @property height frame height in pixels.
     * @property frames number of animation frames, always `>= 2`.
     * @property layers number of paint layers (bottom-up stack).
     * @property paletteId id of the [BuiltInPalettes] entry attached to
     *   built projects when [build] receives no override.
     */
    data class TemplateEntry(
        val id: String,
        val name: String,
        val description: String,
        val width: Int,
        val height: Int,
        val frames: Int,
        val layers: Int,
        val paletteId: String,
    )

    /**
     * One registered template: everything [TemplateEntry] exposes plus the
     * default palette object and the painter lambda.
     */
    private class Spec(
        val id: String,
        val name: String,
        val description: String,
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val layerNames: List<String>,
        val defaultPalette: Palette,
        val painter: (layer: String, frameIndex: Int) -> PixelFrame,
    ) {
        init {
            require(width > 0 && height > 0) { "Template '$id' size must be positive" }
            require(frameCount >= 2) { "V2 template '$id' needs at least 2 frames" }
            require(layerNames.isNotEmpty()) { "Template '$id' needs at least one layer" }
            require(layerNames.toSet().size == layerNames.size) {
                "Template '$id' has duplicate layer names: $layerNames"
            }
        }
    }

    /**
     * Fluent spec constructor used by the registry below; mirrors the V1
     * [TemplateLibrary] builder so both registries read the same way.
     */
    private class SpecBuilder(private val templateId: String) {
        var name: String = templateId
        var width: Int = 16
        var height: Int = 16
        var description: String = ""
        var frames: Int = 2
        var layers: List<String> = listOf("layer")
        var palette: Palette = BuiltInPalettes.PICO8
        private var paintFn: ((layer: String, frameIndex: Int) -> PixelFrame)? = null

        /** Installs the per-layer painter: `painter { layer, frame -> ... }`. */
        fun painter(fn: (layer: String, frameIndex: Int) -> PixelFrame) {
            paintFn = fn
        }

        fun build(): Spec {
            val fn = paintFn
                ?: throw IllegalStateException("Template '$templateId' is missing a painter")
            return Spec(
                id = templateId, name = name, width = width, height = height,
                description = description, frameCount = frames, layerNames = layers,
                defaultPalette = palette, painter = fn,
            )
        }
    }

    /** Registry entry helper: `spec("id") { ... }`. */
    private fun spec(id: String, init: SpecBuilder.() -> Unit): Spec =
        SpecBuilder(id).apply(init).build()

    // ------------------------------------------------------- template colors
    // Declared exception (see object KDoc): template colors are content data
    // of the artwork, not configuration. Grouped per template.

    /** Mushroom — PICO-8 reds, creams and browns. */
    private val MUSH_CAP = 0xFFFF004D.toInt()        // red cap fill
    private val MUSH_CAP_DARK = 0xFF7E2553.toInt()   // maroon cap border
    private val MUSH_SPOT = 0xFFFFF1E8.toInt()       // cream spots
    private val MUSH_GLINT = 0xFFFFEC27.toInt()      // flickering cap highlight
    private val MUSH_STEM = 0xFFFFCCAA.toInt()       // stem fill
    private val MUSH_STEM_DARK = 0xFFAB5236.toInt()  // stem shading

    /** Slime — PICO-8 greens with navy eyes. */
    private val SLIME_BODY = 0xFF00E436.toInt()      // goo fill
    private val SLIME_EDGE = 0xFF008751.toInt()      // dark green rim
    private val SLIME_EYE = 0xFF1D2B53.toInt()       // navy eyes and mouth
    private val SLIME_SHEEN = 0xFFFFF1E8.toInt()     // white gel sheen

    /** Ghost — PICO-8 whites and navy. */
    private val GHOST_BODY = 0xFFFFF1E8.toInt()      // pale body fill
    private val GHOST_EDGE = 0xFFC2C3C7.toInt()      // soft gray border
    private val GHOST_FACE = 0xFF1D2B53.toInt()      // navy eyes and mouth

    /** Explosion — PICO-8 fire ramp plus fading embers. */
    private val BOOM_CORE = 0xFFFFF1E8.toInt()       // white-hot center
    private val BOOM_HOT = 0xFFFFEC27.toInt()        // yellow blast body
    private val BOOM_WARM = 0xFFFFA300.toInt()       // orange body / ring
    private val BOOM_RED = 0xFFFF004D.toInt()        // red outer tips
    private val BOOM_EMBER = 0xFF7E2553.toInt()      // dark red ember (dissipating)
    private val BOOM_SMOKE = 0xFF5F574F.toInt()      // gray dust

    /** Star spin — PICO-8 golds. */
    private val STAR_CORE = 0xFFFFF1E8.toInt()       // white core
    private val STAR_ARM = 0xFFFFEC27.toInt()        // yellow arms
    private val STAR_TIP = 0xFFFFA300.toInt()        // orange leading tips

    /** Shield — Kimi cool blues with gold furniture. */
    private val SHIELD_FACE = 0xFF29ADFF.toInt()     // upper field blue
    private val SHIELD_FACE_LOW = 0xFF83769C.toInt() // lower field shadow blue
    private val SHIELD_EDGE = 0xFF1D2B53.toInt()     // navy border
    private val SHIELD_BOSS = 0xFFFFA300.toInt()     // gold central boss
    private val SHIELD_SHEEN = 0xFFDAF3FF.toInt()    // icy highlight sweep

    /** Potion — PICO-8 glass, cork and bubblegum liquid. */
    private val POTION_GLASS = 0xFFC2C3C7.toInt()    // flask walls
    private val POTION_GLINT = 0xFFFFF1E8.toInt()    // glass highlight
    private val POTION_CORK = 0xFFAB5236.toInt()     // cork stopper
    private val POTION_LIQUID = 0xFFFF77A8.toInt()   // pink liquid
    private val POTION_SURFACE = 0xFFFFCCAA.toInt()  // lighter liquid crest
    private val POTION_BUBBLE = 0xFFFFF1E8.toInt()   // rising bubble

    /** Chest — Endesga 32 woods, oranges and cream for the treasure glow. */
    private val CHEST_WOOD = 0xFFE4A672.toInt()      // light plank fill
    private val CHEST_WOOD_MID = 0xFFB86F50.toInt()  // mid plank shade
    private val CHEST_WOOD_DARK = 0xFF743F39.toInt() // dark trim and corners
    private val CHEST_GOLD = 0xFFFB922B.toInt()      // gold banding and lock
    private val CHEST_GOLD_HOT = 0xFFFE8501.toInt()  // bright light cone
    private val CHEST_SPARK = 0xFFF6D7BD.toInt()     // cream sparkles
    private val CHEST_RAY_SOFT = 0x80FE8501.toInt()  // half-alpha ray edges

    // -------------------------------------------------------------- registry

    /** All V2 templates in stable, documented registration order. */
    private val REGISTRY: List<Spec> = listOf(
        spec("mushroom") {
            name = "Mushroom"
            description = "Classic 16x16 mushroom: red spotted cap over a cream " +
                "stem. Frame 1 flickers a yellow highlight across the cap dome."
            frames = 2
            layers = listOf("stem", "cap")
            palette = BuiltInPalettes.PICO8
            painter { layer, frame -> mushroomLayer(layer, frame) }
        },
        spec("slime") {
            name = "Slime"
            description = "Bouncy 16x16 slime looping squash-normal-squash-normal. " +
                "Squashed frames widen by two pixels, sink the eyes one row and " +
                "slide the gel sheen down with the body."
            frames = 4
            layers = listOf("slime")
            palette = BuiltInPalettes.PICO8
            painter { layer, frame -> slimeLayer(layer, frame) }
        },
        spec("ghost") {
            name = "Ghost"
            description = "Pale 16x16 ghost: the wavy skirt spikes alternate " +
                "between three and two tips while both eyes slide one pixel " +
                "left, then one pixel right."
            frames = 2
            layers = listOf("body", "face")
            palette = BuiltInPalettes.PICO8
            painter { layer, frame -> ghostLayer(layer, frame) }
        },
        spec("explosion") {
            name = "Explosion"
            description = "Four-stage 16x16 blast: small star, big star, intact " +
                "ring, then a ragged dissipating ring with drifting dust."
            frames = 4
            layers = listOf("fx")
            palette = BuiltInPalettes.PICO8
            painter { layer, frame -> explosionLayer(layer, frame) }
        },
        spec("star-spin") {
            name = "Spin Star"
            description = "8x8 four-point star with two long and two short arms, " +
                "rotated 90 degrees per frame: the asymmetric arms make all four " +
                "frames of the spin readable."
            width = 8
            height = 8
            frames = 4
            layers = listOf("star")
            palette = BuiltInPalettes.PICO8
            painter { layer, frame -> starSpinLayer(layer, frame) }
        },
        spec("shield") {
            name = "Shield"
            description = "16x16 heater shield in Kimi blues with a gold boss. " +
                "The icy sheen sweep rotates around the border ring: upper-left " +
                "edge on frame 0, upper-right on frame 1."
            frames = 2
            layers = listOf("shield", "sheen")
            palette = BuiltInPalettes.KIMI
            painter { layer, frame -> shieldLayer(layer, frame) }
        },
        spec("potion") {
            name = "Potion"
            description = "8x8 pink potion in a glass flask. The liquid surface " +
                "sloshes left and right by one pixel while a bubble rises to " +
                "the crest."
            width = 8
            height = 8
            frames = 2
            layers = listOf("bottle", "liquid")
            palette = BuiltInPalettes.PICO8
            painter { layer, frame -> potionLayer(layer, frame) }
        },
        spec("chest-open") {
            name = "Open Chest"
            description = "16x16 treasure chest. Closed: gold-banded lid and lock. " +
                "Open: the lid tips back and a cream-and-orange light cone rays " +
                "out of the seam onto the gold rim."
            frames = 2
            layers = listOf("base", "lid", "glow")
            palette = BuiltInPalettes.ENDESGA32
            painter { layer, frame -> chestLayer(layer, frame) }
        },
    )

    // ------------------------------------------------------------ public API

    /**
     * Metadata for every registered V2 template, in registration order:
     * `mushroom`, `slime`, `ghost`, `explosion`, `star-spin`, `shield`,
     * `potion`, `chest-open`.
     *
     * The returned entries always agree with what [build] produces for the
     * same id (dimensions, frame count, layer count, default palette).
     */
    fun all(): List<TemplateEntry> = REGISTRY.map {
        TemplateEntry(
            id = it.id,
            name = it.name,
            description = it.description,
            width = it.width,
            height = it.height,
            frames = it.frameCount,
            layers = it.layerNames.size,
            paletteId = it.defaultPalette.id,
        )
    }

    /**
     * Builds a fresh, editable [SpriteProject] from the V2 template [id].
     *
     * The layer stack is built bottom-up in the template's layer order with
     * ids `0..n-1`, the active layer is the top-most one, frames are numbered
     * `0..m-1` with the active frame at index 0, and cels are stored only for
     * layers that have visible content in a given frame. The project id is
     * `"tpl2-" + id`, so repeated builds of the same template (with equal
     * palette arguments) produce equal projects.
     *
     * @param id one of the ids exposed by [all], e.g. `"chest-open"`.
     * @param palette optional override for the project's palette; `null`
     *   keeps the template's default. The override changes the project
     *   palette only — already painted pixels are never re-mapped.
     * @throws IllegalArgumentException when [id] is unknown.
     */
    fun build(id: String, palette: Palette? = null): SpriteProject {
        val found = REGISTRY.firstOrNull { it.id == id } ?: throw IllegalArgumentException(
            "Unknown template id '$id'. Available V2 templates: " +
                REGISTRY.joinToString(", ") { it.id },
        )
        return buildProject(found, palette ?: found.defaultPalette)
    }

    /**
     * Assembles the project from a spec: layers bottom-up, frames with
     * content-conditional cels, deterministic id `tpl2-<templateId>`.
     */
    private fun buildProject(spec: Spec, palette: Palette): SpriteProject {
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
            id = "tpl2-" + spec.id,
        )
            .withLayers(layers)
            .withActiveLayer(layers.last().id)
            .withFrames(frames)
    }

    // ------------------------------------------------- painter: mushroom ----

    /**
     * 16x16 mushroom. The `stem` layer is constant: a 4px column flaring to
     * 6px at the ground with shaded edges. The `cap` layer paints the
     * 7-row dome with three cream spots; frame 1 adds three yellow glint
     * pixels on the upper-left of the dome — the "highlight flicker".
     */
    private fun mushroomLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        when (layer) {
            "stem" -> {
                // row 8 sits under the cap lip, so it is fully shaded
                for (x in 6..9) f[x, 8] = MUSH_STEM_DARK
                for (y in 9..13) {
                    for (x in 6..9) {
                        f[x, y] = if (x == 6 || x == 9) MUSH_STEM_DARK else MUSH_STEM
                    }
                }
                // flared foot
                for (x in 5..10) f[x, 14] = if (x == 5 || x == 10) MUSH_STEM_DARK else MUSH_STEM
            }
            "cap" -> {
                val cap = spans(
                    1 to 6..9, 2 to 4..11, 3 to 3..12,
                    4 to 2..13, 5 to 2..13, 6 to 2..13, 7 to 3..12,
                )
                fillSpans(f, cap, MUSH_CAP, MUSH_CAP_DARK)
                // cream spots: one pair on the crown, two 2x2 dots on the sides
                for ((x, y) in listOf(
                    6 to 2, 7 to 2,
                    4 to 4, 5 to 4, 4 to 5, 5 to 5,
                    10 to 5, 11 to 5, 10 to 6, 11 to 6,
                )) {
                    f[x, y] = MUSH_SPOT
                }
                if (frameIndex == 1) {
                    // highlight flicker: a short L-shaped glint on the dome
                    f[5, 2] = MUSH_GLINT
                    f[5, 3] = MUSH_GLINT
                    f[12, 4] = MUSH_GLINT
                }
            }
        }
    }

    // --------------------------------------------------- painter: slime -----

    /**
     * 16x16 slime on a single layer. Frames 0 and 2 are squashed (7 rows
     * tall, 12px wide at the base); frames 1 and 3 are normal (9 rows tall,
     * 10px base). Squashing sinks the eyes and mouth one row, widens the
     * silhouette by two pixels and moves the sheen down with the dome.
     */
    private fun slimeLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        if (layer != "slime") return@paint
        val squashed = frameIndex == 0 || frameIndex == 2
        val bodySpans = if (squashed) {
            spans(
                7 to 4..11, 8 to 3..12,
                9 to 2..13, 10 to 2..13, 11 to 2..13, 12 to 2..13, 13 to 2..13,
            )
        } else {
            spans(
                5 to 6..9, 6 to 5..10, 7 to 4..11,
                8 to 3..12, 9 to 3..12, 10 to 3..12, 11 to 3..12, 12 to 3..12, 13 to 3..12,
            )
        }
        fillSpans(f, bodySpans, SLIME_BODY, SLIME_EDGE)
        // the whole dome rim reads darker, like stretched gel skin
        val dome = bodySpans.first()
        for (x in dome.x0..dome.x1) f[x, dome.y] = SLIME_EDGE

        val eyeY = if (squashed) 10 else 8
        val mouthY = if (squashed) 12 else 10
        for (eyeX in listOf(5, 9)) {
            for (dy in 0..1) {
                for (dx in 0..1) f[eyeX + dx, eyeY + dy] = SLIME_EYE
            }
        }
        f[7, mouthY] = SLIME_EYE
        f[8, mouthY] = SLIME_EYE
        // gel sheen rides the dome slope
        if (squashed) {
            f[4, 8] = SLIME_SHEEN
            f[5, 8] = SLIME_SHEEN
        } else {
            f[6, 6] = SLIME_SHEEN
            f[6, 7] = SLIME_SHEEN
        }
    }

    // --------------------------------------------------- painter: ghost -----

    /**
     * 16x16 ghost. The `body` layer is the dome (rows 3..12) plus the wavy
     * skirt on row 13: frame 0 hangs three spikes (x 3-4, 7-8, 11-12), frame
     * 1 hangs two offset spikes (x 5-6, 9-10). The `face` layer shifts both
     * 2x2 navy eyes one pixel right on frame 1; the small mouth stays fixed.
     */
    private fun ghostLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        when (layer) {
            "body" -> {
                val bodySpans = mutableListOf<Span>()
                bodySpans.addAll(spans(3 to 5..10, 4 to 4..11))
                for (y in 5..12) bodySpans.add(Span(y, 3, 12))
                if (frameIndex == 0) {
                    bodySpans.addAll(spans(13 to 3..4, 13 to 7..8, 13 to 11..12))
                } else {
                    bodySpans.addAll(spans(13 to 5..6, 13 to 9..10))
                }
                fillSpans(f, bodySpans, GHOST_BODY, GHOST_EDGE)
            }
            "face" -> {
                val shift = if (frameIndex == 0) 0 else 1
                for (eyeX in listOf(5 + shift, 9 + shift)) {
                    for (dy in 0..1) {
                        for (dx in 0..1) f[eyeX + dx, 7 + dy] = GHOST_FACE
                    }
                }
                f[7, 10] = GHOST_FACE
                f[8, 10] = GHOST_FACE
            }
        }
    }

    // ----------------------------------------------- painter: explosion -----

    /**
     * 16x16 explosion on a single `fx` layer, centered on (8, 8):
     *
     *  * frame 0 — small 4-radius star (white heart, yellow body, orange
     *    axis-arm tips);
     *  * frame 1 — big 7-radius star (white heart, yellow, orange, red tips);
     *  * frame 2 — intact ring, radius ~5..6.7, orange with a red outer rim
     *    and four yellow compass highlights;
     *  * frame 3 — dissipating: the same ring with every third pixel knocked
     *    out, colors cooled to dark red/gray embers, plus four dust specks
     *    drifting in the blown-out middle.
     */
    private fun explosionLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        if (layer != "fx") return@paint
        when (frameIndex) {
            0 -> paintStar(f, radius = 4, big = false)
            1 -> paintStar(f, radius = 7, big = true)
            else -> paintRing(f, frameIndex)
        }
    }

    /**
     * One explosion star: the Manhattan diamond of [radius] unioned with
     * 2px-thick axis arms of the same radius — the classic four-point blast.
     * Colors ramp outwards by `|dx| + |dy|`: white heart, then yellow, then
     * orange (and red tips for the big star).
     */
    private fun paintStar(f: MutablePixelFrame, radius: Int, big: Boolean) {
        for (y in 0..15) {
            for (x in 0..15) {
                val ax = abs(x - 8)
                val ay = abs(y - 8)
                val on = (ax + ay <= radius) || (ax <= 1 && ay <= radius) || (ay <= 1 && ax <= radius)
                if (!on) continue
                val dist = ax + ay
                f[x, y] = when {
                    dist <= 2 -> BOOM_CORE
                    big && dist <= 4 -> BOOM_HOT
                    big && dist <= 6 -> BOOM_WARM
                    big -> BOOM_RED
                    dist <= 3 -> BOOM_HOT
                    else -> BOOM_WARM
                }
            }
        }
    }

    /**
     * One explosion ring stage around the center (7.5, 7.5) — integer math
     * with `dx = 2x - 15` keeps everything exact. The ring is the annulus
     * `100 <= dx*dx + dy*dy <= 180`. Frame 2 paints it intact (orange body,
     * red outer rim, four yellow compass dots); frame 3 knocks out every
     * pixel with `(x + 2y) % 3 == 0`, cools the survivors to ember colors
     * via [dissipateColor] and drops four gray dust specks inside the hole.
     */
    private fun paintRing(f: MutablePixelFrame, frameIndex: Int) {
        for (y in 0..15) {
            for (x in 0..15) {
                val dx = 2 * x - 15
                val dy = 2 * y - 15
                val d = dx * dx + dy * dy
                if (d < 100 || d > 180) continue
                if (frameIndex == 3 && (x + 2 * y) % 3 == 0) continue
                f[x, y] = if (frameIndex == 3) dissipateColor(x, y) else ringColor(x, y)
            }
        }
        if (frameIndex == 2) {
            for ((x, y) in listOf(8 to 2, 8 to 13, 2 to 8, 13 to 8)) f[x, y] = BOOM_HOT
        } else {
            for ((x, y) in listOf(6 to 6, 9 to 6, 6 to 9, 9 to 9)) f[x, y] = BOOM_SMOKE
        }
    }

    /** Ring body color: orange inside, red on the outer rim. */
    private fun ringColor(x: Int, y: Int): Int {
        val dx = 2 * x - 15
        val dy = 2 * y - 15
        return if (dx * dx + dy * dy > 160) BOOM_RED else BOOM_WARM
    }

    /** Dissipating ring color: mostly dark red embers with gray patches. */
    private fun dissipateColor(x: Int, y: Int): Int =
        if ((5 * x + 3 * y) % 7 == 0) BOOM_SMOKE else BOOM_EMBER

    // ---------------------------------------------- painter: star-spin ------

    /**
     * 8x8 spinning star on a single `star` layer. The base pose is
     * hand-drawn (see [starSpinBase]) and each subsequent frame is the
     * previous frame rotated 90 degrees clockwise via
     * [PixelFrame.rotated90Cw]. Because the base has two long arms (up,
     * right) and two short arms (down, left), all four rotation steps are
     * distinct and the loop closes only after a full 360 degrees.
     */
    private fun starSpinLayer(layer: String, frameIndex: Int): PixelFrame {
        if (layer != "star") return paint(8, 8) { }
        var frame = starSpinBase()
        repeat(frameIndex) { frame = frame.rotated90Cw() }
        return frame
    }

    /**
     * The frame-0 pose: a white 2x2 core, yellow arms (3px up, 3px right,
     * 1px down, 1px left) and four orange leading tips biased clockwise —
     * the bias is what makes the rotation read as a spin direction.
     */
    private fun starSpinBase(): PixelFrame = paint(8, 8) { f ->
        f[3, 3] = STAR_CORE
        f[4, 3] = STAR_CORE
        f[3, 4] = STAR_CORE
        f[4, 4] = STAR_CORE
        f[3, 2] = STAR_ARM
        f[3, 1] = STAR_ARM
        f[3, 0] = STAR_ARM
        f[5, 3] = STAR_ARM
        f[6, 3] = STAR_ARM
        f[7, 3] = STAR_ARM
        f[4, 5] = STAR_ARM
        f[2, 4] = STAR_ARM
        f[4, 0] = STAR_TIP
        f[7, 2] = STAR_TIP
        f[5, 5] = STAR_TIP
        f[2, 3] = STAR_TIP
    }

    // ------------------------------------------------- painter: shield ------

    /**
     * 16x16 heater shield. The `shield` layer is constant: a navy-bordered
     * blue field that tapers to a point over rows 2..14, a lighter upper
     * field, a shadowed lower field from row 9 down, and a gold boss with an
     * icy glint at the center. The `sheen` layer carries the rotating ring
     * highlight: frame 0 sweeps the upper-left border, frame 1 the
     * upper-right — mirrored runs of [SHIELD_SHEEN] along the rim.
     */
    private fun shieldLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        when (layer) {
            "shield" -> {
                val field = spans(
                    2 to 3..12, 3 to 2..13, 4 to 2..13, 5 to 2..13, 6 to 2..13,
                    7 to 2..13, 8 to 2..13, 9 to 3..12, 10 to 3..12, 11 to 4..11,
                    12 to 5..10, 13 to 6..9, 14 to 7..8,
                )
                for (s in field) {
                    for (x in s.x0..s.x1) {
                        val edge = x == s.x0 || x == s.x1
                        f[x, s.y] = when {
                            edge -> SHIELD_EDGE
                            s.y >= 9 -> SHIELD_FACE_LOW
                            else -> SHIELD_FACE
                        }
                    }
                }
                // gold boss with an icy glint pixel
                for (x in 7..8) {
                    for (y in 6..7) f[x, y] = SHIELD_BOSS
                }
                f[7, 6] = SHIELD_SHEEN
            }
            "sheen" -> {
                val cells = if (frameIndex == 0) {
                    listOf(
                        3 to 2, 4 to 2, 5 to 2,
                        2 to 3, 2 to 4, 2 to 5, 2 to 6, 2 to 7, 2 to 8,
                    )
                } else {
                    listOf(
                        12 to 2, 11 to 2, 10 to 2,
                        13 to 3, 13 to 4, 13 to 5, 13 to 6, 13 to 7, 13 to 8,
                    )
                }
                for ((x, y) in cells) f[x, y] = SHIELD_SHEEN
            }
        }
    }

    // ------------------------------------------------- painter: potion ------

    /**
     * 8x8 potion. The `bottle` layer is constant: cork stopper, 2px neck
     * walls, shoulder, side walls and bottom rail in glass gray, with a
     * white highlight on the left wall. The `liquid` layer sloshes: frame 0
     * raises the left half of the surface (crest over x 2..3), frame 1 the
     * right half (crest over x 4..5) — a 1px tilt; a white bubble rises
     * from (5, 6) on frame 0 to the surface crest at (5, 5) on frame 1.
     */
    private fun potionLayer(layer: String, frameIndex: Int): PixelFrame = paint(8, 8) { f ->
        when (layer) {
            "bottle" -> {
                f[3, 0] = POTION_CORK
                f[4, 0] = POTION_CORK
                f[3, 1] = POTION_GLASS
                f[4, 1] = POTION_GLASS
                f[3, 2] = POTION_GLASS
                f[4, 2] = POTION_GLASS
                f[2, 3] = POTION_GLASS
                f[5, 3] = POTION_GLASS
                for (y in 4..6) {
                    f[1, y] = POTION_GLASS
                    f[6, y] = POTION_GLASS
                }
                for (x in 2..5) f[x, 7] = POTION_GLASS
                f[1, 4] = POTION_GLINT
            }
            "liquid" -> {
                val tiltLeft = frameIndex == 0
                // interior cells are x 2..5 on rows 4..6 (rows above are air)
                for (x in 2..5) {
                    val surfaceY = if (tiltLeft) {
                        if (x <= 3) 5 else 6
                    } else {
                        if (x >= 4) 5 else 6
                    }
                    for (y in surfaceY..6) f[x, y] = POTION_LIQUID
                    f[x, surfaceY] = POTION_SURFACE
                }
                if (tiltLeft) f[5, 6] = POTION_BUBBLE else f[5, 5] = POTION_BUBBLE
            }
        }
    }

    // --------------------------------------------- painter: chest-open ------

    /**
     * 16x16 treasure chest across three layers.
     *
     *  * `base` — constant: gold rim on row 8, plank body rows 9..13 with
     *    darker plank seams at x 5-6 and 10-11, dark outline columns, bottom
     *    rail on row 14, and a gold lock plate with a dark keyhole slot.
     *  * `lid` — closed (frame 0): a 4-row dome with a gold band and dark
     *    corners sitting flush on the base; open (frame 1): the lid tips
     *    back into rows 1..3, narrower and fully banded.
     *  * `glow` — empty when closed (the layer stays cel-less); when open, a
     *    cream-to-orange light cone rays out of the seam over rows 4..7,
     *    with two sparkles and a half-alpha spill tinting the gold rim.
     */
    private fun chestLayer(layer: String, frameIndex: Int): PixelFrame = paint(16, 16) { f ->
        val open = frameIndex == 1
        when (layer) {
            "base" -> {
                for (x in 2..13) f[x, 8] = CHEST_GOLD
                f[2, 8] = CHEST_WOOD_DARK
                f[13, 8] = CHEST_WOOD_DARK
                for (y in 9..13) {
                    for (x in 2..13) {
                        f[x, y] = when {
                            x == 2 || x == 13 -> CHEST_WOOD_DARK
                            x in 5..6 || x in 10..11 -> CHEST_WOOD_MID
                            else -> CHEST_WOOD
                        }
                    }
                }
                for (x in 2..13) f[x, 14] = CHEST_WOOD_DARK
                // lock plate with a dark keyhole slot
                for (x in 7..8) {
                    for (y in 9..10) f[x, y] = CHEST_GOLD
                }
                f[7, 10] = CHEST_WOOD_DARK
                f[8, 10] = CHEST_WOOD_DARK
            }
            "lid" -> {
                if (!open) {
                    for (x in 3..12) f[x, 4] = CHEST_WOOD_MID
                    f[3, 4] = CHEST_WOOD_DARK
                    f[12, 4] = CHEST_WOOD_DARK
                    for (x in 2..13) f[x, 5] = if (x == 2 || x == 13) CHEST_WOOD_DARK else CHEST_WOOD
                    for (x in 2..13) f[x, 6] = if (x == 2 || x == 13) CHEST_WOOD_DARK else CHEST_GOLD
                    for (x in 2..13) f[x, 7] = if (x == 2 || x == 13) CHEST_WOOD_DARK else CHEST_WOOD
                } else {
                    for (x in 4..11) f[x, 1] = CHEST_WOOD_MID
                    f[4, 1] = CHEST_WOOD_DARK
                    f[11, 1] = CHEST_WOOD_DARK
                    for (x in 3..12) f[x, 2] = if (x == 3 || x == 12) CHEST_WOOD_DARK else CHEST_WOOD
                    for (x in 3..12) f[x, 3] = CHEST_GOLD
                    f[3, 3] = CHEST_WOOD_DARK
                    f[12, 3] = CHEST_WOOD_DARK
                }
            }
            "glow" -> {
                if (open) {
                    // light cone bursting out of the lid seam
                    for (x in 6..9) f[x, 4] = CHEST_SPARK
                    for (x in 5..10) f[x, 5] = CHEST_GOLD_HOT
                    for (x in 4..11) f[x, 6] = CHEST_GOLD_HOT
                    for (x in 3..12) f[x, 7] = CHEST_GOLD_HOT
                    f[3, 7] = CHEST_RAY_SOFT
                    f[12, 7] = CHEST_RAY_SOFT
                    // sparkles riding the cone
                    f[9, 5] = CHEST_SPARK
                    f[5, 6] = CHEST_SPARK
                    // half-alpha spill tinting the gold rim below
                    for (x in 4..11) f[x, 8] = CHEST_RAY_SOFT
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

    /** A horizontal run of cells on one row: [x0]..[x1] inclusive. */
    private class Span(val y: Int, val x0: Int, val x1: Int)

    /** Builds spans from `(y, xRange)` pairs. */
    private fun spans(vararg rows: Pair<Int, IntRange>): List<Span> =
        rows.map { Span(it.first, it.second.first, it.second.last) }

    /**
     * Paints each span into [target]: [fill] on the interior cells and
     * [edge] on both end cells — the two-tone look pixel-art silhouettes
     * need. Spans are painted in list order, so later spans may overwrite
     * earlier ones where they overlap.
     */
    private fun fillSpans(target: MutablePixelFrame, spans: List<Span>, fill: Int, edge: Int) {
        for (s in spans) {
            for (x in s.x0..s.x1) {
                target[x, s.y] = if (x == s.x0 || x == s.x1) edge else fill
            }
        }
    }
}
