package com.pixellab.core.model

/**
 * Immutable single pixel coordinate on a canvas grid. Coordinates are
 * non-negative: the pixel grid has no negative axes.
 */
data class PixelPoint(val x: Int, val y: Int) {
    init {
        require(x >= 0 && y >= 0) { "PixelPoint coordinates must be non-negative (x=$x, y=$y)" }
    }
}

/**
 * An immutable raster of ARGB_8888 pixels stored in a flat row-major [IntArray].
 *
 * This is the fundamental data unit of Pixel Lab. It intentionally does not
 * reference [android.graphics.Bitmap]: conversion happens at the boundaries
 * (see `com.pixellab.core.convert.BitmapIo`) so the core stays JVM-pure and
 * unit-testable. Pixels are packed integers `0xAARRGGBB`; `0x00000000` is the
 * canonical fully-transparent pixel.
 *
 * Instances are deeply immutable: every mutating operation returns a new frame
 * (copy-on-write). [equals] and [hashCode] compare pixel content, so frames can
 * be used as cache keys.
 */
class PixelFrame private constructor(
    /** Grid width in pixels; always greater than zero. */
    val width: Int,
    /** Grid height in pixels; always greater than zero. */
    val height: Int,
    /** Row-major ARGB pixels, size == [width] * [height]. Never mutated after construction. */
    val pixels: IntArray,
) {

    companion object {
        /**
         * Creates a frame from a row-major ARGB pixel array. The array is
         * COPIED — callers may freely reuse or mutate their buffer afterwards
         * (the earlier reference-sharing contract was a documented-but-weak
         * invariant: one escaped mutation silently corrupted every bitmap
         * cache and shared cel keyed on this frame, and froze its cached
         * hashCode at the pre-mutation value). Hot internal paths that build
         * a fresh array and hand over ownership use the private constructor
         * instead, keeping one copy at most.
         *
         * @throws IllegalArgumentException if dimensions are non-positive or the
         * array size does not equal `width * height`.
         */
        fun of(width: Int, height: Int, pixels: IntArray): PixelFrame {
            require(width > 0 && height > 0) { "Frame dimensions must be positive (w=$width, h=$height)" }
            // Long-domain: `width * height` on Ints wraps (e.g. 65536x65536
            // == 0), which would let a 0-length array masquerade as a valid
            // frame and later corrupt native heap writes.
            require(pixels.size.toLong() == width.toLong() * height.toLong()) {
                "Pixel array size ${pixels.size} does not match ${width}x${height}"
            }
            return PixelFrame(width, height, pixels.copyOf())
        }

        /**
         * Module-internal trusted hand-off: adopts [pixels] WITHOUT copying.
         * Use only when the array was freshly built for this call and no
         * caller retains a reference (engine/composite hot paths —
         * `compositeFrame` runs once per preview refresh and stroke commit,
         * where `of`'s defensive copy would double every allocation).
         */
        internal fun adopt(width: Int, height: Int, pixels: IntArray): PixelFrame {
            require(width > 0 && height > 0) { "Frame dimensions must be positive (w=$width, h=$height)" }
            require(pixels.size.toLong() == width.toLong() * height.toLong()) {
                "Pixel array size ${pixels.size} does not match ${width}x${height}"
            }
            return PixelFrame(width, height, pixels)
        }

        /** Creates a fully transparent frame. */
        fun blank(width: Int, height: Int): PixelFrame {
            require(width > 0 && height > 0) { "Frame dimensions must be positive (w=$width, h=$height)" }
            val count = width.toLong() * height.toLong()
            require(count <= Int.MAX_VALUE) {
                "Frame ${width}x${height} needs $count pixels, beyond the Int array limit"
            }
            return of(width, height, IntArray(count.toInt()))
        }
    }

    /** Total number of pixels (`width * height`). */
    val pixelCount: Int get() = pixels.size

    /** Reads the ARGB pixel at (`x`, `y`); out-of-bounds reads return transparent black. */
    operator fun get(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        return pixels[y * width + x]
    }

    /** Returns a defensive copy of the backing pixel array. */
    fun copyPixels(): IntArray = pixels.copyOf()

    /**
     * Returns a copy of this frame with one pixel replaced. Out-of-bounds
     * coordinates are skipped silently, matching canvas draw semantics.
     */
    fun withPixel(x: Int, y: Int, argb: Int): PixelFrame {
        if (x < 0 || y < 0 || x >= width || y >= height) return this
        val next = pixels.copyOf()
        next[y * width + x] = argb
        return PixelFrame(width, height, next)
    }

    /**
     * Returns a copy with the given pixels set to [argb]. Out-of-bounds points
     * are skipped; the order of the surviving points is preserved.
     */
    fun withPixels(points: List<PixelPoint>, argb: Int): PixelFrame {
        var changed = false
        val next = pixels.copyOf()
        for (p in points) {
            if (p.x in 0 until width && p.y in 0 until height) {
                next[p.y * width + p.x] = argb
                changed = true
            }
        }
        return if (changed) PixelFrame(width, height, next) else this
    }

    /**
     * Returns a copy with individually specified colors applied. Each pair maps
     * a grid point to an ARGB color. Out-of-bounds points are skipped.
     */
    fun withPixels(points: List<PixelPoint>, colors: List<Int>): PixelFrame {
        require(points.size == colors.size) { "points (${points.size}) and colors (${colors.size}) sizes differ" }
        var changed = false
        val next = pixels.copyOf()
        for (i in points.indices) {
            val p = points[i]
            if (p.x in 0 until width && p.y in 0 until height) {
                next[p.y * width + p.x] = colors[i]
                changed = true
            }
        }
        return if (changed) PixelFrame(width, height, next) else this
    }

    /**
     * Generic rebuild: [sample] is called for every output pixel with source
     * coordinates. Out-of-bounds reads inside [sample] should use [get], which
     * yields transparent black. Useful for transforms that change dimensions.
     */
    fun transformed(newWidth: Int, newHeight: Int, sample: (x: Int, y: Int) -> Int): PixelFrame {
        require(newWidth > 0 && newHeight > 0) { "Transformed dimensions must be positive" }
        // Long-domain guard: the private constructor bypassed `of()`'s
        // validation, so 65536x65536 used to wrap to IntArray(0) here and
        // return a malformed frame that exploded on first access.
        val count = newWidth.toLong() * newHeight.toLong()
        require(count <= Int.MAX_VALUE) {
            "Transformed frame ${newWidth}x${newHeight} needs $count pixels, beyond the Int array limit"
        }
        val out = IntArray(count.toInt())
        var i = 0
        for (y in 0 until newHeight) {
            for (x in 0 until newWidth) {
                out[i++] = sample(x, y)
            }
        }
        return PixelFrame(newWidth, newHeight, out)
    }

    /** Returns a copy with every pixel mapped through [transform]. */
    fun map(transform: (argb: Int) -> Int): PixelFrame =
        PixelFrame(width, height, IntArray(pixels.size) { transform(pixels[it]) })

    /** Mirrors horizontally (left-right flip). */
    fun mirroredHorizontally(): PixelFrame =
        transformed(width, height) { x, y -> pixels[y * width + (width - 1 - x)] }

    /** Mirrors vertically (top-bottom flip). */
    fun mirroredVertically(): PixelFrame =
        transformed(width, height) { x, y -> pixels[(height - 1 - y) * width + x] }

    /** Rotates 180 degrees; dimensions unchanged. */
    fun rotated180(): PixelFrame =
        transformed(width, height) { x, y -> pixels[(height - 1 - y) * width + (width - 1 - x)] }

    /**
     * Rotates 90 degrees clockwise; output dimensions are `height x width`.
     * Input pixel (`x`, `y`) lands at output (`height - 1 - y`, `x`).
     */
    fun rotated90Cw(): PixelFrame =
        transformed(height, width) { x, y -> pixels[(height - 1 - x) * width + y] }

    /**
     * Rotates 90 degrees counter-clockwise; output dimensions are `height x
     * width`. Input pixel (`x`, `y`) lands at output (`y`, `width - 1 - x`).
     */
    fun rotated90Ccw(): PixelFrame =
        transformed(height, width) { x, y -> pixels[x * width + (width - 1 - y)] }

    /**
     * Shifts the whole frame by (`dx`, `dy`); areas shifted out are lost and
     * the vacated strip becomes transparent.
     */
    fun shifted(dx: Int, dy: Int): PixelFrame =
        transformed(width, height) { x, y -> this[x - dx, y - dy] }

    /** Integer nearest-neighbor scale; [scale] must be >= 1. */
    fun scaledNearest(scale: Int): PixelFrame {
        require(scale >= 1) { "scale must be >= 1 (was $scale)" }
        if (scale == 1) return this
        // Long-domain: `width * scale * height * scale` wraps for large
        // frames/scales (NegativeArraySizeException or a malformed frame).
        val scaledCount = width.toLong() * scale * height.toLong() * scale
        require(scaledCount <= Int.MAX_VALUE) {
            "Scaled frame ${width}x${height}@$scale needs $scaledCount pixels, beyond the Int array limit"
        }
        val out = IntArray(scaledCount.toInt())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = pixels[y * width + x]
                for (ry in 0 until scale) {
                    val rowStart = (y * scale + ry) * width * scale
                    for (rx in 0 until scale) out[rowStart + x * scale + rx] = c
                }
            }
        }
        return PixelFrame(width * scale, height * scale, out)
    }

    /** Extracts a sub-frame; out-of-bounds regions of the target become transparent. */
    fun region(x0: Int, y0: Int, w: Int, h: Int): PixelFrame {
        require(w > 0 && h > 0) { "region dimensions must be positive" }
        return transformed(w, h) { x, y -> this[x0 + x, y0 + y] }
    }

    /** Iterates every pixel in row-major order. */
    inline fun forEach(action: (x: Int, y: Int, argb: Int) -> Unit) {
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                action(x, y, pixels[i++])
            }
        }
    }

    /** Row view for debugging and text-based inspection. */
    fun toRows(): List<IntArray> = (0 until height).map { y -> pixels.copyOfRange(y * width, (y + 1) * width) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PixelFrame) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    /**
     * Computed once and cached: frames sit in hash maps on every hot path
     * (bitmap caches, dedup sets) and a per-call `contentHashCode` over a
     * 1024x1024 raster is a million integer hashes for nothing. Valid to
     * cache because the constructor contract forbids post-construction
     * mutation of [pixels].
     */
    private var cachedHashCode: Int = 0

    override fun hashCode(): Int {
        var value = cachedHashCode
        if (value == 0) {
            value = 31 * (31 * width + height) + pixels.contentHashCode()
            cachedHashCode = value
        }
        return value
    }

    override fun toString(): String = "PixelFrame(${width}x${height})"
}

