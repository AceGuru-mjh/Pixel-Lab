package com.pixellab.core.pipeline

import com.pixellab.core.convert.DitherAlgorithm
import com.pixellab.core.convert.QuantizeAlgorithm
import com.pixellab.core.tools.Connectivity
import com.pixellab.core.tools.OutlineMode

/**
 * One stage of a processing [Recipe]. Steps are immutable value objects —
 * the wire vocabulary between recipe authors (JSON, see [RecipeParser]) and
 * the executor ([PipelineRunner]).
 *
 * Each subclass validates its own parameters in `init` with
 * [IllegalArgumentException] (fail-fast, consistent with the rest of the
 * library), so a [Recipe] can never hold a step that the runner would have
 * to reject. See [RecipeParser] for the JSON spelling of every step.
 */
sealed class RecipeStep {

    /** Nearest-neighbor integer upscale by [factor] (canvas grows `factor`x). */
    data class ScaleStep(val factor: Int) : RecipeStep() {
        init {
            require(factor >= 1) { "scale factor must be >= 1 (was $factor)" }
        }
    }

    /**
     * Palette extraction: reduce all frames to at most [maxColors] colors
     * with [algorithm] (a *shared* palette across frames, so animations
     * stay consistent).
     */
    data class QuantizeStep(
        val algorithm: QuantizeAlgorithm,
        val maxColors: Int,
    ) : RecipeStep() {
        init {
            require(maxColors in 2..256) { "maxColors must be in [2, 256] (was $maxColors)" }
        }
    }

    /**
     * Error-diffusion / ordered dithering onto a palette. The palette is
     * resolved at run time: [paletteId] when given (a
     * `com.pixellab.core.palette.BuiltInPalettes` id), otherwise the
     * distinct opaque colors of the frames entering the step (deterministic
     * ascending order). [strength] mirrors the convert-pipeline intensity
     * semantics: `null` means full strength (`1.0`), otherwise `0..1` with
     * NaN treated as 0.
     */
    data class DitherStep(
        val algorithm: DitherAlgorithm,
        val strength: Float? = null,
        val paletteId: String? = null,
    ) : RecipeStep() {
        init {
            require(strength == null || (strength >= 0f && strength <= 1f)) {
                "dither strength must be null or in [0, 1] (was $strength)"
            }
        }
    }

    /**
     * Snap every opaque pixel to the nearest color of the built-in palette
     * [paletteId] (Lab nearest-neighbor). `null` keeps the colors as they
     * are — the step is then an explicit "no recolor" marker.
     */
    data class PaletteMapStep(val paletteId: String? = null) : RecipeStep()

    /** One-pixel outline pass; see [com.pixellab.core.tools.OutlineShading.outline]. */
    data class OutlineStep(
        val color: Int,
        val mode: OutlineMode = OutlineMode.OUTER,
        val connectivity: Connectivity = Connectivity.EIGHT,
    ) : RecipeStep()

    /**
     * Crop every frame to the bounding box of the non-transparent content.
     * For a multi-frame input the union box is used so the frames stay
     * registered (frame alignment is animation-critical). Carries no
     * parameters, hence a singleton.
     */
    data object TrimStep : RecipeStep()

    /**
     * Per-channel posterization: each RGB channel is quantized to [levels]
     * evenly spaced values (`levels = 2` → pure black/white channels).
     * Alpha is preserved.
     */
    data class PosterizeStep(val levels: Int) : RecipeStep() {
        init {
            require(levels in 2..255) { "posterize levels must be in [2, 255] (was $levels)" }
        }
    }

    /**
     * Background removal: pixels whose alpha is *strictly below* [tolerance]
     * become fully transparent (0x00000000). `tolerance = 0` only removes
     * already-transparent pixels.
     */
    data class BgRemoveStep(val tolerance: Int) : RecipeStep() {
        init {
            require(tolerance in 0..255) { "bg-remove tolerance must be in [0, 255] (was $tolerance)" }
        }
    }
}

/**
 * A named, ordered list of [RecipeStep]s — the document format of the
 * processing pipeline.
 *
 * ```
 * val recipe = RecipeParser.parse(json)
 * val out = PipelineRunner.run(recipe, frames)
 * ```
 */
data class Recipe(
    val name: String = "recipe",
    val steps: List<RecipeStep> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "recipe name must not be blank" }
    }
}

