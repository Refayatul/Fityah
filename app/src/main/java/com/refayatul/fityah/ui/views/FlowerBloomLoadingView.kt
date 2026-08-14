package com.refayatul.fityah.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.google.android.material.color.MaterialColors
import kotlin.random.Random

/**
 * A loading indicator that procedurally grows a fresh, randomized flower on every
 * cycle. Each bloom picks new petal counts, proportions, rotation and colours from
 * the Material palette, blooms outward, holds briefly, then fades so the next one
 * can grow.
 */
class FlowerBloomLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val petalPath = Path()

    private var animator: ValueAnimator? = null
    private var lastProgress = 1f

    // Per bloom randomized design. Regenerated at the start of every cycle.
    private var petalCount = 6
    private var petalWidthRatio = 0.55f
    private var startSpin = 30f
    private var baseRotation = 0f
    private var outerColor = 0
    private var innerColor = 0
    private var centerColor = 0
    private var palette = intArrayOf()

    init {
        buildPalette()
        randomizeBloom()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Only animate while actually on screen, and always begin with a fresh
        // bloom so the flower never appears mid fade out when it pops in.
        if (isShown) startAnimation() else stopAnimation()
    }

    private fun buildPalette() {
        palette = intArrayOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary),
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary),
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
        )
    }

    private fun randomizeBloom() {
        petalCount = Random.nextInt(5, 9)
        petalWidthRatio = Random.nextDouble(0.45, 0.7).toFloat()
        startSpin = Random.nextDouble(20.0, 55.0).toFloat() * if (Random.nextBoolean()) 1f else -1f
        baseRotation = Random.nextDouble(0.0, 360.0).toFloat()

        outerColor = palette[Random.nextInt(palette.size)]
        innerColor = palette[Random.nextInt(palette.size)]
        centerColor = palette[Random.nextInt(palette.size)]
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return

        randomizeBloom()
        lastProgress = 0f

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                // A new cycle has started, grow a fresh flower.
                if (progress < lastProgress) randomizeBloom()
                lastProgress = progress
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val p = lastProgress

        // Bloom outward over the first 60%, then hold, then fade away at the end.
        val bloomRaw = (p / 0.6f).coerceIn(0f, 1f)
        val bloomEase = 1f - (1f - bloomRaw) * (1f - bloomRaw)
        val alpha = when {
            p < 0.12f -> p / 0.12f
            p < 0.82f -> 1f
            else -> 1f - (p - 0.82f) / 0.18f
        }.coerceIn(0f, 1f)
        if (alpha <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f * 0.85f

        canvas.save()
        canvas.translate(cx, cy)
        // Spin gently into the final resting angle as it blooms.
        canvas.rotate(baseRotation + startSpin * (1f - bloomEase))

        val outerLen = radius
        val innerLen = radius * 0.6f
        val step = 360f / petalCount

        // Outer ring of petals.
        drawPetalRing(canvas, petalCount, step, 0f, outerLen, bloomRaw, alpha, outerColor)
        // Inner ring, offset by half a step for a layered look.
        drawPetalRing(canvas, petalCount, step, step / 2f, innerLen, bloomRaw, alpha, innerColor)

        // Center disc.
        paint.color = centerColor
        paint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(0f, 0f, radius * 0.18f * bloomEase, paint)

        canvas.restore()
    }

    private fun drawPetalRing(
        canvas: Canvas,
        count: Int,
        step: Float,
        angleOffset: Float,
        length: Float,
        bloomRaw: Float,
        alpha: Float,
        color: Int
    ) {
        val staggerSpan = 0.4f
        val perPetal = staggerSpan / count

        paint.color = color
        for (i in 0 until count) {
            val petalStart = i * perPetal
            val local = ((bloomRaw - petalStart) / (1f - staggerSpan)).coerceIn(0f, 1f)
            val scale = 1f - (1f - local) * (1f - local)
            if (scale <= 0f) continue

            paint.alpha = (alpha * 255).toInt()
            canvas.save()
            canvas.rotate(angleOffset + step * i)
            canvas.scale(scale, scale)
            buildPetal(length, length * petalWidthRatio)
            canvas.drawPath(petalPath, paint)
            canvas.restore()
        }
    }

    // Builds a teardrop petal pointing up (toward negative Y) from the center.
    private fun buildPetal(length: Float, width: Float) {
        val halfW = width / 2f
        petalPath.reset()
        petalPath.moveTo(0f, 0f)
        petalPath.cubicTo(halfW, -length * 0.35f, halfW * 0.7f, -length, 0f, -length)
        petalPath.cubicTo(-halfW * 0.7f, -length, -halfW, -length * 0.35f, 0f, 0f)
        petalPath.close()
    }
}
