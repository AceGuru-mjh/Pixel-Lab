// GIF89a encoder. Global color table (256 entries, slot 0 transparent),
// per-frame graphic control extension with disposal 2, NETSCAPE2.0 looping,
// optional dithering before palette snapping. Output is deterministic for a
// given (frames, delays, loopCount, quantAlgorithmId, ditherId) tuple.
#ifndef PIXEL_LAB_GIF_ENCODER_H
#define PIXEL_LAB_GIF_ENCODER_H

#include "../common/color_utils.h"
#include "../quantization/quantization.h"
#include "../dithering/dithering.h"

#include <cstdint>
#include <cstddef>
#include <vector>

namespace pixel_lab {

// Encodes frames (each width*height ARGB pixels) into a GIF89a byte stream.
// delaysMs per frame; loopCount 0 = forever. quantAlgorithmId selects the
// palette extraction (0 median cut, 1 k-means, 2 octree); ditherId selects
// the pre-snap dithering kernel (see DitherKernel).
bool encodeGif(int width, int height,
               const std::vector<const uint32_t*>& frames,
               const std::vector<size_t>& frameSizes,
               const std::vector<int>& delaysMs,
               int loopCount, int quantAlgorithmId, int ditherId,
               std::vector<uint8_t>& out);

} // namespace pixel_lab

#endif // PIXEL_LAB_GIF_ENCODER_H
