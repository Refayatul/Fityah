package com.refayatul.fityah.utils

import android.content.Context
import com.refayatul.fityah.data.db.AppDatabase
import com.refayatul.fityah.data.db.BlockedDomainEntity
import com.refayatul.fityah.data.db.BlocklistSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader

import com.refayatul.fityah.data.db.AllowedDomainEntity

class BlocklistManager(private val context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val dnsDao = db.dnsDao()
    private val client = OkHttpClient()

    companion object {
        const val DEFAULT_PORN_LIST = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts"
        
        // Task 2/11: DoH/DoT bypass prevention (Canary + Common Endpoints)
        private val BYPASS_DOMAINS = listOf(
            "use-application-dns.net", // Firefox DoH canary
            "mask.icloud.com",         // iCloud Private Relay
            "mask-h2.icloud.com",
            "dns.google",              // Google DoH
            "cloudflare-dns.com",      // Cloudflare DoH
            "dns.quad9.net",           // Quad9 DoH
            "doh.opendns.com"          // OpenDNS DoH
        )
        
        // Task 2/11: DoH/DoT IP endpoints to block at DNS level (sinkholed via VPN routes)
        val DOH_IPS = listOf(
            "8.8.8.8", "8.8.4.4",          // Google
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "1.1.1.1", "1.0.0.1",          // Cloudflare
            "2606:4700:4700::1111", "2606:4700:4700::1001",
            "9.9.9.9", "149.112.112.112",     // Quad9
            "2620:fe::fe", "2620:fe::9",
            "208.67.222.222", "208.67.220.220", // OpenDNS
            "2620:119:35::35", "2620:119:53::53",
            "94.140.14.14", "94.140.15.15",    // AdGuard
            "2a00:5a60::ad1:0ff", "2a00:5a60::ad2:0ff",
            "76.76.2.0", "76.76.10.0",         // ControlD
            "185.228.168.9", "185.228.169.9"   // CleanBrowsing
        )
    }

    suspend fun setupDefaultSources() {
        if (dnsDao.getAllSources().isEmpty()) {
            dnsDao.insertSource(BlocklistSourceEntity(
                url = DEFAULT_PORN_LIST,
                label = "Adult Content (StevenBlack)",
                isEnabled = true
            ))
            
            // Add bypass domains to blocked_domains immediately
            dnsDao.insertBlockedDomains(BYPASS_DOMAINS.map { 
                BlockedDomainEntity(domain = it, sourceUrl = "system") 
            })
        }
    }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val sources = dnsDao.getAllSources().filter { it.isEnabled }
        for (source in sources) {
            runCatching { refresh(source) }
        }
    }

    private suspend fun refresh(source: BlocklistSourceEntity) {
        val request = Request.Builder().url(source.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use
            val domains = mutableListOf<BlockedDomainEntity>()
            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
            reader.forEachLine { line ->
                val cleaned = line.trim()
                if (cleaned.startsWith("#") || cleaned.isEmpty()) return@forEachLine
                
                val parts = cleaned.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val domain = parts[1].lowercase()
                    if (domain != "localhost" && domain != "broadcasthost") {
                        domains.add(BlockedDomainEntity(domain = domain, sourceUrl = source.url))
                    }
                }
            }
            dnsDao.updateBlockedDomains(source.url, domains)
            dnsDao.insertSource(source.copy(lastRefreshTime = System.currentTimeMillis()))
        }
    }
    
    suspend fun isDomainBlocked(domain: String): Boolean {
        val parts = domain.lowercase().split(".")
        if (parts.size < 2) return false // TLDs aren't blocked usually
        
        // We check from the most specific to the least specific (parent domains)
        // Example: sub.example.com -> check sub.example.com, then example.com
        for (i in 0 until parts.size - 1) {
            val current = parts.subList(i, parts.size).joinToString(".")
            
            // Allowlist ALWAYS wins if specific match found
            if (dnsDao.isDomainAllowed(current)) return false
            
            // If this suffix is blocked, the whole domain is blocked
            if (dnsDao.isDomainBlocked(current)) return true
        }
        
        return false
    }
}
