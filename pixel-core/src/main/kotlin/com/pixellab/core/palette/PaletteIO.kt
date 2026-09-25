package com.pixellab.core.palette

import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource

/**
 * Import/export of palettes in the three text formats pixel artists actually
 * exchange: JASC-PAL (Paint Shop Pro `.pal`, understood by Aseprite,
 * Grafx2, Pro Motion and most retro tools), GIMP `.gpl`, and plain hex
 * lists (`#rrggbb`, `0xRRGGBB`, bare `RRGGBB`, or decimal `r,g,b` triples).
 *
 * ## Contracts
 *
 * * All `to*` functions produce text that round-trips losslessly through
 *   the matching `from*` function (color-wise; names are preserved where
 *   the target format has a name field).
 * * All `from*` functions return [Palette]s with
 *   [PaletteSource.IMPORTED] provenance and a derived id, so imports never
 *   masquerade as built-ins.
 * * Validation is strict for JASC-PAL (the format has no comments, so any
 *   deviation is corruption) and lenient for GIMP `.gpl` (files in the wild
 *   carry arbitrary metadata lines); hex lists report **all** invalid
 *   tokens at once instead of failing on the first one.
 *
 * All functions are pure: no I/O, no locale dependence (numbers are always
 * ASCII digits), fully deterministic output.
 */
object PaletteIO {

    /**
     * Palette text formats recognized by [guessFormat].
     *
     * * [JASC] — `JASC-PAL` R G B triplets (Paint Shop Pro `.pal`).
     * * [GPL] — GIMP palette `.gpl` with `Name:`/`Columns:` headers.
     * * [HEX] — loose hex / decimal color list.
     */
    enum class Format { JASC, GPL, HEX }

    /** Line terminators accepted on import (Windows, old-Mac and Unix). */
    private val LINE_BREAKS = Regex("\r\n|\r|\n")

    /** Runs of whitespace used as token separators (incl. tabs). */
    private val WHITESPACE = Regex("\\s+")

    /** Separators between colors in a hex list: whitespace, comma, semicolon. */
    private val HEX_LIST_SEPARATORS = Regex("[\\s,;]+")

    /** Fallback id assigned to JASC imports (the format carries no name). */
    private const val JASC_ID = "imported-jasc"

    /** Fallback id assigned to GIMP imports lacking a `Name:` header. */
    private const val GPL_ID = "imported-gpl"

    // ------------------------------------------------------------------
    // JASC-PAL
    // ------------------------------------------------------------------

    /**
     * Serializes [palette] as a JASC-PAL (Paint Shop Pro) text file:
     *
     * ```
     * JASC-PAL\r\n
     * 0100\r\n
     * {size}\r\n
     * R G B\r\n      (one line per color, decimal 0-255)
     * ```
     *
     * Lines are CRLF-terminated exactly like the original Paint Shop Pro
     * writer, so even picky DOS-era tools read the output.
     */
    fun toJasc(palette: Palette): String {
        val sb = StringBuilder(24 + palette.size * 13)
        sb.append("JASC-PAL\r\n")
        sb.append("0100\r\n")
        sb.append(palette.size).append("\r\n")
        for (color in palette.colors) {
            sb.append((color shr 16) and 0xFF).append(' ')
            sb.append((color shr 8) and 0xFF).append(' ')
            sb.append(color and 0xFF).append("\r\n")
        }
        return sb.toString()
    }

