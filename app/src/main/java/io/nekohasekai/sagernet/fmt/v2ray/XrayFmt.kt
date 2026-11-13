package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.ktx.app
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Kombain: Xray-core engine.
 *
 * sing-box keeps the TUN, routing and DNS. A profile with [StandardV2RayBean.xrayEngine]
 * is served by a separate Xray-core process: sing-box sends the traffic to Xray's local
 * SOCKS inbound, and Xray dials the server through sing-box's local "mapping" inbound
 * (finalAddress:finalPort), so its sockets still leave through the protected path.
 *
 * Same core as Happ / v2rayNG — for servers where sing-box's VLESS/Reality client
 * doesn't get along with a newer Xray server.
 */
object Xray {

    fun executable(): File = File(app.applicationInfo.nativeLibraryDir, "libxray.so")

    fun isAvailable() = executable().canExecute()
}

fun StandardV2RayBean.buildXrayConfig(port: Int): String {
    // Kombain: with several names of the masking site, every connection picks one at random,
    // so the censor doesn't see a burst of handshakes to one name
    val names = if (security == "tls" && realityPubKey.isNotBlank())
        sniAlts.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it != sni } else emptyList()
    val outbounds = JSONArray()
    if (names.isEmpty()) {
        outbounds.put(xrayOutbound(null).put("tag", "proxy"))
    } else {
        (listOf<String?>(null) + names).forEachIndexed { i, n -> outbounds.put(xrayOutbound(n).put("tag", "proxy-$i")) }
    }

    return JSONObject().apply {
        put("log", JSONObject().put("loglevel", "warning"))
        put("inbounds", JSONArray().put(JSONObject().apply {
            put("tag", "in")
            put("listen", LOCALHOST)
            put("port", port)
            put("protocol", "socks")
            put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
        }))
        put("outbounds", outbounds)
        if (names.isNotEmpty()) put("routing", JSONObject().apply {
            put("balancers", JSONArray().put(JSONObject().put("tag", "rot")
                .put("selector", JSONArray().put("proxy-")).put("strategy", JSONObject().put("type", "random"))))
            put("rules", JSONArray().put(JSONObject().put("type", "field")
                .put("inboundTag", JSONArray().put("in")).put("balancerTag", "rot")))
        })
    }.toString(2)
}

private fun StandardV2RayBean.xrayOutbound(serverNameOverride: String?): JSONObject {
    val outbound = JSONObject()
    val address = finalAddress.ifBlank { serverAddress }
    val portOut = if (finalPort > 0) finalPort else serverPort

    when {
        this is TrojanBean -> {
            outbound.put("protocol", "trojan")
            outbound.put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", address).put("port", portOut).put("password", password)
            )))
        }

        isVLESS -> {
            val user = JSONObject().put("id", uuid).put("encryption", vlessEncryption.orEmpty().ifBlank { "none" })
            val flow = encryption.orEmpty()
            if (flow.startsWith("xtls-")) user.put("flow", flow)
            outbound.put("protocol", "vless")
            outbound.put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", address).put("port", portOut).put("users", JSONArray().put(user))
            )))
        }

        else -> { // VMess
            val user = JSONObject().put("id", uuid)
                .put("alterId", (this as? VMessBean)?.alterId?.coerceAtLeast(0) ?: 0)
                .put("security", encryption.orEmpty().ifBlank { "auto" })
            outbound.put("protocol", "vmess")
            outbound.put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", address).put("port", portOut).put("users", JSONArray().put(user))
            )))
        }
    }
    outbound.put("streamSettings", xrayStreamSettings(serverNameOverride))
    return outbound
}

private fun StandardV2RayBean.xrayStreamSettings(serverNameOverride: String?): JSONObject {
    val stream = JSONObject()
    val network = when (type) {
        "tcp", "" -> "raw"
        "http", "h2", "xhttp", "splithttp" -> "xhttp"
        else -> type
    }
    stream.put("network", network)
    when (network) {
        "ws" -> stream.put("wsSettings", JSONObject().apply {
            if (path.isNotBlank()) put("path", path)
            if (host.isNotBlank()) put("host", host)
        })

        "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().apply {
            if (path.isNotBlank()) put("path", path)
            if (host.isNotBlank()) put("host", host)
        })

        "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", path))
        "xhttp" -> stream.put("xhttpSettings", JSONObject().apply {
            if (path.isNotBlank()) put("path", path)
            if (host.isNotBlank()) put("host", host)
            put("mode", xhttpMode.orEmpty().ifBlank { "auto" })
            val extra = xhttpExtra.orEmpty().takeIf { it.isNotBlank() }
                ?.let { e -> runCatching { JSONObject(e) }.getOrNull() } ?: JSONObject()
            // Kombain: a long-lived connection gets silently "frozen" by DPI after a while (video hangs
            // after ~15 min). New requests move to a fresh connection every 5–10 min, and an HTTP/2 ping
            // every 20 s finds a dead one quickly.
            val xmux = extra.optJSONObject("xmux") ?: JSONObject()
            if (!xmux.has("hMaxReusableSecs")) xmux.put("hMaxReusableSecs", "300-600")
            if (!xmux.has("hKeepAlivePeriod")) xmux.put("hKeepAlivePeriod", 20)
            extra.put("xmux", xmux)
            put("extra", extra)
        })
    }

    val serverName = serverNameOverride ?: sni.ifBlank { host.ifBlank { if (serverAddress.any { it.isLetter() }) serverAddress else "" } }
    val fp = utlsFingerprint.ifBlank { "firefox" }
    when {
        security == "tls" && realityPubKey.isNotBlank() -> {
            stream.put("security", "reality")
            stream.put("realitySettings", JSONObject().apply {
                put("serverName", serverName)
                put("fingerprint", fp)
                put("publicKey", realityPubKey)
                put("shortId", realityShortId)
                put("spiderX", "")
            })
        }

        security == "tls" -> {
            stream.put("security", "tls")
            stream.put("tlsSettings", JSONObject().apply {
                if (serverName.isNotBlank()) put("serverName", serverName)
                put("fingerprint", fp)
                if (allowInsecure) put("allowInsecure", true)
                val alpnList = alpn.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (alpnList.isNotEmpty()) put("alpn", JSONArray(alpnList))
            })
        }

        else -> stream.put("security", "none")
    }
    return stream
}
