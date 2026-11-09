package io.nekohasekai.sagernet.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * Kombain look for every settings list: consecutive preferences between two
 * categories are drawn as one rounded card with thin separators. Works purely as
 * an ItemDecoration, so preference layouts, ids and custom adapters stay intact.
 */
class PrefCardDecoration(ctx: android.content.Context) : RecyclerView.ItemDecoration() {

    private val side = dp2px(14)
    private val gap = dp2px(6)
    private val radius = dp2px(20).toFloat()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.kombain_tile)
    }
    private val line = Paint().apply {
        color = ContextCompat.getColor(ctx, R.color.kombain_tile_stroke)
        strokeWidth = dp2px(1).toFloat().coerceAtLeast(1f)
    }
    private val path = Path()
    private val rect = RectF()

    private fun isCategory(parent: RecyclerView, pos: Int): Boolean {
        val adapter = parent.adapter as? PreferenceGroupAdapter ?: return true
        if (pos < 0 || pos >= adapter.itemCount) return true
        return adapter.getItem(pos) is PreferenceCategory
    }

    override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        if (isCategory(parent, pos)) {
            out.set(side + dp2px(4), 0, side, 0)
            return
        }
        out.left = side
        out.right = side
        if (isCategory(parent, pos - 1)) out.top = gap
        if (isCategory(parent, pos + 1)) out.bottom = gap
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(v)
            if (pos == RecyclerView.NO_POSITION || isCategory(parent, pos)) continue
            val first = isCategory(parent, pos - 1)
            val last = isCategory(parent, pos + 1)
            val ty = v.translationY
            rect.set(v.left.toFloat(), v.top + ty, v.right.toFloat(), v.bottom + ty)
            val t = if (first) radius else 0f
            val b = if (last) radius else 0f
            path.reset()
            path.addRoundRect(rect, floatArrayOf(t, t, t, t, b, b, b, b), Path.Direction.CW)
            fill.alpha = (v.alpha * 255).toInt()
            c.drawPath(path, fill)
            if (!first) c.drawLine(rect.left + side, rect.top, rect.right - side, rect.top, line)
        }
    }
}

private fun unreserveCategories(group: androidx.preference.PreferenceGroup) {
    for (i in 0 until group.preferenceCount) {
        val p = group.getPreference(i)
        if (p is PreferenceCategory) p.isIconSpaceReserved = false
        if (p is androidx.preference.PreferenceGroup) unreserveCategories(p)
    }
}

fun PreferenceFragmentCompat.applyKombainCards() {
    setDivider(null)
    preferenceScreen?.let { unreserveCategories(it) }
    listView.addItemDecoration(PrefCardDecoration(requireContext()))
    listView.setPadding(0, 0, 0, listView.paddingBottom + dp2px(16))
    listView.clipToPadding = false
}
