package com.pixellab.core.io

import com.pixellab.core.model.AnimationTag
import com.pixellab.core.model.Frame
import com.pixellab.core.model.Layer
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.model.SpriteProject
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Raised by [AsepriteImporter] when a byte stream violates the Aseprite
 * `.ase` / `.aseprite` file structure: wrong header or frame magic, chunk
 * sizes running past frame or file bounds, unknown cel types, corrupt zlib
 * cels, unresolvable linked cels or tags that violate the project model.
 * Messages carry the absolute byte offset of the offending structure.
 */
class AsepriteDecodeException(message: String) : IllegalArgumentException(message)

/**
 * Pure-JVM Aseprite `.ase` / `.aseprite` importer — turns a sprite file into
 * a fully populated [SpriteProject].
 *
 * ## Format coverage
 *
 * * 128-byte file header (magic `0xA5E0`, frame count, canvas size, color
 *   depth 32 RGBA — plus best-effort legacy 16-bit grayscale cels), flags
 *   (bit 0 = layer opacity valid) and grid fields (read, not used);
 * * per-frame records: frame magic `0xF1F0`, duration in ms, chunk count
 *   (the u32 "new" count whenever the legacy u16 count reads `0xFFFF`);
 * * chunk types:
 *   * `0x0004` / `0x0011` old palette chunks (3- and 4-byte packet colors),
 *   * `0x2005` cel chunks — raw (type 0: `w`, `h`, then interleaved
 *     `r, g, b, a` bytes per pixel), linked (type 1: a reference to another
 *     frame's cel, resolved with cycle detection) and compressed (type 2:
 *     zlib via [Inflater]),
 *   * `0x2007` color profile, `0x2006` cel extra and `0x2020` user data —
 *     skipped by chunk size,
 *   * `0x2018` tags (name, frame span; the tag color is dropped — the
 *     project model has no tag color field),
 *   * `0x2019` new palette (u32 entry count, RGBA entries, optional names),
 *   * `0x2022` layers (flags, type, blend mode, opacity, name);
 * * unknown chunk types are skipped whole, using the chunk size field.
 *
 * ## Mapping decisions (documented simplifications)
 *
 * * **Layer order**: Aseprite emits layer chunks bottom-up (chunk index 0 is
 *   the bottom layer); that order maps 1:1 onto [SpriteProject.layers]
 *   (`layers[0]` renders first). Cel chunks reference layers by that same
 *   chunk index.
 * * **Groups**: group layers (type 1) are kept in the index mapping but emit
 *   no [Layer] — the project is flattened. Blend modes are ignored (the
 *   model composites with source-over).
 * * **Opacity**: the model has no cel opacity field, so both cel opacity and
 *   (header-flag-valid) layer opacity are baked into cel pixel alpha as
 *   `alpha * opacity / 255`, integer arithmetic. Cels of layers with layer
 *   opacity additionally get the layer-level factor applied at composite
 *   time via [Layer.opacity].
 * * **Cel geometry**: Aseprite cels may be smaller than the canvas and
 *   offset (`x`, `y` may be negative); they are placed into a full
 *   canvas-sized transparent cel, clipped at the edges.
 * * **Palette**: taken from the newest `0x2019` / old palette chunk when
 *   present; otherwise the distinct opaque colors of all cels in
 *   first-appearance order (capped at 256); fully transparent sprites fall
 *   back to a single opaque black entry (the model requires non-empty).
 * * **fps**: derived from the first frame's duration (`1000 / ms`,
 *   clamped to `1..24`), defaulting to 12 when durations are unusable.
 * * Frame durations outside `1..60000` ms (including the 0 of degenerate
 *   files) are dropped to null — the project fps then governs playback.
 */
object AsepriteImporter {

    /** File header magic. */
    private const val FILE_MAGIC = 0xA5E0

    /** Frame magic. */
    private const val FRAME_MAGIC = 0xF1F0

    /** Total file header size. */
    private const val HEADER_SIZE = 128

