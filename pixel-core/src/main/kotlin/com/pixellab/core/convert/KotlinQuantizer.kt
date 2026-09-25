package com.pixellab.core.convert

import com.pixellab.core.palette.LabColor
import java.util.PriorityQueue
import java.util.Random

/** Opaque alpha bits (bit pattern `0xFF000000`), shared inside this file. */
private const val OPAQUE: Int = -0x1000000

/** Mask isolating the 24-bit RGB payload of a packed ARGB pixel. */
private const val RGB_MASK: Int = 0xFFFFFF

/**
 * Deterministic pure-JVM color quantization — the reference implementation
 * behind [Quantizer] and the behavior the native bridge mirrors.
 *
 * Semantics shared by every algorithm:
 * - Pixels with `alpha < 0x80` are transparent: they are excluded from all
 *   statistics and pass through `mappedPixels` unchanged.
 * - Palette entries are always opaque (`alpha == 0xFF`); the RGB part of a
 *   mapped opaque pixel comes from the palette while its original alpha is
 *   preserved.
 * - [targetColors] is clamped to `[2, 256]`.
 * - A fully transparent input yields an empty palette plus the input pixels.
 *
 * Algorithm notes (tie-breaks are fixed so runs are byte-identical):
 * - [QuantizeAlgorithm.MEDIAN_CUT]: 24-bit RGB histogram in ascending packed
 *   order; the box with the largest channel range is split next (ties: larger
 *   pixel weight, then smaller box start); the split channel ties resolve
 *   `R > G > B`; the split position is the weighted median (smallest prefix
 *   with `cumulative * 2 >= total`); box colors are weighted means rounded
 *   half up. Inputs whose distinct color count already fits the target are
 *   deduped directly.
 * - [QuantizeAlgorithm.KMEANS]: Lab-space Lloyd iterations, k-means++
 *   initialization with `Random(0x504958)`, at most 16 rounds, convergence
 *   when every center moves below 0.1, empty clusters dropped.
 * - [QuantizeAlgorithm.OCTREE]: depth-8 octree over the distinct colors in
 *   histogram order; reduction repeatedly folds the leaf minimizing
 *   `(pixelCount, nodeIdx)` into its parent (the parent collapses wholesale,
 *   absorbing its subtree; when every remaining leaf hangs directly off the
 *   root, the two smallest leaves merge instead so walks always end on a
 *   leaf); the final palette is collected by depth-first search in child
 *   index order.
 */
internal object KotlinQuantizer {

    /** Clamped lower bound of extractable colors. */
    private const val MIN_TARGET = 2

    /** Clamped upper bound of extractable colors. */
    private const val MAX_TARGET = 256

    /** Fixed k-means seed (hex spelling of "PIXEL") for reproducible clustering. */
    private const val KMEANS_SEED = 0x504958L

    /** Lloyd iteration budget per call. */
    private const val KMEANS_MAX_ROUNDS = 16

    /** Center movement below this value converges a k-means round. */
    private const val KMEANS_EPSILON = 0.1

    /**
     * Quantizes [pixels] down to at most [targetColors] colors with [algorithm].
     * Transparent pixels pass through untouched; the palette is opaque.
     */
    fun quantize(pixels: IntArray, targetColors: Int, algorithm: QuantizeAlgorithm): QuantizeResult {
        val target = targetColors.coerceIn(MIN_TARGET, MAX_TARGET)
        val histogram = Histogram.of(pixels)
        if (histogram.count == 0) {
            // Fully transparent input: nothing to extract, pixels pass through.
            return QuantizeResult(IntArray(0), pixels.copyOf())
        }
        return when (algorithm) {
            QuantizeAlgorithm.MEDIAN_CUT -> medianCut(pixels, histogram, target)
            QuantizeAlgorithm.KMEANS -> kMeans(pixels, histogram, target)
            QuantizeAlgorithm.OCTREE -> octree(pixels, histogram, target)
        }
    }

    // ------------------------------------------------------------------
    // Shared histogram + mapping
    // ------------------------------------------------------------------

