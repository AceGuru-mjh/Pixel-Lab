package com.pixellab.core.export

import android.graphics.Bitmap
import android.os.Build
import com.pixellab.core.PixelErrorKind
import com.pixellab.core.PixelLabConfig
import com.pixellab.core.PixelResult
import com.pixellab.core.convert.DitherAlgorithm
import com.pixellab.core.convert.QuantizeAlgorithm
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.nativelib.NativeGifEncoder
import com.pixellab.core.nativelib.NativeLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Layout strategies for spritesheet composition.
 */
enum class SpritesheetLayout {
    /** All frames packed into a single row, left to right. */
    HORIZONTAL,

    /** All frames packed into a single column, top to bottom. */
    VERTICAL,

    /** Fixed column count, frames wrap onto new rows; trailing cells of the
     * last row stay transparent. */
    GRID,
}

/**
 * Export facade for Pixel Lab: PNG sprites and spritesheets, animated GIF and
 * APNG, WebP, Android [Bitmap] rendering and the Codex companion-pet pack.
 *
 * Every public entry point is a `suspend` function that performs its work on
 * [Dispatchers.Default]. Argument validation (scale ranges, loop counts,
 * margin, column counts, frame-index domains) runs synchronously **before**
 * the dispatcher switch and throws [IllegalArgumentException] on bad input —
 * programmer errors fail fast at the call site. Failures that occur while
 * encoding are surfaced as [PixelResult.Err] instead of exceptions, so hosts
 * can branch without try/catch.
 *
 * The GIF path prefers the native encoder
 * ([NativeGifEncoder], median-cut quantization) when
 * [PixelLabConfig.preferNative] is set and `pixel_lab_native` links
 * successfully, and falls back to [KotlinGifEncoder] on
 * `UnsatisfiedLinkError`/`IllegalArgumentException` (fallback logged through
 * [PixelLabConfig.effectiveLogger] at warn level).
 *
 * All outputs are deterministic: identical inputs produce byte-identical
 * files (Codex ZIP entry timestamps are pinned to `1577836800000L`).
 */
class Exporter(private val config: PixelLabConfig) {

    /** Inclusive range of accepted integer upscale factors. */
    private val scaleRange = 1..16

    /** Log tag used for native-fallback warnings. */
    private val logTag = "Exporter"

    /** First Android API level where `WEBP_LOSSLESS` compress exists. */
    private val webpLosslessMinSdk = 30 // Build.VERSION_CODES.R

    /**
     * Encodes a single [frame] as a PNG file.
     *
     * @param frame raster to encode; pixels are packed `0xAARRGGBB`.
     * @param scale integer nearest-neighbor upscale factor, `1..16`.
     * @return PNG bytes (signature, `IHDR`, single `IDAT`, `IEND`).
     * @throws IllegalArgumentException if [scale] is outside `1..16` — thrown
     * synchronously, before the dispatcher switch.
     */
    suspend fun exportPng(frame: PixelFrame, scale: Int = 1): PixelResult<ByteArray> {
        require(scale in scaleRange) { "scale must be in 1..16 (was $scale)" }
        return withContext(Dispatchers.Default) {
            guarded("PNG export") { PngCodec.encode(frame, scale) }
        }
    }

    /**
     * Encodes one frame of [project] (all visible layers composited) as a PNG
     * file.
     *
     * @param project source project.
     * @param frameIndex index of the frame to composite.
     * @param scale integer nearest-neighbor upscale factor, `1..16`.
     * @return PNG bytes of the composited frame.
     * @throws IllegalArgumentException if [scale] is outside `1..16` or
     * [frameIndex] is outside the project's frame range — thrown
     * synchronously, before the dispatcher switch.
     */
    suspend fun exportPng(
        project: SpriteProject,
        frameIndex: Int = project.activeFrameIndex,
        scale: Int = 1,
    ): PixelResult<ByteArray> {
        require(scale in scaleRange) { "scale must be in 1..16 (was $scale)" }
        require(frameIndex in project.frames.indices) {
            "frameIndex $frameIndex out of bounds (project has ${project.frameCount} frames)"
        }
        return withContext(Dispatchers.Default) {
            guarded("PNG export") { PngCodec.encode(project.compositeFrame(frameIndex), scale) }
        }
    }

