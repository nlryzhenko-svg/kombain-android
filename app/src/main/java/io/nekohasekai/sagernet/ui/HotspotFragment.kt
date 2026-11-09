package io.nekohasekai.sagernet.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutHotspotBinding
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.widget.QRCodeDialog
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * "Раздача": shares the running tunnel with devices on this phone's hotspot
 * (or any local network) through the built-in mixed HTTP/SOCKS5 proxy.
 *
 * Android routes hotspot clients around VpnService, so without root the
 * proxy is the reliable way to give them the VPN.
 */
class HotspotFragment : ToolbarFragment(R.layout.layout_hotspot) {

    private var binding: LayoutHotspotBinding? = null

    private val tetherReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            view?.postDelayed({ refresh() }, 800)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_hotspot)
        toolbar.inflateMenu(R.menu.hotspot_menu)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_refresh) refresh()
            true
        }

        val b = LayoutHotspotBinding.bind(view)
        binding = b

        b.shareSwitch.isChecked = DataStore.allowAccess
        b.shareSwitch.setOnCheckedChangeListener { _, checked ->
            if (DataStore.allowAccess == checked) return@setOnCheckedChangeListener
            DataStore.allowAccess = checked
            if (DataStore.serviceState.canStop) {
                SagerNet.reloadService()
                snackbar(R.string.hotspot_reloading).show()
            }
            refresh()
        }

        b.copyButton.setOnClickListener {
            val addr = currentAddress() ?: return@setOnClickListener
            if (SagerNet.trySetPrimaryClip(addr)) snackbar(R.string.hotspot_copied).show()
        }

        b.qrButton.setOnClickListener {
            val host = bestAddress()?.address ?: return@setOnClickListener
            val link = SOCKSBean().apply {
                initializeDefaultValues()
                serverAddress = host
                serverPort = DataStore.mixedPort
                name = getString(R.string.hotspot_qr_name)
            }.toUri()
            QRCodeDialog(link, getString(R.string.hotspot_qr_name))
                .showAllowingStateLoss(parentFragmentManager)
        }

        b.tetherSettings.setOnClickListener { openTetherSettings() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("android.net.conn.TETHER_STATE_CHANGED").apply {
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        }
        ContextCompat.registerReceiver(
            requireContext(), tetherReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )
        refresh()
    }

    override fun onPause() {
        runCatching { requireContext().unregisterReceiver(tetherReceiver) }
        super.onPause()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    // ---- state ----

    data class LocalAddress(val iface: String, val address: String, val kind: Kind)

    enum class Kind { HOTSPOT, USB, WIFI, OTHER }

    private fun scanAddresses(): List<LocalAddress> {
        val result = mutableListOf<LocalAddress>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                // mobile data, VPN tunnels and docker-ish bridges are useless here
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
                    name.startsWith("dummy") || name.startsWith("ipsec") || name.startsWith("clat")
                ) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a !is Inet4Address || !a.isSiteLocalAddress) continue
                    val kind = when {
                        name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap") ||
                                name == "wlan1" || name == "wlan2" || name.startsWith("wigig") -> Kind.HOTSPOT

                        name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") -> Kind.USB
                        name.startsWith("wlan") -> Kind.WIFI
                        else -> Kind.OTHER
                    }
                    result.add(LocalAddress(ni.name, a.hostAddress ?: continue, kind))
                }
            }
        }
        return result.sortedBy { it.kind.ordinal }
    }

    private fun bestAddress(): LocalAddress? = scanAddresses().firstOrNull()

    private fun currentAddress(): String? = bestAddress()?.let { "${it.address}:${DataStore.mixedPort}" }

    private fun refresh() {
        val b = binding ?: return
        val ctx = context ?: return

        val connected = DataStore.serviceState.connected
        b.vpnStatus.setText(if (connected) R.string.hotspot_vpn_on else R.string.hotspot_vpn_off)
        b.vpnDot.backgroundTintList = ColorStateList.valueOf(
            if (connected) 0xFF2FE3A6.toInt() else 0xFFE57373.toInt()
        )

        if (b.shareSwitch.isChecked != DataStore.allowAccess) b.shareSwitch.isChecked = DataStore.allowAccess

        val all = scanAddresses()
        val best = all.firstOrNull()
        val port = DataStore.mixedPort
        val enabled = DataStore.allowAccess && best != null

        if (best == null) {
            b.addressHost.text = getString(R.string.hotspot_no_network)
            b.addressPort.text = ""
            b.addressHint.text = getString(R.string.hotspot_no_network_hint)
        } else {
            b.addressHost.text = best.address
            b.addressPort.text = getString(R.string.hotspot_port, port)
            b.addressHint.text = when {
                !DataStore.allowAccess -> getString(R.string.hotspot_hint_switch_off)
                !connected -> getString(R.string.hotspot_hint_vpn_off)
                else -> getString(
                    R.string.hotspot_hint_ready, kindLabel(best.kind), best.iface
                )
            }
        }
        b.addressCard.alpha = if (enabled) 1f else 0.6f
        b.copyButton.isEnabled = enabled
        b.qrButton.isEnabled = enabled

        val others = all.drop(1)
        if (others.isEmpty()) {
            b.otherAddresses.visibility = View.GONE
        } else {
            b.otherAddresses.visibility = View.VISIBLE
            b.otherAddresses.text = getString(R.string.hotspot_other) + "\n" +
                    others.joinToString("\n") { "${it.address}:$port  · ${kindLabel(it.kind)} (${it.iface})" }
        }
    }

    private fun kindLabel(kind: Kind) = getString(
        when (kind) {
            Kind.HOTSPOT -> R.string.hotspot_kind_ap
            Kind.USB -> R.string.hotspot_kind_usb
            Kind.WIFI -> R.string.hotspot_kind_wifi
            Kind.OTHER -> R.string.hotspot_kind_other
        }
    )

    private fun openTetherSettings() {
        val intents = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TetherSettingsActivity"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }
}
