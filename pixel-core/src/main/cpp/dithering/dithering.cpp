#include "dithering.h"
#include "../common/color_utils.h"

#include <cmath>
#include <cstring>

namespace pixel_lab {

namespace {

const int kBayer2[2][2] = {{0, 2}, {3, 1}};
const int kBayer4[4][4] = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};

// Standard 8x8 Bayer matrix (recursive subdivision).
const int kBayer8[8][8] = {
    {0, 32, 8, 40, 2, 34, 10, 42},   {48, 16, 56, 24, 50, 18, 58, 26},
    {12, 44, 4, 36, 14, 46, 6, 38},  {60, 28, 52, 20, 62, 30, 54, 22},
    {3, 35, 11, 43, 1, 33, 9, 41},   {51, 19, 59, 27, 49, 17, 57, 25},
    {15, 47, 7, 39, 13, 45, 5, 37},  {63, 31, 55, 23, 61, 29, 53, 21},
};

float clampIntensity(float intensity) {
    if (std::isnan(intensity)) {
        return 0.0f;
    }
    if (intensity < 0.0f) return 0.0f;
    if (intensity > 1.0f) return 1.0f;
    return intensity;
}

int truncateTowardZero(double v) {
    // C++ cast-to-int semantics already truncate toward zero; mirrors the
    // Kotlin `.toInt()` behavior of the reference implementation.
    return static_cast<int>(v);
}

struct ErrorBuffer {
    // Three rolling rows of (r, g, b) fractional errors: rows 0/1/2 hold the
    // current row, the next row and the row after that. Each row slot
    // reserves width * 3 doubles.
    double* data;
    int width;
    explicit ErrorBuffer(int w) : data(new double[static_cast<size_t>(w) * 9]()), width(w) {}
    ~ErrorBuffer() { delete[] data; }
    double& at(int row, int col, int channel) {
        // row in {0, 1, 2} relative (current, next, next2).
        return data[(static_cast<size_t>(row) * width + col) * 3 + channel];
    }
    void shift() {
        for (int col = 0; col < width; ++col) {
            for (int ch = 0; ch < 3; ++ch) {
                at(0, col, ch) = at(1, col, ch);
                at(1, col, ch) = at(2, col, ch);
                at(2, col, ch) = 0.0;
            }
        }
    }
};

} // namespace

uint32_t nearestPaletteColor(uint32_t argb, const uint32_t* palette, size_t count) {
    if (count == 0) {
        return argb;
    }
    size_t best = 0;
    int64_t bestDist = INT64_MAX;
    for (size_t i = 0; i < count; ++i) {
        const int64_t d = rgbDistanceSq(argb, palette[i]);
        if (d < bestDist) {
            bestDist = d;
            best = i;
        }
    }
    return palette[best];
}

