package io.nekohasekai.sagernet.kombain

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import org.json.JSONObject
import java.security.MessageDigest

/**
 * «Умный выбор протокола»: which profile works on which network.
 *
 * A network is told apart by its type and, for mobile data, the operator; for Wi-Fi by the network's
 * name when Android gives it, otherwise by its gateway and DNS servers. Kombain remembers the profile
 * that last worked there and picks it again when the phone comes back to that network.
 */
object NetMemory {

    class Net(val key: String, val label: String, val mobile: Boolean)

    private val cm get() = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The phone's real network (not the VPN). */
    @Suppress("DEPRECATION")
    fun underlying(): Network? {
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        if (activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return active
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private fun short(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .take(3).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    fun current(): Net? = runCatching {
        val n = underlying() ?: return null
        val caps = cm.getNetworkCapabilities(n) ?: return null
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val op = tm.networkOperatorName.orEmpty().ifBlank { tm.simOperatorName.orEmpty() }.ifBlank { "?" }
                Net("mobile:$op", "Мобильный интернет ($op)", true)
            }

            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = runCatching {
                    (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid
                }.getOrNull()?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                if (ssid != null) Net("wifi:$ssid", "Wi-Fi «$ssid»", false)
                else {
                    val lp = cm.getLinkProperties(n)
                    val sig = (lp?.routes?.mapNotNull { it.gateway?.hostAddress }.orEmpty() +
                            lp?.dnsServers?.map { it.hostAddress }.orEmpty()).sorted().joinToString(",")
                    val id = short(sig)
                    Net("wifi:#$id", "Wi-Fi (сеть $id)", false)
                }
            }

            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Net("eth", "Кабель", false)
            else -> Net("other", "Другая сеть", false)
        }
    }.onFailure { Logs.w(it) }.getOrNull()

    private fun load(): JSONObject = runCatching { JSONObject(DataStore.kbNetMemory) }.getOrDefault(JSONObject())

    /** This profile works on the current network. */
    fun remember(profileId: Long, net: Net? = current()) {
        if (!DataStore.kbSmartProto || net == null || profileId <= 0) return
        val m = load()
        val old = m.optJSONObject(net.key)?.optLong("id")
        m.put(net.key, JSONObject().put("id", profileId).put("at", System.currentTimeMillis()).put("label", net.label))
        DataStore.kbNetMemory = m.toString()
        if (old != profileId) Logs.i("[Kombain] ${net.label}: запомнил профиль #$profileId")
    }

    /** The profile that worked here last time — only one from the same group as [sameGroupAs]. */
    fun recall(sameGroupAs: Long, net: Net? = current()): Long? {
        if (!DataStore.kbSmartProto || net == null) return null
        val id = load().optJSONObject(net.key)?.optLong("id")?.takeIf { it > 0 } ?: return null
        if (id == sameGroupAs) return id
        val a = SagerDatabase.proxyDao.getById(id) ?: return null
        val b = SagerDatabase.proxyDao.getById(sameGroupAs) ?: return null
        return id.takeIf { a.groupId == b.groupId }
    }

    /** Protocols of the same server in the order to try when the current one froze. */
    fun fallbackOrder(currentId: Long): List<io.nekohasekai.sagernet.database.ProxyEntity> =
        AutoPick.groupOf(currentId).filter { it.id != currentId }
}
