package com.pixellab.core.gen

import com.pixellab.core.model.PixelFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural texture generator: cloud, wood, stone, marble and brick
 * rasters plus two frame post-effects (scanlines, vignette).
 *
 * Every generator is a **pure function of its parameters**:
 *  * the color of pixel `(x, y)` comes from a [ValueNoise] field seeded by
 *    [seed] (or from a deterministic layout, like bricks/checker);
 *  * the field value is mapped onto the caller-supplied [palette] ramp,
 *    which runs **low → high** (index 0 is the darkest end);
 *  * no RNG state leaks between calls, no wall-clock, no platform calls —
 *    the same `(width, height, palette, seed)` always produces
 *    byte-identical pixels.
 *
 * Palette entries are copied verbatim, so palettes with alpha produce
 * translucent textures; typical use passes opaque colors.
 *
 * Value → palette index mapping shared by the noise textures:
 * ```
 * index = round(v * (palette.size - 1))          (v clamped to [0, 1])
 * ```
 * which puts `v = 0` on `palette[0]` and `v = 1` on the last entry and
 * rounds halves up.
 */
object TextureGen {

    // ------------------------------------------------------------------
    // Noise-based materials
    // ------------------------------------------------------------------

    /**
     * Soft cloud texture: plain fbm value noise mapped onto the ramp.
     *
     * The noise is sampled at `0.25`-pixel frequency with [octaves] layers,
     * which yields blobs a few pixels wide on typical 16–64 px canvases;
     * lower the octaves for flatter skies, raise them for billowy detail.
     *
     * @param width canvas width in pixels.
     * @param height canvas height in pixels.
     * @param palette color ramp, dark to bright, at least one entry.
     * @param seed deterministic noise seed.
     * @param octaves fbm layer count in `1..8` (default 4).
     */
    fun clouds(width: Int, height: Int, palette: IntArray, seed: Long, octaves: Int = 4): PixelFrame {
        requireDimensions(width, height)
        requirePalette(palette)
        require(octaves in 1..8) { "octaves must be in [1, 8] (was $octaves)" }
        val noise = ValueNoise(seed)
        return mapToPalette(width, height, palette) { x, y -> noise.fbm(x * 0.25f, y * 0.25f, octaves) }
    }

    /**
     * Wood grain: concentric growth rings around the center, perturbed by
     * noise so the rings wobble like real timber.
     *
     * Formula (per pixel):
     * ```
     * nx = (x - cx) / max(cx, 1);  ny = (y - cy) / max(cy, 1)
     * r  = sqrt(nx^2 + ny^2) + (fbm(x·0.3, y·0.3) - 0.5) · 0.25
     * v  = 0.5 + 0.5 · sin(π · rings · r)
     * ```
     * [rings] is the number of *half*-cycles of the sine across the unit
     * radius, i.e. roughly the count of light/dark ring pairs visible
     * between the center and the edge.
     *
     * @param width canvas width in pixels.
     * @param height canvas height in pixels.
     * @param palette color ramp, dark to bright (bark → highlight), at least
     *   one entry.
     * @param seed deterministic noise seed.
     * @param rings ring density, `>= 1` (default 6).
     */
    fun wood(width: Int, height: Int, palette: IntArray, seed: Long, rings: Int = 6): PixelFrame {
        requireDimensions(width, height)
        requirePalette(palette)
        require(rings >= 1) { "rings must be >= 1 (was $rings)" }
        val noise = ValueNoise(seed)
        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        val dx = maxOf(cx, 1f)
        val dy = maxOf(cy, 1f)
        return mapToPalette(width, height, palette) { x, y ->
            val nx = (x - cx) / dx
            val ny = (y - cy) / dy
            val r = sqrt(nx * nx + ny * ny) + (noise.fbm(x * 0.3f, y * 0.3f) - 0.5f) * 0.25f
            (0.5 + 0.5 * sin(PI * rings * r)).toFloat()
        }
    }

