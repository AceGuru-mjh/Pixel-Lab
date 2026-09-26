package com.pixellab.core.project

import com.pixellab.core.model.AnimationTag
import com.pixellab.core.model.Frame
import com.pixellab.core.model.Layer
import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject

/**
 * Compact JSON serialization of [SpriteProject] — the project file format of
 * Pixel Lab, version 2.
 *
 * pixel-core cannot depend on the pixel-mcp JSON tree (module dependency
 * direction), so this object carries its own minimal, self-contained JSON
 * machinery: a sealed value tree ([PValue]), a strict recursive-descent
 * parser ([JsonParser]) and a compact writer ([JsonWriter]). The grammar is
 * plain JSON: objects, arrays, strings with the full escape set, numbers
 * (int/frac/exponent) and the three literals. Both directions enforce a
 * nesting depth limit of [MAX_DEPTH] so hostile payloads cannot exhaust the
 * JVM stack.
 *
 * ## Wire format (version 2)
 *
 * ```json
 * {
 *   "version": 2,
 *   "id": "proj-x", "name": "hero", "width": 16, "height": 16,
 *   "fps": 12, "activeLayerId": 0, "activeFrameIndex": 0,
 *   "nextLayerId": 1, "nextFrameId": 1,
 *   "palette": {"id": "pico-8", "name": "PICO-8", "source": "BUILTIN",
 *               "colors": [-16776961]},
 *   "layers": [{"id": 0, "name": "Layer 1", "opacity": 1.0,
 *               "visible": true, "locked": false}],
 *   "frames": [{"id": 0, "durationMs": 100,
 *               "cels": {"0": [-16776961, 0]}}],
 *   "tags": [{"name": "idle", "startFrame": 0, "endFrame": 3}]
 * }
 * ```
 *
 * * Cel pixel arrays are row-major ARGB integers (alpha `0xFF` makes the int
 *   negative — valid JSON numbers handle that). `durationMs` is omitted
 *   when null.
 * * Cels are emitted in layer-stack order (only layers that actually have a
 *   cel in the frame), frames in timeline order — the output is fully
 *   deterministic.
 *
 * ## Guarantees
 *
 * * **Byte-stable round trip**: `save(load(save(p))) == save(p)` for every
 *   valid project — field order, frame/layer order and cel order are fixed,
 *   integers round-trip through their decimal text, and layer opacities are
 *   written with the shortest `Float.toString` form that parses back to the
 *   exact same float.
 * * **Full validation on load**: dimensions, layer/frame stacks, id
 *   uniqueness, active-layer/frame domains, cel sizes, palette size and tag
 *   domains are checked before the model is constructed, with clear
 *   [IllegalArgumentException] messages that carry the offending path
 *   (e.g. `project document: frames[3].cels[2] length 63 does not match
 *   canvas 8x8`).
 * * **Sizes 1x1 through 64x64 (and beyond)** are supported: nothing in the
 *   codec assumes a minimum or maximum canvas edge.
 */
object ProjectCodec {

    /** Wire format version written by [save] and required by [load]. */
    const val VERSION: Int = 2

    /** Maximum JSON nesting depth accepted by the parser and writer. */
    const val MAX_DEPTH: Int = 64

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Serializes [project] to compact JSON text (no whitespace).
     *
     * The output is deterministic: identical projects serialize to
     * byte-identical text, and `save(load(save(p))) == save(p)`.
     */
    fun save(project: SpriteProject): String = JsonWriter.write(projectToValue(project))

    /**
     * Parses and validates [text] as a version-2 project document.
     *
     * @param text JSON text produced by [save] (or hand-written in the same
     *   shape).
     * @return the reconstructed [SpriteProject].
     * @throws IllegalArgumentException when the text is not valid JSON, is
     *   not a version-2 document, or violates any documented invariant —
     *   the message names the offending path, e.g.
     *   `project document: frames[3].cels[2] length 64 does not match canvas 8x8`.
     */
    fun load(text: String): SpriteProject = valueToProject(JsonParser(text).parse())

