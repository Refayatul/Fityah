package com.refayatul.fityah.utils

import com.refayatul.fityah.data.models.AppBlockerWarningScreenConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionComparatorFocusGoalTest {
    private val enabled = AppBlockerWarningScreenConfig(
        isFocusGoalRequirementEnabled = true,
        focusGoalGroupId = "focus",
        focusGoalRequiredMinutes = 60
    )

    @Test
    fun enablingFocusGoalIsStricter() {
        assertTrue(
            RestrictionComparator.warningConfig(
                AppBlockerWarningScreenConfig(),
                enabled
            )
        )
    }

    @Test
    fun disablingFocusGoalIsWeaker() {
        assertFalse(
            RestrictionComparator.warningConfig(
                enabled,
                enabled.copy(isFocusGoalRequirementEnabled = false)
            )
        )
    }

    @Test
    fun increasingThresholdIsStricter() {
        assertTrue(
            RestrictionComparator.warningConfig(
                enabled,
                enabled.copy(focusGoalRequiredMinutes = 120)
            )
        )
    }

    @Test
    fun decreasingThresholdIsWeaker() {
        assertFalse(
            RestrictionComparator.warningConfig(
                enabled,
                enabled.copy(focusGoalRequiredMinutes = 30)
            )
        )
    }

    @Test
    fun changingGroupIsConservativelyWeaker() {
        assertFalse(
            RestrictionComparator.warningConfig(
                enabled,
                enabled.copy(focusGoalGroupId = "other")
            )
        )
    }
}
