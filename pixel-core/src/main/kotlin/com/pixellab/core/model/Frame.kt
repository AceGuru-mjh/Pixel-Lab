package com.pixellab.core.model

/**
 * A single animation frame: per-layer pixel data plus optional per-frame
 * duration override. Pixels live in `cels`, keyed by layer id; a layer without
 * a cel in a frame renders nothing for that layer (treated as transparent).
 */
data class Frame(
    /** Unique frame id within its project. */
    val id: Int,
    /** Per-layer pixel content; keys reference [Layer.id]. */
    val cels: Map<Int, PixelFrame>,
    /** Explicit frame duration in milliseconds, or null to use project fps. */
    val durationMs: Int? = null,
) {
    init {
        durationMs?.let {
            require(it in 1..60_000) { "Frame duration must be in [1, 60000] ms (was $it)" }
        }
    }

    /** The cel of [layerId], or null when this layer has no content here. */
    fun cel(layerId: Int): PixelFrame? = cels[layerId]

    /** Returns a frame with [layerId]'s cel replaced (or removed when null). */
    fun withCel(layerId: Int, frame: PixelFrame?): Frame {
        val next = cels.toMutableMap()
        if (frame == null) next.remove(layerId) else next[layerId] = frame
        return copy(cels = next)
    }

    /** Returns a frame with a per-frame duration override (null clears it). */
    fun withDuration(durationMs: Int?): Frame = copy(durationMs = durationMs)
}

/** A named span of frames used as an animation label, e.g. `"idle"` over frames 0..3. */
data class AnimationTag(
    val name: String,
    /** Inclusive first frame index. */
    val startFrame: Int,
    /** Inclusive last frame index. */
    val endFrame: Int,
) {
    init {
        require(startFrame >= 0) { "Tag startFrame must be >= 0 (was $startFrame)" }
        require(endFrame >= startFrame) { "Tag endFrame ($endFrame) must be >= startFrame ($startFrame)" }
        require(name.isNotBlank()) { "Tag name must not be blank" }
    }

    /** Whether [frameIndex] falls inside this tag's span. */
    operator fun contains(frameIndex: Int): Boolean = frameIndex in startFrame..endFrame

    /** Number of frames covered. */
    val length: Int get() = endFrame - startFrame + 1
}