    /**
     * Loads [text], falling back to [fallback] when parsing or validation
     * fails (malformed JSON, wrong version, invariant violation). Never
     * throws for bad documents — the fallback is returned unchanged so
     * callers can keep a working session alive after a corrupt file.
     */
    fun loadOrDefault(text: String, fallback: SpriteProject): SpriteProject {
        return try {
            load(text)
        } catch (error: IllegalArgumentException) {
            fallback
        } catch (error: IllegalStateException) {
            fallback
        }
    }

    // ------------------------------------------------------------------
    // Serialization (project -> PValue)
    // ------------------------------------------------------------------

    /** Builds the JSON value tree of [project] in the fixed wire order. */
    private fun projectToValue(project: SpriteProject): PValue {
        val palette = PObject()
        palette.put("id", PString(project.palette.id))
        palette.put("name", PString(project.palette.name))
        palette.put("source", PString(project.palette.source.name))
        palette.put("colors", PArray(project.palette.colors.map<PValue> { PNumber.of(it) }))

        val layers = PArray(project.layers.map<Layer, PValue> { layer ->
            val obj = PObject()
            obj.put("id", PNumber.of(layer.id))
            obj.put("name", PString(layer.name))
            obj.put("opacity", PNumber.of(layer.opacity))
            obj.put("visible", PBool(layer.visible))
            obj.put("locked", PBool(layer.locked))
            obj
        })

        val frames = PArray(project.frames.map<Frame, PValue> { frame ->
            val obj = PObject()
            obj.put("id", PNumber.of(frame.id))
            frame.durationMs?.let { obj.put("durationMs", PNumber.of(it)) }
            val cels = PObject()
            // Layer-stack order keeps the output deterministic and makes the
            // round trip reproduce the exact same insertion order.
            for (layer in project.layers) {
                val cel = frame.cels[layer.id] ?: continue
                cels.put(layer.id.toString(), PArray(cel.pixels.map<PValue> { PNumber.of(it) }))
            }
            obj.put("cels", cels)
            obj
        })

        val tags = PArray(project.tags.map<AnimationTag, PValue> { tag ->
            val obj = PObject()
            obj.put("name", PString(tag.name))
            obj.put("startFrame", PNumber.of(tag.startFrame))
            obj.put("endFrame", PNumber.of(tag.endFrame))
            obj
        })

        val root = PObject()
        root.put("version", PNumber.of(VERSION))
        root.put("id", PString(project.id))
        root.put("name", PString(project.name))
        root.put("width", PNumber.of(project.width))
        root.put("height", PNumber.of(project.height))
        root.put("fps", PNumber.of(project.fps))
        root.put("activeLayerId", PNumber.of(project.activeLayerId))
        root.put("activeFrameIndex", PNumber.of(project.activeFrameIndex))
        root.put("nextLayerId", PNumber.of(project.nextLayerId))
        root.put("nextFrameId", PNumber.of(project.nextFrameId))
        root.put("palette", palette)
        root.put("layers", layers)
        root.put("frames", frames)
        root.put("tags", tags)
        return root
    }

    // ------------------------------------------------------------------
    // Deserialization (PValue -> project) with full validation
    // ------------------------------------------------------------------

