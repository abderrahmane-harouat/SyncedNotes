package com.syncednotes.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

class EditorActivity : Activity() {
    private lateinit var canvas: NoteCanvasView
    private lateinit var panel: TextOptionsPanel
    private lateinit var toolbar: View
    private lateinit var undo: View
    private lateinit var redo: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.editor)
        val root = findViewById<ViewGroup>(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        undo = findViewById(R.id.undo)
        redo = findViewById(R.id.redo)

        canvas = NoteCanvasView(this, AppFonts.get(this))
        findViewById<ViewGroup>(R.id.canvas_holder).addView(canvas, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel = TextOptionsPanel(this, canvas.composer)

        findViewById<View>(R.id.text_options).setOnClickListener { togglePanel() }
        undo.setOnClickListener { canvas.composer.undo(SystemClock.uptimeMillis()) }
        redo.setOnClickListener { canvas.composer.redo(SystemClock.uptimeMillis()) }
        canvas.onFormatChanged = ::refreshToolbar
        canvas.onTextOptionsShortcut = ::togglePanel
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> panel.reposition() }
        refreshToolbar()

        root.setOnApplyWindowInsetsListener { v, insets ->
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
        window.statusBarColor = canvas.composer.style.background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        canvas.requestFocus()
    }

    private fun togglePanel() {
        if (panel.isShowing) panel.dismiss() else panel.show(toolbar)
    }

    private fun refreshToolbar() {
        val s = canvas.composer.formatState()
        setEnabled(undo, s.canUndo)
        setEnabled(redo, s.canRedo)
        if (panel.isShowing) panel.refresh()
    }

    private fun setEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        // Samsung dims an unavailable undo or redo to 40%.
        button.alpha = if (enabled) 1f else 0.4f
    }

    override fun onDestroy() {
        panel.dismiss()
        super.onDestroy()
    }
}
