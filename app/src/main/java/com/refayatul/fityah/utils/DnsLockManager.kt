package com.refayatul.fityah.utils

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.refayatul.fityah.R
import com.refayatul.fityah.data.models.VpnConfig
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DnsLockManager(private val context: Context) {

    fun applyLock(config: VpnConfig) {
        if (!config.lockPrivateDns) return
        
        if (!PermissionUtils.hasShizukuPermission()) {
            Log.w("DnsLock", "Cannot lock Private DNS: Shizuku permission missing")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Task: Setting Private DNS to "OFF" and CLEARING specifier
            val command = "settings put global private_dns_mode off; " +
                         "settings put global private_dns_specifier ''"
            
            ShizukuRunner.executeCommand(command, object : ShizukuRunner.CommandResultListener {
                override fun onCommandError(error: String) {
                    Log.e("DnsLock", "Failed to set Private DNS to OFF: $error")
                }
            })
        }
    }
}