    /**
     * Composes every frame of [project] into a spritesheet and encodes it as
     * a PNG. Frames are composited (all visible layers), upscaled
     * nearest-neighbor by [scale] and packed into uniform cells of
     * `frameWidth * scale + 2 * margin` by `frameHeight * scale + 2 * margin`
     * pixels; margin rings and empty trailing cells stay transparent.
     *
     * @param project source project.
     * @param scale integer upscale factor, `1..16`.
     * @param layout sheet layout; [SpritesheetLayout.GRID] by default.
     * @param columns cells per row, only used by
     * [SpritesheetLayout.GRID] (`>= 1`).
     * @param margin transparent padding inside each cell (`>= 0`).
     * @return PNG bytes of the spritesheet.
     * @throws IllegalArgumentException if [scale] is outside `1..16`,
     * [columns] is below 1 or [margin] is negative — thrown synchronously,
     * before the dispatcher switch.
     */
    suspend fun exportSpritesheet(
        project: SpriteProject,
        scale: Int = 1,
        layout: SpritesheetLayout = SpritesheetLayout.GRID,
        columns: Int = 8,
        margin: Int = 0,
    ): PixelResult<ByteArray> {
        require(scale in scaleRange) { "scale must be in 1..16 (was $scale)" }
        require(columns >= 1) { "columns must be >= 1 (was $columns)" }
        require(margin >= 0) { "margin must be >= 0 (was $margin)" }
        return withContext(Dispatchers.Default) {
            guarded("Spritesheet export") {
                val sheet = SpritesheetExporter.compose(
                    compositeFrames(project),
                    scale,
                    layout,
                    columns,
                    margin,
                )
                PngCodec.encode(PixelFrame.of(sheet.width, sheet.height, sheet.pixels))
            }
        }
    }

    /**
     * Encodes [project] as an animated GIF89a.
     *
     * Every frame is composited from all visible layers; the per-frame delay
     * is the frame's `durationMs` override or `1000 / fps`. The native
     * encoder ([NativeGifEncoder], median-cut quantization, [dither] passed
     * through by id) is preferred when [PixelLabConfig.preferNative] is set
     * and the native library links; otherwise — or when the native call fails
     * with `UnsatisfiedLinkError`/`IllegalArgumentException` — the pure-Kotlin
     * [KotlinGifEncoder] takes over and the fallback is logged at warn level.
     * The two encoders produce equally valid but not byte-identical GIFs;
     * each path is independently deterministic.
     *
     * @param project source project (needs at least one frame, which the
     * model guarantees).
     * @param loopCount NETSCAPE loop count; 0 loops forever.
     * @param dither dithering strategy forwarded to the native encoder's
     * quantizer; the Kotlin fallback dithers implicitly through its own
     * palette mapping.
     * @return GIF89a bytes.
     * @throws IllegalArgumentException if [loopCount] is negative — thrown
     * synchronously, before the dispatcher switch.
     */
    suspend fun exportGif(
        project: SpriteProject,
        loopCount: Int = 0,
        dither: DitherAlgorithm = DitherAlgorithm.FLOYD_STEINBERG,
    ): PixelResult<ByteArray> {
        require(loopCount >= 0) { "loopCount must be >= 0 (was $loopCount)" }
        return withContext(Dispatchers.Default) {
            guarded("GIF export") { encodeGifBytes(project, loopCount, dither) }
        }
    }

