package io.nekohasekai.sagernet.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.kombain.NetMemory
import io.nekohasekai.sagernet.kombain.RuApps
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.SecureRandom
import java.util.Locale

/**
 * «Проверка утечек»: with the VPN on — does anything show the real IP? The address sites see over IPv4
 * and IPv6, the DNS servers that resolve names, the address WebRTC (UDP/STUN) would reveal, and browsers
 * that go around the VPN.
 */
class LeakCheckFragment : ToolbarFragment(R.layout.layout_diagnostics) {

    private lateinit var rows: LinearLayout
    private lateinit var verdict: TextView
    private lateinit var go: MaterialButton
    private var running = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.leak_title)
        view.findViewById<TextView>(R.id.diag_intro).setText(R.string.leak_intro)
        rows = view.findViewById(R.id.diag_rows)
        verdict = view.findViewById(R.id.diag_verdict)
        view.findViewById<View>(R.id.diag_action).isVisible = false
        go = view.findViewById(R.id.diag_go)
        go.setOnClickListener { if (!running) run() }
    }

    private inner class Row(private val title: String) {
        val view = TextView(requireContext()).apply {
            textSize = 14.5f
            setPadding(0, dp2px(9), 0, dp2px(9))
        }

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

    private val cm get() = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    private fun vpnNetwork(): Network? = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** Marker for «through the tunnel»: requests go via DAK's own local proxy, i.e. the VPN outbound. */
    /** the VPN network; HTTP requests «through VPN» go via DAK's local proxy (the same outbound as apps) */
    private var vpnMark: Network? = null

    private fun get(net: Network?, url: String, timeout: Int = 8000): String? {
        if (net != null && net === vpnMark) {
            // DAK's local proxy first (the tunnel's outbound), then the VPN network itself
            fetch(url, timeout) { URL(it).openConnection(java.net.Proxy(java.net.Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", DataStore.mixedPort))) }?.let { return it }
        }
        return fetch(url, timeout) { net?.openConnection(URL(it)) ?: URL(it).openConnection() }
    }

    private fun fetch(url: String, timeout: Int, open: (String) -> java.net.URLConnection): String? = runCatching {
        val conn = open(url) as HttpURLConnection
        conn.connectTimeout = timeout
        conn.readTimeout = timeout
        conn.setRequestProperty("User-Agent", "DAK")
        try {
            if (conn.responseCode !in 200..299) null else conn.inputStream.bufferedReader().readText().trim()
        } finally {
            conn.disconnect()
        }
    }.onFailure { android.util.Log.w("DAK-Leak", "$url: $it") }.getOrNull()

    private fun country(net: Network?, ip: String): String = runCatching {
        val code = JSONObject(get(net, "https://ipwho.is/$ip", 6000)!!).optString("country_code")
        Locale("", code).getDisplayCountry(Locale("ru")).takeIf { it.isNotBlank() && code.isNotBlank() }
    }.getOrNull()?.let { " ($it)" } ?: ""

    /** STUN binding request — the address a browser's WebRTC would learn. */
    private fun stunAddress(net: Network?): String? = runCatching {
        DatagramSocket().use { s ->
            net?.bindSocket(s)
            s.soTimeout = 4000
            val host = net?.getByName("stun.l.google.com") ?: InetAddress.getByName("stun.l.google.com")
            val tx = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val req = byteArrayOf(0, 1, 0, 0, 0x21, 0x12, 0xA4.toByte(), 0x42) + tx
            s.send(DatagramPacket(req, req.size, InetSocketAddress(host, 19302)))
            val buf = ByteArray(512)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            var i = 20
            while (i + 4 <= p.length) {
                val type = ((buf[i].toInt() and 0xff) shl 8) or (buf[i + 1].toInt() and 0xff)
                val len = ((buf[i + 2].toInt() and 0xff) shl 8) or (buf[i + 3].toInt() and 0xff)
                val v = i + 4
                if ((type == 0x0020 || type == 0x0001) && buf[v + 1].toInt() == 1) {
                    val magic = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
                    val ip = ByteArray(4) { k ->
                        if (type == 0x0020) (buf[v + 4 + k].toInt() xor magic[k].toInt()).toByte() else buf[v + 4 + k]
                    }
                    return@use InetAddress.getByAddress(ip).hostAddress
                }
                i = v + len + ((4 - len % 4) % 4)
            }
            null
        }
    }.getOrNull()

    private val browsers = mapOf(
        "com.android.chrome" to "Chrome", "org.mozilla.firefox" to "Firefox", "com.yandex.browser" to "Яндекс Браузер",
        "ru.yandex.searchplugin" to "Яндекс", "com.opera.browser" to "Opera", "com.opera.mini.native" to "Opera Mini",
        "com.sec.android.app.sbrowser" to "Samsung Internet", "com.microsoft.emmx" to "Edge", "com.brave.browser" to "Brave",
        "com.duckduckgo.mobile.android" to "DuckDuckGo", "com.vivaldi.browser" to "Vivaldi", "ru.atom.browser" to "Atom",
    )

    /** Browsers that go around the tunnel: by «Приложения мимо VPN» or by the per-app list. */
    private fun bypassingBrowsers(): List<String> {
        val pm = requireContext().packageManager
        val out = mutableSetOf<String>()
        if (DataStore.kbBankDirect) out += RuApps.effective(pm)
        if (DataStore.proxyApps) {
            val list = DataStore.individual.split('\n').filter { it.isNotBlank() }.toSet()
            val installed = browsers.keys.filter { runCatching { pm.getApplicationInfo(it, 0); true }.getOrDefault(false) }
            if (DataStore.bypass) out += list else out += installed.filter { it !in list }
        }
        return out.mapNotNull { browsers[it] }.distinct()
    }

    private fun run() {
        running = true
        rows.removeAllViews()
        verdict.isVisible = false
        go.isEnabled = false
        runOnDefaultDispatcher {
            val problems = mutableListOf<String>()
            val notes = mutableListOf<String>()
            val vpn = vpnNetwork()
            vpnMark = vpn
            if (vpn == null || !DataStore.serviceState.started) {
                finish("VPN выключен. Включите его и запустите проверку ещё раз — проверяется то, что видят сайты, когда VPN работает.")
                return@runOnDefaultDispatcher
            }
            val direct = NetMemory.underlying()
            val blockedOnly = io.nekohasekai.sagernet.kombain.BlockedLists.enabled

            // the address sites see
            val ipRow = onMainDispatcher { Row("IP, который видят сайты") }
            val tunnelIp = get(vpn, "https://api.ipify.org")
            val realIp = get(direct, "https://api.ipify.org")
            val ipOk = tunnelIp != null && tunnelIp != realIp
            val tunnelCountry = tunnelIp?.let { country(vpn, it) }.orEmpty()
            onMainDispatcher {
                ipRow.set(if (tunnelIp == null) null else ipOk || blockedOnly, when {
                    tunnelIp == null -> "не удалось узнать (нет связи через VPN?)"
                    ipOk -> "$tunnelIp$tunnelCountry, а не ваш $realIp"
                    else -> "$tunnelIp — это ваш настоящий IP"
                })
            }
            if (tunnelIp != null && !ipOk) {
                if (!blockedOnly) problems += "Сайты видят ваш настоящий IP — трафик идёт мимо VPN. Переподключите VPN или выберите другой профиль."
            }

            // IPv6
            val v6Row = onMainDispatcher { Row("IPv6") }
            val realV6 = get(direct, "https://api6.ipify.org", 5000)
            val tunnelV6 = if (realV6 != null) get(vpn, "https://api6.ipify.org", 5000) else null
            val v6Leak = realV6 != null && tunnelV6 == realV6
            onMainDispatcher {
                v6Row.set(!v6Leak, when {
                    realV6 == null -> "у вашей сети нет IPv6 — утекать нечему"
                    v6Leak -> "идёт мимо VPN: сайты видят $realV6"
                    tunnelV6 == null -> "закрыт VPN — утечки нет"
                    else -> "через VPN ($tunnelV6)"
                })
            }
            if (v6Leak) problems += "IPv6 идёт мимо VPN. Включите в настройках маршрутизации IPv6 через VPN или отключите IPv6 у роутера/оператора."

            // DNS
            val dnsRow = onMainDispatcher { Row("DNS-серверы") }
            val dns = runCatching {
                val id = get(vpn, "https://bash.ws/id")!!.trim()
                // the names must be resolved where the apps' requests are resolved: open them through the tunnel
                // (with fake-DNS the server resolves them), and ask the system resolver too
                for (i in 1..6) {
                    runCatching {
                        (URL("http://$i.$id.bash.ws/").openConnection(java.net.Proxy(java.net.Proxy.Type.SOCKS,
                            InetSocketAddress("127.0.0.1", DataStore.mixedPort))) as HttpURLConnection).apply {
                            connectTimeout = 3000; readTimeout = 3000
                        }.let { c -> runCatching { c.responseCode }; c.disconnect() }
                    }
                    runCatching { vpn.getAllByName("${i + 10}.$id.bash.ws") }
                }
                Thread.sleep(2500)
                val arr = JSONArray(get(vpn, "https://bash.ws/dnsleak/test/$id?json", 10000)!!)
                (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "dns" }
            }.onFailure { android.util.Log.w("DAK-Leak", "dns: $it") }.getOrNull()
            val realCountry = realIp?.let { country(direct, it) }.orEmpty()
            val dnsCountries = dns?.map { it.optString("country").uppercase() }?.distinct().orEmpty()
            val russianDns = dnsCountries.contains("RU") && !tunnelCountry.contains("Росси")
            onMainDispatcher {
                dnsRow.set(if (dns == null) null else dns.isNotEmpty() && !russianDns, when {
                    dns == null -> "не удалось проверить"
                    dns.isEmpty() -> "запросы не дошли до проверочного сервера"
                    else -> dns.take(3).joinToString(", ") { "${it.optString("ip")} (${it.optString("org").ifBlank { it.optString("country_name") }})" } +
                            if (dns.size > 3) " и ещё ${dns.size - 3}" else ""
                })
            }
            if (russianDns && !blockedOnly) problems += "Имена сайтов разрешает российский DNS — провайдер видит, какие сайты вы открываете, и может подменять ответы. Настройки → «DNS-сервер»: выберите Cloudflare или Google."

            // WebRTC / UDP
            val udpRow = onMainDispatcher { Row("WebRTC (UDP)") }
            val stun = stunAddress(vpn)
            val udpLeak = stun != null && stun == realIp
            onMainDispatcher {
                udpRow.set(!udpLeak, when {
                    stun == null -> "UDP-запрос не ответил — WebRTC не выдаст ваш IP"
                    udpLeak -> "браузер через WebRTC покажет ваш IP $stun"
                    else -> "видит $stun — адрес VPN"
                })
            }
            if (udpLeak && !blockedOnly) problems += "WebRTC в браузере может выдать ваш настоящий IP. Проверьте, что VPN подключён в режиме «весь трафик», а браузер не в списке «мимо VPN»."

            // browsers around the tunnel
            val brRow = onMainDispatcher { Row("Браузеры") }
            val br = runCatching { bypassingBrowsers() }.getOrDefault(emptyList())
            onMainDispatcher {
                brRow.set(br.isEmpty(), if (br.isEmpty()) "все идут через VPN" else "мимо VPN: ${br.joinToString()}")
            }
            if (br.isNotEmpty()) problems += "${br.joinToString()} идёт мимо VPN — сайты в нём видят ваш IP. Снимите его в «Приложения мимо VPN»."

            val incomplete = tunnelIp == null || dns == null
            if (blockedOnly) notes.add(0, "Включён режим «только заблокированное»: обычные сайты — и проверочные сайты этой проверки — идут напрямую, поэтому они видят ваш настоящий IP и DNS. Так и задумано. Через VPN идут только заблокированные сайты. Чтобы проверить сам VPN, переключите на время режим «весь трафик через VPN».")
            val text = buildString {
                if (problems.isEmpty() && blockedOnly) append("Сейчас включён режим «только заблокированное».")
                else if (problems.isEmpty() && incomplete) append("Проверка не завершена: часть проверочных сайтов не ответила через VPN. Явных утечек не найдено — повторите проверку через минуту.")
                else if (problems.isEmpty()) append("Утечек нет: сайты видят адрес VPN$tunnelCountry, а DNS и WebRTC не выдают ваш настоящий IP$realCountry.")
                else {
                    append("Найдено утечек: ${problems.size}.\n\n")
                    append(problems.joinToString("\n\n") { "• $it" })
                }
                notes.forEach { append("\n\n").append(it) }
            }
            finish(text)
        }
    }

    private suspend fun finish(text: String) = onMainDispatcher {
        if (!isAdded) return@onMainDispatcher
        running = false
        go.isEnabled = true
        go.setText(R.string.diag_again)
        verdict.text = text
        verdict.isVisible = true
    }
}
