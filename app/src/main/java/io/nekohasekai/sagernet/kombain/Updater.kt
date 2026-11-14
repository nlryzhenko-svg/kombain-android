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
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Updates of DAK itself: new versions are published as GitHub releases of the app's repository
 * (tag v4.1.0, files DAK-4.1.0-arm64-v8a.apk …). Downloads go through the VPN when it is on.
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

    private fun client() = Libcore.newHttpClient().apply {
        modernTLS(); keepAlive()
        if (DataStore.serviceState.started) trySocks5(DataStore.mixedPort)
    }

    /** The newest release when it is newer than this app; null otherwise. Throws on network errors. */
    fun check(): Release? {
        val client = client()
        try {
            val r = JSONObject(Util.getStringBox(client.newRequest().apply {
                setURL("https://api.github.com/repos/$REPO/releases/latest")
            }.execute().contentString))
            DataStore.kbUpdateChecked = System.currentTimeMillis()
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
        } finally {
            client.close()
        }
    }

    /** Time for the automatic check (twice a day at most). */
    fun due() = DataStore.kbAutoUpdate && System.currentTimeMillis() - DataStore.kbUpdateChecked > CHECK_EVERY

    private fun dir() = File(app.cacheDir, "update").apply { mkdirs() }

    /** Downloads the APK of [r]; verifies its size and SHA-256 when GitHub gives them. */
    fun download(r: Release): File {
        dir().listFiles()?.forEach { it.delete() }
        val file = File(dir(), "DAK-${r.version}.apk")
        val client = client()
        try {
            client.newRequest().apply { setURL(r.url) }.execute().writeTo(file.canonicalPath)
        } finally {
            client.close()
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
