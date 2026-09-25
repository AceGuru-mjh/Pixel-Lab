package com.pixellab.core.io

/**
 * Raster formats recognised by the import pipeline's magic-number sniffer.
 *
 * [UNKNOWN] is never a decoding target: it signals "not one of the supported
 * byte signatures" so facades can reject early with a precise message instead
 * of feeding garbage to a decoder.
 */
enum class ImageFormat {
    /** PNG (static or APNG); signature `89 50 4E 47 0D 0A 1A 0A`. */
    PNG,

    /** GIF87a / GIF89a; starts with the ASCII `GIF8`. */
    GIF,

    /** QOI 1.0; magic `qoif`. */
    QOI,

    /** Windows / OS-2 bitmap; magic `BM`. */
    BMP,

    /** Aseprite `.ase` / `.aseprite`; magic `0xA5E0` at byte offset 4. */
    ASEPRITE,

    /** Windows icon container; reserved `0x0000` then type `0x0001`. */
    ICO,

    /** No supported magic matched (empty, truncated or foreign bytes). */
    UNKNOWN;

    /** Human-readable long name for logs and error messages. */
    fun humanName(): String = when (this) {
        PNG -> "PNG (Portable Network Graphics)"
        GIF -> "GIF (Graphics Interchange Format)"
        QOI -> "QOI (Quite OK Image)"
        BMP -> "BMP (Windows bitmap)"
        ASEPRITE -> "Aseprite sprite (.ase/.aseprite)"
        ICO -> "ICO (Windows icon container)"
        UNKNOWN -> "unknown format"
    }
}

/**
 * Magic-number format sniffer for the import pipeline.
 *
 * Detection is purely structural and conservative: every candidate needs its
 * full signature present, so truncated files degrade to [ImageFormat.UNKNOWN]
 * instead of half-matching. Signatures are checked most-specific first:
 *
 * | Format | Bytes checked | Signature |
 * |--------|---------------|-----------|
 * | PNG | 8 | `89 50 4E 47 0D 0A 1A 0A` |
 * | GIF | 4 | ASCII `GIF8` |
 * | QOI | 4 | ASCII `qoif` |
 * | ICO | 4 | `00 00 01 00` (reserved 0, type 1 = icon) |
 * | BMP | 2 | ASCII `BM` |
 * | ASEPRITE | 8 | u16le `0xA5E0` at offset 4 and a non-zero u16le at offset 6 |
 *
 * The Aseprite header stores the frame count at bytes 6..7 (there is no
 * separate version field in the real format); a file with zero "frames" there
 * is rejected, which doubles as the sanity check for a well-formed header.
 */
object FormatSniffer {

    /** PNG file signature. */
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Aseprite file magic, little-endian, at byte offset 4. */
    private const val ASE_MAGIC = 0xA5E0

    /**
     * Sniffs the container format of [bytes] purely by magic signature.
     *
     * @return the matched [ImageFormat], or [ImageFormat.UNKNOWN] when the
     * bytes are too short or match none of the supported signatures. This
     * function never throws — sniffing is a safe probe, decoding is where
     * strictness begins.
     */
    fun sniff(bytes: ByteArray): ImageFormat {
        if (bytes.size < 2) return ImageFormat.UNKNOWN
        if (bytes.size >= PNG_MAGIC.size && bytes.startsWith(PNG_MAGIC)) return ImageFormat.PNG
        if (bytes.size >= 4 && asciiAt(bytes, 0, 4) == "GIF8") return ImageFormat.GIF
        if (bytes.size >= 4 && asciiAt(bytes, 0, 4) == "qoif") return ImageFormat.QOI
        if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            bytes[2] == 1.toByte() && bytes[3] == 0.toByte()
        ) {
            return ImageFormat.ICO
        }
        if (bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            return ImageFormat.BMP
        }
        if (bytes.size >= 8) {
            val magic = u16LeAt(bytes, 4)
            val sanity = u16LeAt(bytes, 6)
            if (magic == ASE_MAGIC && sanity != 0) return ImageFormat.ASEPRITE
        }
        return ImageFormat.UNKNOWN
    }

    /** Reads 4 ASCII characters starting at [offset] (bounds pre-checked). */
    private fun asciiAt(bytes: ByteArray, offset: Int, count: Int): String =
        String(bytes, offset, count, Charsets.US_ASCII)

    /** Reads an unsigned 16-bit little-endian value at [offset]. */
    private fun u16LeAt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)

    /** Whether [bytes] begins with [magic]. */
    private fun ByteArray.startsWith(magic: ByteArray): Boolean {
        for (i in magic.indices) {
            if (this[i] != magic[i]) return false
        }
        return true
    }
}