    /**
     * Distinct opaque colors of the input (24-bit packed RGB, sorted
     * ascending) with aligned pixel counts.
     */
    private class Histogram(val colors: IntArray, val counts: IntArray) {
        /** Number of distinct colors. */
        val count: Int get() = colors.size

        companion object {
            /** Builds the histogram of opaque pixels of [pixels]. */
            fun of(pixels: IntArray): Histogram {
                val keys = IntArray(pixels.size)
                var opaquePixels = 0
                for (p in pixels) {
                    if ((p ushr 24) >= 0x80) keys[opaquePixels++] = p and RGB_MASK
                }
                val sorted = if (opaquePixels == keys.size) keys else keys.copyOf(opaquePixels)
                sorted.sort()
                var distinct = 0
                var i = 0
                while (i < sorted.size) {
                    distinct++
                    val value = sorted[i]
                    var j = i + 1
                    while (j < sorted.size && sorted[j] == value) j++
                    i = j
                }
                val colors = IntArray(distinct)
                val counts = IntArray(distinct)
                i = 0
                var w = 0
                while (i < sorted.size) {
                    val value = sorted[i]
                    var j = i + 1
                    while (j < sorted.size && sorted[j] == value) j++
                    colors[w] = value
                    counts[w] = j - i
                    w++
                    i = j
                }
                return Histogram(colors, counts)
            }
        }
    }

