package io.nekohasekai.sagernet.fmt.wireguard

import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/**
 * Converts one Xray-core JSON outbound (as found in AmneziaVPN "amnezia-xray"
 * containers, or any Xray client config) into a standard share link that the
 * existing parsers understand. Returns null for non-proxy outbounds.
 */
object XrayOutbound {

    fun toLink(ob: JSONObject, name: String): String? {
        val protocol = ob.optString("protocol").lowercase()
        val settings = ob.optJSONObject("settings") ?: JSONObject()
        val stream = ob.optJSONObject("streamSettings") ?: JSONObject()
        return when (protocol) {
            "vless", "vmess" -> {
                val server = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                val user = server.optJSONArray("users")?.optJSONObject(0) ?: return null
                val q = streamQuery(stream)
                if (protocol == "vless") {
                    q["encryption"] = user.optString("encryption").ifBlank { "none" }
                    user.optString("flow").takeIf { it.isNotBlank() }?.let { q["flow"] = it }
                } else {
                    q["encryption"] = user.optString("security").ifBlank { "auto" }
                }
                build(protocol, user.optString("id"), server.optString("address"), server.optInt("port"), q, name)
            }

            "trojan" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                val q = streamQuery(stream)
                server.optString("flow").takeIf { it.isNotBlank() }?.let { q["flow"] = it }
                build("trojan", server.optString("password"), server.optString("address"), server.optInt("port"), q, name)
            }

            "shadowsocks" -> {
                val server = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                val userInfo = Base64.encodeToString(
                    "${server.optString("method")}:${server.optString("password")}".toByteArray(),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )
                "ss://$userInfo@${host(server.optString("address"))}:${server.optInt("port")}#${Uri.encode(name)}"
            }

            else -> null // freedom, blackhole, dns, ...
        }
    }

    private fun streamQuery(stream: JSONObject): LinkedHashMap<String, String> {
        val q = LinkedHashMap<String, String>()
        val network = stream.optString("network").ifBlank { "tcp" }.lowercase()
        q["type"] = when (network) {
            "raw" -> "tcp"
            "splithttp" -> "xhttp"
            else -> network
        }
        when (network) {
            "ws" -> stream.optJSONObject("wsSettings")?.let { ws ->
                ws.optString("path").takeIf { it.isNotBlank() }?.let { q["path"] = it }
                (ws.optString("host").ifBlank { ws.optJSONObject("headers")?.optString("Host") ?: "" })
                    .takeIf { it.isNotBlank() }?.let { q["host"] = it }
            }

            "httpupgrade" -> stream.optJSONObject("httpupgradeSettings")?.let { h ->
                h.optString("path").takeIf { it.isNotBlank() }?.let { q["path"] = it }
                h.optString("host").takeIf { it.isNotBlank() }?.let { q["host"] = it }
            }

            "grpc" -> stream.optJSONObject("grpcSettings")?.let { g ->
                g.optString("serviceName").takeIf { it.isNotBlank() }?.let { q["serviceName"] = it }
            }

            "http", "h2" -> stream.optJSONObject("httpSettings")?.let { h ->
                q["type"] = "http"
                h.optString("path").takeIf { it.isNotBlank() }?.let { q["path"] = it }
                h.optJSONArray("host")?.optString(0)?.takeIf { it.isNotBlank() }?.let { q["host"] = it }
            }

            "xhttp", "splithttp" -> (stream.optJSONObject("xhttpSettings")
                ?: stream.optJSONObject("splithttpSettings"))?.let { x ->
                x.optString("path").takeIf { it.isNotBlank() }?.let { q["path"] = it }
                x.optString("host").takeIf { it.isNotBlank() }?.let { q["host"] = it }
                x.optString("mode").takeIf { it.isNotBlank() }?.let { q["mode"] = it }
            }
        }
        when (stream.optString("security").lowercase()) {
            "reality" -> {
                q["security"] = "reality"
                stream.optJSONObject("realitySettings")?.let { r ->
                    q["pbk"] = r.optString("publicKey")
                    r.optString("shortId").takeIf { it.isNotBlank() }?.let { q["sid"] = it }
                    r.optString("serverName").takeIf { it.isNotBlank() }?.let { q["sni"] = it }
                    r.optString("fingerprint").takeIf { it.isNotBlank() }?.let { q["fp"] = it }
                    r.optString("spiderX").takeIf { it.isNotBlank() }?.let { q["spx"] = it }
                }
            }

            "tls" -> {
                q["security"] = "tls"
                stream.optJSONObject("tlsSettings")?.let { t ->
                    t.optString("serverName").takeIf { it.isNotBlank() }?.let { q["sni"] = it }
                    t.optString("fingerprint").takeIf { it.isNotBlank() }?.let { q["fp"] = it }
                    t.optJSONArray("alpn")?.let { a ->
                        (0 until a.length()).joinToString(",") { a.optString(it) }
                            .takeIf { it.isNotBlank() }?.let { q["alpn"] = it }
                    }
                    if (t.optBoolean("allowInsecure")) q["allowInsecure"] = "1"
                }
            }

            else -> q["security"] = "none"
        }
        return q
    }

    private fun host(address: String) = if (address.contains(":") && !address.startsWith("[")) "[$address]" else address

    private fun build(
        scheme: String, user: String, address: String, port: Int, q: Map<String, String>, name: String
    ): String? {
        if (user.isBlank() || address.isBlank() || port <= 0) return null
        val query = q.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        return "$scheme://${Uri.encode(user)}@${host(address)}:$port?$query#${Uri.encode(name)}"
    }
}
