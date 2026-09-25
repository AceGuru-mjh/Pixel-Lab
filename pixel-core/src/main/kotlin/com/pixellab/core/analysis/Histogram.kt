package com.pixellab.core.analysis

import com.pixellab.core.model.PixelFrame
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A 256-bin histogram over one 8-bit channel.
 *
 * Bins are indexed directly by the 8-bit value: `counts[v]` is the number of
 * sampled pixels whose channel value equals `v`. The histogram is immutable
 * after construction; derived statistics (cumulative distribution, mean,
 * variance, entropy, percentiles) are computed lazily and cached in plain
 * fields — safe under single-threaded use, cheap to rebuild otherwise.
 *
 * Common usage: obtain one from [RgbaHistograms], then query statistics or
 * feed it into [OtsuThreshold] to derive a binarization threshold.
 */
class ChannelHistogram(private val counts: IntArray) {

    init {
        require(counts.size == BINS) { "ChannelHistogram needs exactly $BINS bins, got ${counts.size}" }
        require(counts.all { it >= 0 }) { "Bin counts must be non-negative" }
    }

    /** Total number of samples behind this histogram. */
    val total: Long = counts.sum().toLong()

    /** Number of non-empty bins (distinct channel values observed). */
    val distinctValues: Int get() = counts.count { it > 0 }

    /** Raw count for channel value [v] (0..255). */
    fun count(v: Int): Int {
        require(v in 0 until BINS) { "Channel value out of range: $v" }
        return counts[v]
    }

    /** Mean of the underlying samples, or 0 when the histogram is empty. */
    val mean: Double by lazy {
        if (total == 0L) return@lazy 0.0
        var acc = 0.0
        for (v in 0 until BINS) acc += v.toDouble() * counts[v]
        acc / total
    }

    /** Population variance of the underlying samples (0 when empty). */
    val variance: Double by lazy {
        if (total == 0L) return@lazy 0.0
        val m = mean
        var acc = 0.0
        for (v in 0 until BINS) {
            val d = v - m
            acc += d * d * counts[v]
        }
        acc / total
    }

    /** Population standard deviation. */
    val standardDeviation: Double get() = sqrt(variance)

    /**
     * Shannon entropy of the normalized bin distribution, in bits.
     * Returns 0 for an empty histogram.
     */
    val entropy: Double by lazy {
        if (total == 0L) return@lazy 0.0
        var acc = 0.0
        for (v in 0 until BINS) {
            val c = counts[v]
            if (c > 0) {
                val p = c.toDouble() / total
                acc -= p * log2(p)
            }
        }
        acc
    }

    /** The most frequent channel value (lowest value wins ties). Empty → 0. */
    val mode: Int by lazy {
        var best = 0
        var bestCount = -1
        for (v in 0 until BINS) {
            if (counts[v] > bestCount) {
                bestCount = counts[v]
                best = v
            }
        }
        best
    }

    /**
     * Cumulative count up to and including channel value [v]
     * (`sum(counts[0..v])`).
     */
    fun cumulative(v: Int): Long {
        require(v in 0 until BINS) { "Channel value out of range: $v" }
        var acc = 0L
        for (i in 0..v) acc += counts[i]
        return acc
    }

    /**
     * The channel value at which the cumulative distribution first reaches
     * [fraction] of [total]. [fraction] is clamped to `0..1`; an empty
     * histogram returns 0.
     *
     * `percentile(0.5)` is the median under the "lower" convention.
     */
    fun percentile(fraction: Double): Int {
        if (total == 0L) return 0
        // ceil so fractional targets are only satisfied by a bin whose
        // cumulative share actually reaches the fraction: 0.95 of 10
        // samples requires cumulative >= 9.5, i.e. >= 10.
        val target = ceil(fraction.coerceIn(0.0, 1.0) * total).toLong()
        var acc = 0L
        for (v in 0 until BINS) {
            acc += counts[v]
            if (acc >= max(1L, target)) return v
        }
        return BINS - 1
    }

    override fun equals(other: Any?): Boolean =
        other is ChannelHistogram && counts.contentEquals(other.counts)

    override fun hashCode(): Int = counts.contentHashCode()

    override fun toString(): String =
        "ChannelHistogram(total=$total, distinct=$distinctValues, mean=${"%.2f".format(mean)})"

