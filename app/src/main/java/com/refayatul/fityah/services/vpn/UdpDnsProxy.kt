package com.refayatul.fityah.services.vpn

import android.content.Context
import android.net.Uri
import android.util.Log
import com.refayatul.fityah.data.models.DnsServer
import com.refayatul.fityah.data.models.DnsType
import com.refayatul.fityah.utils.DnsCache
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class UdpDnsProxy(private val service: DnsVpnService) {
    private var udpSocket: DatagramSocket? = null
    
    // Custom SocketFactory to ensure OkHttp traffic bypasses the VPN
    private val protectedSocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = Socket().also { service.protect(it) }
        override fun createSocket(host: String?, port: Int): Socket = Socket(host, port).also { service.protect(it) }
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = Socket(host, port, localHost, localPort).also { service.protect(it) }
        override fun createSocket(host: InetAddress?, port: Int): Socket = Socket(host, port).also { service.protect(it) }
        override fun createSocket(address: InetAddress?, port: Int, localAddr: InetAddress?, localPort: Int): Socket = Socket(address, port, localAddr, localPort).also { service.protect(it) }
    }

    // Custom Dns resolver for OkHttp to bootstrap known DoH providers without loops
    private val bootstrapperDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // NextDNS uses subdomains for profiles sometimes. Match *.dns.nextdns.io
            if (hostname == "dns.nextdns.io" || hostname.endsWith(".dns.nextdns.io")) {
                return listOf(
                    InetAddress.getByName("45.90.28.231"),
                    InetAddress.getByName("45.90.30.231")
                )
            }

            val hardcoded = when (hostname) {
                "dns.quad9.net" -> "9.9.9.9"
                "cloudflare-dns.com" -> "1.1.1.1"
                "dns.google" -> "8.8.8.8"
                "dns.adguard-dns.com", "family.adguard-dns.com" -> "94.140.14.14"
                else -> null
            }
            return if (hardcoded != null) {
                listOf(InetAddress.getByName(hardcoded))
            } else {
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .socketFactory(protectedSocketFactory)
        .dns(bootstrapperDns)
        .build()

    private fun getSocket(): DatagramSocket {
        val s = udpSocket
        if (s != null && !s.isClosed) return s
        
        val newSocket = DatagramSocket().apply {
            service.protect(this) 
            soTimeout = 4000 
        }
        udpSocket = newSocket
        return newSocket
    }

    suspend fun resolve(query: ByteArray, servers: List<DnsServer>): ByteArray? = withContext(Dispatchers.IO) {
        val socket = getSocket()
        
        // 1. Check local cache
        DnsCache.get(query)?.let { return@withContext it }

        // 2. Try each server in order
        // Use all servers, prioritizing enabled ones
        val sortedServers = servers.sortedByDescending { it.isEnabled }
        
        for (server in sortedServers) {
            val result = try {
                when (server.type) {
                    DnsType.DOH, DnsType.DOH3 -> resolveSecure(query, server.address)
                    DnsType.DOT -> resolveDot(query, server.address)
                    DnsType.PLAIN -> resolvePlain(query, server.address, socket)
                }
            } catch (e: Exception) {
                null
            }
            
            if (result != null) {
                DnsCache.put(query, result)
                return@withContext result
            }
        }

        // 3. Emergency Fallback: Quad9
        try {
            val emergencyResult = resolvePlain(query, "9.9.9.9", socket)
            if (emergencyResult != null) return@withContext emergencyResult
        } catch (e: Exception) {}

        null
    }

    private fun resolvePlain(query: ByteArray, upstream: String, socket: DatagramSocket): ByteArray? {
        return try {
            val address = InetAddress.getByName(upstream)
            val packet = DatagramPacket(query, query.size, address, 53)
            socket.send(packet)

            val buffer = ByteArray(4096)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)
            
            responsePacket.data.copyOfRange(0, responsePacket.length)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveSecure(query: ByteArray, url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .post(query.toRequestBody("application/dns-message".toMediaType()))
                .addHeader("Accept", "application/dns-message")
                .addHeader("User-Agent", "Fityah/1.0")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveDot(query: ByteArray, hostname: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val ips = bootstrapperDns.lookup(hostname)
            if (ips.isEmpty()) return@withContext null
            
            val factory = javax.net.ssl.SSLSocketFactory.getDefault()
            val socket = factory.createSocket() as javax.net.ssl.SSLSocket
            service.protect(socket)
            socket.connect(java.net.InetSocketAddress(ips[0], 853), 3000)
            socket.startHandshake()
            socket.soTimeout = 3000
            
            val out = socket.outputStream
            out.write((query.size shr 8).toByte().toInt())
            out.write((query.size and 0xFF).toByte().toInt())
            out.write(query)
            out.flush()
            
            val input = socket.inputStream
            val b1 = input.read()
            val b2 = input.read()
            if (b1 == -1 || b2 == -1) return@withContext null
            val responseLen = (b1 shl 8) or b2
            
            val response = ByteArray(responseLen)
            var totalRead = 0
            while (totalRead < responseLen) {
                val r = input.read(response, totalRead, responseLen - totalRead)
                if (r == -1) break
                totalRead += r
            }
            
            socket.close()
            if (totalRead == responseLen) response else null
        } catch (e: Exception) {
            Log.e("UdpDnsProxy", "DOT failed for $hostname", e)
            null
        }
    }

    fun close() {
        try {
            udpSocket?.close()
            udpSocket = null
        } catch (e: Exception) {}
    }
}