/**
 * Parses the compact recipe JSON into a [Recipe].
 *
 * Accepted document shape:
 * ```json
 * {
 *   "name": "gameboy-ify",
 *   "steps": [
 *     {"type": "scale", "factor": 2},
 *     {"type": "quantize", "algorithm": "mediancut", "maxColors": 16},
 *     {"type": "dither", "algorithm": "floydsteinberg", "strength": 0.8},
 *     {"type": "palette-map", "palette": "pico-8"},
 *     {"type": "outline", "color": "#FF000000", "mode": "outer", "connectivity": "eight"},
 *     {"type": "trim"},
 *     {"type": "posterize", "levels": 4},
 *     {"type": "bg-remove", "tolerance": 40}
 *   ]
 * }
 * ```
 *
 * Rules:
 *  * `"name"` is optional (default `"recipe"`); `"steps"` is required and
 *    must be an array of step objects — an empty array is the identity
 *    recipe.
 *  * **Colors** are `#RRGGBB` (alpha forced to 0xFF) or `#RRGGBBAA` hex
 *    strings, case-insensitive, or plain JSON integers holding raw ARGB.
 *  * **Algorithm names** are matched after lowercasing and stripping
 *    `-`, `_` and spaces: quantize `mediancut`/`median-cut`, `kmeans`,
 *    `octree`; dither `none`, `floydsteinberg`, `atkinson`, `bayer2`
 *    (=`bayer2x2`), `bayer4`, `bayer8`, `checkerboard`.
 *  * **Modes** `outer`/`inner`; **connectivity** `four`/`4`/`eight`/`8`.
 *  * Unknown step types, unknown parameters inside a step, wrong value
 *    types, duplicate keys and malformed JSON all throw
 *    [IllegalArgumentException] with the step index and the source offset
 *    of the offending object, e.g.
 *    `steps[3] (offset 118): unknown parameter 'blur' for step 'outline'`.
 *
 * The parser is a self-contained recursive-descent JSON reader (the
 * [com.pixellab.core.project.ProjectCodec] machinery is deliberately
 * private to that package); it accepts the full JSON grammar — strings
 * with the complete escape set, integral/fractional/exponent numbers,
 * `true`/`false`/`null`, nesting up to 64 levels — and rejects trailing
 * garbage after the root value.
 */
object RecipeParser {

    /** Parses [json] into a validated [Recipe]. */
    fun parse(json: String): Recipe {
        val root = Lexer(json).parseDocument()
        val rootObject = root as? JObject
            ?: throw IllegalArgumentException(
                "recipe document must be a JSON object (found ${describe(root)} at offset 0)",
            )
        var name = DEFAULT_NAME
        var stepsValue: JArray? = null
        for ((key, value) in rootObject.entries) {
            when (key) {
                "name" -> name = when (value) {
                    is JString -> value.string
                    is JNull -> DEFAULT_NAME
                    else -> throw typeError(rootObject, "name must be a string", value)
                }
                "steps" -> stepsValue = value as? JArray
                    ?: throw typeError(rootObject, "steps must be an array", value)
                else -> throw IllegalArgumentException(
                    "${rootObject.where()}: unknown top-level parameter '$key'",
                )
            }
        }
        val stepsArray = stepsValue
            ?: throw IllegalArgumentException("${rootObject.where()}: missing required 'steps' array")
        val steps = ArrayList<RecipeStep>(stepsArray.items.size)
        for ((index, item) in stepsArray.items.withIndex()) {
            val obj = item as? JObject
                ?: throw IllegalArgumentException(
                    "steps[$index]: must be an object (found ${describe(item)})",
                )
            steps.add(parseStep(index, obj))
        }
        return Recipe(name, steps)
    }

