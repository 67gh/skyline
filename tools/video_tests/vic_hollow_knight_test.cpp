// SPDX-License-Identifier: MPL-2.0
// Replays the VIC job that Hollow Knight's intro video submits (captured configuration, NV12 1280x720 -> NV12 1280x720).
// Before the fix every frame was rejected with "VIC: blend factors/overrides unsupported", which left the cutscene black.
// g++ -std=c++20 -O2 -I app/src/main/cpp/skyline/soc/host1x/video tools/video_tests/vic_hollow_knight_test.cpp app/src/main/cpp/skyline/soc/host1x/video/compositor.cpp -o /tmp/vic_hk_test
#include "compositor.h"
#include <cassert>
#include <chrono>
#include <iostream>
using namespace skyline::soc::host1x::video;

static const std::uint8_t HollowKnightConfig[] = {
#include "vic_hollow_knight_config.inc"
};

int main() {
    constexpr std::size_t Width{1280}, Height{720};
    constexpr std::uint64_t ConfigAddress{0x1000}, OutY{0x200000}, OutUV{0x400000}, SrcY{0x600000}, SrcUV{0x800000};
    // Source: block-linear NV12 as written by NVDEC (2 GOBs per block); output: pitch-linear NV12 as read by the GPU
    const Plane srcY(Width, Height, false, 1), srcUV(Width, Height / 2, false, 1);
    const Plane outYPlane(Width, Height, true, 0), outUVPlane(Width, Height / 2, true, 0);
    Bytes ram(0x1000000, 0xe7);
    std::copy(std::begin(HollowKnightConfig), std::end(HollowKnightConfig), ram.begin() + ConfigAddress);
    for (unsigned y = 0; y < Height; ++y) for (unsigned x = 0; x < Width; ++x)
        ram[SrcY + srcY.Offset(x, y)] = static_cast<std::uint8_t>(x * 7 + y * 13);
    for (unsigned y = 0; y < Height / 2; ++y) for (unsigned x = 0; x < Width; ++x)
        ram[SrcUV + srcUV.Offset(x, y)] = static_cast<std::uint8_t>(x * 3 + y * 5 + 1);

    Memory m{
        [&](auto a, auto b) { std::copy_n(ram.begin() + static_cast<std::ptrdiff_t>(a), b.size(), b.begin()); },
        [&](auto a, auto b) { std::copy(b.begin(), b.end(), ram.begin() + static_cast<std::ptrdiff_t>(a)); },
        [&](auto a, auto n) { if (a > ram.size() || n > ram.size() - a) throw std::runtime_error("range"); }};

    Compositor vic;
    vic.Method(0x1c2, ConfigAddress >> 8, m);
    vic.Method(0x1c8, OutY >> 8, m);
    vic.Method(0x1c9, OutUV >> 8, m);
    vic.Method(0x100, SrcY >> 8, m);  // slot 0 luma
    vic.Method(0x101, SrcUV >> 8, m); // slot 0 chroma

    const auto start = std::chrono::steady_clock::now();
    vic.Method(0xc0, 1, m);
    const auto micros = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count();

    for (unsigned y = 0; y < Height; ++y) for (unsigned x = 0; x < Width; ++x)
        if (ram[OutY + outYPlane.Offset(x, y)] != static_cast<std::uint8_t>(x * 7 + y * 13)) { std::cout << "FAIL luma at x=" << x << " y=" << y << "\n"; return 1; }
    for (unsigned y = 0; y < Height / 2; ++y) for (unsigned x = 0; x < Width; ++x)
        if (ram[OutUV + outUVPlane.Offset(x, y)] != static_cast<std::uint8_t>(x * 3 + y * 5 + 1)) { std::cout << "FAIL chroma at x=" << x << " y=" << y << "\n"; return 1; }
    std::cout << "PASS: the Hollow Knight VIC job (block-linear NV12 -> pitch-linear NV12, 1280x720) is copied exactly in " << micros << " us (host, -O2)\n";
}
