package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.kombain.Deployer
import io.nekohasekai.sagernet.kombain.ServerNode
import io.nekohasekai.sagernet.kombain.ServerStore
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.snackbar

/** «Мои серверы»: servers deployed with Kombain, their actions and the live install log. */
class MyServersFragment : ToolbarFragment(R.layout.layout_my_servers) {

    private var root: View? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        toolbar.setTitle(R.string.srv_title)
        view.findViewById<View>(R.id.btn_new).setOnClickListener {
            if (Deployer.running) snackbar(R.string.srv_busy).show()
            else (activity as? MainActivity)?.displayFragment(ServerWizardFragment())
        }
        view.findViewById<View>(R.id.btn_connect).setOnClickListener {
            if (Deployer.running) snackbar(R.string.srv_busy).show()
            else (activity as? MainActivity)?.displayFragment(ServerConnectFragment())
        }
        view.findViewById<View>(R.id.log_copy).setOnClickListener {
            if (SagerNet.trySetPrimaryClip(synchronized(Deployer.log) { Deployer.log.toString() })) {
                snackbar(R.string.srv_log_copied).show()
            }
        }
        renderServers()
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        Deployer.listener = {
            renderLog()
            if (!Deployer.running) renderServers()
        }
        Deployer.userAdded = { node, user, json -> if (isAdded) UsersSheet.showUser(this, node, user, json) }
        Deployer.telegramReady = { node, link -> if (isAdded) UsersSheet.showTelegram(this, node, link) }
        renderLog()
        renderServers()
    }

    override fun onPause() {
        Deployer.listener = null
        Deployer.userAdded = null
        Deployer.telegramReady = null
        super.onPause()
    }

    override fun onDestroyView() {
        root = null
        super.onDestroyView()
    }

    private fun renderLog() {
        val v = root ?: return
        val text = synchronized(Deployer.log) { Deployer.log.toString().trimEnd() }
        val card = v.findViewById<View>(R.id.log_card)
        card.isVisible = text.isNotEmpty()
        if (text.isEmpty()) return
        v.findViewById<TextView>(R.id.log_title).setText(
            when (Deployer.lastOk) {
                null -> if (Deployer.running) R.string.srv_log_running else R.string.srv_log_title
                true -> R.string.srv_log_ok
                false -> R.string.srv_log_fail
            }
        )
        v.findViewById<View>(R.id.log_progress).isVisible = Deployer.running
        v.findViewById<TextView>(R.id.log_text).text = text
    }

    private fun renderServers() {
        val v = root ?: return
        val ctx = requireContext()
        val list = v.findViewById<LinearLayout>(R.id.server_list)
        list.removeAllViews()
        val servers = ServerStore.all()
        v.findViewById<View>(R.id.empty).isVisible = servers.isEmpty()
        for (s in servers) {
            val card = MaterialCardView(ctx).apply {
                radius = dp2px(20).toFloat()
                cardElevation = 0f
                strokeWidth = dp2px(1)
                strokeColor = ContextCompat.getColor(ctx, R.color.kombain_tile_stroke)
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.kombain_tile))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp2px(10) }
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp2px(16), dp2px(14), dp2px(8), dp2px(8))
            }
            inner.addView(TextView(ctx).apply {
                text = roleLabel(s)
                textSize = 11f
                isAllCaps = true
                letterSpacing = 0.06f
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_fab))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            inner.addView(TextView(ctx).apply {
                text = s.name
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
            })
            inner.addView(TextView(ctx).apply {
                val protos = s.protocols.split(',').joinToString(" + ") { protoName(it) }
                val unprotected = !s.antifreeze && !s.sniRotation && s.hyHop.isBlank() && s.fp == "chrome"
                text = "${s.host} · $protos" +
                        (if (s.autoPorts > 0) "\n" + getString(R.string.srv_auto_ports_on, s.autoPorts) else "") +
                        "\n" + if (unprotected) getString(R.string.srv_protect_old) else ProtectionDialog.summary(ctx, s)
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
            })
            // health line (from the last check)
            val st = s.stats.takeIf { it.isNotBlank() }?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            inner.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp2px(8), 0, 0)
                val color = when {
                    st == null && s.health.isEmpty() -> 0xFF9AA9AC.toInt()
                    s.health.isEmpty() -> 0xFF2FE3A6.toInt()
                    else -> ContextCompat.getColor(ctx, R.color.kombain_danger)
                }
                addView(View(ctx).apply {
                    setBackgroundResource(R.drawable.kombain_dot)
                    backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                }, LinearLayout.LayoutParams(dp2px(8), dp2px(8)))
                addView(TextView(ctx).apply {
                    text = when {
                        st == null && s.health.isEmpty() -> getString(R.string.srv_never_checked)
                        else -> (s.health.ifEmpty { getString(R.string.srv_ok) }) + " · " +
                                getString(R.string.srv_checked_ago,
                                    if (System.currentTimeMillis() - s.statsAt < 60_000) getString(R.string.srv_just_now)
                                    else android.text.format.DateUtils.getRelativeTimeSpanString(
                                        s.statsAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
                    }
                    textSize = 13f
                    setTextColor(if (s.health.isEmpty()) ContextCompat.getColor(ctx, R.color.kombain_text) else color)
                    setPadding(dp2px(8), 0, 0, 0)
                })
            })
            if (st != null) inner.addView(TextView(ctx).apply {
                text = io.nekohasekai.sagernet.kombain.ServerHealth.summary(st) + "\n" +
                        io.nekohasekai.sagernet.kombain.ServerHealth.traffic(st)
                textSize = 12.5f
                setLineSpacing(dp2px(2).toFloat(), 1f)
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
                setPadding(dp2px(16), dp2px(2), 0, 0)
            })
            val actions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp2px(4), 0, 0)
            }
            fun action(label: Int, onClick: () -> Unit) = actions.addView(
                MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    setText(label)
                    isAllCaps = false
                    textSize = 13.5f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp2px(10), 0, dp2px(10), 0)
                    setTextColor(ContextCompat.getColor(ctx, R.color.kombain_fab))
                    setOnClickListener { if (Deployer.running) snackbar(R.string.srv_busy).show() else onClick() }
                })
            if (s.role != "exit") {
                action(R.string.srv_a_profiles) { Deployer.refreshLinks(s); scrollTop() }
                action(R.string.srv_a_user) { UsersSheet.show(this, s) }
            }
            action(R.string.srv_a_status) { Deployer.status(s); scrollTop() }
            action(R.string.srv_a_more) { moreMenu(s) }
            inner.addView(actions)
            card.addView(inner)
            list.addView(card)
        }
    }

    private fun roleLabel(s: ServerNode) = getString(
        when (s.role) {
            "entry" -> R.string.srv_role_entry
            "exit" -> R.string.srv_role_exit
            else -> R.string.srv_role_single
        }
    )

    private fun scrollTop() {
        root?.findViewById<NestedScrollView>(R.id.scroll)?.smoothScrollTo(0, 0)
    }

    private fun protoName(p: String) = when (p) {
        "vless" -> "VLESS"; "xhttp" -> "XHTTP"; "hy2" -> "Hysteria2"; "tuic" -> "TUIC"; "tg" -> "Telegram"; "sudoku" -> "Sudoku"; else -> p
    }

    fun askUser(s: ServerNode) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.srv_user_hint)
            setSingleLine()
        }
        val box = LinearLayout(requireContext()).apply {
            setPadding(dp2px(22), dp2px(8), dp2px(22), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.srv_a_user)
            .setMessage(R.string.srv_user_msg)
            .setView(box)
            .setPositiveButton(R.string.srv_add) { _, _ ->
                val name = input.text.toString().trim().replace("|", "")
                if (name.isNotEmpty()) {
                    Deployer.addUser(s, name); scrollTop()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun moreMenu(s: ServerNode) {
        val tgItem = when {
            s.role == "exit" -> null
            s.hasTelegram -> getString(R.string.srv_m_tg_off)
            else -> getString(R.string.srv_m_tg_on)
        }
        // ids keep the actions stable when the Telegram item is absent
        val entries = listOfNotNull(
            8 to getString(R.string.srv_m_protect),
            10 to getString(R.string.srv_m_move),
            tgItem?.let { 4 to it },
            if (s.role == "single") 9 to getString(if (s.hasSudoku) R.string.srv_m_sd_off else R.string.srv_m_sd_on) else null,
            5 to getString(R.string.srv_m_sni),
            6 to getString(R.string.srv_m_shuffle),
            if (s.role != "exit") 7 to getString(R.string.srv_m_auto_ports) else null,
            0 to getString(R.string.srv_m_rotate), 1 to getString(R.string.srv_m_ports),
            2 to getString(R.string.srv_m_uninstall), 3 to getString(R.string.srv_m_forget),
        )
        val items = entries.map { it.second }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(s.name)
            .setItems(items) { _, pos ->
                when (entries[pos].first) {
                    9 -> if (s.hasSudoku) MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.srv_m_sd_off)
                        .setMessage(getString(R.string.srv_sd_off_msg, s.name))
                        .setPositiveButton(R.string.srv_sd_off_yes) { _, _ -> Deployer.disableSudoku(s); scrollTop() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    else MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.srv_m_sd_on)
                        .setMessage(getString(R.string.srv_sd_msg, s.sdPort))
                        .setPositiveButton(R.string.srv_sd_yes) { _, _ -> Deployer.enableSudoku(s); scrollTop() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()

                    10 -> if (Deployer.running) snackbar(R.string.srv_busy).show()
                    else (activity as? MainActivity)?.displayFragment(ServerMoveFragment.of(s))

                    8 -> ProtectionDialog.show(requireContext(), s) { af, fp, rot, hop ->
                        Deployer.harden(s, af, fp, rot, hop); scrollTop()
                    }

                    6 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.srv_m_shuffle)
                        .setMessage(R.string.srv_shuffle_msg)
                        .setPositiveButton(R.string.srv_rotate_yes) { _, _ -> Deployer.shufflePorts(s); scrollTop() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()

                    7 -> {
                        val values = intArrayOf(0, 1, 3, 7)
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.srv_m_auto_ports)
                            .setSingleChoiceItems(R.array.srv_auto_ports_names, values.indexOf(s.autoPorts).coerceAtLeast(0)) { d, i ->
                                d.dismiss()
                                if (values[i] != s.autoPorts) { Deployer.setAutoPorts(s, values[i]); scrollTop() }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }

                    5 -> {
                        Deployer.findSites(s) { sites ->
                            if (!isAdded) return@findSites
                            SitePicker.show(requireContext(), sites, s.sni, markWhitelist = s.role == "entry") { domain, dest ->
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(R.string.srv_m_sni)
                                    .setMessage(getString(R.string.srv_sni_apply_msg, domain))
                                    .setPositiveButton(R.string.srv_sni_apply) { _, _ -> Deployer.setSite(s, domain, dest); scrollTop() }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show()
                            }
                        }
                        scrollTop()
                    }
                    4 -> if (s.hasTelegram) MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.srv_m_tg_off)
                        .setMessage(getString(R.string.srv_tg_off_msg, s.name))
                        .setPositiveButton(R.string.srv_tg_off_yes) { _, _ -> Deployer.disableTelegram(s); scrollTop() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    else askTelegram(s)

                    0 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.srv_m_rotate)
                        .setMessage(R.string.srv_rotate_msg)
                        .setPositiveButton(R.string.srv_rotate_yes) { _, _ -> Deployer.rotate(s); scrollTop() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()

                    1 -> askPorts(s)

                    2 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.srv_m_uninstall)
                        .setMessage(getString(R.string.srv_uninstall_confirm, s.name))
                        .setPositiveButton(R.string.srv_m_uninstall_yes) { _, _ -> Deployer.uninstall(s); scrollTop() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()

                    3 -> {
                        ServerStore.remove(s.id); renderServers()
                    }
                }
            }
            .show()
    }

    private fun askTelegram(s: ServerNode) {
        val ctx = requireContext()
        val til = com.google.android.material.textfield.TextInputLayout(ctx).apply { hint = getString(R.string.srv_tg_port) }
        val et = com.google.android.material.textfield.TextInputEditText(til.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(s.tgPort.toString())
        }
        til.addView(et)
        val box = LinearLayout(ctx).apply {
            setPadding(dp2px(22), dp2px(4), dp2px(22), 0)
            addView(til, LinearLayout.LayoutParams(-1, -2))
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.srv_m_tg_on)
            .setMessage(getString(R.string.srv_tg_on_msg, s.sni.ifBlank { "www.microsoft.com" }))
            .setView(box)
            .setPositiveButton(R.string.srv_tg_on_yes) { _, _ ->
                val port = et.text.toString().toIntOrNull()?.takeIf { it in 1..65535 } ?: 9443
                if (port == s.vpnPort || ("xhttp" in s.protocols.split(',') && port == s.xhPort)) {
                    snackbar(R.string.srv_err_ports).show()
                } else {
                    Deployer.enableTelegram(s, port); scrollTop()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askPorts(s: ServerNode) {
        val ctx = requireContext()
        val protos = s.protocols.split(',').toMutableList().apply { if ("vless" !in this) add(0, "vless") }
        val current = mapOf("vless" to s.vpnPort, "xhttp" to s.xhPort, "hy2" to s.hyPort, "tuic" to s.tuicPort, "tg" to s.tgPort, "sudoku" to s.sdPort)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(22), dp2px(4), dp2px(22), 0)
        }
        val fields = protos.associateWith { p ->
            val til = com.google.android.material.textfield.TextInputLayout(ctx).apply {
                hint = protoName(p) + if (p == "hy2" || p == "tuic") " (UDP)" else " (TCP)"
            }
            val et = com.google.android.material.textfield.TextInputEditText(til.context).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(current[p].toString())
            }
            til.addView(et)
            box.addView(til, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp2px(6) })
            et
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.srv_m_ports)
            .setMessage(R.string.srv_ports_msg)
            .setView(box)
            .setPositiveButton(R.string.srv_rotate_yes) { _, _ ->
                val ports = fields.mapValues { it.value.text.toString().toIntOrNull() ?: 0 }
                    .filter { it.value in 1..65535 }
                Deployer.rotate(s, ports); scrollTop()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
