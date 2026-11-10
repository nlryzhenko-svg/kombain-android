package io.nekohasekai.sagernet.bg

import android.net.NetworkCapabilities
import android.os.Build
import android.widget.Toast
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.kombain.AutoPick
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kombain connection watchdog (runs in the VPN process).
 *
 * While connected it sends a test request through the tunnel every 30 s. When the tunnel
 * stops answering but the phone itself is online, it first resets connections, then
 * reconnects — or, with «Автовыбор сервера», moves to the fastest live server of the group.
 */
@OptIn(DelicateCoroutinesApi::class)
object Watchdog {

    private const val INTERVAL = 30_000L
    private const val RETRY = 5_000L
    private const val GRACE = 15_000L
    private const val FAILS_TO_ACT = 3

    private var job: Job? = null
    private val recoveries = ArrayDeque<Long>()

    fun start(service: BaseService.Interface) {
        stop()
        if (!DataStore.kbWatchdog) return
        job = GlobalScope.launch(Dispatchers.IO) {
            try {
                loop(service)
            } catch (e: Exception) {
                if (isActive) Logs.w(e)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun log(msg: String) = Logs.w("[Kombain] сторож: $msg")

    private suspend fun loop(service: BaseService.Interface) {
        // after several recoveries in a short time, back off so we don't flap
        val now = System.currentTimeMillis()
        while (recoveries.isNotEmpty() && now - recoveries.first() > 10 * 60_000) recoveries.removeFirst()
        delay(if (recoveries.size >= 3) 120_000 else GRACE)

        var fails = 0
        while (true) {
            if (service.data.state != BaseService.State.Connected) return
            if (!DataStore.kbWatchdog) return
            if (deviceIdle() || !phoneOnline()) {
                fails = 0
                delay(INTERVAL)
                continue
            }
            if (probeTunnel(service)) {
                if (fails > 0) log("связь восстановилась")
                fails = 0
                delay(INTERVAL)
                continue
            }
            fails++
            log("нет ответа через туннель ($fails/$FAILS_TO_ACT)")
            if (fails == FAILS_TO_ACT - 1) {
                // cheap fix first: drop stuck connections
                runCatching { Libcore.resetAllConnections(true) }
            }
            if (fails >= FAILS_TO_ACT) {
                // is it the server or the phone's own internet?
                if (!directInternet()) {
                    log("у телефона нет интернета — жду")
                    fails = 0
                    delay(INTERVAL)
                    continue
                }
                recover(service)
                return
            }
            delay(RETRY)
        }
    }

    private fun probeTunnel(service: BaseService.Interface): Boolean {
        val box = service.data.proxy?.box ?: return true
        return try {
            Libcore.urlTest(box, DataStore.connectionTestURL, 5000)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun deviceIdle(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && SagerNet.power.isDeviceIdleMode

    private fun phoneOnline(): Boolean {
        val net = SagerNet.underlyingNetwork ?: return true
        val caps = SagerNet.connectivity.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Request outside the tunnel, over the phone's real network. */
    private fun directInternet(): Boolean {
        val net = SagerNet.underlyingNetwork ?: return true
        for (url in listOf("http://connectivitycheck.gstatic.com/generate_204", "https://ya.ru/")) {
            try {
                val conn = net.openConnection(URL(url)) as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    private suspend fun recover(service: BaseService.Interface) {
        recoveries.addLast(System.currentTimeMillis())
        val currentId = DataStore.currentProfile.takeIf { it > 0 } ?: DataStore.selectedProxy
        val current = SagerDatabase.proxyDao.getById(currentId)
        val name = current?.displayName() ?: "?"

        // own server with a subscription: the keys may have been changed from another device — fetch them first
        val group = current?.let { SagerDatabase.groupDao.getById(it.groupId) }
        val sub = group?.subscription
        if (sub != null && sub.link.orEmpty().contains("#kbpin=") &&
            System.currentTimeMillis() / 1000 - (sub.lastUpdated ?: 0) > 300
        ) {
            log("обновляю подписку «${group.displayName()}» — вдруг ключи сменили")
            runCatching { io.nekohasekai.sagernet.group.GroupUpdater.executeUpdate(group, false) }.onFailure { Logs.w(it) }
        }

        var message = app.getString(R.string.kb_wd_reconnect, name)
        if (DataStore.kbAutoServer) {
            val others = AutoPick.groupOf(currentId).filter { it.id != currentId }
            if (others.isNotEmpty()) {
                log("ищу живой сервер в группе (${others.size})")
                val best = AutoPick.test(others).best?.first
                if (best != null) {
                    DataStore.selectedProxy = best.id
                    message = app.getString(R.string.kb_wd_switch, name, best.displayName())
                } else {
                    message = app.getString(R.string.kb_wd_none)
                }
            }
        }
        log(message)
        withContext(Dispatchers.Main) {
            Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            if (service.data.state == BaseService.State.Connected) service.stopRunner(true)
        }
    }
}
