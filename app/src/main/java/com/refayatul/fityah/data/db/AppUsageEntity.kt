package com.refayatul.fityah.data.db

import androidx.room.Entity

@Entity(tableName = "app_usage_stats", primaryKeys = ["date", "packageName"])
data class AppUsageEntity(
    val date: String,
    val packageName: String,
    val totalTime: Long = 0L,
    val hourlyUsage: String = "",
    val launchCount: Int = 0,
    val lastUsed: Long = 0L
)
