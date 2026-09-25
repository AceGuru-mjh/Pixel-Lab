package com.pixellab.mcp.json

/**
 * Hand-rolled JSON tree API for pixel-mcp — zero external dependencies.
 *
 * The element hierarchy mirrors the JSON data model exactly:
 * [JsonObject], [JsonArray], [JsonString], [JsonNumber], [JsonBoolean] and
 * [JsonNull]. [JsonNumber] keeps its raw source text so parsed numbers round
 * trip byte-for-byte (`"1.50"` stays `"1.50"`, `"-0"` stays `"-0"`).
 *
 * Parsing and writing both enforce a nesting depth limit ([Json.MAX_DEPTH]) so
 * hostile payloads cannot blow the JVM stack.
 */
sealed class JsonElement {

    /** True only for the [JsonNull] singleton. */
    val isNull: Boolean get() = this === JsonNull

    /** Compact JSON rendering (same output as [Json.write]). */
    override fun toString(): String = Json.write(this)
}

/** The JSON `null` literal. */
object JsonNull : JsonElement()

/** The JSON `true` / `false` literals. */
data class JsonBoolean(val value: Boolean) : JsonElement()

/** A JSON string value. */
data class JsonString(val value: String) : JsonElement()

/**
 * A JSON number: the parsed [value] plus the [raw] source text when the number
 * came from [Json.parse] (or was built from an integral literal), or null to
 * request canonical formatting on output.
 */
data class JsonNumber(val value: Double, val raw: String?) : JsonElement() {

    companion object {
        /** Number with raw text, used by the parser. */
        internal fun of(raw: String): JsonNumber = JsonNumber(raw.toDouble(), raw)

        /** Integral number with loss-free canonical text. */
        internal fun of(value: Long): JsonNumber = JsonNumber(value.toDouble(), value.toString())
    }
}

/** A JSON array. */
class JsonArray(val items: List<JsonElement>) : JsonElement() {

    /** Number of elements. */
    val size: Int get() = items.size

    /** Element at [index]. */
    operator fun get(index: Int): JsonElement = items[index]

    /** Iterates the elements. */
    operator fun iterator(): Iterator<JsonElement> = items.iterator()
}

/** A JSON object; entries keep their insertion order. */
class JsonObject(val entries: Map<String, JsonElement>) : JsonElement() {

    /** Whether [key] is present (even when its value is null). */
    fun has(key: String): Boolean = entries.containsKey(key)

    /** The raw element stored under [key], or null when absent. */
    fun raw(key: String): JsonElement? = entries[key]

    // ---- strict accessors (throw IllegalArgumentException on misuse) ----

    /** The string stored under [key]. */
    fun string(key: String): String {
        val e = entries[key] ?: throw IllegalArgumentException("missing key '$key'")
        return (e as? JsonString)?.value ?: throw IllegalArgumentException("key '$key' is not a string")
    }

    /** The integer stored under [key]; fractional values are rejected. */
    fun int(key: String): Int {
        val e = entries[key] ?: throw IllegalArgumentException("missing key '$key'")
        val n = e as? JsonNumber ?: throw IllegalArgumentException("key '$key' is not a number")
        if (n.value != Math.floor(n.value) || n.value < Int.MIN_VALUE.toDouble() || n.value > Int.MAX_VALUE.toDouble()) {
            throw IllegalArgumentException("key '$key' is not a 32-bit integer")
        }
        return n.value.toInt()
    }

    /** The long integer stored under [key]; fractional values are rejected. */
    fun long(key: String): Long {
        val e = entries[key] ?: throw IllegalArgumentException("missing key '$key'")
        val n = e as? JsonNumber ?: throw IllegalArgumentException("key '$key' is not a number")
        if (n.value != Math.floor(n.value) || n.value < Long.MIN_VALUE.toDouble() || n.value > Long.MAX_VALUE.toDouble()) {
            throw IllegalArgumentException("key '$key' is not a 64-bit integer")
        }
        return n.value.toLong()
    }

    /** The double stored under [key]. */
    fun double(key: String): Double {
        val e = entries[key] ?: throw IllegalArgumentException("missing key '$key'")
        val n = e as? JsonNumber ?: throw IllegalArgumentException("key '$key' is not a number")
        return n.value
    }

    /** The boolean stored under [key]. */
    fun bool(key: String): Boolean {
        val e = entries[key] ?: throw IllegalArgumentException("missing key '$key'")
        return (e as? JsonBoolean)?.value ?: throw IllegalArgumentException("key '$key' is not a boolean")
    }

    /** The array stored under [key]. */
    fun array(key: String): JsonArray {
        val e = entries[key] ?: throw IllegalArgumentException("missing key '$key'")
        return e as? JsonArray ?: throw IllegalArgumentException("key '$key' is not an array")
    }

    /** The object stored under [key]. */
    fun obj(key: String): JsonObject {
        val e = entries[key] ?: throw IllegalArgumentException("missing key '$key'")
        return e as? JsonObject ?: throw IllegalArgumentException("key '$key' is not an object")
    }

