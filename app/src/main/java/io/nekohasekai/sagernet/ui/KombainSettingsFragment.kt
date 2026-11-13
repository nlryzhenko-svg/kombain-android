package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreference
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.needRestart
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.ListListener
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/** Kombain settings: the essentials in plain words, each with a "?" explanation. */
class KombainSettingsFragment : ToolbarFragment(R.layout.layout_config_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.ks_title)
        toolbar.inflateMenu(R.menu.kombain_settings_menu)
        toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_help) (activity as? MainActivity)?.displayFragment(HelpFragment())
            true
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.settings, KombainPreferenceFragment())
            .commitAllowingStateLoss()
    }
}

class KombainPreferenceFragment : PreferenceFragmentCompat() {

    companion object {
        const val RULE_RU = "Kombain · РФ напрямую"
        const val RULE_ADS = "Kombain · Без рекламы"
    }

    private lateinit var apps: Preference
    private lateinit var battery: Preference
    private lateinit var bank: Preference
    private lateinit var geoNow: Preference

    private fun updateGeoSummary() {
        val f = java.io.File(io.nekohasekai.sagernet.kombain.Maintenance.geoDir(), "geosite.version.txt")
        val t = maxOf(DataStore.kbGeoUpdated, if (f.exists()) f.lastModified() else 0L)
        geoNow.summary = if (t == 0L) getString(R.string.ks_geo_never) else getString(
            R.string.ks_geo_last,
            android.text.format.DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
        )
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.kombain_preferences)

        val reload = Preference.OnPreferenceChangeListener { _, _ -> needReload(); true }

