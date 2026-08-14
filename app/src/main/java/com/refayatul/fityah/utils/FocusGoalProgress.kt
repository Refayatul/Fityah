package com.refayatul.fityah.utils

import com.refayatul.fityah.data.db.FocusStatsEntity

object FocusGoalProgress {
    fun durationWithinDay(
        sessions: List<FocusStatsEntity>,
        dayStart: Long,
        dayEnd: Long,
        now: Long
    ): Long {
        val ranges = sessions.mapNotNull { session ->
            val sessionEnd = if (session.status == RUNNING_STATUS) {
                minOf(now, session.estimatedEndTimeInMillis)
            } else {
                session.actualEndTimeInMillis
            }
            val clippedStart = maxOf(session.startTimeInMillis, dayStart)
            val clippedEnd = minOf(sessionEnd, dayEnd)
            if (clippedEnd > clippedStart) clippedStart to clippedEnd else null
        }.sortedBy { it.first }

        var total = 0L
        var currentStart = 0L
        var currentEnd = 0L
        ranges.forEachIndexed { index, range ->
            if (index == 0) {
                currentStart = range.first
                currentEnd = range.second
            } else if (range.first <= currentEnd) {
                currentEnd = maxOf(currentEnd, range.second)
            } else {
                total += currentEnd - currentStart
                currentStart = range.first
                currentEnd = range.second
            }
        }
        if (ranges.isNotEmpty()) {
            total += currentEnd - currentStart
        }
        return total
    }

    private const val RUNNING_STATUS = 0
}