    /** Decodes one step object; every failure message carries its position. */
    private fun parseStep(index: Int, obj: JObject): RecipeStep {
        val typeValue = obj["type"] ?: throw IllegalArgumentException("${obj.where()}: missing 'type'")
        val type = (typeValue as? JString)?.string
            ?: throw IllegalArgumentException("${obj.where()}: 'type' must be a string")
        fun requireInt(field: String): Int {
            val value = obj[field] ?: throw IllegalArgumentException("${obj.where()}: missing '$field' for step '$type'")
            return value.asInt { reason ->
                IllegalArgumentException("${obj.where()}: '$field' $reason for step '$type'")
            }
        }
        fun optionalInt(field: String): Int? {
            val value = obj[field] ?: return null
            return value.asInt { reason ->
                IllegalArgumentException("${obj.where()}: '$field' $reason for step '$type'")
            }
        }
        fun optionalFloat(field: String): Float? {
            val value = obj[field] ?: return null
            return value.asFloat { reason ->
                IllegalArgumentException("${obj.where()}: '$field' $reason for step '$type'")
            }
        }
        fun optionalString(field: String): String? {
            val value = obj[field] ?: return null
            return (value as? JString)?.string
                ?: throw IllegalArgumentException("${obj.where()}: '$field' must be a string for step '$type'")
        }
        val allowed = ArrayList<String>()
        val step: RecipeStep = when (type) {
            "scale" -> {
                allowed += listOf("type", "factor")
                RecipeStep.ScaleStep(factor = requireInt("factor"))
            }
            "quantize" -> {
                allowed += listOf("type", "algorithm", "maxColors")
                RecipeStep.QuantizeStep(
                    algorithm = parseQuantizeAlgorithm(
                        optionalString("algorithm") ?: run {
                            throw IllegalArgumentException("${obj.where()}: missing 'algorithm' for step 'quantize'")
                        },
                        obj,
                    ),
                    maxColors = optionalInt("maxColors") ?: DEFAULT_MAX_COLORS,
                )
            }
            "dither" -> {
                allowed += listOf("type", "algorithm", "strength", "palette")
                RecipeStep.DitherStep(
                    algorithm = parseDitherAlgorithm(
                        optionalString("algorithm") ?: run {
                            throw IllegalArgumentException("${obj.where()}: missing 'algorithm' for step 'dither'")
                        },
                        obj,
                    ),
                    strength = optionalFloat("strength"),
                    paletteId = optionalString("palette"),
                )
            }
            "palette-map" -> {
                allowed += listOf("type", "palette")
                RecipeStep.PaletteMapStep(paletteId = optionalStringOrNull("palette", obj))
            }
            "outline" -> {
                allowed += listOf("type", "color", "mode", "connectivity")
                RecipeStep.OutlineStep(
                    color = parseColor(obj["color"] ?: run {
                        throw IllegalArgumentException("${obj.where()}: missing 'color' for step 'outline'")
                    }, obj),
                    mode = parseMode(optionalString("mode"), obj),
                    connectivity = parseConnectivity(optionalString("connectivity"), obj),
                )
            }
            "trim" -> {
                allowed += listOf("type")
                RecipeStep.TrimStep
            }
            "posterize" -> {
                allowed += listOf("type", "levels")
                RecipeStep.PosterizeStep(levels = requireInt("levels"))
            }
            "bg-remove" -> {
                allowed += listOf("type", "tolerance")
                RecipeStep.BgRemoveStep(tolerance = requireInt("tolerance"))
            }
            else -> throw IllegalArgumentException("${obj.where()}: unknown step type '$type'")
        }
        for (key in obj.keys) {
            if (key !in allowed) {
                throw IllegalArgumentException("${obj.where()}: unknown parameter '$key' for step '$type'")
            }
        }
        return step
    }

    /** `"palette": null` (and absence) both mean "keep the colors". */
    private fun optionalStringOrNull(field: String, obj: JObject): String? {
        val value = obj[field] ?: return null
        return when (value) {
            is JNull -> null
            is JString -> value.string
            else -> throw IllegalArgumentException("${obj.where()}: '$field' must be a string or null")
        }
    }

    private fun parseQuantizeAlgorithm(raw: String, obj: JObject): QuantizeAlgorithm =
        when (normalize(raw)) {
            "mediancut" -> QuantizeAlgorithm.MEDIAN_CUT
            "kmeans" -> QuantizeAlgorithm.KMEANS
            "octree" -> QuantizeAlgorithm.OCTREE
            else -> throw IllegalArgumentException("${obj.where()}: unknown quantize algorithm '$raw'")
        }

    private fun parseDitherAlgorithm(raw: String, obj: JObject): DitherAlgorithm =
        when (normalize(raw)) {
            "none" -> DitherAlgorithm.NONE
            "floydsteinberg" -> DitherAlgorithm.FLOYD_STEINBERG
            "atkinson" -> DitherAlgorithm.ATKINSON
            "bayer2", "bayer2x2" -> DitherAlgorithm.BAYER_2X2
            "bayer4", "bayer4x4" -> DitherAlgorithm.BAYER_4X4
            "bayer8", "bayer8x8" -> DitherAlgorithm.BAYER_8X8
            "checkerboard", "checker" -> DitherAlgorithm.CHECKERBOARD
            else -> throw IllegalArgumentException("${obj.where()}: unknown dither algorithm '$raw'")
        }

