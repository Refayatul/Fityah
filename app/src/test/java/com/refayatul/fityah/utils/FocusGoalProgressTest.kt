package com.refayatul.fityah.utils

import com.refayatul.fityah.data.db.FocusStatsEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusGoalProgressTest {
    @Test
    fun durationClipsSessionsToToday() {
        val sessions = listOf(
            session(start = 500L, end = 1_500L, status = 1),
            session(start = 9_500L, end = 11_000L, status = 2)
        )

        val duration = FocusGoalProgress.durationWithinDay(
            sessions = sessions,
            dayStart = 1_000L,
            dayEnd = 10_000L,
            now = 8_000L
        )

        assertEquals(1_000L, duration)
    }

    @Test
    fun runningSessionCountsElapsedTime() {
        val sessions = listOf(
            FocusStatsEntity(
                groupId = "focus",
                startTimeInMillis = 2_000L,
                estimatedEndTimeInMillis = 8_000L,
                actualEndTimeInMillis = 0L,
                status = 0
            )
        )

        val duration = FocusGoalProgress.durationWithinDay(
            sessions = sessions,
            dayStart = 1_000L,
            dayEnd = 10_000L,
            now = 5_000L
        )

        assertEquals(3_000L, duration)
    }

    @Test
    fun runningSessionDoesNotExceedScheduledEnd() {
        val sessions = listOf(
            FocusStatsEntity(
                groupId = "focus",
                startTimeInMillis = 2_000L,
                estimatedEndTimeInMillis = 4_000L,
                actualEndTimeInMillis = 0L,
                status = 0
            )
        )

        val duration = FocusGoalProgress.durationWithinDay(
            sessions = sessions,
            dayStart = 1_000L,
            dayEnd = 10_000L,
            now = 7_000L
        )

        assertEquals(2_000L, duration)
    }

    @Test
    fun overlappingSessionsAreNotCountedTwice() {
        val sessions = listOf(
            session(start = 2_000L, end = 6_000L, status = 1),
            session(start = 4_000L, end = 8_000L, status = 1)
        )

        val duration = FocusGoalProgress.durationWithinDay(
            sessions = sessions,
            dayStart = 1_000L,
            dayEnd = 10_000L,
            now = 9_000L
        )

        assertEquals(6_000L, duration)
    }

    private fun session(start: Long, end: Long, status: Int): FocusStatsEntity {
        return FocusStatsEntity(
            groupId = "focus",
            startTimeInMillis = start,
            estimatedEndTimeInMillis = end,
            actualEndTimeInMillis = end,
            status = status
        )
    }
}
