package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/** Tiny live speed chart for the status card (last ~60 seconds, download + upload). */
class SpeedGraphView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val size = 60
    private val down = LongArray(size)
    private val up = LongArray(size)
    private var count = 0

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.6f
        color = 0xFFFFFFFF.toInt()
        strokeJoin = Paint.Join.ROUND
    }
    private val upLine = Paint(line).apply { color = 0x99FFFFFF.toInt(); strokeWidth = resources.displayMetrics.density }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun add(rx: Long, tx: Long) {
        System.arraycopy(down, 1, down, 0, size - 1)
        System.arraycopy(up, 1, up, 0, size - 1)
        down[size - 1] = rx
        up[size - 1] = tx
        if (count < size) count++
        invalidate()
    }

    fun clear() {
        down.fill(0); up.fill(0); count = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        fill.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    private fun build(values: LongArray, max: Float, closed: Boolean): Path {
        path.reset()
        val w = width.toFloat(); val h = height.toFloat()
        val step = w / (size - 1)
        for (i in 0 until size) {
            val x = i * step
            val y = h - (values[i] / max) * (h - line.strokeWidth) - line.strokeWidth / 2
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (closed) { path.lineTo(w, h); path.lineTo(0f, h); path.close() }
        return path
    }

    override fun onDraw(canvas: Canvas) {
        if (count < 2) return
        val max = maxOf(down.maxOrNull() ?: 0, up.maxOrNull() ?: 0, 32 * 1024L).toFloat() * 1.15f
        canvas.drawPath(build(down, max, true), fill)
        canvas.drawPath(build(down, max, false), line)
        canvas.drawPath(build(up, max, false), upLine)
    }
}
