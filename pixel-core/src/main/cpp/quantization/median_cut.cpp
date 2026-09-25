// Median-cut color quantization. Deterministic: histogram order is by color
// value, box selection ties break on weight then start index, split channel
// ties break R > G > B, and the weighted median is the lower bucket boundary.
#include "quantization.h"

#include <algorithm>
#include <cstring>

namespace pixel_lab {

namespace {

struct ColorCount {
    uint32_t color;
    int64_t count;
};

struct Box {
    size_t begin;
    size_t end;   // half-open
    int64_t weight;
    int ranges[3];
};

int channelOf(uint32_t c, int ch) {
    switch (ch) {
        case 0: return redOf(c);
        case 1: return greenOf(c);
        default: return blueOf(c);
    }
}

int computeRange(const std::vector<ColorCount>& hist, size_t begin, size_t end, int ch) {
    int lo = 255;
    int hi = 0;
    for (size_t i = begin; i < end; ++i) {
        const int v = channelOf(hist[i].color, ch);
        if (v < lo) lo = v;
        if (v > hi) hi = v;
    }
    return hi - lo;
}

} // namespace

void medianCut(const uint32_t* pixels, size_t count, int targetColors,
               QuantizeOutput& out) {
    out.palette.clear();
    out.mapped.assign(pixels, pixels + count);

    // 24-bit RGB histogram keyed by color value (deterministic order).
    std::map<uint32_t, int64_t> histogram;
    size_t opaqueCount = 0;
    for (size_t i = 0; i < count; ++i) {
        if (!isTransparent(pixels[i])) {
            ++histogram[pixels[i] & 0xFFFFFF];
            ++opaqueCount;
        }
    }
    if (opaqueCount == 0 || targetColors <= 0) {
        return;
    }

    std::vector<ColorCount> hist;
    hist.reserve(histogram.size());
    for (const auto& kv : histogram) {
        // Mark opaque by restoring full alpha.
        hist.push_back({kv.first | 0xFF000000u, kv.second});
    }
    histogram.clear();

    // Fewer distinct colors than the target: emit them directly, ordered by
    // descending frequency with ascending color as the tiebreak.
    if (hist.size() <= static_cast<size_t>(targetColors)) {
        std::vector<ColorCount> sorted(hist);
        std::sort(sorted.begin(), sorted.end(), [](const ColorCount& a, const ColorCount& b) {
            if (a.count != b.count) return a.count > b.count;
            return a.color < b.color;
        });
        for (const auto& cc : sorted) {
            out.palette.push_back(packArgb(0xFF, redOf(cc.color), greenOf(cc.color), blueOf(cc.color)));
        }
    } else {
        // Box queue, always splitting the box with the largest channel range;
        // ties prefer heavier boxes, then earlier starts.
        std::vector<Box> queue;
        Box whole{0, hist.size(), 0, {0, 0, 0}};
        for (size_t i = 0; i < hist.size(); ++i) {
            whole.weight += hist[i].count;
        }
        for (int ch = 0; ch < 3; ++ch) {
            whole.ranges[ch] = computeRange(hist, 0, hist.size(), ch);
        }
        queue.push_back(whole);

        while (queue.size() < static_cast<size_t>(targetColors)) {
            // Find the box with the largest range (any channel).
            size_t best = SIZE_MAX;
            int bestRange = -1;
            for (size_t i = 0; i < queue.size(); ++i) {
                const int r = std::max({queue[i].ranges[0], queue[i].ranges[1], queue[i].ranges[2]});
                if (r > bestRange) {
                    bestRange = r;
                    best = i;
                } else if (r == bestRange && best != SIZE_MAX) {
                    if (queue[i].weight > queue[best].weight ||
                        (queue[i].weight == queue[best].weight && queue[i].begin < queue[best].begin)) {
                        best = i;
                    }
                }
            }
            if (best == SIZE_MAX || bestRange <= 0) {
                break;
            }
            Box box = queue[best];
            queue.erase(queue.begin() + static_cast<long>(best));
            if (box.end - box.begin < 2) {
                queue.push_back(box);
                break;
            }

            // Split channel: largest range, ties break R > G > B.
            int splitCh = 0;
            if (box.ranges[1] > box.ranges[0]) splitCh = 1;
            if (box.ranges[2] > box.ranges[splitCh]) splitCh = 2;

            // Sort the box slice by the split channel ascending (std::sort is
            // not stable, so compare (channel, color) for full determinism).
            std::sort(hist.begin() + static_cast<long>(box.begin),
                      hist.begin() + static_cast<long>(box.end),
                      [splitCh](const ColorCount& a, const ColorCount& b) {
                          const int ca = channelOf(a.color, splitCh);
                          const int cb = channelOf(b.color, splitCh);
                          if (ca != cb) return ca < cb;
                          return a.color < b.color;
                      });

            // Weighted median: smallest index with cumulative*2 >= total,
            // clamped to keep both buckets non-empty.
            int64_t cum = 0;
            size_t mid = box.begin;
            for (size_t i = box.begin; i < box.end; ++i) {
                cum += hist[i].count;
                if (cum * 2 >= box.weight) {
                    mid = i;
                    break;
                }
            }
            if (mid < box.begin + 1) mid = box.begin + 1;
            if (mid > box.end - 1) mid = box.end - 1;

            Box left{box.begin, mid, 0, {0, 0, 0}};
            Box right{mid, box.end, 0, {0, 0, 0}};
            for (size_t i = left.begin; i < left.end; ++i) left.weight += hist[i].count;
            for (size_t i = right.begin; i < right.end; ++i) right.weight += hist[i].count;
            for (int ch = 0; ch < 3; ++ch) {
                left.ranges[ch] = computeRange(hist, left.begin, left.end, ch);
                right.ranges[ch] = computeRange(hist, right.begin, right.end, ch);
            }
            queue.push_back(left);
            queue.push_back(right);
        }

        // Box color = rounded channel means; output ordered by box start.
        std::sort(queue.begin(), queue.end(), [](const Box& a, const Box& b) {
            return a.begin < b.begin;
        });
        for (const Box& box : queue) {
            int64_t sumR = 0, sumG = 0, sumB = 0, w = 0;
            for (size_t i = box.begin; i < box.end; ++i) {
                const int64_t c = hist[i].count;
                sumR += static_cast<int64_t>(redOf(hist[i].color)) * c;
                sumG += static_cast<int64_t>(greenOf(hist[i].color)) * c;
                sumB += static_cast<int64_t>(blueOf(hist[i].color)) * c;
                w += c;
            }
            if (w == 0) {
                out.palette.push_back(packArgb(0xFF, 0, 0, 0));
                continue;
            }
            auto mean = [w](int64_t sum) -> int {
                // Arithmetic rounding of sum / w: (sum + w/2) / w.
                return static_cast<int>((sum + w / 2) / w);
            };
            out.palette.push_back(packArgb(0xFF, mean(sumR), mean(sumG), mean(sumB)));
        }
    }

    // Map every opaque pixel to the nearest palette entry by squared RGB
    // distance (ties to the lowest index); transparent pixels pass through.
    std::map<uint32_t, int> exact;
    for (size_t i = 0; i < out.palette.size(); ++i) {
        exact[out.palette[i] & 0xFFFFFF] = static_cast<int>(i);
    }
    for (size_t i = 0; i < count; ++i) {
        if (isTransparent(pixels[i])) {
            out.mapped[i] = pixels[i];
            continue;
        }
        auto hit = exact.find(pixels[i] & 0xFFFFFF);
        if (hit != exact.end()) {
            out.mapped[i] = out.palette[static_cast<size_t>(hit->second)];
            continue;
        }
        int best = 0;
        int64_t bestDist = INT64_MAX;
        for (size_t p = 0; p < out.palette.size(); ++p) {
            const int64_t d = rgbDistanceSq(pixels[i], out.palette[p]);
            if (d < bestDist) {
                bestDist = d;
                best = static_cast<int>(p);
            }
        }
        out.mapped[i] = out.palette[static_cast<size_t>(best)];
    }
}

} // namespace pixel_lab