    /**
     * Maps every opaque pixel onto its color from [colorOf] (packed RGB ->
     * opaque palette ARGB), preserving the pixel's original alpha; transparent
     * pixels are copied verbatim.
     */
    private fun mapPixels(pixels: IntArray, colorOf: HashMap<Int, Int>): IntArray {
        val mapped = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            mapped[i] = if ((p ushr 24) >= 0x80) {
                (colorOf.getValue(p and RGB_MASK) and RGB_MASK) or (p and OPAQUE)
            } else {
                p
            }
        }
        return mapped
    }

    /** Squared Lab distance between two colors. */
    private fun squaredDistance(l0: LabColor.Lab, l1: LabColor.Lab): Double {
        val dl = l0.l - l1.l
        val da = l0.a - l1.a
        val db = l0.b - l1.b
        return dl * dl + da * da + db * db
    }

    // ------------------------------------------------------------------
    // Median cut
    // ------------------------------------------------------------------

    private fun medianCut(pixels: IntArray, histogram: Histogram, target: Int): QuantizeResult {
        if (histogram.count <= target) {
            return dedupe(pixels, histogram)
        }
        val boxes = ArrayList<MedianBox>()
        boxes.add(MedianBox.of(histogram, IntArray(histogram.count) { it }))
        while (boxes.size < target) {
            val pick = pickBoxToSplit(boxes)
            if (pick < 0) break
            // pickBoxToSplit only returns splittable boxes; the null guard
            // simply stops splitting should that invariant ever break.
            val halves = splitBox(histogram, boxes[pick]) ?: break
            boxes.removeAt(pick)
            boxes.add(halves.first)
            boxes.add(halves.second)
        }
        // One color per box; palette sorted by color, duplicate colors merged.
        val boxColors = IntArray(boxes.size)
        for (b in boxes.indices) boxColors[b] = boxColor(histogram, boxes[b])
        val order = boxColors.indices.sortedBy { boxColors[it] }
        val palette = ArrayList<Int>(boxes.size)
        val colorOf = HashMap<Int, Int>(histogram.count * 2)
        var previous = -1
        for (boxIndex in order) {
            val color = boxColors[boxIndex]
            if (palette.isEmpty() || color != previous) {
                palette.add(OPAQUE or color)
                previous = color
            }
            for (member in boxes[boxIndex].members) {
                colorOf[histogram.colors[member]] = palette.last()
            }
        }
        return QuantizeResult(palette.toIntArray(), mapPixels(pixels, colorOf))
    }

    /** Direct dedup path used when the distinct colors already fit the target. */
    private fun dedupe(pixels: IntArray, histogram: Histogram): QuantizeResult {
        val palette = IntArray(histogram.count) { OPAQUE or histogram.colors[it] }
        val colorOf = HashMap<Int, Int>(histogram.count * 2)
        for (i in histogram.colors.indices) colorOf[histogram.colors[i]] = palette[i]
        return QuantizeResult(palette, mapPixels(pixels, colorOf))
    }

    /**
     * Index of the box to split next: largest channel range, ties resolved by
     * larger pixel weight, then by the smaller box start (minimum packed
     * color). Returns -1 when no box is splittable.
     */
    private fun pickBoxToSplit(boxes: ArrayList<MedianBox>): Int {
        var best = -1
        for (i in boxes.indices) {
            val box = boxes[i]
            if (box.largestRange == 0) continue
            if (best < 0) {
                best = i
                continue
            }
            val champion = boxes[best]
            if (box.largestRange > champion.largestRange) {
                best = i
                continue
            }
            if (box.largestRange < champion.largestRange) continue
            if (box.weight > champion.weight) {
                best = i
                continue
            }
            if (box.weight < champion.weight) continue
            if (box.start < champion.start) best = i
        }
        return best
    }

    /**
     * Splits [box] along its widest channel at the weighted median. Members
     * are ordered by `(channel value, packed color)` so the split is fully
     * deterministic. Returns null when the box holds a single color.
     */
    private fun splitBox(histogram: Histogram, box: MedianBox): Pair<MedianBox, MedianBox>? {
        val dr = box.maxR - box.minR
        val dg = box.maxG - box.minG
        val db = box.maxB - box.minB
        var channel = 0
        var range = dr
        if (dg > range) {
            channel = 1
            range = dg
        }
        if (db > range) {
            channel = 2
            range = db
        }
        if (range <= 0) return null
        val shift = when (channel) {
            0 -> 16
            1 -> 8
            else -> 0
        }
        // Composite sort key: channel value, packed color, member index.
        val keys = LongArray(box.members.size)
        for (i in box.members.indices) {
            val member = box.members[i]
            val color = histogram.colors[member]
            val channelValue = (color shr shift) and 0xFF
            keys[i] = (channelValue.toLong() shl 48) or (color.toLong() shl 24) or member.toLong()
        }
        keys.sort()
        val members = IntArray(box.members.size) { i -> keys[i].toInt() and RGB_MASK }
        // Weighted median: smallest prefix whose doubled cumulative weight
        // reaches the box total; clamped so both halves stay non-empty.
        var cumulative = 0
        var splitAt = -1
        for (i in members.indices) {
            cumulative += histogram.counts[members[i]]
            if (cumulative.toLong() * 2 >= box.weight) {
                splitAt = i + 1
                break
            }
        }
        if (splitAt <= 0 || splitAt >= members.size) splitAt = members.size - 1
        val left = MedianBox.of(histogram, members.copyOfRange(0, splitAt))
        val right = MedianBox.of(histogram, members.copyOfRange(splitAt, members.size))
        return left to right
    }

    /** Weighted mean color of a box, each channel rounded half up. */
    private fun boxColor(histogram: Histogram, box: MedianBox): Int {
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var weight = 0.0
        for (member in box.members) {
            val color = histogram.colors[member]
            val w = histogram.counts[member].toDouble()
            sumR += ((color shr 16) and 0xFF) * w
            sumG += ((color shr 8) and 0xFF) * w
            sumB += (color and 0xFF) * w
            weight += w
        }
        val r = (sumR / weight + 0.5).toInt()
        val g = (sumG / weight + 0.5).toInt()
        val b = (sumB / weight + 0.5).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * One median-cut box: histogram member indices plus cached statistics.
     */
    private class MedianBox(
        val members: IntArray,
        val weight: Int,
        val start: Int,
        val minR: Int, val maxR: Int,
        val minG: Int, val maxG: Int,
        val minB: Int, val maxB: Int,
    ) {
        /** Largest per-channel extent inside the box. */
        val largestRange: Int get() = maxOf(maxR - minR, maxG - minG, maxB - minB)

        companion object {
            /** Scans [members] of [histogram] into a box. */
            fun of(histogram: Histogram, members: IntArray): MedianBox {
                var weight = 0
                var start = Int.MAX_VALUE
                var minR = 255; var maxR = 0
                var minG = 255; var maxG = 0
                var minB = 255; var maxB = 0
                for (member in members) {
                    val color = histogram.colors[member]
                    weight += histogram.counts[member]
                    if (color < start) start = color
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    if (r < minR) minR = r
                    if (r > maxR) maxR = r
                    if (g < minG) minG = g
                    if (g > maxG) maxG = g
                    if (b < minB) minB = b
                    if (b > maxB) maxB = b
                }
                return MedianBox(members, weight, start, minR, maxR, minG, maxG, minB, maxB)
            }
        }
    }

    // ------------------------------------------------------------------
    // K-means (Lab space)
    // ------------------------------------------------------------------

    private fun kMeans(pixels: IntArray, histogram: Histogram, target: Int): QuantizeResult {
        val n = histogram.count
        val k = minOf(target, n)
        val labs = Array(n) { LabColor.fromArgb(OPAQUE or histogram.colors[it]) }
        val weights = DoubleArray(n) { histogram.counts[it].toDouble() }
        val random = Random(KMEANS_SEED)

        // k-means++ seeding: uniform first center, then centers drawn with
        // probability proportional to the pixel-weighted squared distance to
        // the nearest center chosen so far.
        val centers = ArrayList<LabColor.Lab>(k)
        val minSquared = DoubleArray(n) { Double.MAX_VALUE }
        fun addCenter(center: LabColor.Lab) {
            centers.add(center)
            for (i in 0 until n) {
                val d = squaredDistance(labs[i], center)
                if (d < minSquared[i]) minSquared[i] = d
            }
        }
        addCenter(labs[random.nextInt(n)])
        while (centers.size < k) {
            var total = 0.0
            for (i in 0 until n) total += weights[i] * minSquared[i]
            if (total <= 0.0) break
            val threshold = random.nextDouble() * total
            var cumulative = 0.0
            var chosen = -1
            for (i in 0 until n) {
                cumulative += weights[i] * minSquared[i]
                if (cumulative > threshold) {
                    chosen = i
                    break
                }
            }
            if (chosen < 0 || minSquared[chosen] <= 0.0) {
                // Numerical fallback: the first point not yet covered.
                chosen = -1
                for (i in 0 until n) {
                    if (minSquared[i] > 0.0) {
                        chosen = i
                        break
                    }
                }
                if (chosen < 0) break
            }
            addCenter(labs[chosen])
        }

        // Lloyd iterations: assign, recompute weighted means, drop empties.
        var current = centers.toTypedArray()
        val assignment = IntArray(n)
        var round = 0
        while (round < KMEANS_MAX_ROUNDS) {
            round++
            for (i in 0 until n) {
                var best = 0
                var bestDistance = Double.MAX_VALUE
                for (c in current.indices) {
                    val d = squaredDistance(labs[i], current[c])
                    if (d < bestDistance) {
                        bestDistance = d
                        best = c
                    }
                }
                assignment[i] = best
            }
            val next = ArrayList<LabColor.Lab>(current.size)
            var maxShift = 0.0
            for (c in current.indices) {
                var weightSum = 0.0
                var sumL = 0.0
                var sumA = 0.0
                var sumB = 0.0
                for (i in 0 until n) {
                    if (assignment[i] != c) continue
                    weightSum += weights[i]
                    sumL += labs[i].l * weights[i]
                    sumA += labs[i].a * weights[i]
                    sumB += labs[i].b * weights[i]
                }
                if (weightSum <= 0.0) continue // empty cluster: dropped
                val mean = LabColor.Lab(sumL / weightSum, sumA / weightSum, sumB / weightSum)
                maxShift = maxOf(maxShift, LabColor.distance(current[c], mean))
                next.add(mean)
            }
            if (next.isEmpty()) break // defensive: unreachable with n >= 1
            current = next.toTypedArray()
            if (maxShift < KMEANS_EPSILON) break
        }

        // Palette: surviving centers as opaque ARGB (order preserved,
        // duplicates merged); every distinct color maps to its nearest center.
        val centerArgb = IntArray(current.size) { LabColor.toArgb(current[it]) }
        val palette = ArrayList<Int>(centerArgb.size)
        for (color in centerArgb) {
            if (color !in palette) palette.add(color)
        }
        val colorOf = HashMap<Int, Int>(n * 2)
        for (i in 0 until n) {
            var best = 0
            var bestDistance = Double.MAX_VALUE
            for (c in centerArgb.indices) {
                val d = squaredDistance(labs[i], current[c])
                if (d < bestDistance) {
                    bestDistance = d
                    best = c
                }
            }
            colorOf[histogram.colors[i]] = centerArgb[best]
        }
        return QuantizeResult(palette.toIntArray(), mapPixels(pixels, colorOf))
    }

    // ------------------------------------------------------------------
    // Octree
    // ------------------------------------------------------------------

    private fun octree(pixels: IntArray, histogram: Histogram, target: Int): QuantizeResult {
        val root = OctNode(null, 0)
        var nextIdx = 1
        // Insert distinct colors in histogram order (ascending packed RGB);
        // every node on the path accumulates the subtree totals.
        for (ci in 0 until histogram.count) {
            val color = histogram.colors[ci]
            val weight = histogram.counts[ci]
            var node = root
            node.absorb(color, weight)
            for (level in 0 until 8) {
                val shift = 7 - level
                val rBit = (color shr (16 + shift)) and 1
                val gBit = (color shr (8 + shift)) and 1
                val bBit = (color shr shift) and 1
                val index = (rBit shl 2) or (gBit shl 1) or bBit
                val child = node.children[index] ?: node.newChild(index, nextIdx++)
                node = child
                node.absorb(color, weight)
            }
        }

        // Reduction: fold the (pixelCount, nodeIdx)-minimal leaf into its
        // parent until the leaf count fits the target.
        val leaves = PriorityQueue<OctNode>(compareBy({ it.count }, { it.nodeIdx }))
        var leafCount = collectLeaves(root, isRoot = true, leaves)
        while (leafCount > target) {
            val heldBack = ArrayList<OctNode>()
            var candidate: OctNode? = null
            while (leaves.isNotEmpty()) {
                val leaf = leaves.poll()
                if (leaf == null || leaf.detached || leaf.childCount != 0) continue // stale entry
                if (leaf.parent === root) {
                    heldBack.add(leaf)
                    continue
                }
                candidate = leaf
                break
            }
            if (candidate == null) {
                // Every live leaf hangs directly off the root: merge the two
                // smallest, repointing the folded leaf's slot at the survivor.
                if (heldBack.size < 2) break // defensive: nothing reducible
                val fold = heldBack[0]
                val keep = heldBack[1]
                keep.absorbNode(fold)
                for (i in 0 until 8) {
                    if (root.children[i] === fold) root.children[i] = keep
                }
                fold.detached = true
                leafCount--
                for (i in 2 until heldBack.size) leaves.add(heldBack[i])
                leaves.add(keep)
            } else {
                for (leaf in heldBack) leaves.add(leaf)
                val parent = checkNotNull(candidate.parent)
                leafCount -= collapse(parent, leaves)
            }
        }

        // Palette: depth-first collection of live leaves in child-index order.
        val palette = ArrayList<Int>()
        val leafIndex = HashMap<OctNode, Int>()
        fun visit(node: OctNode) {
            if (node !== root && node.childCount == 0) {
                if (node !in leafIndex) {
                    leafIndex[node] = palette.size
                    palette.add(node.color())
                }
                return
            }
            for (child in node.children) {
                if (child != null && !child.detached) visit(child)
            }
        }
        visit(root)
        val colorOf = HashMap<Int, Int>(histogram.count * 2)
        for (ci in 0 until histogram.count) {
            val color = histogram.colors[ci]
            val leaf = walkToLeaf(root, color)
            colorOf[color] = palette[leafIndex.getValue(leaf)]
        }
        return QuantizeResult(palette.toIntArray(), mapPixels(pixels, colorOf))
    }

    /** Adds every live leaf of [node] to [leaves]; returns how many were added. */
    private fun collectLeaves(node: OctNode, isRoot: Boolean, leaves: PriorityQueue<OctNode>): Int {
        var added = 0
        if (!isRoot && node.childCount == 0) {
            leaves.add(node)
            added = 1
        }
        for (child in node.children) {
            if (child != null && !child.detached) added += collectLeaves(child, isRoot = false, leaves)
        }
        return added
    }

    /**
     * Collapses [parent] into a single leaf (folding its whole subtree) and
     * enrolls it in [leaves]. Returns the net leaf-count decrease (at least 0:
     * single-child chains shorten the tree without merging leaves).
     */
    private fun collapse(parent: OctNode, leaves: PriorityQueue<OctNode>): Int {
        var removed = 0
        for (i in 0 until 8) {
            val child = parent.children[i] ?: continue
            parent.children[i] = null
            removed += detachSubtree(child)
        }
        parent.childCount = 0
        leaves.add(parent)
        return removed - 1
    }

    /** Marks [node] and its subtree detached; returns the live leaf count inside. */
    private fun detachSubtree(node: OctNode): Int {
        if (node.detached) return 0
        node.detached = true
        var removed = if (node.childCount == 0) 1 else 0
        for (child in node.children) {
            if (child != null && !child.detached) removed += detachSubtree(child)
        }
        return removed
    }

    /** Descends [color]'s path as far as the reduced tree allows. */
    private fun walkToLeaf(root: OctNode, color: Int): OctNode {
        var node = root
        var level = 0
        while (level < 8 && node.childCount != 0) {
            val shift = 7 - level
            val rBit = (color shr (16 + shift)) and 1
            val gBit = (color shr (8 + shift)) and 1
            val bBit = (color shr shift) and 1
            val child = node.children[(rBit shl 2) or (gBit shl 1) or bBit] ?: break
            node = child
            level++
        }
        return node
    }

    /** One octree node accumulating the totals of its subtree. */
    private class OctNode(val parent: OctNode?, val nodeIdx: Int) {
        val children: Array<OctNode?> = arrayOfNulls(8)
        var childCount: Int = 0
        var detached: Boolean = false
        var count: Int = 0
        var sumR: Long = 0
        var sumG: Long = 0
        var sumB: Long = 0

        /** Creates and registers the child at [index]. */
        fun newChild(index: Int, nodeIdx: Int): OctNode {
            val child = OctNode(this, nodeIdx)
            children[index] = child
            childCount++
            return child
        }

        /** Accumulates [weight] pixels of [color] into this node. */
        fun absorb(color: Int, weight: Int) {
            count += weight
            sumR += ((color shr 16) and 0xFF).toLong() * weight
            sumG += ((color shr 8) and 0xFF).toLong() * weight
            sumB += (color and 0xFF).toLong() * weight
        }

        /** Folds another node's totals into this one. */
        fun absorbNode(other: OctNode) {
            count += other.count
            sumR += other.sumR
            sumG += other.sumG
            sumB += other.sumB
        }

        /** Weighted mean color of the subtree, rounded half up, opaque. */
        fun color(): Int {
            val r = (sumR.toDouble() / count + 0.5).toInt().coerceIn(0, 255)
            val g = (sumG.toDouble() / count + 0.5).toInt().coerceIn(0, 255)
            val b = (sumB.toDouble() / count + 0.5).toInt().coerceIn(0, 255)
            return OPAQUE or (r shl 16) or (g shl 8) or b
        }
    }
}