    /** Per-frame header size. */
    private const val FRAME_HEADER_SIZE = 16

    /** Chunk types handled structurally. */
    private const val CHUNK_OLD_PALETTE_3B = 0x0004
    private const val CHUNK_OLD_PALETTE_4B = 0x0011
    private const val CHUNK_CEL = 0x2005
    private const val CHUNK_TAGS = 0x2018
    private const val CHUNK_NEW_PALETTE = 0x2019
    private const val CHUNK_LAYER = 0x2022

    /** Chunk-header size: u32 size + u16 type. */
    private const val CHUNK_HEADER_SIZE = 6

    /** Palette size cap for the extracted fallback palette. */
    private const val EXTRACTED_PALETTE_CAP = 256

    /** Hard sanity cap for stored palette entries. */
    private const val MAX_PALETTE_ENTRIES = 65_536

    /**
     * Imports an Aseprite sprite file as a [SpriteProject].
     *
     * @param bytes `.ase` / `.aseprite` file contents.
     * @param projectName display name of the created project.
     * @return a project with one [Frame] per Aseprite frame, cels per
     * non-group layer, tags mapped to [AnimationTag] and the file palette
     * (or the extracted fallback).
     * @throws AsepriteDecodeException on any structural violation.
     */
    fun import(bytes: ByteArray, projectName: String = "aseprite-import"): SpriteProject {
        try {
            return importInternal(bytes, projectName)
        } catch (error: AsepriteDecodeException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw AsepriteDecodeException("Malformed Aseprite file: ${error.message}")
        }
    }

    // ---- parse phase ---------------------------------------------------------

    /** Aseprite layer chunk content, in file order (index = cel reference). */
    private class AseLayer(
        val flags: Int,
        val type: Int,
        val opacity: Int,
        val name: String,
    )

    /** One parsed cel record; pixels stay unparsed until the build phase. */
    private class AseCel(
        val layerIndex: Int,
        val x: Int,
        val y: Int,
        val opacity: Int,
        val type: Int,
        var width: Int,
        var height: Int,
        var pixelBytes: ByteArray? = null,
        var linkFrame: Int = -1,
    )

    /** One parsed tag record. */
    private class AseTag(val name: String, val fromFrame: Int, val toFrame: Int)

    /** Per-frame parse result. */
    private class AseFrame(val durationMs: Int, val cels: MutableList<AseCel>)

