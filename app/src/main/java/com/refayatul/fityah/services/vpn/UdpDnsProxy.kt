package com.refayatul.fityah.services.vpn

import android.content.Context
import com.refayatul.fityah.data.models.DnsServer
import com.refayatul.fityah.data.models.DnsType
import com.refayatul.fityah.utils.DnsCache
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    private val udpSocket = DatagramSocket().apply {
        service.protectSocket(this)
        soTimeout = 3000
    }
    
    private val cronetEngine: CronetEngine by lazy {
        CronetEngine.Builder(service)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)
            // Privacy Hardening: Disable metrics and cache to avoid tracking
            .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISABLED, 0)
            .build()
    }
    
    private val cronetExecutor: Executor = Executors.newSingleThreadExecutor()

    suspend fun resolve(query: ByteArray, servers: List<DnsServer>): ByteArray? = withContext(Dispatchers.IO) {
        // 1. Check local cache first
        DnsCache.get(query)?.let { return@withContext it }

        // 2. Try each server in order (failover)
        for (server in servers) {
            if (!server.isEnabled) continue
            
            val result = try {
                when (server.type) {
                    DnsType.DOH, DnsType.DOH3 -> resolveSecure(query, server.address)
                    DnsType.PLAIN -> resolvePlain(query, server.address)
                }
            } catch (e: Exception) {
                null
            }
            
            if (result != null) {
                DnsCache.put(query, result)
                return@withContext result
            }
        }
        null
    }

    private fun resolvePlain(query: ByteArray, upstream: String): ByteArray? {
        return try {
            val address = InetAddress.getByName(upstream)
            val packet = DatagramPacket(query, query.size, address, 53)
            udpSocket.send(packet)

            val buffer = ByteArray(1024)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            udpSocket.receive(responsePacket)
            
            responsePacket.data.copyOfRange(0, responsePacket.length)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveSecure(query: ByteArray, url: String): ByteArray? = suspendCoroutine { continuation ->
        val callback = object : UrlRequest.Callback() {
            private val responseBuffer = ByteBuffer.allocateDirect(4096)
            private var totalData = ByteArray(0)

            override fun onRedirectReceived(request: UrlRequest?, info: UrlResponseInfo?, newLocationUrl: String?) {
                request?.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest?, info: UrlResponseInfo?) {
                request?.read(responseBuffer)
            }

            override fun onReadCompleted(request: UrlRequest?, info: UrlResponseInfo?, byteBuffer: ByteBuffer?) {
                responseBuffer.flip()
                val bytes = ByteArray(responseBuffer.remaining())
                responseBuffer.get(bytes)
                totalData += bytes
                responseBuffer.clear()
                request?.read(responseBuffer)
            }

            override fun onSucceeded(request: UrlRequest?, info: UrlResponseInfo?) {
                continuation.resume(totalData)
            }

            override fun onFailed(request: UrlRequest?, info: UrlResponseInfo?, error: CronetException?) {
                continuation.resume(null)
            }

            override fun onCanceled(request: UrlRequest?, info: UrlResponseInfo?) {
                continuation.resume(null)
            }
        }

        val request = cronetEngine.newUrlRequestBuilder(url, callback, cronetExecutor)
            .setHttpMethod("POST")
            .addHeader("Content-Type", "application/dns-message")
            .addHeader("Accept", "application/dns-message")
            .addHeader("User-Agent", "Fityah/1.0 (Privacy-First DNS)") // Hardened User-Agent
            .setUploadDataProvider(org.chromium.net.UploadDataProviders.create(query), cronetExecutor)
            .build()
        
        request.start()
    }
}
