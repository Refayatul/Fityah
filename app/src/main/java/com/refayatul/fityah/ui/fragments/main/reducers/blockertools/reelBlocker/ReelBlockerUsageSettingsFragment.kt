package com.refayatul.fityah.ui.fragments.main.reducers.blockertools.reelBlocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.refayatul.fityah.data.models.AppUsageConfig
import com.refayatul.fityah.data.models.ReelUsageConfig
import com.refayatul.fityah.databinding.FragmentReelBlockerUsageSettingsBinding
import com.refayatul.fityah.ui.fragments.main.reducers.blockertools.shared.BaseUsageSettingsFragment

class ReelBlockerUsageSettingsFragment : BaseUsageSettingsFragment() {

    companion object {
        const val FRAGMENT_ID = "ReelBlockerUsageSettingsBottomSheet"
    }

    private val viewModel: ReelBlockerViewModel by activityViewModels()

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View =
        FragmentReelBlockerUsageSettingsBinding.inflate(inflater, container, false).root

    override fun loadUsageConfig(): AppUsageConfig {
        val c = viewModel.getReelUsageConfig()
        return AppUsageConfig(c.isDailyUniform, c.uniformLimit).also { c.dailyLimits.copyInto(it.dailyLimits) }
    }

    override fun saveUsageConfig(config: AppUsageConfig) {
        val reelConfig = ReelUsageConfig(config.isDailyUniform, config.uniformLimit)
        config.dailyLimits.copyInto(reelConfig.dailyLimits)
        viewModel.saveReelUsageConfig(reelConfig)
    }
}