    /**
     * Rock/crackle texture: fbm pushed through the ridge transform
     * `1 - |2v - 1|`, so the field's level curves become bright creases
     * (cracks) over dark valleys — the classic ridged-multifractal look at
     * a single octave stack.
     *
     * @param width canvas width in pixels.
     * @param height canvas height in pixels.
     * @param palette color ramp, dark to bright, at least one entry.
     * @param seed deterministic noise seed.
     */
    fun stone(width: Int, height: Int, palette: IntArray, seed: Long): PixelFrame {
        requireDimensions(width, height)
        requirePalette(palette)
        val noise = ValueNoise(seed)
        return mapToPalette(width, height, palette) { x, y ->
            ridged(noise.fbm(x * 0.35f, y * 0.35f))
        }
    }

    /**
     * Marble: sine veins warped by fbm turbulence. The turbulence displaces
     * the vein phase so the bands swirl like mineral grain.
     *
     * Formula (per pixel):
     * ```
     * t = (fbm(x·0.12, y·0.12) - 0.5) · 2        (≈ [-1, 1])
     * v = 0.5 + 0.5 · sin(2π · veins · x/width + t · 3)
     * ```
     * [veins] counts the bright/dark band pairs across the full width.
     *
     * @param width canvas width in pixels.
     * @param height canvas height in pixels.
     * @param palette color ramp, dark to bright, at least one entry.
     * @param seed deterministic noise seed.
     * @param veins band count across the width, `>= 1` (default 5).
     */
    fun marble(width: Int, height: Int, palette: IntArray, seed: Long, veins: Int = 5): PixelFrame {
        requireDimensions(width, height)
        requirePalette(palette)
        require(veins >= 1) { "veins must be >= 1 (was $veins)" }
        val noise = ValueNoise(seed)
        return mapToPalette(width, height, palette) { x, y ->
            val t = (noise.fbm(x * 0.12f, y * 0.12f) - 0.5f) * 2f
            (0.5 + 0.5 * sin(2.0 * PI * veins * x / width + t * 3.0)).toFloat()
        }
    }

    // ------------------------------------------------------------------
    // Layout-based materials
    // ------------------------------------------------------------------

    /**
     * Running-bond brick wall.
     *
     * Layout: rows of height `brickH + mortar` alternate their horizontal
     * offset by `brickW / 2` (row 0 unshifted, row 1 shifted, ...). A pixel
     * is mortar (color `palette[0]`) when either its row position or its
     * (offset-shifted) column position falls in the mortar strip; otherwise
     * it belongs to a brick whose body color is picked from
     * `palette[1..size-1]` by a seeded lattice value evaluated at the
     * brick's (column, row) — every brick of the same (col, row) shares one
     * color, and repeated (col, row) pairs across the canvas repeat the
     * same body color deterministically.
     *
     * Worked example — `brickW = 8`, `brickH = 4`, `mortar = 1`: pixel rows
     * `y = 4` and `y = 9` are full mortar rows (`y mod 5 ∈ {4}`), and
     * vertical mortar stripes appear every 9 columns, shifted 4 columns on
     * odd rows.
     *
     * @param width canvas width, `>= brickW + mortar`.
     * @param height canvas height, `>= brickH + mortar`.
     * @param palette `palette[0]` = mortar, `palette[1..]` = body colors; at
     *   least two entries.
     * @param seed deterministic brick-jitter seed.
     * @param brickW brick width in pixels, `>= 2` (default 8).
     * @param brickH brick height in pixels, `>= 1` (default 4).
     * @param mortar mortar thickness in pixels, `>= 0` (default 1).
     */
    fun bricks(
        width: Int,
        height: Int,
        palette: IntArray,
        seed: Long,
        brickW: Int = 8,
        brickH: Int = 4,
        mortar: Int = 1,
    ): PixelFrame {
        requireDimensions(width, height)
        require(palette.size >= 2) { "bricks needs >= 2 palette colors (mortar + body), was ${palette.size}" }
        require(brickW >= 2) { "brickW must be >= 2 (was $brickW)" }
        require(brickH >= 1) { "brickH must be >= 1 (was $brickH)" }
        require(mortar >= 0) { "mortar must be >= 0 (was $mortar)" }
        require(width >= brickW + mortar) { "width $width too small for brickW $brickW + mortar $mortar" }
        require(height >= brickH + mortar) { "height $height too small for brickH $brickH + mortar $mortar" }
        val periodX = brickW + mortar
        val periodY = brickH + mortar
        val halfShift = brickW / 2
        val noise = ValueNoise(seed)
        val bodySpan = palette.size - 2
        val out = IntArray(width * height)
        var i = 0
        for (y in 0 until height) {
            val row = y / periodY
            val withinY = y - row * periodY
            val rowShift = if (row % 2 == 0) 0 else halfShift
            for (x in 0 until width) {
                val shifted = x + rowShift
                val col = shifted / periodX
                val withinX = shifted - col * periodX
                out[i++] = if (withinY >= brickH || withinX >= brickW) {
                    palette[0]
                } else {
                    // Per-brick jitter: one lattice sample per (col, row).
                    val t = noise.noise2(col * 1.7f + 0.5f, row * 2.3f + 0.5f)
                    val bodyIndex = 1 + (t * bodySpan + 0.5f).toInt().coerceIn(0, bodySpan)
                    palette[bodyIndex]
                }
            }
        }
        return PixelFrame.of(width, height, out)
    }

