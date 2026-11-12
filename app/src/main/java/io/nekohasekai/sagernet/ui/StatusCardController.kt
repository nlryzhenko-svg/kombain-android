package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.ImageView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

/**
 * Kombain status card on the servers screen: big server name, connection
 * switch, live speed and a tap-to-test latency check. Replaces NekoBox's
 * floating button + bottom stats bar.
 */
class StatusCardController(root: View, private val activity: MainActivity) {

    private val card: MaterialCardView = root.findViewById(R.id.status_card)
    private val dot: View = root.findViewById(R.id.status_dot)
    private val title: TextView = root.findViewById(R.id.status_title)
    private val server: TextView = root.findViewById(R.id.status_server)
    private val detail: TextView = root.findViewById(R.id.status_detail)
    private val knob: View = root.findViewById(R.id.status_knob)
    private val knobIcon: ImageView = root.findViewById(R.id.status_knob_icon)
    private val progress: KnobSpinner = root.findViewById(R.id.status_progress)
    private val pulse: View = root.findViewById(R.id.status_pulse)
    private val graph: io.nekohasekai.sagernet.widget.SpeedGraphView = root.findViewById(R.id.status_graph)
    private var pulseAnim: android.animation.Animator? = null
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateTitle()
            if (state == BaseService.State.Connected) ticker.postDelayed(this, 1000)
        }
    }

    companion object {
        /** elapsedRealtime when the tunnel came up; survives fragment re-creation */
        private var connectedSince = 0L
    }

    private var state = BaseService.State.Idle
    private var testResult: String? = null
    private var testing = false
    private var searching = false

    fun setSearching(on: Boolean) {
        searching = on
        if (on) {
            progress.isVisible = true
            setPulsing(true)
            title.setText(R.string.kb_searching)
            detail.typeface = android.graphics.Typeface.DEFAULT
            detail.setText(R.string.kb_test_running)
        } else onState(state)
    }

    init {
        knob.setOnClickListener {
            val s = DataStore.serviceState
            if (s == BaseService.State.Connecting || s == BaseService.State.Stopping) return@setOnClickListener
            activity.toggleService()
        }
        card.setOnClickListener {
            if (DataStore.serviceState.connected) runTest() else knob.performClick()
        }
        onState(DataStore.serviceState)
        refreshProfile()
    }

    fun onState(newState: BaseService.State) {
        if (newState != state) testResult = null
        state = newState
        val ctx = card.context
        val connected = newState == BaseService.State.Connected
        val busy = newState == BaseService.State.Connecting || newState == BaseService.State.Stopping

        card.setCardBackgroundColor(
            ContextCompat.getColor(ctx, if (connected || busy) R.color.kombain_card else R.color.kombain_card_off)
        )
        dot.backgroundTintList = ColorStateList.valueOf(
            when {
                connected -> 0xFF2FE3A6.toInt()
                busy -> 0xFFFFD166.toInt()
                else -> 0xFF9AA9AC.toInt()
            }
        )
        if (connected) {
            if (connectedSince == 0L) {
                connectedSince = SystemClock.elapsedRealtime()
                celebrate()
            }
        } else {
            connectedSince = 0L
            graph.clear()
        }
        graph.isVisible = connected
        setPulsing(busy)
        ticker.removeCallbacks(tick)
        updateTitle()
        if (connected) ticker.postDelayed(tick, 1000)
        progress.isVisible = busy || testing || searching
        knob.alpha = if (busy) 0.7f else 1f
        (knobIcon.background.mutate() as? android.graphics.drawable.GradientDrawable)?.setColor(
            if (connected) 0xFF2FE3A6.toInt() else 0x33FFFFFF
        )
        knobIcon.setColorFilter(if (connected) 0xFF06372A.toInt() else 0xFFFFFFFF.toInt())
        if (!connected) {
            detail.typeface = android.graphics.Typeface.DEFAULT
            detail.text = ctx.getString(
                when {
                    newState == BaseService.State.Connecting -> R.string.kb_hint_connecting
                    DataStore.kbSmartProto -> R.string.kb_hint_off_auto
                    else -> R.string.kb_hint_off
                }
            )
        } else if (testResult == null) {
            onSpeed(0, 0)
        }
        refreshProfile()
    }

    private fun updateTitle() {
        val ctx = card.context
        if (searching) {
            title.setText(R.string.kb_searching)
            return
        }
        title.text = when (state) {
            BaseService.State.Connected -> {
                val sec = ((SystemClock.elapsedRealtime() - connectedSince) / 1000).coerceAtLeast(0)
                val t = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
                else "%02d:%02d".format(sec / 60, sec % 60)
                ctx.getString(R.string.kb_connected) + " · " + t
            }
            BaseService.State.Connecting -> ctx.getString(R.string.kb_connecting)
            BaseService.State.Stopping -> ctx.getString(R.string.kb_stopping)
            else -> ctx.getString(R.string.kb_disconnected)
        }
    }

    fun release() {
        ticker.removeCallbacks(tick)
        pulseAnim?.cancel()
    }

    /** Expanding ring around the knob while connecting. */
    private fun setPulsing(on: Boolean) {
        if (on == (pulseAnim != null)) return
        pulseAnim?.cancel(); pulseAnim = null
        pulse.alpha = 0f
        if (!on) return
        val sx = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.79f, 1f)
        val sy = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.79f, 1f)
        val a = android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f)
        pulseAnim = android.animation.ObjectAnimator.ofPropertyValuesHolder(pulse, sx, sy, a).apply {
            duration = 1200
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.DecelerateInterpolator()
            start()
        }
    }

    /** Small bounce of the knob when the tunnel comes up. */
    private fun celebrate() {
        knob.animate().cancel()
        knob.scaleX = 0.85f; knob.scaleY = 0.85f
        knob.animate().scaleX(1f).scaleY(1f).setDuration(450)
            .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }

    fun onSpeed(tx: Long, rx: Long) {
        if (state != BaseService.State.Connected) return
        val ctx = card.context
        detail.typeface = android.graphics.Typeface.MONOSPACE
        graph.add(rx, tx)
        val speed = "↓ ${Formatter.formatFileSize(ctx, rx)}/с   ↑ ${Formatter.formatFileSize(ctx, tx)}/с"
        detail.text = testResult?.let { "$it   $speed" } ?: speed
    }

    fun refreshProfile() {
        runOnDefaultDispatcher {
            val id = if (DataStore.serviceState.started && DataStore.currentProfile > 0)
                DataStore.currentProfile else DataStore.selectedProxy
            val profile = runCatching { ProfileManager.getProfile(id) }.getOrNull()
            onMainDispatcher {
                server.text = profile?.displayName() ?: card.context.getString(R.string.kb_no_server)
            }
        }
    }

    private fun runTest() {
        if (testing) return
        testing = true
        progress.isVisible = true
        detail.setText(R.string.connection_test_testing)
        runOnDefaultDispatcher {
            val result = try {
                card.context.getString(R.string.kb_ping, activity.urlTest())
            } catch (e: Exception) {
                Logs.w(e)
                card.context.getString(R.string.kb_ping_fail)
            }
            onMainDispatcher {
                testing = false
                testResult = result
                progress.isVisible = state == BaseService.State.Connecting
                detail.text = result
            }
        }
    }
}
