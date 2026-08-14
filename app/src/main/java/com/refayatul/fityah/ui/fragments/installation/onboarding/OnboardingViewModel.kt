package com.refayatul.fityah.ui.fragments.installation.onboarding

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class OnboardingViewModel : ViewModel() {

    private val _estimatedHours = MutableLiveData<Int>(4) // Default guess
    val estimatedHours: LiveData<Int> = _estimatedHours

    private val _targetAppPackage = MutableLiveData<String?>(null)
    val targetAppPackage: LiveData<String?> = _targetAppPackage

    private val _dailyLimitMinutes = MutableLiveData<Long>(30L)
    val dailyLimitMinutes: LiveData<Long> = _dailyLimitMinutes

    fun setEstimatedHours(hours: Int) {
        _estimatedHours.value = hours
    }

    fun setTargetApp(packageName: String) {
        _targetAppPackage.value = packageName
    }

    fun skipTargetApp() {
        _targetAppPackage.value = null
    }

    fun setDailyLimit(limit: Long) {
        _dailyLimitMinutes.value = limit
    }
}
