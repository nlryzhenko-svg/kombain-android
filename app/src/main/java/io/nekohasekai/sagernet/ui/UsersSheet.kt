package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.kombain.Deployer
import io.nekohasekai.sagernet.kombain.ServerHealth
import io.nekohasekai.sagernet.kombain.ServerNode
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.widget.QRCodeDialog
import org.json.JSONObject

/** «Пользователи» of one server: traffic, QR codes to share access, removal. */
object UsersSheet {

    fun show(f: MyServersFragment, node: ServerNode) {
        Deployer.fetchLinks(node) { json, _ ->
            if (json != null && f.isAdded) render(f, node, json)
        }
    }

    private fun linksOf(json: JSONObject): List<JSONObject> {
        val arr = json.optJSONArray("links") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun protoName(p: String) = when (p) {
        "vless" -> "VLESS Reality"; "xhttp" -> "XHTTP"; "hy2" -> "Hysteria2"; "tuic" -> "TUIC"; "sudoku" -> "Sudoku"; else -> p
    }

    private fun render(f: MyServersFragment, node: ServerNode, json: JSONObject) {
        val ctx = f.requireContext()
        val sheet = BottomSheetDialog(ctx, R.style.ThemeOverlay_Kombain_BottomSheet)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(16), dp2px(10), dp2px(16), dp2px(16))
        }
        root.addView(View(ctx).apply { setBackgroundResource(R.drawable.kombain_handle) },
            LinearLayout.LayoutParams(dp2px(36), dp2px(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp2px(12) })
        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.srv_users_title, node.name)
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
            setPadding(dp2px(4), 0, 0, dp2px(6))
        })
        val traffic = ServerHealth.userTraffic(node.stats.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() })
        val links = linksOf(json)
        val users = links.map { it.optString("user") }.distinct()
        for (u in users) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp2px(4), dp2px(8), 0, dp2px(8))
            }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(ctx).apply {
                text = u
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
            })
            traffic[u]?.let { (down, up) ->
                texts.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.srv_users_traffic, ServerHealth.size(down), ServerHealth.size(up))
                    textSize = 12.5f
                    setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
                })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            fun btn(label: Int, danger: Boolean = false, onClick: () -> Unit) = row.addView(
                MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    setText(label); isAllCaps = false; minWidth = 0; minimumWidth = 0
                    setPadding(dp2px(10), 0, dp2px(10), 0)
                    setTextColor(ContextCompat.getColor(ctx, if (danger) R.color.kombain_danger else R.color.kombain_fab))
                    setOnClickListener { onClick() }
                })
            btn(R.string.srv_users_qr) { sheet.dismiss(); showUser(f, node, u, json) }
            if (users.size > 1) btn(R.string.srv_users_remove, true) {
                MaterialAlertDialogBuilder(ctx)
                    .setMessage(ctx.getString(R.string.srv_users_remove_msg, u))
                    .setPositiveButton(R.string.srv_users_remove) { _, _ ->
                        sheet.dismiss(); Deployer.removeUser(node, u)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            root.addView(row)
        }
        // the Telegram proxy link (one for everybody) is on the server card
        root.addView(MaterialButton(ctx).apply {
            setText(R.string.srv_users_add)
            isAllCaps = false
            cornerRadius = dp2px(14)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.kombain_fab))
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { sheet.dismiss(); f.askUser(node) }
        }, LinearLayout.LayoutParams(-1, dp2px(52)).apply { topMargin = dp2px(10) })
        sheet.setContentView(root)
        sheet.show()
    }

    /** The server's Telegram proxy: open it in Telegram, QR for another phone, copy or send. */
    fun showTelegram(f: androidx.fragment.app.Fragment, node: ServerNode, link: String) {
        val ctx = f.context ?: return
        val web = link.replaceFirst("tg://proxy", "https://t.me/proxy")
        val actions = listOf(
            ActionSheet.Action(R.drawable.ic_kombain_send, ctx.getString(R.string.srv_tg_open)) {
                try {
                    f.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
                } catch (e: android.content.ActivityNotFoundException) {
                    SagerNet.trySetPrimaryClip(web)
                    f.snackbar(R.string.srv_tg_no_app).show()
                }
            },
            ActionSheet.Action(R.drawable.ic_kombain_qr, ctx.getString(R.string.srv_tg_qr), ctx.getString(R.string.srv_tg_qr_hint)) {
                QRCodeDialog(web, ctx.getString(R.string.srv_tg_title) + " · " + node.name)
                    .showAllowingStateLoss(f.parentFragmentManager)
            },
            ActionSheet.Action(R.drawable.ic_kombain_copy, ctx.getString(R.string.srv_tg_copy)) {
                if (SagerNet.trySetPrimaryClip(web)) f.snackbar(R.string.srv_tg_copied).show()
            },
            ActionSheet.Action(R.drawable.ic_kombain_share, ctx.getString(R.string.srv_tg_send)) {
                f.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, web)
                }, null))
            },
        )
        ActionSheet.show(ctx, ctx.getString(R.string.srv_tg_title) + " · " + node.name, actions)
    }

    /** QR codes and links of one user. */
    fun showUser(f: androidx.fragment.app.Fragment, node: ServerNode, user: String, json: JSONObject) {
        val ctx = f.context ?: return
        val mine = linksOf(json).filter { it.optString("user") == user }
        if (mine.isEmpty()) return
        val sheet = BottomSheetDialog(ctx, R.style.ThemeOverlay_Kombain_BottomSheet)
        val all = mine.joinToString("\n") { it.optString("link") }
        val actions = mine.mapIndexed { i, l ->
            val title = protoName(l.optString("proto"))
            ActionSheet.Action(R.drawable.ic_kombain_qr, title, if (i == 0) ctx.getString(R.string.srv_qr_hint) else null) {
                QRCodeDialog(l.optString("link"), ctx.getString(R.string.srv_qr_for, user, title))
                    .showAllowingStateLoss(f.parentFragmentManager)
            }
        } + listOf(
            ActionSheet.Action(R.drawable.ic_kombain_copy, ctx.getString(R.string.srv_copy_all)) {
                if (SagerNet.trySetPrimaryClip(all)) f.snackbar(R.string.srv_copied).show()
            },
            ActionSheet.Action(R.drawable.ic_kombain_share, ctx.getString(R.string.srv_share_all)) {
                f.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, all)
                }, null))
            },
        )
        val subUrl = json.optJSONArray("subs")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("user") == user }?.optString("url")
        }.orEmpty()
        val subAction = if (subUrl.isBlank()) emptyList() else listOf(
            ActionSheet.Action(R.drawable.ic_kombain_auto, ctx.getString(R.string.srv_sub_title), ctx.getString(R.string.srv_sub_sub)) {
                showSub(f, node, user, subUrl)
            })
        sheet.dismiss()
        ActionSheet.show(ctx, "$user · ${node.name}", subAction + actions)
    }

    /** sn://subscription link: Kombain on another phone adds it as a subscription (QR scan or clipboard). */
    private fun showSub(f: androidx.fragment.app.Fragment, node: ServerNode, user: String, plainUrl: String) {
        // with a reserve address the link survives a move of the server
        val url = io.nekohasekai.sagernet.kombain.AltSub.decorate(node, plainUrl)
        if (url != plainUrl) io.nekohasekai.sagernet.kombain.ServerStore.upsert(node)
        val ctx = f.context ?: return
        val name = "${node.name} · $user"
        val link = "sn://subscription?url=" + java.net.URLEncoder.encode(url, "UTF-8") +
                "&name=" + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        ActionSheet.show(ctx, ctx.getString(R.string.srv_sub_title) + " · " + user, listOf(
            ActionSheet.Action(R.drawable.ic_kombain_qr, ctx.getString(R.string.srv_sub_qr), ctx.getString(R.string.srv_sub_qr_hint)) {
                QRCodeDialog(link, name).showAllowingStateLoss(f.parentFragmentManager)
            },
            ActionSheet.Action(R.drawable.ic_kombain_copy, ctx.getString(R.string.srv_sub_copy)) {
                if (SagerNet.trySetPrimaryClip(link)) f.snackbar(R.string.srv_sub_copied).show()
            },
            ActionSheet.Action(R.drawable.ic_kombain_share, ctx.getString(R.string.srv_sub_send)) {
                f.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link)
                }, null))
            },
        ))
    }
}
