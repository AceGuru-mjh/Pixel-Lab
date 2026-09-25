// Bulk pixel operations: batch writes, scanline flood fill and layer
// compositing. Semantics mirror engine/FloodFill.kt and the model layer's
// source-over compositing (straight alpha, not premultiplied).
#ifndef PIXEL_LAB_PIXEL_OPS_H
#define PIXEL_LAB_PIXEL_OPS_H

#include <cstdint>
#include <cstddef>
#include <vector>

namespace pixel_lab {

// Batch pixel write: points is x,y pairs; out-of-range coordinates skipped.
// Returns the number of written pixels. Writes into `out` (a copy the caller
// prepared) — the JNI bridge copies the input first.
int64_t setPixelsBatch(const uint32_t* pixels, int width, int height,
                       const int32_t* points, size_t pointCount, uint32_t argb,
                       uint32_t* out);

// Scanline flood fill with per-channel tolerance (max abs diff including
// alpha). Out-of-bounds seed: `out` receives an unchanged copy. Returns the
// number of pixels changed.
int64_t floodFill(const uint32_t* pixels, int width, int height, int x, int y,
                  uint32_t replacement, int tolerance, uint32_t* out);

// Composites same-size layers bottom-up with per-layer opacity (0..1) into
// `out` using straight-alpha source-over. Returns 0 on success.
int compositeLayers(const std::vector<const uint32_t*>& layers,
                    const std::vector<int>& widths, const std::vector<int>& heights,
                    const std::vector<float>& opacities, uint32_t* out);

} // namespace pixel_lab

#endif // PIXEL_LAB_PIXEL_OPS_H
