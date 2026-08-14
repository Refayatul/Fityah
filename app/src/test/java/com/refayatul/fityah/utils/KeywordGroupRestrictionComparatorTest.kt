package com.refayatul.fityah.utils

import com.refayatul.fityah.data.models.AppTimeConfig
import com.refayatul.fityah.data.models.AppUsageConfig
import com.refayatul.fityah.data.models.KeywordBlocker
import com.refayatul.fityah.data.models.KeywordGroup
import com.refayatul.fityah.data.models.ScheduledUsageConfig
import com.refayatul.fityah.data.models.TimeInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordGroupRestrictionComparatorTest {
    private val oldBlocker = blocker(startHour = 9, usageMinutes = 60)

    @Test
    fun lowerUsageAllowanceIsStricter() {
        assertTrue(
            RestrictionComparator.keywordBlocker(
                oldBlocker,
                blocker(startHour = 9, usageMinutes = 30)
            )
        )
    }

    @Test
    fun shorterActiveScheduleIsWeaker() {
        assertFalse(
            RestrictionComparator.keywordBlocker(
                oldBlocker,
                blocker(startHour = 10, usageMinutes = 60)
            )
        )
    }

    @Test
    fun higherUsageAllowanceIsWeaker() {
        assertFalse(
            RestrictionComparator.keywordBlocker(
                oldBlocker,
                blocker(startHour = 9, usageMinutes = 90)
            )
        )
    }

    private fun blocker(startHour: Int, usageMinutes: Long) = KeywordBlocker(
        isActive = true,
        keywordGroups = listOf(
            KeywordGroup(
                id = "group",
                selectedKeywords = listOf("example"),
                config = ScheduledUsageConfig(
                    schedule = AppTimeConfig(
                        everydayIntervals =
                            mutableListOf(TimeInterval(startHour, 0, 17, 0))
                    ),
                    usage = AppUsageConfig(uniformLimit = usageMinutes)
                ),
                isActive = true
            )
        )
    )
}
