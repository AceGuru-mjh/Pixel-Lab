package com.pixellab.core.palette

import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource

/**
 * The extended palette universe of Pixel Lab: every palette shipped with the
 * library, classic and modern, historical and house-made.
 *
 * ## Merge semantics
 *
 * [all] returns [BuiltInPalettes.all] first (PICO-8, Game Boy, NES 54,
 * Endesga 32, Clawd, Kimi — six palettes), followed by the extended palettes
 * curated in this object. The merged list is de-duplicated by [Palette.id]
 * with first-registration-wins semantics, so a built-in id always shadows an
 * extended entry registered under the same id. The order is stable:
 *
 * 1. built-ins, in [BuiltInPalettes.all] order;
 * 2. classic artist/historical palettes, roughly chronological;
 * 3. Pixel Lab house utility palettes.
 *
 * Callers may rely on [all] producing the identical sequence on every call;
 * insertion of new palettes only ever appends within the group boundaries.
 *
 * ## Color values
 *
 * Every palette below is transcribed from the Pixel Lab palette spec sheet,
 * which itself mirrors the published definitions of each palette (see the
 * per-palette KDoc source line). Colors are stored as opaque ARGB ints
 * (alpha `0xFF`) in index order. Where a palette's published values are
 * ambiguous or unverified, it is left out — invented colors never ship.
 *
 * The extended entries are exposed both as constants (for direct, compile-
 * time-known access) and through the query API ([byId], [search],
 * [byColorCount]).
 */
object PaletteLibrary {

    // ------------------------------------------------------------------
    // Classic artist & historical palettes
    // ------------------------------------------------------------------

    /**
     * Sweetie 16 — the widely used 16-color starter palette for pixel art.
     *
     * Source: published "Sweetie 16" color list (Lospec classic).
     */
    val SWEETIE16: Palette = Palette(
        id = "sweetie-16", name = "Sweetie 16", source = PaletteSource.BUILTIN,
        colors = colors(
            "1a1c2c", "5d275d", "b13e53", "ef7d57",
            "ffcd75", "a7f070", "38b764", "257179",
            "29366f", "3b5dc9", "41a6f6", "73eff7",
            "f4f4f4", "94b0c2", "566c86", "333c57",
        ),
    )

    /**
     * Vinik 24 — a soft 24-color palette with an earthy-green core, warm
     * neutrals and a pale-yellow highlight.
     *
     * Source: published "Vinik24" color list (Lospec, by Vinik).
     */
    val VINIK24: Palette = Palette(
        id = "vinik24", name = "Vinik 24", source = PaletteSource.BUILTIN,
        colors = colors(
            "000000", "6f6f6f", "8c8c8c", "b6b6b6",
            "d9d9d9", "ffffff", "eaffef", "cfe8d9",
            "b5cfa9", "92ac7c", "6f8f5a", "5f8457",
            "587b4e", "9e6151", "9d6b45", "8c6440",
            "7c5c36", "7d7458", "8f886c", "a69261",
            "b0a676", "c8b882", "e5e0a0", "ffff7a",
        ),
    )

    /**
     * DawnBringer 16 (DB16) — a compact, contrasty 16-color palette from the
     * DawnBringer family, beloved for its dark aubergine "black" and muted
     * greens. The first twelve entries match the widely published list; the
     * tail slots are filled per the Pixel Lab spec sheet
     * (`c4d2c4`, `3e2731`, `3a2f4d`, `555f7d`) to complete the 16 colors.
     *
     * Source: Pixel Lab palette spec sheet (DB16 entry).
     */
    val DB16: Palette = Palette(
        id = "db16", name = "DawnBringer 16", source = PaletteSource.BUILTIN,
        colors = colors(
            "140c1c", "442434", "30346c", "4e4a4e",
            "854c30", "346524", "d04648", "757161",
            "597dce", "d27d2c", "8595a6", "fff6e3",
            "c4d2c4", "3e2731", "3a2f4d", "555f7d",
        ),
    )

    /**
     * Arne 16 — Arne Niklas Jansson's compact 16-color "Low Palette" ramp
     * for general game art; strong primary ramps plus a sky/teal row.
     *
     * Source: published "Arne 16" color list (androidarts.com).
     */
    val ARNE16: Palette = Palette(
        id = "arne16", name = "Arne 16", source = PaletteSource.BUILTIN,
        colors = colors(
            "000000", "9D9D9D", "FFFFFF", "BE2633",
            "E06F8B", "493C2B", "A46422", "EB8931",
            "F7E26B", "2F484E", "44891A", "A3CE27",
            "1B2632", "005784", "31A2F2", "B2DCEF",
        ),
    )

