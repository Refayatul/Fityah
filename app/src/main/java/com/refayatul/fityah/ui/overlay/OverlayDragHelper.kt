package com.refayatul.fityah.ui.overlay

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.refayatul.fityah.R

object OverlayDragHelper {

    val PRESET_COLORS = intArrayOf(
        0x000000,
        0x1A1A2E,
        0x0D2818,
        0x2A0D1A,
        0x2A2A3A,
        0xFFFFFF
    )

    fun buildColorChips(
        container: ViewGroup,
        fragment: Fragment,
        onColorSelected: (index: Int) -> Unit
    ): List<View> {
        val density = fragment.resources.displayMetrics.density
        val sizePx = (40 * density).toInt()
        val marginPx = (8 * density).toInt()
        return PRESET_COLORS.mapIndexed { index, color ->
            FrameLayout(fragment.requireContext()).apply {
                layoutParams = ViewGroup.MarginLayoutParams(sizePx, sizePx).apply {
                    marginEnd = marginPx
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF))
                    setStroke((2 * density).toInt(), Color.TRANSPARENT)
                }
                setOnClickListener { onColorSelected(index) }
                container.addView(this)
            }
        }
    }

    fun refreshChipSelection(chips: List<View>, selectedIndex: Int, density: Float) {
        chips.forEachIndexed { i, chip ->
            val bg = chip.background as? GradientDrawable ?: return@forEachIndexed
            val strokeColor = if (i == selectedIndex) Color.parseColor("#83D5C5") else Color.TRANSPARENT
            bg.setStroke((3 * density).toInt(), strokeColor)
        }
    }

    /**
     * Shows a full-screen scrim on the activity's decorView with the real overlay widget,
     * allowing the user to drag it to the desired position and confirm with OK.
     *
     * Positions are saved as fractions of dm.widthPixels / dm.heightPixels, matching
     * the coordinate space used by WindowManager-based overlay managers.
     *
     * Returns the scrim view; caller must remove it from decorView in onDestroyView.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showDragOverlay(
        fragment: Fragment,
        layoutResId: Int,
        positionX: Float,
        positionY: Float,
        setupWidget: (View) -> Unit,
        onPositionSaved: (x: Float, y: Float) -> Unit,
        onDismiss: () -> Unit
    ): View {
        val activity = fragment.requireActivity()
        val decorView = activity.window.decorView as FrameLayout
        val dm = fragment.resources.displayMetrics

        val scrim = FrameLayout(fragment.requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(180, 0, 0, 0))
        }

        scrim.addView(TextView(fragment.requireContext()).apply {
            text = fragment.getString(R.string.position_hint)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(32, 0, 32, 0)
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            it.topMargin = (56 * dm.density).toInt()
        })

        val widget = LayoutInflater.from(fragment.requireContext()).inflate(layoutResId, scrim, false)
        setupWidget(widget)
        scrim.addView(widget, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // decorLoc[1] is the y-offset of the decorView origin in screen coordinates
        // (equals statusBarHeight for non-edge-to-edge activities).
        // We need this to convert between screen-absolute positions (used by overlay managers
        // via dm.widthPixels/heightPixels) and decorView-relative positions (used by widget.x/y).
        val decorLoc = IntArray(2)

        widget.post {
            decorView.getLocationOnScreen(decorLoc)
            widget.x = (dm.widthPixels * positionX - widget.width / 2f - decorLoc[0])
                .coerceIn(0f, (scrim.width - widget.width).toFloat().coerceAtLeast(0f))
            widget.y = (dm.heightPixels * positionY - widget.height / 2f - decorLoc[1])
                .coerceIn(0f, (scrim.height - widget.height).toFloat().coerceAtLeast(0f))
        }

        var downOffsetX = 0f
        var downOffsetY = 0f
        widget.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // rawX/rawY are screen-absolute; v.x/y are decorView-relative.
                    // The mixed-space offset is stable throughout the gesture and
                    // correctly tracks movement regardless of the decorView offset.
                    downOffsetX = event.rawX - v.x
                    downOffsetY = event.rawY - v.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    v.x = (event.rawX - downOffsetX)
                        .coerceIn(0f, (scrim.width - v.width).toFloat().coerceAtLeast(0f))
                    v.y = (event.rawY - downOffsetY)
                        .coerceIn(0f, (scrim.height - v.height).toFloat().coerceAtLeast(0f))
                    true
                }
                else -> false
            }
        }

        scrim.addView(MaterialButton(fragment.requireContext()).apply {
            text = fragment.getString(android.R.string.ok)
            setOnClickListener {
                // Use screen-absolute coordinates to match the overlay manager's coordinate space.
                val loc = IntArray(2)
                widget.getLocationOnScreen(loc)
                if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                    val posXSaved = (loc[0] + widget.width / 2f) / dm.widthPixels
                    val posYSaved = (loc[1] + widget.height / 2f) / dm.heightPixels
                    onPositionSaved(posXSaved.coerceIn(0f, 1f), posYSaved.coerceIn(0f, 1f))
                }
                decorView.removeView(scrim)
                onDismiss()
            }
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).also {
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            it.bottomMargin = (32 * dm.density).toInt()
        })

        decorView.addView(scrim)
        return scrim
    }
}
