package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.fmt.v2ray.Xray
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins

/** Kombain's own «О Kombain» screen: version, engines, plugins and licence. */
class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    private lateinit var binding: LayoutAboutBinding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutAboutBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.more_about)

        binding.aboutVersion.text = SagerNet.appVersionNameForDisplay
        binding.aboutLicenseBtn.setOnClickListener { showLicense() }
        binding.aboutSourceBtn.setOnClickListener {
            requireContext().launchCustomTab("https://github.com/MatsuriDayo/NekoBoxForAndroid")
        }
        binding.aboutBattery.setOnClickListener {
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:${app.packageName}".toUri()
                ))
            }
        }
        loadComponents()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        var show = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            show = !pm.isIgnoringBatteryOptimizations(app.packageName)
        }
        binding.aboutBattery.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadComponents() {
        runOnDefaultDispatcher {
            val rows = mutableListOf<Triple<String, String, (() -> Unit)?>>()
            rows += Triple("sing-box", getString(R.string.ab_core_engine) + " · " +
                    runCatching {
                        val v = Libcore.versionBox()
                        Regex("""sing-box:?\s*(\S+)""").find(v)?.groupValues?.get(1) ?: v.lineSequence().first()
                    }.getOrDefault("?"), null)
            val xray = if (Xray.isAvailable()) runCatching {
                val p = ProcessBuilder(Xray.executable().absolutePath, "version")
                    .redirectErrorStream(true).start()
                val first = p.inputStream.bufferedReader().readLine().orEmpty()
                p.destroy()
                Regex("""Xray (\S+)""").find(first)?.groupValues?.get(1) ?: first
            }.getOrDefault("?") else getString(R.string.ab_not_bundled)
            rows += Triple("Xray-core", getString(R.string.ab_xray_engine) + " · " + xray, null)
            PackageCache.awaitLoadSync()
            for ((_, pkg) in PackageCache.installedPluginPackages) {
                try {
                    val id = pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                    if (id.isNullOrBlank()) continue
                    rows += Triple(getString(R.string.ab_plugin, id), "v" + pkg.versionName) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", pkg.packageName, null)
                        })
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                val box = binding.aboutComponents
                box.removeAllViews()
                val ctx = requireContext()
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                rows.forEachIndexed { i, (title, sub, click) ->
                    if (i > 0) box.addView(View(ctx).apply {
                        setBackgroundColor(ContextCompat.getColor(ctx, R.color.kombain_tile_stroke))
                    }, LinearLayout.LayoutParams(-1, dp2px(1)).apply {
                        marginStart = dp2px(16); marginEnd = dp2px(16)
                    })
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12))
                        if (click != null) {
                            setBackgroundResource(tv.resourceId)
                            setOnClickListener { click() }
                        }
                    }
                    row.addView(TextView(ctx).apply {
                        text = title
                        textSize = 15.5f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
                    })
                    row.addView(TextView(ctx).apply {
                        text = sub
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
                    })
                    box.addView(row)
                }
            }
        }
    }

    private fun showLicense() {
        runOnDefaultDispatcher {
            val text = runCatching {
                requireContext().assets.open("LICENSE").bufferedReader().readText()
            }.getOrDefault("GPL-3.0")
            onMainDispatcher {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.ab_license)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
}
