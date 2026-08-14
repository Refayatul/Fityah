package com.refayatul.fityah.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * A simple in-memory DNS cache to reduce upstream network calls and improve privacy.
 * Entries expire after a few minutes.
 */
object DnsCache {
    private data class CacheEntry(val response: ByteArray, val expiry: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    
    private const val TTL_MS = 300_000L // 5 minutes

    fun get(query: ByteArray): ByteArray? {
        val key = query.contentHashCode().toString()
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() > entry.expiry) {
            cache.remove(key)
            return null
        }
        return entry.response
    }

    fun put(query: ByteArray, response: ByteArray) {
        val key = query.contentHashCode().toString()
        cache[key] = CacheEntry(response, System.currentTimeMillis() + TTL_MS)
        
        // Basic eviction if cache grows too large
        if (cache.size > 500) {
            val oldest = cache.entries.minByOrNull { it.value.expiry }
            if (oldest != null) cache.remove(oldest.key)
        }
    }
}
