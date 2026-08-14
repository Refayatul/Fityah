package com.refayatul.fityah.data.models

import com.google.gson.Gson
import com.refayatul.fityah.utils.activeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

@Suppress("DEPRECATION")
class AppGroupConfigMigrationTest {
    private val gson = Gson()

    @Test
    fun usageGroupKeepsItsLimitAndBecomesAllDay() {
        val legacyUsage = AppUsageConfig(uniformLimit = 45)
        val group = AppGroup(
            id = "usage",
            blockingType = AppBlockingType.Usage,
            setting = gson.toJson(legacyUsage)
        )

        val upgraded = listOf(group).upgradeLegacyAppGroupConfigs(gson).single()

        assertEquals(45L, upgraded.config?.usage?.uniformLimit)
        assertNotNull(upgraded.config?.schedule?.activeWindow(at(Calendar.MONDAY, 3, 0)))
        assertEquals("", upgraded.setting)
        assertNull(upgraded.linkedTimeGroupId)
    }

    @Test
    fun linkedUsageGroupAbsorbsScheduleAndRemovesCarrierGroup() {
        val schedule = AppTimeConfig(
            everydayIntervals = mutableListOf(TimeInterval(9, 0, 17, 0))
        )
        val carrier = AppGroup(
            id = "schedule",
            isActive = true,
            blockingType = AppBlockingType.Timed,
            setting = gson.toJson(schedule)
        )
        val usage = AppGroup(
            id = "usage",
            isActive = true,
            blockingType = AppBlockingType.Usage,
            setting = gson.toJson(AppUsageConfig(uniformLimit = 90)),
            linkedTimeGroupId = carrier.id
        )

        val upgraded = listOf(carrier, usage).upgradeLegacyAppGroupConfigs(gson)

        assertEquals(listOf("usage"), upgraded.map(AppGroup::id))
        assertNotNull(upgraded.single().config?.schedule?.activeWindow(at(Calendar.MONDAY, 10, 0)))
        assertNull(upgraded.single().config?.schedule?.activeWindow(at(Calendar.MONDAY, 18, 0)))
        assertEquals(90L, upgraded.single().config?.usage?.uniformLimit)
    }

    @Test
    fun timeOnlyGroupKeepsBlockingOutsideItsOldAllowedHours() {
        val oldAllowedSchedule = AppTimeConfig(
            everydayIntervals = mutableListOf(TimeInterval(9, 0, 17, 0))
        )
        val legacy = AppGroup(
            id = "timed",
            blockingType = AppBlockingType.Timed,
            setting = gson.toJson(oldAllowedSchedule)
        )

        val upgraded = listOf(legacy).upgradeLegacyAppGroupConfigs(gson).single()
        val config = requireNotNull(upgraded.config)

        assertEquals(0L, config.usage.uniformLimit)
        assertNotNull(config.schedule.activeWindow(at(Calendar.MONDAY, 8, 0)))
        assertNull(config.schedule.activeWindow(at(Calendar.MONDAY, 10, 0)))
        assertNotNull(config.schedule.activeWindow(at(Calendar.MONDAY, 18, 0)))
    }

    @Test
    fun onOpenGroupKeepsItsSessionGateBehavior() {
        val legacy = AppGroup(
            id = "on-open",
            blockingType = AppBlockingType.OnOpen
        )

        val upgraded = listOf(legacy).upgradeLegacyAppGroupConfigs(gson).single()
        val config = requireNotNull(upgraded.config)

        assertEquals(0L, config.usage.uniformLimit)
        assertNotNull(config.schedule.activeWindow(at(Calendar.MONDAY, 10, 0)))
        assertTrue(upgraded.warningScreenConfig.isOnOpenConfig)
    }

    @Test
    fun upgradedConfigDoesNotMigrateTwice() {
        val config = AppGroupConfig(
            schedule = AppTimeConfig.allDay(),
            usage = AppUsageConfig(uniformLimit = 30)
        )
        val groups = listOf(AppGroup(id = "new", config = config))

        val upgraded = groups.upgradeLegacyAppGroupConfigs(gson)

        assertTrue(upgraded === groups)
        assertFalse(upgraded.single().warningScreenConfig.isOnOpenConfig)
    }

    private fun at(dayOfWeek: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 27, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != dayOfWeek) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
}
