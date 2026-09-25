package com.pixellab.core.model

/**
 * A named drawing layer inside a sprite project. Layers are stacked bottom-up:
 * index 0 of [SpriteProject.layers] renders first (below everything).
 *
 * [opacity] blends the whole layer; [visible] toggles rendering without losing
 * data; [locked] blocks edits (engines must reject writes to locked layers).
 */
data class Layer(
    /** Unique layer id within its project; stable across reordering. */
    val id: Int,
    val name: String,
    /** Layer-wide alpha in `[0, 1]`. */
    val opacity: Float = 1f,
    val visible: Boolean = true,
    val locked: Boolean = false,
) {
    init {
        require(opacity in 0f..1f) { "Layer opacity must be in [0, 1] (was $opacity)" }
        require(name.isNotBlank()) { "Layer name must not be blank" }
    }

    fun withOpacity(value: Float): Layer = copy(opacity = value.coerceIn(0f, 1f))
    fun withVisible(value: Boolean): Layer = copy(visible = value)
    fun withLocked(value: Boolean): Layer = copy(locked = value)
    fun withName(value: String): Layer = copy(name = value)
}
