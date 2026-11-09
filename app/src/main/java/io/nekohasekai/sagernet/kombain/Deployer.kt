package io.nekohasekai.sagernet.kombain

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import org.json.JSONObject

/**
 * Drives server deployment and keeps one shared session (log + state), so the screen
 * can be left and reopened while an install is running.
 */
object Deployer {

    val log = StringBuilder()
    @Volatile
    var running = false
        private set
    var lastOk: Boolean? = null
        private set
    var lastMessage: String = ""
        private set
    var listener: (() -> Unit)? = null

    private fun emit(line: String) {
        synchronized(log) { log.append(line).append('\n') }
        notifyUi()
    }

    private fun notifyUi() {
        runOnDefaultDispatcher { onMainDispatcher { listener?.invoke() } }
    }

    private fun start(title: String): Boolean {
        if (running) return false
        running = true
        lastOk = null
        synchronized(log) { log.setLength(0) }
        emit(title)
        return true
    }

    private fun finish(ok: Boolean, message: String) {
        running = false
        lastOk = ok
        lastMessage = message
        emit(if (ok) "✔ $message" else "✖ $message")
    }

    private fun installArgs(n: ServerNode, extra: List<String> = emptyList()) = listOf(
        "install", "--host", n.host.trim(), "--protocols", n.protocols, "--sni", n.sni.trim(),
        "--port", n.vpnPort.toString(), "--hy-port", n.hyPort.toString(), "--name", n.name.trim(),
    ) + extra

    // ---------------------------------------------------------------- public actions

    fun installSingle(node: ServerNode) {
        if (!start("Установка на ${node.name} (${node.host})")) return
        runOnDefaultDispatcher {
            node.role = "single"
            val r = SshRunner(node, ::emit).run(installArgs(node))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "установка не удалась")
            node.installedAt = System.currentTimeMillis()
            val n = importLinks(node, node.name, r.json, replace = true)
            ServerStore.upsert(node)
            finish(true, "Готово: добавлено профилей — $n. Они в группе «${node.name}».")
        }
    }

    fun installCascade(exit: ServerNode, entry: ServerNode) {
        if (!start("Каскад: ${entry.name} → ${exit.name}")) return
        runOnDefaultDispatcher {
            emit("── Шаг 1 из 2: выходной сервер ${exit.name}")
            exit.role = "exit"
            val r1 = SshRunner(exit, ::emit).run(installArgs(exit, listOf("--bridge")))
            val bridge = r1.json?.optString("bridge").orEmpty()
            if (!r1.ok || bridge.isBlank()) return@runOnDefaultDispatcher finish(false, r1.error ?: "выходной сервер не установился")
            exit.installedAt = System.currentTimeMillis()
            ServerStore.upsert(exit)

            emit("── Шаг 2 из 2: входной сервер ${entry.name}")
            entry.role = "entry"
            entry.exitId = exit.id
            val r2 = SshRunner(entry, ::emit).run(installArgs(entry, listOf("--role", "entry", "--exit-link", bridge)))
            if (!r2.ok || r2.json == null) return@runOnDefaultDispatcher finish(false, r2.error ?: "входной сервер не установился")
            entry.installedAt = System.currentTimeMillis()
            val group = "${entry.name} → ${exit.name}"
            val n = importLinks(entry, group, r2.json, replace = true)
            ServerStore.upsert(entry)
            finish(true, "Готово: добавлено профилей — $n. Они в группе «$group».")
        }
    }

    fun addUser(node: ServerNode, userName: String) {
        if (!start("Новый пользователь «$userName» на ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("add-user", userName))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось добавить")
            ServerStore.upsert(node)
            val links = r.json.optJSONArray("links")
            val mine = (0 until (links?.length() ?: 0)).map { links!!.getJSONObject(it) }
                .filter { it.optString("user") == userName }
            finish(true, "Пользователь добавлен. Ссылки ниже — отправьте их на его устройство.")
            mine.forEach { emit(it.optString("link")) }
        }
    }

    fun refreshLinks(node: ServerNode) {
        if (!start("Обновляю профили с ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("links"))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось получить ссылки")
            val n = importLinks(node, groupName(node), r.json, replace = true, onlyFirstUser = true)
            ServerStore.upsert(node)
            finish(true, "Профили обновлены: $n.")
        }
    }

    fun status(node: ServerNode) {
        if (!start("Состояние ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("status"))
            if (r.error != null) finish(false, r.error) else finish(true, "Проверка завершена")
        }
    }

    fun uninstall(node: ServerNode) {
        if (!start("Удаляю Kombain с ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("uninstall"))
            if (r.error != null) return@runOnDefaultDispatcher finish(false, r.error)
            ServerStore.remove(node.id)
            finish(true, "Сервер очищен и убран из списка. Профили в приложении можно удалить вручную.")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun groupName(node: ServerNode): String {
        if (node.role == "entry") {
            val exit = ServerStore.get(node.exitId)
            if (exit != null) return "${node.name} → ${exit.name}"
        }
        return node.name
    }

    /** Puts the first user's links into the server's group. Returns the profile count. */
    private suspend fun importLinks(
        node: ServerNode, groupName: String, json: JSONObject, replace: Boolean, onlyFirstUser: Boolean = true,
    ): Int {
        val arr = json.optJSONArray("links") ?: return 0
        val items = (0 until arr.length()).map { arr.getJSONObject(it) }
        val firstUser = items.firstOrNull()?.optString("user")
        val chosen = if (onlyFirstUser) items.filter { it.optString("user") == firstUser } else items

        var groupId = node.groupId
        if (groupId == 0L || SagerDatabase.groupDao.getById(groupId) == null) {
            groupId = GroupManager.createGroup(ProxyGroup(name = groupName)).id
            node.groupId = groupId
        } else if (replace) {
            SagerDatabase.proxyDao.deleteByGroup(groupId)
            GroupManager.postReload(groupId)
        }
        var count = 0
        var firstProfile = 0L
        for (item in chosen) {
            val bean = runCatching { parseProxies(item.optString("link")).firstOrNull() }.getOrNull() ?: continue
            // our servers run current Xray: use the Xray engine for VLESS (sing-box Reality is rejected)
            (bean as? io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean)?.xrayEngine = true
            val p = ProfileManager.createProfile(groupId, bean)
            if (firstProfile == 0L) firstProfile = p.id
            count++
        }
        GroupManager.postReload(groupId)
        if (firstProfile != 0L) {
            DataStore.selectedGroup = groupId
            DataStore.selectedProxy = firstProfile
        }
        return count
    }
}
