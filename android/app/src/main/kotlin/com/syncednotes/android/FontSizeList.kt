package com.syncednotes.android

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListPopupWindow
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Samsung's font size drop-down: the phone's list of sizes, the current one in orange with a
 * check, scrolled into view when it opens. Picking one applies it and closes the list.
 */
class FontSizeList(private val context: Context, private val onPick: (Int) -> Unit) {
    private val sizes = (6..20).toList() + listOf(22, 24, 26, 28, 30, 32, 36, 40, 44, 48, 52, 56, 60, 64)
    private var current: Int? = null
    private val dp = context.resources.displayMetrics.density
    private val selectedColor = context.getColor(R.color.size_selected)
    private val textColor = context.getColor(R.color.list_text)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = sizes.size
        override fun getItem(position: Int) = sizes[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.font_size_row, parent, false)
            val selected = sizes[position] == current
            row.findViewById<TextView>(R.id.size).apply {
                text = sizes[position].toString()
                setTextColor(if (selected) selectedColor else textColor)
            }
            // Invisible rather than gone, so every row keeps the same width.
            row.findViewById<ImageView>(R.id.check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            return row
        }
    }

    private val list = ListPopupWindow(context).apply {
        setAdapter(adapter)
        isModal = true
        height = (220 * dp).toInt()
        width = (120 * dp).toInt()
        horizontalOffset = (-8 * dp).toInt()
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        setBackgroundDrawable(context.getDrawable(R.drawable.panel_background))
        setOnItemClickListener { _, _, position, _ ->
            onPick(sizes[position])
            dismiss()
        }
    }

    val isShowing: Boolean get() = list.isShowing

    /** [size] is the size in use, or null for a selection of mixed sizes (nothing is checked). */
    fun show(anchor: View, size: Int?) {
        current = size
        adapter.notifyDataSetChanged()
        list.anchorView = anchor
        list.show()
        sizes.indexOf(size).takeIf { it >= 0 }?.let { list.setSelection(it) }
    }

    fun dismiss() = list.dismiss()
}
