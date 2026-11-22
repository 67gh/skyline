/*
 * SPDX-License-Identifier: MPL-2.0
 */

package emu.skyline.utils

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Samples system-level performance counters shown in the emulation performance overlay.
 * Every getter returns `null` when the value can't be read on this device, so the overlay can show "N/A" instead of a wrong number.
 */
class PerformanceMonitor {
    private val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)
    private val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private var lastCpuTimeMs = Process.getElapsedCpuTime()
    private var lastWallTimeMs = SystemClock.elapsedRealtime()
    private var lastCpuPercent : Float? = null

    private var gpuSource : GpuSource? = null
    private var gpuProbed = false

    /**
     * @return The resident memory of the emulator process in bytes (this includes the memory mapped for the game)
     */
    fun appRamBytes() : Long? = runCatching {
        File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * pageSize
    }.getOrNull()

    /**
     * @return The memory the system reports as available for new allocations without swapping, in bytes
     */
    fun availableRamBytes() : Long? = runCatching {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }?.split(Regex("\\s+"))?.get(1)?.toLong()?.times(1024)
        }
    }.getOrNull()

    /**
     * @return The CPU time used by this process since the previous call as a percentage of the total capacity of all cores (0-100), `null` until two samples exist
     * @note Calls closer than 500ms to the previous sample return the previous value so a short window doesn't produce noisy numbers
     */
    fun cpuPercent() : Float? {
        val nowWall = SystemClock.elapsedRealtime()
        val wallDelta = nowWall - lastWallTimeMs
        if (wallDelta < 500)
            return lastCpuPercent

        val nowCpu = Process.getElapsedCpuTime()
        val percent = ((nowCpu - lastCpuTimeMs).toFloat() / (wallDelta.toFloat() * coreCount) * 100f).coerceIn(0f, 100f)
        lastCpuTimeMs = nowCpu
        lastWallTimeMs = nowWall
        lastCpuPercent = percent
        return percent
    }

    /**
     * @return The GPU busy percentage (0-100) from the driver's sysfs counters, `null` when this device doesn't expose any readable one (it is blocked on many Android versions without root)
     */
    fun gpuPercent() : Float? {
        if (!gpuProbed) {
            gpuProbed = true
            gpuSource = GpuSource.candidates().firstOrNull { it.read() != null }
        }
        return gpuSource?.read()
    }

    private class GpuSource(private val file : File, private val kind : Kind) {
        enum class Kind { Percentage, BusyTotal }

        fun read() : Float? = runCatching {
            val text = file.readText().trim()
            when (kind) {
                Kind.Percentage -> text.replace("%", "").trim().split(Regex("\\s+"))[0].toFloat()
                Kind.BusyTotal -> text.split(Regex("\\s+")).let { parts ->
                    val busy = parts[0].toFloat()
                    val total = parts[1].toFloat()
                    if (total > 0f) busy / total * 100f else 0f
                }
            }.coerceIn(0f, 100f)
        }.getOrNull()

        companion object {
            fun candidates() = listOf(
                GpuSource(File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"), Kind.Percentage), // Qualcomm Adreno
                GpuSource(File("/sys/class/kgsl/kgsl-3d0/gpubusy"), Kind.BusyTotal), // Qualcomm Adreno (older kernels)
                GpuSource(File("/sys/kernel/gpu/gpu_busy"), Kind.Percentage), // Samsung Mali / Exynos
                GpuSource(File("/sys/devices/platform/13000000.mali/utilization"), Kind.Percentage), // MediaTek Mali
                GpuSource(File("/sys/class/misc/mali0/device/utilisation"), Kind.Percentage), // Mali
            )
        }
    }
}
