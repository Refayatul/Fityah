package com.refayatul.fityah.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColor
import com.google.android.material.color.MaterialColors

class WeeklyBarGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class DayData(
        val label: String,
        val value: Float,       // hours
        val dateMillis: Long
    )

    private var days: List<DayData> = emptyList()
    private var selectedIndex: Int = -1
    private var onDaySelected: ((DayData) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val barRadius = 8f
    private val barRect = RectF()
    private val minBarHeight = 6f // dp

    fun setData(data: List<DayData>, selected: Int = -1) {
        days = data
        selectedIndex = selected
        resolveColors()
        invalidate()
    }

    fun setOnDaySelectedListener(listener: (DayData) -> Unit) {
        onDaySelected = listener
    }

    fun setSelectedIndex(index: Int) {
        selectedIndex = index
        invalidate()
    }

    private fun resolveColors() {
        val colorPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
        val colorOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)

        barPaint.color = ColorUtils.setAlphaComponent(colorPrimary, 192)
        selectedBarPaint.color = colorPrimary
        labelPaint.color = colorOnSurfaceVariant
        labelPaint.textSize = 11f * resources.displayMetrics.density
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resolveColors()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (180 * resources.displayMetrics.density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return

        val density = resources.displayMetrics.density
        val count = days.size
        val labelAreaHeight = 28f * density  // space for day labels
        val titleAreaHeight = 24f * density  // space for "WEEKLY ACTIVITY"
        val topPadding = 16f * density
        val bottomPadding = 8f * density

        val availableWidth = width.toFloat() - paddingStart - paddingEnd
        val barAreaWidth = availableWidth * 0.85f
        val leftOffset = (availableWidth - barAreaWidth) / 2f + paddingStart

        val barWidth = barAreaWidth / count * 0.35f
        val gapWidth = barAreaWidth / count

        val chartTop = topPadding
        val chartBottom = height - labelAreaHeight - titleAreaHeight - bottomPadding
        val maxBarHeight = chartBottom - chartTop
        val maxValue = days.maxOfOrNull { it.value } ?: 1f
        val effectiveMax = if (maxValue == 0f) 1f else maxValue

        val minBarPx = minBarHeight * density

        for (i in days.indices) {
            val cx = leftOffset + gapWidth * i + gapWidth / 2f
            val ratio = days[i].value / effectiveMax
            var barHeight = maxBarHeight * ratio
            if (days[i].value > 0f && barHeight < minBarPx) barHeight = minBarPx

            val left = cx - barWidth / 2f
            val right = cx + barWidth / 2f
            val top = chartBottom - barHeight
            val bottom = chartBottom

            barRect.set(left, top, right, bottom)
            val paint = if (i == selectedIndex) selectedBarPaint else barPaint
            canvas.drawRoundRect(barRect, barRadius * density, barRadius * density, paint)
        }

        val labelY = chartBottom + labelAreaHeight * 0.65f
        for (i in days.indices) {
            val cx = leftOffset + gapWidth * i + gapWidth / 2f
            val paint = if (i == selectedIndex) {
                Paint(labelPaint).apply {
                    color = selectedBarPaint.color
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            } else {
                labelPaint
            }
            canvas.drawText(days[i].label, cx, labelY, paint)
        }

    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && days.isNotEmpty()) {
            val availableWidth = width.toFloat() - paddingStart - paddingEnd
            val barAreaWidth = availableWidth * 0.85f
            val leftOffset = (availableWidth - barAreaWidth) / 2f + paddingStart
            val gapWidth = barAreaWidth / days.size

            val touchX = event.x
            for (i in days.indices) {
                val cx = leftOffset + gapWidth * i + gapWidth / 2f
                if (touchX >= cx - gapWidth / 2f && touchX <= cx + gapWidth / 2f) {
                    selectedIndex = i
                    invalidate()
                    onDaySelected?.invoke(days[i])
                    performClick()
                    return true
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
