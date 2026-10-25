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
import com.syncednotes.engine.text.CalloutColor
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
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
    private val quote = button(R.id.quote)
    private val codeBlock = button(R.id.code_block)
    private val indent = button(R.id.indent)
    private val outdent = button(R.id.outdent)
    private val divider = button(R.id.divider)
    private val spacing = content.findViewById<View>(R.id.line_spacing)
    /** The callout colours, as round swatches in the panel shades they give. */
    private val callouts: List<Pair<View, CalloutColor>> = content.findViewById<android.widget.LinearLayout>(R.id.callouts).let { row ->
        val density = context.resources.displayMetrics.density
        CalloutColor.entries.mapIndexed { i, color ->
            if (i > 0) row.addView(android.widget.Space(context), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
            val swatch = View(context).apply {
                contentDescription = "${context.getString(R.string.callout)}, ${color.title}"
                setOnClickListener { composer.toggleCallout(color, now()) }
            }
            row.addView(swatch, android.widget.LinearLayout.LayoutParams((26 * density).toInt(), (26 * density).toInt()))
            swatch to color
        }
    }
    private val spacingLabel = content.findViewById<TextView>(R.id.line_spacing_label)

    private fun now() = SystemClock.uptimeMillis()

    init {
        button(R.id.close).setOnClickListener { dismiss() }
        for ((view, role) in roles) view.setOnClickListener { composer.toggleRole(role, now()) }
        for ((view, type) in lists) view.setOnClickListener { composer.toggleList(type, now()) }
        for ((view, alignment) in alignments) view.setOnClickListener { composer.setAlignment(alignment, now()) }
        for ((view, style) in styles) view.setOnClickListener { composer.toggle(style, now()) }
        quote.setOnClickListener { composer.toggleRole(Role.QUOTE, now()) }
        // The Mac's Code Block menu: plain code, or code in a language its syntax is coloured
        // by. Choosing the one the block is in already puts it back to body text.
        codeBlock.setOnClickListener {
            PopupMenu(context, codeBlock, Gravity.END).apply {
                for (language in CodeLanguage.entries) {
                    menu.add(language.title).apply {
                        isCheckable = true
                        isChecked = composer.formatState().codeLanguage == language
                        setOnMenuItemClickListener { composer.toggleCodeBlock(language, now()); refresh(); true }
                    }
                }
                show()
            }
        }
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
        quote.isSelected = s.role == Role.QUOTE
        codeBlock.isSelected = s.role == Role.CODE_BLOCK
        setEnabled(indent, s.canIndent)
        setEnabled(outdent, s.canOutdent)
        spacingLabel.text = SPACINGS.firstOrNull { it.second == s.lineSpacing }?.first ?: ""
        val density = content.resources.displayMetrics.density
        for ((view, color) in callouts) {
            // The panel's own shade, edged so a pale one shows on the card; ringed in blue when on.
            val on = s.calloutColor == color
            view.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(composer.style.calloutColors[color.ordinal])
                setStroke(((if (on) 2.5f else 1f) * density).toInt(), if (on) 0xFF3E91F7.toInt() else 0x33000000)
            }
            view.isSelected = on
        }
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
