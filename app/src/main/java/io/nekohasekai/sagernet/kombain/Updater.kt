package io.nekohasekai.sagernet.kombain

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Updates of DAK itself: the newest version lies in the «updates» branch of the app's repository
 * (latest.json + DAK-4.1.0-arm64-v8a.apk …), GitHub releases are asked too. With DAK running the requests
 * go through its local proxy, i.e. through the VPN; every request has a time limit.
 * Android refuses an update signed with another key, so a foreign APK can't replace DAK.
 */
object Updater {

    private const val REPO = "nlryzhenko-svg/kombain-android"
    private const val CHECK_EVERY = 12 * 3600_000L

    class Release(val version: String, val notes: String, val url: String, val size: Long, val sha256: String?)

    /** The APK flavour for this phone. */
    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        ?: "arm64-v8a"

    /** "4.1.0" > "4.0.12"? (numbers, not text) */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    val current: String get() = BuildConfig.VERSION_NAME

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(if (downloading) 15 else 1, java.util.concurrent.TimeUnit.MINUTES)
        .apply {
            if (DataStore.serviceState.started) proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", DataStore.mixedPort)))
        }
        .build()

    @Volatile private var downloading = false

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "DAK/$current").build()
        client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("сервер обновлений ответил ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    private const val CHANNEL = "https://raw.githubusercontent.com/$REPO/updates"

    /**
     * The newest version when it is newer than this app; null otherwise. Two places are asked: the «updates»
     * branch of the repository (latest.json + APKs) and GitHub releases; the newer one wins.
     * Throws when neither could be read.
     */
    fun check(): Release? {
        val a = runCatching { fromChannel() }.onFailure { android.util.Log.w("DAK-Update", "channel: $it") }
        val b = runCatching { fromReleases() }.onFailure { android.util.Log.w("DAK-Update", "releases: $it") }
        if (a.isFailure && b.isFailure) throw a.exceptionOrNull()!!
        DataStore.kbUpdateChecked = System.currentTimeMillis()
        return listOfNotNull(a.getOrNull(), b.getOrNull()).maxWithOrNull { x, y ->
            if (newer(x.version, y.version)) 1 else if (newer(y.version, x.version)) -1 else 0
        }
    }

    /** latest.json: {"version", "notes", "apks": {"arm64-v8a": {"file", "size", "sha256"}, …}} */
    private fun fromChannel(): Release? {
        val j = JSONObject(get("$CHANNEL/latest.json?t=${System.currentTimeMillis() / 600_000}"))
        val version = j.optString("version")
        if (version.isBlank() || !newer(version, current)) return null
        val apk = j.optJSONObject("apks")?.optJSONObject(abi()) ?: return null
        return Release(version, j.optString("notes"), "$CHANNEL/" + apk.getString("file"),
            apk.optLong("size"), apk.optString("sha256").takeIf { it.length == 64 })
    }

    private fun fromReleases(): Release? {
        run {
            val r = JSONObject(get("https://api.github.com/repos/$REPO/releases/latest"))
            val version = r.optString("tag_name").removePrefix("v")
            if (version.isBlank() || !newer(version, current)) return null
            val assets = r.optJSONArray("assets") ?: return null
            val want = abi()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith("-$want.apk")) {
                    return Release(
                        version, r.optString("body").trim(), a.optString("browser_download_url"), a.optLong("size"),
                        a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                    )
                }
            }
            return null
        }
    }

    /** Time for the automatic check (twice a day at most). */
    fun due() = DataStore.kbAutoUpdate && System.currentTimeMillis() - DataStore.kbUpdateChecked > CHECK_EVERY

    private fun dir() = File(app.cacheDir, "update").apply { mkdirs() }

    /** Downloads the APK of [r]; verifies its size and SHA-256 when GitHub gives them. */
    fun download(r: Release): File {
        dir().listFiles()?.forEach { it.delete() }
        val file = File(dir(), "DAK-${r.version}.apk")
        downloading = true
        try {
            client().newCall(Request.Builder().url(r.url).header("User-Agent", "DAK/$current").build()).execute().use { resp ->
                if (!resp.isSuccessful) error("сервер обновлений ответил ${resp.code}")
                val body = resp.body ?: error("пустой ответ")
                file.outputStream().use { out -> body.byteStream().copyTo(out, 1 shl 16) }
            }
        } finally {
            downloading = false
        }
        if (!file.exists() || file.length() < 1_000_000 || (r.size > 0 && file.length() != r.size)) {
            file.delete(); error("файл скачался не полностью")
        }
        if (r.sha256 != null) {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(r.sha256, true)) { file.delete(); error("контрольная сумма не совпала") }
        }
        Logs.i("[DAK] скачано обновление ${r.version}")
        return file
    }

    /** Opens the system installer; first asks for the «install unknown apps» permission when needed. */
    fun install(activity: Activity, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Разрешите DAK устанавливать приложения и нажмите «Обновить» ещё раз", Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.cache", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun cleanup(ctx: Context = app) {
        File(ctx.cacheDir, "update").listFiles()?.forEach { it.delete() }
    }
}