    // ---- lenient accessors (fall back to a default) ----

    /** String value or [default]. */
    fun opt(key: String, default: String): String = (entries[key] as? JsonString)?.value ?: default

    /** Int value or [default]. */
    fun opt(key: String, default: Int): Int {
        val n = entries[key] as? JsonNumber ?: return default
        return if (n.value == Math.floor(n.value) && n.value >= Int.MIN_VALUE.toDouble() && n.value <= Int.MAX_VALUE.toDouble()) {
            n.value.toInt()
        } else {
            default
        }
    }

    /** Long value or [default]. */
    fun opt(key: String, default: Long): Long {
        val n = entries[key] as? JsonNumber ?: return default
        return if (n.value == Math.floor(n.value)) n.value.toLong() else default
    }

    /** Double value or [default]. */
    fun opt(key: String, default: Double): Double = (entries[key] as? JsonNumber)?.value ?: default

    /** Float value or [default]. */
    fun opt(key: String, default: Float): Float = (entries[key] as? JsonNumber)?.value?.toFloat() ?: default

    /** Boolean value or [default]. */
    fun opt(key: String, default: Boolean): Boolean = (entries[key] as? JsonBoolean)?.value ?: default

    /** Element value or [default]. */
    fun opt(key: String, default: JsonElement): JsonElement = entries[key] ?: default

    /** A mutable copy of this object. */
    fun toMutable(): MutableJsonObject {
        val out = MutableJsonObject()
        for ((k, v) in entries) out.put(k, v)
        return out
    }
}

/** In-place builder target for [jsonobj]; every [put] returns `this`. */
class MutableJsonObject internal constructor() {

    private val map = LinkedHashMap<String, JsonElement>()

    /** Stores a string. */
    fun put(key: String, value: String): MutableJsonObject = put(key, JsonString(value))

    /** Stores an Int. */
    fun put(key: String, value: Int): MutableJsonObject = put(key, JsonNumber.of(value.toLong()))

    /** Stores a Long. */
    fun put(key: String, value: Long): MutableJsonObject = put(key, JsonNumber.of(value))

    /** Stores a Float (canonical formatting on output). */
    fun put(key: String, value: Float): MutableJsonObject = put(key, JsonNumber(value.toDouble(), null))

    /** Stores a Double (canonical formatting on output). */
    fun put(key: String, value: Double): MutableJsonObject = put(key, JsonNumber(value, null))

    /** Stores a Boolean. */
    fun put(key: String, value: Boolean): MutableJsonObject = put(key, JsonBoolean(value))

    /** Stores any element, including [JsonNull] and nested objects/arrays. */
    fun put(key: String, value: JsonElement): MutableJsonObject {
        map[key] = value
        return this
    }

    /** Stores a list of strings as a JSON array. */
    fun put(key: String, values: List<String>): MutableJsonObject =
        put(key, JsonArray(values.map { JsonString(it) }))

    /** Stores [value] only when it is non-null. */
    fun putIfNotNull(key: String, value: JsonElement?): MutableJsonObject {
        if (value != null) map[key] = value
        return this
    }

    /** Removes [key] when present. */
    fun remove(key: String): MutableJsonObject {
        map.remove(key)
        return this
    }

    /** Freezes the accumulated entries into an immutable [JsonObject]. */
    fun build(): JsonObject = JsonObject(LinkedHashMap(map))
}

/** Builds a [JsonObject] with `jsonobj { put("k", v) }` syntax. */
fun jsonobj(build: MutableJsonObject.() -> Unit): JsonObject {
    val builder = MutableJsonObject()
    builder.build()
    return builder.apply(build).build()
}

/** Builds a [JsonArray] with `jsonarray { add(...) }` syntax. */
fun jsonarray(build: MutableList<JsonElement>.() -> Unit): JsonArray {
    val list = ArrayList<JsonElement>()
    list.apply(build)
    return JsonArray(list)
}

/**
 * Parser and writer for the JSON grammar accepted by the [JsonElement] tree.
 */
object Json {

    /** Maximum nesting depth for both parsing and writing. */
    const val MAX_DEPTH: Int = 64

    /** Parses [text] into a [JsonElement] tree. */
    fun parse(text: String): JsonElement {
        val parser = Parser(text)
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonParseException("trailing content at offset ${parser.pos}")
        return value
    }

    /** Renders [element] as compact JSON text. */
    fun write(element: JsonElement): String {
        val out = StringBuilder()
        writeElement(out, element, 0)
        return out.toString()
    }

    // ---- writer ----------------------------------------------------------

