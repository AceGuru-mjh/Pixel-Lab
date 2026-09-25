// Lab-space k-means quantization with a fixed seed (0x504958), k-means++
#include <algorithm>
// initialization, at most 16 iterations, convergence when every center moves
// less than 0.1, and empty-cluster discarding. Deterministic by construction.
#include "quantization.h"

#include <cmath>
#include <vector>

namespace pixel_lab {

namespace {

constexpr uint64_t KMEANS_SEED = 0x504958;
constexpr int MAX_ITERATIONS = 16;
constexpr double CONVERGENCE_EPS = 0.1;

} // namespace

void kmeans(const uint32_t* pixels, size_t count, int targetColors,
            QuantizeOutput& out) {
    out.palette.clear();
    out.mapped.assign(pixels, pixels + count);

    // Collect opaque pixels (deduplicated with counts so distance sums stay
    // proportional to the original image).
    std::vector<uint32_t> colors;
    std::vector<int64_t> weights;
    {
        std::map<uint32_t, int64_t> hist;
        for (size_t i = 0; i < count; ++i) {
            if (!isTransparent(pixels[i])) {
                ++hist[pixels[i] & 0xFFFFFF];
            }
        }
        for (const auto& kv : hist) {
            colors.push_back(kv.first | 0xFF000000u);
            weights.push_back(kv.second);
        }
    }
    if (colors.empty() || targetColors <= 0) {
        return;
    }
    const size_t k = std::min<size_t>(colors.size(), static_cast<size_t>(targetColors));

    // Lab coordinates per distinct color.
    const size_t n = colors.size();
    std::vector<Lab> labs(n);
    for (size_t i = 0; i < n; ++i) {
        labs[i] = argbToLab(colors[i]);
    }

    XorShift64 rng(KMEANS_SEED);
    std::vector<Lab> centers(k);
    // k-means++: first center uniform; the rest sampled with D^2 weighting.
    centers[0] = labs[rng.next() % n];
    std::vector<double> minDist(n, 1e300);
    for (size_t c = 1; c < k; ++c) {
        double total = 0.0;
        for (size_t i = 0; i < n; ++i) {
            const double dl = labs[i].l - centers[c - 1].l;
            const double da = labs[i].a - centers[c - 1].a;
            const double db = labs[i].b - centers[c - 1].b;
            const double d = dl * dl + da * da + db * db;
            if (d < minDist[i]) {
                minDist[i] = d;
            }
            total += minDist[i] * static_cast<double>(weights[i]);
        }
        if (total <= 0.0) {
            // All remaining mass sits on existing centers: fill with the
            // first color to keep positions defined.
            centers[c] = labs[0];
            continue;
        }
        double pick = rng.nextDouble() * total;
        size_t chosen = 0;
        double lastPositive = 0.0;
        for (size_t i = 0; i < n; ++i) {
            const double w = minDist[i] * static_cast<double>(weights[i]);
            pick -= w;
            if (w > 0.0) {
                lastPositive = w;
            }
            if (pick <= 0.0 && w > 0.0) {
                chosen = i;
                break;
            }
        }
        centers[c] = labs[chosen];
        (void) lastPositive;
    }

    // Lloyd iterations.
    std::vector<size_t> assignment(n, 0);
    for (int iter = 0; iter < MAX_ITERATIONS; ++iter) {
        bool changed = false;
        for (size_t i = 0; i < n; ++i) {
            size_t best = 0;
            double bestDist = 1e300;
            for (size_t c = 0; c < k; ++c) {
                const double dl = labs[i].l - centers[c].l;
                const double da = labs[i].a - centers[c].a;
                const double db = labs[i].b - centers[c].b;
                const double d = dl * dl + da * da + db * db;
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
            if (assignment[i] != best) {
                assignment[i] = best;
                changed = true;
            }
        }
        // Recompute centers as weighted Lab means.
        std::vector<double> sumL(k, 0.0), sumA(k, 0.0), sumB(k, 0.0), sumW(k, 0.0);
        for (size_t i = 0; i < n; ++i) {
            const size_t c = assignment[i];
            const double w = static_cast<double>(weights[i]);
            sumL[c] += labs[i].l * w;
            sumA[c] += labs[i].a * w;
            sumB[c] += labs[i].b * w;
            sumW[c] += w;
        }
        double maxShift = 0.0;
        for (size_t c = 0; c < k; ++c) {
            if (sumW[c] > 0.0) {
                const Lab next{sumL[c] / sumW[c], sumA[c] / sumW[c], sumB[c] / sumW[c]};
                maxShift = std::max(maxShift, std::fabs(next.l - centers[c].l) +
                                     std::fabs(next.a - centers[c].a) +
                                     std::fabs(next.b - centers[c].b));
                centers[c] = next;
            }
            // Empty clusters keep their previous position.
        }
        if (!changed && maxShift < CONVERGENCE_EPS) {
            break;
        }
    }

    // Palette from surviving centers (order by first assignment appearance).
    std::vector<size_t> firstSeen(k, SIZE_MAX);
    for (size_t i = 0; i < n; ++i) {
        const size_t c = assignment[i];
        if (firstSeen[c] == SIZE_MAX) {
            firstSeen[c] = i;
        }
    }
    std::vector<size_t> order;
    for (size_t c = 0; c < k; ++c) {
        if (firstSeen[c] != SIZE_MAX) {
            order.push_back(c);
        }
    }
    std::sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return firstSeen[a] < firstSeen[b];
    });

    std::vector<int> remap(k, -1);
    for (size_t idx = 0; idx < order.size(); ++idx) {
        remap[order[idx]] = static_cast<int>(idx);
        out.palette.push_back(labToArgb(centers[order[idx]]));
    }

    // Exact-match table for speed, then nearest-center mapping.
    std::map<uint32_t, int> exact;
    for (size_t i = 0; i < out.palette.size(); ++i) {
        exact[out.palette[i] & 0xFFFFFF] = static_cast<int>(i);
    }
    for (size_t i = 0; i < count; ++i) {
        if (isTransparent(pixels[i])) {
            out.mapped[i] = pixels[i];
            continue;
        }
        // Find the distinct-color index of this pixel via the histogram key.
        // Colors were collected in ascending map order, so a binary search on
        // the sorted colors vector is exact.
        auto it = std::lower_bound(colors.begin(), colors.end(), pixels[i] & 0xFFFFFF,
                                   [](uint32_t a, uint32_t key) {
                                       return (a & 0xFFFFFF) < key;
                                   });
        if (it != colors.end() && (*it & 0xFFFFFF) == (pixels[i] & 0xFFFFFF)) {
            const size_t colorIdx = static_cast<size_t>(it - colors.begin());
            out.mapped[i] = out.palette[static_cast<size_t>(remap[assignment[colorIdx]])];
            continue;
        }
        auto hit = exact.find(pixels[i] & 0xFFFFFF);
        if (hit != exact.end()) {
            out.mapped[i] = out.palette[static_cast<size_t>(hit->second)];
        }
    }
}

} // namespace pixel_lab
