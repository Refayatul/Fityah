package com.refayatul.fityah.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reel_stats")
data class ReelStatsEntity(
    @PrimaryKey val date: String,
    val count: Int = 0
)
