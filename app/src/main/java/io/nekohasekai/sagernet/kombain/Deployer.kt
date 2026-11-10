package io.nekohasekai.sagernet.kombain

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.applyDefaultValues
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
    /** called on the main thread after add-user, to show the new user's QR codes */
    var userAdded: ((ServerNode, String, JSONObject) -> Unit)? = null
    /** called on the main thread when the server's Telegram proxy link is ready */
    var telegramReady: ((ServerNode, String) -> Unit)? = null

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
        if (ok) runCatching { Maintenance.schedule() }
    }

    private fun installArgs(n: ServerNode, extra: List<String> = emptyList()) = listOf(
        "install", "--host", n.host.trim(), "--protocols", n.protocols, "--sni", n.sni.trim(),
        "--port", n.vpnPort.toString(), "--hy-port", n.hyPort.toString(),
        "--xh-port", n.xhPort.toString(), "--tuic-port", n.tuicPort.toString(), "--tg-port", n.tgPort.toString(),
        "--name", n.name.trim(),
    ) + (if (n.dest.isNotBlank()) listOf("--dest", n.dest) else emptyList()) + extra

    /** Keeps the stored node in step with what the server reports (protocols, ports). */
    private fun syncInfo(node: ServerNode, json: JSONObject) {
        val i = json.optJSONObject("info") ?: return
        i.optString("protocols").takeIf { it.isNotBlank() }?.let { node.protocols = it }
        i.optString("sni").takeIf { it.isNotBlank() }?.let { node.sni = it }
        if (i.has("dest")) node.dest = i.optString("dest")
        node.vpnPort = i.optInt("port", node.vpnPort); node.xhPort = i.optInt("xhPort", node.xhPort)
        node.hyPort = i.optInt("hyPort", node.hyPort); node.tuicPort = i.optInt("tuicPort", node.tuicPort)
        node.tgPort = i.optInt("tgPort", node.tgPort)
    }

    private fun telegramOf(json: JSONObject?) = json?.optString("telegram").orEmpty()

    private fun tgNote(json: JSONObject?) =
        if (telegramOf(json).isNotEmpty()) " Ссылка на прокси для Telegram — в «Пользователи»." else ""

    // ---------------------------------------------------------------- public actions

    fun installSingle(node: ServerNode) {
        if (!start("Установка на ${node.name} (${node.host})")) return
        runOnDefaultDispatcher {
            node.role = "single"
            val r = SshRunner(node, ::emit).run(installArgs(node))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "установка не удалась")
            node.installedAt = System.currentTimeMillis()
            syncInfo(node, r.json)
            val n = importLinks(node, node.name, r.json, replace = true)
            ServerStore.upsert(node)
            finish(true, "Готово: добавлено профилей — $n. Они в группе «${node.name}».${tgNote(r.json)}")
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
            syncInfo(entry, r2.json)
            val group = "${entry.name} → ${exit.name}"
            val n = importLinks(entry, group, r2.json, replace = true)
            ServerStore.upsert(entry)
            finish(true, "Готово: добавлено профилей — $n. Они в группе «$group».${tgNote(r2.json)}")
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
            finish(true, "Пользователь добавлен. Ссылки и QR-коды — в «Пользователи».")
            mine.forEach { emit(it.optString("link")) }
            onMainDispatcher { userAdded?.invoke(node, userName, r.json) }
        }
    }

    fun refreshLinks(node: ServerNode) {
        if (!start("Обновляю профили с ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("ensure-sub"))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось получить ссылки")
            syncInfo(node, r.json)
            val n = importLinks(node, groupName(node), r.json, replace = true, onlyFirstUser = true)
            ServerStore.upsert(node)
            finish(true, "Профили обновлены: $n.")
        }
    }

    fun status(node: ServerNode) {
        if (!start("Состояние ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("stats"))
            val st = r.stats
            if (st == null) {
                node.health = r.error ?: "нет ответа"
                ServerStore.upsert(node)
                return@runOnDefaultDispatcher finish(false, r.error ?: "сервер не ответил")
            }
            saveStats(node, st)
            ServerHealth.describe(st).forEach { emit("• $it") }
            finish(true, if (node.health.isEmpty()) "Всё работает" else node.health)
        }
    }

    /** New Reality keys, paths, passwords (and optionally ports). Cascade nodes are re-linked. */
    fun rotate(node: ServerNode, ports: Map<String, Int> = emptyMap()) {
        if (!start("Новые ключи для ${node.name}")) return
        runOnDefaultDispatcher {
            val args = mutableListOf("rotate")
            ports["vless"]?.let { args += listOf("--port", it.toString()); node.vpnPort = it }
            ports["xhttp"]?.let { args += listOf("--xh-port", it.toString()); node.xhPort = it }
            ports["hy2"]?.let { args += listOf("--hy-port", it.toString()); node.hyPort = it }
            ports["tuic"]?.let { args += listOf("--tuic-port", it.toString()); node.tuicPort = it }
            ports["tg"]?.let { args += listOf("--tg-port", it.toString()); node.tgPort = it }
            val r = SshRunner(node, ::emit).run(args)
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось сменить ключи")
            syncInfo(node, r.json)
            ServerStore.upsert(node)
            when (node.role) {
                "exit" -> {
                    // the entry node must learn the exit's new credentials
                    val bridge = r.json.optString("bridge")
                    val entry = ServerStore.all().firstOrNull { it.role == "entry" && it.exitId == node.id }
                    if (entry == null || bridge.isBlank()) return@runOnDefaultDispatcher finish(true, "Ключи заменены.")
                    emit("── Переключаю входной сервер ${entry.name} на новые ключи")
                    val r2 = SshRunner(entry, ::emit).run(listOf("set-exit", "--exit-link", bridge))
                    if (!r2.ok || r2.json == null) return@runOnDefaultDispatcher finish(false, r2.error ?: "входной сервер не переключился")
                    ServerStore.upsert(entry)
                    finish(true, "Ключи выходного сервера заменены, каскад продолжает работать.")
                }

                else -> {
                    val n = importLinks(node, groupName(node), r.json, replace = true)
                    ServerStore.upsert(node)
                    finish(true, "Ключи заменены, профили обновлены: $n. Другим пользователям отправьте новые ссылки." +
                            if (telegramOf(r.json).isNotEmpty()) " Ссылка Telegram-прокси тоже новая." else "")
                }
            }
        }
    }

    /** All users and their links (no import) — for the sharing sheet. */
    fun fetchLinks(node: ServerNode, done: (JSONObject?, String?) -> Unit) {
        if (!start("Пользователи ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("ensure-sub"))
            r.json?.let { syncInfo(node, it) }
            ServerStore.upsert(node)
            if (!r.ok || r.json == null) finish(false, r.error ?: "не удалось получить ссылки")
            else finish(true, "Список пользователей получен")
            onMainDispatcher { done(r.json, r.error) }
        }
    }

    /**
     * Adds a server where Kombain is already installed (e.g. from another device):
     * reads its links and settings, nothing on the server changes.
     */
    fun connectExisting(node: ServerNode) {
        if (!start("Подключаю ${node.host}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("ensure-sub"))
            val json = r.json
            if (!r.ok || json == null) {
                return@runOnDefaultDispatcher finish(false, r.error ?: "на сервере не найден Kombain")
            }
            // the same server added twice: update the existing card
            ServerStore.all().firstOrNull { it.host.trim() == node.host.trim() && it.id != node.id }?.let { old ->
                node.id = old.id; node.groupId = old.groupId; node.exitId = old.exitId
            }
            syncInfo(node, json)
            val info = json.optJSONObject("info")
            if (node.name.isBlank()) node.name = info?.optString("name").orEmpty().ifBlank { node.host }
            node.role = when {
                json.optString("role") == "entry" -> "entry"
                json.optString("bridge").isNotBlank() -> "exit"
                else -> "single"
            }
            node.installedAt = System.currentTimeMillis()
            var n = 0
            if (node.role != "exit") n = importLinks(node, groupName(node), json, replace = true)
            ServerStore.upsert(node)
            emit("• Проверяю состояние")
            val st = SshRunner(node, ::emit).run(listOf("stats")).stats
            if (st != null) {
                saveStats(node, st)
                ServerHealth.describe(st).forEach { emit("• $it") }
            }
            finish(true, when (node.role) {
                "exit" -> "Выходной сервер каскада добавлен. Профили берутся с входного сервера."
                else -> "Сервер добавлен, профилей — $n, группа «${groupName(node)}».${tgNote(json)}"
            })
        }
    }

    /** Sites next to the server that suit Reality (no changes on the server). */
    fun findSites(node: ServerNode, done: (org.json.JSONArray?) -> Unit) {
        if (!start("Ищу сайт для маскировки рядом с ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("find-sni", "--host", node.host.trim()))
            ServerStore.upsert(node)
            val n = r.sites?.length() ?: 0
            if (r.sites == null) finish(false, r.error ?: "не удалось проверить")
            else finish(true, if (n > 0) "Найдено подходящих сайтов: $n — выберите один." else "Рядом подходящих сайтов нет — оставьте текущий.")
            onMainDispatcher { done(r.sites) }
        }
    }

    fun setSite(node: ServerNode, domain: String, dest: String) {
        if (!start("Новый сайт для маскировки на ${node.name}")) return
        runOnDefaultDispatcher {
            val args = mutableListOf("set-sni", "--sni", domain)
            if (dest.isNotBlank()) args += listOf("--dest", dest)
            val r = SshRunner(node, ::emit).run(args)
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось сменить")
            syncInfo(node, r.json)
            val n = if (node.role != "exit") importLinks(node, groupName(node), r.json, replace = true) else 0
            ServerStore.upsert(node)
            finish(true, if (node.role == "exit") "Маскировка сменена." else
                "Маскировка: $domain. Профили обновлены ($n), у остальных устройств подписка обновится сама.")
        }
    }

    fun enableTelegram(node: ServerNode, port: Int) {
        if (!start("Прокси для Telegram на ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("enable-telegram", "--tg-port", port.toString()))
            val link = telegramOf(r.json)
            if (!r.ok || r.json == null || link.isEmpty()) {
                return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось включить прокси")
            }
            syncInfo(node, r.json)
            ServerStore.upsert(node)
            finish(true, "Прокси для Telegram работает.")
            emit(link)
            onMainDispatcher { telegramReady?.invoke(node, link) }
        }
    }

    fun disableTelegram(node: ServerNode) {
        if (!start("Выключаю прокси для Telegram на ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("disable-telegram"))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось выключить")
            syncInfo(node, r.json)
            ServerStore.upsert(node)
            finish(true, "Прокси для Telegram выключен.")
        }
    }

    fun removeUser(node: ServerNode, userName: String) {
        if (!start("Удаляю пользователя «$userName» с ${node.name}")) return
        runOnDefaultDispatcher {
            val r = SshRunner(node, ::emit).run(listOf("remove-user", userName))
            if (!r.ok) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось удалить")
            ServerStore.upsert(node)
            finish(true, "Пользователь «$userName» удалён — его ссылки больше не работают.")
        }
    }

    fun saveStats(node: ServerNode, st: JSONObject) {
        node.stats = st.toString()
        node.statsAt = System.currentTimeMillis()
        node.health = ServerHealth.problem(st)
        ServerStore.upsert(node)
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

        // the first user's subscription keeps this device's profiles up to date after key changes
        val subUrl = json.optJSONArray("subs")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("user") == firstUser }?.optString("url")
        }.orEmpty()
        fun ProxyGroup.applySub() {
            if (subUrl.isBlank()) return
            type = io.nekohasekai.sagernet.GroupType.SUBSCRIPTION
            subscription = (subscription ?: io.nekohasekai.sagernet.database.SubscriptionBean()).applyDefaultValues().apply {
                link = subUrl
                lastUpdated = (System.currentTimeMillis() / 1000).toInt()
            }
        }
        var groupId = node.groupId
        if (groupId == 0L || SagerDatabase.groupDao.getById(groupId) == null) {
            groupId = GroupManager.createGroup(ProxyGroup(name = groupName).apply { applySub() }).id
            node.groupId = groupId
        } else {
            SagerDatabase.groupDao.getById(groupId)?.let {
                val oldLink = it.subscription?.link
                if (it.name != groupName || (subUrl.isNotBlank() && oldLink != subUrl)) {
                    it.name = groupName
                    it.applySub()
                    GroupManager.updateGroup(it)
                }
            }
            if (replace) {
                SagerDatabase.proxyDao.deleteByGroup(groupId)
                GroupManager.postReload(groupId)
            }
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
