package com.pixellab.core.color

import com.pixellab.core.model.PixelFrame
import kotlin.math.abs

/**
 * Human-readable color naming: the CSS named-color list (147 entries)
 * plus nearest-match naming in Lab space for arbitrary colors.
 *
 * Two naming modes:
 *  * [exactName] — only returns a name for exact CSS matches (fast,
 *    dictionary lookup, no distance computation),
 *  * [nearestName] — falls back to the Lab-nearest CSS color and reports
 *    the perceptual distance, so any color gets a useful, stable label
 *    ("nearest: darksalmon, ΔE ≈ 12.4").
 */
object ColorNamer {

    /** A named CSS color. */
    data class NamedColor(val name: String, val argb: Int)

    /** All 147 CSS named colors (opaque). */
    val CSS_COLORS: List<NamedColor> = listOf(
        NamedColor("aliceblue", 0xFFF0F8FF.toInt()),
        NamedColor("antiquewhite", 0xFFFAEBD7.toInt()),
        NamedColor("aqua", 0xFF00FFFF.toInt()),
        NamedColor("aquamarine", 0xFF7FFFD4.toInt()),
        NamedColor("azure", 0xFFF0FFFF.toInt()),
        NamedColor("beige", 0xFFF5F5DC.toInt()),
        NamedColor("bisque", 0xFFFFE4C4.toInt()),
        NamedColor("black", 0xFF000000.toInt()),
        NamedColor("blanchedalmond", 0xFFFFEBCD.toInt()),
        NamedColor("blue", 0xFF0000FF.toInt()),
        NamedColor("blueviolet", 0xFF8A2BE2.toInt()),
        NamedColor("brown", 0xFFA52A2A.toInt()),
        NamedColor("burlywood", 0xFFDEB887.toInt()),
        NamedColor("cadetblue", 0xFF5F9EA0.toInt()),
        NamedColor("chartreuse", 0xFF7FFF00.toInt()),
        NamedColor("chocolate", 0xFFD2691E.toInt()),
        NamedColor("coral", 0xFFFF7F50.toInt()),
        NamedColor("cornflowerblue", 0xFF6495ED.toInt()),
        NamedColor("cornsilk", 0xFFFFF8DC.toInt()),
        NamedColor("crimson", 0xFFDC143C.toInt()),
        NamedColor("cyan", 0xFF00FFFF.toInt()),
        NamedColor("darkblue", 0xFF00008B.toInt()),
        NamedColor("darkcyan", 0xFF008B8B.toInt()),
        NamedColor("darkgoldenrod", 0xFFB8860B.toInt()),
        NamedColor("darkgray", 0xFFA9A9A9.toInt()),
        NamedColor("darkgreen", 0xFF006400.toInt()),
        NamedColor("darkgrey", 0xFFA9A9A9.toInt()),
        NamedColor("darkkhaki", 0xFFBDB76B.toInt()),
        NamedColor("darkmagenta", 0xFF8B008B.toInt()),
        NamedColor("darkolivegreen", 0xFF556B2F.toInt()),
        NamedColor("darkorange", 0xFFFF8C00.toInt()),
        NamedColor("darkorchid", 0xFF9932CC.toInt()),
        NamedColor("darkred", 0xFF8B0000.toInt()),
        NamedColor("darksalmon", 0xFFE9967A.toInt()),
        NamedColor("darkseagreen", 0xFF8FBC8F.toInt()),
        NamedColor("darkslateblue", 0xFF483D8B.toInt()),
        NamedColor("darkslategray", 0xFF2F4F4F.toInt()),
        NamedColor("darkslategrey", 0xFF2F4F4F.toInt()),
        NamedColor("darkturquoise", 0xFF00CED1.toInt()),
        NamedColor("darkviolet", 0xFF9400D3.toInt()),
        NamedColor("deeppink", 0xFFFF1493.toInt()),
        NamedColor("deepskyblue", 0xFF00BFFF.toInt()),
        NamedColor("dimgray", 0xFF696969.toInt()),
        NamedColor("dimgrey", 0xFF696969.toInt()),
        NamedColor("dodgerblue", 0xFF1E90FF.toInt()),
        NamedColor("firebrick", 0xFFB22222.toInt()),
        NamedColor("floralwhite", 0xFFFFFAF0.toInt()),
        NamedColor("forestgreen", 0xFF228B22.toInt()),
        NamedColor("fuchsia", 0xFFFF00FF.toInt()),
        NamedColor("gainsboro", 0xFFDCDCDC.toInt()),
        NamedColor("ghostwhite", 0xFFF8F8FF.toInt()),
        NamedColor("gold", 0xFFFFD700.toInt()),
        NamedColor("goldenrod", 0xFFDAA520.toInt()),
        NamedColor("gray", 0xFF808080.toInt()),
        NamedColor("green", 0xFF008000.toInt()),
        NamedColor("greenyellow", 0xFFADFF2F.toInt()),
        NamedColor("grey", 0xFF808080.toInt()),
        NamedColor("honeydew", 0xFFF0FFF0.toInt()),
        NamedColor("hotpink", 0xFFFF69B4.toInt()),
        NamedColor("indianred", 0xFFCD5C5C.toInt()),
        NamedColor("indigo", 0xFF4B0082.toInt()),
        NamedColor("ivory", 0xFFFFFFF0.toInt()),
        NamedColor("khaki", 0xFFF0E68C.toInt()),
        NamedColor("lavender", 0xFFE6E6FA.toInt()),
        NamedColor("lavenderblush", 0xFFFFF0F5.toInt()),
        NamedColor("lawngreen", 0xFF7CFC00.toInt()),
        NamedColor("lemonchiffon", 0xFFFFFACD.toInt()),
        NamedColor("lightblue", 0xFFADD8E6.toInt()),
        NamedColor("lightcoral", 0xFFF08080.toInt()),
        NamedColor("lightcyan", 0xFFE0FFFF.toInt()),
        NamedColor("lightgoldenrodyellow", 0xFFFAFAD2.toInt()),
        NamedColor("lightgray", 0xFFD3D3D3.toInt()),
        NamedColor("lightgreen", 0xFF90EE90.toInt()),
        NamedColor("lightgrey", 0xFFD3D3D3.toInt()),
        NamedColor("lightpink", 0xFFFFB6C1.toInt()),
        NamedColor("lightsalmon", 0xFFFFA07A.toInt()),
        NamedColor("lightseagreen", 0xFF20B2AA.toInt()),
        NamedColor("lightskyblue", 0xFF87CEFA.toInt()),
        NamedColor("lightslategray", 0xFF778899.toInt()),
        NamedColor("lightslategrey", 0xFF778899.toInt()),
        NamedColor("lightsteelblue", 0xFFB0C4DE.toInt()),
        NamedColor("lightyellow", 0xFFFFFFE0.toInt()),
        NamedColor("lime", 0xFF00FF00.toInt()),
        NamedColor("limegreen", 0xFF32CD32.toInt()),
        NamedColor("linen", 0xFFFAF0E6.toInt()),
        NamedColor("magenta", 0xFFFF00FF.toInt()),
        NamedColor("maroon", 0xFF800000.toInt()),
        NamedColor("mediumaquamarine", 0xFF66CDAA.toInt()),
        NamedColor("mediumblue", 0xFF0000CD.toInt()),
        NamedColor("mediumorchid", 0xFFBA55D3.toInt()),
        NamedColor("mediumpurple", 0xFF9370DB.toInt()),
        NamedColor("mediumseagreen", 0xFF3CB371.toInt()),
        NamedColor("mediumslateblue", 0xFF7B68EE.toInt()),
        NamedColor("mediumspringgreen", 0xFF00FA9A.toInt()),
        NamedColor("mediumturquoise", 0xFF48D1CC.toInt()),
        NamedColor("mediumvioletred", 0xFFC71585.toInt()),
        NamedColor("midnightblue", 0xFF191970.toInt()),
        NamedColor("mintcream", 0xFFF5FFFA.toInt()),
        NamedColor("mistyrose", 0xFFFFE4E1.toInt()),
        NamedColor("moccasin", 0xFFFFE4B5.toInt()),
        NamedColor("navajowhite", 0xFFFFDEAD.toInt()),
        NamedColor("navy", 0xFF000080.toInt()),
        NamedColor("oldlace", 0xFFFDF5E6.toInt()),
        NamedColor("olive", 0xFF808000.toInt()),
        NamedColor("olivedrab", 0xFF6B8E23.toInt()),
        NamedColor("orange", 0xFFFFA500.toInt()),
        NamedColor("orangered", 0xFFFF4500.toInt()),
        NamedColor("orchid", 0xFFDA70D6.toInt()),
        NamedColor("palegoldenrod", 0xFFEEE8AA.toInt()),
        NamedColor("palegreen", 0xFF98FB98.toInt()),
        NamedColor("paleturquoise", 0xFFAFEEEE.toInt()),
        NamedColor("palevioletred", 0xFFDB7093.toInt()),
        NamedColor("papayawhip", 0xFFFFEFD5.toInt()),
        NamedColor("peachpuff", 0xFFFFDAB9.toInt()),
        NamedColor("peru", 0xFFCD853F.toInt()),
        NamedColor("pink", 0xFFFFC0CB.toInt()),
        NamedColor("plum", 0xFFDDA0DD.toInt()),
        NamedColor("powderblue", 0xFFB0E0E6.toInt()),
        NamedColor("purple", 0xFF800080.toInt()),
        NamedColor("rebeccapurple", 0xFF663399.toInt()),
        NamedColor("red", 0xFFFF0000.toInt()),
        NamedColor("rosybrown", 0xFFBC8F8F.toInt()),
        NamedColor("royalblue", 0xFF4169E1.toInt()),
        NamedColor("saddlebrown", 0xFF8B4513.toInt()),
        NamedColor("salmon", 0xFFFA8072.toInt()),
        NamedColor("sandybrown", 0xFFF4A460.toInt()),
        NamedColor("seagreen", 0xFF2E8B57.toInt()),
        NamedColor("seashell", 0xFFFFF5EE.toInt()),
        NamedColor("sienna", 0xFFA0522D.toInt()),
        NamedColor("silver", 0xFFC0C0C0.toInt()),
        NamedColor("skyblue", 0xFF87CEEB.toInt()),
        NamedColor("slateblue", 0xFF6A5ACD.toInt()),
        NamedColor("slategray", 0xFF708090.toInt()),
        NamedColor("slategrey", 0xFF708090.toInt()),
        NamedColor("snow", 0xFFFFFAFA.toInt()),
        NamedColor("springgreen", 0xFF00FF7F.toInt()),
        NamedColor("steelblue", 0xFF4682B4.toInt()),
        NamedColor("tan", 0xFFD2B48C.toInt()),
        NamedColor("teal", 0xFF008080.toInt()),
        NamedColor("thistle", 0xFFD8BFD8.toInt()),
        NamedColor("tomato", 0xFFFF6347.toInt()),
        NamedColor("turquoise", 0xFF40E0D0.toInt()),
        NamedColor("violet", 0xFFEE82EE.toInt()),
        NamedColor("wheat", 0xFFF5DEB3.toInt()),
        NamedColor("white", 0xFFFFFFFF.toInt()),
        NamedColor("whitesmoke", 0xFFF5F5F5.toInt()),
        NamedColor("yellow", 0xFFFFFF00.toInt()),
        NamedColor("yellowgreen", 0xFF9ACD32.toInt()),
    )

