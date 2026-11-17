// SPDX-License-Identifier: MPL-2.0
#pragma once

#include <chrono>
#include <common.h>

namespace skyline::soc::host1x::video {
    /**
     * @brief Counts the jobs run by a video engine and logs a summary at most once per second, to show in a log if frames are decoded and how long they take
     */
    class EngineStats {
      private:
        const char *name;
        u64 executed{};
        u64 failed{};
        u64 totalMicros{};
        u64 maxMicros{};
        std::chrono::steady_clock::time_point lastLog{std::chrono::steady_clock::now()};
        u64 executedAtLastLog{};

      public:
        explicit EngineStats(const char *name) : name(name) {}

        void Record(bool success, std::chrono::steady_clock::time_point start) {
            const auto now{std::chrono::steady_clock::now()};
            const u64 micros{static_cast<u64>(std::chrono::duration_cast<std::chrono::microseconds>(now - start).count())};
            executed++;
            if (!success)
                failed++;
            totalMicros += micros;
            maxMicros = std::max(maxMicros, micros);

            if (executed == 1)
                Logger::Info("{}: first job executed ({}), took {} us", name, success ? "ok" : "failed", micros);

            if (now - lastLog >= std::chrono::seconds(1)) {
                Logger::Info("{}: {} jobs in total ({} failed), {} in the last second, average {} us, longest {} us", name, executed, failed, executed - executedAtLastLog, totalMicros / executed, maxMicros);
                lastLog = now;
                executedAtLastLog = executed;
            }
        }
    };
}