    companion object {
        /** Number of bins: one per 8-bit value. */
        const val BINS = 256
    }
}

/**
 * Per-channel histograms for an ARGB raster: R, G, B, A plus Rec.709 luma.
 *
 * Transparent pixels (alpha == 0) are excluded from the R/G/B/luma
 * histograms by default so that sprite transparency padding does not skew
 * color statistics; the alpha histogram always covers every pixel.
 *
 * @see HistogramOps.compute
 */
class RgbaHistograms(
    /** Histogram of the red channel (opaque samples only). */
    val red: ChannelHistogram,
    /** Histogram of the green channel (opaque samples only). */
    val green: ChannelHistogram,
    /** Histogram of the blue channel (opaque samples only). */
    val blue: ChannelHistogram,
    /** Histogram of the alpha channel over every pixel. */
    val alpha: ChannelHistogram,
    /** Histogram of Rec.709 luma (opaque samples only). */
    val luma: ChannelHistogram,
    /** Number of sampled pixels (opaque count when transparency is excluded). */
    val sampleCount: Int,
) {
    /** Fraction of pixels that were fully transparent. */
    val transparentRatio: Double

    init {
        require(sampleCount >= 0) { "sampleCount must be non-negative" }
        val pixelCount = alpha.total
        transparentRatio = if (pixelCount == 0L) 0.0
        else (pixelCount - sampleCount).toDouble() / pixelCount
    }

    override fun toString(): String =
        "RgbaHistograms(samples=$sampleCount, transparent=${"%.1f%%".format(transparentRatio * 100)})"
}

/**
 * Otsu's method for automatic threshold selection, plus the classic
 * dynamic-programming extension to multi-level thresholding.
 *
 * Single-level Otsu maximizes the between-class variance
 * `σ_B²(t) = ω₀(t)·ω₁(t)·(µ₀(t) − µ₁(t))²` over all 255 candidate
 * thresholds — the original 1979 formulation, O(L) per candidate.
 *
 * Multi-level Otsu partitions the histogram into `[levels]` classes with
 * `[levels − 1]` thresholds. The naive search is O(L^(k−1)); this
 * implementation uses the standard dynamic-programming recursion over
 * (first threshold, class count) with prefix sums for O(k·L²) total work,
 * which stays instant for 256 bins and any practical class count.
 */
object OtsuThreshold {

    /**
     * Computes the single optimal threshold for [histogram] (0..255).
     * An empty or single-valued histogram returns 0 so callers can treat
     * the result as "no meaningful split".
     */
    fun single(histogram: ChannelHistogram): Int {
        if (histogram.total <= 1L || histogram.distinctValues < 2) return 0
        val counts = IntArray(ChannelHistogram.BINS) { histogram.count(it) }
        val total = histogram.total.toDouble()
        var bestThreshold = 0
        var bestVariance = -1.0
        var w0 = 0.0
        var sum0 = 0.0
        val totalMean = histogram.mean * total
        for (t in 0 until ChannelHistogram.BINS - 1) {
            w0 += counts[t]
            sum0 += t.toDouble() * counts[t]
            if (w0 == 0.0) continue
            val w1 = total - w0
            if (w1 == 0.0) break
            val mu0 = sum0 / w0
            val mu1 = (totalMean - sum0) / w1
            val between = w0 * w1 * (mu0 - mu1) * (mu0 - mu1)
            if (between > bestVariance) {
                bestVariance = between
                bestThreshold = t
            }
        }
        return bestThreshold
    }

