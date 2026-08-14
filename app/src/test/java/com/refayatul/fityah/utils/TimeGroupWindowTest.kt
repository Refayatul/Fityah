package com.refayatul.fityah.utils

import com.refayatul.fityah.data.models.AppTimeConfig
import com.refayatul.fityah.data.models.TimeInterval
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TimeGroupWindowTest {
    private fun at(dayOfWeek: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 27, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != dayOfWeek) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

    @Test
    fun afterMidnightBelongsToPreviousDaysOvernightInterval() {
        val mondayOvernight = TimeInterval(22, 0, 2, 0)
        val config = AppTimeConfig(
            isEveryday = false,
            dailyIntervals = mutableMapOf(
                Calendar.MONDAY - 1 to mutableListOf(mondayOvernight)
            )
        )

        assertNotNull(config.activeWindow(at(Calendar.TUESDAY, 1, 0)))
        assertNull(config.activeWindow(at(Calendar.WEDNESDAY, 1, 0)))
    }

    @Test
    fun futureDaysOvernightIntervalDoesNotStartEarly() {
        val tuesdayOvernight = TimeInterval(22, 0, 2, 0)
        val config = AppTimeConfig(
            isEveryday = false,
            dailyIntervals = mutableMapOf(
                Calendar.TUESDAY - 1 to mutableListOf(tuesdayOvernight)
            )
        )

        assertNull(config.activeWindow(at(Calendar.TUESDAY, 1, 0)))
        assertNotNull(config.activeWindow(at(Calendar.WEDNESDAY, 1, 0)))
    }

    @Test
    fun nextChangeIsStartWhenOutsideScheduleAndEndWhenInside() {
        val config = AppTimeConfig(
            isEveryday = true,
            everydayIntervals = mutableListOf(TimeInterval(9, 0, 17, 0))
        )

        assertEquals(
            at(Calendar.MONDAY, 9, 0),
            config.nextChangeAfter(at(Calendar.MONDAY, 8, 0))
        )
        assertEquals(
            at(Calendar.MONDAY, 17, 0),
            config.nextChangeAfter(at(Calendar.MONDAY, 10, 0))
        )
    }
}
