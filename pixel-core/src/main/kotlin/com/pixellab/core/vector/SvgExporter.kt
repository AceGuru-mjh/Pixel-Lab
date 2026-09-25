package com.pixellab.core.vector

import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject

/**
 * Scalable Vector Graphics export for pixel art.
 *
 * Two shape modes:
 *  * **Runs** (default) — every color becomes one `<path>` of merged
 *    horizontal rectangle runs (`M x y h w v 1 h -w z` each), the
 *    canonical "pixel SVG" encoding: tiny files, crisp edges, and
 *    editors re-import it losslessly.
 *  * **Outline** — every color region's marching-squares contour becomes
 *    one `<path>` polygon; larger but stroke-friendly for vector-native
 *    workflows.
 *
 * Animated [SpriteProject]s embed SMIL: each frame sits in its own
 * `<g>` with an opacity driver, so a plain browser or Inkscape plays
 * the loop with no scripts.
 */
object SvgExporter {

    /**
     * Rendering mode for the shape geometry.
     */
    enum class ShapeMode {
        /** One path per color, merged horizontal runs. */
        RUNS,

        /** One path per color, marching-squares contours. */
        OUTLINE,
    }

    /**
     * Export options.
     */
    class Options(
        /** Shape geometry mode. */
        val mode: ShapeMode = ShapeMode.RUNS,
        /** Include a `<!-- palette: ... -->` comment listing the colors. */
        val embedPaletteComment: Boolean = true,
        /** XML indentation per nesting level. */
        val indent: String = "  ",
        /** Document title (empty → omit the <title> element). */
        val title: String = "",
    )

