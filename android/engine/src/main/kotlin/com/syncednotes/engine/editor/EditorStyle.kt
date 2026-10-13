package com.syncednotes.engine.editor

/** Sizes in pixels and colours as 0xAARRGGBB. Defaults follow Samsung Notes' text editor. */
data class EditorStyle(
    val textSize: Float,
    val lineHeight: Float,
    val paddingLeft: Float,
    val paddingRight: Float,
    val paddingTop: Float,
    val paddingBottom: Float,
    val background: Int,
    val textColor: Int,
    val caretColor: Int,
    val caretWidth: Float,
    val selectionColor: Int,
    val handleColor: Int,
    val handleRadius: Float,
    val handleTouchRadius: Float,
    val composingUnderline: Float,
    val touchSlop: Float,
    val indentWidth: Float,
    val listMarkerWidth: Float,
    val listMarkerGap: Float,
    val bulletRadius: Float,
    val longPressMs: Long = 400,
    val doubleTapMs: Long = 300,
    val blinkMs: Long = 500,
    val insertionHandleMs: Long = 4000,
) {
    companion object {
        /** [density] is pixels per dp; [fontScale] the user's text size setting. */
        fun samsungLike(density: Float, fontScale: Float = 1f): EditorStyle {
            // Samsung's default size "17" draws at about 19.4sp, with lines 26.2dp apart
            // (measured against Samsung Notes on the same emulator: the same sentence wraps at
            // the same words, lines are 68.7px apart at 420dpi).
            val textSize = 19.4f * density * fontScale
            return EditorStyle(
                textSize = textSize,
                lineHeight = 26.2f * density * fontScale,
                paddingLeft = 18f * density,
                paddingRight = 18f * density,
                paddingTop = 16f * density,
                paddingBottom = 120f * density,
                background = 0xFFFCFCFC.toInt(),
                textColor = 0xFF1E1E1E.toInt(),
                caretColor = 0xFF3E91F7.toInt(),
                caretWidth = 2f * density,
                selectionColor = 0xFFC6E3F7.toInt(),
                handleColor = 0xFF3E91F7.toInt(),
                handleRadius = 8f * density,
                handleTouchRadius = 24f * density,
                composingUnderline = 1f * density,
                touchSlop = 8f * density,
                indentWidth = 24f * density,
                listMarkerWidth = 28f * density,
                listMarkerGap = 6f * density,
                bulletRadius = 2.6f * density * fontScale,
            )
        }
    }
}
