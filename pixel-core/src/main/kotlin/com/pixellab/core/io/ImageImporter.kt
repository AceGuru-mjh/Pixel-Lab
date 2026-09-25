package com.pixellab.core.io

import com.pixellab.core.PixelErrorKind
import com.pixellab.core.PixelResult
import com.pixellab.core.model.Frame
import com.pixellab.core.model.Layer
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.model.SpriteProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoded frames of an imported still or animated image, before they are
 * shaped into a [SpriteProject].
 */
data class ImportedFrames(
    /** Sniffed container format of the source bytes. */
    val format: ImageFormat,
    /** Canvas width shared by all frames. */
    val width: Int,
    /** Canvas height shared by all frames. */
    val height: Int,
    /** Frames in playback order, row-major ARGB, all sized `width x height`. */
    val frames: List<PixelFrame>,
    /**
     * Per-frame display durations in milliseconds (GIF centiseconds * 10,
     * APNG `fcTL` delays), or null when the format carries no timing
     * (static PNG, QOI, BMP).
     */
    val delaysMs: List<Int>?,
    /**
     * Distinct opaque colors of all frames in first-appearance order when
     * they fit in 256 entries — a ready [Palette] for pixel-art sources;
     * null for photos with more than 256 distinct colors or fully
     * transparent images.
     */
    val paletteHint: Palette?,
)

/**
 * Import facade of Pixel Lab — the reading counterpart of
 * `com.pixellab.core.export.Exporter`.
 *
 * `importFrames` sniffs the container with [FormatSniffer] and dispatches to
 * the format decoder ([GifDecoder], [PngDecoder], [QoiCodec], [BmpCodec],
 * [AsepriteImporter]); `importProject` / `importProjectFromFrames` shape the
 * decoded frames into a single-layer [SpriteProject] (fps 12, frame durations
 * carried over when the source had timing, palette hint as project palette).
 *
 * Fail-fast entry points throw the format-specific decode exceptions (all
 * [IllegalArgumentException] subclasses) — programmer errors fail at the
 * call site. The `*Result` variants mirror [PixelResult] conventions of the
 * export side: they run the identical work on [Dispatchers.Default] and map
 * malformed input to [PixelErrorKind.INVALID_INPUT], unknown/unsupported
 * formats to [PixelErrorKind.UNSUPPORTED], and unexpected failures to
 * [PixelErrorKind.CONVERT_FAILED], so hosts can branch without try/catch.
 *
 * ICO is deliberately import-unsupported (export-only container): its
 * sniff branch fails with [ImageFormat.ICO] in the message.
 */
object ImageImporter {

    /** fps of projects created by [importProject]. */
    private const val IMPORT_FPS = 12

    /** Palette hint cap: pixel-art sources stay under this color count. */
    private const val PALETTE_HINT_CAP = 256

    /**
     * Decodes [bytes] into frames, timing and a palette hint, dispatching on
     * the sniffed format.
     *
     * @throws IllegalArgumentException (one of the format decode exceptions,
     * or a plain `require` message) when the bytes are malformed; an
     * `ICO`/`UNKNOWN` sniff fails with a descriptive message.
     */
    fun importFrames(bytes: ByteArray): ImportedFrames {
        val format = FormatSniffer.sniff(bytes)
        return when (format) {
            ImageFormat.GIF -> importGif(bytes)
            ImageFormat.PNG -> importPng(bytes)
            ImageFormat.QOI -> importQoi(bytes)
            ImageFormat.BMP -> importBmp(bytes)
            ImageFormat.ASEPRITE -> importAseprite(bytes)
            ImageFormat.ICO -> throw IllegalArgumentException(
                "ICO is an export-only container; decode its embedded PNGs individually instead"
            )
            ImageFormat.UNKNOWN -> throw IllegalArgumentException(
                "Unrecognised image format: no supported magic signature (PNG, GIF, QOI, BMP, Aseprite)"
            )
        }
    }

    /**
     * Imports [bytes] as a single-layer [SpriteProject] named [name]:
     * one [Frame] per decoded image (cel on `"Layer 1"`), per-frame duration
     * overrides from the source timing, fps 12, palette from the hint or the
     * grayscale fallback.
     *
     * @throws IllegalArgumentException propagated from [importFrames].
     */
    fun importProject(bytes: ByteArray, name: String = "import"): SpriteProject =
        importProjectFromFrames(importFrames(bytes), name)

    /**
     * Shapes already-decoded [imported] frames into a single-layer
     * [SpriteProject] named [name].
     *
     * Frame durations outside `1..60000` ms (e.g. zero-centisecond GIF
     * frames) drop to null — the project fps then governs playback. When
     * [ImportedFrames.paletteHint] is null, the project palette becomes a
     * 256-step grayscale ramp (the model requires a non-empty palette).
     */
    fun importProjectFromFrames(imported: ImportedFrames, name: String = "import"): SpriteProject {
        require(imported.frames.isNotEmpty()) { "imported frames must not be empty" }
        val layer = Layer(id = 0, name = "Layer 1")
        val delays = imported.delaysMs
        val frames = imported.frames.mapIndexed { index, raster ->
            val duration = delays?.getOrNull(index)?.takeIf { it in 1..60_000 }
            Frame(id = index, cels = mapOf(0 to raster), durationMs = duration)
        }
        val palette = imported.paletteHint ?: grayscaleFallback()
        return SpriteProject(
            id = SpriteFactory.defaultId(),
            name = name,
            width = imported.width,
            height = imported.height,
            layers = listOf(layer),
            frames = frames,
            activeLayerId = 0,
            activeFrameIndex = 0,
            palette = palette,
            fps = IMPORT_FPS,
            nextLayerId = 1,
            nextFrameId = frames.size,
        )
    }

