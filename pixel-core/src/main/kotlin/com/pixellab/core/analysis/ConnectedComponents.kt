package com.pixellab.core.analysis

import com.pixellab.core.model.PixelFrame

/**
 * Neighborhood connectivity for connected-component labeling.
 */
enum class Connectivity {
    /** 4-connected: orthogonal neighbors only (up/down/left/right). */
    FOUR,

    /** 8-connected: orthogonal + diagonal neighbors. */
    EIGHT,
}

/**
 * What makes two pixels belong to the same component.
 */
enum class ComponentColorMode {
    /** Any opaque pixel belongs to the foreground, regardless of color. */
    OPAQUE,

    /** Two pixels are connected only when their ARGB values are equal. */
    SAME_COLOR,
}

/**
 * A labeled connected region ("blob") of a raster.
 *
 * All statistics are computed eagerly at labeling time so downstream
 * consumers pay once: area, bounding box, centroid, perimeter (number of
 * component pixels with at least one 4-neighbor outside the component),
 * hole count (transparent regions fully enclosed by this component),
 * and whether the component touches the canvas border.
 */
class Blob(
    /** Stable label id assigned during labeling (1-based). */
    val id: Int,
    /** Number of pixels in the component. */
    val area: Int,
    /** Row-major flat indices of every pixel in the component. */
    val pixelIndices: IntArray,
    /** Left bound of the bounding box (inclusive). */
    val minX: Int,
    /** Top bound of the bounding box (inclusive). */
    val minY: Int,
    /** Right bound of the bounding box (exclusive). */
    val maxX: Int,
    /** Bottom bound of the bounding box (exclusive). */
    val maxY: Int,
    /** Mean x of member pixels. */
    val centroidX: Double,
    /** Mean y of member pixels. */
    val centroidY: Double,
    /** Number of pixels that have a 4-neighbor outside the component. */
    val perimeter: Int,
    /** Number of fully-enclosed transparent 4-regions inside the component. */
    val holes: Int,
    /** True when any member pixel lies on the canvas border. */
    val touchesBorder: Boolean,
) {
    /** Bounding box width (≥ 1). */
    val width: Int get() = maxX - minX

    /** Bounding box height (≥ 1). */
    val height: Int get() = maxY - minY

    /** Fill ratio of the component inside its bounding box (0..1]. */
    val fillRatio: Double get() = area.toDouble() / (width * height)

    override fun toString(): String =
        "Blob(id=$id, area=$area, box=${minX},${minY}..${maxX - 1},${maxY - 1}, holes=$holes)"
}

/**
 * Result of a connected-component labeling pass.
 */
class ConnectedComponentsResult(
    /** Number of components found. */
    val count: Int,
    /** Components in ascending label order. */
    val blobs: List<Blob>,
    /** Raster width the labels were computed on. */
    val width: Int,
    /** Raster height the labels were computed on. */
    val height: Int,
    /** Per-pixel label map (row-major); 0 = background, `n` = blob [n]'s id. */
    val labels: IntArray,
) {
    /** Largest component by area, or null when none. */
    val largest: Blob? get() = blobs.maxByOrNull { it.area }

    /** Components whose bounding box touches the canvas edge. */
    val borderComponents: List<Blob> get() = blobs.filter { it.touchesBorder }

    override fun toString(): String =
        "ConnectedComponentsResult(count=$count, largest=${largest?.area ?: 0})"
}

/**
 * Connected-component analysis over [PixelFrame]s.
 *
 * Labeling uses the classic two-pass algorithm with union-find path
 * compression and a pre-allocated label image — O(pixels × α⁻¹) in
 * practice, no recursion (stack-safe on any canvas size), zero
 * per-pixel allocations beyond the two result arrays.
 */
object ConnectedComponentOps {

