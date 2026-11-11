package io.nekohasekai.sagernet.ui

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import org.json.JSONArray

/** Choice of a masking site found next to the server by `find-sni`. */
object SitePicker {

    /** [markWhitelist]: for a cascade entry node in Russia — whitelisted sites first, with a mark. */
    fun show(ctx: Context, sites: JSONArray?, current: String, markWhitelist: Boolean = false, onPick: (domain: String, dest: String) -> Unit) {
        var list = (0 until (sites?.length() ?: 0)).map { sites!!.getJSONObject(it) }
        val listed = if (markWhitelist) list.associate { o ->
            o.optString("domain") to runCatching { io.nekohasekai.sagernet.kombain.Whitelist.siteListed(o.optString("domain")) }.getOrDefault(false)
        } else emptyMap()
        if (markWhitelist) list = list.sortedByDescending { listed[it.optString("domain")] == true }
        if (list.isEmpty()) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.srv_sni_title)
                .setMessage(ctx.getString(R.string.srv_sni_none, current.ifBlank { "www.microsoft.com" }))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val items = list.map {
            ctx.getString(R.string.srv_sni_item, it.optString("domain"), it.optInt("ms")) +
                    if (listed[it.optString("domain")] == true) " · ✓ " + ctx.getString(R.string.srv_wl_badge) else ""
        }.toTypedArray()
        val d = ctx.resources.displayMetrics.density
        val head = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (6 * d).toInt())
            addView(android.widget.TextView(ctx).apply {
                setText(R.string.srv_sni_title)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(android.widget.TextView(ctx).apply {
                setText(R.string.srv_sni_hint)
                textSize = 13f
                alpha = 0.75f
                setPadding(0, (6 * d).toInt(), 0, 0)
            })
        }
        MaterialAlertDialogBuilder(ctx)
            .setCustomTitle(head)
            .setItems(items) { _, i -> onPick(list[i].optString("domain"), list[i].optString("dest")) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
