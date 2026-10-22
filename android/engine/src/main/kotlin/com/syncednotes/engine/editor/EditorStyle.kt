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
    /** Selection handles are for fingers; a mouse selects without them. */
    val handles: Boolean = true,
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

        /**
         * The Mac app's note, in its SwiftUI predecessor's measures: 16 pt body text, a 20 pt
         * margin, lists 24 pt a level, round checkboxes as in Apple Notes. [scale] is pixels
         * per point (2 on a Retina screen).
         */
        fun macLike(scale: Float): EditorStyle = EditorStyle(
            // Sizes stay in Samsung's units so the toolbar's 17 means the same on both devices;
            // on the Mac 17 units are the old app's 16 pt.
            sizeUnit = 16f * scale / 17f,
            defaultSize = 17,
            lineSpacing = 1.3f,
            paddingLeft = 20f * scale,
            paddingRight = 20f * scale,
            paddingTop = 20f * scale,
            paddingBottom = 40f * scale,
            background = 0xFFFFFFFF.toInt(),
            textColor = 0xFF1D1D1F.toInt(),
            // The Mac's own blue and its selection colour in light mode.
            caretColor = 0xFF007AFF.toInt(),
            caretWidth = 1.5f * scale,
            selectionColor = 0xFFB3D7FF.toInt(),
            handleColor = 0xFF007AFF.toInt(),
            handleRadius = 0f,
            handleTouchRadius = 0f,
            composingUnderline = 1f * scale,
            touchSlop = 4f * scale,
            indentWidth = 24f * scale,
            listMarkerWidth = 24f * scale,
            listMarkerGap = 6f * scale,
            bulletRadius = 2.75f * scale,
            checkboxSize = 16f * scale,
            // Half the side makes the box a circle.
            checkboxRadius = 8f * scale,
            checkboxStroke = 1.25f * scale,
            checkboxColor = 0xFF8E8E93.toInt(),
            checkedTextAlpha = 0.5f,
            handles = false,
        )
    }
}
