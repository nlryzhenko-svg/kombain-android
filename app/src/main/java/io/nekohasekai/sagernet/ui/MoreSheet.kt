package io.nekohasekai.sagernet.ui

import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/** «Ещё»: a bottom sheet with everything that isn't a bottom-bar tab. */
object MoreSheet {

    private class Item(val icon: Int, val title: Int, val sub: Int, val open: (MainActivity) -> Unit)

    private val items = listOf(
        Item(R.drawable.ic_kombain_server, R.string.more_servers, R.string.more_servers_sub) { it.displayFragment(MyServersFragment()) },
        Item(R.drawable.ic_kombain_speed, R.string.more_diag, R.string.more_diag_sub) { it.displayFragment(DiagnosticsFragment()) },
        Item(R.drawable.ic_kombain_folders, R.string.more_groups, R.string.more_groups_sub) { it.displayFragment(GroupFragment()) },
        Item(R.drawable.ic_kombain_log, R.string.more_log, R.string.more_log_sub) { it.displayFragment(LogcatFragment()) },
        Item(R.drawable.ic_kombain_tools, R.string.more_tools, R.string.more_tools_sub) { it.displayFragment(ToolsFragment()) },
        Item(R.drawable.ic_kombain_help_toolbar, R.string.ks_help, R.string.more_help_sub) { it.displayFragment(HelpFragment()) },
        Item(R.drawable.ic_kombain_info, R.string.more_about, R.string.more_about_sub) { it.displayFragment(AboutFragment()) },
    )

    fun show(activity: MainActivity) {
        val ctx = activity
        val sheet = BottomSheetDialog(ctx, R.style.ThemeOverlay_Kombain_BottomSheet)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(16), dp2px(10), dp2px(16), dp2px(20))
        }
        root.addView(View(ctx).apply {
            setBackgroundResource(R.drawable.kombain_handle)
        }, LinearLayout.LayoutParams(dp2px(36), dp2px(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp2px(14)
        })
        root.addView(TextView(ctx).apply {
            setText(R.string.bn_more)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
            setPadding(dp2px(4), 0, 0, dp2px(10))
        })
        val grid = GridLayout(ctx).apply { columnCount = 2 }
        for (item in items) {
            val card = MaterialCardView(ctx).apply {
                radius = dp2px(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp2px(1)
                strokeColor = ContextCompat.getColor(ctx, R.color.kombain_tile_stroke)
                setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.kombain_bg))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    sheet.dismiss()
                    item.open(activity)
                    activity.markMore()
                }
            }
            val inner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp2px(14), dp2px(14), dp2px(14), dp2px(14))
                minimumHeight = dp2px(96)
            }
            inner.addView(ImageView(ctx).apply {
                setImageResource(item.icon)
                setColorFilter(ContextCompat.getColor(ctx, R.color.kombain_fab))
            }, LinearLayout.LayoutParams(dp2px(26), dp2px(26)))
            inner.addView(TextView(ctx).apply {
                setText(item.title)
                textSize = 15.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
                setPadding(0, dp2px(10), 0, 0)
            })
            inner.addView(TextView(ctx).apply {
                setText(item.sub)
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
            })
            card.addView(inner)
            grid.addView(card, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply {
                width = 0
                setMargins(dp2px(5), dp2px(5), dp2px(5), dp2px(5))
            })
        }
        root.addView(grid)
        sheet.setContentView(root)
        sheet.show()
    }
}
