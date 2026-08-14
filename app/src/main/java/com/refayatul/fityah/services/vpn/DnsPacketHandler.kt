package com.refayatul.fityah.services.vpn

import android.content.Context
import android.util.Log
import com.refayatul.fityah.data.models.VpnConfig
import com.refayatul.fityah.utils.BlocklistManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

import java.util.Locale

class DnsPacketHandler(private val context: Context, private val proxy: UdpDnsProxy, private val config: VpnConfig) {
    private val blocklistManager = BlocklistManager(context)
    
    suspend fun handlePacket(packet: ByteBuffer): ByteBuffer? = withContext(Dispatchers.IO) {
        val buffer = packet.array()
        val limit = packet.limit()
        
        if (limit < 40) return@withContext null
        
        val version = (buffer[0].toInt() and 0xF0) shr 4
        if (version == 4) {
            handleIpv4(buffer, limit)
        } else if (version == 6) {
            handleIpv6(buffer, limit)
        } else {
            null
        }
    }

    private suspend fun handleIpv4(buffer: ByteArray, limit: Int): ByteBuffer? {
        val ipHeaderLength = (buffer[0].toInt() and 0x0F) * 4
        val protocol = buffer[9].toInt() and 0xFF
        val dstIp = String.format(Locale.US, "%d.%d.%d.%d", 
            buffer[16].toInt() and 0xFF, buffer[17].toInt() and 0xFF, 
            buffer[18].toInt() and 0xFF, buffer[19].toInt() and 0xFF)

        if (protocol == 17) { // UDP
            val udpHeaderStart = ipHeaderLength
            val dstPort = ((buffer[udpHeaderStart + 2].toInt() and 0xFF) shl 8) or (buffer[udpHeaderStart + 3].toInt() and 0xFF)
            
            if (dstPort == 53) {
                val dnsDataStart = udpHeaderStart + 8
                val dnsDataLength = limit - dnsDataStart
                val domain = parseDnsQuery(buffer, dnsDataStart, dnsDataLength) ?: return null
                
                // Task: Detailed logging for debugging
                Log.d("DnsVpn", "Processing UDP DNS query for: $domain to $dstIp")
                
                return processDomain(domain, buffer, limit, ipHeaderLength, udpHeaderStart, 4)
            }
            
            // Task 2: Block DoT over UDP on port 853
            if (dstPort == 853 && BlocklistManager.DOH_IPS.contains(dstIp)) {
                return null 
            }
        } else if (protocol == 6) { // TCP
            val tcpHeaderStart = ipHeaderLength
            val dstPort = ((buffer[tcpHeaderStart + 2].toInt() and 0xFF) shl 8) or (buffer[tcpHeaderStart + 3].toInt() and 0xFF)
            
            // Task 2: Block DoH (443) and DoT (853) to known resolver IPs
            if ((dstPort == 443 || dstPort == 853) && BlocklistManager.DOH_IPS.contains(dstIp)) {
                Log.d("DnsVpn", "Blocking secure DNS bypass attempt to $dstIp:$dstPort")
                return null 
            }
        }
        
        return null
    }

    private suspend fun handleIpv6(buffer: ByteArray, limit: Int): ByteBuffer? {
        if (limit < 48) return null
        val protocol = buffer[6].toInt() and 0xFF
        
        if (protocol == 17) { // UDP
            val udpHeaderStart = 40
            val dstPort = ((buffer[udpHeaderStart + 2].toInt() and 0xFF) shl 8) or (buffer[udpHeaderStart + 3].toInt() and 0xFF)
            if (dstPort == 53) {
                val dnsDataStart = 48
                val dnsDataLength = limit - dnsDataStart
                val domain = parseDnsQuery(buffer, dnsDataStart, dnsDataLength) ?: return null
                return processDomain(domain, buffer, limit, 40, udpHeaderStart, 6)
            }
        }
        
        return null
    }