    /**
     * Flat checkerboard: cell `(x / cell, y / cell)` colored [colorA] when
     * the cell parity sum is even, else [colorB]. No palette, no seed.
     *
     * @param width canvas width in pixels.
     * @param height canvas height in pixels.
     * @param cell cell edge in pixels, `>= 1`.
     * @param colorA color of the even-parity cells.
     * @param colorB color of the odd-parity cells.
     */
    fun checker(width: Int, height: Int, cell: Int, colorA: Int, colorB: Int): PixelFrame {
        requireDimensions(width, height)
        require(cell >= 1) { "cell must be >= 1 (was $cell)" }
        val out = IntArray(width * height)
        var i = 0
        for (y in 0 until height) {
            val cellRow = y / cell
            for (x in 0 until width) {
                val cellCol = x / cell
                out[i++] = if ((cellCol + cellRow) % 2 == 0) colorA else colorB
            }
        }
        return PixelFrame.of(width, height, out)
    }

    // ------------------------------------------------------------------
    // Frame post-effects
    // ------------------------------------------------------------------

    /**
     * CRT scanline overlay: every **odd** row (`y mod 2 == 1`) of opaque
     * content gets [dark] blended over it (source-over, sRGB). Transparent
     * pixels are left alone so the effect never grows a sprite's silhouette.
     *
     * @param frame source frame; not mutated.
     * @param dark overlay color; the default `0x30000000` is a subtle
     *   ~19% black wash.
     */
    fun scanlines(frame: PixelFrame, dark: Int = 0x30000000.toInt()): PixelFrame {
        val overlayAlpha = (dark ushr 24) and 0xFF
        if (overlayAlpha == 0) return frame
        val overlayRgb = dark and 0xFFFFFF
        val out = frame.copyPixels()
        for (y in 0 until frame.height) {
            if (y % 2 == 0) continue
            val rowStart = y * frame.width
            for (x in 0 until frame.width) {
                val i = rowStart + x
                if ((out[i] ushr 24) < OPAQUE_MIN) continue
                out[i] = blendOver(overlayRgb, overlayAlpha, out[i])
            }
        }
        return PixelFrame.of(frame.width, frame.height, out)
    }