    private fun writeElement(out: StringBuilder, element: JsonElement, depth: Int) {
        if (depth > MAX_DEPTH) throw JsonWriteException("nesting deeper than $MAX_DEPTH levels")
        when (element) {
            is JsonNull -> out.append("null")
            is JsonBoolean -> out.append(if (element.value) "true" else "false")
            is JsonString -> writeString(out, element.value)
            is JsonNumber -> writeNumber(out, element)
            is JsonArray -> {
                out.append('[')
                for ((i, item) in element.items.withIndex()) {
                    if (i > 0) out.append(',')
                    writeElement(out, item, depth + 1)
                }
                out.append(']')
            }
            is JsonObject -> {
                out.append('{')
                for ((i, entry) in element.entries.entries.withIndex()) {
                    if (i > 0) out.append(',')
                    writeString(out, entry.key)
                    out.append(':')
                    writeElement(out, entry.value, depth + 1)
                }
                out.append('}')
            }
        }
    }

    /** Raw text when available, otherwise a canonical representation. */
    private fun writeNumber(out: StringBuilder, number: JsonNumber) {
        val raw = number.raw
        if (raw != null) {
            out.append(raw)
            return
        }
        val value = number.value
        if (value.isNaN() || value.isInfinite()) {
            out.append("null")
        } else if (value == Math.floor(value) && value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            out.append(value.toLong())
        } else {
            out.append(value)
        }
    }

    /** Escapes `"` `\` `\n` `\r` `\t` `\b` `\f` and control characters. */
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

    // ---- parser ----------------------------------------------------------

    private class Parser(val text: String) {
        var pos: Int = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun parseValue(depth: Int): JsonElement {
            if (depth > MAX_DEPTH) throw JsonParseException("nesting deeper than $MAX_DEPTH levels")
            skipWhitespace()
            if (atEnd()) throw JsonParseException("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonString(parseStringBody())
                't' -> parseLiteral("true", JsonBoolean(true))
                'f' -> parseLiteral("false", JsonBoolean(false))
                'n' -> parseLiteral("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') {
                    parseNumber()
                } else {
                    throw JsonParseException("unexpected character '$c' at offset $pos")
                }
            }
        }

        private fun parseObject(depth: Int): JsonObject {
            pos++ // consume '{'
            val builder = MutableJsonObject()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') {
                pos++
                return builder.build()
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw JsonParseException("expected object key at offset $pos")
                val key = parseStringBody()
                skipWhitespace()
                if (atEnd() || text[pos] != ':') throw JsonParseException("expected ':' at offset $pos")
                pos++
                builder.put(key, parseValue(depth + 1))
                skipWhitespace()
                if (atEnd()) throw JsonParseException("unterminated object")
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return builder.build()
                    }
                    else -> throw JsonParseException("expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(depth: Int): JsonArray {
            pos++ // consume '['
            val items = ArrayList<JsonElement>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') {
                pos++
                return JsonArray(items)
            }
            while (true) {
                items.add(parseValue(depth + 1))
                skipWhitespace()
                if (atEnd()) throw JsonParseException("unterminated array")
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonArray(items)
                    }
                    else -> throw JsonParseException("expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseStringBody(): String {
            pos++ // consume opening '"'
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("unterminated string")
                when (val c = text[pos]) {
                    '"' -> {
                        pos++
                        return out.toString()
                    }
                    '\\' -> {
                        pos++
                        if (atEnd()) throw JsonParseException("unterminated escape")
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
                                if (pos + 4 >= text.length) throw JsonParseException("truncated \\u escape")
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("invalid \\u escape '$hex'")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonParseException("invalid escape '\\$e'")
                        }
                        pos++
                    }
                    else -> {
                        if (c < ' ') throw JsonParseException("unescaped control character at offset $pos")
                        out.append(c)
                        pos++
                    }
                }
            }
        }

        private fun parseLiteral(word: String, value: JsonElement): JsonElement {
            if (!text.startsWith(word, pos)) throw JsonParseException("invalid literal at offset $pos")
            pos += word.length
            return value
        }

        private fun parseNumber(): JsonNumber {
            val start = pos
            if (text[pos] == '-') pos++
            if (atEnd()) throw JsonParseException("unterminated number")
            if (text[pos] == '0') {
                pos++
            } else if (text[pos] in '1'..'9') {
                consumeDigits()
            } else {
                throw JsonParseException("invalid number at offset $start")
            }
            if (!atEnd() && text[pos] == '.') {
                pos++
                if (atEnd() || text[pos] !in '0'..'9') throw JsonParseException("invalid fraction at offset $pos")
                consumeDigits()
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                if (atEnd() || text[pos] !in '0'..'9') throw JsonParseException("invalid exponent at offset $pos")
                consumeDigits()
            }
            val raw = text.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: throw JsonParseException("invalid number '$raw'")
            if (value.isNaN() || value.isInfinite()) throw JsonParseException("number out of range '$raw'")
            return JsonNumber.of(raw)
        }

        private fun consumeDigits() {
            while (!atEnd() && text[pos] in '0'..'9') pos++
        }
    }
}

/** Thrown by [Json.parse] on malformed input. */
class JsonParseException(message: String) : Exception(message)

/** Thrown by [Json.write] when the tree nests too deeply. */
class JsonWriteException(message: String) : Exception(message)
