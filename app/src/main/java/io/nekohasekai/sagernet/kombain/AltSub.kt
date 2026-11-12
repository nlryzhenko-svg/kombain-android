package io.nekohasekai.sagernet.kombain

import android.util.Base64
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reserve subscription address.
 *
 * A subscription lives on the server itself — when the server's IP gets blocked, devices can't learn where
 * it moved. With a GitHub token in the settings, Kombain keeps an encrypted copy of every user's
 * subscription in a secret gist. Subscription links get `&kbalt=<raw gist URL>&kbkey=<key>` in the
 * fragment (never sent to any server); when the main address fails, Kombain reads the gist, decrypts
 * it and switches the subscription to the new address.
 *
 * Gist file per user: {"sub": "<new subscription link, with kbalt/kbkey>", "links": "<profiles>"},
 * AES-256-GCM, key = SHA-256("kbalt|" + node.altSecret + "|" + user token).
 */
object AltSub {

    private const val API = "https://api.github.com"

    var token: String
        get() = ServerStore.getSecret("gist_token")
        set(v) = ServerStore.setSecret("gist_token", v.trim())

    val enabled get() = token.isNotBlank()

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** "https://host:port/<token>#kbpin=…" → token */
    fun userToken(subUrl: String) = subUrl.substringBefore('#').trimEnd('/').substringAfterLast('/')

    private fun ensureSecret(node: ServerNode) {
        if (node.altSecret.isBlank()) node.altSecret = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
    }

    private fun fileName(node: ServerNode, user: String) = hex(sha256("kbfile|${node.altSecret}|$user")).take(24) + ".txt"
    private fun key(node: ServerNode, user: String) = sha256("kbalt|${node.altSecret}|$user")

    private fun rawUrl(node: ServerNode, user: String): String? {
        val login = DataStore.kbGistLogin.takeIf { it.isNotBlank() } ?: return null
        val id = DataStore.kbGistId.takeIf { it.isNotBlank() } ?: return null
        return "https://gist.githubusercontent.com/$login/$id/raw/${fileName(node, user)}"
    }

    /** The subscription link with the reserve address in its fragment (unchanged when not set up). */
    fun decorate(node: ServerNode, subUrl: String): String {
        if (!enabled || subUrl.isBlank()) return subUrl
        ensureSecret(node)
        val user = userToken(subUrl)
        val raw = rawUrl(node, user) ?: return subUrl
        val base = subUrl.substringBefore("&kbalt=")
        return base + "&kbalt=" + java.net.URLEncoder.encode(raw, "UTF-8") + "&kbkey=" + b64(key(node, user))
    }

    private fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return "KBALT1:" + b64(iv + c.doFinal(plain.toByteArray()))
    }

    /** Client side: decrypts a reserve file. */
    fun decrypt(keyB64: String, body: String): JSONObject {
        val data = unb64(body.trim().removePrefix("KBALT1:"))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(unb64(keyB64), "AES"), GCMParameterSpec(128, data, 0, 12))
        return JSONObject(String(c.doFinal(data, 12, data.size - 12)))
    }

    private val http by lazy { okhttp3.OkHttpClient() }

    private fun api(method: String, path: String, body: JSONObject? = null): JSONObject {
        val reqBody = body?.toString()?.toRequestBody("application/json".toMediaType())
        val req = okhttp3.Request.Builder().url(API + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Kombain")
            .method(method, reqBody)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                error(when (resp.code) {
                    401 -> "GitHub не принял токен — проверьте его в настройках"
                    403, 404 -> "у токена нет права «gist» ($msg)"
                    else -> "GitHub ответил ${resp.code} $msg"
                })
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    /** Checks the token and makes the secret gist. Returns the GitHub login. */
    fun setup(): String {
        val login = api("GET", "/user").getString("login")
        DataStore.kbGistLogin = login
        val id = DataStore.kbGistId
        val exists = id.isNotBlank() && runCatching { api("GET", "/gists/$id") }.isSuccess
        if (!exists) {
            val g = api("POST", "/gists", JSONObject()
                .put("description", "Kombain: резервные адреса подписок (зашифровано)")
                .put("public", false)
                .put("files", JSONObject().put("kombain.txt", JSONObject().put("content", "Kombain reserve subscriptions. Encrypted."))))
            DataStore.kbGistId = g.getString("id")
        }
        return login
    }

    /**
     * Puts every user's subscription of [node] (from a KB_RESULT) into the gist.
     * Returns the number of users written; throws on network/API errors.
     */
    fun publish(node: ServerNode, json: JSONObject): Int {
        if (!enabled) return 0
        if (DataStore.kbGistId.isBlank() || DataStore.kbGistLogin.isBlank()) setup()
        ensureSecret(node)
        val subs = json.optJSONArray("subs") ?: return 0
        val links = json.optJSONArray("links")
        val files = JSONObject()
        for (i in 0 until subs.length()) {
            val s = subs.getJSONObject(i)
            val user = s.optString("user")
            val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
            val token = userToken(url)
            val userLinks = buildString {
                if (links != null) for (j in 0 until links.length()) {
                    val l = links.getJSONObject(j)
                    if (l.optString("user") == user) appendLine(l.optString("link"))
                }
            }
            val content = JSONObject().put("sub", decorate(node, url)).put("links", userLinks)
            files.put(fileName(node, token), JSONObject().put("content", encrypt(key(node, token), content.toString())))
        }
        if (files.length() == 0) return 0
        api("PATCH", "/gists/${DataStore.kbGistId}", JSONObject().put("files", files))
        Logs.i("[Kombain] резервная подписка обновлена: ${files.length()}")
        return files.length()
    }
}
