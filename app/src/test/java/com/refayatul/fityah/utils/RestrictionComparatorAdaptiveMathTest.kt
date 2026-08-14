package com.refayatul.fityah.utils

import com.refayatul.fityah.data.models.AppBlockerWarningScreenConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionComparatorAdaptiveMathTest {
    private val base = AppBlockerWarningScreenConfig()

    @Test
    fun enablingAdaptiveMathIsStricter() {
        assertTrue(
            RestrictionComparator.warningConfig(
                base,
                base.copy(isAdaptiveMathRequirementEnabled = true)
            )
        )
    }

    @Test
    fun disablingAdaptiveMathIsWeaker() {
        val old = base.copy(isAdaptiveMathRequirementEnabled = true)

        assertFalse(
            RestrictionComparator.warningConfig(
                old,
                old.copy(isAdaptiveMathRequirementEnabled = false)
            )
        )
    }

    @Test
    fun increasingQuestionCountIsStricter() {
        val old = base.copy(
            isAdaptiveMathRequirementEnabled = true,
            adaptiveMathQuestionCount = 3
        )

        assertTrue(
            RestrictionComparator.warningConfig(
                old,
                old.copy(adaptiveMathQuestionCount = 5)
            )
        )
    }

    @Test
    fun decreasingQuestionCountIsWeaker() {
        val old = base.copy(
            isAdaptiveMathRequirementEnabled = true,
            adaptiveMathQuestionCount = 5
        )

        assertFalse(
            RestrictionComparator.warningConfig(
                old,
                old.copy(adaptiveMathQuestionCount = 3)
            )
        )
    }

    @Test
    fun increasingStartingLevelIsStricter() {
        val old = base.copy(
            isAdaptiveMathRequirementEnabled = true,
            adaptiveMathStartingLevel = 2
        )

        assertTrue(
            RestrictionComparator.warningConfig(
                old,
                old.copy(adaptiveMathStartingLevel = 4)
            )
        )
    }

    @Test
    fun decreasingStartingLevelIsWeaker() {
        val old = base.copy(
            isAdaptiveMathRequirementEnabled = true,
            adaptiveMathStartingLevel = 4
        )

        assertFalse(
            RestrictionComparator.warningConfig(
                old,
                old.copy(adaptiveMathStartingLevel = 2)
            )
        )
    }
}
