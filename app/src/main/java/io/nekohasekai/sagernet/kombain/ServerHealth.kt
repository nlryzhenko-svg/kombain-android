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
        return ""
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
