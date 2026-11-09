package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.SingBoxOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import android.util.Base64
import java.util.zip.Inflater

/**
 * AmneziaWG obfuscation parameters, stored in the profile as plain
 * "Key = Value" lines exactly as they appear in an Amnezia .conf file.
 */
object AmneziaWG {

    val INT_KEYS = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
    val STR_KEYS = listOf("h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
    val ALL_KEYS = INT_KEYS + STR_KEYS

    /** Parses "Jc = 4" lines into a lowercase-key map; unknown keys are ignored. */
    fun parse(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (raw in text.split('\n', ';')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue
            val key = line.substringBefore("=").trim().lowercase()
            val value = line.substringAfter("=").trim()
            if (key in ALL_KEYS && value.isNotEmpty()) out[key] = value
        }
        return out
    }

    fun isEnabled(text: String?) = parse(text).isNotEmpty()

    /** Canonical text form, in the order Amnezia uses. */
    fun format(map: Map<String, String>): String =
        ALL_KEYS.mapNotNull { k -> map[k]?.let { "${k.replaceFirstChar(Char::uppercase)} = $it" } }
            .joinToString("\n")

    fun summary(text: String?): String {
        val m = parse(text)
        if (m.isEmpty()) return ""
        val shown = INT_KEYS.filter { m.containsKey(it) }
            .joinToString(", ") { "${it.replaceFirstChar(Char::uppercase)}=${m[it]}" }
        val extra = STR_KEYS.count { m.containsKey(it) }
        return if (extra > 0) "$shown (+$extra)" else shown
    }

    fun applyTo(options: SingBoxOptions.Outbound_WireGuardOptions, text: String?) {
        val m = parse(text)
        if (m.isEmpty()) return
        fun int(k: String) = m[k]?.toIntOrNull()?.takeIf { it != 0 }
        options.jc = int("jc"); options.jmin = int("jmin"); options.jmax = int("jmax")
        options.s1 = int("s1"); options.s2 = int("s2"); options.s3 = int("s3"); options.s4 = int("s4")
        options.h1 = m["h1"]; options.h2 = m["h2"]; options.h3 = m["h3"]; options.h4 = m["h4"]
        options.i1 = m["i1"]; options.i2 = m["i2"]; options.i3 = m["i3"]; options.i4 = m["i4"]; options.i5 = m["i5"]
    }

    /** Pulls AmneziaWG keys out of an [Interface] section (case-insensitive). */
    fun fromInterface(get: (String) -> String?): String {
        val m = LinkedHashMap<String, String>()
        for (k in ALL_KEYS) {
            val v = get(k.replaceFirstChar(Char::uppercase)) ?: get(k) ?: get(k.uppercase())
            if (!v.isNullOrBlank()) m[k] = v.trim()
        }
        return format(m)
    }

    /**
     * Decodes an AmneziaVPN share link (vpn://...) — base64url of Qt qCompress()
     * (4-byte big-endian length + zlib) wrapping a JSON server description.
     * Returns every profile found inside: WireGuard/AmneziaWG config texts
     * ("[Interface]...") and share links (vless://, trojan://, ss://, vmess://)
     * converted from Xray containers.
     */
    fun decodeVpnLink(link: String): List<String> {
        val payload = link.trim().removePrefix("vpn://").trim()
        val raw = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val json = runCatching { inflateQt(raw) }.getOrNull() ?: String(raw)
        val root = JSONObject(json)
        val name = root.optString("description").ifBlank { root.optString("hostName") }
        val items = mutableListOf<String>()
        collect(root, name, items)
        return items.distinct()
    }

    private fun inflateQt(data: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(data, 4, data.size - 4)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toString("UTF-8")
    }

    private fun collect(node: Any?, name: String, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                val outbounds = node.optJSONArray("outbounds")
                if (outbounds != null) {
                    // an Xray client config
                    for (i in 0 until outbounds.length()) {
                        val ob = outbounds.optJSONObject(i) ?: continue
                        runCatching { XrayOutbound.toLink(ob, name) }.getOrNull()?.let { out.add(it) }
                    }
                } else {
                    node.keys().forEach { collect(node.opt(it), name, out) }
                }
            }

            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), name, out)
            is String -> {
                if (node.contains("[Interface]")) {
                    out.add(node)
                } else if (node.trimStart().startsWith("{")) {
                    // last_config is itself a JSON string
                    runCatching { collect(JSONObject(node), name, out) }
                }
            }
        }
    }
}
