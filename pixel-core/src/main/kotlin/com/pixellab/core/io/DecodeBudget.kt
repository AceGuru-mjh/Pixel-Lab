package com.pixellab.core.io

/**
 * Hard memory ceilings for every decoder in this package. All of them ingest
 * *untrusted bytes* (the MCP `io_import_*` tools feed client base64 straight
 * in), so a 20 KB deflate stream must never be able to ask for a 1.6 GB
 * raster.
 *
 * The constants are deliberately generous for real pixel art (a 4096x4096
 * import is 16.7 M pixels) while capping a hostile file's allocation at a
 * fraction of a phone heap:
 *
 *  * [MAX_DIMENSION] — single edge ceiling (16 384).
 *  * [MAX_FRAME_PIXELS] — one frame's raster (16 777 216 pixels = 64 MB as
 *    an ARGB IntArray).
 *  * [MAX_TOTAL_PIXELS] — the summed rasters of one multi-frame import
 *    (33 554 432 pixels = 128 MB), which is what animation formats retain
 *    (per-frame full-canvas snapshots).
 *
 * Decoders report violations as their normal decode exception type (the
 * constructors take the message), keeping the error contract unchanged.
 */
internal object DecodeBudget {

    /** Ceiling for any single image edge. */
    const val MAX_DIMENSION: Int = 16_384

    /** Ceiling for one frame's pixel count (64 MB as an ARGB IntArray). */
    const val MAX_FRAME_PIXELS: Long = 16_777_216L

    /** Ceiling for the summed frames of one import (128 MB as IntArrays). */
    const val MAX_TOTAL_PIXELS: Long = 33_554_432L

    /**
     * Validates one raster's shape. Call it the moment a header's dimensions
     * are known — BEFORE any buffer sized off them is allocated.
     */
    fun checkFrame(what: String, width: Int, height: Int, failure: (String) -> Exception) {
        if (width <= 0 || height <= 0) {
            throw failure("$what dimensions ${width}x${height} are non-positive")
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw failure("$what edge ${width}x${height} exceeds the $MAX_DIMENSION import ceiling")
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_FRAME_PIXELS) {
            throw failure("$what ${width}x${height} is $pixels pixels, above the $MAX_FRAME_PIXELS-pixel import ceiling")
        }
    }

    /**
     * Accumulating multi-frame budget: call once per retained frame raster
     * (animation decoders keep a full-canvas snapshot per frame, so a
     * tiny-per-frame file can still amplify to gigabytes).
     */
    fun accumulate(what: String, soFar: Long, framePixels: Long, failure: (String) -> Exception): Long {
        val total = soFar + framePixels
        if (total > MAX_TOTAL_PIXELS) {
            throw failure(
                "$what frames exceed the $MAX_TOTAL_PIXELS-pixel total import budget " +
                    "($total pixels retained; reduce the frame count or canvas size)",
            )
        }
        return total
    }
}
