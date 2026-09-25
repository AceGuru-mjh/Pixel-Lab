package com.pixellab.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.pixellab.core.model.PixelFrame
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts this [PixelFrame] into a Compose [ImageBitmap] backed by an
 * ARGB_8888 bitmap.
 *
 * The result is cached process-wide keyed by frame **content**: [PixelFrame]
 * implements content-based equality and hashing, so any two frames carrying
 * identical pixels share a single bitmap. Repeated conversions of the same
 * frame (a timeline thumbnail on every recomposition, for example) therefore
 * cost one map lookup instead of a bitmap allocation.
 *
 * The pixel array is copied by [Bitmap.createBitmap], so later frames that
 * reuse the same backing array never alias a stale bitmap.
 *
 * Thread-safe: the cache is a [ConcurrentHashMap] and [PixelFrame] instances
 * are deeply immutable.
 */
fun PixelFrame.toImageBitmap(): ImageBitmap = FrameBitmapCache.obtain(this)

/**
 * Content-keyed bitmap cache backing [toImageBitmap].
 *
 * Eviction policy: when the map grows beyond [MAX_ENTRIES] (256) entries it
 * is cleared outright rather than maintained in LRU order.
 *
 * Why not LRU:
 * 1. [PixelFrame] keys hash and compare whole pixel arrays (O(pixels) per
 *    access), so an LRU's ordered bookkeeping — touch-on-read, unlink,
 *    relink, evict-tail — would add constant overhead on top of already
 *    non-trivial hashing for very little hit-rate gain at this scale.
 * 2. Frames are immutable and small (a 64x64 frame is 16 KB of ints); the
 *    bitmap of an evicted entry can be rebuilt in microseconds, which makes
 *    precise eviction largely pointless.
 * 3. A hard cap plus wholesale clear bounds native bitmap memory
 *    deterministically, without soft references, reference queues, or locks
 *    beyond what [ConcurrentHashMap] already provides.
 * 4. Real editing sessions cycle through far fewer than 256 distinct frames;
 *    wholesale clears only happen after heavy undo/redo or animation churn,
 *    after which the working set rebuilds itself within a few accesses.
 */
private object FrameBitmapCache {

    /** Maximum number of cached bitmaps before the cache is cleared. */
    private const val MAX_ENTRIES = 256

    private val cache = ConcurrentHashMap<PixelFrame, ImageBitmap>()

    /**
     * Returns the cached bitmap for [frame], creating and caching it when
     * absent. The size check happens before insertion so the just-created
     * bitmap always survives the clear it may trigger.
     */
    fun obtain(frame: PixelFrame): ImageBitmap {
        cache[frame]?.let { return it }
        val bitmap = Bitmap
            .createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
            .asImageBitmap()
        if (cache.size > MAX_ENTRIES) {
            // Deliberate full reset instead of LRU eviction; see class KDoc.
            cache.clear()
        }
        cache[frame] = bitmap
        return bitmap
    }
}
