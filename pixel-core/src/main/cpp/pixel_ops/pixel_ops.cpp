#include "pixel_ops.h"
#include "../common/color_utils.h"

#include <algorithm>
#include <cstring>
#include <vector>

namespace pixel_lab {

namespace {

// Straight-alpha source-over composite of one source pixel over dst.
inline uint32_t compositePixel(uint32_t src, int srcAlpha, uint32_t dst) {
    if (srcAlpha <= 0) {
        return dst;
    }
    const int dstAlpha = alphaOf(dst);
    const int outAlpha = srcAlpha + dstAlpha * (255 - srcAlpha) / 255;
    if (outAlpha == 0) {
        return 0;
    }
    const auto mix = [srcAlpha, dstAlpha, outAlpha](int sc, int dc) -> int {
        return (sc * srcAlpha + dc * dstAlpha * (255 - srcAlpha) / 255) / outAlpha;
    };
    return packArgb(outAlpha,
                    mix(redOf(src), redOf(dst)),
                    mix(greenOf(src), greenOf(dst)),
                    mix(blueOf(src), blueOf(dst)));
}

// Simple stack of pixel indices for the scanline fill (no boxing in C++).
class IndexStack {
public:
    void push(int v) { data_.push_back(v); }
    bool pop(int& v) {
        if (data_.empty()) {
            return false;
        }
        v = data_.back();
        data_.pop_back();
        return true;
    }
    bool empty() const { return data_.empty(); }

private:
    std::vector<int> data_;
};

} // namespace

int64_t setPixelsBatch(const uint32_t* pixels, int width, int height,
                       const int32_t* points, size_t pointCount, uint32_t argb,
                       uint32_t* out) {
    if (out != pixels) {
        std::memcpy(out, pixels, sizeof(uint32_t) * static_cast<size_t>(width) * height);
    }
    int64_t written = 0;
    for (size_t i = 0; i < pointCount; ++i) {
        const int x = points[i * 2];
        const int y = points[i * 2 + 1];
        if (x < 0 || y < 0 || x >= width || y >= height) {
            continue;
        }
        out[static_cast<size_t>(y) * width + x] = argb;
        ++written;
    }
    return written;
}

int64_t floodFill(const uint32_t* pixels, int width, int height, int x, int y,
                  uint32_t replacement, int tolerance, uint32_t* out) {
    std::memcpy(out, pixels, sizeof(uint32_t) * static_cast<size_t>(width) * height);
    if (x < 0 || y < 0 || x >= width || y >= height) {
        return 0;
    }
    const uint32_t target = pixels[static_cast<size_t>(y) * width + x];
    if (tolerance < 0) {
        tolerance = 0;
    }

    if (tolerance == 0) {
        // Exact-match fast path with a visited bitmap.
        if (target == replacement) {
            return 0;
        }
        std::vector<uint64_t> visited(static_cast<size_t>((static_cast<size_t>(width) * height + 63) / 64), 0);
        IndexStack stack;
        stack.push(y * width + x);
        int64_t changed = 0;
        int v;
        while (stack.pop(v)) {
            if (v < 0 || v >= width * height) {
                continue;
            }
            const size_t word = static_cast<size_t>(v) >> 6;
            const uint64_t bit = 1ULL << (v & 63);
            if (visited[word] & bit) {
                continue;
            }
            visited[word] |= bit;
            if (pixels[v] != target) {
                continue;
            }
            out[v] = replacement;
            ++changed;
            const int cx = v % width;
            const int cy = v / width;
            if (cx > 0) stack.push(v - 1);
            if (cx < width - 1) stack.push(v + 1);
            if (cy > 0) stack.push(v - width);
            if (cy < height - 1) stack.push(v + width);
        }
        return changed;
    }

    // Tolerant scanline fill: expand runs left/right, seed the first cell of
    // adjacent-row runs (matches FloodFill.kt).
    if (channelMaxDiff(target, replacement) <= tolerance && target == replacement) {
        return 0;
    }
    std::vector<uint64_t> visited(static_cast<size_t>((static_cast<size_t>(width) * height + 63) / 64), 0);
    IndexStack seeds;
    seeds.push(y * width + x);
    int64_t changed = 0;
    int seed;
    while (seeds.pop(seed)) {
        const int sx = seed % width;
        const int sy = seed / width;
        if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
            continue;
        }
        const size_t sWord = static_cast<size_t>(seed) >> 6;
        const uint64_t sBit = 1ULL << (seed & 63);
        if (visited[sWord] & sBit) {
            continue;
        }
        if (channelMaxDiff(pixels[static_cast<size_t>(seed)], target) > static_cast<int64_t>(tolerance)) {
            continue;
        }
        // Expand the run containing the seed.
        int left = sx;
        while (left > 0 && channelMaxDiff(pixels[static_cast<size_t>(sy) * width + left - 1], target) <= tolerance) {
            --left;
        }
        int right = sx;
        while (right < width - 1 && channelMaxDiff(pixels[static_cast<size_t>(sy) * width + right + 1], target) <= tolerance) {
            ++right;
        }
        for (int cx = left; cx <= right; ++cx) {
            const int idx = sy * width + cx;
            const size_t word = static_cast<size_t>(idx) >> 6;
            const uint64_t bit = 1ULL << (idx & 63);
            if (visited[word] & bit) {
                continue;
            }
            visited[word] |= bit;
            out[idx] = replacement;
            ++changed;
        }
        // Seed adjacent rows at run extremes and interior transitions.
        for (int row = sy - 1; row <= sy + 1; row += 2) {
            if (row < 0 || row >= height) {
                continue;
            }
            for (int cx = left; cx <= right; ++cx) {
                const int idx = row * width + cx;
                const size_t word = static_cast<size_t>(idx) >> 6;
                const uint64_t bit = 1ULL << (idx & 63);
                if ((visited[word] & bit) == 0 &&
                    channelMaxDiff(pixels[static_cast<size_t>(idx)], target) <= tolerance) {
                    seeds.push(idx);
                    // Skip to the next non-matching cell to bound seed count.
                    int nx = cx + 1;
                    while (nx <= right &&
                           channelMaxDiff(pixels[static_cast<size_t>(row) * width + nx], target) <= tolerance) {
                        ++nx;
                    }
                    cx = nx - 1;
                }
            }
        }
    }
    return changed;
}