    private fun importInternal(bytes: ByteArray, projectName: String): SpriteProject {
        val r = BinaryReader(bytes)
        val fileSize = r.u32Le()
        if (fileSize != bytes.size.toLong()) {
            throw AsepriteDecodeException(
                "Aseprite header declares $fileSize file bytes but the buffer holds ${bytes.size}"
            )
        }
        if (fileSize < HEADER_SIZE) {
            throw AsepriteDecodeException("Aseprite file too short: $fileSize bytes (minimum $HEADER_SIZE)")
        }
        val magic = r.u16Le()
        if (magic != FILE_MAGIC) {
            throw AsepriteDecodeException("Aseprite magic 0x${magic.toString(16)} != 0xA5E0")
        }
        val frameCount = r.u16Le()
        if (frameCount == 0) {
            throw AsepriteDecodeException("Aseprite file declares zero frames")
        }
        val width = r.u16Le()
        val height = r.u16Le()
        if (width == 0 || height == 0) {
            throw AsepriteDecodeException("Aseprite canvas ${width}x${height} has a zero axis")
        }
        val depth = r.u16Le()
        if (depth != 32 && depth != 16) {
            throw AsepriteDecodeException("Aseprite color depth $depth unsupported (32 RGBA, 16 grayscale)")
        }
        val flags = r.u32Le().toInt()
        val layerOpacityValid = (flags and 0x1) != 0
        r.skip(2) // speed (deprecated duration hint)
        r.skip(16) // four reserved u32 fields
        r.skip(2) // transparent palette entry index
        r.skip(8) // reserved color hints
        r.u16Le() // number of colors (the 0x2019 chunk supersedes it)
        r.u16Le() // pixel ratio width
        r.u16Le() // pixel ratio height
        r.u32Le() // grid x
        r.u32Le() // grid y
        r.u16Le() // grid width
        r.u16Le() // grid height
        r.skip(HEADER_SIZE - r.position) // rest of the fixed 128-byte header

        val layers = ArrayList<AseLayer>()
        val tags = ArrayList<AseTag>()
        val frames = ArrayList<AseFrame>(frameCount)
        var palette: IntArray? = null
        var paletteId = "ase-import"

        for (frameIndex in 0 until frameCount) {
            val frameOffset = r.position
            val frameSize = r.u32Le()
            if (frameSize < FRAME_HEADER_SIZE) {
                throw AsepriteDecodeException(
                    "Frame $frameIndex size $frameSize below the ${FRAME_HEADER_SIZE}-byte minimum"
                )
            }
            val frameEnd = frameOffset + frameSize.toInt()
            if (frameEnd > bytes.size) {
                throw AsepriteDecodeException(
                    "Frame $frameIndex at offset $frameOffset runs to $frameEnd, past the ${bytes.size}-byte file"
                )
            }
            val frameMagic = r.u16Le()
            if (frameMagic != FRAME_MAGIC) {
                throw AsepriteDecodeException(
                    "Frame $frameIndex magic 0x${frameMagic.toString(16)} != 0xF1F0 at offset ${frameOffset + 4}"
                )
            }
            val legacyChunks = r.u16Le()
            val durationMs = r.u16Le()
            r.u16Le() // reserved "for future" field — always present in the
            // fixed 16-byte frame header and must be consumed
            val newChunks = r.u32Le().toInt()
            // The old 16-bit count caps at 0xFFFE; 0xFFFF means "use the
            // 32-bit field below" (spec: chunks beyond 65534 need the new
            // count), which is always read so chunk parsing stays aligned.
            val chunkCount = if (legacyChunks == 0xFFFF) newChunks else legacyChunks
            if (chunkCount < 0) {
                throw AsepriteDecodeException("Frame $frameIndex has negative chunk count $chunkCount")
            }
            val frame = AseFrame(durationMs, ArrayList())
            frames.add(frame)

            for (chunk in 0 until chunkCount) {
                val chunkOffset = r.position
                if (chunkOffset + CHUNK_HEADER_SIZE > frameEnd) {
                    throw AsepriteDecodeException(
                        "Frame $frameIndex chunk $chunk would start at $chunkOffset, past the frame end $frameEnd"
                    )
                }
                val chunkSize = r.u32Le().toInt()
                val chunkType = r.u16Le()
                if (chunkSize < CHUNK_HEADER_SIZE || chunkOffset + chunkSize > frameEnd) {
                    throw AsepriteDecodeException(
                        "Frame $frameIndex chunk $chunk (type 0x${chunkType.toString(16)}) size $chunkSize " +
                            "at offset $chunkOffset does not fit inside the frame (end $frameEnd)"
                    )
                }
                val chunkReader = BinaryReader(bytes, chunkOffset + CHUNK_HEADER_SIZE, chunkOffset + chunkSize)
                when (chunkType) {
                    CHUNK_LAYER -> parseLayer(chunkReader, layers)

                    CHUNK_CEL -> parseCel(chunkReader, frame, frameIndex, chunkOffset, depth)

                    CHUNK_TAGS -> parseTags(chunkReader, tags, frameIndex, chunkOffset)

                    CHUNK_NEW_PALETTE -> {
                        palette = parseNewPalette(chunkReader)
                        paletteId = "ase-import"
                    }

                    CHUNK_OLD_PALETTE_3B -> {
                        palette = parseOldPalette(chunkReader, 3)
                        paletteId = "ase-import-old"
                    }

                    CHUNK_OLD_PALETTE_4B -> {
                        palette = parseOldPalette(chunkReader, 4)
                        paletteId = "ase-import-old"
                    }

                    else -> {
                        // 0x2006 cel extra, 0x2007 color profile, 0x2020 user
                        // data, future chunk types: skipped by chunk size.
                    }
                }
                r.seek(chunkOffset + chunkSize)
            }
            r.seek(frameEnd)
        }

        return buildProject(
            width, height, depth, layerOpacityValid,
            layers, tags, frames, palette, paletteId, projectName,
        )
    }