    /**
     * Labels [frame] into connected components.
     *
     * @param connectivity neighbor adjacency for labeling.
     * @param colorMode whether components are color regions or opacity
     *   regions; [ComponentColorMode.OPAQUE] treats every non-transparent
     *   pixel as foreground, [ComponentColorMode.SAME_COLOR] separates
     *   differently-colored areas.
     */
    fun label(
        frame: PixelFrame,
        connectivity: Connectivity = Connectivity.FOUR,
        colorMode: ComponentColorMode = ComponentColorMode.OPAQUE,
    ): ConnectedComponentsResult {
        val w = frame.width
        val h = frame.height
        val src = frame.pixels
        val n = w * h
        val labels = IntArray(n)
        // Union-find over provisional labels; slot 0 unused.
        var nextLabel = 1
        var parent = IntArray(64)

        fun find(label: Int): Int {
            var root = label
            while (parent[root] != root) root = parent[root]
            // Path compression.
            var cur = label
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
        }

        fun ensureCapacity(label: Int) {
            if (label >= parent.size) {
                parent = parent.copyOf(maxOf(parent.size * 2, label + 1))
            }
        }

        fun foreground(idx: Int): Boolean = src[idx] ushr 24 != 0

        fun sameComponent(idxA: Int, idxB: Int): Boolean =
            if (colorMode == ComponentColorMode.OPAQUE) true
            else src[idxA] == src[idxB]

        // Pass 1: provisional labels + union-find merges.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (!foreground(idx)) continue
                var up = 0
                if (y > 0 && foreground(idx - w) && sameComponent(idx, idx - w)) up = labels[idx - w]
                var left = 0
                if (x > 0 && foreground(idx - 1) && sameComponent(idx, idx - 1)) left = labels[idx - 1]
                var upLeft = 0
                var upRight = 0
                if (connectivity == Connectivity.EIGHT) {
                    if (x > 0 && y > 0 && foreground(idx - w - 1) && sameComponent(idx, idx - w - 1)) upLeft = labels[idx - w - 1]
                    if (x < w - 1 && y > 0 && foreground(idx - w + 1) && sameComponent(idx, idx - w + 1)) upRight = labels[idx - w + 1]
                }
                val candidates = intArrayOf(up, left, upLeft, upRight).filter { it != 0 }
                if (candidates.isEmpty()) {
                    ensureCapacity(nextLabel)
                    parent[nextLabel] = nextLabel
                    labels[idx] = nextLabel
                    nextLabel++
                } else {
                    // Merge every candidate into one set FIRST, then read
                    // the surviving root; updating the minimum before the
                    // union would skip the merge (classic union-find trap).
                    var m = candidates[0]
                    for (c in candidates) {
                        union(m, c)
                        m = find(m)
                    }
                    labels[idx] = m
                }
            }
        }

        // Pass 2: resolve roots and renumber densely 1..count.
        val remap = IntArray(nextLabel)
        var count = 0
        for (i in 0 until n) {
            val l = labels[i]
            if (l == 0) continue
            val root = find(l)
            if (remap[root] == 0) remap[root] = ++count
            labels[i] = remap[root]
        }

        // Gather per-blob statistics in one sweep.
        val areas = IntArray(count + 1)
        val minXs = IntArray(count + 1) { Int.MAX_VALUE }
        val minYs = IntArray(count + 1) { Int.MAX_VALUE }
        val maxXs = IntArray(count + 1) { Int.MIN_VALUE }
        val maxYs = IntArray(count + 1) { Int.MIN_VALUE }
        val sumX = LongArray(count + 1)
        val sumY = LongArray(count + 1)
        val members = Array(count + 1) { intArrayOf() }
        val grow = IntArray(count + 1)
        for (i in 0 until n) {
            val l = labels[i]
            if (l == 0) continue
            val x = i % w
            val y = i / w
            areas[l]++
            if (x < minXs[l]) minXs[l] = x
            if (y < minYs[l]) minYs[l] = y
            if (x + 1 > maxXs[l]) maxXs[l] = x + 1
            if (y + 1 > maxYs[l]) maxYs[l] = y + 1
            sumX[l] += x
            sumY[l] += y
        }

        // Member lists via counting sort by label.
        for (l in 1..count) grow[l] = areas[l]
        var acc = 0
        for (l in 1..count) {
            val a = grow[l]
            grow[l] = acc
            acc += a
        }
        val membersFlat = IntArray(acc)
        val cursor = grow.copyOf()
        for (i in 0 until n) {
            val l = labels[i]
            if (l == 0) continue
            membersFlat[cursor[l]++] = i
        }
        for (l in 1..count) {
            members[l] = membersFlat.copyOfRange(grow[l], grow[l] + areas[l])
        }

        // Perimeter + border + holes per blob.
        val perimeters = IntArray(count + 1)
        val borders = BooleanArray(count + 1)
        val inComp = BooleanArray(n) // fast membership for perimeter test
        for (i in 0 until n) inComp[i] = labels[i] != 0 && colorMode == ComponentColorMode.OPAQUE
        if (colorMode == ComponentColorMode.SAME_COLOR) {
            for (i in 0 until n) inComp[i] = labels[i] != 0
        }
        for (i in 0 until n) {
            val l = labels[i]
            if (l == 0) continue
            val x = i % w
            val y = i / w
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) borders[l] = true
            val neighborOutside = (x == 0 || !sameBlob(labels, i - 1, l, colorMode, src)) ||
                (x == w - 1 || !sameBlob(labels, i + 1, l, colorMode, src)) ||
                (y == 0 || !sameBlob(labels, i - w, l, colorMode, src)) ||
                (y == h - 1 || !sameBlob(labels, i + w, l, colorMode, src))
            if (neighborOutside) perimeters[l]++
        }

        // Holes: flood transparent regions from the border once; any
        // unvisited transparent region afterwards belongs to some blob's
        // interior — attribute it to the blob of its first neighbor.
        val holeCounts = IntArray(count + 1)
        if (colorMode == ComponentColorMode.OPAQUE) {
            val visited = BooleanArray(n)
            val queue = IntArray(n)
            for (x in 0 until w) {
                floodOutside(labels, visited, queue, w, h, x, 0)
                floodOutside(labels, visited, queue, w, h, x, h - 1)
            }
            for (y in 0 until h) {
                floodOutside(labels, visited, queue, w, h, 0, y)
                floodOutside(labels, visited, queue, w, h, w - 1, y)
            }
            for (i in 0 until n) {
                if (visited[i] || labels[i] != 0) continue
                // Find owning blob from a 4-neighbor, then flood the hole.
                val owner = neighborBlob(labels, w, h, i) ?: continue
                holeCounts[owner]++
                var head = 0
                var tail = 0
                queue[tail++] = i
                visited[i] = true
                while (head < tail) {
                    val idx = queue[head++]
                    val x = idx % w
                    val y = idx / w
                    if (x > 0 && !visited[idx - 1] && labels[idx - 1] == 0) {
                        visited[idx - 1] = true; queue[tail++] = idx - 1
                    }
                    if (x < w - 1 && !visited[idx + 1] && labels[idx + 1] == 0) {
                        visited[idx + 1] = true; queue[tail++] = idx + 1
                    }
                    if (y > 0 && !visited[idx - w] && labels[idx - w] == 0) {
                        visited[idx - w] = true; queue[tail++] = idx - w
                    }
                    if (y < h - 1 && !visited[idx + w] && labels[idx + w] == 0) {
                        visited[idx + w] = true; queue[tail++] = idx + w
                    }
                }
            }
        }

        val blobs = (1..count).map { l ->
            Blob(
                id = l,
                area = areas[l],
                pixelIndices = members[l],
                minX = minXs[l],
                minY = minYs[l],
                maxX = maxXs[l],
                maxY = maxYs[l],
                centroidX = if (areas[l] == 0) 0.0 else sumX[l].toDouble() / areas[l],
                centroidY = if (areas[l] == 0) 0.0 else sumY[l].toDouble() / areas[l],
                perimeter = perimeters[l],
                holes = holeCounts[l],
                touchesBorder = borders[l],
            )
        }
        return ConnectedComponentsResult(count, blobs, w, h, labels)
    }

    /**
     * Removes components smaller than [minArea] (despeckle). Only
     * [ComponentColorMode.OPAQUE] foreground is considered; colors and
     * remaining components are untouched.
     */
    fun removeSmall(frame: PixelFrame, minArea: Int, connectivity: Connectivity = Connectivity.FOUR): PixelFrame {
        require(minArea > 0) { "minArea must be positive" }
        val result = label(frame, connectivity, ComponentColorMode.OPAQUE)
        if (result.count == 0) return frame
        val kill = BooleanArray(result.count + 1)
        for (b in result.blobs) if (b.area < minArea) kill[b.id] = true
        var any = false
        for (i in result.labels.indices) {
            val l = result.labels[i]
            if (l != 0 && kill[l]) any = true
        }
        if (!any) return frame
        val out = frame.pixels.copyOf()
        for (i in out.indices) {
            val l = result.labels[i]
            if (l != 0 && kill[l]) out[i] = 0
        }
        return PixelFrame.of(frame.width, frame.height, out)
    }

    /**
     * Keeps only the [keepLargest]-th largest components: useful for
     * isolating a hero sprite from background fragments. [count] selects
     * how many of the largest components survive (default 1).
     */
    fun keepLargest(frame: PixelFrame, count: Int = 1, connectivity: Connectivity = Connectivity.FOUR): PixelFrame {
        require(count > 0) { "count must be positive" }
        val result = label(frame, connectivity, ComponentColorMode.OPAQUE)
        if (result.count <= count) return frame
        val survivors = result.blobs.sortedByDescending { it.area }.take(count).map { it.id }.toHashSet()
        val out = frame.pixels.copyOf()
        for (i in out.indices) {
            val l = result.labels[i]
            if (l != 0 && l !in survivors) out[i] = 0
        }
        return PixelFrame.of(frame.width, frame.height, out)
    }

    private fun sameBlob(labels: IntArray, neighborIdx: Int, label: Int, colorMode: ComponentColorMode, src: IntArray): Boolean {
        if (labels[neighborIdx] != label) return false
        return colorMode == ComponentColorMode.OPAQUE || true // color already implied by equal labels
    }

    private fun floodOutside(labels: IntArray, visited: BooleanArray, queue: IntArray, w: Int, h: Int, x0: Int, y0: Int) {
        val start = y0 * w + x0
        if (visited[start] || labels[start] != 0) return
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            val y = idx / w
            if (x > 0 && !visited[idx - 1] && labels[idx - 1] == 0) {
                visited[idx - 1] = true; queue[tail++] = idx - 1
            }
            if (x < w - 1 && !visited[idx + 1] && labels[idx + 1] == 0) {
                visited[idx + 1] = true; queue[tail++] = idx + 1
            }
            if (y > 0 && !visited[idx - w] && labels[idx - w] == 0) {
                visited[idx - w] = true; queue[tail++] = idx - w
            }
            if (y < h - 1 && !visited[idx + w] && labels[idx + w] == 0) {
                visited[idx + w] = true; queue[tail++] = idx + w
            }
        }
    }

    private fun neighborBlob(labels: IntArray, w: Int, h: Int, idx: Int): Int? {
        val x = idx % w
        val y = idx / w
        if (x > 0 && labels[idx - 1] != 0) return labels[idx - 1]
        if (x < w - 1 && labels[idx + 1] != 0) return labels[idx + 1]
        if (y > 0 && labels[idx - w] != 0) return labels[idx - w]
        if (y < h - 1 && labels[idx + w] != 0) return labels[idx + w]
        return null
    }
}
