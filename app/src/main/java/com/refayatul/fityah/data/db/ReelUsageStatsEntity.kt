package com.refayatul.fityah.data.db

import androidx.room.Entity

@Entity(tableName = "reel_usage_stats", primaryKeys = ["date", "packageName"])
data class ReelUsageStatsEntity(
    val date: String,
    val packageName: String,
    val totalTime: Long = 0L,
    val reelCount: Int = 0,
    val lastUpdated: Long = 0L
)
