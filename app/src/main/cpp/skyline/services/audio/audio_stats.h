// SPDX-License-Identifier: MPL-2.0
#pragma once

#include <algorithm>
#include <chrono>
#include <mutex>
#include <optional>
#include <common.h>

namespace skyline::service::audio {
    /**
     * @brief Counts calls (and an amount such as buffers or microseconds) and hands back a summary roughly once per second so it can be written to the log
     * @note It shows whether the game feeds the audio system regularly: a game that starves its audio output makes it crackle
     */
    class RateStats {
      public:
        struct Summary {
            u64 calls;
            u64 amount;
            u64 totalMicros;
            u64 maxMicros;
            double seconds;
        };

      private:
        std::mutex mutex;
        u64 calls{};
        u64 amount{};
        u64 totalMicros{};
        u64 maxMicros{};
        std::chrono::steady_clock::time_point last{std::chrono::steady_clock::now()};

      public:
        std::optional<Summary> Record(u64 recordedAmount, u64 micros) {
            std::scoped_lock lock{mutex};
            calls++;
            amount += recordedAmount;
            totalMicros += micros;
            maxMicros = std::max(maxMicros, micros);

            const auto now{std::chrono::steady_clock::now()};
            const double seconds{std::chrono::duration<double>(now - last).count()};
            if (seconds < 1.0)
                return std::nullopt;

            Summary summary{calls, amount, totalMicros, maxMicros, seconds};
            calls = amount = totalMicros = maxMicros = 0;
            last = now;
            return summary;
        }
    };
}
