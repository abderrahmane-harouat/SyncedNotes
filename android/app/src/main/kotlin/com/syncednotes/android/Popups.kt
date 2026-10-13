package com.syncednotes.android

import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow

/**
 * A card popup the way Samsung opens its toolbar cards: it never takes the keyboard's place
 * (the window does not need input), closes on Back or any touch outside it, and sits centred
 * on the screen just above the toolbar.
 */
open class CardPopup(protected val content: View) {
    private val window = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        isOutsideTouchable = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        isClippingEnabled = false
        setBackgroundDrawable(ColorDrawable(0))
    }
    private var anchor: View? = null

    val isShowing: Boolean get() = window.isShowing

    fun show(toolbar: View) {
        anchor = toolbar
        refresh()
        val (x, y) = position(toolbar)
        window.showAtLocation(toolbar, Gravity.NO_GRAVITY, x, y)
    }

    /** Follows the toolbar when the keyboard opens or closes under it. */
    fun reposition() {
        val toolbar = anchor ?: return
        if (!window.isShowing) return
        val (x, y) = position(toolbar)
        window.update(x, y, -1, -1)
    }

    private fun position(toolbar: View): Pair<Int, Int> {
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val where = IntArray(2)
        toolbar.getLocationInWindow(where)
        val x = (toolbar.rootView.width - content.measuredWidth) / 2
        return x to (where[1] - content.measuredHeight)
    }

    fun dismiss() {
        window.dismiss()
    }

    /** Redraws the card from the editor's current state. */
    open fun refresh() {}
}