/**
 * Mutable workspace counterpart of [PixelFrame] for bulk edits. Convert to an
 * immutable frame with [toImmutable]; the hand-off copies the workspace array
 * (via [PixelFrame.of]'s defensive copy) so the immutable frame stays truly
 * immutable even while the workspace keeps evolving.
 */
class MutablePixelFrame(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Mutable frame dimensions must be positive" }
        // Long-domain: 65536x65536 wrapped to IntArray(0) here while the
        // width/height fields claimed 4.3 billion cells — every subsequent
        // set() threw AIOOBE on a "successfully" constructed workspace.
        val count = width.toLong() * height.toLong()
        require(count <= Int.MAX_VALUE) {
            "Mutable frame ${width}x${height} needs $count pixels, beyond the Int array limit"
        }
    }

    private val pixels: IntArray = IntArray((width.toLong() * height.toLong()).toInt())

    /** Reads the pixel at (`x`, `y`); out-of-bounds reads return 0. */
    operator fun get(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        return pixels[y * width + x]
    }

    /** Writes the pixel at (`x`, `y`); out-of-bounds writes are ignored. */
    operator fun set(x: Int, y: Int, argb: Int) {
        if (x < 0 || y < 0 || x >= width || y >= height) return
        pixels[y * width + x] = argb
    }

    /** Direct access to the backing array, sized `width * height`, row-major. */
    fun backing(): IntArray = pixels

    /** Clears the workspace to fully transparent. */
    fun clear() = pixels.fill(0)

    /** Snapshot into an immutable frame (copies the backing array). */
    fun toImmutable(): PixelFrame = PixelFrame.of(width, height, pixels)
}
