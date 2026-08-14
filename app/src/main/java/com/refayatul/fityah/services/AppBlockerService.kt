package com.refayatul.fityah.services

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import com.refayatul.fityah.BuildConfig
import com.refayatul.fityah.CrashLogger
import com.refayatul.fityah.R
import com.refayatul.fityah.anti_stimulants.AutoDnd
import com.refayatul.fityah.anti_stimulants.GrayScaleFilter
import com.refayatul.fityah.anti_stimulants.MindfulMessage
import com.refayatul.fityah.blockers.AntiUninstallBlocker
import com.refayatul.fityah.blockers.AppBlocker
import com.refayatul.fityah.blockers.FocusModeBlocker
import com.refayatul.fityah.blockers.KeywordBlocker
import com.refayatul.fityah.blockers.ReelBlocker
import com.refayatul.fityah.blockers.ReelScriptRunner
import com.refayatul.fityah.blockers.uihider.NodePicker
import com.refayatul.fityah.blockers.uihider.UiHider
import com.refayatul.fityah.trackers.AppUsageTracker
import com.refayatul.fityah.trackers.ReelsCountTracker
import com.refayatul.fityah.trackers.ReelUsageTracker
import com.refayatul.fityah.trackers.WebsiteObservation
import com.refayatul.fityah.trackers.WebsiteUsageTracker
import com.refayatul.fityah.ui.overlay.ReelsOverlayManager

import android.net.VpnService
import com.refayatul.fityah.data.models.AppBlockerWarningScreenConfig
import com.google.gson.Gson
import com.refayatul.fityah.ui.activity.WarningActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@Suppress("DEPRECATION")
class AppBlockerService : BaseBlockingService() {

    private val appBlocker: AppBlocker = AppBlocker()
    private val focusModeBlocker = FocusModeBlocker()
    private val autoDnd = AutoDnd()
    private val reelBlocker = ReelBlocker()
    private val reelScriptRunner = ReelScriptRunner()
    private var keywordBlocker = KeywordBlocker()
    private val uiHider = UiHider()
    private val nodePicker = NodePicker()
    private val antiUninstallBlocker = AntiUninstallBlocker()

    private var grayScaleFilter = GrayScaleFilter()

    // Usage tracking, which used to live in its own accessibility service, now
    // runs here so the user only has to grant one service.
    private val reelsOverlayManager by lazy { ReelsOverlayManager(this) }
    private val reelsCountTracker = ReelsCountTracker()
    private val reelUsageTracker = ReelUsageTracker()
    private val mindfulMessage = MindfulMessage()
    private val websiteUsageTracker = WebsiteUsageTracker()
    private val appUsageTracker = AppUsageTracker()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val eventChannel = Channel<AccessibilityEvent>(Channel.CONFLATED) { droppedEvent ->
        droppedEvent.recycle()
    }
    private val websiteObservationChannel = Channel<WebsiteObservation?>(Channel.CONFLATED)

    private lateinit var crashLogger: CrashLogger

    private var lastVpnCheck = 0L
    private fun checkVpnTamper() {
        val now = System.currentTimeMillis()
        if (now - lastVpnCheck < 10000) return
        lastVpnCheck = now

        serviceScope.launch {
            val settings = dataStoreManager.settings.first()
            if (settings.vpnConfig.isEnabled && VpnService.prepare(this@AppBlockerService) != null) {
                showVpnBypassWarning()
            }
        }
    }

