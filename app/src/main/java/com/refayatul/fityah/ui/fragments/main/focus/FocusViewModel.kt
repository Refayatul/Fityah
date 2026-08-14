package com.refayatul.fityah.ui.fragments.main.focus

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.refayatul.fityah.blockers.FocusModeBlocker
import com.refayatul.fityah.data.models.ManualFocusGroup
import com.refayatul.fityah.utils.DataStoreManager

class FocusViewModel(application: Application) : AndroidViewModel(application) {
    var newGroupSelectedApps = hashSetOf<String>()
    var newGroupSelectedKeywords = hashSetOf<String>()

    private val dataStoreManager = DataStoreManager(application)
    private val db = com.refayatul.fityah.data.db.AppDatabase.getInstance(application)
    private val statsDao = db.focusStatsDao()

    private val _groups = MutableStateFlow<List<com.refayatul.fityah.data.models.ManualFocusGroup>>(emptyList())
    val groups: StateFlow<List<com.refayatul.fityah.data.models.ManualFocusGroup>> = _groups

    val allSessions = statsDao.getAllSessionsFlow()

    private val _autoDndGroups = MutableStateFlow<List<com.refayatul.fityah.data.models.AutoDndGroup>>(emptyList())
    val autoDndGroups: StateFlow<List<com.refayatul.fityah.data.models.AutoDndGroup>> = _autoDndGroups
    
    private val prefs = application.getSharedPreferences("AppPreferences", android.content.Context.MODE_PRIVATE)
    var selectedMins = prefs.getInt("lastFocusDuration", 25)

    var selectedGroup : ManualFocusGroup? = null


    private val _currentRunningFocus = MutableStateFlow<Pair<String?, Long>>(Pair(null,0L))
    val currentRunningFocus: StateFlow<Pair<String?, Long>> = _currentRunningFocus

    private var timerJob: Job? = null
    private var _currentRunningTimer = MutableStateFlow<Long>(0L)
    var currentRunningTimer: StateFlow<Long> = _currentRunningTimer


    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _groups.value = settings.manualFocusGroups
                _autoDndGroups.value = settings.autoDndGroups
                _currentRunningFocus.value = settings.activeManualFocusGroupId

                if (selectedGroup == null && settings.manualFocusGroups.isNotEmpty()) {
                    val lastGroupId = prefs.getString("lastFocusGroupId", null)
                    val lastUsedGroup = settings.manualFocusGroups.find { it.groupId == lastGroupId }

                    if (lastUsedGroup != null) {
                        selectedGroup = lastUsedGroup
                    } else if (settings.manualFocusGroups.size == 1) {
                        selectedGroup = settings.manualFocusGroups[0]
                    }
                }

                if(settings.activeManualFocusGroupId.first != null) {
                    if (settings.activeManualFocusGroupId.second < System.currentTimeMillis()) {
                        forceStopFocus(wasMidwayExit = false)
                    } else {
                        requestFocusBlockerRefresh()
                    }
                }
            }
        }
    }


    fun updateGroups(newGroups: List<ManualFocusGroup>) {
        viewModelScope.launch {
            dataStoreManager.updateManualFocusGroups(newGroups)
        }
    }

    private fun requestFocusBlockerRefresh() {
        val intent = Intent(FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE)
        application.sendBroadcast(intent)
    }

    fun forceStopFocus(wasMidwayExit: Boolean = false){
        viewModelScope.launch {
            val runningSessions = statsDao.getRunningSessions()
            val now = System.currentTimeMillis()
            for (session in runningSessions) {
                if (wasMidwayExit) {
                    statsDao.update(session.copy(status = 2, actualEndTimeInMillis = now))
                } else {
                    val actEnd = if (session.estimatedEndTimeInMillis < now) session.estimatedEndTimeInMillis else now
                    statsDao.update(session.copy(status = 1, actualEndTimeInMillis = actEnd))
                }
            }
            dataStoreManager.setManualFocusStateToInactive()
            requestFocusBlockerRefresh()
        }
    }
    fun startFocusing() {
        if(selectedGroup == null) return
        prefs.edit()
            .putInt("lastFocusDuration", selectedMins)
            .putString("lastFocusGroupId", selectedGroup?.groupId)
            .apply()
        
        val durationMs = selectedMins * 60_000L
        val startTime = System.currentTimeMillis()
        val endTime = startTime + durationMs
        viewModelScope.launch {
            val session = com.refayatul.fityah.data.db.FocusStatsEntity(
                groupId = selectedGroup!!.groupId,
                startTimeInMillis = startTime,
                estimatedEndTimeInMillis = endTime,
                actualEndTimeInMillis = 0L,
                status = 0
            )
            statsDao.insert(session)
            
            dataStoreManager.setManualFocusStateToActive(selectedGroup!!.groupId, durationMs)
            requestFocusBlockerRefresh()
        }
    }
    fun addGroup(group: ManualFocusGroup) {
        val updatedGroups = _groups.value.toMutableList().apply { add(group) }
        updateGroups(updatedGroups)
    }

    fun removeGroup(group: ManualFocusGroup) {
        val currentGroups = _groups.value.toMutableList()
        currentGroups.remove(group)
        updateGroups(currentGroups)
    }

    fun updateGroup(group: ManualFocusGroup) {
        val currentGroups = _groups.value.toMutableList()
        val index = currentGroups.indexOfFirst { it.groupId == group.groupId }
        if (index != -1) {
            currentGroups[index] = group
            updateGroups(currentGroups)
        }
    }


    fun startTimer( endTime: Long) {
        // Stop any existing timer before starting a new one
        timerJob?.cancel()

        timerJob = viewModelScope.launch {

            while (System.currentTimeMillis() < endTime) {
                val remaining = endTime - System.currentTimeMillis()

                _currentRunningTimer.value = remaining

                delay(100L)
            }

            onTimerFinished()
        }
    }

    fun stopTimer() {
        timerJob?.cancel()
        _currentRunningTimer.value = 0L
    }

    private fun onTimerFinished() {
        _currentRunningTimer.value = 0L
    }
}
