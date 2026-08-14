package com.refayatul.fityah.anti_stimulants

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.refayatul.fityah.data.models.AutoDndGroup
import com.refayatul.fityah.data.models.TimeInterval
import com.refayatul.fityah.services.AppBlockerService
import com.refayatul.fityah.services.BaseBlockingService
import java.util.Calendar

class AutoDnd {
    private lateinit var service: AppBlockerService
    private var autoDndGroups: List<AutoDndGroup> = emptyList()
    private var settingsJob: Job? = null
    private var tickerJob: Job? = null

    @Volatile private var isDndRequested = false

    fun setup(service: BaseBlockingService) {
        if (service !is AppBlockerService) return
        this.service = service
        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                autoDndGroups = settings.autoDndGroups
                updateDndRequest()
            }
        }

        // Periodic check every minute to handle schedule transitions
        tickerJob?.cancel()
        tickerJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                updateDndRequest()
                delay(60000)
            }
        }
    }

    private fun updateDndRequest() {
        val now = Calendar.getInstance()
        val calDay = now.get(Calendar.DAY_OF_WEEK)
        // 0=Mon, 1=Tue, ..., 6=Sun
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        var shouldBeOn = false
        for (group in autoDndGroups) {
            if (!group.autoTurnOnDnd) continue

            val config = group.timeConfig
            val intervals = if (config.isEveryday) config.everydayIntervals else config.dailyIntervals[currentDay]

            if (intervals != null && intervals.any { isWithinInterval(currentMinutes, it) }) {
                shouldBeOn = true
                break
            }
        }

        if (isDndRequested != shouldBeOn) {
            isDndRequested = shouldBeOn
            service.syncDndState()
        }
    }

    fun isDndRequested(): Boolean = isDndRequested

    private fun isWithinInterval(currentMinutes: Int, interval: TimeInterval): Boolean {
        val start = interval.startHour * 60 + interval.startMinute
        val end = interval.endHour * 60 + interval.endMinute
        return if (start <= end) {
            currentMinutes in start until end
        } else {
            // Overnight interval
            currentMinutes >= start || currentMinutes < end
        }
    }

    fun stop() {
        settingsJob?.cancel()
        tickerJob?.cancel()
    }
}
