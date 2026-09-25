package com.pixellab.core.model

/** Where a [Palette] came from. */
enum class PaletteSource { BUILTIN, EXTRACTED, CUSTOM, IMPORTED }

/**
 * An immutable color palette: a fixed set of ARGB colors with stable indices.
 *
 * Palettes are the vocabulary of pixel art: draw operations snap to palette
 * colors, conversions map source images onto them, and exports embed them.
 * [findClosest] matches in CIELAB distance so perceptually-close hues win over
 * RGB-near misses.
 */
class Palette(
    /** Stable machine identifier, e.g. `"pico-8"`. */
    val id: String,
    /** Human-readable name. */
    val name: String,
    /** ARGB colors in stable index order. Never empty. */
    val colors: IntArray,
    /** Provenance of this palette. */
    val source: PaletteSource,
) {

    init {
        require(colors.isNotEmpty()) { "Palette '$id' must contain at least one color" }
    }

    /** Number of colors. */
    val size: Int get() = colors.size

    /** The color at [index]. */
    operator fun get(index: Int): Int {
        require(index in colors.indices) { "Palette index $index out of bounds for $size colors" }
        return colors[index]
    }

    /** Iterates all colors in index order. */
    operator fun iterator(): IntIterator = colors.iterator()

    /** `"#RRGGBB"` (lowercase) representation of the color at [index]. */
    fun toHex(index: Int): String {
        val argb = this[index]
        return "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
    }

    /**
     * Index of the palette color closest to [argb] in CIELAB distance. Ties
     * resolve to the lowest index, keeping results deterministic.
     */
    fun findClosest(argb: Int): Int {
        var best = 0
        var bestDist = Double.MAX_VALUE
        for (i in colors.indices) {
            val d = com.pixellab.core.palette.LabColor.distance(argb, colors[i])
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /**
     * Index of the palette color closest to [argb] using plain RGB squared
     * distance. Useful for bit-exact parity with the native GIF path.
     */
    fun findClosestRgb(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in colors.indices) {
            val c = colors[i]
            val dr = r - ((c shr 16) and 0xFF)
            val dg = g - ((c shr 8) and 0xFF)
            val db = b - (c and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /** Returns a palette with the same colors plus one extra at the end. */
    fun withColor(argb: Int): Palette =
        Palette(id, name, colors + argb, source)

    /** Returns a palette with [index] removed; indices above shift down. */
    fun withoutIndex(index: Int): Palette {
        require(colors.size > 1) { "Cannot remove the last color from palette '$id'" }
        require(index in colors.indices) { "Palette index $index out of bounds" }
        val next = colors.toMutableList()
        next.removeAt(index)
        return Palette(id, name, next.toIntArray(), source)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Palette) return false
        return id == other.id && name == other.name && source == other.source &&
            colors.contentEquals(other.colors)
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + colors.contentHashCode()) + source.hashCode()

    override fun toString(): String = "Palette($id, $size colors)"
}
