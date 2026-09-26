package com.pixellab.core.describe

import com.pixellab.core.model.PixelFrame

/**
 * Content fingerprint of one frame: a 64-bit FNV-1a digest over the ARGB
 * pixels plus three cheap structural counters.
 *
 * Agents drawing over MCP need a *cheap* way to ask "did my change
 * actually land?" after a write. Re-reading the whole canvas
 * (`canvas_read`) costs a full region dump; diffing (`canvas_diff`) needs
 * the pre-state. A fingerprint answers the same question in O(w*h) with a
 * constant-size reply — compare the digest before and after an operation,
 * or echo it back to the user as proof of state.
 *
 * The digest is sensitive to a single bit of a single pixel, stable across
 * processes (no hashing randomization), and independent of alpha-zero
 * color *channels*: `0x00000000` and `0x00FF00FF` are both fully
 * transparent and therefore equal for the digest, matching how every
 * other Pixel Lab reader treats visibility. That keeps "cosmetic" changes
 * to invisible pixels from masquerading as real edits.
 */
class FrameFingerprint(
    /** 64-bit FNV-1a digest as 16 lowercase hex characters. */
    val digest: String,
    /** Canvas width the digest was computed over. */
    val width: Int,
    /** Canvas height the digest was computed over. */
    val height: Int,
    /** Number of pixels with alpha != 0. */
    val visiblePixels: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is FrameFingerprint &&
            other.digest == digest &&
            other.width == width &&
            other.height == height &&
            other.visiblePixels == visiblePixels

    override fun hashCode(): Int = digest.hashCode() * 31 + visiblePixels

    override fun toString(): String =
        "FrameFingerprint($digest, ${width}x$height, $visiblePixels visible)"
}

/**
 * Stateless fingerprint computer. Pure JVM, no allocation beyond the
 * result object (the digest is built from a [Long] accumulator).
 */
object FrameFingerprintComputer {

    /** FNV-1a 64-bit offset basis. */
    private const val FNV_OFFSET_BASIS: Long = -0x61c8864680b583ebL // 0xcbf29ce484222325

    /** FNV-1a 64-bit prime. */
    private const val FNV_PRIME: Long = 0x100000001b3L

    /** Alphabet for hex rendering. */
    private const val HEX: String = "0123456789abcdef"

    /**
     * Computes the fingerprint of [frame].
     *
     * Alpha-zero pixels contribute a canonical zero regardless of their
     * RGB channels; every visible pixel contributes its exact 32-bit
     * value. Size (w, h) and the visible-pixel count are mixed into the
     * digest, so two frames of different dimensions can never collide by
     * pixel-prefix accident.
     */
    fun of(frame: PixelFrame): FrameFingerprint {
        var hash = FNV_OFFSET_BASIS
        var visible = 0
        val pixels = frame.pixels
        for (i in pixels.indices) {
            val value = pixels[i]
            if (value ushr 24 == 0) {
                hash = fnv(hash, 0)
            } else {
                visible++
                hash = fnv(hash, value)
            }
        }
        // Mix dimensions and visible count so different geometries differ.
        hash = fnv(hash, frame.width)
        hash = fnv(hash, frame.height)
        hash = fnv(hash, visible)
        return FrameFingerprint(hex64(hash), frame.width, frame.height, visible)
    }

    private fun fnv(hash: Long, value: Int): Long {
        val mixed = hash xor (value.toLong() and 0xffffffffL)
        return mixed * FNV_PRIME
    }

    /** 16-char lowercase hex of a 64-bit value. */
    private fun hex64(value: Long): String {
        val out = StringBuilder(16)
        for (shift in 60 downTo 0 step 4) {
            out.append(HEX[((value ushr shift) and 0xfL).toInt()])
        }
        return out.toString()
    }
}
