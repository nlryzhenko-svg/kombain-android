package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private val switch: SwitchMaterial = root.findViewById(R.id.status_switch)
    private val progress: LinearProgressIndicator = root.findViewById(R.id.status_progress)

    private var state = BaseService.State.Idle
    private var testResult: String? = null
    private var testing = false
    private var ignoreSwitch = false

    init {
        switch.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            val running = DataStore.serviceState.canStop
            if (checked != running) activity.toggleService()
        }
        card.setOnClickListener {
            if (DataStore.serviceState.connected) runTest() else activity.toggleService()
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
        title.setText(
            when (newState) {
                BaseService.State.Connected -> R.string.kb_connected
                BaseService.State.Connecting -> R.string.kb_connecting
                BaseService.State.Stopping -> R.string.kb_stopping
                else -> R.string.kb_disconnected
            }
        )
        progress.isVisible = busy || testing
        ignoreSwitch = true
        switch.isChecked = newState.canStop
        switch.isEnabled = !busy
        ignoreSwitch = false
        if (!connected) {
            detail.text = ctx.getString(
                if (newState == BaseService.State.Connecting) R.string.kb_hint_connecting else R.string.kb_hint_off
            )
        } else if (testResult == null) {
            onSpeed(0, 0)
        }
        refreshProfile()
    }

    fun onSpeed(tx: Long, rx: Long) {
        if (state != BaseService.State.Connected) return
        val ctx = card.context
        val speed = "↓ ${Formatter.formatFileSize(ctx, rx)}/s   ↑ ${Formatter.formatFileSize(ctx, tx)}/s"
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
