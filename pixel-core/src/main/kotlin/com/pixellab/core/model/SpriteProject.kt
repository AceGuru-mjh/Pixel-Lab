package com.pixellab.core.model

/**
 * The root document of a pixel-art editing session: a grid of fixed `width x
 * height` cells, a stack of layers, a list of animation frames holding per
 * layer cels, a palette, playback settings and animation tags.
 *
 * Immutable value type: every mutation returns a new project (`with*` family).
 * IDs for frames and layers are allocated from monotonic counters carried in
 * the project so they stay unique across the whole editing session.
 */
data class SpriteProject(
    /** Unique project id; also the key used by per-project engine state (undo history). */
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    /** Layers bottom-up: [layers]\[0\] renders first. Never empty. */
    val layers: List<Layer>,
    /** Frames in playback order. Never empty. */
    val frames: List<Frame>,
    /** Id of the layer that receives drawing operations. */
    val activeLayerId: Int,
    /** Index into [frames] of the frame being edited. */
    val activeFrameIndex: Int,
    /** Active draw palette. */
    val palette: Palette,
    /** Playback frames per second, `1..24`. */
    val fps: Int = 12,
    /** Named frame spans (animation labels). */
    val tags: List<AnimationTag> = emptyList(),
    /** Next id to allocate for a new layer. */
    internal val nextLayerId: Int = 1,
    /** Next id to allocate for a new frame. */
    internal val nextFrameId: Int = 1,
) {

    init {
        require(width > 0 && height > 0) { "Project dimensions must be positive (${width}x${height})" }
        require(layers.isNotEmpty()) { "Project must have at least one layer" }
        require(frames.isNotEmpty()) { "Project must have at least one frame" }
        require(activeFrameIndex in frames.indices) { "activeFrameIndex $activeFrameIndex out of bounds (${frames.size} frames)" }
        require(layers.any { it.id == activeLayerId }) { "activeLayerId $activeLayerId not in layer stack" }
        require(fps in 1..24) { "fps must be in [1, 24] (was $fps)" }
        val layerIds = layers.map { it.id }
        require(layerIds.size == layerIds.toSet().size) { "Duplicate layer ids: $layerIds" }
        val frameIds = frames.map { it.id }
        require(frameIds.size == frameIds.toSet().size) { "Duplicate frame ids: $frameIds" }
        for ((i, frame) in frames.withIndex()) {
            for ((layerId, cel) in frame.cels) {
                require(layerId in layerIds) { "Frame $i references unknown layer $layerId" }
                require(cel.width == width && cel.height == height) {
                    "Frame $i layer $layerId cel is ${cel.width}x${cel.height}, project is ${width}x${height}"
                }
            }
        }
        for (tag in tags) {
            require(tag.endFrame < frames.size) { "Tag '${tag.name}' endFrame ${tag.endFrame} out of bounds (${frames.size} frames)" }
        }
    }

    val frameCount: Int get() = frames.size
    val layerCount: Int get() = layers.size
    fun frame(index: Int): Frame = frames[index]
    fun frameById(frameId: Int): Frame? = frames.firstOrNull { it.id == frameId }
    fun layer(layerId: Int): Layer? = layers.firstOrNull { it.id == layerId }
    val activeFrame: Frame get() = frames[activeFrameIndex]
    val activeLayer: Layer get() = layers.first { it.id == activeLayerId }
    /** Index of a layer id in the bottom-up stack, or -1. */
    fun layerIndex(layerId: Int): Int = layers.indexOfFirst { it.id == layerId }

    /**
     * The cel receiving drawing operations: active layer of the active frame.
     * May be null when the layer has no content in this frame yet.
     */
    fun activeCel(): PixelFrame? = activeFrame.cels[activeLayerId]

    /**
     * Composites a frame: layers bottom-up, each cel blended with its layer
     * opacity, invisible layers skipped. Result is a fresh `width x height`
     * frame of plain over-compositing (classic alpha).
     */
    fun compositeFrame(index: Int): PixelFrame {
        require(index in frames.indices) { "Frame index $index out of bounds (${frames.size} frames)" }
        val out = IntArray(width * height)
        for (layer in layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            val cel = frames[index].cels[layer.id] ?: continue
            val op = (layer.opacity * 255f).toInt().coerceIn(0, 255)
            val src = cel.pixels
            for (i in out.indices) {
                val s = src[i]
                val sa = ((s ushr 24) * op) / 255
                if (sa == 0) continue
                val d = out[i]
                out[i] = compositePixel(s, sa, d)
            }
        }
        return PixelFrame.of(width, height, out)
    }

    /** Quick composition of the active frame; see [compositeFrame]. */
    fun compositeActiveFrame(): PixelFrame = compositeFrame(activeFrameIndex)

    /** Effective duration of a frame in ms (per-frame override or `1000 / fps`). */
    fun effectiveFrameDuration(index: Int): Int {
        val override = frames[index].durationMs
        return override ?: (1000f / fps).toInt().coerceAtLeast(1)
    }

    // ---- with* copy family ----------------------------------------------------

    fun withName(value: String): SpriteProject = copy(name = value)
    fun withFps(value: Int): SpriteProject = copy(fps = value.coerceIn(1, 24))
    fun withPalette(value: Palette): SpriteProject = copy(palette = value)

    fun withActiveFrameIndex(value: Int): SpriteProject {
        require(value in frames.indices) { "Frame index $value out of bounds (${frames.size} frames)" }
        return copy(activeFrameIndex = value)
    }

    fun withActiveLayer(layerId: Int): SpriteProject {
        require(layers.any { it.id == layerId }) { "Layer id $layerId not in layer stack" }
        return copy(activeLayerId = layerId)
    }

    fun withLayers(value: List<Layer>): SpriteProject = copy(layers = value, nextLayerId = maxOf(nextLayerId, value.maxOf { it.id } + 1))

    fun withFrames(value: List<Frame>): SpriteProject {
        require(value.isNotEmpty()) { "Project must keep at least one frame" }
        val active = activeFrameIndex.coerceIn(value.indices)
        val activeIds = value.map { it.id }
        return copy(frames = value, activeFrameIndex = active, nextFrameId = maxOf(nextFrameId, activeIds.max() + 1))
    }

    fun withTags(value: List<AnimationTag>): SpriteProject = copy(tags = value)

    /** Replaces the active cel; creating the frame's cel map copy as needed. */
    fun withActiveCel(cel: PixelFrame): SpriteProject {
        require(cel.width == width && cel.height == height) { "Cel ${cel.width}x${cel.height} does not match project ${width}x${height}" }
        val active = frames[activeFrameIndex]
        val next = active.withCel(activeLayerId, cel)
        val newFrames = frames.toMutableList()
        newFrames[activeFrameIndex] = next
        return copy(frames = newFrames)
    }

    /** Replaces the cel of (`layerId`, `frameIndex`). */
    fun withCel(layerId: Int, frameIndex: Int, cel: PixelFrame?): SpriteProject {
        require(layerId in layers.map { it.id }) { "Layer id $layerId not in layer stack" }
        require(frameIndex in frames.indices) { "Frame index $frameIndex out of bounds" }
        val next = frames[frameIndex].withCel(layerId, cel)
        val newFrames = frames.toMutableList()
        newFrames[frameIndex] = next
        return copy(frames = newFrames)
    }

    /** Allocates a fresh layer id. */
    internal fun allocateLayerId(): Int = nextLayerId

    /** Allocates a fresh frame id. */
    internal fun allocateFrameId(): Int = nextFrameId

    override fun toString(): String =
        "SpriteProject('$name', ${width}x${height}, frames=${frames.size}, layers=${layers.size})"
}

