#include "lzw.h"

#include <cstring>
#include <unordered_map>

namespace pixel_lab {

namespace {

class BitWriter {
public:
    explicit BitWriter(std::vector<uint8_t>& sink, int initialCodeSize)
        : sink_(sink), codeSize_(initialCodeSize) {
        // Sub-blocks are written flat and split afterwards.
    }

    void write(uint32_t code) {
        bits_ |= (static_cast<uint64_t>(code) << bitCount_);
        bitCount_ += codeSize_;
        while (bitCount_ >= 8) {
            block_.push_back(static_cast<uint8_t>(bits_ & 0xFF));
            bits_ >>= 8;
            bitCount_ -= 8;
            if (block_.size() >= 255) {
                flushBlock();
            }
        }
    }

    // Pads the final partial byte with zeros and closes the open sub-block.
    void finish() {
        if (bitCount_ > 0) {
            block_.push_back(static_cast<uint8_t>(bits_ & 0xFF));
            bits_ = 0;
            bitCount_ = 0;
        }
        if (!block_.empty()) {
            flushBlock();
        }
    }

    void setCodeSize(int size) { codeSize_ = size; }
    int codeSize() const { return codeSize_; }

private:
    void flushBlock() {
        const size_t n = block_.size();
        sink_.push_back(static_cast<uint8_t>(n));
        sink_.insert(sink_.end(), block_.begin(), block_.end());
        block_.clear();
    }

    std::vector<uint8_t>& sink_;
    std::vector<uint8_t> block_;
    uint64_t bits_ = 0;
    int bitCount_ = 0;
    int codeSize_;
};

} // namespace

void lzwEncode(const uint8_t* data, size_t length, int minCodeSize,
               std::vector<uint8_t>& out) {
    const int clearCode = 1 << minCodeSize;         // 256
    const int eoiCode = clearCode + 1;              // 257
    int nextCode = eoiCode + 1;                     // 258
    int codeSize = minCodeSize + 1;                 // 9
    const int maxCode = 4096;

    BitWriter writer(out, codeSize);
    writer.write(static_cast<uint32_t>(clearCode));

    // Dictionary: (prefix << 8) | symbol -> code. Keys fit in uint64.
    std::unordered_map<uint64_t, int> dict;
    dict.reserve(4096);

    if (length == 0) {
        writer.write(static_cast<uint32_t>(eoiCode));
        writer.finish();
        return;
    }

    int prefix = data[0];
    for (size_t i = 1; i < length; ++i) {
        const uint8_t symbol = data[i];
        const uint64_t key = (static_cast<uint64_t>(prefix) << 8) | symbol;
        auto hit = dict.find(key);
        if (hit != dict.end()) {
            prefix = hit->second;
            continue;
        }
        // Emit the prefix, register (prefix, symbol).
        writer.write(static_cast<uint32_t>(prefix));
        dict[key] = nextCode;
        ++nextCode;
        // Widen when the next code to be emitted no longer fits. This timing
        // (compare after registration) matches the Kotlin implementation
        // byte-for-byte.
        if (nextCode > (1 << codeSize) && codeSize < 12) {
            ++codeSize;
            writer.setCodeSize(codeSize);
        }
        if (nextCode >= maxCode) {
            // Dictionary full: emit CLEAR and reset.
            writer.write(static_cast<uint32_t>(clearCode));
            dict.clear();
            nextCode = eoiCode + 1;
            codeSize = minCodeSize + 1;
            writer.setCodeSize(codeSize);
        }
        prefix = symbol;
    }
    writer.write(static_cast<uint32_t>(prefix));
    writer.write(static_cast<uint32_t>(eoiCode));
    writer.finish();
}

} // namespace pixel_lab
