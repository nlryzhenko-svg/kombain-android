package io.nekohasekai.sagernet.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Even, constant-speed spinner drawn exactly on the knob ring (same centre and radius),
 * instead of Material's grow-and-shrink arc.
 */
class KnobSpinner @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val stroke = 3f * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var angle = 0f
    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // the ring drawable's stroke is centred half a stroke inside the bounds
        val half = stroke / 2
        val side = minOf(w, h).toFloat()
        val left = (w - side) / 2 + half
        val top = (h - side) / 2 + half
        oval.set(left, top, left + side - stroke, top + side - stroke)
        // white reads on both the dark idle card and the green active one
        val c = 0xFFFFFFFF.toInt()
        val clear = c and 0x00FFFFFF
        // tail fades in, head is solid
        paint.shader = SweepGradient(w / 2f, h / 2f, intArrayOf(clear, clear, c), floatArrayOf(0f, 0.55f, 0.95f))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.rotate(angle, width / 2f, height / 2f)
        // start a little past 0°: the round cap there would pick up the solid end of the sweep gradient
        canvas.drawArc(oval, 8f, 332f, false, paint)
        canvas.restore()
    }

    private fun sync() {
        if (isShown && isAttachedToWindow) {
            if (!anim.isStarted) anim.start()
        } else anim.cancel()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        sync()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }
}
