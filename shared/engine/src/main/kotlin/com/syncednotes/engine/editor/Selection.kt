package com.syncednotes.engine.editor

/**
 * The caret or the selected range. [anchor] stays put while [focus] moves, so extending a
 * selection with Shift+arrow or a handle grows it from the right end.
 */
data class Selection(val anchor: Int, val focus: Int = anchor, val upstream: Boolean = false) {
    val start: Int get() = minOf(anchor, focus)
    val end: Int get() = maxOf(anchor, focus)
    val isCollapsed: Boolean get() = anchor == focus
}
