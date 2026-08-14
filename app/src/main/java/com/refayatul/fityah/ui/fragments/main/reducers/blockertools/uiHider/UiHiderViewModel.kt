package com.refayatul.fityah.ui.fragments.main.reducers.blockertools.uiHider

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.refayatul.fityah.blockers.uihider.UiHider
import com.refayatul.fityah.blockers.uihider.script.Parser
import com.refayatul.fityah.data.models.UiHiderConfig
import com.refayatul.fityah.data.models.UiHiderScript
import com.refayatul.fityah.hardcoded.isPresetUiHiderScript
import com.refayatul.fityah.hardcoded.normalized
import com.refayatul.fityah.utils.DataStoreManager

class UiHiderViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = DataStoreManager(application)

    val config: StateFlow<UiHiderConfig> = dataStoreManager.settings
        .map { it.uiHiderConfig.normalized() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiHiderConfig())

    /**
     * Every change is a transform applied inside DataStore's atomic read-modify-write, never a
     * whole-config snapshot from this ViewModel. The editor runs in its own activity with its own
     * ViewModel instance, so snapshot writes raced each other and dropped previously saved scripts.
     */
    private suspend fun update(transform: (UiHiderConfig) -> UiHiderConfig) {
        dataStoreManager.updateUiHiderConfig(transform)
        requestRefresh()
    }

    private fun requestRefresh() {
        getApplication<Application>().sendBroadcast(
            Intent(UiHider.INTENT_ACTION_REFRESH_UI_HIDER).setPackage(getApplication<Application>().packageName)
        )
    }

    fun setIsActive(isActive: Boolean) {
        viewModelScope.launch { update { it.copy(isActive = isActive) } }
    }

    fun setScriptEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            update { config ->
                if (isPresetUiHiderScript(id)) {
                    val ids = if (enabled) (config.enabledPresetIds + id).distinct() else config.enabledPresetIds - id
                    config.copy(enabledPresetIds = ids)
                } else {
                    config.copy(scripts = config.scripts.map { if (it.id == id) it.copy(isEnabled = enabled) else it })
                }
            }
        }
    }

    /** Insert a new user script or replace an existing one with the same id. Presets are read only. */
    suspend fun upsertScript(script: UiHiderScript) {
        if (isPresetUiHiderScript(script.id)) return
        update { config ->
            val scripts = if (config.scripts.any { it.id == script.id }) {
                config.scripts.map { if (it.id == script.id) script else it }
            } else {
                config.scripts + script
            }
            config.copy(scripts = scripts)
        }
    }

    suspend fun deleteScript(id: String) {
        update { config -> config.copy(scripts = config.scripts.filterNot { it.id == id }) }
    }

    fun newScriptId(): String = "uihider_${System.currentTimeMillis()}"

    /** Compile the source to surface syntax errors; returns the error message, or null if valid. */
    fun validate(source: String): String? = try {
        Parser.parse(source)
        null
    } catch (e: Exception) {
        e.message ?: "Invalid script"
    }
}