    /**
     * Radial vignette: a cosine-falloff darkening that reaches full
     * strength at the corners.
     *
     * Per pixel (opaque only — transparent pixels are untouched, same rule
     * as [scanlines]):
     * ```
     * d = |p - center| / cornerDistance          (clamped to [0, 1])
     * alpha = round(255 · strength · (1 - cos(d · π/2)))
     * ```
     * so the exact center stays clean, the falloff follows `1 - cos` (slow
     * near the center, accelerating toward the corners) and the four
     * corners receive the full [strength]. The overlay color is opaque
     * black.
     *
     * @param frame source frame; not mutated.
     * @param strength vignette strength in `[0, 1]`; 0 is the identity,
     *   1 darkens the corners fully black.
     */
    fun vignette(frame: PixelFrame, strength: Float = 0.5f): PixelFrame {
        require(strength in 0f..1f) { "strength must be in [0, 1] (was $strength)" }
        if (strength == 0f) return frame
        val cx = (frame.width - 1) / 2f
        val cy = (frame.height - 1) / 2f
        val corner = maxOf(sqrt(cx * cx + cy * cy), 1f)
        val out = frame.copyPixels()
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val i = y * frame.width + x
                if ((out[i] ushr 24) < OPAQUE_MIN) continue
                val dx = x - cx
                val dy = y - cy
                val d = (sqrt(dx * dx + dy * dy) / corner).coerceIn(0f, 1f)
                val alpha = (strength * (1f - cos(d * (PI / 2).toFloat())) * 255f + 0.5f).toInt().coerceIn(0, 255)
                if (alpha == 0) continue
                out[i] = blendOver(0x000000, alpha, out[i])
            }
        }
        return PixelFrame.of(frame.width, frame.height, out)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Alpha threshold at/above which a pixel counts as opaque (pipeline convention). */
    private const val OPAQUE_MIN: Int = 0x80

    /** Dimension guard shared by every generator. */
    private fun requireDimensions(width: Int, height: Int) {
        require(width > 0 && height > 0) { "dimensions must be positive (${width}x$height)" }
        require(width.toLong() * height <= Int.MAX_VALUE.toLong()) {
            "raster too large: ${width}x$height exceeds ${Int.MAX_VALUE} pixels"
        }
    }

    /** Palette guard shared by the noise materials. */
    private fun requirePalette(palette: IntArray) {
        require(palette.isNotEmpty()) { "palette must not be empty" }
    }

    /**
     * Shared value → ramp mapping: samples [field] per pixel, clamps to
     * `[0, 1]`, rounds to a palette index (`v = 0 → index 0`, `v = 1 → last`).
     */
    private inline fun mapToPalette(
        width: Int,
        height: Int,
        palette: IntArray,
        field: (x: Int, y: Int) -> Float,
    ): PixelFrame {
        val last = palette.size - 1
        val out = IntArray(width * height)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = field(x, y).coerceIn(0f, 1f)
                out[i++] = palette[(v * last + 0.5f).toInt().coerceIn(0, last)]
            }
        }
        return PixelFrame.of(width, height, out)
    }

    /**
     * Source-over compositing of an RGB overlay with explicit [alpha] onto
     * [dst], in sRGB (same formula as the OutlineShading blend).
     */
    private fun blendOver(overlayRgb: Int, alpha: Int, dst: Int): Int {
        val da = (dst ushr 24) and 0xFF
        val outA = alpha + da * (255 - alpha) / 255
        if (outA == 0) return 0
        fun mix(sc: Int, dc: Int): Int = (sc * alpha + dc * da * (255 - alpha) / 255) / outA
        val r = mix((overlayRgb shr 16) and 0xFF, (dst shr 16) and 0xFF)
        val g = mix((overlayRgb shr 8) and 0xFF, (dst shr 8) and 0xFF)
        val b = mix(overlayRgb and 0xFF, dst and 0xFF)
        return (outA shl 24) or (r shl 16) or (g shl 8) or b
    }
}
