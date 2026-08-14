package com.refayatul.fityah.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scroll_pattern")
data class ScrollPatternEntity(
    @PrimaryKey val packageName: String,
    val learnedEventsPerBurst: Float,
    val learnedBurstGapMs: Float = 400f,
    val totalBurstsSeen: Int = 0
)
