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
import android.view.WindowManager.LayoutParams
import com.refayatul.fityah.data.models.ReelCounterOverlayConfig
import com.refayatul.fityah.databinding.OverlayUsageStatBinding

class ReelsOverlayManager(private val context: Context) {

    private var overlayView: View? = null
    var binding: OverlayUsageStatBinding? = null
    var isOverlayVisible = false
    private var windowManager: WindowManager? = null

    var reelsScrolledThisSession = 0

    @SuppressLint("InlinedApi")
    fun startDisplaying(config: ReelCounterOverlayConfig = ReelCounterOverlayConfig(), isVisible: Boolean = true) {
        if (overlayView != null || isOverlayVisible) return

        binding = OverlayUsageStatBinding.inflate(LayoutInflater.from(context))
        isOverlayVisible = true
        overlayView = binding?.root

        val r = (config.bgColor shr 16) and 0xFF
        val g = (config.bgColor shr 8) and 0xFF
        val b = config.bgColor and 0xFF
        val alpha = (config.bgOpacity * 255 / 100)

        binding?.root?.setBackgroundColor(Color.argb(alpha, r, g, b))
        binding?.reelCounter?.apply {
            visibility = if (isVisible) View.VISIBLE else View.GONE
            text = reelsScrolledThisSession.toString()
            textSize = config.textSize
            this.alpha = config.textOpacity / 100f
        }
        binding?.timeElapsedTxt?.visibility = View.GONE

        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        val layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or
                    LayoutParams.FLAG_NOT_TOUCHABLE or
                    LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * config.positionX).toInt()
            y = (screenHeight * config.positionY).toInt()
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.addView(overlayView, layoutParams)

        overlayView?.viewTreeObserver?.addOnGlobalLayoutListener(object :
            ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                overlayView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                val vw = overlayView?.width ?: 0
                val vh = overlayView?.height ?: 0
                layoutParams.x = ((screenWidth * config.positionX) - vw / 2f)
                    .toInt().coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
                layoutParams.y = ((screenHeight * config.positionY) - vh / 2f)
                    .toInt().coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
                try {
                    windowManager?.updateViewLayout(overlayView, layoutParams)
                } catch (_: Exception) {}
            }
        })
    }

    fun removeOverlay() {
        if (overlayView != null && windowManager != null) {
            windowManager?.removeView(overlayView)
            overlayView = null
            binding = null
            isOverlayVisible = false
        }
    }
}
