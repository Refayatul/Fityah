package com.refayatul.fityah.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*
import com.refayatul.fityah.R
import com.refayatul.fityah.data.models.MindfulMessageConfig
import com.refayatul.fityah.utils.TimeTools
import com.refayatul.fityah.utils.UsageStatsHelper

class MindfulMessageOverlayManager(private val context: Context) {

    private var overlayView: View? = null
    var isOverlayVisible = false
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var updateJob: Job? = null

    private var sessionStartTime = 0L
    private var textView: TextView? = null

    private val usageHelper = UsageStatsHelper(context)

    @SuppressLint("InflateParams")
    fun startDisplaying(pkgName: String, config: MindfulMessageConfig) {
        if (!isOverlayVisible || overlayView == null) {
            setupView(config)
            startTicker(pkgName, config)
        }
    }

    private fun setupView(config: MindfulMessageConfig) {
        sessionStartTime = System.currentTimeMillis()
        overlayView = LayoutInflater.from(context).inflate(R.layout.mindfulmsg_overlay, null)
        textView = overlayView?.findViewById<TextView>(R.id.mindful_txt)

        val r = (config.bgColor shr 16) and 0xFF
        val g = (config.bgColor shr 8) and 0xFF
        val b = config.bgColor and 0xFF
        val alpha = (config.bgOpacity * 255 / 100)

        textView?.apply {
            textSize = config.textSize
            setTextColor(Color.argb(config.textOpacity * 255 / 100, 255, 255, 255))
            setBackgroundColor(Color.argb(alpha, r, g, b))
            setPadding(32, 32, 32, 32)
        }

        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * config.positionX).toInt()
            y = (screenHeight * config.positionY).toInt()
        }
        layoutParams = params

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.addView(overlayView, params)
        isOverlayVisible = true

        overlayView?.viewTreeObserver?.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                overlayView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                val vw = overlayView?.width ?: 0
                val vh = overlayView?.height ?: 0
                params.x = ((screenWidth * config.positionX) - vw / 2f)
                    .toInt().coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
                params.y = ((screenHeight * config.positionY) - vh / 2f)
                    .toInt().coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
                try {
                    windowManager?.updateViewLayout(overlayView, params)
                } catch (_: Exception) {}
            }
        })
    }

    private fun startTicker(pkgName: String, config: MindfulMessageConfig) {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (isActive) {
                val todayStats = withContext(Dispatchers.IO) { usageHelper.getForegroundStatsByRelativeDay(0) }
                val appStat = todayStats.find { it.packageName == pkgName }

                val appUsageToday = TimeTools.formatTime(appStat?.totalTime ?: 0, false)
                val totalScreenTime = TimeTools.formatTime(todayStats.sumOf { it.totalTime }, false)

                val liveSessionMs = System.currentTimeMillis() - sessionStartTime
                val totalSeconds = liveSessionMs / 1000
                val mins = totalSeconds / 60
                val secs = totalSeconds % 60
                val liveSessionStr = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"

                val formatted = config.messages
                    .replace("{app_usage_today}", appUsageToday)
                    .replace("{screentime_today}", totalScreenTime)
                    .replace("{live_session_duration}", liveSessionStr)

                textView?.text = formatted

                delay(1000)
            }
        }
    }

    fun removeOverlay() {
        updateJob?.cancel()
        if (overlayView != null && windowManager != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {}
            overlayView = null
            isOverlayVisible = false
        }
    }
}
