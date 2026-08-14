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
    private var vpnInterface: ParcelFileDescriptor? = null
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

    private var lastRestartTime = 0L

    private val connectivityManager by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            Log.d(TAG, "Network available: $network, caps: $caps")
            
            // Guard 1: Ignore VPN networks (prevents self-restart loop)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                Log.d(TAG, "Ignoring VPN network available event")
                return
            }
            
            // Guard 2: Throttling restarts
            val now = System.currentTimeMillis()
            if (now - lastRestartTime < 8000) {
                Log.d(TAG, "Ignoring rapid network change (throttled)")
                return
            }
            
            if (vpnController != null) {
                Log.i(TAG, "Real external network available, restarting VPN to bind to it")
                restartVpn()
            }
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
        val forceEnabled = intent?.getBooleanExtra("EXTRA_ENABLED", false) ?: false
        
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "onStartCommand: ACTION_START (forceEnabled=$forceEnabled)")
                if (prepare(this) != null) {
                    Log.w(TAG, "onStartCommand: prepare(this) != null, notifying conflict")
                    notifyConflict("VPN Permission missing or another VPN is active.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                
                startForeground(NOTIFICATION_ID, createNotification())
                startVpn(forceEnabled)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN Revoked by system")
        serviceScope.launch {
            val current = dataStoreManager.settings.first().vpnConfig
            dataStoreManager.updateVpnConfig(current.copy(isEnabled = false))
        }
        notifyConflict("VPN permission was revoked by the system or another app.")
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

    private fun startVpn(forceEnabled: Boolean = false) {
        vpnJob = serviceScope.launch {
            stateLock.withLock {
                if (vpnController != null) {
                    Log.d(TAG, "startVpn: vpnController already exists, skipping")
                    return@withLock
                }
                
                try {
                    Log.d(TAG, "startVpn: Starting core... (forceEnabled=$forceEnabled)")
                    // Ensure native library is loaded
                    try {
                        System.loadLibrary("fityah_rust")
                    } catch (e: UnsatisfiedLinkError) {
                        Log.e(TAG, "Failed to load native library", e)
                    }

                    val config = dataStoreManager.settings.first().vpnConfig
                    if (!config.isEnabled && !forceEnabled) {
                        Log.d(TAG, "startVpn: config.isEnabled is false AND not forced, stopping")
                        stopVpnInternal()
                        return@withLock
                    }

                    // Initialize persistent proxy and handler
                    val proxy = UdpDnsProxy(this@DnsVpnService)
                    dnsProxy = proxy
                    dnsHandler = DnsPacketHandler(applicationContext, proxy, config)
                    
                    val pfd = establishVpn()
                    if (pfd == null) {
                        Log.e(TAG, "startVpn: establishVpn returned null")
                        return@withLock
                    }
                    vpnInterface = pfd
                    
                    uniffi.fityah_rust.rustInitLogger()
                    
                    val controller = uniffi.fityah_rust.VpnController()
                    // Task: Pass raw FD but Java keeps ownership of PFD object
                    controller.start(pfd.fd, dnsCallback, socketProtector)
                    vpnController = controller
                    
                    // Task: Force Private DNS OFF via Shizuku if enabled in settings
                    if (config.lockPrivateDns) {
                        com.refayatul.fityah.utils.DnsLockManager(applicationContext).applyLock(config)
                    }

                    Log.i(TAG, "startVpn: VPN Controller started successfully")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Fityah DNS Filter Active", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "VPN Error in startVpn", e)
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
        lastRestartTime = System.currentTimeMillis()
        serviceScope.launch {
            stateLock.withLock {
                stopVpnInternal()
            }
            startVpn()
        }
    }

    private fun establishVpn(): ParcelFileDescriptor? {
        val builder = Builder()
        val vpnConfig = runBlocking { dataStoreManager.settings.first().vpnConfig }
        
        // Task: Ensure the current app package (including .debug) is ALWAYS disallowed
        val exempt = vpnConfig.exemptPackages.toMutableSet()
        exempt.add(packageName)

        // Using 10.1.10.x to avoid common home network conflicts
        builder.addAddress("10.1.10.2", 24)
        builder.addRoute("0.0.0.0", 0)

        builder.addDnsServer("192.0.2.1")
        
        for (pkg in exempt) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to exempt $pkg", e)
            }
        }
        
        builder.setMtu(1500)
        builder.setSession("Fityah DNS Filter")
        builder.setBlocking(true)
        builder.allowFamily(android.system.OsConstants.AF_INET)
        builder.allowBypass()
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, FragmentActivity::class.java).apply {
                putExtra("fragment", VpnSettingsFragment.FRAGMENT_ID)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        builder.setConfigureIntent(pendingIntent)

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
            null
        }
        
        if (pfd != null) {
            updateUnderlyingNetworks()
        }
        return pfd
    }

    private fun updateUnderlyingNetworks() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val activeNetwork = connectivityManager.activeNetwork
                setUnderlyingNetworks(if (activeNetwork != null) arrayOf(activeNetwork) else null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set underlying networks", e)
            }
        }
    }

    private fun stopVpn() {
        Log.i(TAG, "stopVpn: Requesting shutdown")
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

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface", e)
        }
        vpnInterface = null
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
        val stopIntent = Intent(this, DnsVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle("DNS Filter Active")
            .setContentText("Protecting your device from distractions")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(R.drawable.baseline_close_24, "Stop", stopPendingIntent)
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
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
        
        // Task: Use runBlocking for cleanup to ensure it finishes before service is destroyed
        runBlocking {
            stateLock.withLock {
                stopVpnInternal()
            }
        }
        serviceScope.cancel()
    }
}
