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
        renderLog()
        renderServers()
    }

    override fun onPause() {
        Deployer.listener = null
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
                val protos = s.protocols.split(',').joinToString(" + ") {
                    when (it) { "vless" -> "VLESS Reality"; "hy2" -> "Hysteria2"; else -> it }
                }
                text = "${s.host} · $protos"
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
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
                action(R.string.srv_a_user) { askUser(s) }
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

    private fun askUser(s: ServerNode) {
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
        val items = arrayOf(getString(R.string.srv_m_uninstall), getString(R.string.srv_m_forget))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(s.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.srv_m_uninstall)
                        .setMessage(getString(R.string.srv_uninstall_confirm, s.name))
                        .setPositiveButton(R.string.srv_m_uninstall_yes) { _, _ -> Deployer.uninstall(s); scrollTop() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()

                    1 -> {
                        ServerStore.remove(s.id); renderServers()
                    }
                }
            }
            .show()
    }
}
