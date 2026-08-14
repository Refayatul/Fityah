package com.refayatul.fityah.services.vpn

import android.content.Context
import android.util.Log
import com.refayatul.fityah.data.models.DnsServer
import com.refayatul.fityah.data.models.DnsType
import com.refayatul.fityah.utils.DnsCache
import okhttp3.OkHttpClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.chromium.net.CronetException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class UdpDnsProxy(private val service: DnsVpnService) {
    private var udpSocket: DatagramSocket? = null
    
    private val cronetEngine: CronetEngine by lazy {
        CronetEngine.Builder(service)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
            .build()
    }
    
    private val cronetExecutor: Executor = Executors.newFixedThreadPool(4)
    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun getSocket(): DatagramSocket {
        val s = udpSocket
        if (s != null && !s.isClosed) return s
        
        val newSocket = DatagramSocket().apply {
            // Task: Increase receive timeout for slow custom DNS (e.g. NextDNS from BD)
            service.protect(this) 
            soTimeout = 8000 
        }
        udpSocket = newSocket
        return newSocket
    }

    suspend fun resolve(query: ByteArray, servers: List<DnsServer>): ByteArray? = withContext(Dispatchers.IO) {
        val socket = getSocket()
        
        // 1. Check local cache
        DnsCache.get(query)?.let { 
            Log.d("DnsProxy", "Cache hit for query")
            return@withContext it 
        }

        // 2. Try each server in order
        for (server in servers) {
            // Task: Safety check - if ALL servers are somehow disabled in config, 
            // force them enabled for this session to prevent total blackout.
            val effectiveEnabled = if (servers.none { it.isEnabled }) true else server.isEnabled
            
            if (!effectiveEnabled) {
                Log.d("DnsProxy", "Server ${server.address} is disabled, skipping")
                continue
            }
            
            Log.d("DnsProxy", "Trying ${server.type} server: ${server.address}")
            val result = try {
                when (server.type) {
                    DnsType.DOH, DnsType.DOH3 -> {
                        // Task: Resolve hostname with bootstrap if needed
                        val resolvedUrl = resolveHostnameInUrl(server.address)
                        resolveSecure(query, resolvedUrl)
                    }
                    DnsType.PLAIN -> resolvePlain(query, server.address, socket)
                }
            } catch (e: Exception) {
                Log.e("DnsProxy", "Resolution error via ${server.address}: ${e.message}")
                null
            }
            
            if (result != null) {
                Log.d("DnsProxy", "Success via ${server.address}")
                DnsCache.put(query, result)
                return@withContext result
            }
        }
        Log.w("DnsProxy", "All DNS servers failed")
        null
    }

    private fun resolvePlain(query: ByteArray, upstream: String, socket: DatagramSocket): ByteArray? {
        return try {
            // Task: Bootstrap hostname to IP if needed for plain DNS
            val targetIp = resolveHostnameInUrl(upstream)
            Log.d("DnsProxy", "Plain resolution using IP: $targetIp (original: $upstream)")
            val address = InetAddress.getByName(targetIp)
            val packet = DatagramPacket(query, query.size, address, 53)
            socket.send(packet)

            val buffer = ByteArray(4096) // Larger buffer
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)
            
            Log.d("DnsProxy", "Plain resolution succeeded for $upstream")
            responsePacket.data.copyOfRange(0, responsePacket.length)
        } catch (e: Exception) {
            Log.e("DnsProxy", "Plain resolution failed for $upstream: ${e.message}")
            null
        }
    }

    private fun resolveHostnameInUrl(url: String): String {
        val hostname = if (url.startsWith("https://")) {
            url.substringAfter("https://").substringBefore("/")
        } else {
            url
        }
        
        if (hostname.matches(Regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$"))) return url
        
        val ip = when {
            hostname == "dns.quad9.net" -> "9.9.9.9"
            hostname == "cloudflare-dns.com" || hostname == "1.1.1.1.cloudflare-dns.com" -> "1.1.1.1"
            hostname == "dns.google" -> "8.8.8.8"
            hostname == "dns.adguard-dns.com" -> "94.140.14.14"
            hostname.endsWith(".nextdns.io") -> "45.90.28.0"
            else -> {
                // Task: Resolve via Google DNS bypass to break deadlock
                try {
                    Log.d("DnsProxy", "Bootstrapping unknown hostname: $hostname")
                    val resolver = InetAddress.getAllByName(hostname)
                    resolver.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                } catch (e: Exception) {
                    Log.e("DnsProxy", "Bootstrap failed for $hostname: ${e.message}")
                    null
                }
            }
        }
        
        return if (ip != null) {
            if (url.startsWith("https://")) url.replace(hostname, ip) else ip
        } else {
            url
        }
    }

    fun close() {
        try {
            udpSocket?.close()
            udpSocket = null
        } catch (e: Exception) {}
    }

    private suspend fun resolveSecure(query: ByteArray, url: String): ByteArray? = suspendCoroutine { continuation ->
        val callback = object : CronetHelper() {
            private val responseBuffer = ByteBuffer.allocateDirect(4096)
            private var totalData = ByteArray(0)

            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                request.read(responseBuffer)
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                responseBuffer.flip()
                val bytes = ByteArray(responseBuffer.remaining())
                responseBuffer.get(bytes)
                totalData += bytes
                responseBuffer.clear()
                request.read(responseBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                continuation.resume(totalData)
            }

            override fun onFailedSafe(request: UrlRequest?, info: UrlResponseInfo?, error: CronetException?) {
                Log.e("DnsProxy", "Secure resolution failed: ${error?.message}")
                continuation.resume(null)
            }

            override fun onCanceledSafe(request: UrlRequest?, info: UrlResponseInfo?) {
                continuation.resume(null)
            }
        }

        val request = cronetEngine.newUrlRequestBuilder(url, callback, cronetExecutor)
            .setHttpMethod("POST")
            .addHeader("Content-Type", "application/dns-message")
            .addHeader("Accept", "application/dns-message")
            // Task: Set high priority for DNS
            .setPriority(UrlRequest.Builder.REQUEST_PRIORITY_HIGHEST)
            .addHeader("User-Agent", "Fityah/1.0 (Privacy-First DNS)") 
            .setUploadDataProvider(org.chromium.net.UploadDataProviders.create(query), cronetExecutor)
            .build()
        
        request.start()
    }
}
