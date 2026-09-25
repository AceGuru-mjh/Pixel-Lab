package com.pixellab.core.pipeline

import com.pixellab.core.PixelErrorKind
import com.pixellab.core.PixelResult
import com.pixellab.core.convert.Ditherer
import com.pixellab.core.convert.Quantizer
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.palette.BuiltInPalettes
import com.pixellab.core.palette.LabColor
import com.pixellab.core.tools.OutlineShading
import com.pixellab.core.tools.TransformOps

/**
 * Executes a [Recipe] over a list of [PixelFrame]s — the bridge between the
 * declarative recipe vocabulary ([RecipeStep]) and the library's existing
 * pixel operations.
 *
 * The runner is **deterministic**: two runs of the same recipe over equal
 * frames produce byte-identical output (the quantizer's k-means seed and
 * every dithering kernel are fixed; no wall-clock, no platform state).
 * Frames are processed as a *batch* wherever that matters:
 *
 *  * [RecipeStep.QuantizeStep] extracts one palette shared by all frames —
 *    animations never split their colors across per-frame palettes;
 *  * [RecipeStep.TrimStep] crops all frames by the same union bounding box
 *    so frame registration survives the crop.
 *
 * Steps apply strictly in recipe order. An empty step list (or a null
 * palette map) is the identity: the input frame instances are returned
 * unchanged. The input list is never mutated.
 *
 * Failures are reported through [PixelResult]:
 *  * [PixelErrorKind.NOT_FOUND] — a dither/palette-map step references an
 *    unknown built-in palette id;
 *  * [PixelErrorKind.INVALID_INPUT] — a step rejected the runtime input
 *    (e.g. a scale factor that overflows the raster). Structural recipe
 *    problems are caught earlier by [RecipeParser]/`require`, so these are
 *    rare, but the runner still refuses to throw across the API boundary.
 */
object PipelineRunner {

    /** Opaque threshold shared with the convert pipeline. */
    private const val OPAQUE_MIN: Int = 0x80

    /**
     * Runs [recipe] over [frames] and returns the transformed list (same
     * order, same count) or a structured failure.
     *
     * @param recipe the validated recipe (see [RecipeParser.parse]).
     * @param frames input frames; an empty list is legal and yields an
     *   empty success.
     * @return [PixelResult.Ok] with one output frame per input frame, or
     *   [PixelResult.Err] carrying the failing step index and reason.
     */
    fun run(recipe: Recipe, frames: List<PixelFrame>): PixelResult<List<PixelFrame>> {
        if (recipe.steps.isEmpty()) return PixelResult.ok(frames)
        var current = frames
        for ((index, step) in recipe.steps.withIndex()) {
            try {
                current = applyStep(step, current)
            } catch (e: PaletteNotFoundException) {
                return PixelResult.err(PixelErrorKind.NOT_FOUND, e.message ?: "unknown palette", e)
            } catch (e: IllegalArgumentException) {
                return PixelResult.err(
                    PixelErrorKind.INVALID_INPUT,
                    "step ${index + 1} (${step.javaClass.simpleName}) rejected the input: ${e.message}",
                    e,
                )
            } catch (e: IllegalStateException) {
                return PixelResult.err(
                    PixelErrorKind.CONVERT_FAILED,
                    "step ${index + 1} (${step.javaClass.simpleName}) failed: ${e.message}",
                    e,
                )
            }
        }
        return PixelResult.ok(current)
    }

    /** Dispatches one step over the whole frame list. */
    private fun applyStep(step: RecipeStep, frames: List<PixelFrame>): List<PixelFrame> = when (step) {
        is RecipeStep.ScaleStep -> frames.map { TransformOps.scaleNearest(it, step.factor) }
        is RecipeStep.QuantizeStep -> quantize(frames, step)
        is RecipeStep.DitherStep -> dither(frames, step)
        is RecipeStep.PaletteMapStep -> paletteMap(frames, step)
        is RecipeStep.OutlineStep ->
            frames.map { OutlineShading.outline(it, step.color, step.mode, step.connectivity) }
        is RecipeStep.TrimStep -> trim(frames)
        is RecipeStep.PosterizeStep -> frames.map { posterize(it, step.levels) }
        is RecipeStep.BgRemoveStep -> frames.map { removeBackground(it, step.tolerance) }
    }

