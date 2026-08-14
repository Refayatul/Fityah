package com.refayatul.fityah.ui.fragments.main.reducers.blockertools.appBlocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.refayatul.fityah.data.models.AppTimeConfig
import com.refayatul.fityah.databinding.FragmentAppBlockerTimeRangeSettingsBinding
import com.refayatul.fityah.ui.fragments.main.reducers.blockertools.shared.BaseTimeSettingsFragment

class TimeBasedSettingsFragment : BaseTimeSettingsFragment() {

    companion object {
        const val FRAGMENT_ID = "time_based_settings"
    }

    private val viewModel: AppBlockerSettingViewModel by activityViewModels()

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View =
        FragmentAppBlockerTimeRangeSettingsBinding.inflate(inflater, container, false).root

    override fun getTimeConfig(): AppTimeConfig = viewModel.currentTimeConfig

    override fun saveTimeConfig(config: AppTimeConfig) {
        viewModel.currentTimeConfig = config
    }
}
