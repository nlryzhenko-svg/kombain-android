package io.nekohasekai.sagernet.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.kombain.ServerNode
import io.nekohasekai.sagernet.kombain.Whitelist
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

/**
 * «Защита от блокировок» of one server: XHTTP against freezes, the browser fingerprint,
 * rotation of the masking site's names, Hysteria2 port hopping; for a cascade entry node also
 * the check against the mobile whitelists.
 */
object ProtectionDialog {

    /**
     * uTLS fingerprints the current REALITY accepts, in the order shown. The old Edge, iOS, Android,
     * 360 and QQ profiles (and so the random ones) are rejected by the server: no post-quantum key share.
     */
    val FPS = listOf("firefox", "chrome", "safari")

    /** Any fingerprint → the working one closest to it. */
    fun fixFp(fp: String?): String = when (fp) {
        "firefox", "chrome", "safari" -> fp
        "edge", "360", "qq", "android" -> "chrome"
        "ios" -> "safari"
        else -> "firefox"
    }

    fun fpTitle(ctx: Context, fp: String): String {
        val i = FPS.indexOf(fp)
        return if (i >= 0) ctx.resources.getStringArray(R.array.srv_fp_names)[i] else fp
    }

    /** [apply] gets the new site (domain to neighbour address) only when it was changed. */
    fun show(
        ctx: Context, s: ServerNode,
        scan: (done: (org.json.JSONArray?, String?) -> Unit) -> Unit,
        apply: (antifreeze: Boolean, fp: String, rotation: Boolean, hop: Boolean, site: Pair<String, String>?) -> Unit,
    ) {
        val protos = s.protocols.split(',')
        var fp = fixFp(s.fp)
        var site: Pair<String, String>? = null
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(22), dp2px(4), dp2px(22), 0)
        }
        fun hint(text: CharSequence) = TextView(ctx).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
            setPadding(0, 0, 0, dp2px(10))
        }
        fun switch(title: Int, on: Boolean, enabled: Boolean = true) = SwitchMaterial(ctx).apply {
            setText(title)
            isChecked = on && enabled
            isEnabled = enabled
            minHeight = dp2px(48)
            textSize = 15f
        }

        /** a setting with a value to choose: «Title» on top, the value in green with › */
        fun choice(title: Int, value: String, click: () -> Unit): TextView {
            val v = TextView(ctx).apply {
                text = value
                textSize = 16f
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_accent))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            box.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp2px(56)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp2px(6), 0, dp2px(4))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(TextView(ctx).apply {
                    setText(title)
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
                })
                addView(v)
                setOnClickListener { click() }
            })
            return v
        }

        // mobile whitelists: what matters for the Russian node of a cascade
        if (s.role == "entry") {
            val wl = hint(ctx.getString(R.string.srv_wl_checking)).apply { setPadding(0, dp2px(6), 0, dp2px(12)) }
            box.addView(wl)
            runOnDefaultDispatcher {
                val text = runCatching { Whitelist.describe(Whitelist.check(s.host, s.sni)) }
                    .getOrElse { ctx.getString(R.string.srv_wl_failed) }
                onMainDispatcher {
                    wl.text = text
                }
            }
        }

        val hasXh = "xhttp" in protos
        val af = switch(R.string.srv_af_title, s.antifreeze, hasXh)
        box.addView(af)
        box.addView(hint(ctx.getString(if (hasXh) R.string.srv_af_hint else R.string.srv_af_no_xhttp)))

        lateinit var fpRow: TextView
        fpRow = choice(R.string.srv_fp_title, fpTitle(ctx, fp) + "  ›") {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.srv_fp_title)
                .setSingleChoiceItems(R.array.srv_fp_names, FPS.indexOf(fp).coerceAtLeast(0)) { d, i ->
                    fp = FPS[i]
                    fpRow.text = fpTitle(ctx, fp) + "  ›"
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        box.addView(hint(ctx.getString(R.string.srv_fp_hint)))

        // the masking site: chosen here, applied together with the rest
        lateinit var siteRow: TextView
        lateinit var rotHint: TextView
        siteRow = choice(R.string.srv_sni_title, s.sni.ifBlank { "—" } + "  ›") {
            SitePicker.show(ctx, site?.first ?: s.sni, markWhitelist = s.role == "entry", scan = scan) { domain, dest ->
                site = (domain to dest).takeIf { domain != s.sni }
                siteRow.text = domain + "  ›"
                rotHint.text = ctx.getString(R.string.srv_rot_new_site)
            }
        }
        box.addView(hint(ctx.getString(R.string.srv_site_hint)))

        val rot = switch(R.string.srv_rot_title, s.sniRotation)
        box.addView(rot)
        rotHint = hint(if (s.sniRotation && s.sniAlts.isNotBlank())
            ctx.getString(R.string.srv_rot_now, (listOf(s.sni) + s.sniAlts.split(',')).joinToString(", "))
        else ctx.getString(R.string.srv_rot_hint))
        box.addView(rotHint)

        val hasHy = "hy2" in protos
        val hop = switch(R.string.srv_hop_title, s.hop, hasHy)
        if (hasHy) {
            box.addView(hop)
            box.addView(hint(if (s.hyHop.isNotBlank()) ctx.getString(R.string.srv_hop_now, s.hyHop.replace("-", "–"))
            else ctx.getString(R.string.srv_hop_hint)))
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.srv_protect_title)
            .setView(androidx.core.widget.NestedScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.srv_protect_apply) { _, _ ->
                apply(af.isChecked, fp, rot.isChecked, hasHy && hop.isChecked, site)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Short line for the server card. */
    fun summary(ctx: Context, s: ServerNode): String {
        val parts = mutableListOf<String>()
        if (s.antifreeze && "xhttp" in s.protocols.split(',')) parts += ctx.getString(R.string.srv_ps_af)
        parts += fpTitle(ctx, fixFp(s.fp)).substringBefore(" (").substringBefore(" —")
        if (s.sniRotation && s.sniAlts.isNotBlank()) parts += ctx.getString(R.string.srv_ps_rot, s.sniAlts.split(',').size + 1)
        if (s.hyHop.isNotBlank()) parts += ctx.getString(R.string.srv_ps_hop)
        return ctx.getString(R.string.srv_ps, parts.joinToString(", "))
    }
}
