package com.pixellab.core.atlas

import com.pixellab.core.model.PixelFrame

/**
 * The trimmed-away provenance of one atlas region.
 *
 * When [AtlasBuilder.build] trims a sprite, the region no longer covers the
 * whole source frame; this record says where the content sat inside the
 * original so hosts can restore exact placements:
 *
 *  * [originalW]/[originalH] — the **untrimmed** source frame size;
 *  * [offsetX]/[offsetY] — the top-left of the kept content inside that
 *    original (so the trimmed sprite belongs at `(offsetX, offsetY)` of the
 *    original layout).
 *
 * @throws IllegalArgumentException if any component is negative.
 */
data class OriginalSpec(
    val originalW: Int,
    val originalH: Int,
    val offsetX: Int,
    val offsetY: Int,
) {
    init {
        require(originalW >= 0 && originalH >= 0 && offsetX >= 0 && offsetY >= 0) {
            "OriginalSpec components must be non-negative " +
                "(w=$originalW, h=$originalH, dx=$offsetX, dy=$offsetY)"
        }
    }
}

/**
 * One sprite's final home on the atlas.
 *
 * Mirrors [PackedRect] geometry (`x`, `y`, `w`, `h`, `rotated` — see the
 * [PackedRect] docs for the rotation convention: region dims are swapped and
 * the pixels inside are rotated 90 CW) and adds the trim provenance
 * [original] (`null` when the sprite was not trimmed).
 */
data class AtlasRegion(
    val name: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val rotated: Boolean,
    val original: OriginalSpec?,
)

/**
 * The atlas pipeline on top of [AtlasPacker]: trim, pack, blit, describe.
 *
 * `build` runs four stages:
 *
 * 1. **Trim** (optional): [TextureOps.contentBounds] finds each frame's
 *    opaque content; the content is cropped out and recorded in an
 *    [OriginalSpec]. Fully transparent frames are *not* trimmed (they keep
 *    their full size — there is no content to anchor a crop to).
 * 2. **Pack**: the trimmed contents go through [AtlasPacker.pack] with the
 *    effective padding `padding + extrude` — every sprite reserves
 *    `content + 2 * (padding + extrude)`, so the content keeps `padding`
 *    transparent pixels of clearance even after extrusion eats `extrude`
 *    pixels on each side.
 * 3. **Blit**: each sprite's pixels land at its region; when `extrude > 0`
 *    the (rotated, if placed rotated) frame is run through
 *    [TextureOps.extrudeEdges] first and blitted `extrude` pixels offset
 *    outward, replicating edge pixels into the reserved ring. Trimming
 *    offsets are already baked into the packed positions — the packer only
 *    ever saw the cropped content.
 * 4. **Metadata**: [AtlasRegion]s join the geometry with the trim
 *    provenance (serialized by [AtlasMetadata]).
 */
object AtlasBuilder {

    /**
     * The outcome of [build].
     *
     * @property atlas the rasterized atlas canvas (transparent outside the
     *   blitted sprites and their extrusion rings).
     * @property regions one region per input, in input order.
     * @property width atlas width in pixels.
     * @property height atlas height in pixels.
     */
    data class AtlasResult(
        val atlas: PixelFrame,
        val regions: List<AtlasRegion>,
        val width: Int,
        val height: Int,
    ) {
        /** The region of [name], or `null` when no such name exists. */
        fun find(name: String): AtlasRegion? = regions.firstOrNull { it.name == name }
    }

    /**
     * Builds a texture atlas from [inputs].
     *
     * @param inputs sprites to atlas; names unique (validated by the packer).
     * @param padding transparent clearance around every sprite, `>= 0`
     *   (default 1 — sprites directly adjacent sample-bleed into each other).
     * @param extrude edge-pixel replication outward, `>= 0`; the classic
     *   anti-seam halo (see [TextureOps.extrudeEdges]).
     * @param trim crop each frame to its opaque content first.
     * @param heuristic packing heuristic (see [PackHeuristic]).
     * @param allowRotation allow 90-degree-rotated placements (MaxRects only).
     * @param powerOfTwo round the canvas up to power-of-two dimensions.
     * @return the atlas, its regions and canvas size.
     * @throws IllegalArgumentException when parameters are negative or the
     *   packer/canvas limits are exceeded (propagated).
     */
    fun build(
        inputs: List<AtlasInput>,
        padding: Int = 1,
        extrude: Int = 0,
        trim: Boolean = false,
        heuristic: PackHeuristic = PackHeuristic.MAX_RECTS_BSSF,
        allowRotation: Boolean = false,
        powerOfTwo: Boolean = false,
    ): AtlasResult {
        require(padding >= 0) { "padding must be >= 0 (was $padding)" }
        require(extrude >= 0) { "extrude must be >= 0 (was $extrude)" }

        // Stage 1 — trim.
        val contents = HashMap<String, PixelFrame>(inputs.size)
        val originals = HashMap<String, OriginalSpec?>(inputs.size)
        val packedInputs = ArrayList<AtlasInput>(inputs.size)
        for (input in inputs) {
            val bounds = if (trim) TextureOps.contentBounds(input.frame) else null
            if (bounds != null) {
                contents[input.name] = TextureOps.crop(input.frame, bounds)
                originals[input.name] = OriginalSpec(input.frame.width, input.frame.height, bounds.x, bounds.y)
            } else {
                contents[input.name] = input.frame
                originals[input.name] = null
            }
            packedInputs.add(AtlasInput(input.name, contents[input.name]!!))
        }

        // Stage 2 — pack (extrude consumes part of the reserved ring).
        val packed = AtlasPacker.pack(packedInputs, heuristic, padding + extrude, allowRotation, powerOfTwo)

        // Stage 3 — blit.
        val backing = IntArray(packed.width * packed.height)
        val canvasW = packed.width
        val canvasH = packed.height
        for (rect in packed.rects) {
            var source = contents[rect.name]!!
            if (rect.rotated) source = source.rotated90Cw()
            if (extrude > 0) source = TextureOps.extrudeEdges(source, extrude)
            blit(backing, canvasW, canvasH, source, rect.x - extrude, rect.y - extrude)
        }
        val canvas = PixelFrame.of(canvasW, canvasH, backing)

        // Stage 4 — regions.
        val regions = inputs.map { input ->
            val rect = packed.find(input.name)!!
            AtlasRegion(
                input.name, rect.x, rect.y, rect.w, rect.h, rect.rotated, originals[input.name],
            )
        }
        return AtlasResult(canvas, regions, packed.width, packed.height)
    }

    /**
     * Copies [source] into [backing] (a `canvasW x canvasH` array) at
     * (`originX`, `originY`), clipped — the builder's placements fit by
     * construction, the clip guards power-of-two rounding corner cases.
     */
    private fun blit(
        backing: IntArray,
        canvasW: Int,
        canvasH: Int,
        source: PixelFrame,
        originX: Int,
        originY: Int,
    ) {
        val src = source.pixels
        for (y in 0 until source.height) {
            val py = originY + y
            if (py < 0 || py >= canvasH) continue
            for (x in 0 until source.width) {
                val px = originX + x
                if (px < 0 || px >= canvasW) continue
                backing[py * canvasW + px] = src[y * source.width + x]
            }
        }
    }
}
