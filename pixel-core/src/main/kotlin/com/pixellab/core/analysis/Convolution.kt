package com.pixellab.core.analysis

import com.pixellab.core.model.PixelFrame

/**
 * How the convolution sampler treats coordinates outside the raster.
 */
enum class ConvolutionEdgeMode {
    /** Out-of-bounds samples clamp to the nearest border pixel (replicate). */
    CLAMP,

    /** The raster is tiled infinitely in both axes. */
    WRAP,

    /** Out-of-bounds samples contribute fully-transparent black. */
    TRANSPARENT,
}

/**
 * How convolution interacts with the alpha channel of RGBA pixels.
 */
enum class ConvolutionAlphaMode {
    /**
     * Each of R, G, B, A is convolved independently. Fast, but convolving
     * straight (non-premultiplied) color over transparent neighbors pulls
     * dark halos around sprite edges — appropriate only for fully-opaque
     * rasters.
     */
    STRAIGHT,

    /**
     * Pixels are premultiplied before convolution and unpremultiplied
     * afterwards. This is the color-correct default for sprites: a blurred
     * opaque pixel bleeding into transparent space fades to transparent
     * instead of to black.
     */
    PREMULTIPLIED,

    /**
     * Only the alpha channel is filtered; RGB is copied from the
     * center pixel. Useful for softening masks and selection boundaries
     * without touching color data.
     */
    ALPHA_ONLY,
}

/**
 * An immutable separable-or-not convolution kernel over a small
 * rectangular window of weights.
 *
 * The kernel's anchor defaults to the window center (integer division),
 * matching the convention used by every mainstream image toolkit: output
 * `(x, y) = Σ w(i, j) · input(x + i − ax, y + j − ay)`. The [divisor] and
 * [offset] follow the classic graphics convention
 * `out = Σ(w · in) / divisor + offset` so integer-friendly kernels can be
 * authored directly; [normalized] rescales weights so the divisor becomes 1.
 */
