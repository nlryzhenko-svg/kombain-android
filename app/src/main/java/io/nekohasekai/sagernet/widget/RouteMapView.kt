package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Live route of the traffic: phone → (entry server) → server → internet.
 * Glowing dots run along the links — towards the phone for download, away from it for upload —
 * as fast and as dense as the traffic really is. When the tunnel comes up, the links are drawn from
 * the phone to the internet one after another.
 */
class RouteMapView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    class Node(val emoji: String, val title: String, val subtitle: String = "")

    private val d = resources.displayMetrics.density
    private val onCard = 0xFFF1FFF9.toInt()

    private var nodes: List<Node> = emptyList()
    private var ping: String? = null

    // bytes per second, smoothed
    private var rx = 0.0
    private var tx = 0.0
    private var rxTarget = 0.0
    private var txTarget = 0.0

    /** 0…nodes-1: how far the route has been drawn (the connect animation) */
    private var reveal = 0f
    private var revealAnim: ValueAnimator? = null

    private class Dot(var pos: Float, var lane: Int, val down: Boolean, val size: Float, val speedJitter: Float)

    private val dots = ArrayList<Dot>()
    private var lastFrame = 0L
    private var spawnDebtDown = 0.0
    private var spawnDebtUp = 0.0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND; color = onCard
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x66FFFFFF
    }
    private val emojiPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f * d; textAlign = Paint.Align.CENTER }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onCard; textSize = 11.5f * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onCard; alpha = 190; textSize = 10f * d; textAlign = Paint.Align.CENTER
    }
    private val pingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF06372A.toInt(); textSize = 10f * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val pingBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2FE3A6.toInt() }

    private val radius get() = 21f * d
    private val cy get() = paddingTop + radius + 4 * d

    fun setRoute(route: List<Node>, animate: Boolean) {
        val changed = route.map { it.title + it.emoji + it.subtitle } != nodes.map { it.title + it.emoji + it.subtitle }
        val grew = route.size != nodes.size
        nodes = route
        if (grew) dots.clear()
        if (animate && (changed || reveal < route.size - 1)) startReveal() else if (!animate && revealAnim?.isRunning != true) reveal = (route.size - 1).toFloat()
        invalidate()
    }

    val nodeCount get() = nodes.size

    fun setPing(text: String?) {
        ping = text
        invalidate()
    }

    fun setRates(txBytes: Long, rxBytes: Long) {
        txTarget = txBytes.toDouble()
        rxTarget = rxBytes.toDouble()
        kick()
    }

    /** animate (connected) or freeze */
    var running = false
        set(value) { field = value; if (value) kick() }

    fun reset() {
        revealAnim?.cancel()
        reveal = 0f
        dots.clear()
        rx = 0.0; tx = 0.0; rxTarget = 0.0; txTarget = 0.0
        ping = null
        invalidate()
    }

    private fun startReveal() {
        revealAnim?.cancel()
        val to = (nodes.size - 1).coerceAtLeast(0).toFloat()
        revealAnim = ValueAnimator.ofFloat(0f, to).apply {
            duration = (550L * to.toLong()).coerceAtLeast(400)
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun kick() {
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (paddingTop + paddingBottom + 2 * radius + 4 * d + 40 * d).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    private fun nodeX(i: Int): Float {
        val n = nodes.size
        val left = paddingLeft + 34 * d
        val right = width - paddingRight - 34 * d
        return if (n <= 1) width / 2f else left + (right - left) * i / (n - 1)
    }

    /** dots per second along the route for a traffic rate: 0 → a slow trickle, 10 MB/s → a stream */
    private fun intensity(rate: Double): Double = if (rate < 512) 0.6 else min(28.0, 0.6 + 4.0 * ln(rate / 512.0 + 1))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.size < 2 || width == 0) return
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min(0.05f, (now - lastFrame) / 1e9f)
        lastFrame = now

        // smooth the rates so dots don't jump with every 1-second sample
        rx += (rxTarget - rx) * min(1.0, dt * 3.0)
        tx += (txTarget - tx) * min(1.0, dt * 3.0)

        val links = nodes.size - 1
        val y = cy
        val shown = reveal.coerceIn(0f, links.toFloat())

        // links: faint full line + the drawn part
        for (i in 0 until links) {
            val x1 = nodeX(i) + radius + 3 * d
            val x2 = nodeX(i + 1) - radius - 3 * d
            linePaint.alpha = 45
            canvas.drawLine(x1, y, x2, y, linePaint)
            val part = (shown - i).coerceIn(0f, 1f)
            if (part > 0f) {
                linePaint.alpha = 150
                canvas.drawLine(x1, y, x1 + (x2 - x1) * part, y, linePaint)
            }
        }

        // dots
        if (shown >= links - 0.001f) {
            spawnDebtDown += intensity(rx) * dt
            spawnDebtUp += intensity(tx) * dt
            while (spawnDebtDown >= 1) { spawnDebtDown -= 1; dots += Dot(1f, Random.nextInt(3), true, 1.6f + Random.nextFloat() * 1.6f, 0.8f + Random.nextFloat() * 0.4f) }
            while (spawnDebtUp >= 1) { spawnDebtUp -= 1; dots += Dot(0f, Random.nextInt(3), false, 1.3f + Random.nextFloat() * 1.0f, 0.8f + Random.nextFloat() * 0.4f) }
            // the whole route takes 2.2 s when idle, down to 0.6 s at full speed
            val base = 1f / 2.2f
            val boostDown = (intensity(rx) / 28.0).toFloat() * 1.2f
            val boostUp = (intensity(tx) / 28.0).toFloat() * 1.2f
            val it = dots.iterator()
            val startX = nodeX(0) + radius
            val endX = nodeX(links) - radius
            while (it.hasNext()) {
                val p = it.next()
                val v = (base + if (p.down) boostDown else boostUp) * p.speedJitter
                p.pos += if (p.down) -v * dt else v * dt
                if (p.pos < 0f || p.pos > 1f) { it.remove(); continue }
                val x = startX + (endX - startX) * p.pos
                // skip the part hidden behind a node circle
                var inside = false
                for (k in 0..links) if (kotlin.math.abs(x - nodeX(k)) < radius + 1 * d) inside = true
                if (inside) continue
                val laneY = y + (p.lane - 1) * 3.2f * d + if (p.down) 0f else 0f
                val col = if (p.down) 0xFF2FE3A6.toInt() else 0xFFFFD166.toInt()
                glowPaint.color = col; glowPaint.alpha = 70
                canvas.drawCircle(x, laneY, p.size * 2.4f * d, glowPaint)
                dotPaint.color = if (p.down) 0xFFE9FFF7.toInt() else 0xFFFFF4D6.toInt()
                canvas.drawCircle(x, laneY, p.size * d, dotPaint)
            }
            if (dots.size > 260) dots.subList(0, dots.size - 260).clear()
        }

        // nodes
        for (i in nodes.indices) {
            val x = nodeX(i)
            val lit = shown >= i - 0.02f
            circlePaint.alpha = if (lit) 60 else 25
            canvas.drawCircle(x, y, radius, circlePaint)
            ringPaint.alpha = if (lit) 140 else 50
            canvas.drawCircle(x, y, radius, ringPaint)
            emojiPaint.alpha = if (lit) 255 else 90
            val fm = emojiPaint.fontMetrics
            canvas.drawText(nodes[i].emoji, x, y - (fm.ascent + fm.descent) / 2, emojiPaint)
            val maxW = if (nodes.size > 1) (width - paddingLeft - paddingRight) / nodes.size.toFloat() - 4 * d else width.toFloat()
            titlePaint.alpha = if (lit) 255 else 110
            val title = TextUtils.ellipsize(nodes[i].title, titlePaint, maxW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(title, x, y + radius + 15 * d, titlePaint)
            if (nodes[i].subtitle.isNotEmpty()) {
                subPaint.alpha = if (lit) 190 else 80
                val sub = TextUtils.ellipsize(nodes[i].subtitle, subPaint, maxW, TextUtils.TruncateAt.END).toString()
                canvas.drawText(sub, x, y + radius + 28 * d, subPaint)
            }
        }

        // ping badge above the first link
        ping?.let { p ->
            if (shown >= links - 0.001f) {
                val x = (nodeX(0) + nodeX(1)) / 2
                val w = pingPaint.measureText(p) + 12 * d
                val top = y - radius - 2 * d
                val rect = RectF(x - w / 2, top, x + w / 2, top + 15 * d)
                canvas.drawRoundRect(rect, 7.5f * d, 7.5f * d, pingBg)
                val fm = pingPaint.fontMetrics
                canvas.drawText(p, x, rect.centerY() - (fm.ascent + fm.descent) / 2, pingPaint)
            }
        }

        // runs while it is on screen: an idle tunnel still shows a slow trickle
        if (running && isShown) postInvalidateOnAnimation() else lastFrame = 0L
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) kick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        revealAnim?.cancel()
    }
}