    /**
     * Parses a JASC-PAL text into a [Palette] with [PaletteSource.IMPORTED]
     * provenance (id `"imported-jasc"`).
     *
     * The parser is strict and validates every documented field:
     *
     * * line 1 must be the `JASC-PAL` magic;
     * * line 2 must be the version `0100`;
     * * line 3 must be a positive color count;
     * * exactly that many color lines must follow, each holding three
     *   decimal channels in 0..255 separated by whitespace;
     * * no extra content may appear after the color rows (trailing blank
     *   lines from a final terminator are tolerated).
     *
     * A UTF-8 BOM at the start of the file is ignored. CRLF, CR and LF
     * line endings are all accepted.
     *
     * @throws IllegalArgumentException on any violation; the message names
     *         the offending 1-based [line number] and the offending text.
     */
    fun fromJasc(text: String): Palette {
        val lines = splitLines(text)
        require(lines.isNotEmpty()) { "Line 1: expected 'JASC-PAL' header, got empty input" }

        require(lines[0].trim() == "JASC-PAL") {
            "Line 1: expected 'JASC-PAL' header, got '${lines[0]}'"
        }
        require(lines.size >= 2) { "Line 2: expected version '0100', got end of file" }
        require(lines[1].trim() == "0100") {
            "Line 2: expected version '0100', got '${lines[1]}'"
        }
        require(lines.size >= 3) { "Line 3: expected color count, got end of file" }
        val declared = lines[2].trim().toIntOrNull() ?: throw IllegalArgumentException(
            "Line 3: expected decimal color count, got '${lines[2]}'"
        )
        require(declared > 0) { "Line 3: color count must be positive, was $declared" }

        val colorLines = lines.drop(3)
        require(colorLines.size >= declared) {
            "Line ${lines.size + 1}: expected $declared color lines, found only ${colorLines.size}"
        }
        require(colorLines.size == declared) {
            "Line ${4 + declared}: unexpected content after $declared colors: '${colorLines[declared]}'"
        }

        val colors = IntArray(declared)
        for (i in 0 until declared) {
            val lineNumber = 4 + i
            val tokens = colorLines[i].trim().split(WHITESPACE)
            require(tokens.size == 3 && tokens[0].toIntOrNull() != null &&
                tokens[1].toIntOrNull() != null && tokens[2].toIntOrNull() != null) {
                "Line $lineNumber: expected 'R G B' decimal triple, got '${colorLines[i]}'"
            }
            val r = tokens[0].toInt()
            val g = tokens[1].toInt()
            val b = tokens[2].toInt()
            require(r in 0..255 && g in 0..255 && b in 0..255) {
                "Line $lineNumber: channels out of range 0..255: '$r $g $b'"
            }
            colors[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Palette(id = JASC_ID, name = "Imported JASC-PAL", colors = colors, source = PaletteSource.IMPORTED)
    }

    // ------------------------------------------------------------------
    // GIMP .gpl
    // ------------------------------------------------------------------

    /**
     * Serializes [palette] as a GIMP palette (`.gpl`) file:
     *
     * ```
     * GIMP Palette
     * Name: {name}
     * Columns: {n}
     * #
     * {blank line}
     * R G B\tHex
     * ```
     *
     * [name] overrides the palette's display name in the `Name:` header;
     * when null, [Palette.name] is used. Each color row is `R G B` followed
     * by a tab and the color's lowercase hex (`rrggbb`) as the entry name —
     * GIMP shows it in the palette editor and it makes diffs readable.
     * The `Columns:` value is `min(size, 8)`, matching how GIMP itself
     * wraps wide palettes into rows of eight.
     */
    fun toGpl(palette: Palette, name: String? = null): String {
        val sb = StringBuilder(64 + palette.size * 20)
        sb.append("GIMP Palette\n")
        sb.append("Name: ").append(name ?: palette.name).append('\n')
        sb.append("Columns: ").append(minOf(palette.size, 8)).append('\n')
        sb.append("#\n")
        sb.append('\n')
        for (color in palette.colors) {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            sb.append(r).append(' ').append(g).append(' ').append(b)
            sb.append('\t').append(hex6(color)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Parses a GIMP `.gpl` text into a [Palette] with
     * [PaletteSource.IMPORTED] provenance. The palette id is
     * `"imported-gpl"`; the name comes from the `Name:` header when
     * present, otherwise `"Imported GIMP Palette"`.
     *
     * Parsing is deliberately lenient, because real `.gpl` files in the
     * wild carry varying metadata:
     *
     * * the leading `GIMP Palette` line, `Name:`/`Columns:` headers and
     *   `#` comment lines are skipped (`Name:` is remembered);
     * * blank lines are skipped anywhere;
     * * color rows are `R G B` followed by an optional tab-separated name —
     *   tabs and runs of spaces are equally accepted as separators, and
     *   anything after the third number is ignored;
     * * lines that do not start with three decimals are skipped silently.
     *
     * CRLF, CR and LF line endings are accepted, and a leading UTF-8 BOM
     * is ignored.
     *
     * @throws IllegalArgumentException if no parsable color row is found.
     */
    fun fromGpl(text: String): Palette {
        var name: String? = null
        val colors = ArrayList<Int>()
        val lines = splitLines(text)
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (index == 0 && line.equals("GIMP Palette", ignoreCase = true)) continue
            if (line.startsWith("Name:", ignoreCase = true)) {
                val value = line.substring(5).trim()
                if (value.isNotEmpty()) name = value
                continue
            }
            if (line.startsWith("Columns:", ignoreCase = true)) continue

            val tokens = line.split(WHITESPACE)
            if (tokens.size < 3) continue
            val r = tokens[0].toIntOrNull() ?: continue
            val g = tokens[1].toIntOrNull() ?: continue
            val b = tokens[2].toIntOrNull() ?: continue
            if (r !in 0..255 || g !in 0..255 || b !in 0..255) continue
            colors.add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }
        require(colors.isNotEmpty()) { "GIMP palette contains no parsable color rows" }
        return Palette(
            id = GPL_ID,
            name = name ?: "Imported GIMP Palette",
            colors = colors.toIntArray(),
            source = PaletteSource.IMPORTED,
        )
    }

    // ------------------------------------------------------------------
    // Hex lists
    // ------------------------------------------------------------------

    /**
     * Serializes [palette] as a list of hex color strings joined by
     * [separator], each formatted as [prefix] + six lowercase hex digits.
     *
     * The defaults produce one `#rrggbb` per line — the de-facto standard
     * palette sharing format. Pass `prefix = "0x"` or
     * `separator = ", "` for other flavors.
     */
    fun toHexList(palette: Palette, prefix: String = "#", separator: String = "\n"): String =
        palette.colors.joinToString(separator) { prefix + hex6(it) }

    /**
     * Parses a loose list of colors into a [Palette] with the caller-chosen
     * [id] and [name] and [PaletteSource.IMPORTED] provenance.
     *
     * Accepted per-color notations:
     *
     * * `#RRGGBB` — hash-prefixed hex;
     * * `0xRRGGBB` — C-style hex (case-insensitive prefix);
     * * `RRGGBB` — bare six-digit hex (case-insensitive);
     * * `r g b` / `r,g,b` — decimal channels in 0..255.
     *
     * Separators may be freely mixed: newlines, spaces, tabs, commas and
     * semicolons all split tokens, so `"#ff0000, 128 64 32\n0x0000ff"`
     * parses into three colors. Empty segments are skipped.
     *
     * Decimal channels are aggregated greedily: every run of exactly three
     * consecutive bare decimal tokens becomes one color; a run of any other
     * length is rejected.
     *
     * @throws IllegalArgumentException listing **every** invalid token
     *         (hex and decimal alike) in one message, or complaining that
     *         no colors were found at all.
     */
    fun fromHexList(text: String, id: String, name: String): Palette {
        val invalid = ArrayList<String>()
        val colors = ArrayList<Int>()
        // Bare decimal tokens waiting for their r/g/b siblings.
        val pending = ArrayList<String>()

        fun flushPending() {
            if (pending.isEmpty()) return
            if (pending.size == 3) {
                val r = pending[0].toInt()
                val g = pending[1].toInt()
                val b = pending[2].toInt()
                colors.add((0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            } else {
                invalid.addAll(pending)
            }
            pending.clear()
        }

        for (token in text.split(HEX_LIST_SEPARATORS)) {
            if (token.isEmpty()) continue
            when {
                token.startsWith("#") -> {
                    flushPending()
                    addHex(token.substring(1), token, colors, invalid)
                }
                token.startsWith("0x", ignoreCase = true) -> {
                    flushPending()
                    addHex(token.substring(2), token, colors, invalid)
                }
                isHex6(token) -> {
                    flushPending()
                    colors.add((0xFF shl 24) or token.toInt(16))
                }
                else -> {
                    // Bare decimal channel (r/g/b component) or garbage.
                    val value = token.toIntOrNull()
                    if (value != null && value in 0..255) {
                        pending.add(token)
                    } else {
                        invalid.add(token)
                    }
                }
            }
        }
        flushPending()

        if (invalid.isNotEmpty()) {
            throw IllegalArgumentException(
                "Invalid color token(s): " + invalid.joinToString(", ") { "'$it'" }
            )
        }
        require(colors.isNotEmpty()) { "No colors found in hex list" }
        return Palette(id = id, name = name, colors = colors.toIntArray(), source = PaletteSource.IMPORTED)
    }

    // ------------------------------------------------------------------
    // Format detection
    // ------------------------------------------------------------------

    /**
     * Guesses which [Format] [text] is written in, by looking at its first
     * non-empty line: `JASC-PAL` means [Format.JASC], `GIMP Palette` means
     * [Format.GPL] (both compared case-insensitively), anything else —
     * including blank input — is treated as [Format.HEX], the catch-all.
     */
    fun guessFormat(text: String): Format {
        val first = text
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return Format.HEX
        return when {
            first.equals("JASC-PAL", ignoreCase = true) -> Format.JASC
            first.equals("GIMP Palette", ignoreCase = true) -> Format.GPL
            else -> Format.HEX
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Splits [text] into logical lines on CRLF/CR/LF, stripping a leading
     * UTF-8 BOM and dropping trailing blank lines produced by the final
     * line terminator(s). Leading and interior blank lines are preserved so
     * that 1-based indices still equal source line numbers.
     */
    private fun splitLines(text: String): List<String> {
        val raw = text.removePrefix("\uFEFF").split(LINE_BREAKS)
        var end = raw.size
        while (end > 1 && raw[end - 1].isBlank()) end--
        return raw.subList(0, end)
    }

    /**
     * Parses [digits] as `RRGGBB` hex, adding the opaque ARGB result to
     * [colors]; on failure records [original] in [invalid] instead.
     */
    private fun addHex(digits: String, original: String, colors: MutableList<Int>, invalid: MutableList<String>) {
        if (isHex6(digits)) {
            colors.add((0xFF shl 24) or digits.toInt(16))
        } else {
            invalid.add(original)
        }
    }

    /** True iff [s] is exactly six hex digits (case-insensitive). */
    private fun isHex6(s: String): Boolean =
        s.length == 6 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    /** Formats the RGB channels of [argb] as six lowercase hex digits. */
    private fun hex6(argb: Int): String =
        (argb and 0xFFFFFF).toString(16).padStart(6, '0')
}