class ConvolutionKernel(
    /** Window width; odd numbers are typical but even windows are allowed. */
    val width: Int,
    /** Window height. */
    val height: Int,
    /** Row-major weights, size == width * height. Never mutated. */
    val weights: FloatArray,
    /** Post-accumulation divisor; must be non-zero. */
    val divisor: Float,
    /** Post-division additive offset (e.g. +128 for emboss). */
    val offset: Float,
    /** Anchor X within the window; defaults to `width / 2`. */
    val anchorX: Int = width / 2,
    /** Anchor Y within the window; defaults to `height / 2`. */
    val anchorY: Int = height / 2,
) {

    init {
        require(width > 0 && height > 0) { "Kernel dimensions must be positive ($width x $height)" }
        require(weights.size == width * height) {
            "Kernel weights size ${weights.size} does not match ${width}x$height"
        }
        require(divisor != 0f) { "Kernel divisor must be non-zero" }
        require(anchorX in 0 until width) { "anchorX out of window: $anchorX" }
        require(anchorY in 0 until height) { "anchorY out of window: $anchorY" }
    }

    /** Weight at window offset `(i, j)`. */
    operator fun get(i: Int, j: Int): Float {
        require(i in 0 until width && j in 0 until height) { "Kernel access out of window ($i, $j)" }
        return weights[j * width + i]
    }

    /**
     * Returns a kernel with the same shape but weights rescaled so the
     * divisor is 1 and the offset 0 (offset folded into weights when the
     * window is single-tap; otherwise offset is preserved). Used to
     * normalize hand-authored kernels before summation.
     */
    fun normalized(): ConvolutionKernel {
        if (divisor == 1f && offset == 0f) return this
        val scale = 1f / divisor
        return ConvolutionKernel(
            width, height,
            FloatArray(weights.size) { weights[it] * scale },
            1f, offset,
            anchorX, anchorY,
        )
    }

    /** Sum of all weights (before divisor). */
    val weightSum: Float get() = weights.sum()

    /**
     * True when every weight is non-negative and they sum to the divisor —
     * such kernels never produce out-of-range output from in-range input
     * (they are "averaging" operators).
     */
    val isAveraging: Boolean
        get() = weights.all { it >= 0f } && kotlin.math.abs(weightSum - divisor) < 1e-4f

    override fun equals(other: Any?): Boolean =
        other is ConvolutionKernel &&
            width == other.width && height == other.height &&
            divisor == other.divisor && offset == other.offset &&
            weights.contentEquals(other.weights)

    override fun hashCode(): Int =
        31 * (31 * (31 * width + height) + weights.contentHashCode()) + divisor.hashCode()

    override fun toString(): String = "ConvolutionKernel(${width}x$height, sum=${"%.3f".format(weightSum / divisor)})"

    companion object {
        /** 1x1 identity kernel. */
        val IDENTITY = ConvolutionKernel(1, 1, floatArrayOf(1f), 1f, 0f)

        /** 3x3 box blur (all weights 1, divisor 9). */
        val BOX_BLUR_3 = ConvolutionKernel(3, 3, floatArrayOf(
            1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f,
        ), 9f, 0f)

        /** 5x5 box blur. */
        val BOX_BLUR_5 = ConvolutionKernel(5, 5, FloatArray(25) { 1f }, 25f, 0f)

        /**
         * 3x3 Gaussian approximation `[1 2 1; 2 4 2; 1 2 1] / 16`.
         * The classic integer-friendly binomial window.
         */
        val GAUSSIAN_3 = ConvolutionKernel(3, 3, floatArrayOf(
            1f, 2f, 1f, 2f, 4f, 2f, 1f, 2f, 1f,
        ), 16f, 0f)

        /**
         * 5x5 binomial window (two [GAUSSIAN_3] passes inlined).
         * Weights are Pascal-triangle outer product, divisor 256.
         */
        val GAUSSIAN_5 = ConvolutionKernel(5, 5, run {
            val t = intArrayOf(1, 4, 6, 4, 1)
            FloatArray(25) { i -> (t[i / 5] * t[i % 5]).toFloat() }
        }, 256f, 0f)

        /**
         * 3x3 unsharp-style sharpen: center 5, cross −1 (divisor 1).
         * `5·c − (u + d + l + r)`.
         */
        val SHARPEN = ConvolutionKernel(3, 3, floatArrayOf(
            0f, -1f, 0f, -1f, 5f, -1f, 0f, -1f, 0f,
        ), 1f, 0f)

        /**
         * Mild 3x3 sharpen: center 9/5 with 1/5 cross — an averaging-safe
         * alternative to [SHARPEN] that never overshoots into banding.
         */
        val SHARPEN_MILD = ConvolutionKernel(3, 3, floatArrayOf(
            0f, -1f, 0f, -1f, 9f, -1f, 0f, -1f, 0f,
        ), 5f, 0f)

        /**
         * 3x3 emboss: diagonal gradient with +128 offset so flat areas
         * land on mid-gray.
         */
        val EMBOSS = ConvolutionKernel(3, 3, floatArrayOf(
            -2f, -1f, 0f, -1f, 1f, 1f, 0f, 1f, 2f,
        ), 1f, 128f)

        /** 3x3 Laplacian edge detector, cross form. */
        val LAPLACIAN = ConvolutionKernel(3, 3, floatArrayOf(
            0f, 1f, 0f, 1f, -4f, 1f, 0f, 1f, 0f,
        ), 1f, 0f)

        /** 3x3 Laplacian edge detector, full form (includes diagonals). */
        val LAPLACIAN_FULL = ConvolutionKernel(3, 3, floatArrayOf(
            1f, 1f, 1f, 1f, -8f, 1f, 1f, 1f, 1f,
        ), 1f, 0f)

        /** 3x3 Sobel-style horizontal gradient (Gx). */
        val SOBEL_X = ConvolutionKernel(3, 3, floatArrayOf(
            -1f, 0f, 1f, -2f, 0f, 2f, -1f, 0f, 1f,
        ), 1f, 0f)

        /** 3x3 Sobel-style vertical gradient (Gy). */
        val SOBEL_Y = ConvolutionKernel(3, 3, floatArrayOf(
            -1f, -2f, -1f, 0f, 0f, 0f, 1f, 2f, 1f,
        ), 1f, 0f)

        /** 3x3 Prewitt horizontal gradient (softer Sobel). */
        val PREWITT_X = ConvolutionKernel(3, 3, floatArrayOf(
            -1f, 0f, 1f, -1f, 0f, 1f, -1f, 0f, 1f,
        ), 1f, 0f)

        /** 3x3 Prewitt vertical gradient. */
        val PREWITT_Y = ConvolutionKernel(3, 3, floatArrayOf(
            -1f, -1f, -1f, 0f, 0f, 0f, 1f, 1f, 1f,
        ), 1f, 0f)
    }
}

