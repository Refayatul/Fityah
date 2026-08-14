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
}
