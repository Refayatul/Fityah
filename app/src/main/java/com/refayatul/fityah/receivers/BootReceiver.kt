package com.refayatul.fityah.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.refayatul.fityah.services.ServiceWatchdogJob
import com.refayatul.fityah.utils.ServiceProtectionManager

/**
 * After a reboot or an app update the scheduled watchdog job and the live services are gone. This
 * re schedules the watchdog and runs one immediate repair pass so protection comes back without the
 * user opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            ServiceWatchdogJob.schedule(context)
            ServiceProtectionManager.healNow(context)
            ServiceProtectionManager.restartVpnIfEnabled(context)
        } catch (e: Exception) {
            Log.e("BootReceiver", "boot repair failed", e)
        }
    }
}
