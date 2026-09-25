package com.pixellab.core.model

/**
 * Export specification for the Codex companion-pet sprite sheet.
 *
 * The sheet is a fixed 8-column x 9-row grid of 192 x 208 px cells on a
 * 1536 x 1872 px transparent canvas. Each row is one animation track; frames
 * are packed left-to-right, bottom-aligned inside their cell.
 */
object CodexPetSpec {
    const val COLUMNS = 8
    const val ROWS = 9
    const val CELL_WIDTH = 192
    const val CELL_HEIGHT = 208
    const val SHEET_WIDTH = COLUMNS * CELL_WIDTH   // 1536
    const val SHEET_HEIGHT = ROWS * CELL_HEIGHT    // 1872
    const val METADATA_FILE = "pet.json"
    const val SHEET_FILE = "spritesheet.png"

    /** Canonical track order; row i renders track [TRACKS]\[i\]. */
    val TRACKS: List<String> = listOf(
        "idle", "walk", "run", "jump", "sleep", "eat", "play", "happy", "sad",
    )

    /** Pixel budget per cell for reference: 192 x 208 with bottom baseline. */
    const val BASELINE = CELL_HEIGHT
}