    /**
     * Encodes [project] as an APNG (animated PNG).
     *
     * Every frame is composited from all visible layers. Per-frame delays
     * come from [SpriteProject.effectiveFrameDuration]: a frame's explicit
     * `durationMs` override, otherwise the single fps-derived delay
     * `1000 / fps` shared by all frames without an override (the APNG chunk
     * format expresses each frame's delay separately, so overrides do apply
     * per frame; projects without overrides run at one uniform `1000 / fps`
     * delay).
     *
     * @param project source project (needs at least one frame, which the
     * model guarantees).
     * @param loopCount `acTL num_plays`; 0 loops forever.
     * @return APNG bytes.
     * @throws IllegalArgumentException if [loopCount] is negative — thrown
     * synchronously, before the dispatcher switch.
     */
    suspend fun exportApng(
        project: SpriteProject,
        loopCount: Int = 0,
    ): PixelResult<ByteArray> {
        require(loopCount >= 0) { "loopCount must be >= 0 (was $loopCount)" }
        return withContext(Dispatchers.Default) {
            guarded("APNG export") {
                ApngEncoder.encode(
                    project.width,
                    project.height,
                    compositeFrames(project),
                    frameDurations(project),
                    loopCount,
                )
            }
        }
    }

    /**
     * Encodes a single [frame] as WebP bytes through the Android
     * [Bitmap] pipeline.
     *
     * With [lossless] `= true` (the default) the bitmap is compressed as
     * `Bitmap.CompressFormat.WEBP_LOSSLESS` on API 30+; on older API levels
     * that format does not exist, so the export **degrades to lossy
     * `WEBP` at quality 100** (a visually near-lossless but not bit-exact
     * encoding). [lossless] `= false` always selects lossy `WEBP` quality
     * 100. WebP byte output may differ between Android versions and devices —
     * only PNG/GIF/APNG exports are byte-stable.
     *
     * @param frame raster to encode; pixels are packed `0xAARRGGBB`.
     * @param scale integer nearest-neighbor upscale factor, `1..16`.
     * @param lossless request a lossless encode (see the degradation note).
     * @return WebP bytes.
     * @throws IllegalArgumentException if [scale] is outside `1..16` — thrown
     * synchronously, before the dispatcher switch.
     */
    suspend fun exportWebp(
        frame: PixelFrame,
        scale: Int = 1,
        lossless: Boolean = true,
    ): PixelResult<ByteArray> {
        require(scale in scaleRange) { "scale must be in 1..16 (was $scale)" }
        return withContext(Dispatchers.Default) {
            guarded("WebP export") { encodeWebpBytes(frame, scale, lossless) }
        }
    }

    /**
     * Exports the Codex companion-pet pack: a ZIP holding `spritesheet.png`
     * (fixed 1536 x 1872 sheet, 8 columns x 9 rows of 192 x 208 cells, frames
     * bottom-aligned) and `pet.json` (all 9 tracks in
     * `CodexPetSpec.TRACKS` order; missing tracks carry `frames: 0`).
     *
     * @param petName pet display name written into `pet.json`.
     * @param project source project; track frames are composited.
     * @param trackMap animation track name to project frame range; unknown
     * keys are ignored, missing tracks leave empty sheet rows.
     * @return ZIP bytes; deterministic for identical inputs (entry
     * timestamps pinned).
     */
    suspend fun exportCodexPet(
        petName: String,
        project: SpriteProject,
        trackMap: Map<String, IntRange>,
    ): PixelResult<ByteArray> {
        return withContext(Dispatchers.Default) {
            guarded("Codex pet export") { CodexPetExporter.export(petName, project, trackMap) }
        }
    }

    /**
     * Renders [frame] as an immutable `ARGB_8888` Android [Bitmap], optionally
     * upscaled with nearest-neighbor (hard pixel edges, no filtering).
     *
     * The returned bitmap is owned by the caller; this method never recycles
     * it.
     *
     * @param frame raster to render; pixels are packed `0xAARRGGBB`.
     * @param scale integer upscale factor, `1..16`.
     * @return bitmap sized `frame.width * scale x frame.height * scale`.
     * @throws IllegalArgumentException if [scale] is outside `1..16` — thrown
     * synchronously, before the dispatcher switch.
     */
    suspend fun renderToBitmap(frame: PixelFrame, scale: Int = 1): PixelResult<Bitmap> {
        require(scale in scaleRange) { "scale must be in 1..16 (was $scale)" }
        return withContext(Dispatchers.Default) {
            guarded("Bitmap render") { BitmapIo.frameToBitmap(frame, scale) }
        }
    }