    private fun parseMode(raw: String?, obj: JObject): OutlineMode = when (normalize(raw ?: "outer")) {
        "outer" -> OutlineMode.OUTER
        "inner" -> OutlineMode.INNER
        else -> throw IllegalArgumentException("${obj.where()}: unknown outline mode '$raw'")
    }

    private fun parseConnectivity(raw: String?, obj: JObject): Connectivity =
        when (normalize(raw ?: "eight")) {
            "four", "4" -> Connectivity.FOUR
            "eight", "8" -> Connectivity.EIGHT
            else -> throw IllegalArgumentException("${obj.where()}: unknown connectivity '$raw'")
        }

    /** Decodes `#RRGGBB`, `#RRGGBBAA` (case-insensitive) or a raw ARGB integer. */
    private fun parseColor(value: JValue, obj: JObject): Int = when (value) {
        is JString -> {
            val raw = value.string
            val hex = raw.removePrefix("#")
            val valid = raw.startsWith("#") && (hex.length == 6 || hex.length == 8) &&
                hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            if (!valid) {
                throw IllegalArgumentException("${obj.where()}: invalid color '$raw' (expected #RRGGBB or #RRGGBBAA)")
            }
            if (hex.length == 6) {
                (0xFF shl 24) or hex.toInt(16)
            } else {
                hex.toLong(16).toInt()
            }
        }
        is JNumber -> value.asInt { reason ->
            IllegalArgumentException("${obj.where()}: 'color' $reason")
        }
        else -> throw IllegalArgumentException("${obj.where()}: 'color' must be a hex string or an integer")
    }

    private fun normalize(raw: String): String =
        raw.lowercase().replace("-", "").replace("_", "").replace(" ", "")

    private fun describe(value: JValue): String = when (value) {
        is JNull -> "null"
        is JBool -> "a boolean"
        is JString -> "a string"
        is JNumber -> "a number"
        is JArray -> "an array"
        is JObject -> "an object"
    }

    private fun typeError(obj: JObject, message: String, value: JValue): IllegalArgumentException =
        IllegalArgumentException("${obj.where()}: $message (found ${describe(value)})")

    private const val DEFAULT_NAME: String = "recipe"
    private const val DEFAULT_MAX_COLORS: Int = 16

    // ------------------------------------------------------------------
    // JSON value tree + lexer (compact, self-contained)
    // ------------------------------------------------------------------

    /** Sealed JSON value tree shared by the lexer and the decoder above. */
    private sealed class JValue {
        /** Reads an integral value; [onError] wraps the rejection message. */
        fun asInt(onError: (String) -> IllegalArgumentException): Int {
            val number = this as? JNumber ?: throw onError("must be a number")
            val asLong = number.raw.toLongOrNull()
            if (asLong != null) {
                if (asLong < Int.MIN_VALUE || asLong > Int.MAX_VALUE) {
                    throw onError("must be a 32-bit integer (was ${number.raw})")
                }
                return asLong.toInt()
            }
            val asDouble = number.raw.toDoubleOrNull()
            if (asDouble != null && asDouble == Math.floor(asDouble) &&
                asDouble >= Int.MIN_VALUE.toDouble() && asDouble <= Int.MAX_VALUE.toDouble()
            ) {
                return asDouble.toInt()
            }
            throw onError("must be an integer (was ${number.raw})")
        }

        /** Reads a float; NaN and infinities are rejected. */
        fun asFloat(onError: (String) -> IllegalArgumentException): Float {
            val number = this as? JNumber ?: throw onError("must be a number")
            val asDouble = number.raw.toDoubleOrNull()
                ?: throw onError("is not a valid number ('${number.raw}')")
            if (asDouble.isNaN() || asDouble.isInfinite()) {
                throw onError("must be finite (was ${number.raw})")
            }
            return asDouble.toFloat()
        }
    }

    /** The JSON `null` literal. */
    private object JNull : JValue()

    /** The JSON `true` / `false` literals. */
    private data class JBool(val value: Boolean) : JValue()

    /** A JSON string with escapes already resolved. */
    private data class JString(val string: String) : JValue()

    /** A JSON number kept as raw source text for loss-free typed reads. */
    private data class JNumber(val raw: String) : JValue()

