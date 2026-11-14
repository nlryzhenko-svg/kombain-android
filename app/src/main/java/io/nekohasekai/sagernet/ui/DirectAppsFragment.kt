package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.kombain.RuApps
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

/**
 * «Приложения мимо VPN»: banks, Gosuslugi, marketplaces … found on the phone, by category, each with a tick;
 * any other app can be added. They go around the tunnel entirely, the rest of the phone stays under VPN.
 */
class DirectAppsFragment : ToolbarFragment(R.layout.layout_direct_apps) {

    private lateinit var box: LinearLayout
    private var changed = false

    private class App(val pkg: String, val label: String, val icon: Drawable?)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.da_title)
        box = view.findViewById(R.id.direct_box)
        render()
    }

    override fun onPause() {
        super.onPause()
        if (changed && DataStore.serviceState.started) SagerNet.reloadService()
        changed = false
    }

    private fun color(id: Int) = ContextCompat.getColor(requireContext(), id)

    private fun load(pm: PackageManager, pkg: String): App? = runCatching {
        val ai = pm.getApplicationInfo(pkg, 0)
        App(pkg, pm.getApplicationLabel(ai).toString(), runCatching { pm.getApplicationIcon(ai) }.getOrNull())
    }.getOrNull()

    private fun render() {
        val ctx = requireContext()
        val pm = ctx.packageManager
        runOnDefaultDispatcher {
            val off = RuApps.off()
            val cats = RuApps.categories.map { c -> c.title to c.packages.mapNotNull { load(pm, it) }.sortedBy { it.label.lowercase() } }
                .filter { it.second.isNotEmpty() }
            val extra = RuApps.extra().mapNotNull { load(pm, it) }.sortedBy { it.label.lowercase() }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                box.removeAllViews()

                val sw = SwitchMaterial(ctx).apply {
                    setText(R.string.da_switch)
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                    isChecked = DataStore.kbBankDirect
                    minHeight = dp2px(56)
                    setOnCheckedChangeListener { _, on ->
                        DataStore.kbBankDirect = on
                        changed = true
                        render()
                    }
                }
                box.addView(sw)
                box.addView(note(getString(R.string.da_intro)))
                val enabled = DataStore.kbBankDirect

                val total = cats.sumOf { c -> c.second.count { it.pkg !in off } } + extra.size
                box.addView(TextView(ctx).apply {
                    text = if (cats.isEmpty() && extra.isEmpty()) getString(R.string.da_none_found)
                    else getString(R.string.da_count, total)
                    setTextColor(color(R.color.kombain_text2))
                    textSize = 13f
                    setPadding(0, dp2px(10), 0, 0)
                })

                for ((title, apps) in cats) {
                    box.addView(header(title))
                    for (a in apps) box.addView(row(a, a.pkg !in off, enabled) { on ->
                        RuApps.setOff(if (on) RuApps.off() - a.pkg else RuApps.off() + a.pkg)
                        changed = true
                    })
                }

                box.addView(header(getString(R.string.da_own)))
                for (a in extra) box.addView(row(a, true, enabled) { on ->
                    if (!on) {
                        RuApps.setExtra(RuApps.extra() - a.pkg)
                        changed = true
                        render()
                    }
                })
                box.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.da_add)
                    isEnabled = enabled
                    setOnClickListener { pickApp() }
                })
            }
        }
    }

    private fun note(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setBackgroundResource(R.drawable.kombain_note_bg)
        setPadding(dp2px(12), dp2px(12), dp2px(12), dp2px(12))
        setLineSpacing(dp2px(3).toFloat(), 1f)
    }

    private fun header(title: String) = TextView(requireContext()).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(R.color.kombain_accent))
        setPadding(0, dp2px(18), 0, dp2px(4))
    }

    private fun row(a: App, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp2px(52)
        alpha = if (enabled) 1f else 0.5f
        addView(ImageView(context).apply {
            setImageDrawable(a.icon)
            layoutParams = LinearLayout.LayoutParams(dp2px(36), dp2px(36)).apply { marginEnd = dp2px(14) }
        })
        addView(TextView(context).apply {
            text = a.label
            textSize = 15.5f
            setTextColor(color(R.color.kombain_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val cb = CheckBox(context).apply {
            isChecked = checked
            isEnabled = enabled
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        addView(cb)
        if (enabled) setOnClickListener { cb.toggle() }
    }

    /** Any installed app with internet access, with a search line. */
    private fun pickApp() {
        val ctx = requireContext()
        val pm = ctx.packageManager
        runOnDefaultDispatcher {
            val taken = RuApps.extra() + RuApps.packages
            val apps = pm.getInstalledApplications(0).asSequence()
                .filter { it.packageName != ctx.packageName && it.packageName !in taken }
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
                .map { App(it.packageName, pm.getApplicationLabel(it).toString(), null) }
                .sortedBy { it.label.lowercase() }.toList()
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val search = EditText(ctx).apply { hint = getString(R.string.da_search); isSingleLine = true }
                val wrap = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp2px(22), dp2px(8), dp2px(22), 0)
                    addView(search)
                    addView(androidx.core.widget.NestedScrollView(ctx).apply {
                        addView(list)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp2px(380))
                    })
                }
                var dialog: androidx.appcompat.app.AlertDialog? = null
                fun fill(q: String) {
                    list.removeAllViews()
                    apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }.take(150).forEach { a ->
                        list.addView(TextView(ctx).apply {
                            text = a.label
                            textSize = 16f
                            minHeight = dp2px(46)
                            gravity = Gravity.CENTER_VERTICAL
                            setBackgroundResource(android.R.drawable.list_selector_background)
                            setOnClickListener {
                                RuApps.setExtra(RuApps.extra() + a.pkg)
                                changed = true
                                dialog?.dismiss()
                                render()
                            }
                        })
                    }
                }
                search.addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) = fill(s?.toString().orEmpty())
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                fill("")
                dialog = MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.da_add)
                    .setView(wrap)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