    /** Converts a parsed [root] tree into a [SpriteProject], validating everything. */
    private fun valueToProject(root: PValue): SpriteProject {
        val rootObj = root as? PObject
            ?: throw invalid("document root", "must be a JSON object")

        val version = intAt(rootObj, "version", "version")
        if (version != VERSION) {
            throw invalid("version", "must be $VERSION (was $version)")
        }

        val id = stringAt(rootObj, "id", "id")
        val name = stringAt(rootObj, "name", "name")
        val width = intAt(rootObj, "width", "width")
        val height = intAt(rootObj, "height", "height")
        if (width <= 0 || height <= 0) {
            throw invalid("width/height", "must be positive (was ${width}x${height})")
        }
        val fps = intAt(rootObj, "fps", "fps")
        if (fps !in 1..24) {
            throw invalid("fps", "must be in [1, 24] (was $fps)")
        }
        val activeLayerId = intAt(rootObj, "activeLayerId", "activeLayerId")
        val activeFrameIndex = intAt(rootObj, "activeFrameIndex", "activeFrameIndex")
        val nextLayerId = intAt(rootObj, "nextLayerId", "nextLayerId")
        if (nextLayerId < 0) {
            throw invalid("nextLayerId", "must be >= 0 (was $nextLayerId)")
        }
        val nextFrameId = intAt(rootObj, "nextFrameId", "nextFrameId")
        if (nextFrameId < 0) {
            throw invalid("nextFrameId", "must be >= 0 (was $nextFrameId)")
        }

        // ---- palette ----
        val paletteObj = rootObj["palette"] as? PObject
            ?: throw invalid("palette", "must be an object")
        val paletteId = stringAt(paletteObj, "id", "palette.id")
        val paletteName = stringAt(paletteObj, "name", "palette.name")
        val sourceName = stringAt(paletteObj, "source", "palette.source")
        val source = try {
            PaletteSource.valueOf(sourceName)
        } catch (error: IllegalArgumentException) {
            throw invalid(
                "palette.source",
                "'$sourceName' is not one of ${PaletteSource.entries.joinToString()}",
            )
        }
        val colorsArray = paletteObj["colors"] as? PArray
            ?: throw invalid("palette.colors", "must be an array")
        if (colorsArray.items.isEmpty()) {
            throw invalid("palette.colors", "must contain at least 1 color")
        }
        val colors = IntArray(colorsArray.items.size)
        for ((index, item) in colorsArray.items.withIndex()) {
            colors[index] = intElement(item, "palette.colors[$index]")
        }

        // ---- layers ----
        val layersArray = rootObj["layers"] as? PArray
            ?: throw invalid("layers", "must be an array")
        if (layersArray.items.isEmpty()) {
            throw invalid("layers", "must contain at least 1 layer")
        }
        val layerIds = HashSet<Int>(layersArray.items.size)
        val layers = layersArray.items.mapIndexed { index, item ->
            val path = "layers[$index]"
            val obj = item as? PObject ?: throw invalid(path, "must be an object")
            val layerId = intAt(obj, "id", "$path.id")
            val layerName = stringAt(obj, "name", "$path.name")
            if (layerName.isBlank()) {
                throw invalid("$path.name", "must not be blank")
            }
            val opacity = floatAt(obj, "opacity", "$path.opacity")
            if (opacity.isNaN() || opacity < 0f || opacity > 1f) {
                throw invalid("$path.opacity", "must be in [0, 1] (was $opacity)")
            }
            val visible = boolAt(obj, "visible", "$path.visible")
            val locked = boolAt(obj, "locked", "$path.locked")
            if (!layerIds.add(layerId)) {
                throw invalid("$path.id", "duplicate layer id $layerId")
            }
            Layer(id = layerId, name = layerName, opacity = opacity, visible = visible, locked = locked)
        }
        if (activeLayerId !in layerIds) {
            throw invalid("activeLayerId", "$activeLayerId is not in the layer stack $layerIds")
        }

        // ---- frames ----
        val framesArray = rootObj["frames"] as? PArray
            ?: throw invalid("frames", "must be an array")
        if (framesArray.items.isEmpty()) {
            throw invalid("frames", "must contain at least 1 frame")
        }
        val frameIds = HashSet<Int>(framesArray.items.size)
        val expectedPixels = width.toLong() * height.toLong()
        val frames = framesArray.items.mapIndexed { index, item ->
            val path = "frames[$index]"
            val obj = item as? PObject ?: throw invalid(path, "must be an object")
            val frameId = intAt(obj, "id", "$path.id")
            if (!frameIds.add(frameId)) {
                throw invalid("$path.id", "duplicate frame id $frameId")
            }
            var durationMs: Int? = null
            if (obj["durationMs"] != null) {
                durationMs = intAt(obj, "durationMs", "$path.durationMs")
                if (durationMs !in 1..60_000) {
                    throw invalid("$path.durationMs", "must be in [1, 60000] (was $durationMs)")
                }
            }
            val celsObj = obj["cels"] as? PObject
                ?: throw invalid("$path.cels", "must be an object")
            val cels = LinkedHashMap<Int, PixelFrame>()
            for ((layerIdText, celValue) in celsObj.entries) {
                val layerId = layerIdText.toIntOrNull()
                    ?: throw invalid("$path.cels", "key '$layerIdText' is not an integer layer id")
                if (layerId !in layerIds) {
                    throw invalid("$path.cels", "layer id $layerId is not in the layer stack")
                }
                val celPath = "$path.cels[$layerId]"
                val pixelsArray = celValue as? PArray
                    ?: throw invalid(celPath, "must be an array of ARGB integers")
                if (pixelsArray.items.size.toLong() != expectedPixels) {
                    throw invalid(
                        celPath,
                        "length ${pixelsArray.items.size} does not match canvas ${width}x${height}",
                    )
                }
                val pixels = IntArray(pixelsArray.items.size)
                for ((pixelIndex, pixelValue) in pixelsArray.items.withIndex()) {
                    pixels[pixelIndex] = intElement(pixelValue, "$celPath[$pixelIndex]")
                }
                cels[layerId] = PixelFrame.of(width, height, pixels)
            }
            Frame(id = frameId, cels = cels, durationMs = durationMs)
        }
        if (activeFrameIndex !in frames.indices) {
            throw invalid("activeFrameIndex", "$activeFrameIndex out of bounds (${frames.size} frames)")
        }

        // ---- tags ----
        val tagsArray = rootObj["tags"] as? PArray
            ?: throw invalid("tags", "must be an array")
        val tags = tagsArray.items.mapIndexed { index, item ->
            val path = "tags[$index]"
            val obj = item as? PObject ?: throw invalid(path, "must be an object")
            val tagName = stringAt(obj, "name", "$path.name")
            if (tagName.isBlank()) {
                throw invalid("$path.name", "must not be blank")
            }
            val startFrame = intAt(obj, "startFrame", "$path.startFrame")
            val endFrame = intAt(obj, "endFrame", "$path.endFrame")
            if (startFrame < 0) {
                throw invalid("$path.startFrame", "must be >= 0 (was $startFrame)")
            }
            if (endFrame < startFrame) {
                throw invalid("$path.endFrame", "$endFrame must be >= startFrame $startFrame")
            }
            if (endFrame >= frames.size) {
                throw invalid("$path.endFrame", "$endFrame out of bounds (${frames.size} frames)")
            }
            AnimationTag(name = tagName, startFrame = startFrame, endFrame = endFrame)
        }

        return SpriteProject(
            id = id,
            name = name,
            width = width,
            height = height,
            layers = layers,
            frames = frames,
            activeLayerId = activeLayerId,
            activeFrameIndex = activeFrameIndex,
            palette = Palette(id = paletteId, name = paletteName, colors = colors, source = source),
            fps = fps,
            tags = tags,
            nextLayerId = nextLayerId,
            nextFrameId = nextFrameId,
        )
    }

