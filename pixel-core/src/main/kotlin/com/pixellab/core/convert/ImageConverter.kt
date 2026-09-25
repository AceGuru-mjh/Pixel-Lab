package com.pixellab.core.convert

import com.pixellab.core.PixelErrorKind
import com.pixellab.core.PixelResult
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.palette.LabColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Opaque alpha bits (bit pattern `0xFF000000`), shared inside this file. */
private const val OPAQUE: Int = -0x1000000

/** Mask isolating the 24-bit RGB payload of a packed ARGB pixel. */
private const val RGB_MASK: Int = 0xFFFFFF

/**
 * Raster-to-pixel-art conversion pipeline.
 *
 * [convert] runs: nearest-neighbor resample (`floor(dst * src / dstCount)`)
 * -> alpha binarization (strictly below the threshold becomes fully
 * transparent, everything else fully opaque) -> palette extraction or Lab
 * nearest-neighbor mapping onto the request palette -> dithering of the
 * resampled pre-map pixels -> edge cleanup -> [PixelFrame]. All entry points
 * run on [Dispatchers.Default] and report failures through [PixelResult]
 * instead of throwing.
 *
 * Native dispatch follows [config]'s [com.pixellab.core.PixelLabConfig.preferNative]:
 * when it is true the [Quantizer]/[Ditherer] facades decide between native
 * and Kotlin; when false the pure Kotlin implementations are called
 * directly.
 */
class ImageConverter(private val config: com.pixellab.core.PixelLabConfig) {

    /** Identifier of palettes extracted by [convert]. */
    private val extractedId = "pal-extracted"

    /** Display name of palettes extracted by [convert]. */
    private val extractedName = "Extracted"

    /** Hue window (degrees, Lab hue angle) counted as blue. */
    private val blueHueMin = 200.0
    private val blueHueMax = 310.0

    /** Hue window (degrees, Lab hue angle) counted as orange. */
    private val orangeHueMin = 50.0
    private val orangeHueMax = 95.0

    /** Minimum chroma before a pixel is considered to carry a hue. */
    private val hueChromaFloor = 6.0

    /** Average chroma below this suggests the gameboy palette. */
    private val gameboyMaxChroma = 12.0

    /** Blue-hue share above this suggests the kimi palette. */
    private val kimiBlueShare = 0.5

    /** Orange-hue share above this suggests the clawd palette. */
    private val clawdOrangeShare = 0.35

    /** Average chroma above this (with enough colors) suggests pico-8. */
    private val pico8MinChroma = 30.0

    /** Unique opaque colors before the pico-8 heuristic fires. */
    private val pico8MinUnique = 12

