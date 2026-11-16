// SPDX-License-Identifier: MPL-2.0
#include <soc/host1x/video/skyline_memory.h>
#include "nvdec.h"

namespace skyline::soc::host1x {
    NvDecClass::NvDecClass(std::function<void()> callback, const DeviceState &state)
        : state(state), opDoneCallback(std::move(callback)) {}

    void NvDecClass::CallMethod(u32 method, u32 argument) {
        try {
            engine.Method(method, argument, video::GuestMemory(state));
        } catch (const std::exception &error) {
            // A picture that can't be decoded is dropped but the job still completes, as real hardware would signal its syncpoint regardless
            // Failing the whole channel here would stop every later video frame of the game
            Logger::Error("NVDEC execution failed (method 0x{:X}), the picture is dropped: {}", method, error.what());
        }
    }
}
