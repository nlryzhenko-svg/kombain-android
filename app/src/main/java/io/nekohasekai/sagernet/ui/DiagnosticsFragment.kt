package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.kombain.AutoPick
import io.nekohasekai.sagernet.kombain.HopPorts
import io.nekohasekai.sagernet.kombain.NetMemory
import io.nekohasekai.sagernet.kombain.ServerNode
import io.nekohasekai.sagernet.kombain.ServerStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/** «Диагностика сети»: what the current network lets through, in plain words. */
class DiagnosticsFragment : ToolbarFragment(R.layout.layout_diagnostics) {

    private lateinit var rows: LinearLayout
    private lateinit var verdict: TextView
    private lateinit var action: MaterialButton
    private lateinit var go: MaterialButton
    private var running = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.diag_title)
        rows = view.findViewById(R.id.diag_rows)
        verdict = view.findViewById(R.id.diag_verdict)
        action = view.findViewById(R.id.diag_action)
        action.setTextColor(ContextCompat.getColor(requireContext(), R.color.kombain_text))
        go = view.findViewById(R.id.diag_go)
        go.setOnClickListener { if (!running) run() }
    }

    private inner class Row(title: String) {
        val view = TextView(requireContext()).apply {
            textSize = 14.5f
            setPadding(0, dp2px(9), 0, dp2px(9))
            setTextColor(ContextCompat.getColor(context, R.color.kombain_text))
        }
        private val title = title

        init {
            set(null, "проверяю…")
            rows.addView(view)
        }

        fun set(ok: Boolean?, detail: String) {
            val mark = when (ok) { true -> "✓"; false -> "✗"; null -> "…" }
            view.text = "$mark  $title — $detail"
            view.setTextColor(ContextCompat.getColor(view.context, when (ok) {
                true -> R.color.kombain_text
                false -> R.color.kombain_danger
                null -> R.color.kombain_text2
            }))
        }
    }

    private fun httpOk(url: String): Boolean = runCatching {
        val net = NetMemory.underlying()
        val conn = (net?.openConnection(URL(url)) ?: URL(url).openConnection()) as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.instanceFollowRedirects = false
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399
    }.getOrDefault(false)

    private fun tcpOk(host: String, port: Int): Boolean = runCatching {
        val net = NetMemory.underlying()
        val s = net?.socketFactory?.createSocket() ?: java.net.Socket()
        s.use { it.connect(InetSocketAddress(host, port), 6000) }
        true
    }.getOrDefault(false)

    private fun isUdp(p: ProxyEntity) = p.type == ProxyEntity.TYPE_HYSTERIA || p.type == ProxyEntity.TYPE_TUIC

    private class HopCheck(val verdict: String?, val turnOff: Boolean)

    /**
     * Hysteria2 port hopping: tries ports of the range from this network (and the main port).
     * When part of the range is filtered on the way, plain hopping lands on a dead port now and then;
     * with «умные прыжки» the open ports are remembered for this network and used alone.
     */
    private suspend fun checkHop(node: ServerNode, profiles: List<ProxyEntity>, net: NetMemory.Net?): HopCheck? {
        val hy = profiles.firstOrNull { HopPorts.isHop(it.hysteriaBean) } ?: return null
        val row = onMainDispatcher { Row("Прыжки портов Hysteria2") }
        onMainDispatcher { row.set(null, "проверяю ${HopPorts.SAMPLE} портов из ${node.hyHop.replace("-", "–")}…") }
        val found = runCatching { HopPorts.search(hy, net) }.getOrNull() ?: return null
        val n = found.tested
        val good = found.good.size
        val mainOk = good == 0 && runCatching {
            val main = ProxyEntity(id = -99L, groupId = hy.groupId, type = hy.type).putBean(
                (hy.hysteriaBean!!.clone() as io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean).apply { serverPorts = node.hyPort.toString() })
            AutoPick.test(listOf(main), persist = false).alive.isNotEmpty()
        }.getOrDefault(false)
        onMainDispatcher {
            row.set(good == n, when {
                good == n -> "все $n портов открыты"
                good == 0 -> "закрыты все $n портов" + if (mainOk) ", основной порт ${node.hyPort} работает" else ""
                else -> "открыто $good из $n"
            })
        }
        val smart = DataStore.kbSmartHop
        return when {
            good == n -> HopCheck("Прыжки портов Hysteria2 в этой сети работают: все проверенные порты открыты.", false)
            good == 0 && !mainOk -> HopCheck("Hysteria2 не проходит ни на одном порту — эта сеть режет UDP к серверу. Используйте XHTTP, VLESS или Sudoku.", false)
            good == 0 -> HopCheck("Диапазон прыжков ${node.hyHop.replace("-", "–")} закрыт целиком, а основной порт работает — скорее всего, его не пропускает файрвол хостера. Выключите прыжки портов.", true)
            smart -> HopCheck("В этой сети открыта только часть портов прыжков ($good из $n). DAK запомнил открытые порты для «${net?.label ?: "этой сети"}» и Hysteria2 прыгает только по ним — без «нет связи» и подвисаний.", false)
            else -> HopCheck("В этой сети режется часть портов прыжков ($good из $n открыты) — Hysteria2 то подключается, то нет, а видео подвисает секунд на 10. Включите «Умные прыжки портов» в настройках или выключите прыжки.", true)
        }
    }

    private fun run() {
        running = true
        rows.removeAllViews()
        verdict.isVisible = false
        action.isVisible = false
        go.isEnabled = false
        val ctx = requireContext()
        runOnDefaultDispatcher {
            if (DataStore.serviceState.started) {
                onMainDispatcher { SagerNet.stopService() }
                delay(2500)
            }
            val net = NetMemory.current()
            onMainDispatcher { Row("Сеть").set(net != null, net?.label ?: "нет подключения") }

            val ruRow = onMainDispatcher { Row("Сайты в России") }
            val ru = httpOk("https://ya.ru/")
            onMainDispatcher { ruRow.set(ru, if (ru) "открываются" else "не открываются") }

            val fRow = onMainDispatcher { Row("Зарубежные сайты напрямую") }
            val foreign = httpOk("https://www.google.com/generate_204")
            onMainDispatcher { fRow.set(foreign, if (foreign) "открываются" else "не открываются") }

            // the server of the selected profile
            val selected = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            val groupId = selected?.groupId ?: DataStore.currentGroupId()
            val node: ServerNode? = ServerStore.all().firstOrNull { it.groupId == groupId }
            var tcpAny: Boolean? = null
            if (node != null) {
                val ports = buildList {
                    val pr = node.protocols.split(',')
                    if ("vless" in pr || node.role != "single") add("VLESS" to node.vpnPort)
                    if ("xhttp" in pr) add("XHTTP" to node.xhPort)
                    if ("sudoku" in pr) add("Sudoku" to node.sdPort)
                }
                val sRow = onMainDispatcher { Row("Сервер «${node.name}» (${node.host})") }
                val open = ports.filter { tcpOk(node.host, it.second) }
                tcpAny = open.isNotEmpty()
                onMainDispatcher {
                    sRow.set(tcpAny, if (open.isNotEmpty()) "доступен (порты ${open.joinToString { "${it.first} ${it.second}" }})"
                    else "не отвечает ни на одном TCP-порту")
                }
            }

            // every profile of the group, as in «Проверить все»
            val profiles = SagerDatabase.proxyDao.getByGroup(groupId)
            val pRows = onMainDispatcher { profiles.associate { it.id to Row(it.displayName()) } }
            val result = runCatching {
                AutoPick.test(profiles) { p ->
                    onMainDispatcher {
                        pRows[p.id]?.set(p.status == 1, if (p.status == 1) "работает, ${p.ping} мс" else "не проходит")
                    }
                }
            }.getOrNull()
            val alive = result?.alive.orEmpty()
            val best = alive.firstOrNull()?.first
            if (best != null) NetMemory.remember(best.id, net)

            // Hysteria2 port hopping: are all ports of the range open from this network?
            val hop = node?.takeIf { it.hyHop.contains('-') }?.let { n -> checkHop(n, profiles, net) }

            val udpAll = profiles.filter { isUdp(it) }
            val udpDead = udpAll.isNotEmpty() && alive.none { isUdp(it.first) }
            val tcpAlive = alive.any { !isUdp(it.first) }
            val text = buildString {
                when {
                    !ru && !foreign && best == null -> append("У телефона нет интернета — дело не в VPN. Проверьте мобильные данные или Wi-Fi.")
                    profiles.isEmpty() -> append("В выбранной группе нет профилей — выберите сервер на главном экране.")
                    best == null && tcpAny == false -> append("Ваш сервер недоступен из этой сети даже по TCP — похоже, его IP заблокирован. Переезд на новый VPS решит проблему: ключи и пользователи сохранятся.")
                    best == null && tcpAny == true -> append("Сервер доступен, но ни один протокол не проходит — сеть режет сам VPN-трафик. Попробуйте «Защита от блокировок» и Sudoku у сервера.")
                    best == null -> append("Ни один профиль не проходит в этой сети.")
                    else -> {
                        append("Лучше всего здесь работает «${best.displayName()}» (${alive.first().second} мс).")
                        if (udpDead && tcpAlive) append("\n\nUDP в этой сети режется: Hysteria2 и TUIC не проходят — используйте XHTTP, VLESS или Sudoku.")
                        if (DataStore.kbSmartProto) append("\n\nDAK запомнил это для сети «${net?.label ?: "?"}» и в следующий раз выберет его сам.")
                    }
                }
                if (!foreign && best != null) append("\n\nНапрямую ${if (ru) "зарубежные сайты" else "сайты"} не открываются, а через VPN — да.")
                hop?.verdict?.let { append("\n\n").append(it) }
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                running = false
                go.isEnabled = true
                go.setText(R.string.diag_again)
                verdict.text = text
                verdict.isVisible = true
                when {
                    hop?.turnOff == true && node != null -> {
                        action.text = "Выключить прыжки портов"
                        action.isVisible = true
                        action.setOnClickListener {
                            action.isVisible = false
                            if (io.nekohasekai.sagernet.kombain.Deployer.running) {
                                (activity as? MainActivity)?.snackbar(R.string.srv_busy)?.show()
                            } else {
                                io.nekohasekai.sagernet.kombain.Deployer.harden(node, node.antifreeze, node.fp, node.sniRotation, false)
                                (activity as? MainActivity)?.snackbar("Выключаю прыжки портов — ход видно в «Моих серверах»")?.show()
                            }
                        }
                    }

                    best != null && best.id != DataStore.selectedProxy -> {
                        action.text = "Использовать «${best.displayName()}»"
                        action.isVisible = true
                        action.setOnClickListener {
                            val old = DataStore.selectedProxy
                            DataStore.selectedProxy = best.id
                            runOnDefaultDispatcher {
                                ProfileManager.postUpdate(old)
                                ProfileManager.postUpdate(best.id)
                            }
                            action.isVisible = false
                            (activity as? MainActivity)?.snackbar("Выбран «${best.displayName()}»")?.show()
                        }
                    }

                    best == null && tcpAny == false && node != null -> {
                        action.text = getString(R.string.srv_m_move).trimEnd('…')
                        action.isVisible = true
                        action.setOnClickListener {
                            (activity as? MainActivity)?.displayFragment(ServerMoveFragment.of(node))
                        }
                    }
                }
            }
        }
    }
}
