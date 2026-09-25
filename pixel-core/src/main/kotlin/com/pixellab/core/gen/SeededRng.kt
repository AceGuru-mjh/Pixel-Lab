package com.pixellab.core.gen

/**
 * Deterministic pseudo-random number generator — xorshift64* core with
 * helpers tuned for procedural pixel-art generation.
 *
 * ## Why not `java.util.Random`?
 *
 * `java.util.Random` is unspecified *across platforms*: its LCG stream is
 * documented, but `nextDouble`/`nextFloat` compositions and especially any
 * reordering of calls are implementation details; Kotlin's `kotlin.random`
 * deliberately does **not** fix an algorithm across versions either. A
 * seeded sprite generator must reproduce the *exact same pixels* on Android,
 * on the JVM and ten releases from now, so the arithmetic lives here, in
 * pure Long/Int math with no platform calls at all.
 *
 * ## The core
 *
 * xorshift64* (Marsaglia / Vigna): the 64-bit state is stirred with three
 * shifts and the result is scrambled by a multiplication:
 * ```
 * s ^= s >>> 12;  s ^= s << 25;  s ^= s >>> 27;
 * return s * 0x2545F4914F6CDD1D
 * ```
 * The multiplication makes the low bits high-quality enough for direct
 * modulo use; [nextInt] still applies rejection sampling for exact
 * uniformity over small ranges.
 *
 * The all-zero state is a fixed point, so the constructor remaps the seed
 * `0` (and only `0`) to a fixed non-zero constant; every other seed is used
 * verbatim. Sequences are therefore a pure function of the seed.
 *
 * ## Splitting
 *
 * [split] derives an independent child stream (splitmix64 finalizer of one
 * consumed draw) so multi-part generators — "terrain", "details", "props" —
 * never steal values from each other: drawing from the child cannot change
 * what the parent draws next beyond the single consumed value.
 */
class SeededRng(seed: Long) {

    private var s: Long = if (seed == 0L) REMAPPED_ZERO else seed

    /**
     * Raw xorshift64* draw. This is the only mutating primitive; every other
     * generator method is defined in terms of the exact sequence of
     * [nextLong] calls it makes, which keeps the stream reproducible as long
     * as call order is reproducible.
     */
    fun nextLong(): Long {
        var x = s
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        s = x
        return x * MULTIPLIER
    }

    /**
     * Uniform integer in `[0, bound)`. Uses modulo-rejection sampling: the
     * 31 top bits of [nextLong] are drawn and values `>= floor(2^31 / bound)
     * * bound` are rejected, so every result has exactly the same
     * probability — no modulo bias even for non-power-of-two bounds.
     *
     * @throws IllegalArgumentException when [bound] is not positive.
     */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be > 0 (was $bound)" }
        val limit = (Int.MAX_VALUE / bound) * bound
        var v = top31()
        while (v >= limit) v = top31()
        return v % bound
    }

    /**
     * Uniform integer in `[min, max]` **inclusive** on both ends.
     *
     * @throws IllegalArgumentException when `min > max` or the span exceeds
     *   `Int.MAX_VALUE` (the empty/full-range span is undefined).
     */
    fun nextInt(min: Int, max: Int): Int {
        require(min <= max) { "min must be <= max (was $min..$max)" }
        val span = max - min
        require(span >= 0) { "range $min..$max overflows Int" }
        return min + nextInt(span + 1)
    }

    /** Uniform float in `[0, 1)` built from the top 24 bits of one draw. */
    fun nextFloat(): Float = (nextLong() ushr 40).toInt().toFloat() / (1 shl 24).toFloat()

    /** Uniform double in `[0, 1)` built from the top 53 bits of one draw. */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    /**
     * True with probability [p] — a single [nextFloat] comparison, so a
     * `chance(0f)` is always false and `chance(1f)` always true.
     *
     * @throws IllegalArgumentException when [p] lies outside `[0, 1]`.
     */
    fun chance(p: Float): Boolean {
        require(p in 0f..1f) { "p must be in [0, 1] (was $p)" }
        return nextFloat() < p
    }

    /**
     * Uniform random element of [list].
     *
     * @throws IllegalArgumentException when [list] is empty.
     */
    fun <T> pick(list: List<T>): T {
        require(list.isNotEmpty()) { "cannot pick from an empty list" }
        return list[nextInt(list.size)]
    }

    /**
     * Uniform random element of [array].
     *
     * @throws IllegalArgumentException when [array] is empty.
     */
    fun pick(array: IntArray): Int {
        require(array.isNotEmpty()) { "cannot pick from an empty array" }
        return array[nextInt(array.size)]
    }

    /**
     * Fisher-Yates shuffle returning a **new** list; the input order is
     * preserved. Consumes exactly `size - 1` draws (walking the tail down to
     * index 1), so the result is a deterministic function of the current
     * stream position.
     */
    fun <T> shuffled(list: List<T>): List<T> {
        if (list.size < 2) return list.toList()
        val out = ArrayList<T>(list)
        for (i in out.indices.reversed().drop(1)) {
            val j = nextInt(i + 1)
            val tmp = out[i]
            out[i] = out[j]
            out[j] = tmp
        }
        return out
    }

    /**
     * Derives a fresh, independent generator: one draw is consumed and
     * pushed through the splitmix64 finalizer to seed the child. Repeated
     * splits from a freshly-seeded parent give different children, and a
     * child's stream never aliases the parent's future values.
     */
    fun split(): SeededRng = SeededRng(splitmix64(nextLong()))

    /**
     * The current raw internal state. Feed it to [fromState] to clone a
     * generator that reproduces every future draw — the pair is the tool
     * for checkpointing procedural generation.
     */
    fun state(): Long = s

    /** Top 31 bits of one draw as a non-negative Int (uniform in `[0, 2^31)`). */
    private fun top31(): Int = (nextLong() ushr 33).toInt()

    companion object {

        /** xorshift64* scramble multiplier (golden-ratio-ish odd constant). */
        private const val MULTIPLIER: Long = 0x2545F4914F6CDD1DL

        /**
         * Stand-in state for the degenerate all-zero seed. Written as the
         * two's-complement of `0x9E3779B97F4A7C15` because Kotlin Long hex
         * literals only reach `0x7FFFFFFFFFFFFFFF`.
         */
        private const val REMAPPED_ZERO: Long = -0x61C8864680B583EBL

        /**
         * Rebuilds a generator whose next draws match a generator that was
         * [state]-checkpointed. The zero state is remapped exactly like the
         * zero seed, so [fromState] is total and round-trips losslessly.
         */
        fun fromState(state: Long): SeededRng = SeededRng(if (state == 0L) REMAPPED_ZERO else state)
    }
}

/**
 * The splitmix64 finalizer used by [SeededRng.split]: a fixed avalanche mix
 * that spreads any input bit pattern uniformly over the 64 output bits.
 * Kept internal to the package because it is an implementation detail of
 * stream derivation.
 */
internal fun splitmix64(value: Long): Long {
    // Constants are the two's-complement spellings of 0x9E3779B97F4A7C15,
    // 0xBF58476D1CE4E5B9 and 0x94D049BB133111EB (Long hex literals only
    // reach 0x7FFFFFFFFFFFFFFF).
    var z = value + -0x61C8864680B583EBL
    z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
    z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
    return z xor (z ushr 31)
}
