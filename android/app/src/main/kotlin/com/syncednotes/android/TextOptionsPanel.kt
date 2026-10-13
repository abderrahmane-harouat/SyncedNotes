package com.syncednotes.android

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupWindow
import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType

/**
 * Samsung's Text options card: a popup above the toolbar that leaves the keyboard where it is
 * (the window never takes input), closes on the X, Back or any touch outside it, and redraws
 * its buttons whenever the caret or the formatting changes.
 */
class TextOptionsPanel(context: Context, private val composer: Composer) {
    private val content: View = LayoutInflater.from(context).inflate(R.layout.text_options_panel, null)
    private val window = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        isOutsideTouchable = true
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        isClippingEnabled = false
        setBackgroundDrawable(ColorDrawable(0))
    }
    private var anchor: View? = null

    private fun button(id: Int): ImageButton = content.findViewById(id)
    private val bullets = button(R.id.bullets)
    private val numbering = button(R.id.numbering)
    private val alignLeft = button(R.id.align_left)
    private val alignCenter = button(R.id.align_center)
    private val alignRight = button(R.id.align_right)
    private val bold = button(R.id.bold)
    private val italic = button(R.id.italic)
    private val underline = button(R.id.underline)
    private val strikethrough = button(R.id.strikethrough)
    private val indent = button(R.id.indent)
    private val outdent = button(R.id.outdent)

    val isShowing: Boolean get() = window.isShowing

    init {
        fun now() = SystemClock.uptimeMillis()
        button(R.id.close).setOnClickListener { dismiss() }
        bullets.setOnClickListener { composer.toggleList(ListType.BULLET, now()) }
        numbering.setOnClickListener { composer.toggleList(ListType.NUMBER, now()) }
        alignLeft.setOnClickListener { composer.setAlignment(Alignment.LEFT, now()) }
        alignCenter.setOnClickListener { composer.setAlignment(Alignment.CENTER, now()) }
        alignRight.setOnClickListener { composer.setAlignment(Alignment.RIGHT, now()) }
        bold.setOnClickListener { composer.toggle(CharStyle.BOLD, now()) }
        italic.setOnClickListener { composer.toggle(CharStyle.ITALIC, now()) }
        underline.setOnClickListener { composer.toggle(CharStyle.UNDERLINE, now()) }
        strikethrough.setOnClickListener { composer.toggle(CharStyle.STRIKETHROUGH, now()) }
        indent.setOnClickListener { composer.indent(now()) }
        outdent.setOnClickListener { composer.outdent(now()) }
    }

    /** Opens centred on the screen, just above [toolbar]. */
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

    fun refresh() {
        val s = composer.formatState()
        bullets.isSelected = s.list == ListType.BULLET
        numbering.isSelected = s.list == ListType.NUMBER
        alignLeft.isSelected = s.alignment == Alignment.LEFT
        alignCenter.isSelected = s.alignment == Alignment.CENTER
        alignRight.isSelected = s.alignment == Alignment.RIGHT
        bold.isSelected = s.bold
        italic.isSelected = s.italic
        underline.isSelected = s.underline
        strikethrough.isSelected = s.strikethrough
        setEnabled(indent, s.canIndent)
        setEnabled(outdent, s.canOutdent)
    }

    private fun setEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        // Samsung dims a disabled indent button to 30%.
        button.alpha = if (enabled) 1f else 0.3f
    }
}
