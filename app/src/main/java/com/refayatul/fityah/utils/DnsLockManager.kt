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
            val targetHost = config.dnsServers.find { 
                it.type == com.refayatul.fityah.data.models.DnsType.DOH || 
                it.type == com.refayatul.fityah.data.models.DnsType.DOH3 
            }?.address?.substringAfter("https://")?.substringBefore("/") ?: "dns.quad9.net"

            // Task 5 Fix: Validate DoT support (Port 853) before locking
            if (isDoTAvailable(targetHost)) {
                val command = "settings put global private_dns_mode hostname; " +
                             "settings put global private_dns_specifier $targetHost"
                
                ShizukuRunner.executeCommand(command, object : ShizukuRunner.CommandResultListener {
                    override fun onCommandError(error: String) {
                        Log.e("DnsLock", "Failed to lock Private DNS: $error")
                        notifyFailure(targetHost)
                    }
                })
            } else {
                Log.e("DnsLock", "Host $targetHost does not support DoT. Lock aborted to prevent break.")
                notifyFailure(targetHost)
            }
        }
    }

    private fun isDoTAvailable(hostname: String): Boolean {
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(hostname, 853), 3000) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun notifyFailure(hostname: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val notification = androidx.core.app.NotificationCompat.Builder(context, "vpn_service")
            .setSmallIcon(R.drawable.baseline_warning_24)
            .setContentTitle("DNS Lock Failed")
            .setContentText("$hostname does not support Private DNS (DoT).")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(2002, notification)
    }
}
