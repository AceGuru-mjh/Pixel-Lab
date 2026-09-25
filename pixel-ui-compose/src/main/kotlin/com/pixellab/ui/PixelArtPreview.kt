package com.pixellab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.pixellab.core.model.PixelFrame
import com.pixellab.ui.theme.PixelTheme
import com.pixellab.ui.theme.argbColor

/** Lowest/highest CRT preview integer zoom. */
private const val MinScale = 1
private const val MaxScale = 64

/** Lowest/highest chromatic aberration strength, screen pixels. */
private const val MaxAberration = 8

/** Lowest/highest bloom radius, screen pixels. */
private const val MaxBloomRadius = 4

/** Zoom from which the pixel grid becomes visible. */
private const val GridScaleThreshold = 8

/** Screen pixels of one transparency-checker tile (2 canvas pixels). */
private const val CheckerTile = 2

/**
 * CRT-style display options of [PixelArtPreview].
 *
 * @param scanlines darken every odd output row.
 * @param scanlineAlpha scanline darkness `0..255` (default 77 ≈ 30%).
 * @param bloom draw offset ghost copies of the frame (soft glow).
 * @param bloomRadius ghost offset ring radius in screen pixels `0..4`.
 * @param chromaticAberration red/blue channel split strength in screen
 *   pixels `0..8`.
 * @param pixelGrid draw the per-pixel grid from [GridScaleThreshold] zoom.
 * @param scale integer zoom factor `1..64`.
 */
data class PreviewStyle(
    val scanlines: Boolean = false,
    val scanlineAlpha: Int = 77,
    val bloom: Boolean = false,
    val bloomRadius: Int = 1,
    val chromaticAberration: Int = 0,
    val pixelGrid: Boolean = true,
    val scale: Int = 4,
) {
    init {
        require(scanlineAlpha in 0..255) { "scanlineAlpha must be in 0..255 (was $scanlineAlpha)" }
        require(bloomRadius in 0..MaxBloomRadius) { "bloomRadius must be in 0..$MaxBloomRadius (was $bloomRadius)" }
        require(chromaticAberration in 0..MaxAberration) {
            "chromaticAberration must be in 0..$MaxAberration (was $chromaticAberration)"
        }
        require(scale in MinScale..MaxScale) { "scale must be in $MinScale..$MaxScale (was $scale)" }
    }
}

/**
 * Pure overlay geometry of the CRT preview, unit-testable on the JVM.
 *
 * All functions are total: out-of-range inputs are coerced, never thrown at.
 */
object PreviewOverlays {

    /**
     * Whether output row [y] carries a scanline: every odd row (0-based).
     * Negative y answers by the same parity rule.
     */
    fun scanlineRow(y: Int): Boolean = (y % 2 + 2) % 2 == 1

    /**
     * Horizontal offsets of the red and blue ghost copies for an aberration
     * [strength]: red shifts right by `+strength`, blue left by
     * `-strength` (a `Pair(red, blue)`).
     */
    fun aberrationOffsets(strength: Int): Pair<Int, Int> {
        val s = strength.coerceIn(0, MaxAberration)
        return s to -s
    }

    /**
     * Bloom pass offsets for a ghost [radius]: every `(dx, dy)` with
     * Chebyshev distance `<= radius`, center `(0,0)` first, then ring by
     * ring. `radius = 1` yields the center plus its 8 neighbors (9 passes);
     * `radius = 0` degenerates to the single center pass; negative radii
     * are treated as 0.
     */
    fun bloomPasses(radius: Int): List<Pair<Int, Int>> {
        val r = radius.coerceAtLeast(0)
        val out = ArrayList<Pair<Int, Int>>(r * 2 + 1)
        for (ring in 0..r) {
            for (dy in -ring..ring) {
                for (dx in -ring..ring) {
                    if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) == ring) {
                        out.add(dx to dy)
                    }
                }
            }
        }
        return out
    }

    /**
     * Whether the *screen* pixel (`x`, `y`) lies on a pixel-grid line at
     * [scale] zoom: true when `x` or `y` is a multiple of [scale]. A
     * non-positive scale disables the grid (always false).
     */
    fun gridCellAt(x: Int, y: Int, scale: Int): Boolean {
        if (scale <= 0) return false
        return x % scale == 0 || y % scale == 0
    }
}

