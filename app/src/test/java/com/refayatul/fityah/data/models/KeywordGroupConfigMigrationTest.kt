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
class KeywordGroupConfigMigrationTest {
    private val gson = Gson()

    @Test
    fun usageGroupKeepsItsLimitAndBecomesAllDay() {
        val legacy = KeywordGroup(
            id = "usage",
            blockingType = AppBlockingType.Usage,
            setting = gson.toJson(AppUsageConfig(uniformLimit = 45))
        )

        val upgraded = KeywordBlocker(keywordGroups = listOf(legacy))
            .upgradeLegacyKeywordGroupConfigs(gson)
            .keywordGroups
            .single()

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
        val carrier = KeywordGroup(
            id = "schedule",
            isActive = true,
            blockingType = AppBlockingType.Timed,
            setting = gson.toJson(schedule)
        )
        val usage = KeywordGroup(
            id = "usage",
            isActive = true,
            blockingType = AppBlockingType.Usage,
            setting = gson.toJson(AppUsageConfig(uniformLimit = 90)),
            linkedTimeGroupId = carrier.id
        )

        val upgraded = KeywordBlocker(keywordGroups = listOf(carrier, usage))
            .upgradeLegacyKeywordGroupConfigs(gson)
            .keywordGroups

        assertEquals(listOf("usage"), upgraded.map(KeywordGroup::id))
        assertNotNull(upgraded.single().config?.schedule?.activeWindow(at(Calendar.MONDAY, 10, 0)))
        assertNull(upgraded.single().config?.schedule?.activeWindow(at(Calendar.MONDAY, 18, 0)))
        assertEquals(90L, upgraded.single().config?.usage?.uniformLimit)
    }

    @Test
    fun timeOnlyGroupKeepsBlockingOutsideItsOldAllowedHours() {
        val oldAllowedSchedule = AppTimeConfig(
            everydayIntervals = mutableListOf(TimeInterval(9, 0, 17, 0))
        )
        val legacy = KeywordGroup(
            id = "timed",
            blockingType = AppBlockingType.Timed,
            setting = gson.toJson(oldAllowedSchedule)
        )

        val upgraded = KeywordBlocker(keywordGroups = listOf(legacy))
            .upgradeLegacyKeywordGroupConfigs(gson)
            .keywordGroups
            .single()
        val config = requireNotNull(upgraded.config)

        assertEquals(0L, config.usage.uniformLimit)
        assertNotNull(config.schedule.activeWindow(at(Calendar.MONDAY, 8, 0)))
        assertNull(config.schedule.activeWindow(at(Calendar.MONDAY, 10, 0)))
        assertNotNull(config.schedule.activeWindow(at(Calendar.MONDAY, 18, 0)))
    }

    @Test
    fun upgradedConfigDoesNotMigrateTwice() {
        val config = ScheduledUsageConfig(
            schedule = AppTimeConfig.allDay(),
            usage = AppUsageConfig(uniformLimit = 30)
        )
        val blocker = KeywordBlocker(
            keywordGroups = listOf(KeywordGroup(id = "new", config = config))
        )

        val upgraded = blocker.upgradeLegacyKeywordGroupConfigs(gson)

        assertTrue(upgraded === blocker)
        assertFalse(upgraded.keywordGroups.single().warningScreenConfig.isOnOpenConfig)
    }

    private fun at(dayOfWeek: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(2026, Calendar.JULY, 27, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
            while (get(Calendar.DAY_OF_WEEK) != dayOfWeek) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
}