    /** Formats a validation failure with its document [path]. */
    private fun invalid(path: String, message: String): IllegalArgumentException =
        IllegalArgumentException("project document: $path $message")

    // ---- typed field accessors (throwing with path context) ----

    /** String field of [obj] at [key], reported as [path] on failure. */
    private fun stringAt(obj: PObject, key: String, path: String): String {
        val value = obj[key] as? PString ?: throw invalid(path, "must be a string")
        return value.value
    }

    /** Integer field of [obj] at [key] (fractional values rejected). */
    private fun intAt(obj: PObject, key: String, path: String): Int {
        val value = obj[key] as? PNumber ?: throw invalid(path, "must be a number")
        return value.intValue(path)
    }

    /** Float field of [obj] at [key]. */
    private fun floatAt(obj: PObject, key: String, path: String): Float {
        val value = obj[key] as? PNumber ?: throw invalid(path, "must be a number")
        return value.floatValue(path)
    }

    /** Boolean field of [obj] at [key]. */
    private fun boolAt(obj: PObject, key: String, path: String): Boolean {
        val value = obj[key] as? PBool ?: throw invalid(path, "must be a boolean")
        return value.value
    }

    /** A single integer value, reported as [path] on failure. */
    private fun intElement(value: PValue, path: String): Int =
        (value as? PNumber)?.intValue(path) ?: throw invalid(path, "must be a number")

    // ------------------------------------------------------------------
    // JSON value tree
    // ------------------------------------------------------------------

    /** Sealed JSON value tree used between [JsonParser] and [JsonWriter]. */
    private sealed class PValue

    /** The JSON `null` literal. */
    private object PNull : PValue()

    /** The JSON `true` / `false` literals. */
    private data class PBool(val value: Boolean) : PValue()

