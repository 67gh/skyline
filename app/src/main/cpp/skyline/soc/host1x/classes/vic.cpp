// SPDX-License-Identifier: MPL-2.0
#include <soc/host1x/video/skyline_memory.h>
#include "vic.h"

namespace skyline::soc::host1x {
    VicClass::VicClass(std::function<void()> callback, const DeviceState &state)
        : state(state), opDoneCallback(std::move(callback)) {}

    void VicClass::CallMethod(u32 method, u32 argument) {
        const auto start{std::chrono::steady_clock::now()};
        bool success{true};
        try {
            engine.Method(method, argument, video::GuestMemory(state));
        } catch (const std::exception &error) {
            // Same policy as NVDEC: the frame is dropped, the job still completes
            success = false;
            Logger::Error("VIC execution failed (method 0x{:X}), the frame is dropped: {}", method, error.what());
        }

        if (method == 0xC0)
            stats.Record(success, start);
    }
}
