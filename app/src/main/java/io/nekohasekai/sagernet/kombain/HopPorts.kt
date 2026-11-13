package io.nekohasekai.sagernet.kombain

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Умные прыжки» for Hysteria2: networks often let only part of the hopping range through, and a hop
 * onto a filtered port means "no connection" at start or a ~10 s stall in the middle of a video.
 * Kombain tries ports of the range from the current network, remembers the open ones per network
 * and hops only between them. The server still listens on the whole range — nothing changes there.
 */
object HopPorts {

    /** How many ports of the range one search tries. */
    const val SAMPLE = 32

    /** Open ports needed to use the list (one is enough: then it simply stays on that port). */
    const val MIN_GOOD = 1

    private const val FRESH_MS = 24 * 3600_000L

    class Found(val good: List<Int>, val tested: Int)

    fun isHop(bean: HysteriaBean?) = bean != null && bean.protocolVersion == 2 &&
            bean.serverPorts.orEmpty().let { it.contains('-') || it.contains(':') || it.contains(',') }

    private fun range(bean: HysteriaBean): IntRange? {
        val s = bean.serverPorts.orEmpty().substringBefore(',').replace(':', '-')
        val a = s.substringBefore('-').toIntOrNull() ?: return null
        val b = s.substringAfter('-').toIntOrNull() ?: return null
        return a..b
    }

    private fun key(net: NetMemory.Net, bean: HysteriaBean) = "${net.key}|${bean.serverAddress}|${bean.serverPorts}"

    private fun load() = runCatching { JSONObject(DataStore.kbHopPorts) }.getOrDefault(JSONObject())

    private fun entry(bean: HysteriaBean, net: NetMemory.Net?): JSONObject? =
        net?.let { load().optJSONObject(key(it, bean)) }

    /** Open ports remembered for this network, or null when it was not searched yet. */
    fun remembered(bean: HysteriaBean, net: NetMemory.Net? = NetMemory.current()): List<Int>? {
        val e = entry(bean, net) ?: return null
        val arr = e.optJSONArray("ports") ?: return null
        return (0 until arr.length()).map { arr.getInt(it) }
    }

    /** True when this network has no recent search for the profile. */
    fun needsSearch(bean: HysteriaBean, net: NetMemory.Net? = NetMemory.current()): Boolean {
        if (!DataStore.kbSmartHop || net == null || !isHop(bean)) return false
        val e = entry(bean, net) ?: return true
        return System.currentTimeMillis() - e.optLong("at") > FRESH_MS
    }

    /**
     * What the profile connects to on this network: `server_ports` for sing-box ("p:p" items),
     * or null to keep the whole range (not searched yet, or the feature is off).
     */
    fun serverPorts(bean: HysteriaBean): List<String>? {
        if (!DataStore.kbSmartHop || !isHop(bean)) return null
        val good = runCatching { remembered(bean) }.getOrNull() ?: return null
        if (good.size < MIN_GOOD) return null
        return good.map { "$it:$it" }
    }

    /** Tries [SAMPLE] random ports of the range from the current network and remembers the open ones. */
    suspend fun search(profile: ProxyEntity, net: NetMemory.Net? = NetMemory.current()): Found? {
        val bean = profile.hysteriaBean ?: return null
        val r = range(bean) ?: return null
        val ports = r.shuffled().take(SAMPLE)
        val copies = ports.mapIndexed { i, port ->
            ProxyEntity(id = -1000L - i, groupId = profile.groupId, type = profile.type).putBean(
                (bean.clone() as HysteriaBean).apply { serverPorts = port.toString() }
            )
        }
        val alive = AutoPick.test(copies, persist = false).alive.map { it.first.id }.toSet()
        val good = copies.filter { it.id in alive }.map { it.hysteriaBean!!.serverPorts.toInt() }.sorted()
        if (net != null) {
            val m = load()
            m.put(key(net, bean), JSONObject().put("ports", JSONArray(good)).put("tested", ports.size)
                .put("at", System.currentTimeMillis()))
            DataStore.kbHopPorts = m.toString()
        }
        Logs.i("[Kombain] Hysteria2 в сети «${net?.label}»: открыто ${good.size} из ${ports.size} портов прыжков")
        return Found(good, ports.size)
    }
}
