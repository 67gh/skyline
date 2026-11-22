// SPDX-License-Identifier: MPL-2.0

#pragma once

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <fstream>
#include <mutex>
#include <thread>
#include <pthread.h>
#include <common.h>
#include <kernel/types/KProcess.h>

extern std::atomic<skyline::u64> PresentedFrames;

namespace skyline {
    /**
     * @brief Diagnostic thread that reports why a game seems frozen (black screen, 0 FPS) from a single run
     * @details Every 5s it checks whether any frame was presented. If none was for 10s (then every 30s) it logs the memory use of the app and the scheduling state
     * of every guest thread, which shows whether the game is deadlocked (all threads waiting), spinning, or whether the system is running out of memory.
     * It only reads state and never touches the emulation, it is destroyed (and joined) when the emulation ends.
     */
    class StallWatchdog {
      private:
        static constexpr std::chrono::seconds Period{5};
        static constexpr size_t FirstReportPeriods{2}; //!< Periods without any frame before the first report
        static constexpr size_t RepeatReportPeriods{6}; //!< Periods between reports once a stall has been reported

        const DeviceState &state;
        std::mutex mutex;
        std::condition_variable condition;
        bool stop{};
        std::thread thread;

        /**
         * @return The value in kB of the given key of /proc/<file> (e.g. "MemAvailable"), 0 if it can't be read
         */
        static u64 ReadKiloBytes(const char *path, const std::string &key) {
            std::ifstream file{path};
            std::string line;
            while (std::getline(file, line))
                if (line.rfind(key, 0) == 0)
                    return std::strtoull(line.c_str() + key.size(), nullptr, 10);
            return 0;
        }

        void Report(u64 frames, size_t stalledSeconds) {
            Logger::Info("Watchdog: no frame presented for {}s ({} frames in total), app RSS {} MB, system available {} MB", stalledSeconds, frames, ReadKiloBytes("/proc/self/status", "VmRSS:") / 1024, ReadKiloBytes("/proc/meminfo", "MemAvailable:") / 1024);
            if (state.process)
                state.process->LogThreadStates();
            Logger::EmulationContext.Flush();
        }

        void Run() {
            pthread_setname_np(pthread_self(), "Sky-Watchdog");

            u64 lastFrames{PresentedFrames.load(std::memory_order_relaxed)};
            size_t stalledPeriods{};
            bool reported{};

            std::unique_lock lock{mutex};
            while (!condition.wait_for(lock, Period, [this] { return stop; })) {
                u64 frames{PresentedFrames.load(std::memory_order_relaxed)};
                if (frames != lastFrames) {
                    if (reported)
                        Logger::Info("Watchdog: frames are being presented again ({} in total)", frames);
                    lastFrames = frames;
                    stalledPeriods = 0;
                    reported = false;
                    continue;
                }

                stalledPeriods++;
                if (stalledPeriods == FirstReportPeriods || (stalledPeriods > FirstReportPeriods && (stalledPeriods - FirstReportPeriods) % RepeatReportPeriods == 0)) {
                    reported = true;
                    Report(frames, stalledPeriods * static_cast<size_t>(Period.count()));
                }
            }
        }

      public:
        StallWatchdog(const DeviceState &state) : state{state}, thread{&StallWatchdog::Run, this} {}

        ~StallWatchdog() {
            {
                std::scoped_lock lock{mutex};
                stop = true;
            }
            condition.notify_all();
            thread.join();
        }
    };
}
