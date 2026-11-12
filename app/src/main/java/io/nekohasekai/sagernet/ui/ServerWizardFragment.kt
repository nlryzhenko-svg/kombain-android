package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButtonToggleGroup
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.kombain.Deployer
import io.nekohasekai.sagernet.kombain.ServerNode
import io.nekohasekai.sagernet.ktx.snackbar

/** Wizard: deploy one node or a cascade (entry in Russia → exit abroad) over SSH. */
class ServerWizardFragment : ToolbarFragment(R.layout.layout_server_wizard) {

    private lateinit var main: View
    private lateinit var entry: View
    private var cascade = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.srv_new)
        main = view.findViewById(R.id.node_main)
        entry = view.findViewById(R.id.node_entry)
        entry.f(R.id.f_sni).setText("ya.ru")
        main.f(R.id.f_sni).setText("www.microsoft.com")

        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.mode_group)
        group.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                cascade = id == R.id.mode_cascade
                applyMode(view)
            }
        }
        applyMode(view)

        for (form in listOf(main, entry)) form.findViewById<View>(R.id.f_sni_pick).setOnClickListener { pickSite(form) }
        val tg = view.findViewById<CheckBox>(R.id.p_tg)
        tg.setOnCheckedChangeListener { _, on -> view.findViewById<View>(R.id.port_tg_box).isVisible = on }
        view.findViewById<View>(R.id.btn_install).setOnClickListener { install(view) }
    }

    private fun View.f(id: Int) = findViewById<EditText>(id)

    /** form → (domain, neighbour address) picked with «Подобрать» */
    private val picked = HashMap<Int, Pair<String, String>>()

    private fun pickSite(form: View) {
        SitePicker.show(requireContext(), form.f(R.id.f_sni).text.toString().trim(),
            markWhitelist = cascade && form.id == entry.id,
            scan = { done ->
                val host = form.f(R.id.f_host).text.toString().trim()
                val pass = form.f(R.id.f_pass).text.toString()
                val key = form.f(R.id.f_key).text.toString().trim()
                if (host.isEmpty() || (pass.isEmpty() && key.isEmpty())) done(null, getString(R.string.srv_sp_need))
                else {
                    val node = ServerNode(
                        host = host, sshPort = form.f(R.id.f_port).text.toString().toIntOrNull() ?: 22,
                        user = form.f(R.id.f_user).text.toString().trim().ifEmpty { "root" }, password = pass, privateKey = key,
                    )
                    io.nekohasekai.sagernet.kombain.Deployer.scanSites(node, done)
                }
            }) { domain, dest ->
            form.f(R.id.f_sni).setText(domain)
            picked[form.id] = domain to dest
        }
    }

    private fun applyMode(view: View) {
        view.findViewById<TextView>(R.id.mode_hint).setText(
            if (cascade) R.string.srv_mode_cascade_hint else R.string.srv_mode_single_hint
        )
        entry.isVisible = cascade
        entry.findViewById<TextView>(R.id.form_title).setText(R.string.srv_entry_title)
        entry.findViewById<TextView>(R.id.form_hint).setText(R.string.srv_entry_hint)
        main.findViewById<TextView>(R.id.form_title).setText(if (cascade) R.string.srv_exit_title else R.string.srv_single_title)
        main.findViewById<TextView>(R.id.form_hint).setText(if (cascade) R.string.srv_exit_hint else R.string.srv_single_hint)
        if (main.f(R.id.f_name).text.isNullOrBlank()) main.f(R.id.f_name).hint = null
    }

    private fun readNode(form: View, defaultName: String): ServerNode? {
        val host = form.f(R.id.f_host).text.toString().trim()
        val pass = form.f(R.id.f_pass).text.toString()
        val key = form.f(R.id.f_key).text.toString().trim()
        val port = form.f(R.id.f_port).text.toString().toIntOrNull() ?: 22
        if (host.isEmpty()) {
            form.f(R.id.f_host).error = getString(R.string.srv_err_host); form.f(R.id.f_host).requestFocus(); return null
        }
        if (pass.isEmpty() && key.isEmpty()) {
            form.f(R.id.f_pass).error = getString(R.string.srv_err_auth); form.f(R.id.f_pass).requestFocus(); return null
        }
        val sni = form.f(R.id.f_sni).text.toString().trim().removePrefix("https://").trimEnd('/')
        if (sni.isEmpty()) {
            form.f(R.id.f_sni).error = getString(R.string.srv_err_sni); return null
        }
        return ServerNode(
            dest = picked[form.id]?.takeIf { it.first == sni }?.second.orEmpty(),
            name = form.f(R.id.f_name).text.toString().trim().ifEmpty { defaultName }.replace("|", ""),
            host = host, sshPort = port,
            user = form.f(R.id.f_user).text.toString().trim().ifEmpty { "root" },
            password = pass, privateKey = key, sni = sni,
        )
    }

    private fun install(view: View) {
        if (Deployer.running) {
            snackbar(R.string.srv_busy).show(); return
        }
        val protos = buildList {
            if (view.findViewById<CheckBox>(R.id.p_vless).isChecked) add("vless")
            if (view.findViewById<CheckBox>(R.id.p_xhttp).isChecked) add("xhttp")
            if (view.findViewById<CheckBox>(R.id.p_hy2).isChecked) add("hy2")
            if (view.findViewById<CheckBox>(R.id.p_tuic).isChecked) add("tuic")
            // Sudoku can't hand traffic to a cascade's exit: single servers only
            if (!cascade && view.findViewById<CheckBox>(R.id.p_sudoku).isChecked) add("sudoku")
        }
        if (protos.isEmpty()) {
            snackbar(R.string.srv_err_vpn_proto).show(); return
        }
        val withTg = view.findViewById<CheckBox>(R.id.p_tg).isChecked
        val vPort = view.findViewById<EditText>(R.id.port_vless).text.toString().toIntOrNull() ?: 443
        val hPort = view.findViewById<EditText>(R.id.port_hy).text.toString().toIntOrNull() ?: 443
        val xPort = view.findViewById<EditText>(R.id.port_xhttp).text.toString().toIntOrNull() ?: 8443
        val tPort = view.findViewById<EditText>(R.id.port_tuic).text.toString().toIntOrNull() ?: 8443
        val gPort = view.findViewById<EditText>(R.id.port_tg).text.toString().toIntOrNull() ?: 9443
        if (("xhttp" in protos && xPort == vPort) || ("tuic" in protos && "hy2" in protos && tPort == hPort) ||
            (withTg && (gPort == vPort || ("xhttp" in protos && gPort == xPort)))
        ) {
            snackbar(R.string.srv_err_ports).show(); return
        }

        val mainNode = readNode(main, if (cascade) getString(R.string.srv_default_exit) else getString(R.string.srv_default_single)) ?: return
        mainNode.protocols = protos.joinToString(","); mainNode.vpnPort = vPort; mainNode.hyPort = hPort
        mainNode.xhPort = xPort; mainNode.tuicPort = tPort; mainNode.tgPort = gPort
        if (cascade) {
            val entryNode = readNode(entry, getString(R.string.srv_default_entry)) ?: return
            entryNode.protocols = mainNode.protocols; entryNode.vpnPort = vPort; entryNode.hyPort = hPort
            entryNode.xhPort = xPort; entryNode.tuicPort = tPort; entryNode.tgPort = gPort
            // Telegram connects to the entry node (in Russia); it forwards to the exit
            if (withTg) entryNode.protocols += ",tg"
            // the bridge between nodes always uses VLESS Reality
            if ("vless" !in protos) mainNode.protocols = "vless," + mainNode.protocols
            Deployer.installCascade(mainNode, entryNode)
        } else {
            if (withTg) mainNode.protocols += ",tg"
            Deployer.installSingle(mainNode)
        }
        (activity as? MainActivity)?.displayFragment(MyServersFragment())
    }
}
