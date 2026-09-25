// GIF LZW compressor: variable-width codes 9..12 bits, LSB-first packing,
// CLEAR(256) reset when the dictionary reaches 4096, code emission timing
// identical to the Kotlin reference (register the new code, then widen only
// when nextCode exceeds 1 << codeSize).
#ifndef PIXEL_LAB_LZW_H
#define PIXEL_LAB_LZW_H

#include <cstdint>
#include <cstddef>
#include <vector>

namespace pixel_lab {

// Compresses 8-bit symbol data into GIF LZW sub-block layout (excluding the
// final 0x00 block terminator). minCodeSize is 8 for GIF true-color paths.
void lzwEncode(const uint8_t* data, size_t length, int minCodeSize,
               std::vector<uint8_t>& out);

} // namespace pixel_lab

#endif // PIXEL_LAB_LZW_H
