#include "gif_encoder.h"
#include "lzw.h"

#include <cstring>

namespace pixel_lab {

namespace {

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
std::vector<uint8_t> indexFrame(const uint32_t* pixels, size_t count,
                                const std::vector<uint32_t>& palette) {
    std::vector<uint8_t> indices(count);
    for (size_t i = 0; i < count; ++i) {
        if (isTransparent(pixels[i])) {
            indices[i] = 0;
            continue;
        }
        const uint32_t rgb = pixels[i] & 0xFFFFFF;
        // Exact match first (linear scan is fine: <=256 palette entries).
        int found = -1;
        for (size_t p = 1; p < palette.size(); ++p) {
            if ((palette[p] & 0xFFFFFF) == rgb) {
                found = static_cast<int>(p);
                break;
            }
        }
        if (found >= 0) {
            indices[i] = static_cast<uint8_t>(found);
            continue;
        }
        size_t best = 1;
        int64_t bestDist = INT64_MAX;
        for (size_t p = 1; p < palette.size(); ++p) {
            const int64_t d = rgbDistanceSq(pixels[i], palette[p]);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        indices[i] = static_cast<uint8_t>(best);
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

    // ---- Palette: merge all frames, extract <=255 colors --------------------
    std::vector<uint32_t> merged;
    merged.reserve(pixelCount * frames.size());
    for (size_t f = 0; f < frames.size(); ++f) {
        merged.insert(merged.end(), frames[f], frames[f] + pixelCount);
    }
    QuantizeOutput quantized;
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
    std::vector<uint32_t> dithered(pixelCount);
    for (size_t f = 0; f < frames.size(); ++f) {
        const uint32_t* src = frames[f];

        // Dither before palette snapping when requested (kernel NONE = plain
        // nearest-neighbor mapping).
        std::vector<uint32_t> flatPalette(palette.size());
        for (size_t p = 0; p < palette.size(); ++p) {
            flatPalette[p] = packArgb(0xFF, redOf(palette[p]), greenOf(palette[p]), blueOf(palette[p]));
        }
        const DitherKernel effectiveKernel =
            static_cast<DitherKernel>(ditherId < 0 ? 0 : (ditherId > 6 ? 6 : ditherId));
        applyDither(src, width, height, flatPalette.data(), flatPalette.size(),
                    effectiveKernel, 1.0f, dithered.data());

        const std::vector<uint8_t> indices = indexFrame(dithered.data(), pixelCount, palette);

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
