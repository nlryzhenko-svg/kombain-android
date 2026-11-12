package io.nekohasekai.sagernet.fmt.sudoku

import android.util.Base64
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.toStringPretty
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The bundled Sudoku client (app/executableSo/<abi>/libsudoku.so). */
object Sudoku {
    fun executable(): File = File(app.applicationInfo.nativeLibraryDir, "libsudoku.so")
    fun isAvailable() = executable().canExecute()
}

private val ASCII_SHORT = mapOf(
    "entropy" to "prefer_entropy", "ascii" to "prefer_ascii",
    "up_ascii_down_entropy" to "up_ascii_down_entropy", "up_entropy_down_ascii" to "up_entropy_down_ascii",
)

/**
 * sudoku://<base64url JSON> — the short-link format of the official clients:
 * {"n": name, "h": host, "p": port, "k": key, "a": "entropy"|"ascii"|…, "e": aead, "x": packed downlink,
 *  "t"/"ts": custom tables, "hd": httpmask off, "hm": mode, "ht": tls, "hh": host, "hy": path, "hx": mux}
 */
fun parseSudoku(link: String): SudokuBean {
    val payload = link.substringAfter("sudoku://").substringBefore('#').trim()
    val json = JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
    return SudokuBean().apply {
        initializeDefaultValues()
        serverAddress = json.getString("h")
        serverPort = json.getInt("p")
        key = json.getString("k")
        name = json.optString("n").ifBlank { link.substringAfter('#', "").let { java.net.URLDecoder.decode(it, "UTF-8") } }
        json.optString("a").takeIf { it.isNotBlank() }?.let { ascii = ASCII_SHORT[it] ?: it }
        json.optString("e").takeIf { it.isNotBlank() }?.let { aead = it }
        pureDownlink = !json.optBoolean("x", false)
        val tables = mutableListOf<String>()
        json.optString("t").takeIf { it.isNotBlank() }?.let { tables += it }
        json.optJSONArray("ts")?.let { a -> for (i in 0 until a.length()) tables += a.getString(i) }
        customTables = tables.distinct().joinToString(",")
        httpMask = if (json.optBoolean("hd", false)) "" else json.optString("hm").ifBlank { "legacy" }
        httpMaskTls = json.optBoolean("ht", false)
        httpMaskHost = json.optString("hh")
        httpMaskPath = json.optString("hy")
        multiplex = json.optString("hx").ifBlank { "off" }
    }
}

fun SudokuBean.toUri(): String {
    val json = JSONObject().apply {
        if (name.isNotBlank()) put("n", name)
        put("h", serverAddress)
        put("p", serverPort)
        put("k", key)
        put("a", ASCII_SHORT.entries.firstOrNull { it.value == ascii }?.key ?: ascii)
        put("e", aead)
        if (!pureDownlink) put("x", true)
        val tables = customTables.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tables.size == 1) put("t", tables[0]) else if (tables.size > 1) put("ts", JSONArray(tables))
        if (httpMask.isBlank()) put("hd", true) else {
            put("hm", httpMask)
            if (httpMaskTls) put("ht", true)
            if (httpMaskHost.isNotBlank()) put("hh", httpMaskHost)
            if (httpMaskPath.isNotBlank()) put("hy", httpMaskPath)
        }
        if (multiplex != "off") put("hx", multiplex)
    }
    return "sudoku://" + Base64.encodeToString(json.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

/** Client config: a local SOCKS/HTTP proxy on [port] that sing-box sends this profile's traffic to. */
fun SudokuBean.buildSudokuConfig(port: Int): String {
    val tables = customTables.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return JSONObject().apply {
        put("mode", "client")
        put("transport", "tcp")
        put("local_port", port)
        // sing-box's mapping inbound: the connection to the server leaves through the protected path
        put("server_address", "${finalAddress.ifBlank { serverAddress }}:${if (finalPort > 0) finalPort else serverPort}")
        put("key", key)
        put("aead", aead)
        put("padding_min", paddingMin)
        put("padding_max", paddingMax)
        put("ascii", ascii)
        if (tables.isNotEmpty()) put("custom_tables", JSONArray(tables))
        put("enable_pure_downlink", pureDownlink)
        put("multiplex", multiplex)
        put("httpmask", JSONObject().apply {
            put("disable", httpMask.isBlank())
            put("mode", httpMask.ifBlank { "legacy" })
            put("tls", httpMaskTls)
            // the server is reached through 127.0.0.1: keep the real name in Host
            put("host", httpMaskHost.ifBlank { serverAddress })
            put("path_root", httpMaskPath)
        })
        // sing-box already routes; the client just forwards everything
        put("rule_urls", JSONArray().put("global"))
    }.toStringPretty()
}
