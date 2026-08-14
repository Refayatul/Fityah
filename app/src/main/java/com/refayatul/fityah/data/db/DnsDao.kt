package com.refayatul.fityah.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DnsDao {
    @Query("SELECT * FROM blocklist_sources")
    suspend fun getAllSources(): List<BlocklistSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: BlocklistSourceEntity)

    @Query("DELETE FROM blocklist_sources WHERE url = :url")
    suspend fun deleteSource(url: String)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_domains WHERE domain = :domain LIMIT 1)")
    suspend fun isDomainBlocked(domain: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM allowed_domains WHERE domain = :domain LIMIT 1)")
    suspend fun isDomainAllowed(domain: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllowedDomain(domain: AllowedDomainEntity)

    @Query("DELETE FROM allowed_domains WHERE domain = :domain")
    suspend fun deleteAllowedDomain(domain: String)

    @Query("SELECT * FROM allowed_domains")
    suspend fun getAllAllowedDomains(): List<AllowedDomainEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlockedDomains(domains: List<BlockedDomainEntity>)

    @Query("DELETE FROM blocked_domains WHERE sourceUrl = :sourceUrl")
    suspend fun deleteBlockedDomainsBySource(sourceUrl: String)

    @Transaction
    suspend fun updateBlockedDomains(sourceUrl: String, domains: List<BlockedDomainEntity>) {
        deleteBlockedDomainsBySource(sourceUrl)
        insertBlockedDomains(domains)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: DnsRequestLogEntity)

    @Query("SELECT * FROM dns_request_logs ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentLogs(): List<DnsRequestLogEntity>

    @Query("DELETE FROM dns_request_logs WHERE timestamp < :threshold")
    suspend fun cleanupLogs(threshold: Long)

    @Query("""
        SELECT packageName, appName, 
        COUNT(*) as totalRequests, 
        SUM(CASE WHEN isBlocked = 1 THEN 1 ELSE 0 END) as blockedRequests,
        SUM(CASE WHEN trackerName IS NOT NULL THEN 1 ELSE 0 END) as trackerRequests
        FROM dns_request_logs 
        WHERE packageName IS NOT NULL
        GROUP BY packageName 
        ORDER BY totalRequests DESC
    """)
    suspend fun getAppStats(): List<VpnAppStats>

    @Query("SELECT * FROM dns_request_logs WHERE packageName = :pkg ORDER BY timestamp DESC LIMIT 200")
    suspend fun getLogsForApp(pkg: String): List<DnsRequestLogEntity>
}

data class VpnAppStats(
    val packageName: String,
    val appName: String,
    val totalRequests: Int,
    val blockedRequests: Int,
    val trackerRequests: Int
)
