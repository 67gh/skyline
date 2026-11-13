// SPDX-License-Identifier: MPL-2.0
package emu.skyline.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateSession.receive(context, intent)
    }
}
