package com.refayatul.fityah.ui.fragments.main.reducers.blockertools.shared

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.view.isVisible
import com.refayatul.fityah.R
import com.refayatul.fityah.data.models.AppBlockerWarningScreenConfig

internal data class WarningUnlockOption(
    val title: String,
    val subtext: String,
    val isRecommended: Boolean = false
) {
    override fun toString(): String = title
}

internal fun createWarningChallengeOptions(context: Context) = listOf(
    WarningUnlockOption(
        context.getString(R.string.warning_option_never_unlock),
        context.getString(R.string.warning_option_never_unlock_desc)
    ),
    WarningUnlockOption(
        context.getString(R.string.warning_option_require_effort),
        context.getString(R.string.warning_option_require_effort_desc),
        true
    ),
    WarningUnlockOption(
        context.getString(R.string.warning_option_wait),
        context.getString(R.string.warning_option_wait_desc)
    )
)

internal fun createWarningEffortOptions(context: Context) = listOf(
    WarningUnlockOption(
        context.getString(R.string.warning_option_qr),
        context.getString(R.string.warning_option_qr_desc),
        true
    ),
    WarningUnlockOption(
        context.getString(R.string.warning_option_typing),
        context.getString(R.string.warning_option_typing_desc)
    ),
    WarningUnlockOption(
        context.getString(R.string.warning_option_intent),
        context.getString(R.string.warning_option_intent_desc)
    ),
    WarningUnlockOption(
        context.getString(R.string.warning_option_nfc),
        context.getString(R.string.warning_option_nfc_desc)
    ),
    WarningUnlockOption(
        context.getString(R.string.warning_option_adaptive_math),
        context.getString(R.string.warning_option_adaptive_math_desc)
    )
)

internal fun createWarningNoEffortOptions(context: Context) = listOf(
    WarningUnlockOption(
        context.getString(R.string.warning_option_choose_time),
        context.getString(R.string.warning_option_choose_time_desc)
    ),
    WarningUnlockOption(
        context.getString(R.string.warning_option_fixed_time),
        context.getString(R.string.warning_option_fixed_time_desc),
        true
    )
)

internal data class WarningUnlockSelection(
    val challengeIndex: Int,
    val secondaryIndex: Int
)

internal data class WarningUnlockFlags(
    val isProceedDisabled: Boolean = false,
    val isQrUnlockRequirementEnabled: Boolean = false,
    val isTypingRequirementEnabled: Boolean = false,
    val isIntentRequirementEnabled: Boolean = false,
    val isNfcUnlockRequirementEnabled: Boolean = false,
    val isAdaptiveMathRequirementEnabled: Boolean = false,
    val isDynamicIntervalSettingAllowed: Boolean = false
)

internal object WarningUnlockSelectionMapper {
    const val NEVER_UNLOCK_INDEX = 0
    const val EFFORT_INDEX = 1
    const val NO_EFFORT_INDEX = 2
    const val FIXED_TIME_INDEX = 1

    fun fromConfig(
        config: AppBlockerWarningScreenConfig,
        isOnEachOpenEnabled: Boolean
    ): WarningUnlockSelection {
        val selection = when {
            config.isProceedDisabled -> WarningUnlockSelection(NEVER_UNLOCK_INDEX, -1)
            config.isQrUnlockRequirementEnabled -> WarningUnlockSelection(EFFORT_INDEX, 0)
            config.isTypingRequirementEnabled -> WarningUnlockSelection(EFFORT_INDEX, 1)
            config.isIntentRequirementEnabled -> WarningUnlockSelection(EFFORT_INDEX, 2)
            config.isNfcUnlockRequirementEnabled -> WarningUnlockSelection(EFFORT_INDEX, 3)
            config.isAdaptiveMathRequirementEnabled -> WarningUnlockSelection(EFFORT_INDEX, 4)
            config.isDynamicIntervalSettingAllowed -> WarningUnlockSelection(NO_EFFORT_INDEX, 0)
            else -> WarningUnlockSelection(NO_EFFORT_INDEX, FIXED_TIME_INDEX)
        }
        return if (isOnEachOpenEnabled && selection.challengeIndex == NO_EFFORT_INDEX) {
            selection.copy(secondaryIndex = FIXED_TIME_INDEX)
        } else {
            selection
        }
    }

    fun toFlags(challengeIndex: Int, secondaryIndex: Int): WarningUnlockFlags {
        return when (challengeIndex) {
            NEVER_UNLOCK_INDEX -> WarningUnlockFlags(isProceedDisabled = true)
            EFFORT_INDEX -> when (secondaryIndex) {
                0 -> WarningUnlockFlags(isQrUnlockRequirementEnabled = true)
                1 -> WarningUnlockFlags(isTypingRequirementEnabled = true)
                2 -> WarningUnlockFlags(isIntentRequirementEnabled = true)
                3 -> WarningUnlockFlags(isNfcUnlockRequirementEnabled = true)
                4 -> WarningUnlockFlags(isAdaptiveMathRequirementEnabled = true)
                else -> WarningUnlockFlags()
            }
            NO_EFFORT_INDEX -> WarningUnlockFlags(
                isDynamicIntervalSettingAllowed = secondaryIndex == 0
            )
            else -> WarningUnlockFlags()
        }
    }
}

internal class WarningUnlockOptionAdapter(
    context: Context,
    options: List<WarningUnlockOption>
) : ArrayAdapter<WarningUnlockOption>(
    context,
    R.layout.item_dropdown_with_subtext,
    options
) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_dropdown_with_subtext, parent, false)
        val optionTitle = view.findViewById<TextView>(R.id.option_title)
        val optionSubtext = view.findViewById<TextView>(R.id.option_subtext)
        val recommendedBadge = view.findViewById<View>(R.id.recommended_badge)

        getItem(position)?.let { option ->
            optionTitle.text = option.title
            optionSubtext.text = option.subtext
            recommendedBadge.isVisible = option.isRecommended
        }
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return getView(position, convertView, parent)
    }

}
