// SPDX-License-Identifier: MPL-2.0
// Alpha-fill modes seen in the wild: mode 4 (composited) with an NV12 output made the whole VIC channel fail ("alpha fill mode unsupported").
// g++ -std=c++20 -I app/src/main/cpp/skyline/soc/host1x/video tools/video_tests/vic_alpha_mode_test.cpp app/src/main/cpp/skyline/soc/host1x/video/compositor.cpp -o /tmp/vic_alpha_test
#include "compositor.h"
#include <cassert>
#include <iostream>
using namespace skyline::soc::host1x::video;

static void Field(Bytes &b, unsigned off, unsigned bit, unsigned count, unsigned v) {
    for (unsigned i = 0; i < count; ++i) {
        auto &x = b.at(off + (bit + i) / 8);
        auto m = 1U << ((bit + i) % 8);
        x = static_cast<std::uint8_t>((x & ~m) | (((v >> i) & 1U) ? m : 0U));
    }
}

int main() {
    Bytes ram(1 << 20);
    Memory m{
        [&](auto a, auto b) { std::copy_n(ram.begin() + static_cast<std::ptrdiff_t>(a), b.size(), b.begin()); },
        [&](auto a, auto b) { std::copy(b.begin(), b.end(), ram.begin() + static_cast<std::ptrdiff_t>(a)); },
        [&](auto a, auto n) { if (a > ram.size() || n > ram.size() - a) throw std::runtime_error("range"); }};

    auto run = [&](unsigned dstFormat, unsigned alphaMode) {
        std::fill(ram.begin(), ram.end(), 0xe7);
        Bytes c(0x610);
        const bool yuv = dstFormat == 67 || dstFormat == 68;
        Field(c, 0x20, 0, 7, dstFormat);
        Field(c, 0x20, 11, 4, 0); // pitch linear
        Field(c, 0x20, 32, 14, 1); Field(c, 0x20, 46, 14, 1); // 2x2
        Field(c, 0x20, 64, 14, 1); Field(c, 0x20, 78, 14, 1);
        Field(c, 0x20, 96, 14, 0); Field(c, 0x20, 110, 14, 0);
        Field(c, 0x10, 0, 3, alphaMode);
        Field(c, 0x10, 6, 10, 73 * 4);      // background alpha
        Field(c, 0x10, 16, 10, 100 * 4); Field(c, 0x10, 26, 10, 80 * 4); Field(c, 0x10, 36, 10, 180 * 4);
        Field(c, 0x10, 64, 14, 0); Field(c, 0x10, 80, 14, 1); Field(c, 0x10, 96, 14, 0); Field(c, 0x10, 112, 14, 1);
        std::copy(c.begin(), c.end(), ram.begin() + 0x1000);
        Compositor vic;
        vic.Method(0x1c2, 0x10, m); vic.Method(0x1c8, 0x200, m);
        if (yuv) vic.Method(0x1c9, 0x300, m);
        vic.Method(0xc0, 1, m);
    };

    // 1) A YUV output has no alpha channel: every mode must work, including mode 4 and the unused mode 7
    for (unsigned fmt : {67U, 68U}) for (unsigned mode = 0; mode <= 7; ++mode) {
        run(fmt, mode);
        Plane y(2, 2, true, 0);
        for (unsigned py = 0; py < 2; ++py) for (unsigned px = 0; px < 2; ++px)
            assert(ram[0x20000 + y.Offset(px, py)] == 100);
    }

    // 2) RGB output (pitch linear, format 31): modes 3 to 5 take the composited alpha, which is the background alpha without a source slot
    Plane rgb(8, 2, true, 0);
    for (unsigned mode : {3U, 4U, 5U}) {
        run(31, mode);
        for (unsigned py = 0; py < 2; ++py) for (unsigned px = 0; px < 2; ++px)
            assert(ram[0x20000 + rgb.Offset(px * 4 + 3, py)] == 73);
    }
    run(31, 0); assert(ram[0x20000 + rgb.Offset(3, 0)] == 255);
    run(31, 1); assert(ram[0x20000 + rgb.Offset(3, 0)] == 73);
    run(31, 2); assert(ram[0x20000 + rgb.Offset(3, 0)] == 0xe7); // destination keeps the previous alpha byte

    // 3) Modes beyond 5 are still rejected for RGB outputs
    bool rejected = false;
    try { run(31, 6); } catch (const std::exception &) { rejected = true; }
    assert(rejected);

    std::cout << "PASS VIC alpha-fill modes: YUV outputs accept every mode, RGB outputs support modes 0-5 and reject 6-7\n";
}
