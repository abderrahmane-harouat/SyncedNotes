package com.syncednotes.android

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView

/**
 * Samsung's "Font color" and "Font background" cards. A tap applies the colour at once and the
 * card stays open, so colours can be tried one after another; each one is its own undo step.
 */
class ColorPopup(
    context: Context,
    title: Int,
    pages: List<IntArray>,
    pageColor: Int,
    /** The colour in use now: the actual colour, or what "default" stands for. */
    private val current: () -> Int,
    /** 0 from the "none" swatch. */
    onPick: (Int) -> Unit,
) : CardPopup(LayoutInflater.from(context).inflate(R.layout.color_popup, null)) {
    private val palette: PaletteStripView = content.findViewById(R.id.palette)

    init {
        content.findViewById<TextView>(R.id.title).setText(title)
        content.findViewById<android.view.View>(R.id.close).setOnClickListener { dismiss() }
        palette.pages = pages
        palette.noneColor = pageColor
        palette.onPick = onPick
    }

    override fun refresh() {
        val wasShowing = isShowing
        palette.selected = current()
        if (!wasShowing) palette.showSelectedPage()
    }
}
