package com.pixellab.core.gen

import kotlin.math.floor

/**
 * Seeded 2D **value noise** with optional exact tiling — the noise
 * substrate of [TextureGen].
 *
 * Value noise differs from gradient (Perlin) noise in what sits on the
 * integer lattice: a *random value* per lattice point rather than a random
 * gradient. Bilinear + smoothstep interpolation of those values gives a
 * smooth, blobby field in `[0, 1]` — cheap, seedable and visually ideal for
 * pixel-art textures where Perlin's directional artifacts are unwanted.
 *
 * ## Lattice hashing (documented contract)
 *
 * The lattice value at integer point `(ix, iy)` is
 * `hash(seed, ix, iy) -> Float in [0, 1)` where the hash folds the
 * coordinates into the seed and runs a splitmix64-style integer avalanche:
 * ```
 * h  = seed + ix·GOLDEN + iy·GOLDEN2          (GOLDEN  = 0x9E3779B97F4A7C15)
 * h ^= h >>> 30;  h *= 0xBF58476D1CE4E5B9     (GOLDEN2 = 0xC2B2AE3D27D4EB4F)
 * h ^= h >>> 27;  h *= 0x94D049BB133111EB
 * h ^= h >>> 31
 * value = (h >>> 40) / 2^24                   (top 24 bits, [0, 1))
 * ```
 * Pure arithmetic, fully deterministic, no platform dependence; adjacent
 * lattice points decorrelate after a single avalanche round because the
 * coordinate multipliers are odd golden-ratio constants.
 *
 * ## Tiling
 *
 * With `tile > 0`, lattice indices are reduced modulo [tile] *before*
 * hashing, so the noise field wraps with period [tile] in both axes:
 * `noise2(x, y) == noise2(x + tile, y) == noise2(x, y + tile)` exactly for
 * offsets that are representable without float rounding (integers, halves,
 * quarters...). This is what makes [TextureGen] able to emit seamless
 * tiles. With `tile == 0` (default) the lattice is unbounded.
 *
 * [fbm] keeps the tiling property when its frequency doubling stays
 * integral (the default `lacunarity = 2`), because any integer multiple of
 * the period reduces to the same wrapped lattice indices.
 */
class ValueNoise(private val seed: Long, val tile: Int = 0) {

    init {
        require(tile >= 0) { "tile must be >= 0 (was $tile)" }
    }

    /**
     * Smooth value noise at ([x], [y]). The four surrounding lattice points
     * are bilinearly blended with the smoothstep curve `t*t*(3-2t)` on each
     * axis, which removes the bilinear diamond artifacts.
     *
     * @throws IllegalArgumentException when [x] or [y] is NaN.
     * @return a value in `[0, 1)` (the exact bounds follow from the lattice
     *   hash and the convexity of the interpolation).
     */
    fun noise2(x: Float, y: Float): Float {
        require(!x.isNaN() && !y.isNaN()) { "noise2 coordinates must not be NaN" }
        val ix = floor(x)
        val iy = floor(y)
        val fx = x - ix
        val fy = y - iy
        val x0 = wrap(ix.toInt())
        val y0 = wrap(iy.toInt())
        val x1 = wrap((ix.toInt() + 1))
        val y1 = wrap((iy.toInt() + 1))
        val v00 = lattice(x0, y0)
        val v10 = lattice(x1, y0)
        val v01 = lattice(x0, y1)
        val v11 = lattice(x1, y1)
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val top = v00 + (v10 - v00) * sx
        val bottom = v01 + (v11 - v01) * sx
        return top + (bottom - top) * sy
    }

    /**
     * Fractal Brownian motion: [octaves] layers of [noise2] at geometrically
     * increasing frequency and geometrically decreasing amplitude
     * (`amplitude *= persistence`, `frequency *= lacunarity` per octave),
     * normalized by the total amplitude so the result stays in `[0, 1]`.
     *
     * The first octave runs at frequency 1 (input coordinates as given); the
     * default `persistence = 0.5` / `lacunarity = 2` is the classic setup.
     * Tiling is preserved when `lacunarity` is an integer (then every octave
     * frequency is an integer multiple of the base period).
     *
     * @throws IllegalArgumentException when [octaves] is outside `1..8`,
     *   [persistence] is not strictly positive or [lacunarity] is below 1.
     */
    fun fbm(
        x: Float,
        y: Float,
        octaves: Int = 4,
        persistence: Float = 0.5f,
        lacunarity: Float = 2f,
    ): Float {
        require(octaves in 1..8) { "octaves must be in [1, 8] (was $octaves)" }
        require(persistence > 0f) { "persistence must be > 0 (was $persistence)" }
        require(lacunarity >= 1f) { "lacunarity must be >= 1 (was $lacunarity)" }
        var total = 0f
        var amplitude = 1f
        var frequency = 1f
        var maxAmplitude = 0f
        repeat(octaves) {
            total += noise2(x * frequency, y * frequency) * amplitude
            maxAmplitude += amplitude
            amplitude *= persistence
            frequency *= lacunarity
        }
        return total / maxAmplitude
    }

    /** Reduces a lattice index modulo [tile] (non-negative result). */
    private fun wrap(index: Int): Int {
        if (tile == 0) return index
        val m = index % tile
        return if (m < 0) m + tile else m
    }

    /**
     * The lattice hash documented in the class KDoc: coordinate fold +
     * splitmix64-style avalanche, mapped to `[0, 1)` via the top 24 bits.
     */
    private fun lattice(ix: Int, iy: Int): Float {
        var h = seed + ix * GOLDEN + iy * GOLDEN2
        h = h xor (h ushr 30)
        h *= MIX1
        h = h xor (h ushr 27)
        h *= MIX2
        h = h xor (h ushr 31)
        return (h ushr 40).toInt().toFloat() / (1 shl 24).toFloat()
    }

    private companion object {
        // The constants below are the standard 64-bit avalanche constants,
        // written as two's-complement spellings because Kotlin Long hex
        // literals only reach 0x7FFFFFFFFFFFFFFF.

        /** Golden-ratio odd constant for the x fold (0x9E3779B97F4A7C15). */
        private const val GOLDEN: Long = -0x61C8864680B583EBL

        /** Second odd constant (golden ratio companion) for the y fold (0xC2B2AE3D27D4EB4F). */
        private const val GOLDEN2: Long = -0x3D4D51C2D82B14B1L

        /** Avalanche round 1 multiplier (0xBF58476D1CE4E5B9). */
        private const val MIX1: Long = -0x40A7B892E31B1A47L

        /** Avalanche round 2 multiplier (0x94D049BB133111EB). */
        private const val MIX2: Long = -0x6B2FB644ECCEEE15L
    }
}

/**
 * Ridge transform used by craggy textures: mirrors the `[0, 1]` value range
 * onto `[0, 1]` creases — `1 - |2v - 1|` maps the extremes of the field to
 * bright ridges and the midpoint to dark valleys. Repeated fbm layers of
 * this transform are the classic "ridged multifractal" of crack and stone
 * textures. Exposed internally so [TextureGen] can share the formula.
 */
internal fun ridged(v: Float): Float = 1f - kotlin.math.abs(2f * v - 1f)