        findPreference<Preference>(Key.SERVICE_MODE)!!.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }

        apps = findPreference("ks_apps")!!
        apps.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AppManagerActivity::class.java))
            true
        }

        findPreference<Preference>(Key.BYPASS_LAN)!!.onPreferenceChangeListener = reload
        findPreference<Preference>(Key.ACQUIRE_WAKE_LOCK)!!.onPreferenceChangeListener = reload
        findPreference<Preference>(Key.NETWORK_CHANGE_RESET_CONNECTIONS)!!.onPreferenceChangeListener = reload
        findPreference<Preference>("kbBlockQuic")?.onPreferenceChangeListener = reload

        findPreference<SimpleMenuPreference>(Key.REMOTE_DNS)!!.apply {
            value = DataStore.remoteDns
            setSummaryProvider {
                val p = it as SimpleMenuPreference
                p.entry?.toString() ?: getString(R.string.ks_custom, DataStore.remoteDns)
            }
            onPreferenceChangeListener = reload
        }

        ruleSwitch("ks_ru_direct", RULE_RU) {
            RuleEntity(
                name = RULE_RU, enabled = true, outbound = -1L,
                domains = "geosite:category-ru", ip = "geoip:ru",
            )
        }
        ruleSwitch("ks_block_ads", RULE_ADS) {
            RuleEntity(
                name = RULE_ADS, enabled = true, outbound = -2L,
                domains = "geosite:category-ads-all",
            )
        }

        // in «только заблокированное» Russian sites go direct anyway: the switch would only confuse
        val ruDirect = findPreference<Preference>("ks_ru_direct")!!
        ruDirect.isVisible = DataStore.kbRouteMode != "blocked"
        findPreference<Preference>("kbRouteMode")!!.setOnPreferenceChangeListener { _, v ->
            ruDirect.isVisible = v != "blocked"
            if (v == "blocked") runOnDefaultDispatcher {
                // the bundled snapshot works at once; a fresh download follows in the background
                runCatching { io.nekohasekai.sagernet.kombain.BlockedLists.ensure() }.onFailure { Logs.w(it) }
                if (DataStore.serviceState.started) SagerNet.reloadService()
                if (System.currentTimeMillis() - DataStore.kbListsUpdated > 20 * 3600_000L) {
                    runCatching { io.nekohasekai.sagernet.kombain.BlockedLists.update() }.onFailure { Logs.w(it) }
                    if (DataStore.serviceState.started) SagerNet.reloadService()
                }
            } else if (DataStore.serviceState.started) SagerNet.reloadService()
            true
        }

        bank = findPreference("kbBankDirect")!!
        bank.onPreferenceChangeListener = reload
        val upkeep = Preference.OnPreferenceChangeListener { _, _ ->
            runOnDefaultDispatcher { io.nekohasekai.sagernet.kombain.Maintenance.schedule() }; true
        }
        listOf("kbAutoSubs", "kbAutoGeo", "kbWatchServers").forEach { findPreference<Preference>(it)!!.onPreferenceChangeListener = upkeep }
        geoNow = findPreference("ks_geo_now")!!
        geoNow.setOnPreferenceClickListener {
            geoNow.setSummary(R.string.ks_geo_running)
            runOnDefaultDispatcher {
                var changed = runCatching { io.nekohasekai.sagernet.kombain.Maintenance.updateGeo(force = true) }.getOrDefault(false)
                if (io.nekohasekai.sagernet.kombain.BlockedLists.enabled) {
                    if (runCatching { io.nekohasekai.sagernet.kombain.BlockedLists.update() }.onFailure { Logs.w(it) }.isSuccess) {
                        changed = true
                        if (DataStore.serviceState.started) SagerNet.reloadService()
                    }
                }
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    updateGeoSummary()
                    (activity as? MainActivity)?.snackbar(getString(if (changed) R.string.ks_geo_done else R.string.ks_geo_same))?.show()
                }
            }
            true
        }

        battery = findPreference("ks_battery")!!
        battery.setOnPreferenceClickListener { openBatterySettings(); true }

        findPreference<Preference>(Key.NIGHT_THEME)!!.setOnPreferenceChangeListener { _, v ->
            Theme.currentNightMode = (v as String).toInt()
            Theme.applyNightTheme()
            true
        }
        findPreference<Preference>(Key.APP_THEME)!!.setOnPreferenceChangeListener { _, v ->
            if (DataStore.serviceState.started) SagerNet.reloadService()
            val theme = Theme.getTheme(v as Int)
            app.setTheme(theme)
            requireActivity().apply {
                setTheme(theme)
                ActivityCompat.recreate(this)
            }
            true
        }
        findPreference<Preference>(Key.LOG_LEVEL)!!.setOnPreferenceChangeListener { _, _ ->
            needRestart(); true
        }

        reserve = findPreference("ks_reserve")!!
        updateReserveSummary()
        reserve.setOnPreferenceClickListener { askReserve(); true }
        findPreference<Preference>("ks_advanced")!!.setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragment(SettingsFragment()); true
        }
    }

    private lateinit var reserve: Preference

    private fun updateReserveSummary() {
        reserve.summary = if (io.nekohasekai.sagernet.kombain.AltSub.enabled && DataStore.kbGistLogin.isNotBlank())
            getString(R.string.ks_reserve_on, DataStore.kbGistLogin) else getString(R.string.ks_reserve_off)
    }

    private fun askReserve() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.ks_reserve_hint)
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val box = android.widget.FrameLayout(ctx).apply {
            val d = resources.displayMetrics.density
            setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
            addView(input)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.ks_reserve)
            .setMessage(R.string.ks_reserve_msg)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val token = input.text.toString().trim()
                if (token.isEmpty()) return@setPositiveButton
                reserve.setSummary(R.string.ks_reserve_check)
                runOnDefaultDispatcher {
                    val old = io.nekohasekai.sagernet.kombain.AltSub.token
                    io.nekohasekai.sagernet.kombain.AltSub.token = token
                    val r = runCatching { io.nekohasekai.sagernet.kombain.AltSub.setup() }
                    if (r.isFailure) io.nekohasekai.sagernet.kombain.AltSub.token = old
                    onMainDispatcher {
                        if (!isAdded) return@onMainDispatcher
                        updateReserveSummary()
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.ks_reserve)
                            .setMessage(r.exceptionOrNull()?.message ?: getString(R.string.ks_reserve_done))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            .setNeutralButton(R.string.ks_reserve_disable) { _, _ ->
                io.nekohasekai.sagernet.kombain.AltSub.token = ""
                updateReserveSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        apps.summary = getString(
            when {
                !DataStore.proxyApps -> R.string.ks_apps_all
                DataStore.bypass -> R.string.ks_apps_except
                else -> R.string.ks_apps_some
            }
        )
        val pm = requireContext().getSystemService<PowerManager>()
        val ignoring = Build.VERSION.SDK_INT < 23 ||
                pm?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
        battery.summary = getString(if (ignoring) R.string.ks_battery_on else R.string.ks_battery_off)
        updateGeoSummary()
        runOnDefaultDispatcher {
            val n = io.nekohasekai.sagernet.kombain.RuApps.installed(requireContext().packageManager).size
            onMainDispatcher { if (isAdded) bank.summary = getString(R.string.ks_bank_sum, n) }
        }
    }

    /** A switch that adds/removes one of Kombain's ready-made routing rules. */
    private fun ruleSwitch(key: String, ruleName: String, make: () -> RuleEntity) {
        val sw = findPreference<SwitchPreference>(key)!!
        runOnDefaultDispatcher {
            val on = SagerDatabase.rulesDao.allRules().any { it.name == ruleName && it.enabled }
            onMainDispatcher { sw.isChecked = on }
        }
        sw.setOnPreferenceChangeListener { _, value ->
            val enable = value as Boolean
            runOnDefaultDispatcher {
                val existing = SagerDatabase.rulesDao.allRules().filter { it.name == ruleName }
                if (enable) {
                    if (existing.isEmpty()) ProfileManager.createRule(make())
                    else existing.forEach { ProfileManager.updateRule(it.apply { enabled = true }) }
                } else {
                    existing.forEach { ProfileManager.updateRule(it.apply { enabled = false }) }
                }
                onMainDispatcher { if (isAdded) needReload() }
            }
            true
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        val ctx = requireContext()
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)),
        )
        for (i in intents) {
            try {
                startActivity(i); return
            } catch (_: Exception) {
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyKombainCards()
    }

    // ---- "?" buttons -------------------------------------------------------

    private fun helpText(key: String?): String? {
        if (key == null) return null
        val id = resources.getIdentifier("kh_$key", "string", requireContext().packageName)
        return if (id != 0) getString(id) else null
    }

    @SuppressLint("RestrictedApi")
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        return object : PreferenceGroupAdapter(preferenceScreen) {
            override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                val pref = getItem(position) ?: return
                // the horizontal row that holds icon / title / widget (differs for dropdown prefs)
                val title = holder.itemView.findViewById<View>(android.R.id.title) ?: return
                var parent = title.parent
                while (parent is ViewGroup && !(parent is LinearLayout && parent.orientation == LinearLayout.HORIZONTAL)) {
                    parent = parent.parent
                }
                val root = (parent as? ViewGroup) ?: (holder.itemView as? ViewGroup) ?: return
                var help = root.findViewWithTag<ImageView>("ks_help")
                val text = if (pref is PreferenceCategory) null else helpText(pref.key)
                if (text == null) {
                    help?.isVisible = false
                    return
                }
                if (help == null) {
                    val ctx = root.context
                    val size = dp2px(40)
                    help = ImageView(ctx).apply {
                        tag = "ks_help"
                        setImageResource(R.drawable.ic_kombain_help)
                        val pad = dp2px(9)
                        setPadding(pad, pad, pad, pad)
                        val tv = android.util.TypedValue()
                        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                        setBackgroundResource(tv.resourceId)
                        contentDescription = ctx.getString(R.string.ks_help)
                    }
                    val widget = root.findViewById<View>(android.R.id.widget_frame)
                    val idx = widget?.let { root.indexOfChild(it) }?.takeIf { it >= 0 } ?: root.childCount
                    root.addView(help, idx, LinearLayout.LayoutParams(size, size).apply {
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    })
                }
                help.isVisible = true
                help.setOnClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(pref.title)
                        .setMessage(text)
                        .setPositiveButton(R.string.ks_help_ok, null)
                        .show()
                }
            }
        }
    }
}