    private suspend fun processDomain(domain: String, buffer: ByteArray, limit: Int, ipLen: Int, udpStart: Int, version: Int): ByteBuffer? {
        if (version == 4 && config.forcedSafeSearch) {
            val safeIp = getSafeSearchIp(domain)
            if (safeIp != null) {
                Log.d("DnsVpn", "SafeSearch redirect: $domain -> $safeIp")
                return createDnsAResponse(buffer, limit, ipLen, udpStart, safeIp)
            }
        }

        if (config.useLocalBlocklist && blocklistManager.isDomainBlocked(domain)) {
            Log.d("DnsVpn", "Blocking: $domain")
            return createNxDomainResponse(buffer, limit, ipLen, udpStart, version)
        }
        
        val dnsDataStart = udpStart + 8
        val query = buffer.copyOfRange(dnsDataStart, limit)
        
        // Final Fix: Resolve via proxy with logging
        Log.d("DnsVpn", "Resolving $domain via proxy...")
        val dnsResponse = proxy.resolve(query, config.dnsServers)
        
        if (dnsResponse == null) {
            Log.w("DnsVpn", "Proxy failed to resolve $domain")
            return null
        }
        
        Log.d("DnsVpn", "Successfully resolved $domain")
        return createResponsePacket(buffer, ipLen, udpStart, dnsResponse, version)
    }

    private fun parseDnsQuery(buffer: ByteArray, start: Int, length: Int): String? {
        if (length < 12) return null
        var pos = start + 12
        val domain = StringBuilder()
        try {
            while (pos < start + length) {
                val len = buffer[pos].toInt() and 0xFF
                if (len == 0) break
                if (domain.isNotEmpty()) domain.append(".")
                domain.append(String(buffer, pos + 1, len))
                pos += len + 1
            }
        } catch (e: Exception) { return null }
        return domain.toString()
    }

    private fun createNxDomainResponse(buffer: ByteArray, limit: Int, ipLen: Int, udpStart: Int, version: Int): ByteBuffer {
        val dnsStart = udpStart + 8
        val response = buffer.copyOfRange(0, limit)
        
        if (version == 4) {
            System.arraycopy(buffer, 12, response, 16, 4)
            System.arraycopy(buffer, 16, response, 12, 4)
        } else {
            System.arraycopy(buffer, 8, response, 24, 16)
            System.arraycopy(buffer, 24, response, 8, 16)
        }
        
        System.arraycopy(buffer, udpStart, response, udpStart + 2, 2)
        System.arraycopy(buffer, udpStart + 2, response, udpStart, 2)
        
        response[dnsStart + 2] = 0x81.toByte()
        response[dnsStart + 3] = 0x83.toByte()
        for (i in 6..11) response[dnsStart + i] = 0
        response[dnsStart + 5] = 1 
        
        updateChecksums(response, ipLen, udpStart, version)
        return ByteBuffer.wrap(response)
    }

    private fun createDnsAResponse(buffer: ByteArray, limit: Int, ipLen: Int, udpStart: Int, ip: String): ByteBuffer {
        val dnsStart = udpStart + 8
        val ipParts = ip.split(".").map { it.toInt().toByte() }
        if (ipParts.size != 4) return ByteBuffer.allocate(0)

        var pos = dnsStart + 12
        while (pos < limit) {
            val len = buffer[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 5
                break
            }
            pos += len + 1
        }
        val endOfQuestion = pos.coerceAtMost(limit)

        val answerSection = byteArrayOf(
            0xc0.toByte(), 0x0c.toByte(), 0x00, 0x01, 0x00, 0x01, 
            0x00, 0x00, 0x0e.toByte(), 0x10.toByte(), 0x00, 0x04,
            ipParts[0], ipParts[1], ipParts[2], ipParts[3]
        )

        val totalLen = endOfQuestion + answerSection.size
        val response = ByteArray(totalLen)
        System.arraycopy(buffer, 0, response, 0, endOfQuestion)
        System.arraycopy(answerSection, 0, response, endOfQuestion, answerSection.size)

        response[2] = ((totalLen shr 8) and 0xFF).toByte()
        response[3] = (totalLen and 0xFF).toByte()
        System.arraycopy(buffer, 12, response, 16, 4)
        System.arraycopy(buffer, 16, response, 12, 4)

        System.arraycopy(buffer, udpStart, response, udpStart + 2, 2)
        System.arraycopy(buffer, udpStart + 2, response, udpStart, 2)
        val udpLen = totalLen - ipLen
        response[udpStart + 4] = ((udpLen shr 8) and 0xFF).toByte()
        response[udpStart + 5] = (udpLen and 0xFF).toByte()

        response[dnsStart + 2] = 0x81.toByte()
        response[dnsStart + 3] = 0x80.toByte()
        response[dnsStart + 7] = 1 
        
        updateChecksums(response, ipLen, udpStart, 4)
        return ByteBuffer.wrap(response)
    }

