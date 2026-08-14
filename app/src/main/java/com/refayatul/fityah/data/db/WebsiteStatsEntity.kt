package com.refayatul.fityah.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "website_stats", primaryKeys = ["date", "packageName", "urlIdentifier"])
data class WebsiteStatsEntity(
    val date: String,
    val packageName: String,
    val urlIdentifier: String, // e.g., "youtube.com/shorts"
    val domain: String,        // e.g., "youtube.com" (for grouping in UI)
    val totalTime: Long = 0L,
    val lastVisited: Long = 0L,
    /** Trimmed, big-endian Int buckets by local hour; empty until usage is recorded. */
    @ColumnInfo(defaultValue = "X''")
    val hourlyUsage: ByteArray = byteArrayOf()
)