    /** A JSON array. */
    private class JArray(val items: List<JValue>) : JValue()

    /** A JSON object with insertion-ordered entries and its source offset. */
    private class JObject(private val offset: Int) : JValue() {
        private val map = LinkedHashMap<String, JValue>()

        /** The entries in insertion order. */
        val entries: Set<Map.Entry<String, JValue>> get() = map.entries

        /** The keys in insertion order (for unknown-parameter detection). */
        val keys: Set<String> get() = map.keys

        /** The value under [key], or null when absent. */
        operator fun get(key: String): JValue? = map[key]

        /** Adds an entry; duplicate keys are rejected (strict recipes). */
        fun put(key: String, value: JValue) {
            require(!map.containsKey(key)) {
                "JSON object at offset $offset: duplicate key '$key'"
            }
            map[key] = value
        }

        /** Human-readable position used by every error message. */
        fun where(): String = "object at offset $offset"
    }

    /**
     * Strict recursive-descent JSON reader: full string escape set
     * (`" \ / b f n r t uXXXX`), numbers with fraction/exponent, the three
     * literals, nesting up to [MAX_DEPTH] (hostile payloads cannot exhaust
     * the stack), and no trailing content after the root.
     */
    private class Lexer(private val text: String) {

        /** Parses the whole document and returns the root value. */
        fun parseDocument(): JValue {
            val value = parseValue(0)
            skipWhitespace()
            if (pos < text.length) {
                fail("unexpected trailing content '${text[pos]}'")
            }
            return value
        }

        private var pos: Int = 0

        private fun parseValue(depth: Int): JValue {
            if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH levels")
            skipWhitespace()
            if (pos >= text.length) fail("unexpected end of input")
            val c = text[pos]
            return when {
                c == '{' -> parseObject(depth)
                c == '[' -> parseArray(depth)
                c == '"' -> JString(parseStringBody())
                c == 't' -> parseLiteral("true", JBool(true))
                c == 'f' -> parseLiteral("false", JBool(false))
                c == 'n' -> parseLiteral("null", JNull)
                c == '-' || c.isDigit() -> parseNumber()
                else -> fail("unexpected character '$c'")
            }
        }

        private fun parseObject(depth: Int): JObject {
            val obj = JObject(pos)
            pos++ // consume '{'
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return obj
            }
            while (true) {
                skipWhitespace()
                if (pos >= text.length) fail("unterminated object")
                if (text[pos] != '"') fail("expected a key string")
                val key = parseStringBody()
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') fail("expected ':' after key")
                pos++
                val value = parseValue(depth + 1)
                obj.put(key, value)
                skipWhitespace()
                if (pos >= text.length) fail("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return obj
                    }
                    else -> fail("expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(depth: Int): JArray {
            pos++ // consume '['
            val items = ArrayList<JValue>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return JArray(items)
            }
            while (true) {
                items.add(parseValue(depth + 1))
                skipWhitespace()
                if (pos >= text.length) fail("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JArray(items)
                    }
                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        /** Parses a `"…"` body starting at the opening quote. */
        private fun parseStringBody(): String {
            pos++ // consume '"'
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("unterminated string")
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return out.toString()
                    }
                    '\\' -> {
                        pos++
                        if (pos >= text.length) fail("unterminated escape")
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
                                if (pos + 4 >= text.length) fail("truncated \\u escape")
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: fail("invalid \\u escape '\\u$hex'")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                        pos++
                    }
                    else -> {
                        if (c < ' ') fail("raw control character in string")
                        out.append(c)
                        pos++
                    }
                }
            }
        }

        private fun parseLiteral(word: String, value: JValue): JValue {
            if (!text.regionMatches(pos, word, 0, word.length)) {
                fail("invalid literal (expected '$word')")
            }
            pos += word.length
            return value
        }

        private fun parseNumber(): JNumber {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            if (pos >= text.length || !text[pos].isDigit()) fail("invalid number")
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos < text.length && text[pos] == '.') {
                pos++
                if (pos >= text.length || !text[pos].isDigit()) fail("invalid number fraction")
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || !text[pos].isDigit()) fail("invalid number exponent")
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            return JNumber(text.substring(start, pos))
        }

        private fun skipWhitespace() {
            while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' ||
                    text[pos] == '\n' || text[pos] == '\r')
            ) {
                pos++
            }
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("JSON at offset $pos: $message")

        private companion object {
            private const val MAX_DEPTH: Int = 64
        }
    }
}
