package com.refayatul.fityah.utils

import com.refayatul.fityah.data.models.AppGroup
import com.refayatul.fityah.data.models.AppGroupConfig
import com.refayatul.fityah.data.models.AppTimeConfig
import com.refayatul.fityah.data.models.AppUsageConfig
import com.refayatul.fityah.data.models.TimeInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupRestrictionComparatorTest {
    private val oldGroup = group(startHour = 9, usageMinutes = 60)

    @Test
    fun lowerUsageAllowanceIsStricter() {
        assertTrue(
            RestrictionComparator.appGroups(
                listOf(oldGroup),
                listOf(group(startHour = 9, usageMinutes = 30))
            )
        )
    }

    @Test
    fun shorterActiveScheduleIsWeaker() {
        assertFalse(
            RestrictionComparator.appGroups(
                listOf(oldGroup),
                listOf(group(startHour = 10, usageMinutes = 60))
            )
        )
    }

    @Test
    fun higherUsageAllowanceIsWeaker() {
        assertFalse(
            RestrictionComparator.appGroups(
                listOf(oldGroup),
                listOf(group(startHour = 9, usageMinutes = 90))
            )
        )
    }

    private fun group(startHour: Int, usageMinutes: Long) = AppGroup(
        id = "group",
        selectedPackages = listOf("example.app"),
        config = AppGroupConfig(
            schedule = AppTimeConfig(
                everydayIntervals = mutableListOf(TimeInterval(startHour, 0, 17, 0))
            ),
            usage = AppUsageConfig(uniformLimit = usageMinutes)
        ),
        isActive = true
    )
}
