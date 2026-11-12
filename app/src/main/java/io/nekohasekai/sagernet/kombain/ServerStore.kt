package io.nekohasekai.sagernet.kombain

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.nekohasekai.sagernet.ktx.app
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One server the user deployed with Kombain. Secrets are encrypted at rest (Android Keystore). */
data class ServerNode(
    var id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var sshPort: Int = 22,
    var user: String = "root",
    var password: String = "",
    var privateKey: String = "",
    /** single | entry | exit */
    var role: String = "single",
    /** for an entry node: id of its exit node */
    var exitId: String = "",
    var protocols: String = "vless,hy2",
    var sni: String = "",
    /** Reality target when a neighbour site was picked ("ip:443"); empty = the site itself */
    var dest: String = "",
    var vpnPort: Int = 443,
    var hyPort: Int = 443,
    var xhPort: Int = 8443,
    var tuicPort: Int = 8443,
    var tgPort: Int = 9443,
    /** server-side random port change every N days, 0 = off */
    var autoPorts: Int = 0,
    /** protection against DPI freezes (see `harden` in kombain-node.sh) */
    var fp: String = "firefox",
    var antifreeze: Boolean = true,
    var sniRotation: Boolean = true,
    // off by default: on Russian mobile networks frequent new UDP flows made Hysteria2 flaky
    var hop: Boolean = false,
    /** what the server reports: other names of the masking site, Hysteria2 hop range */
    var sniAlts: String = "",
    var hyHop: String = "",
    var hostKey: String = "",
    var groupId: Long = 0L,
    var installedAt: Long = 0L,
    /** last `stats` JSON and when it was taken */
    var stats: String = "",
    var statsAt: Long = 0L,
    /** last background health check: "" = fine, otherwise a short problem text */
    var health: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("sshPort", sshPort); put("user", user)
        put("password", password); put("privateKey", privateKey); put("role", role); put("exitId", exitId)
        put("protocols", protocols); put("sni", sni); put("dest", dest); put("vpnPort", vpnPort); put("hyPort", hyPort)
        put("xhPort", xhPort); put("tuicPort", tuicPort); put("tgPort", tgPort); put("autoPorts", autoPorts)
        put("fp", fp); put("antifreeze", antifreeze); put("sniRotation", sniRotation); put("hop", hop)
        put("sniAlts", sniAlts); put("hyHop", hyHop)
        put("hostKey", hostKey); put("groupId", groupId); put("installedAt", installedAt)
        put("stats", stats); put("statsAt", statsAt); put("health", health)
    }

    companion object {
        fun fromJson(o: JSONObject) = ServerNode(
            id = o.optString("id"), name = o.optString("name"), host = o.optString("host"),
            sshPort = o.optInt("sshPort", 22), user = o.optString("user", "root"),
            password = o.optString("password"), privateKey = o.optString("privateKey"),
            role = o.optString("role", "single"), exitId = o.optString("exitId"),
            protocols = o.optString("protocols", "vless,hy2"), sni = o.optString("sni"), dest = o.optString("dest"),
            vpnPort = o.optInt("vpnPort", 443), hyPort = o.optInt("hyPort", 443),
            xhPort = o.optInt("xhPort", 8443), tuicPort = o.optInt("tuicPort", 8443),
            tgPort = o.optInt("tgPort", 9443), autoPorts = o.optInt("autoPorts", 0),
            // servers added before 3.4 have none of the protection until it is switched on
            fp = o.optString("fp", "chrome"), antifreeze = o.optBoolean("antifreeze", false),
            sniRotation = o.optBoolean("sniRotation", false), hop = o.optBoolean("hop", false),
            sniAlts = o.optString("sniAlts"), hyHop = o.optString("hyHop"),
            hostKey = o.optString("hostKey"), groupId = o.optLong("groupId"), installedAt = o.optLong("installedAt"),
            stats = o.optString("stats"), statsAt = o.optLong("statsAt"), health = o.optString("health"),
        )
    }

    val hasTelegram get() = "tg" in protocols.split(',')
}

object ServerStore {
    private const val PREFS = "kombain_servers"
    private const val KEY_ALIAS = "kombain_servers_key"

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): MutableList<ServerNode> {
        val raw = prefs.getString("servers", null) ?: return mutableListOf()
        val json = runCatching { decrypt(raw) }.getOrNull() ?: return mutableListOf()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { ServerNode.fromJson(arr.getJSONObject(it)) }.toMutableList()
    }

    @Synchronized
    fun saveAll(list: List<ServerNode>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs.edit().putString("servers", encrypt(arr.toString())).apply()
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    fun upsert(node: ServerNode) {
        val list = all()
        val i = list.indexOfFirst { it.id == node.id }
        if (i >= 0) list[i] = node else list.add(node)
        saveAll(list)
    }

    fun remove(id: String) = saveAll(all().filter { it.id != id })

    // ---- crypto: AES-256-GCM key in Android Keystore (API 23+); plain base64 on older Android

    private fun key(): SecretKey? {
        if (Build.VERSION.SDK_INT < 23) return null
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val k = key() ?: return "p:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        val data = c.doFinal(plain.toByteArray())
        return "g:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        if (stored.startsWith("p:")) return String(Base64.decode(stored.substring(2), Base64.NO_WRAP))
        val parts = stored.split(":")
        val k = key() ?: error("no key")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)))
    }
}
