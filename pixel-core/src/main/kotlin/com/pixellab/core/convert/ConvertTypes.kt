package com.pixellab.core.convert

/**
 * Color-count reduction strategies shared by the conversion pipeline,
 * quantizer facades and JNI bridges. Ids are wire values — never renumber.
 */
enum class QuantizeAlgorithm(val id: Int) {
    /** Split the RGB histogram into boxes; classic, fast, deterministic. */
    MEDIAN_CUT(0),
    /** Lab-space k-means with a fixed seed; best perceptual clusters. */
    KMEANS(1),
    /** Adaptive octree color reduction; fast, good for large images. */
    OCTREE(2),
}

/** Dithering / error-diffusion strategies. Ids are wire values — never renumber. */
enum class DitherAlgorithm(val id: Int) {
    /** No dithering: straight nearest-palette-color mapping. */
    NONE(0),
    /** Floyd-Steinberg 7/16, 3/16, 5/16, 1/16 kernel. */
    FLOYD_STEINBERG(1),
    /** Atkinson 1/8 x6 kernel (softer grain). */
    ATKINSON(2),
    /** Ordered 2x2 Bayer matrix. */
    BAYER_2X2(3),
    /** Ordered 4x4 Bayer matrix. */
    BAYER_4X4(4),
    /** Ordered 8x8 Bayer matrix. */
    BAYER_8X8(5),
    /** Two-frame checkerboard interleave. */
    CHECKERBOARD(6),
}

/** Result of quantization: the discovered palette plus per-pixel palette mappings. */
data class QuantizeResult(
    /** Discovered colors (ARGB, opaque), at most `targetColors` entries. */
    val palette: IntArray,
    /** Same length as the input pixels; transparent input pixels pass through. */
    val mappedPixels: IntArray,
)

/**
 * Platform-neutral raster image: ARGB pixels in row-major order. This is the
 * input currency of the conversion pipeline so that Bitmaps, decoded buffers
 * or raw arrays from any host can enter the library without Android types.
 */
data class ImageData(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "ImageData dimensions must be positive (${width}x${height})" }
        require(pixels.size == width * height) {
            "Pixel array size ${pixels.size} does not match ${width}x${height}"
        }
    }

    fun toFrame(): com.pixellab.core.model.PixelFrame =
        com.pixellab.core.model.PixelFrame.of(width, height, pixels.copyOf())
}

/** One raster-to-pixel-art conversion request. */
data class ConvertRequest(
    val image: ImageData,
    val targetWidth: Int,
    val targetHeight: Int,
    /** Target palette, or null to extract one from the image. */
    val palette: com.pixellab.core.model.Palette? = null,
    /** Extraction color count, clamped to `[2, 256]`. */
    val colorCount: Int = 16,
    val algorithm: QuantizeAlgorithm = QuantizeAlgorithm.MEDIAN_CUT,
    val dither: DitherAlgorithm = DitherAlgorithm.FLOYD_STEINBERG,
    /** Dither strength in `[0, 1]`; NaN is treated as 0. */
    val ditherIntensity: Float = 1f,
    /** Merge stray isolated pixels into neighbors after dithering. */
    val edgeCleanup: Boolean = true,
    /** Alpha strictly below this becomes fully transparent (default 128). */
    val alphaThreshold: Int = 128,
)

/** Successful conversion payload. */
data class ConvertResult(
    val frame: com.pixellab.core.model.PixelFrame,
    val palette: com.pixellab.core.model.Palette,
    /** Distinct opaque colors present in the output frame. */
    val colorsUsed: Int,
    val transparentPixels: Int,
    val width: Int,
    val height: Int,
)

/** Image analysis used to pre-fill conversion dialogs or agent prompts. */
data class AnalysisResult(
    val suggestedWidth: Int,
    val suggestedHeight: Int,
    val suggestedColorCount: Int,
    val suggestedPaletteId: String,
    /** Mean Lab L* of opaque pixels scaled to `[0, 1]`. */
    val brightness: Float,
    val hasAlpha: Boolean,
    val notes: List<String>,
)

/** Post-conversion cleanup instructions. */
data class RefineInstructions(
    /** Snap alpha: strictly below threshold -> 0, otherwise -> 0xFF. */
    val snapAlphaThreshold: Int? = 128,
    /** Remove single-pixel islands surrounded by different colors. */
    val removeStrayPixels: Boolean = true,
    /** Reserved for future neighbor-count thresholds; only 0 semantics ship. */
    val strayThreshold: Int = 0,
)