    private fun createResponsePacket(requestBuffer: ByteArray, ipLen: Int, udpStart: Int, dnsResponse: ByteArray, version: Int): ByteBuffer {
        val totalLen = ipLen + 8 + dnsResponse.size
        val response = ByteArray(totalLen)
        
        System.arraycopy(requestBuffer, 0, response, 0, ipLen)
        if (version == 4) {
            response[2] = ((totalLen shr 8) and 0xFF).toByte()
            response[3] = (totalLen and 0xFF).toByte()
            System.arraycopy(requestBuffer, 12, response, 16, 4)
            System.arraycopy(requestBuffer, 16, response, 12, 4)
        } else {
            val payloadLen = totalLen - 40
            response[4] = ((payloadLen shr 8) and 0xFF).toByte()
            response[5] = (payloadLen and 0xFF).toByte()
            System.arraycopy(requestBuffer, 8, response, 24, 16)
            System.arraycopy(requestBuffer, 24, response, 8, 16)
        }
        
        System.arraycopy(requestBuffer, udpStart, response, udpStart + 2, 2)
        System.arraycopy(requestBuffer, udpStart + 2, response, udpStart, 2)
        val udpLen = 8 + dnsResponse.size
        response[udpStart + 4] = ((udpLen shr 8) and 0xFF).toByte()
        response[udpStart + 5] = (udpLen and 0xFF).toByte()
        
        System.arraycopy(dnsResponse, 0, response, udpStart + 8, dnsResponse.size)
        
        updateChecksums(response, ipLen, udpStart, version)
        return ByteBuffer.wrap(response)
    }

    private fun updateChecksums(buffer: ByteArray, ipLen: Int, udpStart: Int, version: Int) {
        if (version == 4) {
            buffer[10] = 0
            buffer[11] = 0
            val ipCsum = calculateChecksum(buffer, 0, ipLen)
            buffer[10] = (ipCsum shr 8).toByte()
            buffer[11] = (ipCsum and 0xFF).toByte()
            
            // Set UDP checksum to 0 for IPv4 (optional)
            buffer[udpStart + 6] = 0
            buffer[udpStart + 7] = 0
        } else {
            // IPv6 REQUIRES UDP checksum.
            buffer[udpStart + 6] = 0
            buffer[udpStart + 7] = 0
            val udpLen = ((buffer[udpStart + 4].toInt() and 0xFF) shl 8) or (buffer[udpStart + 5].toInt() and 0xFF)
            
            var sum = 0
            // Pseudo-header: Src IP (16), Dst IP (16), UDP Len (4), Zero (3), Next Header (1)
            for (i in 0 until 16) {
                sum += ((buffer[8 + i].toInt() and 0xFF) shl 8) or (buffer[24 + i].toInt() and 0xFF)
            }
            sum += udpLen
            sum += 17 // Next Header: UDP
            
            // UDP Header + Data
            var i = udpStart
            while (i < udpStart + udpLen) {
                val high = buffer[i].toInt() and 0xFF
                val low = if (i + 1 < udpStart + udpLen) buffer[i + 1].toInt() and 0xFF else 0
                sum += (high shl 8) or low
                i += 2
            }
            
            while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            val csum = (sum.inv() and 0xFFFF)
            buffer[udpStart + 6] = (csum shr 8).toByte()
            buffer[udpStart + 7] = (csum and 0xFF).toByte()
        }
    }

    private fun calculateChecksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val high = buffer[i].toInt() and 0xFF
            val low = if (i + 1 < offset + length) buffer[i + 1].toInt() and 0xFF else 0
            sum += (high shl 8) or low
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF)
    }

    private fun getSafeSearchIp(domain: String): String? {
        val d = domain.lowercase()
        return when {
            d.contains("google.") -> "216.239.38.120"
            d.contains("bing.com") -> "204.79.197.220"
            d.contains("duckduckgo.com") -> "54.241.2.241"
            else -> null
        }
    }
}
