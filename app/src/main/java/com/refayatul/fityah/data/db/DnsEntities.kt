package com.refayatul.fityah.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "blocklist_sources")
data class BlocklistSourceEntity(
    @PrimaryKey val url: String,
    val label: String,
    val isEnabled: Boolean = true,
    val lastRefreshTime: Long = 0L
)

@Entity(tableName = "blocked_domains", indices = [Index(value = ["domain"])])
data class BlockedDomainEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val domain: String,
    val sourceUrl: String
)

@Entity(tableName = "allowed_domains")
data class AllowedDomainEntity(
    @PrimaryKey val domain: String
)
