package com.refayatul.fityah.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.refayatul.fityah.R
import com.refayatul.fityah.services.BaseBlockingService

class FrictionOverlayManager(private val service: BaseBlockingService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var timer: CountDownTimer? = null

    fun show() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        val view = LayoutInflater.from(service).inflate(R.layout.overlay_friction, null)
        val backBtn = view.findViewById<Button>(R.id.btn_friction_back)
        
        backBtn.setOnClickListener {
            service.pressBack()
            service.pressHome()
            hide()
        }

        overlayView = view
        
        try {
            windowManager.addView(overlayView, params)
            startCountdown(backBtn)
        } catch (e: Exception) {
            overlayView = null
        }
    }

    private fun startCountdown(button: Button) {
        timer?.cancel()
        timer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000) + 1
                button.text = service.getString(R.string.friction_back_countdown, seconds)
            }

            override fun onFinish() {
                button.isEnabled = true
                button.text = service.getString(R.string.friction_back)
            }
        }.start()
    }

    fun hide() {
        timer?.cancel()
        timer = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            overlayView = null
        }
    }

    fun isShowing(): Boolean = overlayView != null
}