    private val exactIndex: Map<Int, List<String>> =
        CSS_COLORS.groupBy({ it.argb }, { it.name })

    /**
     * Exact-match name(s) for [argb] (transparent is "transparent";
     * aliases share one entry, so several names can match). Empty when
     * the color is not in the CSS list.
     */
    fun exactName(argb: Int): List<String> {
        if (argb ushr 24 == 0) return listOf("transparent")
        return exactIndex[argb] ?: emptyList()
    }

    /**
     * Nearest CSS color in Lab perceptual distance for [argb], with the
     * distance reported as an approximate ΔE. Transparent input maps to
     * "transparent" with distance 0.
     */
    fun nearestName(argb: Int): NearestMatch {
        if (argb ushr 24 == 0) return NearestMatch("transparent", 0xFFFFFF and 0, 0.0, exact = true)
        var bestName = ""
        var bestColor = 0
        var bestDist = Double.MAX_VALUE
        for (c in CSS_COLORS) {
            val d = labDistance(argb, c.argb)
            if (d < bestDist) {
                bestDist = d
                bestName = c.name
                bestColor = c.argb
            }
        }
        val exact = bestDist < 1e-9
        return NearestMatch(bestName, bestColor, bestDist, exact)
    }

    /**
     * Names every distinct opaque color of [frame] (transparent ignored)
     * and returns `(argb → name)` in descending pixel-count order.
     */
    fun nameFrameColors(frame: PixelFrame): List<FrameColorName> {
        val counts = HashMap<Int, Int>()
        for (p in frame.pixels) {
            if (p ushr 24 == 0) continue
            counts.merge(p, 1, Int::plus)
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { (color, count) ->
                val nearest = nearestName(color)
                FrameColorName(color, nearest.name, nearest.distance, count)
            }
    }

    /** Result of a nearest-match lookup. */
    data class NearestMatch(
        val name: String,
        val argb: Int,
        /** Approximate Lab ΔE to the named color. */
        val distance: Double,
        val exact: Boolean,
    )

    /** One entry of [nameFrameColors]. */
    data class FrameColorName(val argb: Int, val name: String, val deltaE: Double, val pixelCount: Int)

    /** Compact Lab-ish distance (weighted RGB→Lab proxy, no allocation). */
    private fun labDistance(a: Int, b: Int): Double {
        // Weighted RGB distance approximating perceptual distance well
        // enough for naming (cheap and allocation-free).
        val dr = (a ushr 16 and 0xFF) - (b ushr 16 and 0xFF)
        val dg = (a ushr 8 and 0xFF) - (b ushr 8 and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        val meanR = ((a ushr 16 and 0xFF) + (b ushr 16 and 0xFF)) / 2.0
        val wr = 2.0 + meanR / 256.0
        val wg = 4.0
        val wb = 2.0 + (255.0 - meanR) / 256.0
        return kotlin.math.sqrt(wr * dr * dr + wg * dg * dg + wb * db * db)
    }
}
