package com.syncednotes.engine.editor

/** Sizes in pixels and colours as 0xAARRGGBB. Defaults follow Samsung Notes' text editor. */
data class EditorStyle(
    /** Samsung's text size unit in pixels: 1/360 of the page width. */
    val sizeUnit: Float,
    /** The size of unformatted text, in [sizeUnit]s. */
    val defaultSize: Int,
    /** Line height as a multiple of the text size. */
    val lineSpacing: Float,
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
    val checkboxSize: Float,
    val checkboxRadius: Float,
    val checkboxStroke: Float,
    val checkboxColor: Int,
    /** How opaque the text of a ticked checklist item stays. */
    val checkedTextAlpha: Float,
    val longPressMs: Long = 400,
    val doubleTapMs: Long = 300,
    val blinkMs: Long = 500,
    val insertionHandleMs: Long = 4000,
) {
    /** The default text size in pixels. */
    val textSize: Float get() = defaultSize * sizeUnit

    /** The line height of default-sized text. */
    val lineHeight: Float get() = textSize * lineSpacing

    companion object {
        /**
         * [density] is pixels per dp; [shortSidePx] the screen's shorter side. Samsung measures
         * text in 1/360 of the page width, which on a phone is the screen width in portrait;
         * using the shorter side keeps text the same size when the phone turns.
         */
        fun samsungLike(density: Float, shortSidePx: Int): EditorStyle {
            val unit = shortSidePx / 360f
            return EditorStyle(
                sizeUnit = unit,
                defaultSize = 17,
                // Measured against Samsung Notes on the same emulator: at its default size the
                // same sentence wraps at the same words, with baselines 1.35 text sizes apart.
                lineSpacing = 1.35f,
                paddingLeft = 18f * density,
                paddingRight = 18f * density,
                paddingTop = 16f * density,
                paddingBottom = 120f * density,
                background = 0xFFFCFCFC.toInt(),
                // Samsung's default text colour (composer_light_font_color).
                textColor = 0xFF252525.toInt(),
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
                // Samsung's list marks at size 17: a 6dp dot, and a 15dp outlined square with
                // 2.5dp corners and a 1.5dp stroke, ticked inside when checked. They scale with
                // the text they mark.
                bulletRadius = 3f * density,
                checkboxSize = 15f * density,
                checkboxRadius = 2.5f * density,
                checkboxStroke = 1.5f * density,
                checkboxColor = 0xFF252525.toInt(),
                // Ticked items are struck through and faded to 40% (102/255).
                checkedTextAlpha = 0.4f,
            )
        }
    }
}