void applyDither(const uint32_t* pixels, int width, int height,
                 const uint32_t* palette, size_t paletteCount,
                 DitherKernel kernel, float intensity, uint32_t* out) {
    if (pixels == nullptr || out == nullptr || width <= 0 || height <= 0) {
        return;
    }
    if (palette == nullptr || paletteCount == 0) {
        // Mirror the Kotlin contract: no palette means "return a copy".
        std::memcpy(out, pixels, sizeof(uint32_t) * static_cast<size_t>(width) * height);
        return;
    }
    const float t = clampIntensity(intensity);

    if (kernel == DitherKernel::NONE) {
        // Nearest-neighbor Lab-approximation: native path uses RGB distance
        // (documented divergence: both are deterministic; the Kotlin fallback
        // uses Lab). Transparent pixels pass through untouched.
        for (size_t i = 0; i < static_cast<size_t>(width) * height; ++i) {
            if (isTransparent(pixels[i])) {
                out[i] = pixels[i];
            } else {
                const uint32_t near = nearestPaletteColor(pixels[i], palette, paletteCount);
                out[i] = packArgb(alphaOf(pixels[i]), redOf(near), greenOf(near), blueOf(near));
            }
        }
        return;
    }

    if (kernel == DitherKernel::BAYER_2X2 || kernel == DitherKernel::BAYER_4X4 ||
        kernel == DitherKernel::BAYER_8X8) {
        const int n = kernel == DitherKernel::BAYER_2X2 ? 2
                      : (kernel == DitherKernel::BAYER_4X4 ? 4 : 8);
        const double scale = 63.75 * static_cast<double>(t);
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                const size_t idx = static_cast<size_t>(y) * width + x;
                const uint32_t p = pixels[idx];
                if (isTransparent(p)) {
                    out[idx] = p;
                    continue;
                }
                int v;
                if (n == 2) v = kBayer2[y % 2][x % 2];
                else if (n == 4) v = kBayer4[y % 4][x % 4];
                else v = kBayer8[y % 8][x % 8];
                const double threshold = (static_cast<double>(v) + 0.5) / (static_cast<double>(n) * n);
                const int perturb = truncateTowardZero(scale * (threshold - 0.5));
                const int r = redOf(p) + perturb;
                const int g = greenOf(p) + perturb;
                const int b = blueOf(p) + perturb;
                const uint32_t adjusted = packArgb(alphaOf(p), r < 0 ? 0 : (r > 255 ? 255 : r),
                                                   g < 0 ? 0 : (g > 255 ? 255 : g),
                                                   b < 0 ? 0 : (b > 255 ? 255 : b));
                const uint32_t near = nearestPaletteColor(adjusted, palette, paletteCount);
                out[idx] = packArgb(alphaOf(p), redOf(near), greenOf(near), blueOf(near));
            }
        }
        return;
    }

    if (kernel == DitherKernel::CHECKERBOARD) {
        const int offset = truncateTowardZero(32.0 * static_cast<double>(t));
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                const size_t idx = static_cast<size_t>(y) * width + x;
                const uint32_t p = pixels[idx];
                if (isTransparent(p)) {
                    out[idx] = p;
                    continue;
                }
                const int sign = ((x + y) % 2 == 0) ? 1 : -1;
                const int r = redOf(p) + sign * offset;
                const int g = greenOf(p) + sign * offset;
                const int b = blueOf(p) + sign * offset;
                const uint32_t adjusted = packArgb(alphaOf(p), r < 0 ? 0 : (r > 255 ? 255 : r),
                                                   g < 0 ? 0 : (g > 255 ? 255 : g),
                                                   b < 0 ? 0 : (b > 255 ? 255 : b));
                const uint32_t near = nearestPaletteColor(adjusted, palette, paletteCount);
                out[idx] = packArgb(alphaOf(p), redOf(near), greenOf(near), blueOf(near));
            }
        }
        return;
    }

    // Error-diffusion kernels (Floyd-Steinberg / Atkinson).
    ErrorBuffer errors(width);
    const bool atkinson = kernel == DitherKernel::ATKINSON;

    // Scratch copy carrying the evolving "current" colors (opaque only).
    std::vector<uint32_t> work(pixels, pixels + static_cast<size_t>(width) * height);

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t idx = static_cast<size_t>(y) * width + x;
            const uint32_t p = work[idx];
            if (isTransparent(p)) {
                out[idx] = pixels[idx];
                continue;
            }
            int base[3] = {redOf(p), greenOf(p), blueOf(p)};
            // Add accumulated error (row 0 = current row).
            for (int ch = 0; ch < 3; ++ch) {
                base[ch] = truncateTowardZero(base[ch] + errors.at(0, x, ch));
                if (base[ch] < 0) base[ch] = 0;
                if (base[ch] > 255) base[ch] = 255;
            }
            const uint32_t adjusted = packArgb(alphaOf(p), base[0], base[1], base[2]);
            const uint32_t near = nearestPaletteColor(adjusted, palette, paletteCount);
            out[idx] = packArgb(alphaOf(p), redOf(near), greenOf(near), blueOf(near));

            // Error per channel, scaled by intensity.
            const double errR = (static_cast<double>(base[0]) - redOf(near)) * t;
            const double errG = (static_cast<double>(base[1]) - greenOf(near)) * t;
            const double errB = (static_cast<double>(base[2]) - blueOf(near)) * t;

            if (atkinson) {
                // Atkinson: 6 neighbors at 1/8 each (right, right+2,
                // left-down, down, right-down, down+2).
                const double shares[6] = {1.0 / 8, 1.0 / 8, 1.0 / 8, 1.0 / 8, 1.0 / 8, 1.0 / 8};
                const int dxs[6] = {1, 2, -1, 0, 1, 0};
                const int dys[6] = {0, 0, 1, 1, 1, 2};
                for (int i = 0; i < 6; ++i) {
                    const int nx = x + dxs[i];
                    const int ny = y + dys[i];
                    if (nx < 0 || nx >= width || ny >= height) {
                        continue;
                    }
                    const int row = (ny == y) ? 0 : (ny == y + 1 ? 1 : 2);
                    if (row == 0 && nx == x) {
                        continue;
                    }
                    errors.at(row, nx, 0) += errR * shares[i];
                    errors.at(row, nx, 1) += errG * shares[i];
                    errors.at(row, nx, 2) += errB * shares[i];
                }
            } else {
                // Floyd-Steinberg: right 7/16, left-down 3/16, down 5/16,
                // right-down 1/16.
                const int dxs[4] = {1, -1, 0, 1};
                const int dys[4] = {0, 1, 1, 1};
                const double shares[4] = {7.0 / 16, 3.0 / 16, 5.0 / 16, 1.0 / 16};
                for (int i = 0; i < 4; ++i) {
                    const int nx = x + dxs[i];
                    const int ny = y + dys[i];
                    if (nx < 0 || nx >= width || ny >= height) {
                        continue;
                    }
                    const int row = (ny == y) ? 0 : 1;
                    if (row == 0 && nx == x) {
                        continue;
                    }
                    errors.at(row, nx, 0) += errR * shares[i];
                    errors.at(row, nx, 1) += errG * shares[i];
                    errors.at(row, nx, 2) += errB * shares[i];
                }
            }
        }
        errors.shift();
    }
}

} // namespace pixel_lab