    // ------------------------------------------------------------------
    // Step implementations
    // ------------------------------------------------------------------

    /**
     * Quantization over the *concatenated* pixel payload of all frames, so
     * one palette serves the whole batch; the per-pixel mappings are split
     * back per frame afterwards. Transparent pixels pass through (the
     * quantizer contract) and opaque pixels keep their original alpha while
     * picking up the quantized RGB.
     */
    private fun quantize(frames: List<PixelFrame>, step: RecipeStep.QuantizeStep): List<PixelFrame> {
        if (frames.isEmpty()) return frames
        val total = frames.sumOf { it.pixels.size }
        if (total == 0) return frames
        val all = IntArray(total)
        var offset = 0
        for (frame in frames) {
            System.arraycopy(frame.pixels, 0, all, offset, frame.pixels.size)
            offset += frame.pixels.size
        }
        val result = Quantizer.quantize(all, step.maxColors, step.algorithm)
        val out = ArrayList<PixelFrame>(frames.size)
        var cursor = 0
        for (frame in frames) {
            val size = frame.pixels.size
            val mapped = result.mappedPixels.copyOfRange(cursor, cursor + size)
            cursor += size
            out.add(PixelFrame.of(frame.width, frame.height, mapped))
        }
        return out
    }

    /**
     * Dithering per frame (error diffusion never crosses frame boundaries).
     * The target palette comes from the step's explicit id or — when null —
     * from the frames' own distinct opaque colors in ascending order, which
     * gives diffusion a stable ramp to snap onto. An unknown id fails with
     * [PixelErrorKind.NOT_FOUND]; a fully transparent batch passes through
     * (the ditherer copies input when the palette is empty).
     */
    private fun dither(frames: List<PixelFrame>, step: RecipeStep.DitherStep): List<PixelFrame> {
        if (frames.isEmpty()) return frames
        val palette: IntArray = if (step.paletteId != null) {
            val resolved = BuiltInPalettes.byId(step.paletteId)
                ?: return failNotFound("dither", step.paletteId)
            resolved.colors.copyOf()
        } else {
            distinctOpaqueColors(frames)
        }
        if (palette.isEmpty()) return frames.map { it } // nothing to diffuse onto
        val strength = step.strength ?: 1f
        return frames.map { frame ->
            val dithered = Ditherer.dither(frame.pixels, frame.width, frame.height, palette, step.algorithm, strength)
            PixelFrame.of(frame.width, frame.height, dithered)
        }
    }

    /**
     * Palette remap: every opaque pixel (alpha `>= 0x80`) snaps to the
     * nearest color of the built-in palette in CIELAB distance; its alpha is
     * preserved. Semi-transparent and fully transparent pixels pass through.
     * A null palette id (or a `"palette": null` recipe entry) is an explicit
     * no-op.
     */
    private fun paletteMap(frames: List<PixelFrame>, step: RecipeStep.PaletteMapStep): List<PixelFrame> {
        if (step.paletteId == null || frames.isEmpty()) return frames
        val palette = BuiltInPalettes.byId(step.paletteId)
            ?: return failNotFound("palette-map", step.paletteId)
        val colors = palette.colors
        val labColors = Array(colors.size) { LabColor.fromArgb(colors[it]) }
        return frames.map { frame ->
            val out = frame.copyPixels()
            for (i in out.indices) {
                val p = out[i]
                if ((p ushr 24) < OPAQUE_MIN) continue
                out[i] = (nearestLabColor(p, colors, labColors) and 0xFFFFFF) or (p and 0xFF000000.toInt())
            }
            PixelFrame.of(frame.width, frame.height, out)
        }
    }

