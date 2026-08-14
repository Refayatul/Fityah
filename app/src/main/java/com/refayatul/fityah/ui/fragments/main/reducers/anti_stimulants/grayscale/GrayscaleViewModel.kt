package com.refayatul.fityah.ui.fragments.main.reducers.anti_stimulants.grayscale

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.refayatul.fityah.anti_stimulants.GrayScaleFilter
import com.refayatul.fityah.data.models.AppTimeConfig
import com.refayatul.fityah.data.models.GrayscaleGroup
import com.refayatul.fityah.data.models.TimeInterval
import com.refayatul.fityah.utils.DataStoreManager

class GrayscaleViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)
    
    private val _groups = MutableStateFlow<List<GrayscaleGroup>>(emptyList())
    val groups: StateFlow<List<GrayscaleGroup>> = _groups

    var currentTimeConfig: AppTimeConfig = AppTimeConfig()

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _groups.value = settings.grayscaleGroups
            }
        }
    }

    fun addGroup(group: GrayscaleGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentGroups = currentSettings.grayscaleGroups.toMutableList()
            currentGroups.add(group)
            updateGroups(currentGroups)
        }
    }

    fun removeGroup(group: GrayscaleGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentGroups = currentSettings.grayscaleGroups.toMutableList()
            currentGroups.remove(group)
            updateGroups(currentGroups)
        }
    }

    fun updateGroup(group: GrayscaleGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentGroups = currentSettings.grayscaleGroups.toMutableList()
            val index = currentGroups.indexOfFirst { it.groupId == group.groupId }
            if (index != -1) {
                currentGroups[index] = group
                updateGroups(currentGroups)
            }
        }
    }

    private fun updateGroups(newGroups: List<GrayscaleGroup>) {
        viewModelScope.launch {
            dataStoreManager.updateGrayscaleGroups(newGroups)
            requestGrayscaleRefresh()
        }
    }

    private fun requestGrayscaleRefresh() {
        val intent = Intent(GrayScaleFilter.INTENT_ACTION_REFRESH_GRAYSCALE)
        getApplication<Application>().sendBroadcast(intent)
    }
}