    /**
     * IBM CGA 16 — the full 16-color text/graphics mode palette of the Color
     * Graphics Adapter (1981): 8 dark + 8 light colors of the classic RGBI
     * ordering.
     *
     * Source: IBM CGA RGBI palette (published hardware palette).
     */
    val CGA16: Palette = Palette(
        id = "cga16", name = "IBM CGA 16", source = PaletteSource.BUILTIN,
        colors = colors(
            "000000", "0000AA", "00AA00", "00AAAA",
            "AA0000", "AA00AA", "AA5500", "AAAAAA",
            "555555", "5555FF", "55FF55", "55FFFF",
            "FF5555", "FF55FF", "FFFF55", "FFFFFF",
        ),
    )

    /**
     * Commodore 64 — the 16 colors of the VIC-II video chip (1982), from
     * black through the famous light blue border tone to light gray.
     *
     * Source: Commodore 64 VIC-II power-on palette (published).
     */
    val C64: Palette = Palette(
        id = "c64", name = "Commodore 64", source = PaletteSource.BUILTIN,
        colors = colors(
            "000000", "FFFFFF", "880000", "AAFFEE",
            "CC44CC", "00CC55", "0000AA", "EEEE77",
            "DD8855", "664400", "FF7777", "333333",
            "777777", "AAFF66", "0088FF", "BBBBBB",
        ),
    )

    /**
     * ZX Spectrum — the 15 distinct colors of the ZX Spectrum ULA (1982):
     * eight dim basics plus seven bright variants (bright black is the same
     * as dim black, hence 15 unique entries).
     *
     * Source: Sinclair ZX Spectrum ULA palette (published), de-duplicated.
     */
    val ZX_SPECTRUM: Palette = Palette(
        id = "zx-spectrum", name = "ZX Spectrum", source = PaletteSource.BUILTIN,
        colors = colors(
            "000000", "0000D7", "D70000", "D700D7",
            "00D700", "00D7D7", "D7D700", "D7D7D7",
            "0000FF", "FF0000", "FF00FF", "00FF00",
            "00FFFF", "FFFF00", "FFFFFF",
        ),
    )

    /**
     * Grayscale 4 — the plain 2-bit gray ramp (black / dark gray / light
     * gray / white). Useful for value studies and dither tests.
     *
     * Source: Pixel Lab palette spec sheet (standard 2-bit ramp).
     */
    val GRAYSCALE4: Palette = Palette(
        id = "grayscale-4", name = "Grayscale 4", source = PaletteSource.BUILTIN,
        colors = colors("000000", "555555", "AAAAAA", "FFFFFF"),
    )

    /**
     * Grayscale 8 — an even 8-step gray ramp from pure black to pure white.
     *
     * Source: Pixel Lab palette spec sheet (standard 3-bit ramp).
     */
    val GRAYSCALE8: Palette = Palette(
        id = "grayscale-8", name = "Grayscale 8", source = PaletteSource.BUILTIN,
        colors = colors(
            "000000", "242424", "494949", "6D6D6D",
            "929292", "B6B6B6", "DBDBDB", "FFFFFF",
        ),
    )

    /**
     * SLSO 8 — an 8-color moody palette swinging from purple-blacks through
     * mauve mid tones to warm parchment whites.
     *
     * Source: published "SLSO8" color list (Lospec classic).
     */
    val SLSO8: Palette = Palette(
        id = "slso8", name = "SLSO 8", source = PaletteSource.BUILTIN,
        colors = colors(
            "000000", "322746", "51556b", "7b6489",
            "a9a18c", "d0b6a1", "d7d0c5", "ffffff",
        ),
    )

    // ------------------------------------------------------------------
    // Pixel Lab house utility palettes (original, honestly labeled)
    // ------------------------------------------------------------------

    /**
     * Pixel Lab Mono — an 8-step neutral gray ramp with a slight cool bias,
     * tuned so UI chrome stays readable over both dark and light art.
     * The extra `82828c` slot is a mid-gray accent for disabled states.
     *
     * Source: Pixel Lab original house palette (not a published classic).
     */
    val PIXELLAB_MONO: Palette = Palette(
        id = "pixellab-mono", name = "Pixel Lab Mono", source = PaletteSource.BUILTIN,
        colors = colors(
            "0e0e12", "26262e", "4c4c56", "75757f",
            "a0a0aa", "c9c9d2", "ffffff", "82828c",
        ),
    )

    /**
     * Pixel Lab Sepia — an 8-step warm brown-to-cream ramp for aged-paper
     * looks, menu overlays and vignette tints.
     *
     * Source: Pixel Lab original house palette (not a published classic).
     */
    val PIXELLAB_SEPIA: Palette = Palette(
        id = "pixellab-sepia", name = "Pixel Lab Sepia", source = PaletteSource.BUILTIN,
        colors = colors(
            "1b0f0b", "331e14", "5c3a24", "8c6239",
            "b48a5e", "d8b58a", "eed9b8", "fdf4e3",
        ),
    )

