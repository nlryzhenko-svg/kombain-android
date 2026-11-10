package io.nekohasekai.sagernet.kombain

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.MainActivity
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kombain background upkeep, every 6 hours while the phone has internet:
 *  - checks the user's own servers and notifies when a service went down;
 *  - refreshes all subscriptions once a day;
 *  - refreshes geoip / geosite once a week.
 */
object Maintenance {

    private const val WORK_NAME = "KombainMaintenance"
    const val CHANNEL = "kombain-servers"

    fun schedule() {
        val wm = RemoteWorkManager.getInstance(app)
        val needed = (DataStore.kbWatchServers && ServerStore.all().isNotEmpty()) ||
                DataStore.kbAutoSubs || DataStore.kbAutoGeo
        if (!needed) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        wm.enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(Task::class.java, 6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
        )
    }

    // ------------------------------------------------------------------ servers

    suspend fun checkServers(ctx: Context) {
        for (node in ServerStore.all()) {
            val before = node.health
            val r = SshRunner(node) {}.run(listOf("stats"))
            val st = r.stats
            if (st != null) Deployer.saveStats(node, st) else {
                node.health = "не отвечает по SSH"
                ServerStore.upsert(node)
            }
            if (node.health.isNotEmpty() && node.health != before) notify(ctx, node)
        }
    }

    private fun notify(ctx: Context, node: ServerNode) {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("kombain_open", "servers"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle(ctx.getString(R.string.kb_srv_problem_title, node.name))
            .setContentText(node.health)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(node.id.hashCode(), n) }
    }

    // ------------------------------------------------------------------ subscriptions

    suspend fun updateSubscriptions() {
        val now = System.currentTimeMillis() / 1000
        for (group in SagerDatabase.groupDao.subscriptions()) {
            val sub = group.subscription ?: continue
            if (now - sub.lastUpdated < 20 * 3600) continue
            runCatching { GroupUpdater.executeUpdate(group, false) }.onFailure { Logs.w(it) }
        }
    }

    // ------------------------------------------------------------------ geo bases

    fun geoDir(): File = app.getExternalFilesDir(null) ?: app.filesDir

    /** Downloads newer geoip.db / geosite.db from SagerNet's releases. Returns true if anything changed. */
    fun updateGeo(force: Boolean = false): Boolean {
        val dir = geoDir()
        var changed = false
        for ((file, repo) in listOf("geoip.db" to "SagerNet/sing-geoip", "geosite.db" to "SagerNet/sing-geosite")) {
            val target = File(dir, file)
            val versionFile = File(dir, file.substringBefore('.') + ".version.txt")
            if (!force && versionFile.exists() && System.currentTimeMillis() - versionFile.lastModified() < 6L * 86400_000) continue
            val client = Libcore.newHttpClient().apply {
                modernTLS(); keepAlive()
                if (DataStore.serviceState.started) trySocks5(DataStore.mixedPort)
            }
            try {
                val release = JSONObject(Util.getStringBox(client.newRequest().apply {
                    setURL("https://api.github.com/repos/$repo/releases/latest")
                }.execute().contentString))
                val tag = release.optString("tag_name")
                val local = runCatching { versionFile.readText().trim() }.getOrDefault("")
                if (tag.isBlank()) continue
                if (tag == local) { versionFile.setLastModified(System.currentTimeMillis()); continue }
                val assets = release.getJSONArray("assets")
                var url = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == file) url = a.optString("browser_download_url")
                }
                if (url.isBlank()) continue
                val tmp = File(dir, "$file.tmp")
                client.newRequest().apply { setURL(url) }.execute().writeTo(tmp.canonicalPath)
                if (tmp.length() < 1024) { tmp.delete(); continue }
                tmp.renameTo(target)
                versionFile.writeText(tag)
                changed = true
                Logs.i("[Kombain] база $file обновлена до $tag")
            } catch (e: Exception) {
                Logs.w(e)
            } finally {
                client.close()
            }
        }
        if (changed) DataStore.kbGeoUpdated = System.currentTimeMillis()
        return changed
    }

    class Task(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            if (DataStore.kbWatchServers) runCatching { checkServers(applicationContext) }.onFailure { Logs.w(it) }
            if (DataStore.kbAutoSubs) runCatching { updateSubscriptions() }.onFailure { Logs.w(it) }
            if (DataStore.kbAutoGeo) runCatching { updateGeo() }.onFailure { Logs.w(it) }
            return Result.success()
        }
    }
}
