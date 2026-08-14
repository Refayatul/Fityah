package com.refayatul.fityah.ui.fragments.main.usage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.refayatul.fityah.data.models.Settings
import com.refayatul.fityah.utils.DataStoreManager
import android.content.Intent
import com.refayatul.fityah.blockers.AppBlocker

class SetupShortcutViewModel(application: Application) : AndroidViewModel(application) {
    private var dataStoreManager = DataStoreManager(application)
    
    private val _settings = MutableStateFlow<Settings?>(null)
    val settings: StateFlow<Settings?> = _settings
    
    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest {
                _settings.value = it
            }
        }
    }
    
    fun toggleAppGroup(groupId: String, isActive: Boolean) {
        viewModelScope.launch {
            val currentSettings = _settings.value ?: return@launch
            val updatedGroups = currentSettings.blockedAppGroups.map {
                if (it.id == groupId) it.copy(isActive = isActive) else it
            }
            dataStoreManager.updateAppGroups(updatedGroups)
            getApplication<Application>().sendBroadcast(Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER))
        }
    }

    fun toggleGrayscaleGroup(groupId: String, isActive: Boolean) {
        viewModelScope.launch {
            val currentSettings = _settings.value ?: return@launch
            val updatedGroups = currentSettings.grayscaleGroups.map {
                if (it.groupId == groupId) it.copy(isActive = isActive) else it
            }
            dataStoreManager.updateGrayscaleGroups(updatedGroups)
            getApplication<Application>().sendBroadcast(Intent("com.refayatul.fityah.ACTION_REFRESH_GRAYSCALE"))
        }
    }
}
