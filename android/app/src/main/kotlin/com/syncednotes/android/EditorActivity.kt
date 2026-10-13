package com.syncednotes.android

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.TextView
import com.syncednotes.engine.text.ListType

class EditorActivity : Activity() {
    private lateinit var canvas: NoteCanvasView
    private lateinit var toolbar: View
    private lateinit var textOptions: TextOptionsPanel
    private lateinit var fontColor: ColorPopup
    private lateinit var highlight: ColorPopup
    private lateinit var sizeList: FontSizeList
    private val cards: List<CardPopup> get() = listOf(textOptions, fontColor, highlight)

    private fun now() = SystemClock.uptimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.editor)
        toolbar = findViewById(R.id.toolbar)
        canvas = NoteCanvasView(this, AppFonts.get(this))
        findViewById<ViewGroup>(R.id.canvas_holder).addView(canvas, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val composer = canvas.composer
        val pageColor = composer.style.background

        textOptions = TextOptionsPanel(this, composer)
        fontColor = ColorPopup(this, R.string.font_color, Palettes.text, pageColor,
            current = { composer.formatState().color ?: composer.style.textColor },
            onPick = { composer.setColor(it.takeIf { c -> c != composer.style.textColor }, now()) })
        highlight = ColorPopup(this, R.string.font_background, Palettes.highlight, pageColor,
            current = { composer.formatState().highlight ?: 0 },
            onPick = { composer.setHighlight(it.takeIf { c -> c != 0 }, now()) })
        sizeList = FontSizeList(this) { composer.setSize(it, now()) }

        findViewById<View>(R.id.checklist).setOnClickListener {
            closeCards()
            composer.toggleList(ListType.CHECKBOX, now())
            canvas.showKeyboard()
        }
        findViewById<View>(R.id.text_options).setOnClickListener { toggleCard(textOptions) }
        findViewById<View>(R.id.font_color).setOnClickListener { toggleCard(fontColor) }
        findViewById<View>(R.id.font_highlight).setOnClickListener { toggleCard(highlight) }
        findViewById<View>(R.id.font_size).setOnClickListener {
            closeCards()
            sizeList.show(it, composer.formatState().size)
        }
        findViewById<View>(R.id.undo).setOnClickListener { composer.undo(now()) }
        findViewById<View>(R.id.redo).setOnClickListener { composer.redo(now()) }
        canvas.onFormatChanged = ::refreshToolbar
        canvas.onTextOptionsShortcut = { toggleCard(textOptions) }
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> cards.forEach { it.reposition() } }
        refreshToolbar()

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            // Android 15 draws apps edge to edge: keep the text below the status bar and the
            // toolbar above the navigation bar and the keyboard.
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, bottom)
            canvas.setInsets(top, 0)
            insets
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = pageColor
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        canvas.requestFocus()
    }

    /** Opening a card closes any other, as Samsung's toolbar does. */
    private fun toggleCard(card: CardPopup) {
        val wasShowing = card.isShowing
        closeCards()
        if (!wasShowing) card.show(toolbar)
    }

    private fun closeCards() {
        cards.forEach { it.dismiss() }
        sizeList.dismiss()
    }

    private fun refreshToolbar() {
        val composer = canvas.composer
        val s = composer.formatState()
        findViewById<View>(R.id.checklist).isSelected = s.list == ListType.CHECKBOX
        findViewById<ImageView>(R.id.font_color_bar).imageTintList = ColorStateList.valueOf(s.color ?: composer.style.textColor)
        // No highlight shows an empty bar: the page colour inside its outline.
        findViewById<ImageView>(R.id.font_highlight_bar).imageTintList = ColorStateList.valueOf(s.highlight ?: composer.style.background)
        // A selection of mixed sizes leaves the label blank, as in Samsung Notes.
        findViewById<TextView>(R.id.font_size_label).text = s.size?.toString() ?: ""
        setEnabled(findViewById(R.id.undo), s.canUndo)
        setEnabled(findViewById(R.id.redo), s.canRedo)
        cards.filter { it.isShowing }.forEach { it.refresh() }
    }

    private fun setEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        // Samsung dims an unavailable undo or redo to 40%.
        button.alpha = if (enabled) 1f else 0.4f
    }

    override fun onDestroy() {
        closeCards()
        super.onDestroy()
    }
}
