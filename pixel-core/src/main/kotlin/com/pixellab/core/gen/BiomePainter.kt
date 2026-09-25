package com.pixellab.core.gen

import com.pixellab.core.model.PixelFrame

/**
 * Biome painter: maps continuous height/moisture fields (any noise or
 * hand-authored source) to themed pixel colors with altitude banding,
 * water level and coastline highlighting.
 *
 * Pure function of the input fields; deterministic given the same fields
 * and parameters.
 */
object BiomePainter {

    /**
     * Named biome presets with hand-tuned color ramps. Each ramp is
     * (lowerHeightBound, color) pairs; moisture selects the ramp row
     * via [RampSet].
     */
    enum class Theme(
        /** Ramp rows from driest to wettest. */
        val ramps: Array<Array<Pair<Double, Int>>>,
        /** Sea level in the same `[0, 1]` units as the height field. */
        val seaLevel: Double,
        /** Color for cells below sea level. */
        val waterColor: Int,
        /** Highlight color for the coastline band. */
        val shoreColor: Int,
    ) {
        /** Lush overworld: sand → grass → forest → mountains → snow. */
        OVERWORLD(
            ramps = arrayOf(
                // Dry: savanna tones.
                arrayOf(
                    0.0 to 0xFFE8D8A0.toInt(), 0.42 to 0xFFD4C687.toInt(),
                    0.62 to 0xFF9E8B62.toInt(), 0.82 to 0xFF8A8A8A.toInt(), 0.95 to 0xFFF2F2F2.toInt(),
                ),
                // Temperate: grass/forest.
                arrayOf(
                    0.0 to 0xFFE8D8A0.toInt(), 0.42 to 0xFF7CB342.toInt(),
                    0.62 to 0xFF558B2F.toInt(), 0.82 to 0xFF7D8B76.toInt(), 0.95 to 0xFFF2F2F2.toInt(),
                ),
                // Wet: swamp/jungle.
                arrayOf(
                    0.0 to 0xFFDCD49A.toInt(), 0.42 to 0xFF5A9E3F.toInt(),
                    0.62 to 0xFF2E7D32.toInt(), 0.82 to 0xFF42664F.toInt(), 0.95 to 0xFFE8ECE8.toInt(),
                ),
            ),
            seaLevel = 0.40,
            waterColor = 0xFF3E6C9E.toInt(),
            shoreColor = 0xFFDBCE9A.toInt(),
        ),

        /** Volcanic: ash plains → scorched rock → lava cracks. */
        VOLCANIC(
            ramps = arrayOf(
                arrayOf(
                    0.0 to 0xFF2B2024.toInt(), 0.5 to 0xFF3A2C2C.toInt(),
                    0.75 to 0xFF54413C.toInt(), 0.92 to 0xFF8C3B2E.toInt(),
                ),
                arrayOf(
                    0.0 to 0xFF30242A.toInt(), 0.5 to 0xFF442F30.toInt(),
                    0.75 to 0xFF6B4638.toInt(), 0.92 to 0xFFB04A2F.toInt(),
                ),
                arrayOf(
                    0.0 to 0xFF382A30.toInt(), 0.5 to 0xFF4E3438.toInt(),
                    0.75 to 0xFF7A4A3C.toInt(), 0.92 to 0xFFE06A2B.toInt(),
                ),
            ),
            seaLevel = 0.30,
            waterColor = 0xFF8C2B1E.toInt(),
            shoreColor = 0xFFC88C52.toInt(),
        ),

        /** Frozen: ice shelf → tundra → glacier. */
        FROZEN(
            ramps = arrayOf(
                arrayOf(
                    0.0 to 0xFFD8E4EC.toInt(), 0.5 to 0xFFC2D4E2.toInt(),
                    0.75 to 0xFFAAC4D8.toInt(), 0.92 to 0xFFF0F6FA.toInt(),
                ),
                arrayOf(
                    0.0 to 0xFFD2E0EA.toInt(), 0.5 to 0xFFB4CADC.toInt(),
                    0.75 to 0xFF94B2CC.toInt(), 0.92 to 0xFFF4F8FB.toInt(),
                ),
                arrayOf(
                    0.0 to 0xFFCCDCE8.toInt(), 0.5 to 0xFFA8C0D4.toInt(),
                    0.75 to 0xFF7C9EC0.toInt(), 0.92 to 0xFFF8FBFE.toInt(),
                ),
            ),
            seaLevel = 0.35,
            waterColor = 0xFF9CB8D8.toInt(),
            shoreColor = 0xFFEDF3F8.toInt(),
        ),
    }

