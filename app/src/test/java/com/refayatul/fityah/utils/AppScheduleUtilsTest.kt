package com.refayatul.fityah.utils

import com.refayatul.fityah.data.models.AppGroup
import com.refayatul.fityah.data.models.AppGroupConfig
import com.refayatul.fityah.data.models.AppTimeConfig
import com.refayatul.fityah.data.models.TimeInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppScheduleUtilsTest {

    @Test
    fun recognizesAllDaySchedule() {
        assertTrue(AppTimeConfig.allDay().isAllDaySchedule())
        assertFalse(
            AppTimeConfig(
                everydayIntervals = mutableListOf(TimeInterval(9, 0, 17, 0))
            ).isAllDaySchedule()
        )
    }

    @Test
    fun detectsOvernightOverlapOnFollowingDay() {
        val mondayNight = AppTimeConfig(
            isEveryday = false,
            dailyIntervals = mutableMapOf(1 to mutableListOf(TimeInterval(22, 0, 2, 0)))
        )
        val tuesdayMorning = AppTimeConfig(
            isEveryday = false,
            dailyIntervals = mutableMapOf(2 to mutableListOf(TimeInterval(1, 0, 3, 0)))
        )

        assertTrue(mondayNight.overlaps(tuesdayMorning))
    }

    @Test
    fun touchingRangesDoNotOverlap() {
        val morning = AppTimeConfig(
            everydayIntervals = mutableListOf(TimeInterval(9, 0, 12, 0))
        )
        val afternoon = AppTimeConfig(
            everydayIntervals = mutableListOf(TimeInterval(12, 0, 17, 0))
        )

        assertFalse(morning.overlaps(afternoon))
    }

    @Test
    fun findsConflictsOnlyForSharedApps() {
        val candidate = group("candidate", "com.example.shared", AppTimeConfig.allDay())
        val shared = group(
            "shared",
            "com.example.shared",
            AppTimeConfig(everydayIntervals = mutableListOf(TimeInterval(9, 0, 17, 0)))
        )
        val differentApp = group("different", "com.example.other", AppTimeConfig.allDay())

        val conflicts = candidate.scheduleConflictsWith(listOf(shared, differentApp))

        assertEquals(listOf("shared"), conflicts.map { it.group.id })
    }

    @Test
    fun ignoresSharedAppsWhenSchedulesDoNotOverlap() {
        val morning = group(
            "morning",
            "com.example.shared",
            AppTimeConfig(everydayIntervals = mutableListOf(TimeInterval(8, 0, 12, 0)))
        )
        val afternoon = group(
            "afternoon",
            "com.example.shared",
            AppTimeConfig(everydayIntervals = mutableListOf(TimeInterval(12, 0, 17, 0)))
        )

        assertTrue(morning.scheduleConflictsWith(listOf(afternoon)).isEmpty())
    }

    @Test
    fun ignoresInactiveGroups() {
        val candidate = group("candidate", "com.example.shared", AppTimeConfig.allDay())
        val inactive = group("inactive", "com.example.shared", AppTimeConfig.allDay())
            .copy(isActive = false)

        assertTrue(candidate.scheduleConflictsWith(listOf(inactive)).isEmpty())
    }

    @Test
    fun detectsOverlappingRangesInsideOneSchedule() {
        val schedule = AppTimeConfig(
            everydayIntervals = mutableListOf(
                TimeInterval(9, 0, 13, 0),
                TimeInterval(12, 0, 17, 0)
            )
        )

        assertTrue(schedule.hasOverlappingTimeRanges())
    }

    private fun group(id: String, packageName: String, schedule: AppTimeConfig) = AppGroup(
        id = id,
        selectedPackages = listOf(packageName),
        config = AppGroupConfig(schedule = schedule),
        isActive = true
    )
}