    /**
     * Converts [request] into a pixel-art frame. Dimension failures return
     * [PixelErrorKind.INVALID_INPUT]; pipeline failures return
     * [PixelErrorKind.CONVERT_FAILED].
     */
    suspend fun convert(request: ConvertRequest): PixelResult<ConvertResult> =
        withContext(Dispatchers.Default) {
            try {
                convertInternal(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                PixelResult.err(PixelErrorKind.INVALID_INPUT, e.message ?: "invalid conversion request", e)
            } catch (e: Exception) {
                PixelResult.err(PixelErrorKind.CONVERT_FAILED, "conversion failed: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }

    /**
     * Analyzes [image] to pre-fill conversion dialogs: snapped target
     * dimensions (long edge to 16/32/64, short edge scaled to preserve the
     * aspect ratio), a suggested color count, a suggested built-in palette
     * id, mean brightness and transparency presence.
     */
    suspend fun analyze(image: ImageData): PixelResult<AnalysisResult> =
        withContext(Dispatchers.Default) {
            try {
                PixelResult.ok(analyzeInternal(image))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                PixelResult.err(PixelErrorKind.INVALID_INPUT, e.message ?: "invalid image", e)
            } catch (e: Exception) {
                PixelResult.err(PixelErrorKind.CONVERT_FAILED, "analysis failed: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }

    /**
     * Cleans up a converted frame. [RefineInstructions.snapAlphaThreshold]
     * binarizes alpha (strictly below -> 0, otherwise -> 0xFF);
     * [RefineInstructions.removeStrayPixels] merges single-pixel islands
     * into their neighborhood. [RefineInstructions.strayThreshold] is
     * reserved for future thresholds; only the 0 semantics ship.
     */
    suspend fun refine(frame: PixelFrame, instructions: RefineInstructions): PixelResult<PixelFrame> =
        withContext(Dispatchers.Default) {
            try {
                refineInternal(frame, instructions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                PixelResult.err(PixelErrorKind.INVALID_INPUT, e.message ?: "invalid frame", e)
            } catch (e: Exception) {
                PixelResult.err(PixelErrorKind.CONVERT_FAILED, "refinement failed: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }

    // ------------------------------------------------------------------
    // convert
    // ------------------------------------------------------------------

    private fun convertInternal(request: ConvertRequest): PixelResult<ConvertResult> {
        val source = request.image
        val dstW = request.targetWidth
        val dstH = request.targetHeight
        if (dstW <= 0 || dstH <= 0) {
            return PixelResult.err(PixelErrorKind.INVALID_INPUT, "target dimensions must be positive (${dstW}x${dstH})")
        }
        // 1) Nearest-neighbor resample, then alpha binarization.
        val resampled = resample(source, dstW, dstH)
        binarizeAlpha(resampled, request.alphaThreshold)
        // 2) Extract or adopt a palette; map or dither the resampled pixels.
        val outPalette: Palette
        val finalPixels: IntArray
        if (request.palette == null) {
            val quantized = quantizePixels(resampled, request.colorCount, request.algorithm)
            outPalette = Palette(
                id = extractedId,
                name = extractedName,
                colors = if (quantized.palette.isEmpty()) intArrayOf(OPAQUE) else quantized.palette,
                source = PaletteSource.EXTRACTED,
            )
            finalPixels = if (request.dither == DitherAlgorithm.NONE) {
                quantized.mappedPixels
            } else {
                ditherPixels(resampled, dstW, dstH, quantized.palette, request.dither, request.ditherIntensity)
            }
        } else {
            outPalette = request.palette
            finalPixels = ditherPixels(resampled, dstW, dstH, request.palette.colors, request.dither, request.ditherIntensity)
        }
        // 3) Edge cleanup.
        val cleaned = if (request.edgeCleanup) {
            EdgeCleaner.removeStrayPixels(EdgeCleaner.clean(finalPixels), dstW, dstH)
        } else {
            finalPixels
        }
        // 4) Frame plus summary statistics over distinct opaque RGB colors.
        var transparentPixels = 0
        val opaqueKeys = IntArray(cleaned.size)
        var opaqueCount = 0
        for (p in cleaned) {
            if ((p ushr 24) >= 0x80) opaqueKeys[opaqueCount++] = p and RGB_MASK else transparentPixels++
        }
        val distinctKeys = opaqueKeys.copyOf(opaqueCount)
        distinctKeys.sort()
        var colorsUsed = 0
        var i = 0
        while (i < distinctKeys.size) {
            colorsUsed++
            var j = i + 1
            while (j < distinctKeys.size && distinctKeys[j] == distinctKeys[i]) j++
            i = j
        }
        return PixelResult.ok(
            ConvertResult(
                frame = PixelFrame.of(dstW, dstH, cleaned),
                palette = outPalette,
                colorsUsed = colorsUsed,
                transparentPixels = transparentPixels,
                width = dstW,
                height = dstH,
            ),
        )
    }

    /** Nearest-neighbor resample: source index = `floor(dst * src / dstCount)`. */
    private fun resample(source: ImageData, dstW: Int, dstH: Int): IntArray {
        val srcW = source.width
        val srcH = source.height
        val out = IntArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = ((y.toLong() * srcH) / dstH).toInt()
            val srcRow = sy * srcW
            val dstRow = y * dstW
            for (x in 0 until dstW) {
                val sx = ((x.toLong() * srcW) / dstW).toInt()
                out[dstRow + x] = source.pixels[srcRow + sx]
            }
        }
        return out
    }

    /** Binarizes alpha in place: strictly below [threshold] -> 0, else 0xFF. */
    private fun binarizeAlpha(pixels: IntArray, threshold: Int) {
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = if ((p ushr 24) < threshold) 0 else OPAQUE or (p and RGB_MASK)
        }
    }

    /** Dispatches quantization through the facade or straight to Kotlin. */
    private fun quantizePixels(pixels: IntArray, targetColors: Int, algorithm: QuantizeAlgorithm): QuantizeResult =
        if (config.preferNative) {
            Quantizer.quantize(pixels, targetColors, algorithm)
        } else {
            KotlinQuantizer.quantize(pixels, targetColors, algorithm)
        }

    /** Dispatches dithering through the facade or straight to Kotlin. */
    private fun ditherPixels(
        pixels: IntArray, width: Int, height: Int, palette: IntArray,
        algorithm: DitherAlgorithm, intensity: Float,
    ): IntArray =
        if (config.preferNative) {
            Ditherer.dither(pixels, width, height, palette, algorithm, intensity)
        } else {
            KotlinDitherer.dither(pixels, width, height, palette, algorithm, intensity)
        }

    // ------------------------------------------------------------------
    // analyze
    // ------------------------------------------------------------------

    private fun analyzeInternal(image: ImageData): AnalysisResult {
        // Unique opaque colors and transparency presence.
        val keys = IntArray(image.pixels.size)
        var opaquePixels = 0
        var transparentPixels = 0
        var hasAlpha = false
        for (p in image.pixels) {
            val alpha = p ushr 24
            if (alpha != 0xFF) hasAlpha = true
            if (alpha >= 0x80) keys[opaquePixels++] = p and RGB_MASK else transparentPixels++
        }
        val sorted = keys.copyOf(opaquePixels)
        sorted.sort()
        var unique = 0
        var i = 0
        while (i < sorted.size) {
            unique++
            var j = i + 1
            while (j < sorted.size && sorted[j] == sorted[i]) j++
            i = j
        }
        // Pixel-weighted Lab statistics over opaque pixels.
        var sumL = 0.0
        var sumChroma = 0.0
        var blueVotes = 0
        var orangeVotes = 0
        for (p in image.pixels) {
            if ((p ushr 24) < 0x80) continue
            val lab = LabColor.fromArgb(p)
            sumL += lab.l
            val chroma = sqrt(lab.a * lab.a + lab.b * lab.b)
            sumChroma += chroma
            if (chroma >= hueChromaFloor) {
                val hue = normalizeDegrees(Math.toDegrees(atan2(lab.b, lab.a)))
                if (hue >= blueHueMin && hue < blueHueMax) {
                    blueVotes++
                } else if (hue >= orangeHueMin && hue < orangeHueMax) {
                    orangeVotes++
                }
            }
        }
        val avgChroma = if (opaquePixels > 0) sumChroma / opaquePixels else 0.0
        val blueShare = if (opaquePixels > 0) blueVotes.toDouble() / opaquePixels else 0.0
        val orangeShare = if (opaquePixels > 0) orangeVotes.toDouble() / opaquePixels else 0.0
        val suggestedPaletteId = when {
            avgChroma < gameboyMaxChroma -> "gameboy"
            blueShare > kimiBlueShare -> "kimi"
            orangeShare > clawdOrangeShare -> "clawd"
            avgChroma > pico8MinChroma && unique >= pico8MinUnique -> "pico-8"
            else -> "endesga-32"
        }
        val brightness = if (opaquePixels > 0) (sumL / opaquePixels / 100.0).toFloat() else 0f
        // Dimensions: snap the long edge, scale the short edge.
        val longEdge = maxOf(image.width, image.height)
        val shortEdge = minOf(image.width, image.height)
        val snapped = snapLongEdge(longEdge)
        val scaledShort = ((shortEdge.toLong() * snapped + longEdge / 2) / longEdge).toInt().coerceAtLeast(1)
        val suggestedWidth: Int
        val suggestedHeight: Int
        if (image.width >= image.height) {
            suggestedWidth = snapped
            suggestedHeight = scaledShort
        } else {
            suggestedWidth = scaledShort
            suggestedHeight = snapped
        }
        val suggestedColorCount = if (unique <= 16) unique else if (suggestedPaletteId == "endesga-32") 32 else 16
        val notes = buildNotes(unique, transparentPixels, image.pixels.size, hasAlpha, longEdge, snapped, suggestedPaletteId, avgChroma)
        return AnalysisResult(
            suggestedWidth = suggestedWidth,
            suggestedHeight = suggestedHeight,
            suggestedColorCount = suggestedColorCount,
            suggestedPaletteId = suggestedPaletteId,
            brightness = brightness,
            hasAlpha = hasAlpha,
            notes = notes,
        )
    }

    /** Nearest of 16/32/64 for the long edge; ties resolve to the smaller size. */
    private fun snapLongEdge(longEdge: Int): Int {
        var best = 16
        var bestDistance = abs(longEdge - 16)
        for (candidate in intArrayOf(32, 64)) {
            val distance = abs(longEdge - candidate)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        return best
    }

    /** Wraps [degrees] into `[0, 360)`. */
    private fun normalizeDegrees(degrees: Double): Double {
        val wrapped = degrees % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    /** Human-readable analysis notes; wording is stable for identical input. */
    private fun buildNotes(
        unique: Int, transparentPixels: Int, totalPixels: Int, hasAlpha: Boolean,
        longEdge: Int, snapped: Int, paletteId: String, avgChroma: Double,
    ): List<String> {
        val notes = ArrayList<String>(5)
        notes.add("$unique unique opaque color" + if (unique == 1) "" else "s")
        if (hasAlpha) {
            notes.add("transparency present ($transparentPixels of $totalPixels pixels)")
        }
        notes.add("long edge $longEdge snaps to $snapped")
        notes.add("suggested palette '$paletteId' (average chroma ${avgChroma.toInt()})")
        if (unique > 256) {
            notes.add("more than 256 unique colors; extraction will reduce them")
        }
        return notes
    }

    // ------------------------------------------------------------------
    // refine
    // ------------------------------------------------------------------

    private fun refineInternal(frame: PixelFrame, instructions: RefineInstructions): PixelResult<PixelFrame> {
        var pixels = frame.copyPixels()
        instructions.snapAlphaThreshold?.let { threshold ->
            binarizeAlpha(pixels, threshold)
        }
        if (instructions.removeStrayPixels) {
            pixels = EdgeCleaner.removeStrayPixels(pixels, frame.width, frame.height)
        }
        return PixelResult.ok(PixelFrame.of(frame.width, frame.height, pixels))
    }
}

/**
 * Post-dithering cleanup shared by [ImageConverter.convert] and
 * [ImageConverter.refine]. Both operations read the input grid as a snapshot
 * and return either a fresh array or the untouched input.
 */
internal object EdgeCleaner {

    /**
     * Zeroes near-transparent pixels: any alpha strictly between 0 and 0x80
     * becomes fully transparent `0x00000000`. Fully opaque and fully
     * transparent pixels are untouched.
     */
    fun clean(pixels: IntArray): IntArray {
        var out = pixels
        for (i in pixels.indices) {
            val alpha = pixels[i] ushr 24
            if (alpha != 0 && alpha < 0x80) {
                if (out === pixels) out = pixels.copyOf()
                out[i] = 0
            }
        }
        return out
    }

    /**
     * Removes stray opaque pixels: a pixel whose four neighbors all differ
     * from it is replaced by the dominant neighbor color. Transparent
     * neighbors vote `0x00000000` (out-of-bounds counts as transparent) and
     * ties resolve to the earliest vote in up/down/left/right order.
     * Decisions read the original grid (snapshot semantics), so replacements
     * never cascade within a single call.
     */
    fun removeStrayPixels(pixels: IntArray, width: Int, height: Int): IntArray {
        var out = pixels
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val center = pixels[index]
                if ((center ushr 24) < 0x80) continue
                val up = if (y > 0) pixels[index - width] else 0
                val down = if (y < height - 1) pixels[index + width] else 0
                val left = if (x > 0) pixels[index - 1] else 0
                val right = if (x < width - 1) pixels[index + 1] else 0
                if (up == center || down == center || left == center || right == center) continue
                if (out === pixels) out = pixels.copyOf()
                out[index] = dominantNeighbor(up, down, left, right)
            }
        }
        return out
    }

    /** Mode of the four neighbor votes; the earliest occurrence breaks ties. */
    private fun dominantNeighbor(up: Int, down: Int, left: Int, right: Int): Int {
        val votes = intArrayOf(up, down, left, right)
        var best = up
        var bestCount = 0
        for (vote in votes) {
            var count = 0
            for (other in votes) {
                if (other == vote) count++
            }
            if (count > bestCount) {
                best = vote
                bestCount = count
            }
        }
        return best
    }
}
