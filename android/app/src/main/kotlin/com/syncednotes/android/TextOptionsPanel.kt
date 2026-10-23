package com.syncednotes.android

import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.Role

/**
 * Samsung's Text options card, holding every tool the Mac's Format menu has, so a note is
 * written with the same means on both; its buttons follow the caret and the formatting under it.
 */
class TextOptionsPanel(context: Context, private val composer: Composer) :
    CardPopup(LayoutInflater.from(context).inflate(R.layout.text_options_panel, null)) {

    private fun button(id: Int): ImageButton = content.findViewById(id)
    private val roles = listOf(
        content.findViewById<TextView>(R.id.role_title) to Role.TITLE,
        content.findViewById<TextView>(R.id.role_heading) to Role.HEADING,
        content.findViewById<TextView>(R.id.role_subheading) to Role.SUBHEADING,
        content.findViewById<TextView>(R.id.role_body) to Role.BODY,
    )
    private val lists = listOf(button(R.id.bullets) to ListType.BULLET, button(R.id.dashes) to ListType.DASH, button(R.id.numbering) to ListType.NUMBER)
    private val alignments = listOf(
        button(R.id.align_left) to Alignment.LEFT, button(R.id.align_center) to Alignment.CENTER,
        button(R.id.align_justify) to Alignment.JUSTIFIED, button(R.id.align_right) to Alignment.RIGHT,
    )
    private val styles = listOf(
        button(R.id.bold) to CharStyle.BOLD, button(R.id.italic) to CharStyle.ITALIC,
        button(R.id.underline) to CharStyle.UNDERLINE, button(R.id.strikethrough) to CharStyle.STRIKETHROUGH,
        button(R.id.inline_code) to CharStyle.CODE,
    )
    private val blocks = listOf(button(R.id.quote) to Role.QUOTE, button(R.id.code_block) to Role.CODE_BLOCK)
    private val indent = button(R.id.indent)
    private val outdent = button(R.id.outdent)
    private val divider = button(R.id.divider)
    private val spacing = content.findViewById<View>(R.id.line_spacing)
    private val spacingLabel = content.findViewById<TextView>(R.id.line_spacing_label)

    private fun now() = SystemClock.uptimeMillis()

    init {
        button(R.id.close).setOnClickListener { dismiss() }
        for ((view, role) in roles) view.setOnClickListener { composer.toggleRole(role, now()) }
        for ((view, type) in lists) view.setOnClickListener { composer.toggleList(type, now()) }
        for ((view, alignment) in alignments) view.setOnClickListener { composer.setAlignment(alignment, now()) }
        for ((view, style) in styles) view.setOnClickListener { composer.toggle(style, now()) }
        for ((view, role) in blocks) view.setOnClickListener { composer.toggleRole(role, now()) }
        indent.setOnClickListener { composer.indent(now()) }
        outdent.setOnClickListener { composer.outdent(now()) }
        divider.setOnClickListener { composer.insertDivider(now()) }
        // The Mac's Line Spacing menu: the same four choices.
        spacing.setOnClickListener {
            PopupMenu(context, spacing, Gravity.END).apply {
                for ((title, multiple) in SPACINGS) {
                    menu.add(title).apply {
                        isCheckable = true
                        isChecked = composer.formatState().lineSpacing == multiple
                        setOnMenuItemClickListener { composer.setLineSpacing(multiple, now()); refresh(); true }
                    }
                }
                show()
            }
        }
    }

    override fun refresh() {
        val s = composer.formatState()
        for ((view, role) in roles) view.isSelected = s.role == role
        for ((view, type) in lists) view.isSelected = s.list == type
        for ((view, alignment) in alignments) view.isSelected = s.alignment == alignment
        for ((view, style) in styles) view.isSelected = s.has(style)
        for ((view, role) in blocks) view.isSelected = s.role == role
        setEnabled(indent, s.canIndent)
        setEnabled(outdent, s.canOutdent)
        spacingLabel.text = SPACINGS.firstOrNull { it.second == s.lineSpacing }?.first ?: ""
    }

    private fun setEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        // Samsung dims a disabled indent button to 30%.
        button.alpha = if (enabled) 1f else 0.3f
    }

    companion object {
        private val SPACINGS = listOf("1.0" to 1f, "1.15" to 1.15f, "1.5" to 1.5f, "2.0" to 2f)
    }
}
