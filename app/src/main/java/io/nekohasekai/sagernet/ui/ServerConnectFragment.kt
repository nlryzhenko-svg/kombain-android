package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.kombain.Deployer
import io.nekohasekai.sagernet.kombain.ServerNode
import io.nekohasekai.sagernet.ktx.snackbar

/** «Подключить существующий»: add a server that already runs Kombain, without reinstalling it. */
class ServerConnectFragment : ToolbarFragment(R.layout.layout_server_connect) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.srv_connect)
        val form = view.findViewById<View>(R.id.node_form)
        form.findViewById<TextView>(R.id.form_title).setText(R.string.srv_connect_title)
        form.findViewById<TextView>(R.id.form_hint).setText(R.string.srv_connect_hint)
        // the masking site is read from the server
        var p: View? = form.findViewById(R.id.f_sni)
        while (p != null && p !is TextInputLayout) p = p.parent as? View
        p?.visibility = View.GONE
        form.findViewById<View>(R.id.f_sni_pick).visibility = View.GONE
        view.findViewById<View>(R.id.btn_connect_go).setOnClickListener { connect(form) }
    }

    private fun View.f(id: Int) = findViewById<EditText>(id)

    private fun connect(form: View) {
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
        val node = ServerNode(
            // empty name: taken from the server
            name = form.f(R.id.f_name).text.toString().trim().replace("|", ""),
            host = host, sshPort = form.f(R.id.f_port).text.toString().toIntOrNull() ?: 22,
            user = form.f(R.id.f_user).text.toString().trim().ifEmpty { "root" },
            password = pass, privateKey = key,
        )
        Deployer.connectExisting(node)
        (activity as? MainActivity)?.displayFragment(MyServersFragment())
    }
}
