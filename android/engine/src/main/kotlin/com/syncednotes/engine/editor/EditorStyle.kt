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
    /** How far a quote and a code block sit in from the margin, and the code block's right inset. */
    val quoteIndent: Float = 0f,
    val codeIndent: Float = 0f,
    val quoteBarWidth: Float = 0f,
    val quoteBarColor: Int = 0x33000000,
    val codeBackground: Int = 0x0F000000,
    val codeCornerRadius: Float = 0f,
    val dividerColor: Int = 0x33000000,
    val dividerThickness: Float = 1f,
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
                quoteIndent = 20f * density,
                codeIndent = 12f * density,
                quoteBarWidth = 3f * density,
                codeCornerRadius = 6f * density,
                dividerThickness = 1f * density,
            )
        }

        /**
         * The Mac app's note, in its SwiftUI predecessor's measures: 16 pt body text, a 20 pt
         * margin, lists 24 pt a level, round checkboxes as in Apple Notes. [scale] is pixels
         * per point (2 on a Retina screen); [dark] for the Mac's dark mode.
         */
        fun macLike(scale: Float, dark: Boolean = false): EditorStyle = EditorStyle(
            // Sizes stay in Samsung's units so the toolbar's 17 means the same on both devices;
            // on the Mac 17 units are the old app's 16 pt.
            sizeUnit = 16f * scale / 17f,
            defaultSize = 17,
            lineSpacing = 1.3f,
            paddingLeft = 20f * scale,
            paddingRight = 20f * scale,
            paddingTop = 20f * scale,
            paddingBottom = 40f * scale,
            background = if (dark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt(),
            textColor = if (dark) 0xFFF5F5F7.toInt() else 0xFF1D1D1F.toInt(),
            // The Mac's own blue and its selection colour, in each mode.
            caretColor = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt(),
            caretWidth = 1.5f * scale,
            selectionColor = if (dark) 0xFF3F638B.toInt() else 0xFFB3D7FF.toInt(),
            handleColor = 0xFF007AFF.toInt(),
            handleRadius = 0f,
            handleTouchRadius = 0f,
            composingUnderline = 1f * scale,
            touchSlop = 4f * scale,
            // The Swift app's measures: 28 pt an indent step, 24 pt a list level.
            indentWidth = 28f * scale,
            listMarkerWidth = 24f * scale,
            listMarkerGap = 6f * scale,
            bulletRadius = 2.75f * scale,
            checkboxSize = 16f * scale,
            // Half the side makes the box a circle.
            checkboxRadius = 8f * scale,
            checkboxStroke = 1.25f * scale,
            checkboxColor = if (dark) 0xFF98989D.toInt() else 0xFF8E8E93.toInt(),
            checkedTextAlpha = 0.5f,
            handles = false,
            // A quote 20 pt in with a bar beside it, a code block 12 pt in on a panel.
            quoteIndent = 20f * scale,
            codeIndent = 12f * scale,
            quoteBarWidth = 3f * scale,
            quoteBarColor = if (dark) 0x40FFFFFF else 0x40000000,
            codeBackground = if (dark) 0x14FFFFFF else 0x0D000000,
            codeCornerRadius = 6f * scale,
            dividerColor = if (dark) 0x33FFFFFF else 0x26000000,
            dividerThickness = 1f * scale,
        )
    }
}
