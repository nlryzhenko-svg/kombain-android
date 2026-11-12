package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.kombain.Deployer
import io.nekohasekai.sagernet.kombain.ServerNode
import io.nekohasekai.sagernet.kombain.ServerStore
import io.nekohasekai.sagernet.ktx.snackbar

/** «Переехать на новый сервер»: the same keys, users and settings on a new VPS (from the app's backup). */
class ServerMoveFragment : ToolbarFragment(R.layout.layout_server_connect) {

    companion object {
        fun of(node: ServerNode) = ServerMoveFragment().apply { arguments = Bundle().apply { putString("id", node.id) } }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val old = ServerStore.get(requireArguments().getString("id").orEmpty())
        toolbar.setTitle(R.string.srv_move)
        val form = view.findViewById<View>(R.id.node_form)
        form.findViewById<TextView>(R.id.form_title).text = getString(R.string.srv_move_title, old?.name ?: "")
        form.findViewById<TextView>(R.id.form_hint).text = getString(
            if (old?.backup.isNullOrBlank()) R.string.srv_move_hint_nobackup else R.string.srv_move_hint,
            old?.host ?: ""
        )
        // name and masking site come from the backup
        for (id in intArrayOf(R.id.f_sni, R.id.f_name)) {
            var p: View? = form.findViewById(id)
            while (p != null && p !is TextInputLayout) p = p.parent as? View
            p?.visibility = View.GONE
        }
        form.findViewById<View>(R.id.f_sni_pick).visibility = View.GONE
        view.findViewById<View>(R.id.connect_note).visibility = View.GONE
        view.findViewById<MaterialButton>(R.id.btn_connect_go).apply {
            setText(R.string.srv_move_go)
            setOnClickListener { if (old != null) move(form, old) }
        }
    }

    private fun View.f(id: Int) = findViewById<EditText>(id)

    private fun move(form: View, old: ServerNode) {
        if (Deployer.running) {
            snackbar(R.string.srv_busy).show(); return
        }
        val host = form.f(R.id.f_host).text.toString().trim()
        val pass = form.f(R.id.f_pass).text.toString()
        val key = form.f(R.id.f_key).text.toString().trim()
        if (host.isEmpty()) {
            form.f(R.id.f_host).error = getString(R.string.srv_err_host); form.f(R.id.f_host).requestFocus(); return
        }
        if (pass.isEmpty() && key.isEmpty()) {
            form.f(R.id.f_pass).error = getString(R.string.srv_err_auth); form.f(R.id.f_pass).requestFocus(); return
        }
        if (host == old.host && (form.f(R.id.f_port).text.toString().toIntOrNull() ?: 22) == old.sshPort) {
            form.f(R.id.f_host).error = getString(R.string.srv_move_same); return
        }
        val target = ServerNode(
            name = old.name, host = host, sshPort = form.f(R.id.f_port).text.toString().toIntOrNull() ?: 22,
            user = form.f(R.id.f_user).text.toString().trim().ifEmpty { "root" },
            password = pass, privateKey = key,
        )
        Deployer.move(old, target)
        (activity as? MainActivity)?.displayFragment(MyServersFragment())
    }
}