/**
 * CRT-flavored playback preview of a frame sequence.
 *
 * Renders the frame [PlaybackState.frameIndex] of [frames] (the host's
 * playback clock — scaffold-side [TimelineController] timer — advances it;
 * this component re-draws whenever the prop changes) at an integer
 * [PreviewStyle.scale], over the theme transparency checkerboard, with the
 * optional post effects in this paint order:
 *
 * 1. checkerboard + base frame (nearest-neighbor);
 * 2. chromatic aberration: red-tinted copy at `+strength`, blue-tinted copy
 *    at `-strength` — tinted via `ColorFilter.tint` in `Plus` blend, which
 *    keeps the *shape* of the copy and shifts its channel energy (a pure
 *    per-channel split is impossible without shader access; documented
 *    approximation);
 * 3. bloom: ghost copies at every [PreviewOverlays.bloomPasses] offset with
 *    a low alpha (soft glow, no shader blur);
 * 4. scanlines: dark line across every odd row at [PreviewStyle.scanlineAlpha];
 * 5. pixel grid from [GridScaleThreshold] zoom when [PreviewStyle.pixelGrid].
 *
 * @param theme active theme (checker + grid colors).
 * @param frames frames to preview; empty lists render an empty checkerboard.
 * @param style display options; validated on construction.
 * @param playback playback state (frame selection).
 * @param modifier host modifier; the canvas sizes itself to
 *   `frame.width * scale x frame.height * scale`.
 */
@Composable
fun PixelArtPreview(
    theme: PixelTheme,
    frames: List<PixelFrame>,
    style: PreviewStyle,
    playback: PlaybackState,
    modifier: Modifier = Modifier,
) {
    val frameIndex = playback.frameIndex.coerceIn(0, (frames.size - 1).coerceAtLeast(0))
    val frame = frames.getOrNull(frameIndex)
    val bitmap = remember(frame) { frame?.toImageBitmap() }
    val gridColor = remember(theme) { theme.colors.gridLine.argbColor() }
    val checkerLight = remember(theme) { theme.colors.canvasCheckerLight.argbColor() }
    val checkerDark = remember(theme) { theme.colors.canvasCheckerDark.argbColor() }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier) {
            val fw = frame?.width ?: 1
            val fh = frame?.height ?: 1
            val cell = style.scale
            val outW = fw * cell
            val outH = fh * cell
            val tile = cell * CheckerTile

            // 1. transparency checkerboard, 2-canvas-pixel tiles.
            var y = 0
            while (y < outH) {
                var x = 0
                while (x < outW) {
                    val light = ((x / tile) + (y / tile)) % 2 == 0
                    drawRect(
                        color = if (light) checkerLight else checkerDark,
                        topLeft = Offset(x.toFloat(), y.toFloat()),
                        size = Size(
                            tile.coerceAtMost(outW - x).toFloat(),
                            tile.coerceAtMost(outH - y).toFloat(),
                        ),
                    )
                    x += tile
                }
                y += tile
            }

            if (bitmap != null) {
                val dst = IntSize(outW, outH)

                // 2. chromatic aberration ghosts (tinted offset copies).
                if (style.chromaticAberration > 0) {
                    val (red, blue) = PreviewOverlays.aberrationOffsets(style.chromaticAberration)
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(red, 0),
                        dstSize = dst,
                        alpha = 0.55f,
                        colorFilter = ColorFilter.tint(Color(0xFFFF4444), BlendMode.Plus),
                        filterQuality = FilterQuality.None,
                    )
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset(blue, 0),
                        dstSize = dst,
                        alpha = 0.55f,
                        colorFilter = ColorFilter.tint(Color(0xFF44AAFF), BlendMode.Plus),
                        filterQuality = FilterQuality.None,
                    )
                }

                // 1b. the frame itself.
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset.Zero,
                    dstSize = dst,
                    filterQuality = FilterQuality.None,
                )

                // 3. bloom ghosts.
                if (style.bloom) {
                    for ((dx, dy) in PreviewOverlays.bloomPasses(style.bloomRadius)) {
                        if (dx == 0 && dy == 0) continue
                        drawImage(
                            image = bitmap,
                            dstOffset = IntOffset(dx, dy),
                            dstSize = dst,
                            alpha = 0.18f,
                            filterQuality = FilterQuality.None,
                        )
                    }
                }
            }

            // 4. scanlines.
            if (style.scanlines) {
                val scanColor = Color(0xFF000000.toInt())
                var row = 0
                while (row < outH) {
                    if (PreviewOverlays.scanlineRow(row)) {
                        drawRect(
                            color = scanColor,
                            topLeft = Offset(0f, row.toFloat()),
                            size = Size(outW.toFloat(), 1f),
                            alpha = style.scanlineAlpha / 255f,
                        )
                    }
                    row += 1
                }
            }

            // 5. pixel grid.
            if (style.pixelGrid && style.scale >= GridScaleThreshold) {
                var gx = 0
                while (gx <= outW) {
                    if (PreviewOverlays.gridCellAt(gx, 0, style.scale)) {
                        drawLine(
                            color = gridColor,
                            start = Offset(gx.toFloat(), 0f),
                            end = Offset(gx.toFloat(), outH.toFloat()),
                            strokeWidth = 1f,
                        )
                    }
                    gx += 1
                }
                var gy = 0
                while (gy <= outH) {
                    if (PreviewOverlays.gridCellAt(0, gy, style.scale)) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, gy.toFloat()),
                            end = Offset(outW.toFloat(), gy.toFloat()),
                            strokeWidth = 1f,
                        )
                    }
                    gy += 1
                }
            }
        }
    }
}
