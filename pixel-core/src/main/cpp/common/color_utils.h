// Color utilities shared by the quantizer, ditherer and GIF encoder.
// All functions operate on packed ARGB integers (0xAARRGGBB) in host byte
// order; no endianness assumptions.
#ifndef PIXEL_LAB_COLOR_UTILS_H
#define PIXEL_LAB_COLOR_UTILS_H

#include <cstdint>
#include <cmath>
#include <vector>

namespace pixel_lab {

inline int alphaOf(uint32_t argb) { return static_cast<int>((argb >> 24) & 0xFF); }
inline int redOf(uint32_t argb) { return static_cast<int>((argb >> 16) & 0xFF); }
inline int greenOf(uint32_t argb) { return static_cast<int>((argb >> 8) & 0xFF); }
inline int blueOf(uint32_t argb) { return static_cast<int>(argb & 0xFF); }

inline uint32_t packArgb(int a, int r, int g, int b) {
    return (static_cast<uint32_t>(a) << 24) |
           (static_cast<uint32_t>(r & 0xFF) << 16) |
           (static_cast<uint32_t>(g & 0xFF) << 8) |
           static_cast<uint32_t>(b & 0xFF);
}

inline bool isTransparent(uint32_t argb, int threshold = 0x80) {
    return alphaOf(argb) < threshold;
}

// Squared RGB distance between two packed pixels (alpha ignored).
inline int64_t rgbDistanceSq(uint32_t a, uint32_t b) {
    const int dr = redOf(a) - redOf(b);
    const int dg = greenOf(a) - greenOf(b);
    const int db = blueOf(a) - blueOf(b);
    return static_cast<int64_t>(dr) * dr + static_cast<int64_t>(dg) * dg +
           static_cast<int64_t>(db) * db;
}

// Maximum absolute per-channel difference (A, R, G, B) — the tolerance
// predicate used by flood fill. Alpha participates per the Kotlin contract.
inline int channelMaxDiff(uint32_t a, uint32_t b) {
    int d = std::abs(alphaOf(a) - alphaOf(b));
    d = std::max(d, std::abs(redOf(a) - redOf(b)));
    d = std::max(d, std::abs(greenOf(a) - greenOf(b)));
    d = std::max(d, std::abs(blueOf(a) - blueOf(b)));
    return d;
}

// sRGB -> CIELAB conversion for the k-means quantizer. Matches the Kotlin
// LabColor implementation constant-for-constant (D65, f(t) with 903.3 knee).
struct Lab {
    double l;
    double a;
    double b;
};

inline double srgbLinear(double c) {
    return c <= 0.04045 ? c / 12.92 : std::pow((c + 0.055) / 1.055, 2.4);
}

inline double labF(double t) {
    return t > 0.008856 ? std::cbrt(t) : (903.3 * t + 16.0) / 116.0;
}

inline Lab argbToLab(uint32_t argb) {
    const double r = srgbLinear(redOf(argb) / 255.0);
    const double g = srgbLinear(greenOf(argb) / 255.0);
    const double b = srgbLinear(blueOf(argb) / 255.0);
    const double x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047;
    const double y = (r * 0.2126729 + g * 0.7151522 + b * 0.0721750);
    const double b_ = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883;
    const double fx = labF(x);
    const double fy = labF(y);
    const double fz = labF(b_);
    Lab out;
    out.l = 116.0 * fy - 16.0;
    out.a = 500.0 * (fx - fy);
    out.b = 200.0 * (fy - fz);
    return out;
}

inline uint32_t labToArgb(const Lab& lab) {
    const double fy = (lab.l + 16.0) / 116.0;
    const double fx = fy + lab.a / 500.0;
    const double fz = fy - lab.b / 200.0;
    auto fInv = [](double t) -> double {
        const double t3 = t * t * t;
        return t3 > 0.008856 ? t3 : (116.0 * t - 16.0) / 903.3;
    };
    const double x = fInv(fx) * 0.95047;
    const double y = fInv(fy);
    const double z = fInv(fz) * 1.08883;
    auto delin = [](double c) -> double {
        return c <= 0.0031308 ? c * 12.92 : 1.055 * std::pow(c, 1.0 / 2.4) - 0.055;
    };
    const double r = delin(x * 3.2404542 + y * -1.5371385 + z * -0.4985314);
    const double g = delin(x * -0.9692660 + y * 1.8760108 + z * 0.0415560);
    const double b = delin(x * 0.0556434 + y * -0.2040259 + z * 1.0572252);
    auto ch = [](double c) -> int {
        const int v = static_cast<int>(c * 255.0 + 0.5);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    };
    return packArgb(0xFF, ch(r), ch(g), ch(b));
}

// Deterministic xorshift RNG for the fixed-seed k-means path (seed 0x504958).
class XorShift64 {
public:
    explicit XorShift64(uint64_t seed) : state_(seed ? seed : 0x9E3779B97F4A7C15ULL) {}
    uint64_t next() {
        uint64_t x = state_;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        state_ = x;
        return x;
    }
    // Uniform double in [0, 1).
    double nextDouble() {
        return static_cast<double>(next() >> 11) * (1.0 / 9007199254740992.0);
    }

private:
    uint64_t state_;
};

} // namespace pixel_lab

#endif // PIXEL_LAB_COLOR_UTILS_H