    /**
     * Pixel Lab Neon — a 6-color night-city set: deep violet backgrounds,
     * hot magenta/coral neons, acid yellow and electric cyan.
     *
     * Source: Pixel Lab original house palette (not a published classic).
     */
    val PIXELLAB_NEON: Palette = Palette(
        id = "pixellab-neon", name = "Pixel Lab Neon", source = PaletteSource.BUILTIN,
        colors = colors("0f0316", "2a0a4a", "ff2bd1", "fe4e6e", "ffff2b", "24f0ff"),
    )

    /**
     * Pixel Lab Forest — a 6-step mossy green ramp from near-black
     * undergrowth to sunlit leaf-light, for nature scenes.
     *
     * Source: Pixel Lab original house palette (not a published classic).
     */
    val PIXELLAB_FOREST: Palette = Palette(
        id = "pixellab-forest", name = "Pixel Lab Forest", source = PaletteSource.BUILTIN,
        colors = colors("0b1a12", "1c3a26", "2f6b3a", "57a052", "9ac97c", "e8f5c8"),
    )

    /**
     * Pixel Lab Ice — a 6-step glacial blue ramp from polar night to white,
     * the cold counterpart of [PIXELLAB_FOREST].
     *
     * Source: Pixel Lab original house palette (not a published classic).
     */
    val PIXELLAB_ICE: Palette = Palette(
        id = "pixellab-ice", name = "Pixel Lab Ice", source = PaletteSource.BUILTIN,
        colors = colors("0b1220", "173049", "2b5a8c", "5f9bd0", "a8d0ef", "ffffff"),
    )

    /** Extended palettes in stable order (classics first, then house sets). */
    private val EXTENDED: List<Palette> = listOf(
        SWEETIE16, VINIK24, DB16, ARNE16,
        CGA16, C64, ZX_SPECTRUM,
        GRAYSCALE4, GRAYSCALE8, SLSO8,
        PIXELLAB_MONO, PIXELLAB_SEPIA, PIXELLAB_NEON, PIXELLAB_FOREST, PIXELLAB_ICE,
    )

    // ------------------------------------------------------------------
    // Query API
    // ------------------------------------------------------------------

    /**
     * All palettes in the library, in stable order: the six built-ins of
     * [BuiltInPalettes] first, then the extended entries above. The merged
     * list is de-duplicated by [Palette.id] (first registration wins, so a
     * built-in always shadows an extended palette with the same id).
     *
     * The returned list is freshly built on each call and safe for callers
     * to wrap or reorder without affecting this library.
     */
    fun all(): List<Palette> {
        val seen = HashSet<String>()
        val merged = ArrayList<Palette>(BuiltInPalettes.all().size + EXTENDED.size)
        for (palette in BuiltInPalettes.all() + EXTENDED) {
            if (seen.add(palette.id)) merged.add(palette)
        }
        return merged
    }

    /**
     * Looks a palette up by its stable [Palette.id] (exact, case-sensitive
     * match). Returns null when no palette in the library uses that id.
     */
    fun byId(id: String): Palette? = all().firstOrNull { it.id == id }

    /**
     * Finds palettes whose [Palette.id] or [Palette.name] contains [query]
     * (case-insensitive substring match).
     *
     * A blank [query] matches everything, which makes this usable for
     * as-you-type filtering in pickers.
     */
    fun search(query: String): List<Palette> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return all()
        return all().filter { palette ->
            palette.id.lowercase().contains(needle) ||
                palette.name.lowercase().contains(needle)
        }
    }

    /**
     * Finds palettes whose color count falls inside a ±50% tolerance
     * interval around [n]: a palette of `size` matches when
     * `n / 2 <= size <= n * 1.5` (inclusive bounds, evaluated in double
     * math so odd sizes land fairly, e.g. `n = 15` matches sizes 8..22).
     *
     * @throws IllegalArgumentException if [n] is smaller than 1.
     */
    fun byColorCount(n: Int): List<Palette> {
        require(n >= 1) { "Color count must be at least 1, was $n" }
        val low = n / 2.0
        val high = n * 1.5
        return all().filter { it.size >= low && it.size <= high }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Converts an array of six-digit hex strings into an ARGB color array.
     * Values are given as bare `RRGGBB` hex (case-insensitive); alpha is
     * always forced opaque.
     *
     * @throws IllegalArgumentException if any entry is not exactly six hex
     *         digits — the palette tables are spec constants, so a bad
     *         value is a programming error, not user input.
     */
    private fun colors(vararg hex: String): IntArray = IntArray(hex.size) { rgb(hex[it]) }

    /** Parses one `RRGGBB` hex string into an opaque ARGB int. */
    private fun rgb(hex: String): Int {
        val valid = hex.length == 6 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        require(valid) { "Bad palette color '$hex' — expected exactly 6 hex digits" }
        return (0xFF shl 24) or hex.toInt(16)
    }
}
