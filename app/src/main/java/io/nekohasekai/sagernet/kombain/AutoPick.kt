package io.nekohasekai.sagernet.kombain

import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Tests servers with a real HTTP request through each of them and ranks the live ones.
 * Results are written to the profiles (status / ping / error) so the server list shows them.
 */
object AutoPick {

    class Result(val total: Int, val alive: List<Pair<ProxyEntity, Int>>) {
        val best get() = alive.firstOrNull()
    }

    private val lock = Mutex()

    /** Every profile of the group that the selected server belongs to. */
    fun groupOf(profileId: Long): List<ProxyEntity> {
        val current = SagerDatabase.proxyDao.getById(profileId)
        val groupId = current?.groupId ?: DataStore.currentGroupId()
        return SagerDatabase.proxyDao.getByGroup(groupId)
    }

    suspend fun test(
        profiles: List<ProxyEntity>,
        persist: Boolean = true,
        onEach: (suspend (ProxyEntity) -> Unit)? = null,
    ): Result = lock.withLock {
        val alive = mutableListOf<Pair<ProxyEntity, Int>>()
        val queue = ConcurrentLinkedQueue(profiles)
        val urlTest = UrlTest()
        coroutineScope {
            repeat(DataStore.connectionTestConcurrent.coerceIn(1, 8)) {
                launch(Dispatchers.IO) {
                    while (true) {
                        val p = queue.poll() ?: break
                        try {
                            // hard cap: a hung plugin must not stall the whole check
                            val ping = withTimeoutOrNull(25_000) { urlTest.doTest(p) }
                                ?: error("timeout")
                            p.status = 1
                            p.ping = ping
                            p.error = null
                            synchronized(alive) { alive += p to ping }
                        } catch (e: PluginManager.PluginNotFoundException) {
                            p.ping = 0
                            p.status = 2
                            p.error = e.readableMessage
                        } catch (e: Exception) {
                            p.ping = 0
                            p.status = 3
                            p.error = e.readableMessage
                        }
                        if (persist) runCatching { ProfileManager.updateProfile(p) }.onFailure { Logs.w(it) }
                        onEach?.invoke(p)
                    }
                }
            }
        }
        Result(profiles.size, alive.sortedBy { it.second })
    }

    /**
     * The server to use: the current one if it is alive and not much slower than the best,
     * otherwise the fastest live one. Null when nothing answered.
     */
    fun choose(result: Result, currentId: Long): ProxyEntity? {
        val best = result.best ?: return null
        val current = result.alive.find { it.first.id == currentId }
        if (current != null && current.second <= best.second * 3 / 2 + 50) return current.first
        return best.first
    }
}
