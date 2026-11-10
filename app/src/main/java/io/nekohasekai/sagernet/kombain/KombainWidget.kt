package io.nekohasekai.sagernet.kombain

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

/** Home-screen widget: tap to connect / disconnect, shows the state and the server. */
class KombainWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        runOnDefaultDispatcher {
            try {
                push(ctx, DataStore.serviceState, null)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        /** Called from the VPN process on every state change. */
        fun update(ctx: Context, state: BaseService.State, profileName: String?) {
            runOnDefaultDispatcher { runCatching { push(ctx, state, profileName) } }
        }

        private fun push(ctx: Context, state: BaseService.State, profileName: String?) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, KombainWidget::class.java))
            if (ids.isEmpty()) return
            val name = profileName?.takeIf { it.isNotBlank() && it != "Idle" } ?: run {
                val id = if (state.started && DataStore.currentProfile > 0) DataStore.currentProfile else DataStore.selectedProxy
                runCatching { SagerDatabase.proxyDao.getById(id)?.displayName() }.getOrNull()
            } ?: ctx.getString(R.string.kb_no_server)
            val on = state == BaseService.State.Connected
            val busy = state == BaseService.State.Connecting || state == BaseService.State.Stopping
            val views = RemoteViews(ctx.packageName, R.layout.widget_kombain).apply {
                setInt(R.id.w_root, "setBackgroundResource", if (on || busy) R.drawable.kombain_widget_on else R.drawable.kombain_widget_off)
                setInt(R.id.w_knob, "setBackgroundResource", if (on) R.drawable.kombain_widget_knob_on else R.drawable.kombain_widget_knob_off)
                setInt(R.id.w_knob, "setColorFilter", if (on) 0xFF06372A.toInt() else 0xFFFFFFFF.toInt())
                setTextViewText(R.id.w_status, ctx.getString(when (state) {
                    BaseService.State.Connected -> R.string.kb_connected
                    BaseService.State.Connecting -> R.string.kb_connecting
                    BaseService.State.Stopping -> R.string.kb_stopping
                    else -> R.string.kb_disconnected
                }))
                setTextViewText(R.id.w_server, name)
                val toggle = PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, QuickToggleShortcut::class.java).setAction(Intent.ACTION_MAIN)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.w_root, toggle)
                setOnClickPendingIntent(R.id.w_knob, toggle)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
