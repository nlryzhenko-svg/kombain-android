package io.nekohasekai.sagernet.kombain

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.nekohasekai.sagernet.ktx.app
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/** Result of one kombain-node.sh run. */
class NodeResult(
    val ok: Boolean,
    val error: String?,
    /** links[]: {user, proto, link}; bridge: vless:// for a cascade entry */
    val json: JSONObject?,
    /** server health from `stats` */
    val stats: JSONObject? = null,
    /** find-sni: [{domain, dest, ms}] best first */
    val sites: org.json.JSONArray? = null,
)

/**
 * Runs the bundled kombain-node.sh on a server over SSH, streaming its progress.
 * Host keys are trusted on first use and then pinned per server.
 */
class SshRunner(private val node: ServerNode, private val log: (String) -> Unit) {

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun connect(): Session {
        val jsch = JSch()
        if (node.privateKey.isNotBlank()) {
            jsch.addIdentity("kombain", node.privateKey.trim().toByteArray(), null,
                node.password.takeIf { it.isNotBlank() }?.toByteArray())
        }
        val s = jsch.getSession(node.user.ifBlank { "root" }, node.host.trim(), node.sshPort)
        if (node.password.isNotBlank() && node.privateKey.isBlank()) s.setPassword(node.password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        s.setServerAliveInterval(15_000)
        log("Подключаюсь к ${node.host}:${node.sshPort}…")
        try {
            s.connect(15_000)
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            throw IllegalStateException(
                when {
                    m.contains("Auth fail", true) || m.contains("auth", true) -> "Неверный логин, пароль или ключ"
                    m.contains("timeout", true) -> "Сервер не отвечает (проверьте IP и SSH-порт)"
                    m.contains("refused", true) -> "Порт SSH закрыт (проверьте SSH-порт)"
                    else -> "Не удалось подключиться: $m"
                }
            )
        }
        val fp = s.hostKey.getFingerPrint(jsch)
        if (node.hostKey.isNotBlank() && node.hostKey != fp) {
            s.disconnect()
            throw IllegalStateException("Ключ сервера изменился ($fp). Если вы переустанавливали сервер — удалите его из списка и добавьте заново.")
        }
        if (node.hostKey.isBlank()) {
            node.hostKey = fp
            log("Отпечаток сервера: $fp")
        }
        return s
    }

    private fun exec(s: Session, command: String, stdin: ByteArray?, onLine: (String) -> Unit): Int {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        val out = ch.inputStream
        val input = ch.outputStream
        ch.connect(15_000)
        if (stdin != null) {
            input.write(stdin)
            input.flush()
        }
        input.close()
        BufferedReader(InputStreamReader(out, Charsets.UTF_8)).useLines { lines ->
            lines.forEach(onLine)
        }
        while (!ch.isClosed) Thread.sleep(100)
        val code = ch.exitStatus
        ch.disconnect()
        return code
    }

    /** Uploads the installer and runs it with [args]. Blocking — call from a background thread. */
    fun run(args: List<String>): NodeResult {
        val s = try {
            connect()
        } catch (e: Exception) {
            return NodeResult(false, e.message, null)
        }
        try {
            val script = app.assets.open("kombain/kombain-node.sh").use { it.readBytes() }
            val path = "/tmp/kombain-node.sh"
            exec(s, "cat > $path && chmod 700 $path", script) {}
            val root = node.user.ifBlank { "root" } == "root"
            val cmdArgs = args.joinToString(" ") { q(it) }
            val cmd = if (root) "bash $path $cmdArgs 2>&1"
            else if (node.password.isNotBlank()) "sudo -S -p '' bash $path $cmdArgs 2>&1"
            else "sudo -n bash $path $cmdArgs 2>&1"
            val stdin = if (!root && node.password.isNotBlank()) (node.password + "\n").toByteArray() else null

            var result: JSONObject? = null
            var stats: JSONObject? = null
            var sites: org.json.JSONArray? = null
            var error: String? = null
            val code = exec(s, cmd, stdin) { line ->
                when {
                    line.startsWith("KB_STEP ") -> log("• " + line.removePrefix("KB_STEP "))
                    line.startsWith("KB_ERROR ") -> {
                        error = line.removePrefix("KB_ERROR ")
                        log("✖ $error")
                    }

                    line.startsWith("KB_RESULT ") -> result = runCatching {
                        JSONObject(String(Base64.decode(line.removePrefix("KB_RESULT ").trim(), Base64.DEFAULT)))
                    }.getOrNull()

                    line.startsWith("KB_SNI ") -> sites = runCatching {
                        org.json.JSONArray(String(Base64.decode(line.removePrefix("KB_SNI ").trim(), Base64.DEFAULT)))
                    }.getOrNull()

                    line.startsWith("KB_STATS ") -> stats = runCatching {
                        JSONObject(String(Base64.decode(line.removePrefix("KB_STATS ").trim(), Base64.DEFAULT)))
                    }.getOrNull()

                    line.isNotBlank() -> log("  $line")
                }
            }
            exec(s, "rm -f $path", null) {}
            if (code != 0 && error == null) {
                error = if (!root) "Команда завершилась с кодом $code (нужен sudo без ограничений или вход под root)"
                else "Команда завершилась с кодом $code"
            }
            val ok = error == null && (result != null || stats != null || sites != null || args.firstOrNull() in setOf("status", "uninstall"))
            return NodeResult(ok, error, result, stats, sites)
        } catch (e: Exception) {
            return NodeResult(false, "Ошибка SSH: ${e.message}", null)
        } finally {
            s.disconnect()
        }
    }
}