    /** A JSON string value. */
    private data class PString(val value: String) : PValue()

    /**
     * A JSON number kept as its raw source text so integer and float values
     * round-trip byte-for-byte; conversion helpers reject values that do
     * not fit the requested type.
     */
    private data class PNumber(val raw: String) : PValue() {

        /** Integral 32-bit read; fractional or out-of-range values throw. */
        fun intValue(path: String): Int {
            val asLong = raw.toLongOrNull()
            if (asLong != null) {
                if (asLong < Int.MIN_VALUE || asLong > Int.MAX_VALUE) {
                    throw invalid(path, "must be a 32-bit integer (was $raw)")
                }
                return asLong.toInt()
            }
            val value = parseAsDouble(path)
            if (value == Math.floor(value) &&
                value >= Int.MIN_VALUE.toDouble() && value <= Int.MAX_VALUE.toDouble()
            ) {
                return value.toInt()
            }
            throw invalid(path, "must be a 32-bit integer (was $raw)")
        }

        /** Float read; rejects NaN and infinities. */
        fun floatValue(path: String): Float {
            val value = parseAsDouble(path)
            if (value.isNaN() || value.isInfinite()) {
                throw invalid(path, "must be a finite number (was $raw)")
            }
            return value.toFloat()
        }

        private fun parseAsDouble(path: String): Double =
            raw.toDoubleOrNull() ?: throw invalid(path, "'$raw' is not a valid number")

        companion object {
            /** Number node with loss-free integral text. */
            fun of(value: Int): PNumber = PNumber(value.toString())

            /**
             * Number node for a float: `Float.toString` emits the shortest
             * decimal that round-trips, so save/parse/save is byte-stable.
             */
            fun of(value: Float): PNumber = PNumber(value.toString())
        }
    }

    /** A JSON array. */
    private class PArray(val items: List<PValue>) : PValue()

    /** A JSON object with insertion-ordered entries. */
    private class PObject : PValue() {
        private val map = LinkedHashMap<String, PValue>()

        /** The entries in insertion order. */
        val entries: Set<Map.Entry<String, PValue>> get() = map.entries

        /** Stores [value] under [key] (insertion order preserved). */
        fun put(key: String, value: PValue) {
            map[key] = value
        }

        /** The value under [key], or null when absent. */
        operator fun get(key: String): PValue? = map[key]
    }

    // ------------------------------------------------------------------
    // Writer
    // ------------------------------------------------------------------

    /** Compact JSON writer with full string escaping and a depth guard. */
    private object JsonWriter {

        /** Renders [value] as a single-line JSON document. */
        fun write(value: PValue): String {
            val out = StringBuilder()
            writeValue(out, value, 0)
            return out.toString()
        }

        private fun writeValue(out: StringBuilder, value: PValue, depth: Int) {
            if (depth > MAX_DEPTH) {
                throw IllegalStateException("JSON nesting deeper than $MAX_DEPTH levels")
            }
            when (value) {
                is PNull -> out.append("null")
                is PBool -> out.append(if (value.value) "true" else "false")
                is PString -> writeString(out, value.value)
                is PNumber -> out.append(value.raw)
                is PArray -> {
                    out.append('[')
                    for ((index, item) in value.items.withIndex()) {
                        if (index > 0) out.append(',')
                        writeValue(out, item, depth + 1)
                    }
                    out.append(']')
                }
                is PObject -> {
                    out.append('{')
                    var first = true
                    for ((key, entry) in value.entries) {
                        if (!first) out.append(',')
                        first = false
                        writeString(out, key)
                        out.append(':')
                        writeValue(out, entry, depth + 1)
                    }
                    out.append('}')
                }
            }
        }

