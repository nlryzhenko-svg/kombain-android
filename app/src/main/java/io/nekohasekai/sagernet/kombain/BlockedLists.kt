package io.nekohasekai.sagernet.kombain

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import libcore.Libcore
import java.io.File

/**
 * «Только заблокированное»: lists of what is blocked in Russia (runetfreedom, updated daily upstream).
 *  - domains: ru-blocked.txt → a sing-box "source" rule-set (JSON);
 *  - IPs: ru-blocked.srs, a ready sing-box binary rule-set (Telegram and other apps that connect by IP).
 * A snapshot ships inside the APK, so the mode works offline from the first start.
 */
object BlockedLists {

    const val TAG_DOMAINS = "kb-ru-blocked"
    const val TAG_IPS = "kb-ru-blocked-ip"

    private const val URL_DOMAINS =
        "https://raw.githubusercontent.com/runetfreedom/russia-blocked-geosite/release/ru-blocked.txt"
    private const val URL_IPS =
        "https://raw.githubusercontent.com/runetfreedom/russia-blocked-geoip/release/srs/ru-blocked.srs"

    val enabled get() = DataStore.kbRouteMode == "blocked"

    private fun dir() = File(app.filesDir, "kombain").apply { mkdirs() }
    fun domainsFile() = File(dir(), "ru-blocked-domains.srs")
    fun ipsFile() = File(dir(), "ru-blocked.srs")

    fun ready() = domainsFile().length() > 1024 && ipsFile().length() > 1024

    /** Makes sure both files exist (from the bundled snapshot if never downloaded). */
    @Synchronized
    fun ensure() {
        File(dir(), "ru-blocked.json").delete() // 3.2 preview stored the JSON form
        if (!domainsFile().exists()) {
            val txt = File(dir(), "ru-blocked.txt.tmp")
            app.assets.open("kombain/ru-blocked.txt").use { input -> txt.outputStream().use { input.copyTo(it) } }
            compile(txt)
        }
        if (!ipsFile().exists()) {
            app.assets.open("kombain/ru-blocked.srs").use { input -> ipsFile().outputStream().use { input.copyTo(it) } }
        }
    }

    /** "domain:x.com" / "full:x.com" lines → binary rule-set (loads several times faster than JSON). */
    private fun compile(txt: File): Int {
        try {
            val n = Libcore.compileDomainList(txt.canonicalPath, domainsFile().canonicalPath)
            if (n < 100) error("список подозрительно короткий")
            return n
        } finally {
            txt.delete()
        }
    }

    /** Downloads fresh lists. Returns the number of domains, or throws. */
    fun update(): Int {
        val client = Libcore.newHttpClient().apply {
            modernTLS(); keepAlive()
            // through the tunnel when it is up: raw.githubusercontent.com is sometimes slowed down in Russia
            if (DataStore.serviceState.started) trySocks5(DataStore.mixedPort)
        }
        try {
            val txt = File(dir(), "ru-blocked.txt.tmp")
            client.newRequest().apply { setURL(URL_DOMAINS) }.execute().writeTo(txt.canonicalPath)
            val count = compile(txt)
            val srs = File(dir(), "ru-blocked.srs.tmp")
            client.newRequest().apply { setURL(URL_IPS) }.execute().writeTo(srs.canonicalPath)
            if (srs.length() > 1024 && srs.inputStream().use { s -> ByteArray(3).also { s.read(it) } }.contentEquals("SRS".toByteArray())) srs.renameTo(ipsFile())
            else srs.delete()
            DataStore.kbListsUpdated = System.currentTimeMillis()
            Logs.i("[Kombain] списки блокировок обновлены: $count доменов")
            return count
        } finally {
            client.close()
        }
    }
}
