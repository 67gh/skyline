// SPDX-License-Identifier: MPL-2.0
// CopyPlane/WriteRow (runs of 16 bytes) must give exactly the same bytes as a per-byte copy through Plane::Offset, for every layout and width.
// g++ -std=c++20 -O2 -I app/src/main/cpp/skyline/soc/host1x/video tools/video_tests/plane_copy_test.cpp -o /tmp/plane_copy_test
#include "memory.h"
#include <iostream>
#include <random>
using namespace skyline::soc::host1x::video;

int main() {
    std::mt19937 rng{1234};
    unsigned cases{};
    for (unsigned width : {1U, 15U, 16U, 17U, 31U, 33U, 64U, 100U, 1000U, 1280U})
        for (unsigned height : {1U, 2U, 7U, 9U, 24U, 100U, 720U})
            for (bool srcLinear : {false, true}) for (bool dstLinear : {false, true})
                for (unsigned blockHeight : {0U, 1U, 2U, 4U}) {
                    const Plane src(width, height, srcLinear, blockHeight), dst(width, height, dstLinear, blockHeight);
                    Bytes in(src.Size()), expected(dst.Size(), 0xAA), actual(dst.Size(), 0xAA);
                    for (auto &b : in) b = static_cast<std::uint8_t>(rng());
                    for (unsigned y = 0; y < height; ++y) for (unsigned x = 0; x < width; ++x)
                        expected[dst.Offset(x, y)] = in[src.Offset(x, y)];
                    CopyPlane(dst, actual.data(), src, in.data(), width, height);
                    if (actual != expected) { std::cout << "FAIL CopyPlane " << width << "x" << height << " srcLinear=" << srcLinear << " dstLinear=" << dstLinear << " bh=" << blockHeight << "\n"; return 1; }

                    // WriteRow: a linear buffer written into the destination layout
                    Bytes rows(static_cast<std::size_t>(width) * height), exp2(dst.Size(), 0x55), act2(dst.Size(), 0x55);
                    for (auto &b : rows) b = static_cast<std::uint8_t>(rng());
                    for (unsigned y = 0; y < height; ++y) for (unsigned x = 0; x < width; ++x)
                        exp2[dst.Offset(x, y)] = rows[static_cast<std::size_t>(y) * width + x];
                    for (unsigned y = 0; y < height; ++y)
                        WriteRow(dst, act2.data(), y, rows.data() + static_cast<std::size_t>(y) * width, width);
                    if (act2 != exp2) { std::cout << "FAIL WriteRow " << width << "x" << height << "\n"; return 1; }
                    cases++;
                }
    std::cout << "PASS: CopyPlane and WriteRow match the per-byte copy in " << cases << " layout/size combinations\n";
}
