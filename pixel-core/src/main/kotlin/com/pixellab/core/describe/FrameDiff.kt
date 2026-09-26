package com.pixellab.core.describe

import com.pixellab.core.color.ColorNamer
import com.pixellab.core.model.PixelFrame

/**
 * One `from → to` color transition counted by [FrameDiff].
 */
class ColorTransition(
    /** Previous ARGB (0 = was transparent). */
    val from: Int,
    /** New ARGB (0 = now transparent). */
    val to: Int,
    /** Pixels taking this transition. */
    val count: Int,
) {
    /** `"#ff0000 -> #00ff00 (12 px)"` short form. */
    override fun toString(): String =
        "${ColorCensus.hex(from)} -> ${ColorCensus.hex(to)} ($count px)"
}

/**
 * Bounding box of a change class as `[x, y, w, h]` plus its pixel count.
 */
class ChangeBox(
    /** Left edge (inclusive). */
    val x: Int,
    /** Top edge (inclusive). */
    val y: Int,
    /** Box width (≥ 1 when [count] > 0). */
    val w: Int,
    /** Box height (≥ 1 when [count] > 0). */
    val h: Int,
    /** Changed pixels inside the box. */
    val count: Int,
)

/**
 * Result of diffing two same-sized frames.
 */
class FrameDiffResult(
    /** The "before" frame. */
    val before: PixelFrame,
    /** The "after" frame. */
    val after: PixelFrame,
    /** Pixels that were transparent and became visible. */
    val added: Int,
    /** Pixels that were visible and became transparent. */
    val removed: Int,
    /** Pixels that changed visible color (alpha-agnostic channel distance > 0). */
    val changed: Int,
    /** Pixels that kept their exact value. */
    val unchanged: Int,
    /** Bounding box of added pixels, or null when none. */
    val addedBox: ChangeBox?,
    /** Bounding box of removed pixels, or null when none. */
    val removedBox: ChangeBox?,
    /** Bounding box of changed pixels, or null when none. */
    val changedBox: ChangeBox?,
    /** Color transitions sorted by descending count (top 16). */
    val transitions: List<ColorTransition>,
) {
    /** Total changed pixels (added + removed + changed). */
    val totalChanges: Int get() = added + removed + changed

    /** True when the frames are pixel-identical. */
    val identical: Boolean get() = totalChanges == 0

    override fun toString(): String =
        "FrameDiff(+$added/-$removed/~$changed, ${if (identical) "identical" else "$totalChanges px"})"
}

/**
 * Structural diff between two frames: what an agent asks after every
 * operation — "did the draw land where I intended?".
 *
 * Semantics per pixel pair:
 *  * transparent → visible counts as **added**,
 *  * visible → transparent counts as **removed**,
 *  * visible → visible with any channel difference counts as **changed**
 *    (recorded as a [ColorTransition]),
 *  * everything else is **unchanged**.
 *
 * Frames of different sizes are compared over their top-left intersection
 * with out-of-intersection pixels classified against transparency (a
 * visible pixel outside the other frame's extent counts as removed when
 * shrinking, added when growing). Pure and deterministic.
 */
object FrameDiff {

    /** Maximum transitions kept in the result. */
    const val MAX_TRANSITIONS: Int = 16

