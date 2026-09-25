package com.pixellab.core

import com.pixellab.core.animation.AnimationEngine
import com.pixellab.core.convert.ImageConverter
import com.pixellab.core.engine.PixelEngine
import com.pixellab.core.export.Exporter
import com.pixellab.core.template.TemplateEngine

/**
 * Pixel Lab library entry point.
 *
 * A [PixelLab] instance owns configuration and shared services; it is cheap to
 * create and may be held for the lifetime of a host application. Every
 * sub-engine is stateless or keyed by project id, so one instance safely
 * serves any number of projects (serialize access per project: undo history is
 * instance state inside [PixelEngine]).
 *
 * ```
 * val lab = PixelLab.create()
 * val project = SpriteFactory.create("hero", 32, 32, BuiltInPalettes.PICO8)
 * val drawn = lab.engine.drawPixels(project, listOf(PixelPoint(1, 1)), 0xFF000000)
 * ```
 */
class PixelLab private constructor(
    /** Configuration snapshot for this instance. */
    val config: PixelLabConfig,
    /** Drawing operations, undo/redo, layer and canvas management. */
    val engine: PixelEngine,
    /** Frame management, playback metadata, onion skin, motion effects. */
    val animation: AnimationEngine,
    /** Text/shape template generation. */
    val template: TemplateEngine,
    /** Raster-to-pixel-art conversion pipeline. */
    val converter: ImageConverter,
    /** PNG / GIF / APNG / spritesheet / Codex-pet export. */
    val exporter: Exporter,
) {

    companion object {
        /** Creates a lab with [config]. */
        fun create(config: PixelLabConfig = PixelLabConfig.default()): PixelLab {
            val engine = PixelEngine(config)
            return PixelLab(
                config = config,
                engine = engine,
                animation = AnimationEngine(config),
                template = TemplateEngine(config),
                converter = ImageConverter(config),
                exporter = Exporter(config),
            )
        }
    }
}
