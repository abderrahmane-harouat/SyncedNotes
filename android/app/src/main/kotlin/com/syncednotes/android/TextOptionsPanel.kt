package com.syncednotes.android

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType

/** Samsung's Text options card; its buttons follow the caret and the formatting under it. */
class TextOptionsPanel(context: Context, private val composer: Composer) :
    CardPopup(LayoutInflater.from(context).inflate(R.layout.text_options_panel, null)) {

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

    override fun refresh() {
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
