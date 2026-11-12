package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.kombain.Whitelist
import io.nekohasekai.sagernet.ktx.dp2px
import org.json.JSONArray

/**
 * Choice of the masking site, in one window: the current site, sites found next to the server
 * (`find-sni`, started from the list itself), well-known sites that suit Reality, or any address.
 */
object SitePicker {

    /** Big sites with TLS 1.3 + HTTP/2 that are open from Russia — a safe choice when nothing is found nearby. */
    val POPULAR = listOf(
        "www.microsoft.com", "www.apple.com", "dl.google.com", "www.samsung.com",
        "www.nvidia.com", "www.amd.com", "addons.mozilla.org", "github.com",
    )

    /**
     * [scan] looks for sites next to the server and calls back (on the main thread) with the list
     * or an error text; null hides the search.
     * [markWhitelist]: for a cascade entry node in Russia — sites from the mobile whitelists get a mark.
     */
    fun show(
        ctx: Context,
        current: String,
        markWhitelist: Boolean = false,
        scan: ((done: (JSONArray?, String?) -> Unit) -> Unit)?,
        onPick: (domain: String, dest: String) -> Unit,
    ) {
        var dialog: AlertDialog? = null
        val text = ContextCompat.getColor(ctx, R.color.kombain_text)
        val text2 = ContextCompat.getColor(ctx, R.color.kombain_text2)
        val accent = ContextCompat.getColor(ctx, R.color.kombain_accent)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp2px(2), 0, dp2px(8))
        }

        fun wl(domain: String) = markWhitelist &&
                runCatching { Whitelist.siteListed(domain) }.getOrDefault(false)

        fun header(title: Int) = TextView(ctx).apply {
            setText(title)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            setPadding(dp2px(24), dp2px(14), dp2px(24), dp2px(4))
        }

        fun item(title: String, sub: String?, selected: Boolean = false, click: (() -> Unit)?) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp2px(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(24), dp2px(8), dp2px(24), dp2px(8))
            addView(TextView(ctx).apply {
                this.text = (if (selected) "● " else "") + title
                textSize = 16f
                setTextColor(if (selected) accent else text)
            })
            if (!sub.isNullOrBlank()) addView(TextView(ctx).apply {
                this.text = sub
                textSize = 12.5f
                setTextColor(text2)
            })
            if (click != null) {
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { click() }
            }
        }

        fun pick(domain: String, dest: String) {
            dialog?.dismiss()
            if (domain != current) onPick(domain, dest)
        }

        // the current one
        if (current.isNotBlank()) {
            box.addView(header(R.string.srv_sp_current))
            box.addView(item(current, ctx.getString(R.string.srv_sp_current_sub), true) { dialog?.dismiss() })
        }

        // next to the server
        if (scan != null) {
            val near = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            box.addView(header(R.string.srv_sp_near))
            box.addView(near)
            lateinit var start: () -> Unit
            fun scanButton(sub: String?) {
                near.removeAllViews()
                near.addView(item(ctx.getString(R.string.srv_sp_scan), sub ?: ctx.getString(R.string.srv_sp_scan_sub)) { start() })
            }
            start = {
                near.removeAllViews()
                near.addView(item(ctx.getString(R.string.srv_sp_scanning), ctx.getString(R.string.srv_sp_scanning_sub), click = null))
                scan { sites, error ->
                    near.removeAllViews()
                    val list = (0 until (sites?.length() ?: 0)).map { sites!!.getJSONObject(it) }
                        .sortedByDescending { wl(it.optString("domain")) }
                    when {
                        sites == null -> scanButton(ctx.getString(R.string.srv_sp_scan_failed, error ?: "?"))
                        list.isEmpty() -> near.addView(item(ctx.getString(R.string.srv_sp_none), ctx.getString(R.string.srv_sp_none_sub), click = null))
                        else -> for (o in list) {
                            val d = o.optString("domain")
                            val sub = ctx.getString(R.string.srv_sp_near_sub, o.optInt("ms")) +
                                    if (wl(d)) " · ✓ " + ctx.getString(R.string.srv_wl_badge) else ""
                            near.addView(item(d, sub, d == current) { pick(d, o.optString("dest")) })
                        }
                    }
                }
            }
            scanButton(null)
        }

        // well-known sites
        box.addView(header(R.string.srv_sp_popular))
        for (d in POPULAR) {
            if (d == current) continue
            val sub = if (wl(d)) "✓ " + ctx.getString(R.string.srv_wl_badge) else null
            box.addView(item(d, sub) { pick(d, "") })
        }

        // any address
        box.addView(item(ctx.getString(R.string.srv_sp_own), ctx.getString(R.string.srv_sp_own_sub)) {
            val input = EditText(ctx).apply {
                hint = "www.example.com"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                isSingleLine = true
            }
            val wrap = LinearLayout(ctx).apply {
                setPadding(dp2px(22), dp2px(8), dp2px(22), 0)
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.srv_sp_own)
                .setMessage(R.string.srv_sp_own_msg)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val d = input.text.toString().trim().lowercase()
                        .removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
                    if (Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(d)) pick(d, "")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(24), dp2px(20), dp2px(24), dp2px(4))
            addView(TextView(ctx).apply {
                setText(R.string.srv_sni_title)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                setText(R.string.srv_sp_hint)
                textSize = 13f
                setTextColor(text2)
                setPadding(0, dp2px(6), 0, 0)
            })
        }
        dialog = MaterialAlertDialogBuilder(ctx)
            .setCustomTitle(head)
            .setView(androidx.core.widget.NestedScrollView(ctx).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