    /** Parses a `0x2022` layer chunk into [layers]. */
    private fun parseLayer(r: BinaryReader, layers: MutableList<AseLayer>) {
        val flags = r.u16Le()
        val type = r.u16Le()
        r.u16Le() // child level: only meaningful for groups (flattened away)
        r.u16Le() // default blend width (ignored)
        r.u16Le() // default blend height (ignored)
        r.u16Le() // blend mode: the project model composites source-over only
        val opacity = r.u8()
        r.skip(3) // reserved
        val name = r.stringUtf8()
        layers.add(AseLayer(flags, type, opacity, name))
    }

    /** Parses a `0x2005` cel chunk into [frame]'s cel list. */
    private fun parseCel(r: BinaryReader, frame: AseFrame, frameIndex: Int, chunkOffset: Int, depth: Int) {
        val layerIndex = r.u16Le()
        val x = r.i16Le()
        val y = r.i16Le()
        val opacity = r.u8()
        val celType = r.u16Le()
        val cel = AseCel(layerIndex, x, y, opacity, celType, 0, 0)
        when (celType) {
            0 -> { // raw
                cel.width = r.u16Le()
                cel.height = r.u16Le()
                val bytesNeeded = cel.width * cel.height * (depth / 8)
                cel.pixelBytes = r.bytes(bytesNeeded)
            }

            1 -> { // linked: same pixels as (linkFrame, layerIndex)
                cel.linkFrame = r.u16Le()
            }

            2 -> { // compressed (zlib)
                cel.width = r.u16Le()
                cel.height = r.u16Le()
                val expected = cel.width * cel.height * (depth / 8)
                cel.pixelBytes = inflateCel(r.bytes(r.remaining()), expected, frameIndex, chunkOffset)
            }

            else -> throw AsepriteDecodeException(
                "Frame $frameIndex cel on layer $layerIndex has unknown type $celType (chunk at offset $chunkOffset)"
            )
        }
        frame.cels.add(cel)
    }

    /** Parses a `0x2018` tags chunk into [tags]. */
    private fun parseTags(r: BinaryReader, tags: MutableList<AseTag>, frameIndex: Int, chunkOffset: Int) {
        val count = r.u16Le()
        for (i in 0 until count) {
            val from = r.u16Le()
            val to = r.u16Le()
            r.u8() // animation direction (forward/reverse/ping-pong): model has no direction
            r.skip(2) // repeat count (WORD): model tags have no repeat field
            r.skip(6) // tag RGB color (BYTE[6] = R,G,B,0,0,0): model tags have no color
            val name = r.stringUtf8()
            if (name.isBlank()) {
                throw AsepriteDecodeException(
                    "Frame $frameIndex tag $i at offset $chunkOffset has a blank name (model requires non-blank)"
                )
            }
            tags.add(AseTag(name, from, to))
        }
    }

