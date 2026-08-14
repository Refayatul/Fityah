package com.refayatul.fityah.trackers

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.refayatul.fityah.CrashLogger
import com.refayatul.fityah.data.db.AppDatabase
import com.refayatul.fityah.data.db.ReelUsageStatsDao
import com.refayatul.fityah.hardcoded.ReelAppConfig.Companion.reelData
import com.refayatul.fityah.services.BaseBlockingService
import com.refayatul.fityah.utils.TimeTools

/** Records time spent on detected reel screens and confirmed reel transitions per app. */
class ReelUsageTracker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private lateinit var dao: ReelUsageStatsDao
    private lateinit var crashLogger: CrashLogger
    private lateinit var powerManager: PowerManager
    private var heartbeatJob: Job? = null
    private var activePackage: String? = null
    private var lastTickElapsedMs = 0L

    fun setup(service: BaseBlockingService) {
        dao = AppDatabase.getInstance(service).reelUsageStatsDao()
        crashLogger = CrashLogger(service)
        powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(HEARTBEAT_MS)
                stateMutex.withLock {
                    if (powerManager.isInteractive) {
                        flushActiveTime()
                    } else {
                        flushActiveTime()
                        activePackage = null
                    }
                }
            }
        }
    }

    suspend fun onEvent(event: AccessibilityEvent, dynamicComparator: String?) {
        val packageName = event.packageName?.toString()
        val isReelScreen = packageName != null && packageName in reelData && dynamicComparator != null
        stateMutex.withLock {
            if (isReelScreen) {
                if (activePackage != packageName) {
                    flushActiveTime()
                    activePackage = packageName
                    lastTickElapsedMs = SystemClock.elapsedRealtime()
                }
            } else if (activePackage != null) {
                flushActiveTime()
                activePackage = null
            }
        }
    }

    fun onReelCounted(packageName: String) {
        scope.launch {
            try {
                dao.incrementCount(
                    TimeTools.getCurrentDate(),
                    packageName,
                    System.currentTimeMillis()
                )
            } catch (error: Exception) {
                crashLogger.logNonFatalError(error)
            }
        }
    }

    fun onDestroy() {
        heartbeatJob?.cancel()
        runBlocking(Dispatchers.IO) {
            stateMutex.withLock { flushActiveTime() }
        }
        scope.cancel()
    }

    private suspend fun flushActiveTime() {
        val packageName = activePackage ?: return
        val nowElapsed = SystemClock.elapsedRealtime()
        val deltaMs = (nowElapsed - lastTickElapsedMs).coerceIn(0L, MAX_FLUSH_MS)
        lastTickElapsedMs = nowElapsed
        if (deltaMs < MIN_RECORDED_MS) return
        try {
            dao.addTime(
                TimeTools.getCurrentDate(),
                packageName,
                deltaMs,
                System.currentTimeMillis()
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            crashLogger.logNonFatalError(error)
        }
    }

    private companion object {
        const val HEARTBEAT_MS = 5_000L
        const val MAX_FLUSH_MS = 10_000L
        const val MIN_RECORDED_MS = 250L
    }
}
