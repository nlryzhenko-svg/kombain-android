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

    /** uTLS fingerprints Xray understands, in the order shown */
    val FPS = listOf("firefox", "edge", "chrome", "safari", "ios", "android", "360", "qq", "random", "randomized")

    fun fpTitle(ctx: Context, fp: String): String {
        val i = FPS.indexOf(fp)
        return if (i >= 0) ctx.resources.getStringArray(R.array.srv_fp_names)[i] else fp
    }

    fun show(ctx: Context, s: ServerNode, onPickSite: () -> Unit, apply: (antifreeze: Boolean, fp: String, rotation: Boolean, hop: Boolean) -> Unit) {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val protos = s.protocols.split(',')
        var fp = s.fp
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

        val fpRow = TextView(ctx).apply {
            textSize = 15f
            minHeight = dp2px(48)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
            text = ctx.getString(R.string.srv_fp_row, fpTitle(ctx, fp))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        fpRow.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.srv_fp_title)
                .setSingleChoiceItems(R.array.srv_fp_names, FPS.indexOf(fp).coerceAtLeast(0)) { d, i ->
                    fp = FPS[i]
                    fpRow.text = ctx.getString(R.string.srv_fp_row, fpTitle(ctx, fp))
                    d.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        box.addView(fpRow)
        box.addView(hint(ctx.getString(R.string.srv_fp_hint)))

        // the masking site itself (was a separate menu line)
        box.addView(TextView(ctx).apply {
            textSize = 15f
            minHeight = dp2px(48)
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
            text = ctx.getString(R.string.srv_site_row, s.sni.ifBlank { "—" })
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { dialog?.dismiss(); onPickSite() }
        })
        box.addView(hint(ctx.getString(R.string.srv_site_hint)))

        val rot = switch(R.string.srv_rot_title, s.sniRotation)
        box.addView(rot)
        box.addView(hint(if (s.sniRotation && s.sniAlts.isNotBlank())
            ctx.getString(R.string.srv_rot_now, (listOf(s.sni) + s.sniAlts.split(',')).joinToString(", "))
        else ctx.getString(R.string.srv_rot_hint)))

        val hasHy = "hy2" in protos
        val hop = switch(R.string.srv_hop_title, s.hop, hasHy)
        if (hasHy) {
            box.addView(hop)
            box.addView(hint(if (s.hyHop.isNotBlank()) ctx.getString(R.string.srv_hop_now, s.hyHop.replace("-", "–"))
            else ctx.getString(R.string.srv_hop_hint)))
        }

        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.srv_protect_title)
            .setView(androidx.core.widget.NestedScrollView(ctx).apply { addView(box) })
            .setPositiveButton(R.string.srv_protect_apply) { _, _ ->
                apply(af.isChecked, fp, rot.isChecked, hasHy && hop.isChecked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Short line for the server card. */
    fun summary(ctx: Context, s: ServerNode): String {
        val parts = mutableListOf<String>()
        if (s.antifreeze && "xhttp" in s.protocols.split(',')) parts += ctx.getString(R.string.srv_ps_af)
        parts += fpTitle(ctx, s.fp).substringBefore(" (").substringBefore(" —")
        if (s.sniRotation && s.sniAlts.isNotBlank()) parts += ctx.getString(R.string.srv_ps_rot, s.sniAlts.split(',').size + 1)
        if (s.hyHop.isNotBlank()) parts += ctx.getString(R.string.srv_ps_hop)
        return ctx.getString(R.string.srv_ps, parts.joinToString(", "))
    }
}
