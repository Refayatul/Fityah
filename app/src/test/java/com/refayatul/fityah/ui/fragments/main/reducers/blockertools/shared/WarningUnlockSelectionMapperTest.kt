package com.refayatul.fityah.ui.fragments.main.reducers.blockertools.shared

import com.refayatul.fityah.data.models.AppBlockerWarningScreenConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class WarningUnlockSelectionMapperTest {
    @Test
    fun fromConfigMapsEveryUnlockMode() {
        val cases = listOf(
            AppBlockerWarningScreenConfig(isProceedDisabled = true) to
                WarningUnlockSelection(0, -1),
            AppBlockerWarningScreenConfig(isQrUnlockRequirementEnabled = true) to
                WarningUnlockSelection(1, 0),
            AppBlockerWarningScreenConfig(isTypingRequirementEnabled = true) to
                WarningUnlockSelection(1, 1),
            AppBlockerWarningScreenConfig(isIntentRequirementEnabled = true) to
                WarningUnlockSelection(1, 2),
            AppBlockerWarningScreenConfig(isNfcUnlockRequirementEnabled = true) to
                WarningUnlockSelection(1, 3),
            AppBlockerWarningScreenConfig(isAdaptiveMathRequirementEnabled = true) to
                WarningUnlockSelection(1, 4),
            AppBlockerWarningScreenConfig(isDynamicIntervalSettingAllowed = true) to
                WarningUnlockSelection(2, 0),
            AppBlockerWarningScreenConfig() to WarningUnlockSelection(2, 1)
        )

        cases.forEach { (config, expected) ->
            assertEquals(
                expected,
                WarningUnlockSelectionMapper.fromConfig(
                    config,
                    isOnEachOpenEnabled = false
                )
            )
        }
    }

    @Test
    fun fromConfigUsesFixedTimeForOnEachOpen() {
        val selection = WarningUnlockSelectionMapper.fromConfig(
            AppBlockerWarningScreenConfig(isDynamicIntervalSettingAllowed = true),
            isOnEachOpenEnabled = true
        )

        assertEquals(WarningUnlockSelection(2, 1), selection)
    }

    @Test
    fun toFlagsMapsEveryUnlockMode() {
        val cases = listOf(
            WarningUnlockSelection(0, -1) to
                WarningUnlockFlags(isProceedDisabled = true),
            WarningUnlockSelection(1, 0) to
                WarningUnlockFlags(isQrUnlockRequirementEnabled = true),
            WarningUnlockSelection(1, 1) to
                WarningUnlockFlags(isTypingRequirementEnabled = true),
            WarningUnlockSelection(1, 2) to
                WarningUnlockFlags(isIntentRequirementEnabled = true),
            WarningUnlockSelection(1, 3) to
                WarningUnlockFlags(isNfcUnlockRequirementEnabled = true),
            WarningUnlockSelection(1, 4) to
                WarningUnlockFlags(isAdaptiveMathRequirementEnabled = true),
            WarningUnlockSelection(2, 0) to
                WarningUnlockFlags(isDynamicIntervalSettingAllowed = true),
            WarningUnlockSelection(2, 1) to WarningUnlockFlags()
        )

        cases.forEach { (selection, expected) ->
            assertEquals(
                expected,
                WarningUnlockSelectionMapper.toFlags(
                    selection.challengeIndex,
                    selection.secondaryIndex
                )
            )
        }
    }
}
