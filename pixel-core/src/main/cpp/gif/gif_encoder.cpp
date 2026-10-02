#include "gif_encoder.h"
#include "lzw.h"

#include <cstring>
#include <unordered_map>

namespace pixel_lab {

namespace {

// Quantizer input cap. Feeding the concatenated animation into the quantizer
// costs one uint32 per pixel of native memory; 512x512x200 frames used to
// peak at ~210 MB just for that buffer (plus an equal-size `mapped` copy the
// GIF path never read). Animations above this cap are stride-sampled — the
// color histogram stays statistically faithful and deterministic.
constexpr size_t MAX_QUANTIZE_SAMPLES = 1u << 22;

void putU16(std::vector<uint8_t>& out, uint16_t v) {
    out.push_back(static_cast<uint8_t>(v & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
}

void putU32(std::vector<uint8_t>& out, uint32_t v) {
    out.push_back(static_cast<uint8_t>(v & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    out.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
}

void putBytes(std::vector<uint8_t>& out, const char* s, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        out.push_back(static_cast<uint8_t>(s[i]));
    }
}

// Maps opaque pixels onto the palette (exact match cache then nearest RGB);
// transparent pixels map to index 0. Ties resolve to the lowest index.
// `exact` and `nearest` cache resolutions ACROSS frames: pixel art repeats
// colors heavily, so the per-pixel linear palette scan runs at most once
// per distinct color instead of once per pixel.
std::vector<uint8_t> indexFrame(const uint32_t* pixels, size_t count,
                                const std::vector<uint32_t>& palette,
                                std::unordered_map<uint32_t, uint8_t>& nearest) {
    std::vector<uint8_t> indices(count);
    for (size_t i = 0; i < count; ++i) {
        if (isTransparent(pixels[i])) {
            indices[i] = 0;
            continue;
        }
        const uint32_t rgb = pixels[i] & 0xFFFFFF;
        auto cached = nearest.find(rgb);
        if (cached != nearest.end()) {
            indices[i] = cached->second;
            continue;
        }
        // Exact match first (linear scan is fine: <=256 palette entries).
        int found = -1;
        for (size_t p = 1; p < palette.size(); ++p) {
            if ((palette[p] & 0xFFFFFF) == rgb) {
                found = static_cast<int>(p);
                break;
            }
        }
        uint8_t slot;
        if (found >= 0) {
            slot = static_cast<uint8_t>(found);
        } else {
            size_t best = 1;
            int64_t bestDist = INT64_MAX;
            for (size_t p = 1; p < palette.size(); ++p) {
                const int64_t d = rgbDistanceSq(pixels[i], palette[p]);
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
            slot = static_cast<uint8_t>(best);
        }
        nearest.emplace(rgb, slot);
        indices[i] = slot;
    }
    return indices;
}

} // namespace

bool encodeGif(int width, int height,
               const std::vector<const uint32_t*>& frames,
               const std::vector<size_t>& frameSizes,
               const std::vector<int>& delaysMs,
               int loopCount, int quantAlgorithmId, int ditherId,
               std::vector<uint8_t>& out) {
    if (width <= 0 || height <= 0 || width > 0xFFFF || height > 0xFFFF) {
        return false;
    }
    if (frames.empty() || frames.size() != delaysMs.size()) {
        return false;
    }
    const size_t pixelCount = static_cast<size_t>(width) * height;
    for (size_t f = 0; f < frames.size(); ++f) {
        if (frameSizes[f] != pixelCount) {
            return false;
        }
    }

    // ---- Palette: sample all frames, extract <=255 colors -------------------
    // Feed the quantizer a BOUNDED, representative sample: full concatenation
    // costs totalPixels uint32 words of memory (210 MB for 512x512x200); the
    // stride sample keeps the histogram faithful while capping the buffer.
    const size_t totalPixels = pixelCount * frames.size();
    std::vector<uint32_t> merged;
    if (totalPixels <= MAX_QUANTIZE_SAMPLES) {
        merged.reserve(totalPixels);
        for (size_t f = 0; f < frames.size(); ++f) {
            merged.insert(merged.end(), frames[f], frames[f] + pixelCount);
        }
    } else {
        const size_t stride = (totalPixels + MAX_QUANTIZE_SAMPLES - 1) / MAX_QUANTIZE_SAMPLES;
        merged.reserve(totalPixels / stride + 1);
        size_t global = 0;
        for (size_t f = 0; f < frames.size(); ++f) {
            for (size_t i = 0; i < pixelCount; ++i) {
                if (global % stride == 0) {
                    merged.push_back(frames[f][i]);
                }
                ++global;
            }
        }
    }
    QuantizeOutput quantized;
    // The GIF path only consumes `quantized.palette`: every frame is
    // re-mapped individually after dithering. Skipping `mapped` avoids the
    // full-sample copy AND the per-pixel nearest pass inside the quantizer.
    quantized.wantMapped = false;
    switch (quantAlgorithmId) {
        case 1:
            kmeans(merged.data(), merged.size(), 255, quantized);
            break;
        case 2:
            octreeQuantize(merged.data(), merged.size(), 255, quantized);
            break;
        default:
            medianCut(merged.data(), merged.size(), 255, quantized);
            break;
    }
    if (quantized.palette.empty()) {
        // Fully transparent animation: one black entry keeps the GIF valid.
        quantized.palette.push_back(packArgb(0xFF, 0, 0, 0));
    }
    std::vector<uint32_t> palette(1, 0x00000000u);  // slot 0: transparent
    for (uint32_t c : quantized.palette) {
        if (palette.size() >= 256) {
            break;
        }
        palette.push_back(c);
    }

    // ---- Header ---------------------------------------------------------------
    putBytes(out, "GIF89a", 6);
    putU16(out, static_cast<uint16_t>(width));
    putU16(out, static_cast<uint16_t>(height));
    out.push_back(0xF7);  // GCT flag, color resolution 7, size code 7 (256)
    out.push_back(0x00);  // background color index
    out.push_back(0x00);  // pixel aspect ratio

    // ---- Global color table (768 bytes) ---------------------------------------
    for (int i = 0; i < 256; ++i) {
        if (i < static_cast<int>(palette.size())) {
            out.push_back(static_cast<uint8_t>(redOf(palette[i])));
            out.push_back(static_cast<uint8_t>(greenOf(palette[i])));
            out.push_back(static_cast<uint8_t>(blueOf(palette[i])));
        } else {
            out.push_back(0);
            out.push_back(0);
            out.push_back(0);
        }
    }

    // ---- NETSCAPE2.0 loop block -----------------------------------------------
    out.push_back(0x21);
    out.push_back(0xFF);
    out.push_back(0x0B);
    putBytes(out, "NETSCAPE2.0", 11);
    out.push_back(0x03);
    out.push_back(0x01);
    putU16(out, static_cast<uint16_t>(loopCount & 0xFFFF));
    out.push_back(0x00);

    // ---- Frames -----------------------------------------------------------------
    // Dither palette = REAL colors only (slots 1..N). Handing the transparent
    // slot 0 (packed as opaque black) to applyDither made dark opaque pixels
    // snap to a phantom black and corrupted the error-diffusion feedback —
    // indexFrame never maps to slot 0 for opaque pixels, so dithering against
    // it produces colors the indexed output cannot express.
    std::vector<uint32_t> flatPalette(palette.size() - 1);
    for (size_t p = 1; p < palette.size(); ++p) {
        flatPalette[p - 1] = packArgb(0xFF, redOf(palette[p]), greenOf(palette[p]), blueOf(palette[p]));
    }
    std::vector<uint32_t> dithered(pixelCount);
    std::unordered_map<uint32_t, uint8_t> slotCache;
    slotCache.reserve(512);
    for (size_t f = 0; f < frames.size(); ++f) {
        const uint32_t* src = frames[f];

        // Dither before palette snapping when requested (kernel NONE = plain
        // nearest-neighbor mapping).
        const DitherKernel effectiveKernel =
            static_cast<DitherKernel>(ditherId < 0 ? 0 : (ditherId > 6 ? 6 : ditherId));
        applyDither(src, width, height, flatPalette.data(), flatPalette.size(),
                    effectiveKernel, 1.0f, dithered.data());

        const std::vector<uint8_t> indices = indexFrame(dithered.data(), pixelCount, palette, slotCache);

        // Graphic control extension: disposal 2, transparent flag, delay in
        // centiseconds (>= 2 to dodge renderer reinterpretation).
        out.push_back(0x21);
        out.push_back(0xF9);
        out.push_back(0x04);
        out.push_back(0x09);
        int delayCs = delaysMs[f] / 10;
        if (delayCs < 2) delayCs = 2;
        if (delayCs > 0xFFFF) delayCs = 0xFFFF;
        putU16(out, static_cast<uint16_t>(delayCs));
        out.push_back(0x00);  // transparent color index 0
        out.push_back(0x00);

        // Image descriptor: full canvas, no local color table.
        out.push_back(0x2C);
        putU16(out, 0);
        putU16(out, 0);
        putU16(out, static_cast<uint16_t>(width));
        putU16(out, static_cast<uint16_t>(height));
        out.push_back(0x00);

        // LZW-compressed image data, min code size 8.
        out.push_back(0x08);
        std::vector<uint8_t> lzw;
        lzwEncode(indices.data(), indices.size(), 8, lzw);
        out.insert(out.end(), lzw.begin(), lzw.end());
        out.push_back(0x00);  // end of image data
    }

    out.push_back(0x3B);  // trailer
    return true;
}

} // namespace pixel_lab
