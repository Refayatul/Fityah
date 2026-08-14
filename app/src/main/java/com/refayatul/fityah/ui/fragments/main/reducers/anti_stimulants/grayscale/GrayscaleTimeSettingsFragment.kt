package com.refayatul.fityah.ui.fragments.main.reducers.anti_stimulants.grayscale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.refayatul.fityah.data.models.AppTimeConfig
import com.refayatul.fityah.databinding.FragmentGrayscaleTimeSettingsBinding
import com.refayatul.fityah.ui.fragments.main.reducers.blockertools.shared.BaseTimeSettingsFragment

class GrayscaleTimeSettingsFragment : BaseTimeSettingsFragment() {

    companion object {
        const val FRAGMENT_ID = "grayscale_time_settings"
    }

    private val viewModel: GrayscaleViewModel by activityViewModels()

    override val daysOfWeek = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View =
        FragmentGrayscaleTimeSettingsBinding.inflate(inflater, container, false).root

    override fun getTimeConfig(): AppTimeConfig = viewModel.currentTimeConfig

    override fun saveTimeConfig(config: AppTimeConfig) {
        viewModel.currentTimeConfig = config
    }
}
