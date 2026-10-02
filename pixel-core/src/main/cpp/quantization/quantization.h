// Color quantization: MedianCut, KMeans (Lab space) and Octree.
// Semantics mirror the Kotlin fallbacks in convert/KotlinQuantizer.kt:
//  - opaque pixels (alpha >= 0x80) participate; transparent pixels map to
//    themselves in the output;
//  - palette entries are opaque (alpha = 0xFF);
//  - all algorithms are deterministic (fixed RNG seed for k-means).
#ifndef PIXEL_LAB_QUANTIZATION_H
#define PIXEL_LAB_QUANTIZATION_H

#include "../common/color_utils.h"
#include <cstdint>
#include <map>
#include <vector>

namespace pixel_lab {

struct QuantizeOutput {
    std::vector<uint32_t> palette;
    // Per-pixel palette-mapped copy of the input. Callers that only need the
    // palette (e.g. the GIF encoder, which re-maps every frame itself after
    // dithering) set wantMapped = false BEFORE the call: the quantizers then
    // skip both the initial full-count copy and the final mapping loop,
    // saving ~2x input-size memory and one full-distance pass.
    std::vector<uint32_t> mapped;
    bool wantMapped = true;
};

// ---- Median cut -----------------------------------------------------------

void medianCut(const uint32_t* pixels, size_t count, int targetColors,
               QuantizeOutput& out);

// ---- K-means (CIELAB, fixed seed) -----------------------------------------

void kmeans(const uint32_t* pixels, size_t count, int targetColors,
            QuantizeOutput& out);

// ---- Octree ---------------------------------------------------------------

void octreeQuantize(const uint32_t* pixels, size_t count, int targetColors,
                    QuantizeOutput& out);

} // namespace pixel_lab

#endif // PIXEL_LAB_QUANTIZATION_H
