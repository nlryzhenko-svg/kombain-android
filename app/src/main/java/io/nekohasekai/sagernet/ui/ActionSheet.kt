package io.nekohasekai.sagernet.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/** Kombain bottom sheet with a list of actions (icon, title, optional subtitle). */
object ActionSheet {

    class Action(
        val icon: Int,
        val title: CharSequence,
        val subtitle: CharSequence? = null,
        val danger: Boolean = false,
        val run: () -> Unit,
    )

    fun show(ctx: Context, title: CharSequence?, actions: List<Action>) {
        val sheet = BottomSheetDialog(ctx, R.style.ThemeOverlay_Kombain_BottomSheet)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(8), dp2px(10), dp2px(8), dp2px(16))
        }
        root.addView(View(ctx).apply { setBackgroundResource(R.drawable.kombain_handle) },
            LinearLayout.LayoutParams(dp2px(36), dp2px(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp2px(10)
            })
        if (title != null) root.addView(TextView(ctx).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text))
            setPadding(dp2px(16), dp2px(4), dp2px(16), dp2px(8))
        })
        val tv = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        for (a in actions) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12))
                setBackgroundResource(tv.resourceId)
                isClickable = true
                setOnClickListener { sheet.dismiss(); a.run() }
            }
            val color = ContextCompat.getColor(ctx, if (a.danger) R.color.kombain_danger else R.color.kombain_fab)
            row.addView(ImageView(ctx).apply {
                setImageResource(a.icon)
                setColorFilter(color)
            }, LinearLayout.LayoutParams(dp2px(24), dp2px(24)))
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp2px(18), 0, 0, 0)
            }
            texts.addView(TextView(ctx).apply {
                text = a.title
                textSize = 16f
                setTextColor(if (a.danger) color else ContextCompat.getColor(ctx, R.color.kombain_text))
            })
            if (a.subtitle != null) texts.addView(TextView(ctx).apply {
                text = a.subtitle
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.kombain_text2))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(row)
        }
        sheet.setContentView(root)
        sheet.show()
    }
}