int compositeLayers(const std::vector<const uint32_t*>& layers,
                    const std::vector<int>& widths, const std::vector<int>& heights,
                    const std::vector<float>& opacities, uint32_t* out) {
    if (layers.empty()) {
        return -1;
    }
    const int width = widths[0];
    const int height = heights[0];
    const size_t count = static_cast<size_t>(width) * height;
    for (size_t i = 0; i < layers.size(); ++i) {
        if (widths[i] != width || heights[i] != height) {
            return -1;
        }
    }
    std::memset(out, 0, sizeof(uint32_t) * count);
    for (size_t layer = 0; layer < layers.size(); ++layer) {
        float opacity = opacities[layer];
        if (opacity < 0.0f) opacity = 0.0f;
        if (opacity > 1.0f) opacity = 1.0f;
        if (opacity <= 0.0f) {
            continue;
        }
        const int layerAlpha = static_cast<int>(opacity * 255.0f + 0.5f);
        const uint32_t* src = layers[layer];
        for (size_t i = 0; i < count; ++i) {
            const uint32_t s = src[i];
            const int srcAlpha = alphaOf(s);
            if (srcAlpha == 0) {
                continue;
            }
            const int effective = (srcAlpha * layerAlpha) / 255;
            out[i] = compositePixel(s, effective, out[i]);
        }
    }
    return 0;
}

} // namespace pixel_lab