        /** Escapes quotes, backslash, short escapes and control characters. */
        private fun writeString(out: StringBuilder, value: String) {
            out.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    else -> if (ch < ' ') {
                        out.append("\\u").append(hex4(ch.code))
                    } else {
                        out.append(ch)
                    }
                }
            }
            out.append('"')
        }

        private fun hex4(code: Int): String = code.toString(16).padStart(4, '0')
    }

    // ------------------------------------------------------------------
    // Parser
    // ------------------------------------------------------------------

    /**
     * Strict recursive-descent JSON parser. Every failure is an
     * [IllegalArgumentException] naming the offset, so [load] can surface a
     * precise message.
     */
    private class JsonParser(val text: String) {
        private var pos: Int = 0

        /** Parses the whole document; trailing content is rejected. */
        fun parse(): PValue {
            val value = parseValue(0)
            skipWhitespace()
            if (!atEnd()) {
                throw IllegalArgumentException("JSON error: trailing content at offset $pos")
            }
            return value
        }

        private fun atEnd(): Boolean = pos >= text.length

        private fun skipWhitespace() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun parseValue(depth: Int): PValue {
            if (depth > MAX_DEPTH) {
                throw IllegalArgumentException("JSON error: nesting deeper than $MAX_DEPTH levels")
            }
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("JSON error: unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> PString(parseStringBody())
                't' -> parseLiteral("true", PBool(true))
                'f' -> parseLiteral("false", PBool(false))
                'n' -> parseLiteral("null", PNull)
                else -> if (c == '-' || c in '0'..'9') {
                    parseNumber()
                } else {
                    throw IllegalArgumentException("JSON error: unexpected character '$c' at offset $pos")
                }
            }
        }

        private fun parseObject(depth: Int): PObject {
            pos++ // consume '{'
            val obj = PObject()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return obj
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') {
                    throw IllegalArgumentException("JSON error: expected object key at offset $pos")
                }
                val key = parseStringBody()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') {
                    throw IllegalArgumentException("JSON error: expected ':' at offset $pos")
                }
                pos++
                obj.put(key, parseValue(depth + 1))
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("JSON error: unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return obj
                    }
                    else -> throw IllegalArgumentException("JSON error: expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(depth: Int): PArray {
            pos++ // consume '['
            val items = ArrayList<PValue>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return PArray(items)
            }
            while (true) {
                items.add(parseValue(depth + 1))
                skipWhitespace()
                if (atEnd()) throw IllegalArgumentException("JSON error: unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return PArray(items)
                    }
                    else -> throw IllegalArgumentException("JSON error: expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseStringBody(): String {
            pos++ // consume opening '"'
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("JSON error: unterminated string")
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return out.toString()
                    }
                    '\\' -> {
                        pos++
                        if (atEnd()) throw IllegalArgumentException("JSON error: unterminated escape")
                        when (val e = text[pos]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) {
                                    throw IllegalArgumentException("JSON error: truncated \\u escape")
                                }
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("JSON error: invalid \\u escape '$hex'")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("JSON error: invalid escape '\\$e'")
                        }
                        pos++
                    }
                    else -> {
                        if (c < ' ') {
                            throw IllegalArgumentException("JSON error: unescaped control character at offset $pos")
                        }
                        out.append(c)
                        pos++
                    }
                }
            }
        }

        private fun parseLiteral(word: String, value: PValue): PValue {
            if (!text.startsWith(word, pos)) {
                throw IllegalArgumentException("JSON error: invalid literal at offset $pos")
            }
            pos += word.length
            return value
        }

        private fun parseNumber(): PNumber {
            val start = pos
            if (text[pos] == '-') pos++
            if (atEnd()) throw IllegalArgumentException("JSON error: unterminated number")
            if (text[pos] == '0') {
                pos++
            } else if (text[pos] in '1'..'9') {
                consumeDigits()
            } else {
                throw IllegalArgumentException("JSON error: invalid number at offset $start")
            }
            if (!atEnd() && text[pos] == '.') {
                pos++
                if (atEnd() || text[pos] !in '0'..'9') {
                    throw IllegalArgumentException("JSON error: invalid fraction at offset $pos")
                }
                consumeDigits()
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                if (atEnd() || text[pos] !in '0'..'9') {
                    throw IllegalArgumentException("JSON error: invalid exponent at offset $pos")
                }
                consumeDigits()
            }
            val raw = text.substring(start, pos)
            val value = raw.toDoubleOrNull()
            if (value == null || value.isNaN() || value.isInfinite()) {
                throw IllegalArgumentException("JSON error: invalid number '$raw'")
            }
            return PNumber(raw)
        }

        private fun consumeDigits() {
            while (!atEnd() && text[pos] in '0'..'9') pos++
        }
    }
}
