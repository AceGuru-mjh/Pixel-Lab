package com.pixellab.core.io

/**
 * Index-bound-checked cursor over an immutable byte array, with explicit
 * little-endian and big-endian read primitives.
 *
 * Every binary format in the import pipeline (GIF, BMP, ICO, Aseprite are
 * little-endian; PNG, QOI are big-endian) is parsed through this one reader
 * so that truncation handling and error messages stay uniform: any read that
 * would cross [to] fails with a descriptive [IllegalArgumentException] that
 * names the absolute offset, the operation and the remaining byte count.
 *
 * The reader never mutates [data]; callers may therefore share the same
 * backing array between several readers (e.g. one per chunk or per frame).
 */
class BinaryReader(
    /** Backing bytes; treated as immutable, never copied. */
    val data: ByteArray,
    /** First readable index (inclusive). */
    private val from: Int = 0,
    /** Last readable index + 1 (exclusive). */
    private val to: Int = data.size,
) {
    init {
        require(from in 0..data.size) {
            "BinaryReader window start $from outside 0..${data.size}"
        }
        require(to in from..data.size) {
            "BinaryReader window end $to outside $from..${data.size}"
        }
    }

    /** Absolute index of the next byte to be read. */
    var position: Int = from
        private set

    /** Number of bytes not yet consumed. */
    fun remaining(): Int = to - position

    /** Whether at least one more byte can be read. */
    fun hasRemaining(): Boolean = position < to

    /**
     * Guarantees that [count] unread bytes are available for [what], failing
     * with a descriptive message that carries the absolute offset.
     */
    fun requireRemaining(count: Int, what: String) {
        if (count < 0 || to - position < count) {
            throw IllegalArgumentException(
                "Truncated input at offset $position: $what needs $count byte(s) " +
                    "but only ${to - position} remain (window [$from, $to) of ${data.size} bytes)"
            )
        }
    }

    /** Reads one unsigned byte (`0..255`). */
    fun u8(): Int {
        requireRemaining(1, "u8")
        return data[position++].toInt() and 0xFF
    }

    /** Reads an unsigned 16-bit little-endian value (`0..65535`). */
    fun u16Le(): Int {
        requireRemaining(2, "u16Le")
        val b0 = data[position].toInt() and 0xFF
        val b1 = data[position + 1].toInt() and 0xFF
        position += 2
        return (b1 shl 8) or b0
    }

    /** Reads an unsigned 16-bit big-endian value (`0..65535`). */
    fun u16Be(): Int {
        requireRemaining(2, "u16Be")
        val b0 = data[position].toInt() and 0xFF
        val b1 = data[position + 1].toInt() and 0xFF
        position += 2
        return (b0 shl 8) or b1
    }

    /**
     * Reads an unsigned 32-bit little-endian value as a `Long` (`0..2^32-1`),
     * so full unsigned magnitudes survive the read.
     */
    fun u32Le(): Long {
        requireRemaining(4, "u32Le")
        val b0 = data[position].toInt() and 0xFF
        val b1 = data[position + 1].toInt() and 0xFF
        val b2 = data[position + 2].toInt() and 0xFF
        val b3 = data[position + 3].toInt() and 0xFF
        position += 4
        return (b3.toLong() shl 24) or (b2.toLong() shl 16) or
            (b1.toLong() shl 8) or b0.toLong()
    }

    /**
     * Reads an unsigned 32-bit big-endian value as a `Long` (`0..2^32-1`),
     * so full unsigned magnitudes survive the read.
     */
    fun u32Be(): Long {
        requireRemaining(4, "u32Be")
        val b0 = data[position].toInt() and 0xFF
        val b1 = data[position + 1].toInt() and 0xFF
        val b2 = data[position + 2].toInt() and 0xFF
        val b3 = data[position + 3].toInt() and 0xFF
        position += 4
        return (b0.toLong() shl 24) or (b1.toLong() shl 16) or
            (b2.toLong() shl 8) or b3.toLong()
    }

    /** Reads a signed 16-bit little-endian value (e.g. Aseprite cel x/y). */
    fun i16Le(): Int = u16Le().toShort().toInt()

    /** Reads a signed 16-bit big-endian value. */
    fun i16Be(): Int = u16Be().toShort().toInt()

    /** Reads a signed 32-bit little-endian value (e.g. BMP width/height). */
    fun i32Le(): Int = u32Le().toInt()

    /** Reads a signed 32-bit big-endian value. */
    fun i32Be(): Int = u32Be().toInt()

    /**
     * Repositions the cursor to [newPosition] (absolute index), failing when
     * it lands outside the readable window.
     */
    fun seek(newPosition: Int) {
        if (newPosition < from || newPosition > to) {
            throw IllegalArgumentException(
                "Seek to $newPosition outside the readable window [$from, $to)"
            )
        }
        position = newPosition
    }

    /**
     * Reads exactly [count] bytes, failing when fewer remain. The returned
     * array is a fresh copy; callers own it.
     */
    fun bytes(count: Int): ByteArray {
        requireRemaining(count, "bytes($count)")
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    /**
     * Advances the cursor by [count] bytes without materialising them;
     * negative or overreaching skips fail like any other read.
     */
    fun skip(count: Int) {
        requireRemaining(count, "skip($count)")
        position += count
    }

    /** Reads exactly [count] US-ASCII bytes as a string (magic numbers, ids). */
    fun ascii(count: Int): String {
        val raw = bytes(count)
        return String(raw, Charsets.US_ASCII)
    }
}

/**
 * Minimal little/big-endian byte accumulator used by the ICO and BMP writers
 * and by handcrafted fixtures in the behavioural smoke tests.
 *
 * Grows on demand; [toByteArray] hands out a copy sized exactly to
 * [length], so the writer can be reused for sequential structure assembly
 * (header, then entries, then payloads) without trimming guesses.
 */
class BinaryWriter(initialCapacity: Int = 64) {
    private var buffer: ByteArray
    private var cursor: Int = 0

    /** Number of bytes written so far. */
    val length: Int
        get() = cursor

    init {
        require(initialCapacity >= 0) { "initialCapacity must be >= 0 (was $initialCapacity)" }
        buffer = ByteArray(initialCapacity)
    }

    /** Appends one byte (only the low 8 bits of [value] are used). */
    fun u8(value: Int) {
        ensure(1)
        buffer[cursor++] = value.toByte()
    }

    /** Appends an unsigned 16-bit little-endian pair. */
    fun u16Le(value: Int) {
        ensure(2)
        buffer[cursor++] = (value and 0xFF).toByte()
        buffer[cursor++] = ((value ushr 8) and 0xFF).toByte()
    }

    /** Appends an unsigned 16-bit big-endian pair. */
    fun u16Be(value: Int) {
        ensure(2)
        buffer[cursor++] = ((value ushr 8) and 0xFF).toByte()
        buffer[cursor++] = (value and 0xFF).toByte()
    }

    /** Appends a 32-bit pattern little-endian (full [value], sign included). */
    fun u32Le(value: Int) {
        ensure(4)
        buffer[cursor++] = (value and 0xFF).toByte()
        buffer[cursor++] = ((value ushr 8) and 0xFF).toByte()
        buffer[cursor++] = ((value ushr 16) and 0xFF).toByte()
        buffer[cursor++] = ((value ushr 24) and 0xFF).toByte()
    }

    /** Appends a 32-bit pattern big-endian (full [value], sign included). */
    fun u32Be(value: Int) {
        ensure(4)
        buffer[cursor++] = ((value ushr 24) and 0xFF).toByte()
        buffer[cursor++] = ((value ushr 16) and 0xFF).toByte()
        buffer[cursor++] = ((value ushr 8) and 0xFF).toByte()
        buffer[cursor++] = (value and 0xFF).toByte()
    }

    /** Appends a signed 16-bit little-endian value. */
    fun i16Le(value: Int) = u16Le(value)

    /** Appends a signed 32-bit little-endian value. */
    fun i32Le(value: Int) = u32Le(value)

    /** Appends every byte of [values] in order. */
    fun bytes(values: ByteArray) {
        ensure(values.size)
        System.arraycopy(values, 0, buffer, cursor, values.size)
        cursor += values.size
    }

    /** Appends [text] as US-ASCII bytes. */
    fun ascii(text: String) = bytes(text.toByteArray(Charsets.US_ASCII))

    /** Appends [count] zero bytes (header padding, reserved fields). */
    fun zeros(count: Int) {
        ensure(count)
        cursor += count
        // ByteArray is zero-initialised; nothing to write.
    }

    /** Snapshot of the written bytes, exactly [length] long. */
    fun toByteArray(): ByteArray = buffer.copyOf(cursor)

    /** Grows the backing buffer so [extra] more bytes fit. */
    private fun ensure(extra: Int) {
        val needed = cursor + extra
        if (needed <= buffer.size) return
        var capacity = buffer.size * 2
        while (capacity < needed) capacity *= 2
        buffer = buffer.copyOf(maxOf(capacity, needed))
    }
}
