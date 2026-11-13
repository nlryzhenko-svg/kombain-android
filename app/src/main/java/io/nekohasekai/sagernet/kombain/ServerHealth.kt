package io.nekohasekai.sagernet.kombain

import android.text.format.Formatter
import io.nekohasekai.sagernet.ktx.app
import org.json.JSONObject

/** Turns `kombain-node.sh stats` JSON into short human texts. */
object ServerHealth {

    fun size(bytes: Long): String = Formatter.formatShortFileSize(app, bytes)

    fun uptime(sec: Long): String = when {
        sec >= 86400 -> "${sec / 86400} дн ${sec % 86400 / 3600} ч"
        sec >= 3600 -> "${sec / 3600} ч ${sec % 3600 / 60} мин"
        else -> "${sec / 60} мин"
    }

    /** Empty when everything is fine. */
    fun problem(st: JSONObject): String {
        val down = mutableListOf<String>()
        val svcs = st.optJSONArray("services")
        for (i in 0 until (svcs?.length() ?: 0)) {
            val o = svcs!!.getJSONObject(i)
            val state = o.optString("state")
            if (state != "active" && state != "unknown") down += o.optString("name")
        }
        if (down.isNotEmpty()) return "Не работает: " + down.joinToString(", ")
        val disk = st.optInt("diskPct")
        if (disk >= 95) return "Диск заполнен на $disk%"
        val total = st.optLong("memTotalKb"); val avail = st.optLong("memAvailKb")
        if (total > 0 && avail * 100 / total < 3) return "Почти нет свободной памяти"
        exitProblem(st)?.let { return it }
        return overload(st).firstOrNull().orEmpty()
    }

    /** Entry node of a cascade: traffic must leave through the exit server. Null when fine or not an entry. */
    fun exitProblem(st: JSONObject): String? {
        val addr = st.optString("exitAddr").takeIf { it.isNotBlank() } ?: return null
        val exitIp = st.optString("exitIp")
        return when {
            exitIp.isBlank() -> "Нет связи с выходным сервером $addr — заблокированные сайты не откроются"
            exitIp == st.optString("ownIp") -> "Трафик выходит с самого российского сервера, а не через $addr"
            else -> null
        }
    }

    /** Signs that the server itself chokes — the reason connections hang on a working server. */
    fun overload(st: JSONObject): List<String> = buildList {
        val oom = st.optInt("oom")
        if (oom > 0) add("Серверу не хватает памяти: система убивала процессы ($oom раз с последней перезагрузки). Нужен тариф с ОЗУ побольше или отключите лишние протоколы")
        val restarts = st.optInt("restarts")
        if (restarts > 0) add("Службы VPN падали и перезапускались ($restarts раз) — в эти моменты связь обрывается")
        val ct = st.optLong("conntrack"); val ctMax = st.optLong("conntrackMax")
        if (ctMax > 0 && ct * 100 / ctMax >= 80) add("Таблица соединений сервера почти заполнена ($ct из $ctMax) — новые соединения теряются")
        val steal = st.optInt("steal")
        if (steal >= 20) add("Хостер забирает $steal% процессора сервера (перегруженный VPS) — от этого подвисания. Поможет другой тариф или хостер")
        val total = st.optLong("memTotalKb"); val avail = st.optLong("memAvailKb"); val swap = st.optLong("swapUsedKb")
        if (total > 0 && avail * 100 / total < 10 && swap > 64 * 1024) add("Память на исходе, сервер работает с диска (swap ${size(swap * 1024)}) — всё тормозит")
        val load = st.optString("load").substringBefore(' ').toDoubleOrNull() ?: 0.0
        val cpus = st.optInt("cpus", 1).coerceAtLeast(1)
        if (load > cpus * 2) add("Процессор сервера перегружен (нагрузка $load при $cpus ядр.)")
    }

    /** One line for the server card. */
    fun summary(st: JSONObject): String {
        val total = st.optLong("memTotalKb"); val avail = st.optLong("memAvailKb")
        val memPct = if (total > 0) ((total - avail) * 100 / total) else 0
        val load = st.optString("load").substringBefore(' ')
        return "Нагрузка $load · ОЗУ $memPct% · работает ${uptime(st.optLong("uptime"))}"
    }

    fun traffic(st: JSONObject): String =
        "Трафик сервера: ↓ ${size(st.optLong("rx"))} ↑ ${size(st.optLong("tx"))}"

    /** Lines for the log after a manual check. */
    fun describe(st: JSONObject): List<String> = buildList {
        val svcs = st.optJSONArray("services")
        for (i in 0 until (svcs?.length() ?: 0)) {
            val o = svcs!!.getJSONObject(i)
            val state = o.optString("state")
            add("${o.optString("name")}: " + if (state == "active") "работает" else state)
        }
        add(summary(st))
        add("Диск занят на ${st.optInt("diskPct")}%")
        if (st.optString("exitAddr").isNotBlank()) {
            val p = exitProblem(st)
            add(if (p == null) "Выход в интернет: через выходной сервер (IP ${st.optString("exitIp")}) — заблокированные сайты открываются"
            else "⚠ $p")
        }
        if (st.has("steal")) {
            val rss = st.optLong("xrayRssKb")
            add("Процессор: забирает хостер ${st.optInt("steal")}%" + (if (rss > 0) " · Xray занимает ${size(rss * 1024)} ОЗУ" else ""))
            val bad = overload(st)
            if (bad.isEmpty()) add("Признаков перегрузки сервера нет: не падал, памяти и процессора хватает")
            else bad.forEach { add("⚠ $it") }
        }
        add(traffic(st))
        val users = st.optJSONArray("users")
        for (i in 0 until (users?.length() ?: 0)) {
            val u = users!!.getJSONObject(i)
            add("${u.optString("name")}: ↓ ${size(u.optLong("down"))} ↑ ${size(u.optLong("up"))} (VLESS/XHTTP)")
        }
    }

    /** Per-user traffic (VLESS/XHTTP), by user name. */
    fun userTraffic(st: JSONObject?): Map<String, Pair<Long, Long>> {
        val m = mutableMapOf<String, Pair<Long, Long>>()
        val users = st?.optJSONArray("users") ?: return m
        for (i in 0 until users.length()) {
            val u = users.getJSONObject(i)
            m[u.optString("name")] = u.optLong("down") to u.optLong("up")
        }
        return m
    }
}
