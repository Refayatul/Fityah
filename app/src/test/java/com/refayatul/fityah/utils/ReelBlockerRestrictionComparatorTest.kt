package com.refayatul.fityah.utils

import com.google.gson.Gson
import com.refayatul.fityah.data.models.ReelBlocker
import com.refayatul.fityah.data.models.ReelBlockingType
import com.refayatul.fityah.data.models.ReelUsageConfig
import com.refayatul.fityah.data.models.upgradeLegacyConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelBlockerRestrictionComparatorTest {

    @Test
    fun oldStoredConfigGetsEmptyExcludedPackages() {
        val config = Gson().fromJson("""{"isActive":true}""", ReelBlocker::class.java)

        assertTrue(config.excludedPackages.isEmpty())
    }

    @Test
    fun addingExcludedPackageIsWeaker() {
        val old = ReelBlocker(isActive = true)
        val new = old.copy(excludedPackages = listOf("com.instagram.android"))

        assertFalse(RestrictionComparator.reelBlocker(old, new))
    }

    @Test
    fun legacyUsageConfigKeepsItsUsageLimitAndAllowsAllHours() {
        val legacy = ReelBlocker(
            blockingType = ReelBlockingType.USAGE,
            settings = Gson().toJson(ReelUsageConfig(uniformLimit = 30))
        )

        val config = legacy.upgradeLegacyConfig().config!!

        assertTrue(config.schedule.everydayIntervals.single().endHour == 24)
        assertTrue(config.usage.uniformLimit == 30L)
    }

    @Test
    fun removingExcludedPackageIsStricter() {
        val old = ReelBlocker(
            isActive = true,
            excludedPackages = listOf("com.instagram.android")
        )
        val new = old.copy(excludedPackages = emptyList())

        assertTrue(RestrictionComparator.reelBlocker(old, new))
    }
}
