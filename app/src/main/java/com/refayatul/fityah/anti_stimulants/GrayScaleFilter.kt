package com.refayatul.fityah.anti_stimulants

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.refayatul.fityah.blockers.BaseBlocker
import com.refayatul.fityah.data.models.GrayscaleGroup
import com.refayatul.fityah.data.models.TimeInterval
import com.refayatul.fityah.services.BaseBlockingService
import com.refayatul.fityah.utils.GrayscaleControl
import com.refayatul.fityah.utils.getCurrentKeyboardPackageName
import java.util.Calendar

class GrayScaleFilter : BaseBlocker() {

    companion object {
        const val INTENT_ACTION_REFRESH_GRAYSCALE = "com.refayatul.fityah.refresh.grayscale"
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private lateinit var service: BaseBlockingService
    private val grayscaleControl = GrayscaleControl()
    private var ignoredGrayScalePackages: List<String> = listOf()
    private var lastPackageName: String? = null
    private var settingsJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var grayscaleGroups: List<GrayscaleGroup> = emptyList()

    fun doGrayscaleCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val currentPackageName = event.packageName?.toString() ?: return
        
        // Skip check if it's the same package or system UI or keyboard
        // We don't skip Fityah here because we want to disable grayscale when user is in the app
        if (currentPackageName == lastPackageName || 
            currentPackageName == "com.android.systemui" ||
            ignoredGrayScalePackages.contains(currentPackageName)) return

        lastPackageName = currentPackageName

        val now = Calendar.getInstance()
        val calDay = now.get(Calendar.DAY_OF_WEEK)
        // Monday=0, ..., Sunday=6 mapping (matches GrayscaleTimeSettingsFragment)
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        var shouldGrayscale = false

        // Don't grayscale Fityah itself to ensure usability
            for (group in grayscaleGroups) {
                if (!group.isActive) continue

                if (group.packages.contains(currentPackageName)) {
                    val config = group.timeConfig
                    val intervals = if (config.isEveryday) {
                        config.everydayIntervals
                    } else {
                        config.dailyIntervals[currentDay]
                    }

                    if (intervals == null || intervals.isEmpty()) {
                        shouldGrayscale = true
                        break
                    } else {
                        val isInInterval = intervals.any { isWithinInterval(currentMinutes, it) }
                        if (isInInterval) {
                            shouldGrayscale = true
                            break
                        }
                    }
                }
            }

        if (shouldGrayscale) {
            Log.d("GrayScaleFilter", "Enabling monochrome for $currentPackageName")
            grayscaleControl.enableGrayscale(service)
        } else {
            Log.d("GrayScaleFilter", "Disabling monochrome for $currentPackageName")
            grayscaleControl.disableGrayscale(service)
        }
    }

    private fun isWithinInterval(currentMinutes: Int, interval: TimeInterval): Boolean {
        val start = interval.startHour * 60 + interval.startMinute
        val end = interval.endHour * 60 + interval.endMinute
        return if (start <= end) {
            currentMinutes in start until end
        } else {
            currentMinutes >= start || currentMinutes < end
        }
    }

    fun setup(service: BaseBlockingService) {
        this.service = service
        ignoredGrayScalePackages = listOf(
            getCurrentKeyboardPackageName(service) ?: "com.google.android.inputmethod.latin",
            service.packageName,
            "com.google.android.apps.wellbeing"
        )
        
        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                grayscaleGroups = settings.grayscaleGroups
                Log.d("GrayScaleFilter", "Grayscale Groups loaded: $grayscaleGroups")
                
                // Force a check for the current package to apply changes immediately
                handler.post {
                    try {
                        val currentPackage = service.rootInActiveWindow?.packageName?.toString()
                        if (currentPackage != null) {
                            lastPackageName = null // Reset to force re-check
                            val dummyEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                            dummyEvent.packageName = currentPackage
                            doGrayscaleCheck(dummyEvent)
                            dummyEvent.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("GrayScaleFilter", "Error in forced re-check", e)
                    }
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_GRAYSCALE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun unregisterReceivers() {
        try {
            service.unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        settingsJob?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_GRAYSCALE -> setup(service)
            }
        }
    }
}
