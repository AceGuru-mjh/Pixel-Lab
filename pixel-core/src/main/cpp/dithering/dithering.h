// Dithering kernels: Floyd-Steinberg, Atkinson, ordered Bayer 2x2/4x4/8x8
// and checkerboard. Semantics match convert/KotlinDitherer.kt exactly:
//  - intensity is clamped to [0, 1] (NaN becomes 0);
//  - error diffusion runs left-to-right without serpentine, three-row
//    rolling buffers, errors dropped at borders and on transparent targets;
//  - opaque pixels map to the nearest palette color preserving their alpha;
//  - transparent pixels (alpha < 0x80) are left untouched;
//  - Bayer thresholds are (v + 0.5) / n^2 with perturbation scaled by
//    63.75 * intensity, truncated toward zero;
//  - checkerboard offsets each channel by ± intensity * 32.
#ifndef PIXEL_LAB_DITHERING_H
#define PIXEL_LAB_DITHERING_H

#include <cstdint>
#include <cstddef>

namespace pixel_lab {

enum class DitherKernel {
    NONE = 0,
    FLOYD_STEINBERG = 1,
    ATKINSON = 2,
    BAYER_2X2 = 3,
    BAYER_4X4 = 4,
    BAYER_8X8 = 5,
    CHECKERBOARD = 6,
};

void applyDither(const uint32_t* pixels, int width, int height,
                 const uint32_t* palette, size_t paletteCount,
                 DitherKernel kernel, float intensity, uint32_t* out);

// Nearest palette color by squared RGB distance (ties to lowest index).
uint32_t nearestPaletteColor(uint32_t argb, const uint32_t* palette, size_t count);

} // namespace pixel_lab

#endif // PIXEL_LAB_DITHERING_H
