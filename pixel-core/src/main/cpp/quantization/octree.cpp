// Adaptive octree color quantization (depth 8). Reduction repeatedly folds
// the leaf with the smallest (count, nodeIdx) into its parent — including
// single-leaf chains, which is what makes the algorithm correct for inputs
// whose colors branch early and run as lonely chains at deep levels. The
// parent becomes a leaf once its last child detaches.
#include "quantization.h"

#include <map>
#include <memory>
#include <set>
#include <vector>

namespace pixel_lab {

namespace {

struct OctreeNode {
    uint64_t count = 0;        // total pixels passing through this node
    uint64_t sumR = 0, sumG = 0, sumB = 0;
    bool isLeaf = false;
    OctreeNode* children[8] = {nullptr, nullptr, nullptr, nullptr,
                               nullptr, nullptr, nullptr, nullptr};
    OctreeNode* parent = nullptr;
    uint32_t nodeIdx = 0;      // allocation order, used for deterministic ties

    explicit OctreeNode(uint32_t idx) : nodeIdx(idx) {}

    int liveChildren() const {
        int n = 0;
        for (int i = 0; i < 8; ++i) {
            if (children[i] != nullptr) {
                ++n;
            }
        }
        return n;
    }
};

inline int childIndex(uint32_t argb, int depth) {
    // Channel bits at the given level, most significant first, combined as
    // (R bit, G bit, B bit) -> index 0..7.
    const int shift = 7 - depth;
    const int r = (redOf(argb) >> shift) & 1;
    const int g = (greenOf(argb) >> shift) & 1;
    const int b = (blueOf(argb) >> shift) & 1;
    return (r << 2) | (g << 1) | b;
}

void collectLeaves(const OctreeNode* node, std::vector<const OctreeNode*>& leaves) {
    if (node == nullptr || node->isLeaf) {
        if (node != nullptr && node->isLeaf) {
            leaves.push_back(node);
        }
        return;
    }
    for (int i = 0; i < 8; ++i) {
        collectLeaves(node->children[i], leaves);
    }
}

} // namespace

void octreeQuantize(const uint32_t* pixels, size_t count, int targetColors,
                    QuantizeOutput& out) {
    out.palette.clear();
    out.mapped.assign(pixels, pixels + count);

    // Node arena with stable addresses (heap nodes, vector only owns).
    std::vector<std::unique_ptr<OctreeNode>> arena;
    uint32_t nextIdx = 0;
    auto newNode = [&arena, &nextIdx](OctreeNode* parent) {
        arena.push_back(std::unique_ptr<OctreeNode>(new OctreeNode(nextIdx)));
        OctreeNode* node = arena.back().get();
        node->nodeIdx = nextIdx++;
        node->parent = parent;
        return node;
    };

    OctreeNode* root = newNode(nullptr);

    // Insert every opaque pixel; leaves live at depth 8.
    for (size_t i = 0; i < count; ++i) {
        if (isTransparent(pixels[i])) {
            continue;
        }
        OctreeNode* node = root;
        for (int depth = 0; depth < 8; ++depth) {
            node->count += 1;
            node->sumR += static_cast<uint64_t>(redOf(pixels[i]));
            node->sumG += static_cast<uint64_t>(greenOf(pixels[i]));
            node->sumB += static_cast<uint64_t>(blueOf(pixels[i]));
            const int idx = childIndex(pixels[i], depth);
            if (node->children[idx] == nullptr) {
                node->children[idx] = newNode(node);
            }
            node = node->children[idx];
        }
        node->count += 1;
        node->sumR += static_cast<uint64_t>(redOf(pixels[i]));
        node->sumG += static_cast<uint64_t>(greenOf(pixels[i]));
        node->sumB += static_cast<uint64_t>(blueOf(pixels[i]));
        node->isLeaf = true;
    }

    if (root->count == 0 || targetColors <= 0) {
        return;
    }

    // Leaf index: (count, nodeIdx) ordered set for smallest-first folding,
    // plus nodeIdx -> node lookup.
    std::set<std::pair<uint64_t, uint32_t>> leafKeys;
    std::map<uint32_t, OctreeNode*> leafByIdx;
    std::vector<const OctreeNode*> leaves;
    collectLeaves(root, leaves);
    for (const OctreeNode* leaf : leaves) {
        leafKeys.insert({leaf->count, leaf->nodeIdx});
        leafByIdx[leaf->nodeIdx] = const_cast<OctreeNode*>(leaf);
    }

    // Reduction: fold the smallest leaf into its parent until the leaf count
    // fits the target. A parent becomes a leaf when its last child detaches.
    while (leafKeys.size() > static_cast<size_t>(targetColors)) {
        auto smallest = leafKeys.begin();
        OctreeNode* leaf = leafByIdx[smallest->second];
        if (leaf == nullptr || leaf->parent == nullptr) {
            break;  // only the root remains
        }
        OctreeNode* parent = leaf->parent;
        for (int i = 0; i < 8; ++i) {
            if (parent->children[i] == leaf) {
                parent->children[i] = nullptr;
            }
        }
        leafKeys.erase(smallest);
        leafByIdx.erase(leaf->nodeIdx);
        leaf->isLeaf = false;
        if (parent->liveChildren() == 0 && !parent->isLeaf) {
            parent->isLeaf = true;
            leafKeys.insert({parent->count, parent->nodeIdx});
            leafByIdx[parent->nodeIdx] = parent;
        }
    }

    // Final palette from surviving leaves, collected in DFS child order
    // 0..7 (deterministic order).
    leaves.clear();
    collectLeaves(root, leaves);
    for (const OctreeNode* leaf : leaves) {
        if (leaf->count == 0) {
            continue;
        }
        const int r = static_cast<int>((leaf->sumR + leaf->count / 2) / leaf->count);
        const int g = static_cast<int>((leaf->sumG + leaf->count / 2) / leaf->count);
        const int b = static_cast<int>((leaf->sumB + leaf->count / 2) / leaf->count);
        out.palette.push_back(packArgb(0xFF, r, g, b));
    }

    // Map pixels: exact palette match, else walk to the deepest live leaf and
    // use the nearest palette entry to that node's average color.
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
            out.mapped[i] = (out.palette[static_cast<size_t>(hit->second)] & 0x00FFFFFFu) | (pixels[i] & 0xFF000000u);
            continue;
        }
        const OctreeNode* node = root;
        const OctreeNode* deepest = root;
        for (int depth = 0; depth < 8; ++depth) {
            const int idx = childIndex(pixels[i], depth);
            const OctreeNode* child = node->children[idx];
            if (child == nullptr) {
                break;
            }
            node = child;
            deepest = node;
            if (node->isLeaf) {
                break;
            }
        }
        const uint64_t c = deepest->count != 0 ? deepest->count : 1;
        const uint32_t avg = packArgb(0xFF,
                                      static_cast<int>((deepest->sumR + c / 2) / c),
                                      static_cast<int>((deepest->sumG + c / 2) / c),
                                      static_cast<int>((deepest->sumB + c / 2) / c));
        int best = 0;
        int64_t bestDist = INT64_MAX;
        for (size_t p = 0; p < out.palette.size(); ++p) {
            const int64_t d = rgbDistanceSq(avg, out.palette[p]);
            if (d < bestDist) {
                bestDist = d;
                best = static_cast<int>(p);
            }
        }
        out.mapped[i] = (out.palette[static_cast<size_t>(best)] & 0x00FFFFFFu) | (pixels[i] & 0xFF000000u);
    }
}

} // namespace pixel_lab