/**
 * Convolution of [PixelFrame]s with [ConvolutionKernel]s.
 *
 * The output frame always has the same geometry as the input; edge samples
 * follow [ConvolutionEdgeMode]. All computation happens in float and is
 * rounded half-up on the way out — deterministic across platforms.
 */
object ConvolutionOps {

    /**
     * Convolves [frame] with [kernel].
     *
     * @param edgeMode out-of-bounds sampling behavior; [ConvolutionEdgeMode.CLAMP]
     *   is the default and matches most host-toolkit expectations.
     * @param alphaMode alpha/color interaction; [ConvolutionAlphaMode.PREMULTIPLIED]
     *   is the color-correct default for sprites.
     */
    fun convolve(
        frame: PixelFrame,
        kernel: ConvolutionKernel,
        edgeMode: ConvolutionEdgeMode = ConvolutionEdgeMode.CLAMP,
        alphaMode: ConvolutionAlphaMode = ConvolutionAlphaMode.PREMULTIPLIED,
    ): PixelFrame {
        val k = kernel.normalized()
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val out = IntArray(w * h)

        val ax = k.anchorX
        val ay = k.anchorY
        val kw = k.width
        val kh = k.height

        for (y in 0 until h) {
            for (x in 0 until w) {
                var accA = 0f
                var accR = 0f
                var accG = 0f
                var accB = 0f
                // Under STRAIGHT the alpha channel is NOT convolved: edge
                // kernels (Sobel, Laplacian) sum their weights to zero,
                // which would annihilate alpha and blank the output.
                // Opacity is inherited from the center pixel instead.
                var centerAlpha = 0
                for (j in 0 until kh) {
                    val sy = y + j - ay
                    for (i in 0 until kw) {
                        val sx = x + i - ax
                        val argb = sample(src, w, h, sx, sy, edgeMode)
                        // Capture the center tap's alpha before any skips:
                        // zero-weight center taps (Sobel) must still define
                        // the inherited alpha for STRAIGHT mode.
                        if (i == ax && j == ay) centerAlpha = argb ushr 24
                        val weight = k[i, j]
                        if (weight == 0f) continue
                        if (argb == 0) continue // TRANSPARENT edge or hole
                        val a = (argb ushr 24) / 255f
                        if (alphaMode == ConvolutionAlphaMode.ALPHA_ONLY) {
                            if (i == ax && j == ay) {
                                // Center color, filtered alpha only.
                                accR = (argb ushr 16 and 0xFF).toFloat()
                                accG = (argb ushr 8 and 0xFF).toFloat()
                                accB = (argb and 0xFF).toFloat()
                            }
                            accA += a * weight
                            continue
                        }
                        if (alphaMode == ConvolutionAlphaMode.PREMULTIPLIED) {
                            accR += (argb ushr 16 and 0xFF) * a * weight
                            accG += (argb ushr 8 and 0xFF) * a * weight
                            accB += (argb and 0xFF) * a * weight
                            accA += a * weight
                        } else {
                            accR += (argb ushr 16 and 0xFF) * weight
                            accG += (argb ushr 8 and 0xFF) * weight
                            accB += (argb and 0xFF) * weight
                            accA += a * weight
                        }
                    }
                }
                if (alphaMode == ConvolutionAlphaMode.STRAIGHT && centerAlpha != 0) {
                    out[y * w + x] = packStraight(accR, accG, accB, centerAlpha)
                } else {
                    out[y * w + x] = pack(accA, accR, accG, accB, alphaMode)
                }
            }
        }
        return PixelFrame.of(w, h, out)
    }