    // ---- internals ---------------------------------------------------------

    /**
     * Encodes the GIF bytes for [project], dispatching to the native encoder
     * when configured and linked, with a Kotlin fallback on native failure.
     */
    private fun encodeGifBytes(
        project: SpriteProject,
        loopCount: Int,
        dither: DitherAlgorithm,
    ): ByteArray {
        val frames = compositeFrames(project)
        val delays = frameDurations(project)
        if (config.preferNative && NativeLib.load()) {
            val framePixels = Array(frames.size) { frames[it].pixels }
            try {
                return NativeGifEncoder.encode(
                    project.width,
                    project.height,
                    framePixels,
                    delays.toIntArray(),
                    loopCount,
                    QuantizeAlgorithm.MEDIAN_CUT.id,
                    dither.id,
                )
            } catch (error: UnsatisfiedLinkError) {
                warnNativeFallback("library linking failed", error)
            } catch (error: IllegalArgumentException) {
                warnNativeFallback("input rejected", error)
            }
        }
        return KotlinGifEncoder.encode(project.width, project.height, frames, delays, loopCount)
    }

    /**
     * Encodes WebP bytes for [frame], selecting `WEBP_LOSSLESS` when
     * requested and supported, otherwise lossy `WEBP` at quality 100. The
     * intermediate bitmap is recycled once compressed.
     */
    private fun encodeWebpBytes(frame: PixelFrame, scale: Int, lossless: Boolean): ByteArray {
        val bitmap = BitmapIo.frameToBitmap(frame, scale)
        try {
            val format = if (lossless && Build.VERSION.SDK_INT >= webpLosslessMinSdk) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            val out = ByteArrayOutputStream(bitmap.width * bitmap.height * 2 + 64)
            check(bitmap.compress(format, 100, out)) {
                "Bitmap.compress produced no WebP bytes"
            }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** Logs a native GIF fallback at warn level through the config logger. */
    private fun warnNativeFallback(reason: String, error: Throwable) {
        config.effectiveLogger.w(
            logTag,
            "Native GIF encoder unavailable ($reason); using KotlinGifEncoder: ${error.message}",
        )
    }

    /** Composites every frame of [project] in playback order. */
    private fun compositeFrames(project: SpriteProject): List<PixelFrame> {
        val frames = ArrayList<PixelFrame>(project.frameCount)
        for (index in 0 until project.frameCount) {
            frames.add(project.compositeFrame(index))
        }
        return frames
    }

    /** Effective duration (ms) of every frame of [project], in order. */
    private fun frameDurations(project: SpriteProject): List<Int> {
        val delays = ArrayList<Int>(project.frameCount)
        for (index in 0 until project.frameCount) {
            delays.add(project.effectiveFrameDuration(index))
        }
        return delays
    }

    /**
     * Runs [block] and wraps its outcome in a [PixelResult]: success becomes
     * [PixelResult.Ok]; [IllegalArgumentException] maps to
     * [PixelErrorKind.INVALID_INPUT] (malformed input discovered while
     * encoding) and any other exception to [PixelErrorKind.EXPORT_FAILED].
     */
    private inline fun <T> guarded(label: String, block: () -> T): PixelResult<T> = try {
        PixelResult.ok(block())
    } catch (error: IllegalArgumentException) {
        PixelResult.err(PixelErrorKind.INVALID_INPUT, "$label rejected input: ${error.message}", error)
    } catch (error: Exception) {
        PixelResult.err(PixelErrorKind.EXPORT_FAILED, "$label failed: ${error.message}", error)
    }
}
