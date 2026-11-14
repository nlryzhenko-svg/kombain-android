package io.nekohasekai.sagernet.kombain

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import libcore.Libcore
import java.io.File
import java.net.InetAddress

/**
 * «Белые списки» of the Russian mobile internet: when an operator switches to the whitelist mode,
 * only these addresses and site names keep working. A cascade entry node survives that only if its
 * IP is in the list and its masking site is a whitelisted name.
 *
 * Lists: ancored/ru-mobile-whitelist (rebuilt daily from public sources). A snapshot ships in the APK;
 * a fresh copy is downloaded when the snapshot is older than three days.
 */
object Whitelist {

    private const val BASE = "https://raw.githubusercontent.com/ancored/ru-mobile-whitelist/release/"
    private const val MAX_AGE = 3 * 24 * 3600_000L

    private fun dir() = File(app.filesDir, "kombain").apply { mkdirs() }
    private fun ipFile() = File(dir(), "wl-ip.txt")
    private fun sniFile() = File(dir(), "wl-sni.txt")

    @Volatile
    private var nets: List<Pair<ByteArray, Int>>? = null
    @Volatile
    private var names: Set<String>? = null

    class Result(
        val ip: String?,
        /** null — the address couldn't be resolved */
        val ipListed: Boolean?,
        val site: String,
        val siteListed: Boolean,
    ) {
        val ok get() = ipListed == true && siteListed
    }

    @Synchronized
    private fun ensureFiles() {
        if (!ipFile().exists()) copyAsset("kombain/wl-ip.txt", ipFile())
        if (!sniFile().exists()) copyAsset("kombain/wl-sni.txt", sniFile())
    }

    /** Network: downloads fresh lists when the copy is older than three days. Not on the main thread. */
    private fun freshen() {
        ensureFiles()
        if (System.currentTimeMillis() - ipFile().lastModified() > MAX_AGE) runCatching { update() }.onFailure { Logs.w(it) }
    }

    @Synchronized
    private fun loadNets(): List<Pair<ByteArray, Int>> {
        ensureFiles()
        return nets ?: ipFile().readLines().mapNotNull { parseCidr(it) }.also { nets = it }
    }

    @Synchronized
    private fun loadNames(): Set<String> {
        ensureFiles()
        return names ?: sniFile().readLines().map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.toSet().also { names = it }
    }

    private fun copyAsset(name: String, to: File) {
        app.assets.open(name).use { input -> to.outputStream().use { input.copyTo(it) } }
    }

    private fun update() {
        val client = Libcore.newHttpClient().apply {
            modernTLS(); keepAlive()
            if (DataStore.serviceState.started) trySocks5(DataStore.mixedPort)
        }
        try {
            for ((remote, local) in listOf("ru-mobile-whitelist-ip.txt" to ipFile(), "ru-mobile-whitelist-sni.txt" to sniFile())) {
                val tmp = File(local.path + ".tmp")
                client.newRequest().apply { setURL(BASE + remote) }.execute().writeTo(tmp.canonicalPath)
                if (tmp.length() > 1000) tmp.renameTo(local) else tmp.delete()
            }
            nets = null; names = null
            Logs.i("[DAK] белые списки обновлены")
        } finally {
            client.close()
        }
    }

    private fun parseCidr(line: String): Pair<ByteArray, Int>? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val addr = t.substringBefore('/')
        // literals only, so that InetAddress never does a DNS lookup
        val literal = if (':' in addr) addr.all { it.isDigit() || it == ':' || it == '.' || it in 'a'..'f' || it in 'A'..'F' }
        else addr.count { it == '.' } == 3 && addr.all { it.isDigit() || it == '.' }
        if (!literal) return null
        val bytes = runCatching { InetAddress.getByName(addr).address }.getOrNull() ?: return null
        val prefix = t.substringAfter('/', "").toIntOrNull() ?: (bytes.size * 8)
        return bytes to prefix
    }

    private fun inNet(ip: ByteArray, net: ByteArray, prefix: Int): Boolean {
        if (ip.size != net.size) return false
        var bits = prefix
        for (i in ip.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((ip[i].toInt() and mask) != (net[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    fun ipListed(ip: String): Boolean {
        val b = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
        return loadNets().any { inNet(b, it.first, it.second) }
    }

    /** "x.ru" is listed if it or one of its parent domains is in the list. */
    fun siteListed(domain: String): Boolean {
        var d = domain.trim().trimEnd('.').lowercase()
        val set = loadNames()
        while (d.contains('.')) {
            if (d in set) return true
            d = d.substringAfter('.')
        }
        return false
    }

    /** Blocking (network): resolves the host and checks the address and the masking site. */
    fun check(host: String, site: String): Result {
        freshen()
        val ip = runCatching {
            InetAddress.getAllByName(host.trim()).firstOrNull { it.address.size == 4 }?.hostAddress
                ?: InetAddress.getByName(host.trim()).hostAddress
        }.getOrNull()
        return Result(ip, ip?.let { ipListed(it) }, site, site.isNotBlank() && siteListed(site))
    }

    fun describe(r: Result): String = buildString {
        append("Белые списки мобильного интернета:\n")
        append(when (r.ipListed) {
            true -> "✓ адрес ${r.ip} есть в списке"
            false -> "✗ адреса ${r.ip} в списке нет — при режиме белых списков сервер будет недоступен"
            null -> "? не удалось узнать IP-адрес сервера"
        })
        append('\n')
        append(if (r.siteListed) "✓ сайт маскировки ${r.site} в списке"
        else "✗ сайта маскировки ${r.site.ifBlank { "—" }} в списке нет — подберите сайт с отметкой «белый список»")
    }
}
