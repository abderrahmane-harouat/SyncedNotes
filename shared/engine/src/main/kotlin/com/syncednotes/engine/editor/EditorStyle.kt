package com.syncednotes.engine.editor

import com.syncednotes.engine.code.TokenKind
import com.syncednotes.engine.text.CalloutColor

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
    /**
     * Behind every match of Find in Note, and behind the one Find is on: a marker's yellow and
     * orange, solid, edged with [findEdgeColor]. Every hue is also a highlight a note can have;
     * the edge, which no highlight has, is what tells a match from one.
     */
    val findColor: Int = 0xFFFFE45C.toInt(),
    val findCurrentColor: Int = 0xFFFF9F0A.toInt(),
    val findEdgeColor: Int = 0x66000000,
    val findEdge: Float = 1f,
    /** Pixels per point, for a picture's own size: a picture is shown at its size unless wider than the note. */
    val pictureScale: Float = 1f,
    /**
     * A code block's padding above its first line, which holds the language's name and the
     * button that changes it, and below its last; the name's size and colour.
     */
    val codePaddingTop: Float = 0f,
    val codePaddingBottom: Float = 0f,
    val codeLabelSize: Float = 0f,
    val codeLabelColor: Int = 0x99000000.toInt(),
    /** Room outside a code block's panel, between it and the text above and below. */
    val codeMargin: Float = 0f,
    /**
     * A callout: its text's room from its panel's sides, above and below, and the room outside
     * the panel; its corners; the panel's colour for each [CalloutColor], in their order.
     */
    val calloutPadding: Float = 0f,
    val calloutPaddingVertical: Float = 0f,
    val calloutMargin: Float = 0f,
    val calloutCornerRadius: Float = 0f,
    val calloutColors: List<Int> = calloutColors(dark = false),
    /**
     * Behind inline code: a chip, see-through so it reads the same on the page and on any
     * callout's panel; 0 for none. Its corners.
     */
    val inlineCodeBackground: Int = 0,
    val inlineCodeRadius: Float = 0f,
    /** The colour of each [TokenKind] in a code block with a language, in the kinds' order. */
    val codeColors: List<Int> = codeColors(dark = false),
) {
    /** The default text size in pixels. */
    val textSize: Float get() = defaultSize * sizeUnit

    /** The line height of default-sized text. */
    val lineHeight: Float get() = textSize * lineSpacing

    companion object {
        /**
         * The callout panels' colours: soft tints of each hue on a light page, deep ones on a dark
         * page, each light or dark enough that the text's own colour reads on it unchanged.
         */
        fun calloutColors(dark: Boolean): List<Int> {
            val byColor = if (dark) mapOf(
                CalloutColor.BLUE to 0xFF1E2A3E,
                CalloutColor.GREEN to 0xFF1C3124,
                CalloutColor.ORANGE to 0xFF3A2B1B,
                CalloutColor.PURPLE to 0xFF2C2440,
                CalloutColor.PINK to 0xFF3A2130,
                CalloutColor.GRAY to 0xFF2C2C2E,
            ) else mapOf(
                CalloutColor.BLUE to 0xFFE3EDFD,
                CalloutColor.GREEN to 0xFFE3F4E7,
                CalloutColor.ORANGE to 0xFFFBEBDA,
                CalloutColor.PURPLE to 0xFFECE5F8,
                CalloutColor.PINK to 0xFFFBE5EE,
                CalloutColor.GRAY to 0xFFF0F0F2,
            )
            return CalloutColor.entries.map { byColor.getValue(it).toInt() }
        }

        /**
         * Code's colours on a light page and a dark one: those of the default themes of the
         * editors people write C in, so code in a note reads as code they know. Each is
         * readable on its page, and on a code block's panel, which is only a shade off it.
         */
        fun codeColors(dark: Boolean): List<Int> {
            val byKind = if (dark) mapOf(
                TokenKind.KEYWORD to 0xFFFC5FA3,
                TokenKind.TYPE to 0xFF5DD8FF,
                TokenKind.LITERAL to 0xFFD0BF69,
                TokenKind.STRING to 0xFFFC6A5D,
                TokenKind.COMMENT to 0xFF7F8C98,
                TokenKind.PREPROCESSOR to 0xFFFD8F3F,
                TokenKind.FUNCTION to 0xFF67B7A4,
            ) else mapOf(
                TokenKind.KEYWORD to 0xFF9B2393,
                TokenKind.TYPE to 0xFF0B4F79,
                TokenKind.LITERAL to 0xFF1C00CF,
                TokenKind.STRING to 0xFFC41A16,
                TokenKind.COMMENT to 0xFF5D6C79,
                TokenKind.PREPROCESSOR to 0xFF643820,
                TokenKind.FUNCTION to 0xFF326D74,
            )
            return TokenKind.entries.map { byKind.getValue(it).toInt() }
        }

        /**
         * [density] is pixels per dp; [shortSidePx] the screen's shorter side. Samsung measures
         * text in 1/360 of the page width, which on a phone is the screen width in portrait;
         * using the shorter side keeps text the same size when the phone turns.
         */
        fun samsungLike(density: Float, shortSidePx: Int, dark: Boolean = false): EditorStyle {
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
                // Dark is the Mac's dark page, so the two apps look alike at night too.
                background = if (dark) 0xFF1E1E1E.toInt() else 0xFFFCFCFC.toInt(),
                // Samsung's default text colour (composer_light_font_color).
                textColor = if (dark) 0xFFF5F5F7.toInt() else 0xFF252525.toInt(),
                caretColor = 0xFF3E91F7.toInt(),
                caretWidth = 2f * density,
                selectionColor = if (dark) 0xFF3F638B.toInt() else 0xFFC6E3F7.toInt(),
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
                checkboxColor = if (dark) 0xFFF5F5F7.toInt() else 0xFF252525.toInt(),
                // Ticked items are struck through and faded to 40% (102/255).
                checkedTextAlpha = 0.4f,
                quoteIndent = 20f * density,
                codeIndent = 14f * density,
                codePaddingTop = 28f * density,
                codePaddingBottom = 10f * density,
                codeLabelSize = 12f * density,
                codeLabelColor = if (dark) 0x99FFFFFF.toInt() else 0x8A000000.toInt(),
                codeMargin = 6f * density,
                calloutPadding = 14f * density,
                calloutPaddingVertical = 12f * density,
                calloutMargin = 6f * density,
                calloutCornerRadius = 12f * density,
                calloutColors = calloutColors(dark),
                inlineCodeBackground = if (dark) 0x24FFFFFF else 0x14000000,
                inlineCodeRadius = 4f * density,
                quoteBarWidth = 3f * density,
                quoteBarColor = if (dark) 0x40FFFFFF else 0x33000000,
                codeBackground = if (dark) 0x14FFFFFF else 0x0F000000,
                codeCornerRadius = 6f * density,
                dividerColor = if (dark) 0x33FFFFFF else 0x33000000,
                dividerThickness = 1f * density,
                findColor = if (dark) 0x66FFD60A else 0xFFFFE45C.toInt(),
                findCurrentColor = if (dark) 0xCCFF9F0A.toInt() else 0xFFFF9F0A.toInt(),
                findEdgeColor = if (dark) 0x99FFFFFF.toInt() else 0x66000000,
                findEdge = 1f * density,
                pictureScale = density,
                codeColors = codeColors(dark),
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
            // A quote 20 pt in with a bar beside it, a code block 16 pt in on a panel, its
            // language named in the room above its first line.
            quoteIndent = 20f * scale,
            codeIndent = 16f * scale,
            codePaddingTop = 26f * scale,
            codePaddingBottom = 10f * scale,
            codeLabelSize = 11f * scale,
            codeLabelColor = if (dark) 0x99FFFFFF.toInt() else 0x8A000000.toInt(),
            codeMargin = 6f * scale,
            calloutPadding = 16f * scale,
            calloutPaddingVertical = 12f * scale,
            calloutMargin = 5f * scale,
            calloutCornerRadius = 10f * scale,
            calloutColors = calloutColors(dark),
            inlineCodeBackground = if (dark) 0x24FFFFFF else 0x14000000,
            inlineCodeRadius = 4f * scale,
            quoteBarWidth = 3f * scale,
            quoteBarColor = if (dark) 0x40FFFFFF else 0x40000000,
            codeBackground = if (dark) 0x14FFFFFF else 0x0D000000,
            codeCornerRadius = 6f * scale,
            dividerColor = if (dark) 0x33FFFFFF else 0x26000000,
            dividerThickness = 1f * scale,
            // On a dark page the marker is see-through, so light text stays readable on it.
            findColor = if (dark) 0x66FFD60A else 0xFFFFE45C.toInt(),
            findCurrentColor = if (dark) 0xCCFF9F0A.toInt() else 0xFFFF9F0A.toInt(),
            findEdgeColor = if (dark) 0x99FFFFFF.toInt() else 0x66000000,
            findEdge = 1f * scale,
            pictureScale = scale,
            codeColors = codeColors(dark),
        )
    }
}