    /**
     * Diffs [before] against [after].
     */
    fun diff(before: PixelFrame, after: PixelFrame): FrameDiffResult {
        val w = minOf(before.width, after.width)
        val h = minOf(before.height, after.height)
        var added = 0
        var removed = 0
        var changed = 0
        var unchanged = 0
        val transitions = HashMap<Long, Int>(32)
        var addMinX = Int.MAX_VALUE; var addMinY = Int.MAX_VALUE; var addMaxX = -1; var addMaxY = -1
        var remMinX = Int.MAX_VALUE; var remMinY = Int.MAX_VALUE; var remMaxX = -1; var remMaxY = -1
        var chgMinX = Int.MAX_VALUE; var chgMinY = Int.MAX_VALUE; var chgMaxX = -1; var chgMaxY = -1

        fun visit(x: Int, y: Int, a: Int, b: Int) {
            val aVisible = a ushr 24 != 0
            val bVisible = b ushr 24 != 0
            when {
                !aVisible && !bVisible -> unchanged++
                !aVisible && bVisible -> {
                    added++
                    if (x < addMinX) addMinX = x
                    if (x > addMaxX) addMaxX = x
                    if (y < addMinY) addMinY = y
                    if (y > addMaxY) addMaxY = y
                }
                aVisible && !bVisible -> {
                    removed++
                    if (x < remMinX) remMinX = x
                    if (x > remMaxX) remMaxX = x
                    if (y < remMinY) remMinY = y
                    if (y > remMaxY) remMaxY = y
                }
                a != b -> {
                    changed++
                    transitions.merge(pack(a, b), 1, Int::plus)
                    if (x < chgMinX) chgMinX = x
                    if (x > chgMaxX) chgMaxX = x
                    if (y < chgMinY) chgMinY = y
                    if (y > chgMaxY) chgMaxY = y
                }
                else -> unchanged++
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                visit(x, y, before.pixels[y * before.width + x], after.pixels[y * after.width + x])
            }
        }
        // Size-mismatch remainder: pixels existing in only one frame.
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                if (x < w && y < h) continue
                val a = before.pixels[y * before.width + x]
                if (a ushr 24 != 0) {
                    removed++
                    if (x < remMinX) remMinX = x
                    if (x > remMaxX) remMaxX = x
                    if (y < remMinY) remMinY = y
                    if (y > remMaxY) remMaxY = y
                }
            }
        }
        for (y in 0 until after.height) {
            for (x in 0 until after.width) {
                if (x < w && y < h) continue
                val b = after.pixels[y * after.width + x]
                if (b ushr 24 != 0) {
                    added++
                    if (x < addMinX) addMinX = x
                    if (x > addMaxX) addMaxX = x
                    if (y < addMinY) addMinY = y
                    if (y > addMaxY) addMaxY = y
                }
            }
        }

        return FrameDiffResult(
            before = before,
            after = after,
            added = added,
            removed = removed,
            changed = changed,
            unchanged = unchanged,
            addedBox = boxOf(added, addMinX, addMinY, addMaxX, addMaxY),
            removedBox = boxOf(removed, remMinX, remMinY, remMaxX, remMaxY),
            changedBox = boxOf(changed, chgMinX, chgMinY, chgMaxX, chgMaxY),
            transitions = transitions.entries
                .map { (key, count) -> ColorTransition(unpackFrom(key), unpackTo(key), count) }
                .sortedWith(compareByDescending<ColorTransition> { it.count }.thenBy { it.from }.thenBy { it.to })
                .take(MAX_TRANSITIONS),
        )
    }

    /**
     * Human sentence for the diff: "identical", "+34 px (box 4,6 12x8, red
     * mostly)", "12 px recolored #ff0000→#00ff00", …
     */
    fun summarize(result: FrameDiffResult): String {
        if (result.identical) return "identical — no pixel changed"
        val parts = ArrayList<String>(4)
        if (result.added > 0) {
            parts.add("+${result.added} added ${boxText(result.addedBox)}")
        }
        if (result.removed > 0) {
            parts.add("-${result.removed} removed ${boxText(result.removedBox)}")
        }
        if (result.changed > 0) {
            parts.add("~${result.changed} recolored ${boxText(result.changedBox)}")
        }
        val top = result.transitions.firstOrNull()
        if (top != null) {
            val fromName = if (top.from ushr 24 == 0) "transparent" else ColorNamer.nearestName(top.from).name
            val toName = if (top.to ushr 24 == 0) "transparent" else ColorNamer.nearestName(top.to).name
            parts.add("mainly $fromName → $toName")
        }
        return parts.joinToString("; ")
    }

    private fun boxText(box: ChangeBox?): String =
        box?.let { "(box ${it.x},${it.y} ${it.w}x${it.h})" } ?: ""

    private fun boxOf(count: Int, minX: Int, minY: Int, maxX: Int, maxY: Int): ChangeBox? {
        if (count == 0) return null
        return ChangeBox(minX, minY, maxX - minX + 1, maxY - minY + 1, count)
    }

    private fun pack(from: Int, to: Int): Long =
        (from.toLong() and 0xFFFFFFFFL) shl 32 or (to.toLong() and 0xFFFFFFFFL)

    private fun unpackFrom(key: Long): Int = (key ushr 32).toInt()

    private fun unpackTo(key: Long): Int = key.toInt()
}