    /**
     * Computes `[levels − 1]` ascending thresholds splitting [histogram]
     * into [levels] classes, maximizing total between-class variance.
     *
     * Requires `levels in 2..8` — beyond that the quantization stops being
     * meaningful for 8-bit inputs and the palette tools in
     * `com.pixellab.core.convert` are a better fit.
     */
    fun multi(histogram: ChannelHistogram, levels: Int): IntArray {
        require(levels in 2..8) { "levels must be in 2..8, got $levels" }
        if (histogram.total <= 1L) return IntArray(levels - 1)
        val bins = ChannelHistogram.BINS
        val counts = IntArray(bins) { histogram.count(it) }

        // Prefix sums over counts and value-weighted counts.
        val prefixCount = LongArray(bins + 1)
        val prefixWeight = DoubleArray(bins + 1)
        for (v in 0 until bins) {
            prefixCount[v + 1] = prefixCount[v] + counts[v]
            prefixWeight[v + 1] = prefixWeight[v] + v.toDouble() * counts[v]
        }

        // best[i][k]: best score splitting values [0, i) into k classes.
        // cut[i][k]: start of the last class chosen for that split.
        val best = Array(bins + 1) { DoubleArray(levels + 1) { Double.NEGATIVE_INFINITY } }
        val cut = Array(bins + 1) { IntArray(levels + 1) { -1 } }
        for (i in 1..bins) best[i][1] = classScore(prefixCount, prefixWeight, 0, i)
        for (k in 2..levels) {
            for (i in k..bins) {
                var bestScore = Double.NEGATIVE_INFINITY
                var bestCut = -1
                // j is the start of the last class; its threshold lands at j-1.
                for (j in k - 1 until i) {
                    val prev = best[j][k - 1]
                    if (prev == Double.NEGATIVE_INFINITY) continue
                    val score = prev + classScore(prefixCount, prefixWeight, j, i)
                    if (score > bestScore) {
                        bestScore = score
                        bestCut = j
                    }
                }
                best[i][k] = bestScore
                cut[i][k] = bestCut
            }
        }

        // Backtrack to recover the full threshold list.
        val thresholds = IntArray(levels - 1)
        var i = bins
        var k = levels
        while (k > 1) {
            val start = cut[i][k]
            thresholds[k - 2] = start - 1
            i = start
            k--
        }
        thresholds.sort()
        return thresholds
    }

    /**
     * Otsu class score for the class covering `[from, until)`.
     *
     * Maximizing `Σ_k n_k·µ_k²` over classes is equivalent to maximizing
     * the classic between-class variance `Σ_k ω_k·(µ_k − µ)²` because the
     * global mean term is constant: expanding the classic form gives
     * `Σ ω_k·µ_k² − µ²` and `ω_k = n_k/N`, so both objectives differ only
     * by the constant factor `1/N²` and the subtracted global mean.
     */
    private fun classScore(prefixCount: LongArray, prefixWeight: DoubleArray, from: Int, until: Int): Double {
        val n = (prefixCount[until] - prefixCount[from]).toDouble()
        if (n <= 0.0) return 0.0
        val mean = (prefixWeight[until] - prefixWeight[from]) / n
        return n * mean * mean
    }
}

/**
 * Histogram-based point operations on [PixelFrame]s: global statistics,
 * luma histogram equalization (hue-preserving) and percentile auto-levels.
 *
 * All operations return new frames; inputs are never mutated.
 */
object HistogramOps {

    /**
     * Computes per-channel histograms for [frame].
     *
     * @param includeTransparent when false (default), fully-transparent
     *   pixels are excluded from the R/G/B/luma histograms; the alpha
     *   histogram always covers every pixel.
     */
    fun compute(frame: PixelFrame, includeTransparent: Boolean = false): RgbaHistograms {
        val r = IntArray(ChannelHistogram.BINS)
        val g = IntArray(ChannelHistogram.BINS)
        val b = IntArray(ChannelHistogram.BINS)
        val a = IntArray(ChannelHistogram.BINS)
        val l = IntArray(ChannelHistogram.BINS)
        var samples = 0
        for (p in frame.pixels) {
            val alpha = p ushr 24
            a[alpha]++
            if (alpha == 0 && !includeTransparent) continue
            r[p ushr 16 and 0xFF]++
            g[p ushr 8 and 0xFF]++
            b[p and 0xFF]++
            l[luma8(p)]++
            samples++
        }
        return RgbaHistograms(
            red = ChannelHistogram(r),
            green = ChannelHistogram(g),
            blue = ChannelHistogram(b),
            alpha = ChannelHistogram(a),
            luma = ChannelHistogram(l),
            sampleCount = samples,
        )
    }