    /**
     * Trim: computes the union bounding box of all pixels with a non-zero
     * alpha over the *whole batch* and crops every frame to that single
     * rectangle, keeping animation frames registered. Uniform frame sizes
     * are the normal input; a batch with mixed sizes falls back to
     * per-frame boxes (documented limitation — registration is already
     * meaningless there). A fully transparent batch is returned untouched.
     */
    private fun trim(frames: List<PixelFrame>): List<PixelFrame> {
        if (frames.isEmpty()) return frames
        val uniform = frames.all { it.width == frames[0].width && it.height == frames[0].height }
        return if (uniform) {
            val box = unionBoundingBox(frames) ?: return frames
            frames.map { it.region(box[0], box[1], box[2], box[3]) }
        } else {
            frames.map { frame ->
                val box = unionBoundingBox(listOf(frame))
                if (box == null) frame else frame.region(box[0], box[1], box[2], box[3])
            }
        }
    }

    /**
     * Posterize: each RGB channel of every non-transparent pixel snaps to
     * the nearest of [levels] evenly spaced values (0 and 255 included).
     * Integer rounding: `idx = (v·(levels-1) + 127) / 255`,
     * `out = idx·255 / (levels-1)`; alpha is preserved.
     */
    private fun posterize(frame: PixelFrame, levels: Int): PixelFrame {
        val span = levels - 1
        fun level(v: Int): Int = ((v * span + 127) / 255 * 255 / span).coerceIn(0, 255)
        val out = frame.copyPixels()
        for (i in out.indices) {
            val p = out[i]
            if ((p ushr 24) == 0) continue
            val r = level((p shr 16) and 0xFF)
            val g = level((p shr 8) and 0xFF)
            val b = level(p and 0xFF)
            out[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return PixelFrame.of(frame.width, frame.height, out)
    }

    /**
     * Background removal: alpha strictly below [tolerance] becomes 0; the
     * RGB of removed pixels is zeroed too (canonical transparent pixel).
     */
    private fun removeBackground(frame: PixelFrame, tolerance: Int): PixelFrame {
        var changed = false
        val out = frame.copyPixels()
        for (i in out.indices) {
            if ((out[i] ushr 24) < tolerance) {
                out[i] = 0
                changed = true
            }
        }
        return if (changed) PixelFrame.of(frame.width, frame.height, out) else frame
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Distinct opaque colors of the batch, ascending; empty when nothing is
     * opaque. Deterministic palette derivation for dither steps without an
     * explicit palette id.
     */
    private fun distinctOpaqueColors(frames: List<PixelFrame>): IntArray {
        val seen = sortedSetOf<Int>()
        for (frame in frames) {
            for (p in frame.pixels) {
                if ((p ushr 24) >= OPAQUE_MIN) seen.add(p and 0xFFFFFF or 0xFF000000.toInt())
            }
        }
        return seen.toIntArray()
    }

    /**
     * Union bounding box `(x0, y0, w, h)` of every pixel with a non-zero
     * alpha across [frames], or null when the batch is fully transparent.
     */
    private fun unionBoundingBox(frames: List<PixelFrame>): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (frame in frames) {
            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    if ((frame.pixels[y * frame.width + x] ushr 24) != 0) {
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                    }
                }
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** Nearest palette entry in Lab distance; ties keep the lowest index. */
    private fun nearestLabColor(argb: Int, colors: IntArray, labColors: Array<LabColor.Lab>): Int {
        val lab = LabColor.fromArgb(argb)
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in colors.indices) {
            val other = labColors[i]
            val dl = lab.l - other.l
            val da = lab.a - other.a
            val db = lab.b - other.b
            val d = dl * dl + da * da + db * db
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return colors[best]
    }

    /**
     * Signals an unknown built-in palette id. Thrown from the step helpers
     * and translated by [run] into a [PixelErrorKind.NOT_FOUND] failure
     * (which is why it precedes the generic [IllegalArgumentException]
     * catch).
     */
    private fun failNotFound(stepName: String, paletteId: String): Nothing =
        throw PaletteNotFoundException("$stepName: unknown palette id '$paletteId'")

    /** Internal marker translated to a NOT_FOUND result by [run]. */
    private class PaletteNotFoundException(message: String) : IllegalArgumentException(message)
}
