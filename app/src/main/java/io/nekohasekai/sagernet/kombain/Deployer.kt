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

    /** Runs the node script and keeps the backup it returns (for «Переехать на новый сервер»). */
    private fun ssh(node: ServerNode) = object {
        fun run(args: List<String>): NodeResult {
            val r = SshRunner(node, ::emit).run(args)
            if (r.ok) r.export?.takeIf { it.length > 100 }?.let {
                node.backup = it
                node.backupAt = System.currentTimeMillis()
            }
            return r
        }
    }

    /** Reserve subscription (GitHub gist): every device finds the server after a move. Never fatal. */
    private fun publishReserve(node: ServerNode, json: JSONObject) {
        if (!AltSub.enabled) return
        runCatching { AltSub.publish(node, json) }
            .onSuccess { if (it > 0) emit("• Резервный адрес подписки обновлён ($it)") }
            .onFailure { emit("• Резервный адрес подписки не обновился: ${it.message}") }
    }

    private fun installArgs(n: ServerNode, extra: List<String> = emptyList()) = listOf(
        "install", "--host", n.host.trim(), "--protocols", n.protocols, "--sni", n.sni.trim(),
        "--port", n.vpnPort.toString(), "--hy-port", n.hyPort.toString(),
        "--xh-port", n.xhPort.toString(), "--tuic-port", n.tuicPort.toString(), "--tg-port", n.tgPort.toString(), "--sd-port", n.sdPort.toString(),
        "--name", n.name.trim(),
        "--fp", n.fp, "--antifreeze", if (n.antifreeze) "1" else "0",
        "--sni-rotation", if (n.sniRotation) "1" else "0", "--hop", if (n.hop) "1" else "0",
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
        node.sdPort = i.optInt("sdPort", node.sdPort)
        if (json.has("telegram")) node.tgLink = json.optString("telegram")
        node.autoPorts = i.optInt("autoPorts", node.autoPorts)
        if (i.has("fp")) {
            node.fp = i.optString("fp", node.fp)
            node.antifreeze = i.optInt("antifreeze") == 1
            node.sniRotation = i.optInt("sniRotation") == 1
            node.sniAlts = i.optString("sniAlts")
            node.hyHop = i.optString("hyHop")
            node.hop = node.hyHop.isNotBlank()
        }
    }

    private fun telegramOf(json: JSONObject?) = json?.optString("telegram").orEmpty()

    private fun tgNote(json: JSONObject?) =
        if (telegramOf(json).isNotEmpty()) " Ссылка на прокси для Telegram — на карточке сервера («✈ Прокси для Telegram»)." else ""

    // ---------------------------------------------------------------- public actions

    fun installSingle(node: ServerNode) {
        if (!start("Установка на ${node.name} (${node.host})")) return
        runOnDefaultDispatcher {
            node.role = "single"
            val r = ssh(node).run(installArgs(node))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "установка не удалась")
            node.installedAt = System.currentTimeMillis()
            syncInfo(node, r.json)
            val n = importLinks(node, node.name, r.json, replace = true)
            ServerStore.upsert(node)
            finish(true, "Готово: добавлено профилей — $n. Они в группе «${node.name}».${tgNote(r.json)}")
            telegramOf(r.json).takeIf { it.isNotEmpty() }?.let { onMainDispatcher { telegramReady?.invoke(node, it) } }
        }
    }

    fun installCascade(exit: ServerNode, entry: ServerNode) {
        if (!start("Каскад: ${entry.name} → ${exit.name}")) return
        runOnDefaultDispatcher {
            emit("── Шаг 1 из 2: выходной сервер ${exit.name}")
            exit.role = "exit"
            val r1 = ssh(exit).run(installArgs(exit, listOf("--bridge")))
            val bridge = r1.json?.optString("bridge").orEmpty()
            if (!r1.ok || bridge.isBlank()) return@runOnDefaultDispatcher finish(false, r1.error ?: "выходной сервер не установился")
            exit.installedAt = System.currentTimeMillis()
            ServerStore.upsert(exit)

            emit("── Шаг 2 из 2: входной сервер ${entry.name}")
            runCatching { Whitelist.describe(Whitelist.check(entry.host, entry.sni)) }.getOrNull()
                ?.lines()?.forEach { emit(it) }
            entry.role = "entry"
            entry.exitId = exit.id
            val r2 = ssh(entry).run(installArgs(entry, listOf("--role", "entry", "--exit-link", bridge)))
            if (!r2.ok || r2.json == null) return@runOnDefaultDispatcher finish(false, r2.error ?: "входной сервер не установился")
            entry.installedAt = System.currentTimeMillis()
            syncInfo(entry, r2.json)
            val group = "${entry.name} → ${exit.name}"
            val n = importLinks(entry, group, r2.json, replace = true)
            ServerStore.upsert(entry)
            finish(true, "Готово: добавлено профилей — $n. Они в группе «$group».${tgNote(r2.json)}")
            telegramOf(r2.json).takeIf { it.isNotEmpty() }?.let { onMainDispatcher { telegramReady?.invoke(entry, it) } }
        }
    }

    /** The Russian entry node that forwards to [node], if there is one. */
    fun entryOf(node: ServerNode): ServerNode? = ServerStore.all().firstOrNull { it.role == "entry" && it.exitId == node.id }

    /** Points the entry node of [node] at its new address / keys / port. Null when fine (or nothing to do). */
    private fun relinkEntry(node: ServerNode, json: JSONObject, why: String): String? {
        val entry = entryOf(node) ?: return null
        val bridge = json.optString("bridge").takeIf { it.isNotBlank() } ?: return null
        emit("── $why: входной сервер ${entry.name}")
        val r2 = ssh(entry).run(listOf("set-exit", "--exit-link", bridge))
        if (!r2.ok) return r2.error ?: "входной сервер не переключился"
        ServerStore.upsert(entry)
        return null
    }

    /**
     * «Добавить вход в России»: [entry] (a VPS in Russia) becomes the entry of the already working [exit].
     * The exit only gets a bridge user — its keys, users, ports and own profiles stay as they are.
     */
    fun attachEntry(exit: ServerNode, entry: ServerNode) {
        if (!start("Вход в России: ${entry.name} → ${exit.name}")) return
        runOnDefaultDispatcher {
            emit("── Шаг 1 из 2: связь на сервере ${exit.name}")
            val r1 = ssh(exit).run(listOf("enable-bridge"))
            val bridge = r1.json?.optString("bridge").orEmpty()
            if (!r1.ok || r1.json == null || bridge.isBlank()) return@runOnDefaultDispatcher finish(false, r1.error ?: "сервер ${exit.name} не ответил")
            syncInfo(exit, r1.json)
            ServerStore.upsert(exit)

            emit("── Шаг 2 из 2: входной сервер ${entry.name}")
            runCatching { Whitelist.describe(Whitelist.check(entry.host, entry.sni)) }.getOrNull()
                ?.lines()?.forEach { emit(it) }
            entry.role = "entry"
            entry.exitId = exit.id
            val r2 = ssh(entry).run(installArgs(entry, listOf("--role", "entry", "--exit-link", bridge)))
            if (!r2.ok || r2.json == null) return@runOnDefaultDispatcher finish(false, r2.error ?: "входной сервер не установился")
            entry.installedAt = System.currentTimeMillis()
            syncInfo(entry, r2.json)
            val group = "${entry.name} → ${exit.name}"
            val n = importLinks(entry, group, r2.json, replace = true)
            ServerStore.upsert(entry)
            finish(true, "Готово: вход в России работает, профилей — $n, группа «$group». " +
                    "Прямые профили «${exit.name}» тоже остались.${tgNote(r2.json)}")
            telegramOf(r2.json).takeIf { it.isNotEmpty() }?.let { onMainDispatcher { telegramReady?.invoke(entry, it) } }
        }
    }

    fun addUser(node: ServerNode, userName: String) {
        if (!start("Новый пользователь «$userName» на ${node.name}")) return
        runOnDefaultDispatcher {
            val r = ssh(node).run(listOf("add-user", userName))
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
            val r = ssh(node).run(listOf("ensure-sub"))
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
            val r = ssh(node).run(listOf("stats"))
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
            ports["sudoku"]?.let { args += listOf("--sd-port", it.toString()); node.sdPort = it }
            val r = ssh(node).run(args)
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось сменить ключи")
            syncInfo(node, r.json)
            ServerStore.upsert(node)
            relinkEntry(node, r.json, "Переключаю на новые ключи")?.let { return@runOnDefaultDispatcher finish(false, it) }
            if (node.role == "exit") return@runOnDefaultDispatcher finish(true, "Ключи выходного сервера заменены, каскад продолжает работать.")
            val n = importLinks(node, groupName(node), r.json, replace = true)
            ServerStore.upsert(node)
            finish(true, "Ключи заменены, профили обновлены: $n. Другим пользователям отправьте новые ссылки." +
                    (if (entryOf(node) != null) " Вход в России переключён." else "") +
                    if (telegramOf(r.json).isNotEmpty()) " Ссылка Telegram-прокси тоже новая." else "")
        }
    }

    /** All users and their links (no import) — for the sharing sheet. */
    fun fetchLinks(node: ServerNode, done: (JSONObject?, String?) -> Unit) {
        if (!start("Пользователи ${node.name}")) return
        runOnDefaultDispatcher {
            val r = ssh(node).run(listOf("ensure-sub"))
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
            val r = ssh(node).run(listOf("ensure-sub"))
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
            val st = ssh(node).run(listOf("stats")).stats
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
    /** Sites next to the server for the masking choice; read-only, so not shown as a server operation. */
    fun scanSites(node: ServerNode, done: (org.json.JSONArray?, String?) -> Unit) {
        runOnDefaultDispatcher {
            val r = SshRunner(node) { }.run(listOf("find-sni", "--host", node.host.trim()))
            onMainDispatcher { done(r.sites, r.error) }
        }
    }

    /** Random new ports for the VPN protocols; keys, the Telegram proxy and the subscription stay. */
    fun shufflePorts(node: ServerNode) {
        if (!start("Новые порты для ${node.name}")) return
        runOnDefaultDispatcher {
            val args = if (node.role == "exit" || entryOf(node) != null) listOf("shuffle-ports", "--relink") else listOf("shuffle-ports")
            val r = ssh(node).run(args)
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось сменить порты")
            syncInfo(node, r.json)
            ServerStore.upsert(node)
            relinkEntry(node, r.json, "Переключаю на новый порт")?.let { return@runOnDefaultDispatcher finish(false, it) }
            if (node.role == "exit") return@runOnDefaultDispatcher finish(true, "Порты выходного сервера сменены, каскад работает.")
            val n = importLinks(node, groupName(node), r.json, replace = true)
            ServerStore.upsert(node)
            finish(true, "Порты сменены, профили обновлены ($n). Telegram-прокси не тронут, устройства с подпиской обновятся сами.")
        }
    }

    /** Protection against DPI freezes: XHTTP hardening, TLS fingerprint, rotation of site names, port hopping. */
    fun harden(node: ServerNode, antifreeze: Boolean, fp: String, sniRotation: Boolean, hop: Boolean, site: Pair<String, String>? = null) {
        if (!start("Защита от блокировок на ${node.name}")) return
        runOnDefaultDispatcher {
            val args = mutableListOf("harden", "--antifreeze", if (antifreeze) "on" else "off", "--fp", fp,
                "--sni-rotation", if (sniRotation) "on" else "off")
            if (node.protocols.split(',').contains("hy2")) args += listOf("--hop", if (hop) "on" else "off")
            if (site != null) {
                args += listOf("--sni", site.first)
                if (site.second.isNotBlank()) args += listOf("--dest", site.second)
            }
            val r = ssh(node).run(args)
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не получилось")
            syncInfo(node, r.json)
            ServerStore.upsert(node)
            // the entry node dials this server with its fingerprint
            relinkEntry(node, r.json, "Обновляю связь")?.let { return@runOnDefaultDispatcher finish(false, it) }
            if (node.role == "exit") return@runOnDefaultDispatcher finish(true, "Защита выходного сервера обновлена, каскад работает.")
            val n = importLinks(node, groupName(node), r.json, replace = true)
            ServerStore.upsert(node)
            finish(true, "Защита обновлена, профили обновлены ($n). Устройства с подпиской получат её сами.")
        }
    }

    /**
     * «Переехать на новый сервер»: the same keys, users and settings on [target] (a fresh VPS), from the backup
     * the app keeps — works even when the old server can't be reached any more. Only the address changes:
     * this phone's profiles are updated, other devices find the new address through the reserve subscription.
     */
    fun move(old: ServerNode, target: ServerNode) {
        if (!start("Переезд «${old.name}»: ${old.host} → ${target.host}")) return
        runOnDefaultDispatcher {
            var data = old.backup
            if (data.isBlank()) {
                emit("── Резервной копии в приложении нет — забираю настройки со старого сервера")
                val r0 = ssh(old).run(listOf("export"))
                data = r0.export.orEmpty()
                if (data.isBlank()) return@runOnDefaultDispatcher finish(false,
                    "Старый сервер недоступен (${r0.error ?: "нет ответа"}), а резервной копии нет. Поднимите новый сервер через «Поднять сервер».")
            } else {
                emit("── Резервная копия от " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("ru")).format(java.util.Date(old.backupAt)))
            }
            emit("── Ставлю на ${target.host}")
            val r = ssh(target).run(listOf("import", "--host", target.host.trim(), "--data", data))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "переезд не удался")
            old.host = target.host.trim(); old.sshPort = target.sshPort; old.user = target.user
            old.password = target.password; old.privateKey = target.privateKey; old.hostKey = target.hostKey
            if (target.backup.isNotBlank()) { old.backup = target.backup; old.backupAt = target.backupAt }
            old.installedAt = System.currentTimeMillis(); old.stats = ""; old.statsAt = 0L; old.health = ""
            syncInfo(old, r.json)
            ServerStore.upsert(old)
            relinkEntry(old, r.json, "Переключаю на новый адрес")?.let { return@runOnDefaultDispatcher finish(false, it) }
            if (old.role == "exit") return@runOnDefaultDispatcher finish(true, "Выходной сервер переехал, каскад работает.")
            val n = importLinks(old, groupName(old), r.json, replace = true)
            ServerStore.upsert(old)
            finish(true, "Переезд завершён, профили обновлены ($n)." +
                (if (AltSub.enabled) " Устройства с подпиской найдут новый адрес сами." else " Другим устройствам отправьте новые подписки («Пользователи»).") +
                " Старый VPS не тронут — удалите его у хостера, когда убедитесь, что всё работает.")
        }
    }

    fun setAutoPorts(node: ServerNode, days: Int) {
        if (!start(if (days > 0) "Автосмена портов на ${node.name}" else "Выключаю автосмену портов на ${node.name}")) return
        runOnDefaultDispatcher {
            val r = ssh(node).run(listOf("auto-ports", "--every", days.toString()))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не получилось")
            syncInfo(node, r.json)
            ServerStore.upsert(node)
            finish(true, if (days > 0) "Порты будут меняться раз в $days дн. Устройства с подпиской подхватят их сами." else "Автосмена портов выключена.")
        }
    }

    fun enableTelegram(node: ServerNode, port: Int) {
        if (!start("Прокси для Telegram на ${node.name}")) return
        runOnDefaultDispatcher {
            val r = ssh(node).run(listOf("enable-telegram", "--tg-port", port.toString()))
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

    fun enableSudoku(node: ServerNode) {
        if (!start("Sudoku на ${node.name}")) return
        runOnDefaultDispatcher {
            val r = ssh(node).run(listOf("enable-sudoku", "--sd-port", node.sdPort.toString()))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось включить Sudoku")
            syncInfo(node, r.json)
            val n = importLinks(node, groupName(node), r.json, replace = true)
            ServerStore.upsert(node)
            finish(true, "Sudoku работает, профили обновлены ($n). Устройства с подпиской получат его сами.")
        }
    }

    fun disableSudoku(node: ServerNode) {
        if (!start("Выключаю Sudoku на ${node.name}")) return
        runOnDefaultDispatcher {
            val r = ssh(node).run(listOf("disable-sudoku"))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось выключить")
            syncInfo(node, r.json)
            val n = importLinks(node, groupName(node), r.json, replace = true)
            ServerStore.upsert(node)
            finish(true, "Sudoku выключен, профили обновлены ($n).")
        }
    }

    fun disableTelegram(node: ServerNode) {
        if (!start("Выключаю прокси для Telegram на ${node.name}")) return
        runOnDefaultDispatcher {
            val r = ssh(node).run(listOf("disable-telegram"))
            if (!r.ok || r.json == null) return@runOnDefaultDispatcher finish(false, r.error ?: "не удалось выключить")
            syncInfo(node, r.json)
            ServerStore.upsert(node)
            finish(true, "Прокси для Telegram выключен.")
        }
    }

    fun removeUser(node: ServerNode, userName: String) {
        if (!start("Удаляю пользователя «$userName» с ${node.name}")) return
        runOnDefaultDispatcher {
            val r = ssh(node).run(listOf("remove-user", userName))
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
            val r = ssh(node).run(listOf("uninstall"))
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
        }.orEmpty().let { AltSub.decorate(node, it) }
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
        publishReserve(node, json)
        return count
    }
}