    /**
     * Hue-preserving histogram equalization.
     *
     * Builds the Rec.709 luma CDF over opaque pixels and remaps each
     * opaque pixel's luma to its equalized value; the resulting gain
     * (`newLuma / oldLuma`) is applied uniformly to R, G and B so chroma
     * is preserved. Transparent pixels are untouched.
     *
     * Returns the input frame reference when it has no opaque pixels.
     */
    fun equalize(frame: PixelFrame): PixelFrame {
        val h = compute(frame).luma
        if (h.total == 0L) return frame
        // Equalized lookup: cdf(v) scaled to full range.
        val map = IntArray(ChannelHistogram.BINS)
        var acc = 0L
        val total = h.total
        val denom = (total - 1).coerceAtLeast(1).toDouble()
        for (v in 0 until ChannelHistogram.BINS) {
            acc += h.count(v)
            map[v] = ((acc - 1).toDouble() / denom * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
        return frame.map { argb ->
            if (argb ushr 24 == 0) argb
            else scaleChromaByLuma(argb, map[luma8(argb)])
        }
    }

    /**
     * Percentile contrast stretch ("auto levels").
     *
     * Computes the [lowPct] and [highPct] percentiles of the luma
     * histogram, then linearly remaps luma from `[low, high]` onto
     * `[0, 255]` with clamping outside the range. The gain is applied to
     * RGB jointly (hue-preserving, same as [equalize]). `lowPct`/`highPct`
     * are fractions in `[0, 1]` with `lowPct < highPct`; defaults stretch
     * the central 98% of samples.
     */
    fun stretch(frame: PixelFrame, lowPct: Double = 0.01, highPct: Double = 0.99): PixelFrame {
        require(lowPct in 0.0..1.0) { "lowPct out of range: $lowPct" }
        require(highPct in 0.0..1.0) { "highPct out of range: $highPct" }
        require(lowPct < highPct) { "lowPct must be below highPct" }
        val h = compute(frame).luma
        if (h.total == 0L) return frame
        val low = h.percentile(lowPct)
        val high = h.percentile(highPct)
        if (high <= low) return frame
        val map = IntArray(ChannelHistogram.BINS)
        val span = (high - low).toDouble()
        for (v in 0 until ChannelHistogram.BINS) {
            val t = ((v - low) / span).coerceIn(0.0, 1.0)
            map[v] = (t * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
        return frame.map { argb ->
            if (argb ushr 24 == 0) argb
            else scaleChromaByLuma(argb, map[luma8(argb)])
        }
    }

    /**
     * Binarizes [frame] by luma: opaque pixels with luma strictly greater
     * than [threshold] become opaque white, the rest opaque black.
     * Fully-transparent pixels stay transparent. Use the result of
     * [OtsuThreshold.single] on [compute]'s luma histogram to pick
     * [threshold] automatically.
     */
    fun binarize(frame: PixelFrame, threshold: Int): PixelFrame {
        require(threshold in 0..255) { "threshold out of range: $threshold" }
        return frame.map { argb ->
            if (argb ushr 24 == 0) argb
            else if (luma8(argb) > threshold) WHITE else BLACK
        }
    }

    /** Rec.709 luma of an ARGB pixel, quantized to 0..255 (rounded). */
    internal fun luma8(argb: Int): Int {
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        return (0.2126 * r + 0.7152 * g + 0.0722 * b + 0.5).toInt().coerceIn(0, 255)
    }

    /**
     * Rescales a pixel's RGB channels so its Rec.709 luma becomes
     * [targetLuma], keeping hue and alpha. Each channel is multiplied by
     * `target / current` (or lifted equally when the current luma is 0)
     * and clamped to 0..255; channels clip independently, which slightly
     * desaturates extreme gains — the accepted trade-off for keeping the
     * operation linear and branch-light.
     */
    private fun scaleChromaByLuma(argb: Int, targetLuma: Int): Int {
        val current = luma8(argb)
        if (current == targetLuma) return argb
        val a = argb ushr 24
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        val nr: Int
        val ng: Int
        val nb: Int
        if (current == 0) {
            // Zero-luma (pure black) pixel: lift all channels equally.
            nr = targetLuma; ng = targetLuma; nb = targetLuma
        } else {
            val gain = targetLuma.toDouble() / current
            nr = (r * gain + 0.5).toInt().coerceIn(0, 255)
            ng = (g * gain + 0.5).toInt().coerceIn(0, 255)
            nb = (b * gain + 0.5).toInt().coerceIn(0, 255)
        }
        return a shl 24 or (nr shl 16) or (ng shl 8) or nb
    }

    private val WHITE = 0xFFFFFFFF.toInt()
    private val BLACK = 0xFF000000.toInt()
}