    /**
     * Paints a biome map.
     *
     * @param height `width*height` values in `[0, 1]` (0 = deep, 1 = peak).
     * @param moisture `width*height` values in `[0, 1]` (0 = arid, 1 = wet).
     * @param theme color scheme.
     * @param shoreBand height range above sea level painted with
     *   [Theme.shoreColor] (default 0.03).
     * @param waterDepthTint when true, water darkens smoothly below sea
     *   level (two bands); when false, flat [Theme.waterColor].
     */
    fun paint(
        width: Int,
        height: Int,
        heightField: DoubleArray,
        moistureField: DoubleArray,
        theme: Theme = Theme.OVERWORLD,
        shoreBand: Double = 0.03,
        waterDepthTint: Boolean = true,
    ): PixelFrame {
        require(heightField.size == width * height) { "height field size mismatch" }
        require(moistureField.size == width * height) { "moisture field size mismatch" }
        require(shoreBand >= 0.0) { "shoreBand must be ≥ 0" }
        val out = IntArray(width * height)
        for (i in out.indices) {
            val h = heightField[i].coerceIn(0.0, 1.0)
            val m = moistureField[i].coerceIn(0.0, 1.0)
            out[i] = when {
                h < theme.seaLevel -> {
                    if (!waterDepthTint) theme.waterColor
                    else {
                        // Two depth bands: near-shore water lightens 12%.
                        val nearShore = h >= theme.seaLevel - 0.08
                        if (nearShore) lighten(theme.waterColor, 0.12) else theme.waterColor
                    }
                }
                h < theme.seaLevel + shoreBand -> theme.shoreColor
                else -> rampColor(theme, h, m)
            }
        }
        return PixelFrame.of(width, height, out)
    }

    /**
     * Convenience overload composing [paint] with ValueNoise-driven
     * fields (the common "world map" recipe): height = low-frequency
     * noise, moisture = an independent mid-frequency noise.
     */
    fun paintFromNoise(
        width: Int,
        height: Int,
        seed: Long,
        theme: Theme = Theme.OVERWORLD,
        heightScale: Double = 0.02,
        moistureScale: Double = 0.035,
    ): PixelFrame {
        val hn = ValueNoise(seed)
        val mn = ValueNoise(seed xor 0x5DEECE66DL)
        val hf = DoubleArray(width * height)
        val mf = DoubleArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                hf[i] = 0.5 + 0.5 * hn.fbm((x * heightScale).toFloat(), (y * heightScale).toFloat())
                mf[i] = 0.5 + 0.5 * mn.fbm((x * moistureScale + 100.0).toFloat(), (y * moistureScale + 100.0).toFloat())
            }
        }
        return paint(width, height, hf, mf, theme)
    }

    /** Picks the moisture-row ramp and interpolates between bounds. */
    private fun rampColor(theme: Theme, h: Double, m: Double): Int {
        val row = theme.ramps[((m * theme.ramps.size).toInt()).coerceIn(0, theme.ramps.size - 1)]
        var lo = row[0]
        for (bound in row) {
            if (bound.first <= h) lo = bound else break
        }
        return lo.second
    }

    private fun lighten(argb: Int, fraction: Double): Int {
        val a = argb ushr 24
        val r = (argb ushr 16 and 0xFF)
        val g = (argb ushr 8 and 0xFF)
        val b = (argb and 0xFF)
        val nr = (r + (255 - r) * fraction).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * fraction).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * fraction).toInt().coerceIn(0, 255)
        return a shl 24 or (nr shl 16) or (ng shl 8) or nb
    }
}