    /**
     * [PixelResult] wrapper of [importFrames] running on
     * [Dispatchers.Default]: [PixelErrorKind.INVALID_INPUT] for malformed
     * bytes, [PixelErrorKind.UNSUPPORTED] for unknown formats/ICO.
     */
    suspend fun importFramesResult(bytes: ByteArray): PixelResult<ImportedFrames> =
        withContext(Dispatchers.Default) {
            val format = FormatSniffer.sniff(bytes)
            if (format == ImageFormat.UNKNOWN || format == ImageFormat.ICO) {
                return@withContext PixelResult.err(
                    PixelErrorKind.UNSUPPORTED,
                    "Unsupported import format ${format.humanName()}",
                )
            }
            guarded("Frame import") { importFrames(bytes) }
        }

    /**
     * [PixelResult] wrapper of [importProject] running on
     * [Dispatchers.Default]; error mapping as in [importFramesResult].
     */
    suspend fun importProjectResult(bytes: ByteArray, name: String = "import"): PixelResult<SpriteProject> =
        withContext(Dispatchers.Default) {
            val format = FormatSniffer.sniff(bytes)
            if (format == ImageFormat.UNKNOWN || format == ImageFormat.ICO) {
                return@withContext PixelResult.err(
                    PixelErrorKind.UNSUPPORTED,
                    "Unsupported import format ${format.humanName()}",
                )
            }
            guarded("Project import") { importProject(bytes, name) }
        }

    // ---- format dispatch -----------------------------------------------------

    private fun importGif(bytes: ByteArray): ImportedFrames {
        val decoded = GifDecoder.decode(bytes)
        return ImportedFrames(
            format = ImageFormat.GIF,
            width = decoded.logicalWidth,
            height = decoded.logicalHeight,
            frames = decoded.frames.map { it.frame },
            delaysMs = decoded.frames.map { it.delayCs * 10 },
            paletteHint = paletteHint(decoded.frames.map { it.frame }),
        )
    }

    private fun importPng(bytes: ByteArray): ImportedFrames {
        val decoded = PngDecoder.decode(bytes)
        val frames = decoded.frames
        val first = frames.first()
        val timing = if (frames.any { it.delayMs != null }) frames.map { it.delayMs ?: 0 } else null
        return ImportedFrames(
            format = ImageFormat.PNG,
            width = first.frame.width,
            height = first.frame.height,
            frames = frames.map { it.frame },
            delaysMs = timing,
            paletteHint = paletteHint(frames.map { it.frame }),
        )
    }

    private fun importQoi(bytes: ByteArray): ImportedFrames {
        val decoded = QoiCodec.decodeWithHeader(bytes)
        return ImportedFrames(
            format = ImageFormat.QOI,
            width = decoded.frame.width,
            height = decoded.frame.height,
            frames = listOf(decoded.frame),
            delaysMs = null,
            paletteHint = paletteHint(listOf(decoded.frame)),
        )
    }

    private fun importBmp(bytes: ByteArray): ImportedFrames {
        val frame = BmpCodec.decode(bytes)
        return ImportedFrames(
            format = ImageFormat.BMP,
            width = frame.width,
            height = frame.height,
            frames = listOf(frame),
            delaysMs = null,
            paletteHint = paletteHint(listOf(frame)),
        )
    }

    private fun importAseprite(bytes: ByteArray): ImportedFrames {
        val project = AsepriteImporter.import(bytes)
        val frames = (0 until project.frameCount).map { project.compositeFrame(it) }
        return ImportedFrames(
            format = ImageFormat.ASEPRITE,
            width = project.width,
            height = project.height,
            frames = frames,
            delaysMs = (0 until project.frameCount).map { project.effectiveFrameDuration(it) },
            paletteHint = paletteHint(frames),
        )
    }

    // ---- helpers --------------------------------------------------------------

    /**
     * Distinct opaque colors across [frames] in first-appearance order, or
     * null when there are none or more than [PALETTE_HINT_CAP].
     */
    private fun paletteHint(frames: List<PixelFrame>): Palette? {
        val distinct = LinkedHashMap<Int, Unit>()
        for (frame in frames) {
            for (argb in frame.pixels) {
                if ((argb ushr 24) == 0) continue
                distinct.putIfAbsent(argb, Unit)
                if (distinct.size > PALETTE_HINT_CAP) return null
            }
        }
        if (distinct.isEmpty()) return null
        return Palette(
            id = "import-hint",
            name = "Imported colors",
            colors = distinct.keys.toIntArray(),
            source = PaletteSource.EXTRACTED,
        )
    }

    /** 256-step grayscale fallback palette for palette-less imports. */
    private fun grayscaleFallback(): Palette {
        val colors = IntArray(256) { i ->
            (0xFF shl 24) or (i shl 16) or (i shl 8) or i
        }
        return Palette(
            id = "import-grayscale",
            name = "Grayscale fallback",
            colors = colors,
            source = PaletteSource.CUSTOM,
        )
    }

    /**
     * Runs [block] and maps failures like the export facade: malformed input
     * ([IllegalArgumentException], which covers every decode exception) to
     * [PixelErrorKind.INVALID_INPUT], other exceptions to
     * [PixelErrorKind.CONVERT_FAILED].
     */
    private inline fun <T> guarded(label: String, block: () -> T): PixelResult<T> = try {
        PixelResult.ok(block())
    } catch (error: IllegalArgumentException) {
        PixelResult.err(PixelErrorKind.INVALID_INPUT, "$label rejected input: ${error.message}", error)
    } catch (error: Exception) {
        PixelResult.err(PixelErrorKind.CONVERT_FAILED, "$label failed: ${error.message}", error)
    }
}