/** Standard "source-over" compositing of one pixel with explicit source alpha. */
internal fun compositePixel(src: Int, sa: Int, dst: Int): Int {
    val da = dst ushr 24
    val outA = sa + da * (255 - sa) / 255
    if (outA == 0) return 0
    fun mix(sc: Int, dc: Int): Int = (sc * sa + dc * da * (255 - sa) / 255) / outA
    val r = mix((src shr 16) and 0xFF, (dst shr 16) and 0xFF)
    val g = mix((src shr 8) and 0xFF, (dst shr 8) and 0xFF)
    val b = mix(src and 0xFF, dst and 0xFF)
    return (outA shl 24) or (r shl 16) or (g shl 8) or b
}

/** Factory for well-formed new projects. */
object SpriteFactory {

    /** Creates a single-frame, single-layer transparent project. */
    fun create(name: String, width: Int, height: Int, palette: Palette, id: String = defaultId()): SpriteProject {
        val layer = Layer(id = 0, name = "Layer 1")
        val frame = Frame(id = 0, cels = emptyMap())
        return SpriteProject(
            id = id,
            name = name,
            width = width,
            height = height,
            layers = listOf(layer),
            frames = listOf(frame),
            activeLayerId = layer.id,
            activeFrameIndex = 0,
            palette = palette,
            nextLayerId = 1,
            nextFrameId = 1,
        )
    }

    /** Fresh opaque project id. */
    fun defaultId(): String = "proj-" + System.nanoTime().toString(36)
}
