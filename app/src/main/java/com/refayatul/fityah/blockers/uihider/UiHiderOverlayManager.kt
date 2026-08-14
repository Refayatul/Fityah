package com.refayatul.fityah.blockers.uihider

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.refayatul.fityah.services.BaseBlockingService

/** One overlay rectangle requested by a script run. [color] of null adopts the dark-mode default. */
data class DrawCommand(
    val key: String,
    val bounds: Rect,
    val color: Int?,
    val blockTouches: Boolean
)

/**
 * Manages the set of accessibility overlays drawn by UIHider scripts, using the same
 * keyed diff strategy as ViewBlocker: each run produces a fresh [DrawCommand] list, overlays
 * whose key is gone are removed, unchanged ones are reused, and changed ones are relaid out.
 * All WindowManager work happens on the main thread.
 */
class UiHiderOverlayManager(private val service: BaseBlockingService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private data class Overlay(val view: View, val bounds: Rect)

    private val overlays = HashMap<String, Overlay>(32)

    companion object {
        private const val MAX_OVERLAY_COUNT = 100
    }

    /** Replace the on-screen overlay set with [commands]. Safe to call from any thread. */
    fun apply(commands: List<DrawCommand>) {
        handler.post {
            try {
                val wanted = HashSet<String>(commands.size)
                for (cmd in commands) {
                    wanted.add(cmd.key)
                    drawOrUpdate(cmd)
                }
                val iter = overlays.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    if (entry.key !in wanted) {
                        removeView(entry.value.view)
                        iter.remove()
                    }
                }
            } catch (e: Exception) {
                Log.e("UiHider", "Error applying overlays", e)
            }
        }
    }

    fun clearAll() {
        handler.post {
            for ((_, overlay) in overlays) removeView(overlay.view)
            overlays.clear()
        }
    }

    private fun drawOrUpdate(cmd: DrawCommand) {
        val bounds = cmd.bounds
        if (bounds.isEmpty) return
        val color = cmd.color ?: defaultColor()

        val existing = overlays[cmd.key]
        if (existing != null) {
            if (existing.bounds != bounds) {
                try {
                    windowManager.updateViewLayout(existing.view, layoutParams(bounds, cmd.blockTouches))
                    existing.bounds.set(bounds)
                } catch (e: Exception) {
                    Log.e("UiHider", "Failed to update overlay", e)
                }
            }
            existing.view.setBackgroundColor(color)
            return
        }

        if (overlays.size >= MAX_OVERLAY_COUNT) return

        try {
            val view = View(service).apply { setBackgroundColor(color) }
            windowManager.addView(view, layoutParams(bounds, cmd.blockTouches))
            overlays[cmd.key] = Overlay(view, Rect(bounds))
        } catch (e: Exception) {
            Log.e("UiHider", "Failed to add overlay", e)
        }
    }

    private fun removeView(view: View) {
        try {
            if (view.parent != null) windowManager.removeView(view)
        } catch (_: Exception) {}
    }

    private fun layoutParams(bounds: Rect, blockTouches: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!blockTouches) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            bounds.width(), bounds.height(), bounds.left, bounds.top,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun defaultColor(): Int {
        val dark = (service.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (dark) Color.BLACK else Color.WHITE
    }
}