    /**
     * Exports a single frame as a standalone SVG document.
     */
    fun export(frame: PixelFrame, options: Options = Options()): String {
        val sb = StringBuilder(1024)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${frame.width}\" height=\"${frame.height}\" ")
        sb.append("viewBox=\"0 0 ${frame.width} ${frame.height}\" shape-rendering=\"crispEdges\">\n")
        if (options.title.isNotEmpty()) {
            sb.append(options.indent).append("<title>").append(escapeXml(options.title)).append("</title>\n")
        }
        if (options.embedPaletteComment) {
            sb.append(options.indent).append("<!-- palette: ")
            sb.append(paletteComment(frame))
            sb.append(" -->\n")
        }
        appendFramePaths(sb, frame, options, options.indent)
        sb.append("</svg>\n")
        return sb.toString()
    }

    /**
     * Exports a multi-frame [project] with a SMIL animation loop.
     *
     * @param frameDurationMs per-frame duration in milliseconds (all
     *   frames share it for a stable SMIL timeline).
     * @param loop forever loop (SMIL `repeatCount="indefinite"`).
     */
    fun exportAnimated(
        project: SpriteProject,
        frameDurationMs: Int = 120,
        loop: Boolean = true,
        options: Options = Options(),
    ): String {
        require(frameDurationMs > 0) { "frameDurationMs must be positive" }
        require(project.frames.isNotEmpty()) { "Project has no frames" }
        val frames = project.frames.mapIndexed { i, _ -> project.compositeFrame(i) }
        val w = frames.maxOf { it.width }
        val h = frames.maxOf { it.height }
        val sb = StringBuilder(2048)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" ")
        sb.append("viewBox=\"0 0 $w $h\" shape-rendering=\"crispEdges\">\n")
        if (options.title.isNotEmpty()) {
            sb.append(options.indent).append("<title>").append(escapeXml(options.title)).append("</title>\n")
        }
        if (options.embedPaletteComment) {
            val colors = frames.asSequence().flatMap { it.pixels.asSequence() }
                .filter { it ushr 24 != 0 }.distinct().toList()
            sb.append(options.indent).append("<!-- palette: ")
            sb.append(colors.joinToString(", ") { "#%06X".format(it and 0xFFFFFF) })
            sb.append(" (animated, ${frames.size} frames @ ${frameDurationMs}ms) -->\n")
        }
        // One <g> per frame; frame 0 starts visible.
        for ((index, frame) in frames.withIndex()) {
            sb.append(options.indent).append("<g id=\"frame$index\" opacity=\"")
                .append(if (index == 0) "1" else "0").append("\">\n")
            appendFramePaths(sb, frame, options, options.indent + options.indent)
            sb.append(options.indent).append("</g>\n")
        }
        // SMIL: each frame's group flashes to opacity 1 during its own
        // slot via discrete calcMode — plays in any plain SVG renderer.
        val total = frameDurationMs * frames.size
        for (index in frames.indices) {
            val values = frames.indices.joinToString(";") { i -> if (i == index) "1" else "0" }
            val keyTimes = frames.indices.joinToString(";") { i ->
                String.format("%.4f", i.toDouble() / frames.size)
            } + ";1"
            sb.append(options.indent)
                .append("<animate href=\"#frame$index\" attributeName=\"opacity\" ")
                .append("dur=\"${total}ms\" begin=\"0s\" ")
                .append("repeatCount=\"${if (loop) "indefinite" else "1"}\" ")
                .append("calcMode=\"discrete\" values=\"$values\" keyTimes=\"$keyTimes\"/>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    /** Adds the per-color paths of [frame] to [sb] at [indent] level. */
    private fun appendFramePaths(sb: StringBuilder, frame: PixelFrame, options: Options, indent: String) {
        // Group pixels by color (transparent skipped), preserving first-
        // appearance order for stable output.
        val runsByColor = LinkedHashMap<Int, ArrayList<IntArray>>()
        for (y in 0 until frame.height) {
            var x = 0
            val row = y * frame.width
            while (x < frame.width) {
                val c = frame.pixels[row + x]
                if (c ushr 24 == 0) {
                    x++
                    continue
                }
                var x2 = x + 1
                while (x2 < frame.width && frame.pixels[row + x2] == c) x2++
                runsByColor.getOrPut(c) { ArrayList(16) }.add(intArrayOf(x, y, x2 - x))
                x = x2
            }
        }
        when (options.mode) {
            ShapeMode.RUNS -> {
                for ((color, runs) in runsByColor) {
                    sb.append(indent).append("<path fill=\"#%06X\"".format(color and 0xFFFFFF))
                    if (color ushr 24 != 0xFF) {
                        sb.append(" fill-opacity=\"${"%.2f".format((color ushr 24) / 255.0)}\"")
                    }
                    sb.append(" d=\"")
                    for (r in runs) {
                        sb.append("M${r[0]} ${r[1]}h${r[2]}v1h-${r[2]}z")
                    }
                    sb.append("\"/>\n")
                }
            }
            ShapeMode.OUTLINE -> {
                for ((color, runs) in runsByColor) {
                    // Build a mask frame for this color from the runs and
                    // trace its contours.
                    val mask = PixelFrame.blank(frame.width, frame.height)
                    val out = mask.pixels
                    for (r in runs) {
                        for (dx in 0 until r[2]) out[r[1] * frame.width + r[0] + dx] = 0xFF shl 24
                    }
                    val contours = MarchingSquares.traceOpacity(PixelFrame.of(frame.width, frame.height, out))
                    if (contours.isEmpty()) continue
                    sb.append(indent).append("<path fill=\"#%06X\"".format(color and 0xFFFFFF))
                    if (color ushr 24 != 0xFF) {
                        sb.append(" fill-opacity=\"${"%.2f".format((color ushr 24) / 255.0)}\"")
                    }
                    sb.append(" fill-rule=\"evenodd\" d=\"")
                    for (c in contours) {
                        for (i in 0 until c.size) {
                            if (i == 0) sb.append("M${c.x[i]} ${c.y[i]}")
                            else sb.append("L${c.x[i]} ${c.y[i]}")
                        }
                        sb.append("Z")
                    }
                    sb.append("\"/>\n")
                }
            }
        }
    }

    /** "#RRGGBB, #RRGGBB, …" listing distinct opaque colors. */
    private fun paletteComment(frame: PixelFrame): String {
        val colors = frame.pixels.asSequence().filter { it ushr 24 != 0 }.distinct().toList()
        return colors.joinToString(", ") { "#%06X".format(it and 0xFFFFFF) }
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