    /**
     * Gradient magnitude via Sobel: convolves with [ConvolutionKernel.SOBEL_X]
     * and [ConvolutionKernel.SOBEL_Y], then combines them as
     * `sqrt(gx² + gy²)` on luma (offset clamped to 0..255). Output is
     * opaque grayscale over opaque input pixels; transparent input pixels
     * stay transparent.
     */
    fun sobelMagnitude(frame: PixelFrame, edgeMode: ConvolutionEdgeMode = ConvolutionEdgeMode.CLAMP): PixelFrame {
        val gx = convolve(frame, ConvolutionKernel.SOBEL_X, edgeMode, ConvolutionAlphaMode.STRAIGHT)
        val gy = convolve(frame, ConvolutionKernel.SOBEL_Y, edgeMode, ConvolutionAlphaMode.STRAIGHT)
        val w = frame.width
        val h = frame.height
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val a = frame.pixels[i] ushr 24
            if (a == 0) continue
            val lx = HistogramOps.luma8(gx.pixels[i])
            val ly = HistogramOps.luma8(gy.pixels[i])
            val mag = (kotlin.math.sqrt((lx * lx + ly * ly).toDouble()) + 0.5)
                .toInt().coerceIn(0, 255)
            out[i] = a shl 24 or (mag shl 16) or (mag shl 8) or mag
        }
        return PixelFrame.of(w, h, out)
    }

    /**
     * Box blur applied [passes] times. A repeated box blur converges to a
     * Gaussian and is far cheaper than a large Gaussian window: three
     * passes of 3x3 approximate σ ≈ 1.4. [alphaMode] semantics follow
     * [convolve]; the default is premultiplied for color-correct edges.
     */
    fun boxBlur(
        frame: PixelFrame,
        size: Int = 3,
        passes: Int = 1,
        edgeMode: ConvolutionEdgeMode = ConvolutionEdgeMode.CLAMP,
        alphaMode: ConvolutionAlphaMode = ConvolutionAlphaMode.PREMULTIPLIED,
    ): PixelFrame {
        require(size in 1..15) { "Box size must be in 1..15, got $size" }
        require(passes in 1..16) { "Pass count must be in 1..16, got $passes" }
        if (size == 1 || passes == 0) return frame
        val kernel = ConvolutionKernel(size, size, FloatArray(size * size) { 1f }, (size * size).toFloat(), 0f)
        var current = frame
        repeat(passes) { current = convolve(current, kernel, edgeMode, alphaMode) }
        return current
    }

    /**
     * Samples the source at `(x, y)` honoring [edgeMode]. Returns 0
     * (fully-transparent) for out-of-bounds under
     * [ConvolutionEdgeMode.TRANSPARENT].
     */
    private fun sample(src: IntArray, w: Int, h: Int, x: Int, y: Int, edgeMode: ConvolutionEdgeMode): Int {
        return when (edgeMode) {
            ConvolutionEdgeMode.CLAMP -> {
                val cx = x.coerceIn(0, w - 1)
                val cy = y.coerceIn(0, h - 1)
                src[cy * w + cx]
            }
            ConvolutionEdgeMode.WRAP -> {
                val wx = ((x % w) + w) % w
                val wy = ((y % h) + h) % h
                src[wy * w + wx]
            }
            ConvolutionEdgeMode.TRANSPARENT ->
                if (x in 0 until w && y in 0 until h) src[y * w + x] else 0
        }
    }

    /** Packs STRAIGHT-mode output: color from accumulation, alpha verbatim. */
    private fun packStraight(r: Float, g: Float, b: Float, alpha: Int): Int {
        if (alpha == 0) return 0
        return (alpha shl 24) or
            ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (b + 0.5f).toInt().coerceIn(0, 255)
    }

    /** Packs accumulated float channels back into an ARGB int. */
    private fun pack(a: Float, r: Float, g: Float, b: Float, mode: ConvolutionAlphaMode): Int {
        // Accumulated alpha is normalized (0..1); scale back to 0..255.
        val alpha = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
        if (alpha == 0) return 0
        return when (mode) {
            ConvolutionAlphaMode.ALPHA_ONLY -> {
                // Round only; color was taken verbatim from the center tap.
                (alpha shl 24) or
                    ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (b + 0.5f).toInt().coerceIn(0, 255)
            }
            ConvolutionAlphaMode.PREMULTIPLIED -> {
                // Un-premultiply: divide accumulated premultiplied color by
                // accumulated alpha, guarding against divide-by-zero.
                val fa = alpha / 255f
                val ur = (r / fa + 0.5f).toInt().coerceIn(0, 255)
                val ug = (g / fa + 0.5f).toInt().coerceIn(0, 255)
                val ub = (b / fa + 0.5f).toInt().coerceIn(0, 255)
                (alpha shl 24) or (ur shl 16) or (ug shl 8) or ub
            }
            ConvolutionAlphaMode.STRAIGHT -> {
                (alpha shl 24) or
                    ((r + 0.5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g + 0.5f).toInt().coerceIn(0, 255) shl 8) or
                    (b + 0.5f).toInt().coerceIn(0, 255)
            }
        }
    }
}