    /** Parses a `0x2019` new palette chunk into a color table. */
    private fun parseNewPalette(r: BinaryReader): IntArray {
        val declaredSize = r.u32Le().toInt()
        val size = if (declaredSize == 0) 256 else declaredSize
        if (size <= 0 || size > MAX_PALETTE_ENTRIES) {
            throw AsepriteDecodeException("New palette size $size outside 1..$MAX_PALETTE_ENTRIES")
        }
        val first = r.u32Le().toInt()
        val last = r.u32Le().toInt()
        val colors = IntArray(size)
        if (first < 0 || last >= size) {
            throw AsepriteDecodeException(
                "New palette change window [$first, $last] invalid for $size entries"
            )
        }
        for (index in first..last) {
            val entryFlags = r.u16Le()
            val red = r.u8()
            val green = r.u8()
            val blue = r.u8()
            val alpha = r.u8()
            if (entryFlags and 0x1 != 0) r.stringUtf8() // entry name: skipped
            colors[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return colors
    }

    /**
     * Parses an old `0x0004`/`0x0011` palette chunk (packet layout: u16 new
     * size, then packets of `[skip][count]` followed by `count + 1` colors of
     * [bytesPerColor] bytes).
     */
    private fun parseOldPalette(r: BinaryReader, bytesPerColor: Int): IntArray {
        val size = r.u16Le()
        if (size == 0) return IntArray(256)
        if (size > MAX_PALETTE_ENTRIES) {
            throw AsepriteDecodeException("Old palette size $size above $MAX_PALETTE_ENTRIES")
        }
        val colors = IntArray(size)
        var target = 0
        while (target < size && r.hasRemaining()) {
            val skip = r.u8()
            val count = r.u8() + 1
            target += skip
            for (i in 0 until count) {
                if (target >= size) break
                val red = r.u8()
                val green = r.u8()
                val blue = r.u8()
                val alpha = if (bytesPerColor == 4) r.u8() else 255
                colors[target] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                target++
            }
        }
        return colors
    }

    /** Inflates a compressed cel, validating the exact expected size. */
    private fun inflateCel(zlib: ByteArray, expected: Int, frameIndex: Int, chunkOffset: Int): ByteArray {
        if (expected <= 0) {
            throw AsepriteDecodeException(
                "Frame $frameIndex compressed cel at offset $chunkOffset has non-positive size $expected"
            )
        }
        val inflater = Inflater()
        try {
            inflater.setInput(zlib)
            val out = ByteArray(expected)
            var written = 0
            while (written < out.size && !inflater.finished()) {
                val n = inflater.inflate(out, written, out.size - written)
                written += n
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw AsepriteDecodeException(
                        "Frame $frameIndex compressed cel at offset $chunkOffset: zlib stream truncated " +
                            "($written of $expected bytes)"
                    )
                }
            }
            if (written != expected) {
                throw AsepriteDecodeException(
                    "Frame $frameIndex compressed cel at offset $chunkOffset: zlib produced $written bytes, " +
                        "expected $expected"
                )
            }
            // The output buffer may be exactly full with the zlib trailer
            // (Adler-32) still pending: `inflate` with zero remaining capacity
            // never consumes it, which would spin. Drain through a one-byte
            // scratch buffer so stream termination is observable; surplus
            // output or truncation fail with precise diagnostics.
            val scratch = ByteArray(1)
            var probes = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(scratch, 0, 1)
                if (n > 0) {
                    throw AsepriteDecodeException(
                        "Frame $frameIndex compressed cel at offset $chunkOffset: zlib stream produces " +
                            "more than $expected bytes"
                    )
                }
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw AsepriteDecodeException(
                        "Frame $frameIndex compressed cel at offset $chunkOffset: zlib stream truncated " +
                            "after $expected bytes"
                    )
                }
                if (++probes > 3) {
                    throw AsepriteDecodeException(
                        "Frame $frameIndex compressed cel at offset $chunkOffset: zlib stream failed to " +
                            "terminate after $expected bytes"
                    )
                }
            }
            return out
        } catch (error: DataFormatException) {
            throw AsepriteDecodeException(
                "Frame $frameIndex compressed cel at offset $chunkOffset: corrupt zlib stream (${error.message})"
            )
        } finally {
            inflater.end()
        }
    }

    /** Reads a u16-length-prefixed UTF-8 string (Aseprite STRING). */
    private fun BinaryReader.stringUtf8(): String {
        val length = u16Le()
        val raw = bytes(length)
        return String(raw, Charsets.UTF_8)
    }

    // ---- build phase ---------------------------------------------------------

    /** Decoded cel content ready for placement, keyed by frame and layer. */
    private class DecodedCel(val width: Int, val height: Int, val argb: IntArray)

    private fun buildProject(
        width: Int,
        height: Int,
        depth: Int,
        layerOpacityValid: Boolean,
        aseLayers: List<AseLayer>,
        aseTags: List<AseTag>,
        aseFrames: List<AseFrame>,
        filePalette: IntArray?,
        paletteId: String,
        projectName: String,
    ): SpriteProject {
        // Layer id mapping: Aseprite chunk index -> project layer id (groups
        // resolve to null and their cels are skipped).
        val layerIdByAseIndex = HashMap<Int, Int>()
        val projectLayers = ArrayList<Layer>()
        for ((index, ase) in aseLayers.withIndex()) {
            if (ase.type != 0) continue // group layer: flattened away
            val opacity = if (layerOpacityValid) ase.opacity / 255f else 1f
            val visible = (ase.flags and 0x1) != 0
            val name = if (ase.name.isBlank()) "Layer ${projectLayers.size + 1}" else ase.name
            val id = projectLayers.size
            layerIdByAseIndex[index] = id
            projectLayers.add(Layer(id = id, name = name, opacity = opacity, visible = visible))
        }
        if (projectLayers.isEmpty()) {
            // File without layer chunks: everything lands on one implicit layer.
            layerIdByAseIndex[0] = 0
            projectLayers.add(Layer(id = 0, name = "Layer 1"))
        }

        // Decode every non-linked cel, then resolve links with cycle checks.
        val decoded = HashMap<Long, DecodedCel>()
        val visiting = HashSet<Long>()
        fun decodeCel(frameIndex: Int, layerIndex: Int): DecodedCel? {
            val key = frameIndex.toLong() * 65_536L + layerIndex
            decoded[key]?.let { return it }
            if (!visiting.add(key)) {
                throw AsepriteDecodeException(
                    "Linked cel cycle detected at (frame $frameIndex, layer $layerIndex)"
                )
            }
            try {
                val cel = aseFrames.getOrNull(frameIndex)?.cels?.firstOrNull { it.layerIndex == layerIndex }
                    ?: return null
                val result: DecodedCel = when {
                    cel.type == 1 -> {
                        val linked = decodeCel(cel.linkFrame, layerIndex)
                            ?: throw AsepriteDecodeException(
                                "Linked cel on layer $layerIndex points at frame ${cel.linkFrame} " +
                                    "which has no cel for that layer"
                            )
                        DecodedCel(linked.width, linked.height, linked.argb.copyOf())
                    }

                    else -> {
                        val px = cel.pixelBytes
                            ?: throw AsepriteDecodeException(
                                "Cel on layer $layerIndex of frame $frameIndex has no pixel data"
                            )
                        DecodedCel(cel.width, cel.height, celPixelsToArgb(px, cel.width, cel.height, depth))
                    }
                }
                decoded[key] = result
                return result
            } finally {
                visiting.remove(key)
            }
        }

        val projectFrames = ArrayList<Frame>(aseFrames.size)
        val distinctColors = LinkedHashMap<Int, Unit>()
        for ((frameIndex, aseFrame) in aseFrames.withIndex()) {
            val cels = HashMap<Int, PixelFrame>()
            for (cel in aseFrame.cels) {
                val indexOk = cel.layerIndex >= 0 &&
                    (cel.layerIndex < aseLayers.size || (cel.layerIndex == 0 && aseLayers.isEmpty()))
                if (!indexOk) {
                    throw AsepriteDecodeException(
                        "Frame $frameIndex cel references layer index ${cel.layerIndex} " +
                            "outside 0..${maxOf(aseLayers.size - 1, 0)}"
                    )
                }
                val source = decodeCel(frameIndex, cel.layerIndex) ?: continue
                val layerId = layerIdByAseIndex[cel.layerIndex] ?: continue // group cel: skipped
                val placed = placeCel(source, cel.x, cel.y, cel.opacity, width, height)
                if (placed != null) {
                    cels[layerId] = placed
                    for (argb in placed.pixels) {
                        if ((argb ushr 24) != 0) distinctColors.putIfAbsent(argb, Unit)
                    }
                }
            }
            val duration = aseFrame.durationMs
            projectFrames.add(
                Frame(
                    id = frameIndex,
                    cels = cels,
                    durationMs = if (duration in 1..60_000) duration else null,
                )
            )
        }

        val tags = aseTags.map { tag ->
            AnimationTag(name = tag.name, startFrame = tag.fromFrame, endFrame = tag.toFrame)
        }

        val paletteColors = when {
            filePalette != null && filePalette.isNotEmpty() -> filePalette
            distinctColors.isNotEmpty() -> {
                if (distinctColors.size > EXTRACTED_PALETTE_CAP) {
                    distinctColors.keys.take(EXTRACTED_PALETTE_CAP).toIntArray()
                } else {
                    distinctColors.keys.toIntArray()
                }
            }

            else -> intArrayOf(0xFF000000.toInt()) // fully transparent sprite
        }
        val palette = Palette(
            id = paletteId,
            name = "$projectName palette",
            colors = paletteColors,
            source = PaletteSource.IMPORTED,
        )

        val firstDuration = aseFrames.first().durationMs
        val fps = if (firstDuration in 1..60_000) {
            (1000 / firstDuration).coerceIn(1, 24)
        } else {
            12
        }
        val activeLayerId = projectLayers.last().id

        return SpriteProject(
            id = SpriteFactory.defaultId(),
            name = projectName,
            width = width,
            height = height,
            layers = projectLayers,
            frames = projectFrames,
            activeLayerId = activeLayerId,
            activeFrameIndex = 0,
            palette = palette,
            fps = fps,
            tags = tags,
            nextLayerId = projectLayers.size,
            nextFrameId = projectFrames.size,
        )
    }

    /**
     * Converts raw Aseprite cel bytes to ARGB: 32 bpp interleaved
     * `r, g, b, a` per pixel; 16 bpp legacy grayscale (u16le value scaled
     * with `>> 8`, opaque alpha).
     */
    private fun celPixelsToArgb(px: ByteArray, width: Int, height: Int, depth: Int): IntArray {
        val out = IntArray(width * height)
        when (depth) {
            32 -> {
                var i = 0
                for (pixel in out.indices) {
                    val r = px[i++].toInt() and 0xFF
                    val g = px[i++].toInt() and 0xFF
                    val b = px[i++].toInt() and 0xFF
                    val a = px[i++].toInt() and 0xFF
                    out[pixel] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            16 -> {
                var i = 0
                for (pixel in out.indices) {
                    val v = ((px[i++].toInt() and 0xFF)) or ((px[i++].toInt() and 0xFF) shl 8)
                    val g = v ushr 8
                    out[pixel] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }

            else -> throw AsepriteDecodeException("Unsupported cel depth $depth")
        }
        return out
    }

    /**
     * Places a decoded cel at (`x`, `y`) on a transparent canvas-sized
     * raster, clipping overhang, and bakes the cel opacity into pixel alpha
     * (`alpha * opacity / 255`). Returns null when nothing remains visible.
     */
    private fun placeCel(source: DecodedCel, x: Int, y: Int, opacity: Int, width: Int, height: Int): PixelFrame? {
        val target = IntArray(width * height)
        var anyPixel = false
        for (sy in 0 until source.height) {
            val ty = y + sy
            if (ty < 0 || ty >= height) continue
            for (sx in 0 until source.width) {
                val tx = x + sx
                if (tx < 0 || tx >= width) continue
                val argb = source.argb[sy * source.width + sx]
                if (argb == 0) continue
                val alpha = (argb ushr 24) * opacity / 255
                if (alpha == 0) continue
                target[ty * width + tx] = (alpha shl 24) or (argb and 0x00FFFFFF)
                anyPixel = true
            }
        }
        if (!anyPixel) return null
        return PixelFrame.of(width, height, target)
    }
}
