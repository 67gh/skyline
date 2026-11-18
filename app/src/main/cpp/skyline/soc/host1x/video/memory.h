// SPDX-License-Identifier: MPL-2.0
#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <functional>
#include <span>
#include <stdexcept>
#include <vector>

namespace skyline::soc::host1x::video {
using Bytes = std::vector<std::uint8_t>;
struct Memory {
    std::function<void(std::uint64_t, std::span<std::uint8_t>)> read;
    std::function<void(std::uint64_t, std::span<const std::uint8_t>)> write;
    std::function<void(std::uint64_t, std::size_t)> validate;
    static void Range(std::uint64_t address, std::size_t size) {
        if (!address || address >= (1ULL << 32) || size > (1ULL << 32) - address)
            throw std::runtime_error("Video: invalid SMMU range");
    }
    void Check(std::uint64_t address, std::size_t size) const {
        Range(address, size);
        validate(address, size);
    }
    Bytes Read(std::uint64_t address, std::size_t size) const {
        Check(address, size);
        Bytes result(size);
        read(address, result);
        return result;
    }
    void Write(std::uint64_t address, const Bytes &bytes) const {
        Check(address, bytes.size());
        write(address, bytes);
    }
};
inline std::uint32_t U32(std::span<const std::uint8_t> bytes, std::size_t offset) {
    if (offset > bytes.size() || bytes.size() - offset < 4)
        throw std::runtime_error("Video: truncated structure");
    return std::uint32_t(bytes[offset]) | std::uint32_t(bytes[offset+1]) << 8 |
           std::uint32_t(bytes[offset+2]) << 16 | std::uint32_t(bytes[offset+3]) << 24;
}
inline std::uint64_t U64(std::span<const std::uint8_t> bytes, std::size_t offset) {
    return U32(bytes, offset) | (std::uint64_t(U32(bytes, offset+4)) << 32);
}
inline std::uint32_t Bits(std::span<const std::uint8_t> bytes, unsigned bit, unsigned count) {
    const auto word = U64(bytes, (bit / 64) * 8);
    return static_cast<std::uint32_t>((word >> (bit % 64)) & ((1ULL << count)-1));
}
inline int Signed(std::uint32_t value, unsigned width) {
    return (value & (1U << (width-1))) ? static_cast<int>(value) - static_cast<int>(1U << width) : static_cast<int>(value);
}
// Tegra block-linear GOB: 64 bytes x 8 rows. blockHeight is log2 GOBs/block.
struct Plane {
    unsigned width, height, blockHeight;
    bool linear;
    std::size_t pitch;
    Plane(unsigned w, unsigned h, bool l, unsigned bh, std::size_t p = 0)
        : width(w), height(h), blockHeight(bh), linear(l), pitch(p ? p : ((w + 255U) & ~255U)) {
        if (!w || !h || w > 16384 || h > 4096 || bh > 5 || pitch < w || pitch > 32768)
            throw std::runtime_error("Video: invalid surface geometry");
    }
    std::size_t Size() const {
        if (linear) return pitch * height;
        const std::size_t rows = 8U << blockHeight;
        return ((width + 63U) / 64U) * 64ULL * ((height + rows-1) / rows) * rows;
    }
    std::size_t Offset(unsigned x, unsigned y) const {
        if (linear) return y * pitch + x;
        const std::size_t gobs = 1U << blockHeight;
        return (y/(8*gobs))*((width+63U)/64U)*512*gobs + (x/64)*512*gobs +
               ((y% (8*gobs))/8)*512 + ((x%64)/32)*256 + ((y%8)/2)*64 +
               ((x%32)/16)*32 + (y%2)*16 + x%16;
    }
};

/**
 * Copies a width x height byte rectangle from one plane layout to another. A run of 16 bytes that starts at a multiple of 16 is contiguous in both the
 * pitch-linear and the block-linear layout, so it is copied at once: computing an offset for every byte made this the slowest part of a video frame.
 */
inline void CopyPlane(const Plane &dstPlane, std::uint8_t *dst, const Plane &srcPlane, const std::uint8_t *src, unsigned width, unsigned height) {
    for (unsigned y=0;y<height;++y) {
        unsigned x=0;
        for (;x+16U<=width;x+=16U)
            std::memcpy(dst+dstPlane.Offset(x,y),src+srcPlane.Offset(x,y),16);
        for (;x<width;++x)
            dst[dstPlane.Offset(x,y)]=src[srcPlane.Offset(x,y)];
    }
}

/**
 * Writes one row of `count` bytes taken from a linear buffer to row `y` of a plane, in runs of 16 bytes (see CopyPlane)
 */
inline void WriteRow(const Plane &plane, std::uint8_t *dst, unsigned y, const std::uint8_t *row, unsigned count) {
    unsigned x=0;
    for (;x+16U<=count;x+=16U)
        std::memcpy(dst+plane.Offset(x,y),row+x,16);
    for (;x<count;++x)
        dst[plane.Offset(x,y)]=row[x];
}
}
