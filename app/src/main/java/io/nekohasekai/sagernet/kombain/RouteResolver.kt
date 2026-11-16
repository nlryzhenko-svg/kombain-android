package io.nekohasekai.sagernet.kombain

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.widget.RouteMapView.Node
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale

/**
 * Where the traffic really goes: phone → server (→ exit server of a cascade) → internet.
 * The server is the profile's address; the exit is the address the internet sees, asked through
 * the tunnel itself. If they differ, the traffic leaves through another server — a cascade.
 * Names come from «Мои серверы» when the server is ours, otherwise from the city.
 */
object RouteResolver {

    class Geo(val ip: String, val code: String, val city: String, val country: String)

    /** Fast first picture, no network: names only. */
    fun quick(profile: ProxyEntity?): List<Node> {
        val node = profile?.let { ourNode(it) }
        val exit = node?.takeIf { it.role == "entry" }?.let { ServerStore.get(it.exitId) }
        return buildList {
            add(Node("📱", "Вы"))
            add(Node(if (node?.role == "entry") "🇷🇺" else "🖥", node?.name ?: profile?.displayName() ?: "Сервер"))
            if (exit != null) add(Node("🖥", exit.name))
            add(Node("🌍", "Интернет"))
        }
    }

    /** Full picture with flags and the exit address; blocking, call off the main thread. */
    fun resolve(profile: ProxyEntity): List<Node> {
        val bean = profile.requireBean()
        val node = ourNode(profile)
        val serverIp = runCatching { InetAddress.getByName(bean.serverAddress).hostAddress }.getOrNull()
        val server = serverIp?.let { geo("https://ipwho.is/$it") }
        val exitNode = node?.takeIf { it.role == "entry" }?.let { ServerStore.get(it.exitId) }
        // «только заблокированное»: an address check goes around the tunnel and would show the real IP
        val blockedOnly = BlockedLists.enabled
        val exit = if (blockedOnly) {
            exitNode?.let { n -> runCatching { InetAddress.getByName(n.host.trim()).hostAddress }.getOrNull() }
                ?.let { geo("https://ipwho.is/$it") } ?: server.takeIf { exitNode == null }
        } else geo("https://ipwho.is/")
        val cascade = !blockedOnly && exit != null && serverIp != null && exit.ip != serverIp
        return buildList {
            add(Node("📱", "Вы"))
            add(Node(flag(server?.code), node?.name ?: place(server) ?: profile.displayName(),
                if (node != null) place(server).orEmpty() else server?.country.orEmpty()))
            if (cascade || exitNode != null) {
                add(Node(flag(exit?.code), exitNode?.name ?: place(exit) ?: "Выход",
                    if (exitNode != null) place(exit).orEmpty() else exit?.country.orEmpty()))
            }
            add(Node("🌍", "Интернет", if (blockedOnly) "заблокированное" else exit?.ip.orEmpty()))
        }
    }

    private fun ourNode(profile: ProxyEntity): ServerNode? {
        val all = runCatching { ServerStore.all() }.getOrNull() ?: return null
        val host = runCatching { profile.requireBean().serverAddress }.getOrNull()
        return all.firstOrNull { it.groupId != 0L && it.groupId == profile.groupId }
            ?: all.firstOrNull { host != null && it.host.trim() == host }
    }

    private fun place(g: Geo?): String? = g?.let { it.city.ifBlank { it.country }.ifBlank { null } }

    fun flag(code: String?): String {
        val c = code?.uppercase()?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } } ?: return "🖥"
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }

    /** ipwho.is through DAK's own local proxy, i.e. through the tunnel. */
    private fun geo(url: String): Geo? = runCatching {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", DataStore.mixedPort))
        val conn = URL(url).openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        conn.setRequestProperty("User-Agent", "DAK")
        val text = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
        val j = JSONObject(text)
        if (!j.optBoolean("success", true)) return null
        val code = j.optString("country_code")
        Geo(j.optString("ip"), code, j.optString("city"),
            Locale("", code).getDisplayCountry(Locale("ru")).ifBlank { j.optString("country") })
    }.onFailure { android.util.Log.w("DAK-Route", "$url: $it") }.getOrNull()
}
