/*
 * SPDX-License-Identifier: MPL-2.0
 */

package emu.skyline.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import emu.skyline.getPublicFilesDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes why the previous processes of this app ended (crash, low memory kill, ANR...) into `exit_reasons.log` next to the emulation logs.
 * A game that "just closes" without anything in the emulation log was usually killed by the system, which the log can't show but Android records.
 */
object ExitReasonLogger {
    private const val MaxEntries = 8

    private fun reasonName(reason : Int) = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF (the app closed itself)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed by a signal)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (killed by the system to free memory)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native crash)"
        ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN ($reason)"
    }

    /**
     * Records the most recent exit reasons, does nothing before Android 11 (the API doesn't exist there)
     */
    fun record(context : Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            return

        Thread {
            runCatching {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val text = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MaxEntries).joinToString("\n") { info ->
                    "${format.format(Date(info.timestamp))} | process ${info.processName} | ${reasonName(info.reason)} | status ${info.status} | importance ${info.importance} | rss ${info.rss / 1024} MB | ${info.description ?: ""}"
                }
                File(context.getPublicFilesDir(), "exit_reasons.log").writeText(text + "\n")
            }
        }.start()
    }
}
