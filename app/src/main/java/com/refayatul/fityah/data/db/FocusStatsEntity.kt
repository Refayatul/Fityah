package com.refayatul.fityah.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "focus_stats")
data class FocusStatsEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val groupId: String,
    val startTimeInMillis: Long,
    val estimatedEndTimeInMillis: Long,
    val actualEndTimeInMillis: Long,
    val status: Int // 0=RUNNING, 1=COMPLETED, 2=FORCE_STOPPED
)
