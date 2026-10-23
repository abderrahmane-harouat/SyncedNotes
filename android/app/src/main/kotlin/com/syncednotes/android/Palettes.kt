package com.syncednotes.android

import com.syncednotes.core.NoteFormat

/**
 * The colours the toolbar offers: the Mac's own, so whatever is picked on one device can be
 * picked, and is named, on the other. Samsung's 32 swatches were wider than the note format's
 * named colours, so a colour chosen here came to the Mac as one its menus could not offer.
 */
object Palettes {
    /** "Default" first (0: the note's own text colour), then the Mac's seven text colours. */
    val text: List<IntArray> = listOf(intArrayOf(0) + NoteFormat.TEXT_COLORS.map { NoteFormat.argbOf(it.second) })

    /** "None" first (0), then the six highlights a note can have. */
    val highlight: List<IntArray> = listOf(intArrayOf(0) + NoteFormat.HIGHLIGHTS.values)
}