    private fun showVpnBypassWarning() {
        if (!isDelayOver(5000)) return
        
        val warningConfig = AppBlockerWarningScreenConfig(
            message = "DNS Filter is bypassed by another VPN or disabled. Please restore the connection to maintain your focus boundaries.",
            isProceedDisabled = true
        )
        
        val intent = Intent(this, WarningActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("mode", com.refayatul.fityah.Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
            putExtra("result_id", "vpn_bypass")
            putExtra("warning_config", Gson().toJson(warningConfig))
        }
        startActivity(intent)
    }

    fun syncDndState() {
        val autoDndActive = autoDnd.isDndRequested()
        val manualFocusDndActive = focusModeBlocker.isDndRequested()
        com.refayatul.fityah.utils.DndHelper.applyDndState(this, autoDndActive || manualFocusDndActive)
    }

    override fun onCreate() {
        super.onCreate()
        crashLogger = CrashLogger(this)
        try {
            rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this)
        } catch (e: Exception) {
            Log.e("Shizuku", "Failed to bind Shizuku in non-provider process", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        super.onAccessibilityEvent(event)

        checkVpnTamper()

        try {
            if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
                antiUninstallBlocker.doAntiUninstallCheck(event)
            }
            appBlocker.doAppBlockerCheck(event)
            grayScaleFilter.doGrayscaleCheck(event)
            focusModeBlocker.doFocusModeCheck(event)
        } catch (t: Throwable) {
            Log.e("error", t.message ?: "Unknown error")
            crashLogger.logNonFatalError(Exception(t))
        }

        try {
            appUsageTracker.onEvent(event)
            mindfulMessage.onEvent(event)
        } catch (error: Exception) {
            Log.e("Usage Tracking error", error.toString())
        }

        val eventCopy = AccessibilityEvent.obtain(event)
        val result = eventChannel.trySend(eventCopy)

        // If the channel is closed or rejected it, recycle immediately
        if (result.isFailure) {
            eventCopy.recycle()
        }
    }

    override fun onInterrupt() {
    }

    private fun startBackgroundWorker() {
        serviceScope.launch {
            for (event in eventChannel) {
                try {
                    websiteUsageTracker.onEvent(event)
                    val reelComparator = reelScriptRunner.detect(event)
                    reelUsageTracker.onEvent(event, reelComparator)
                    reelsCountTracker.onEvent(event, reelComparator)
                    reelBlocker.doViewBlockerCheck(event, reelComparator)
                    keywordBlocker.checkIfUnsupportedBrowser(event)
                    if (BuildConfig.SUPPORTS_UI_HIDER) {
                        uiHider.doUiHiderCheck(event)
                    }
                } catch (t: Throwable) {
                    // Don't log normal coroutine cancellations as crashes
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("Blocker", "Background worker error", t)
                } finally {
                    event.recycle()
                }
            }
        }
        serviceScope.launch(Dispatchers.IO) {
            for (observation in websiteObservationChannel) {
                try {
                    keywordBlocker.onWebsiteObserved(observation)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("KeywordBlocker", "Website observation check failed", t)
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        appBlocker.setupAppBlocker(this)
        focusModeBlocker.setupFocusMode(this)
        autoDnd.setup(this)
        reelBlocker.setupBlocker(this)
        reelScriptRunner.setup(this)
        keywordBlocker.setupBlocker(this)
        grayScaleFilter.setup(this)
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            uiHider.setupBlocker(this)
            nodePicker.setupBlocker(this)
        }
        if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
            antiUninstallBlocker.setupBlocker(this)
        }

        reelUsageTracker.setup(this)
        reelsCountTracker.setup(this, reelsOverlayManager, reelUsageTracker::onReelCounted)
        mindfulMessage.setup(this)
        websiteUsageTracker.setup(this) { observation ->
            websiteObservationChannel.trySend(observation)
        }
        appUsageTracker.setup(this)
        com.refayatul.fityah.utils.UsageStatsCleaner.watch(this)

        focusModeBlocker.setupReceivers()
        appBlocker.setupReceivers()
        reelBlocker.setupReceivers()
        keywordBlocker.setupReceivers()
        grayScaleFilter.setupReceivers()
        if (BuildConfig.SUPPORTS_UI_HIDER) {
            uiHider.setupReceivers()
            nodePicker.setupReceivers()
        }
        reelsCountTracker.setupReceivers()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.please_provide_draw_over_other_apps),
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)
        }

        startBackgroundWorker()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {

            focusModeBlocker.removeReceivers()
            autoDnd.stop()
            reelBlocker.removeReceivers()
            appBlocker.onDestroy()
            keywordBlocker.removeReceivers()
            grayScaleFilter.unregisterReceivers()
            if (BuildConfig.SUPPORTS_UI_HIDER) {
                uiHider.removeReceivers()
                nodePicker.removeReceivers()
            }
            if (BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
                antiUninstallBlocker.onDestroy()
            }
            mindfulMessage.onDestroy()
            reelsCountTracker.onDestroy()
            reelUsageTracker.onDestroy()
            websiteUsageTracker.onDestroy()
            appUsageTracker.onDestroy()

            eventChannel.close()
            websiteObservationChannel.close()
            serviceScope.cancel()
        }catch (_: Exception){}
    }
}
