package com.refayatul.fityah.services.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.refayatul.fityah.R
import com.refayatul.fityah.data.models.VpnConfig
import com.refayatul.fityah.ui.activity.FragmentActivity
import com.refayatul.fityah.ui.fragments.main.reducers.vpn.VpnSettingsFragment
import com.refayatul.fityah.utils.BlocklistManager
import com.refayatul.fityah.utils.DataStoreManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class DnsVpnService : VpnService() {
    private var vpnInterfaceFd: Int = -1
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateLock = Mutex()
    private var vpnJob: Job? = null
    private lateinit var dataStoreManager: DataStoreManager
    
    private var vpnController: uniffi.fityah_rust.VpnController? = null
    private var dnsProxy: UdpDnsProxy? = null
    private var dnsHandler: DnsPacketHandler? = null

    private val dnsCallback = object : uniffi.fityah_rust.DnsCallback {
        override fun onDnsPacket(packet: ByteArray): ByteArray? {
            val byteBuffer = ByteBuffer.wrap(packet)
            byteBuffer.limit(packet.size)
            
            val handler = dnsHandler ?: return null
            
            val response = runBlocking { handler.handlePacket(byteBuffer) }
            return response?.array()
        }
    }

    private val socketProtector = object : uniffi.fityah_rust.SocketProtector {
        override fun protectSocket(fd: Int): Boolean {
            return this@DnsVpnService.protect(fd)
        }
    }

    private val connectivityManager by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            restartVpn()
        }
        override fun onLost(network: Network) {
        }
        
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, caps)
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                Log.i(TAG, "Captive portal detected.")
            }
        }
    }

    companion object {
        private const val TAG = "DnsVpnService"
        const val ACTION_START = "com.refayatul.fityah.vpn.START"
        const val ACTION_STOP = "com.refayatul.fityah.vpn.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vpn_service"
    }

    override fun onCreate() {
        super.onCreate()
        dataStoreManager = DataStoreManager(this)
        createNotificationChannel()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (prepare(this) != null) {
                    notifyConflict("VPN Permission missing or another VPN is active.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, createNotification())
                startVpn()
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.w(TAG, "VPN Revoked")
        notifyConflict("Another VPN is active or permission was revoked.")
        stopVpn()
    }

    private fun notifyConflict(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_warning_24)
            .setContentTitle("DNS Filter Disabled")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, FragmentActivity::class.java).apply {
                        putExtra("fragment", VpnSettingsFragment.FRAGMENT_ID)
                    },
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(2001, notification)
    }

    private fun startVpn() {
        vpnJob = serviceScope.launch {
            stateLock.withLock {
                if (vpnController != null) return@withLock
                
                try {
                    // Ensure native library is loaded before any UniFFI calls
                    try {
                        System.loadLibrary("fityah_rust")
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Failed to load native library", e)
                    }

                    val config = dataStoreManager.settings.first().vpnConfig
                    if (!config.isEnabled) {
                        stopVpnInternal()
                        return@withLock
                    }

                    // Initialize persistent proxy and handler
                    val proxy = UdpDnsProxy(this@DnsVpnService)
                    dnsProxy = proxy
                    dnsHandler = DnsPacketHandler(applicationContext, proxy, config)
                    
                    val fd = establishVpn()
                    if (fd == -1) return@withLock
                    vpnInterfaceFd = fd
                    
                    uniffi.fityah_rust.rustInitLogger()
                    
                    val controller = uniffi.fityah_rust.VpnController()
                    controller.start(fd, dnsCallback, socketProtector)
                    vpnController = controller
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Fityah DNS Filter Active", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "VPN Error", e)
                    if (isActive) {
                        delay(5000L)
                        restartVpn()
                    }
                }
            }
        }
    }

    fun protectSocket(socket: java.net.DatagramSocket) {
        protect(socket)
    }

    private fun restartVpn() {
        serviceScope.launch {
            stateLock.withLock {
                stopVpnInternal()
            }
            startVpn()
        }
    }

    private fun establishVpn(): Int {
        val builder = Builder()
        val vpnConfig = runBlocking { dataStoreManager.settings.first().vpnConfig }

        builder.addAddress("10.0.0.2", 32)
        builder.addAddress("fd00::2", 128)
        builder.addRoute("0.0.0.0", 0)
        builder.addRoute("::", 0)

        builder.addDnsServer("192.0.2.1")
        builder.addDnsServer("2001:db8::1")
        
        for (pkg in vpnConfig.exemptPackages) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exempt $pkg", e)
            }
        }
        
        builder.setSession("Fityah DNS Filter")
        builder.setBlocking(true)
        builder.allowFamily(android.system.OsConstants.AF_INET)
        builder.allowFamily(android.system.OsConstants.AF_INET6)
        builder.allowBypass()
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, FragmentActivity::class.java).apply {
                putExtra("fragment", VpnSettingsFragment.FRAGMENT_ID)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        builder.setConfigureIntent(pendingIntent)

        val pfd = builder.establish()
        // DETACH is the key to fix fdsan crash. 
        // Java will no longer own the FD, Rust will take full responsibility.
        return pfd?.detachFd() ?: -1
    }

    private fun stopVpn() {
        serviceScope.launch {
            stateLock.withLock {
                stopVpnInternal()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopVpnInternal() {
        vpnController?.stop()
        vpnController = null
        vpnJob?.cancel()
        vpnJob = null

        dnsProxy?.close()
        dnsProxy = null
        dnsHandler = null

        // We don't close vpnInterfaceFd here because Rust run_vpn_loop will close it 
        // when its File object is dropped after stop_signal is received.
        vpnInterfaceFd = -1
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DNS Filter Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle("DNS Filter Active")
            .setContentText("Protecting your device from distractions")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        stopVpn()
        serviceScope.cancel()
    }
}
