package com.syncednotes.core

import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.Direction
import com.syncednotes.engine.text.ListKind
import com.syncednotes.engine.text.ListLevel
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TextDocument
import com.syncednotes.engine.text.ValueStyle
import com.syncednotes.engine.text.relativeSize
import com.syncednotes.engine.text.sizeValue
import kotlin.math.roundToInt
import syncednotes.note.v1.CodeLanguage as StoredCodeLanguage
import syncednotes.note.v1.Color
import syncednotes.note.v1.Divider
import syncednotes.note.v1.Embedded
import syncednotes.note.v1.Highlight
import syncednotes.note.v1.ListLevel as StoredListLevel
import syncednotes.note.v1.Note
import syncednotes.note.v1.Paragraph
import syncednotes.note.v1.Picture
import syncednotes.note.v1.Run
import syncednotes.note.v1.Alignment as StoredAlignment
import syncednotes.note.v1.Direction as StoredDirection
import syncednotes.note.v1.ListKind as StoredListKind
import syncednotes.note.v1.Role as StoredRole

/**
 * Turns a stored note (note.proto) into the engine's document and back.
 *
 * Both directions record meaning, not looks, as the Swift app's NoteFormat.swift did: a
 * heading is a heading, a highlight is "yellow". A note may hold things the engine cannot
 * edit yet (a picture, a table); [canEdit] tells, by reading the note in and writing it out
 * again and comparing. Only a note that comes back exactly as it was is ever saved from the
 * engine, so saving can never quietly drop anything.
 */
class NoteFormat {
    /**
     * Each text colour read from the note, by the 0xAARRGGBB the engine shows it as: written
     * back as the very floats it was read as, since eight bits a channel cannot hold them.
     */
    private val colors = HashMap<Int, Color>()

    /** Writes [note]'s text and formatting into [document], which must be empty. */
    fun decode(note: Note, document: TextDocument) {
        val paragraphs = note.paragraphsList.ifEmpty { listOf(Paragraph.getDefaultInstance()) }
        document.replace(0, document.length, paragraphs.joinToString("\n") { it.text })
        for ((index, stored) in paragraphs.withIndex()) {
            // A picture on a line of its own is the engine's: a paragraph showing it. One
            // amid text is not yet, and leaving it out is what makes [canEdit] refuse the note.
            val picture = stored.runsList.singleOrNull()?.takeIf {
                stored.text == OBJECT.toString() && it.start == 0 && it.length == 1 && it.embedded.hasPicture()
            }?.embedded?.picture
            val style = paragraphStyle(stored)
            document.setParagraphStyle(index, if (picture != null) style.copy(picture = PictureHandles.handle(picture)) else style)
            val base = document.paragraphStart(index)
            for (run in stored.runsList) {
                val start = base + run.start
                val end = start + run.length
                // A run that does not fit its paragraph's text is damage, not something to crash on.
                if (run.length <= 0 || run.start + run.length > stored.text.length) continue
                if (run.bold) document.setStyle(CharStyle.BOLD, true, start, end)
                if (run.italic) document.setStyle(CharStyle.ITALIC, true, start, end)
                if (run.underline) document.setStyle(CharStyle.UNDERLINE, true, start, end)
                if (run.strikethrough) document.setStyle(CharStyle.STRIKETHROUGH, true, start, end)
                if (run.code) document.setStyle(CharStyle.CODE, true, start, end)
                highlightColor(run.highlight)?.let { document.setValue(ValueStyle.HIGHLIGHT, it, start, end) }
                if (run.hasColor()) {
                    val argb = argb(run.color)
                    colors.putIfAbsent(argb, run.color)
                    document.setValue(ValueStyle.COLOR, argb, start, end)
                }
                sizeValue(run.relativeSize)?.let { document.setValue(ValueStyle.SIZE, it, start, end) }
                // Embedded things other than a divider (whose paragraph's role draws it) are not
                // in the engine yet; leaving them out is what makes [canEdit] refuse the note.
            }
        }
    }

    /** The document as a stored note. */
    fun encode(document: TextDocument): Note {
        val note = Note.newBuilder().setFormatVersion(1)
        for (p in 0 until document.paragraphCount) note.addParagraphs(paragraph(document, p))
        return note.build()
    }

    private fun paragraphStyle(stored: Paragraph): ParagraphStyle =
        ParagraphStyle(
            role = when (stored.role) {
                StoredRole.ROLE_TITLE -> Role.TITLE
                StoredRole.ROLE_HEADING -> Role.HEADING
                StoredRole.ROLE_SUBHEADING -> Role.SUBHEADING
                StoredRole.ROLE_QUOTE -> Role.QUOTE
                StoredRole.ROLE_CODE_BLOCK -> Role.CODE_BLOCK
                StoredRole.ROLE_CHECKLIST -> Role.CHECKLIST
                StoredRole.ROLE_DIVIDER -> Role.DIVIDER
                else -> Role.BODY
            },
            lists = stored.listsList.map { level ->
                val kind = when (level.kind) {
                    StoredListKind.LIST_KIND_DASHED -> ListKind.DASH
                    StoredListKind.LIST_KIND_NUMBERED -> ListKind.NUMBER
                    else -> ListKind.BULLET
                }
                ListLevel(kind, level.list, level.start)
            },
            alignment = when (stored.alignment) {
                StoredAlignment.ALIGNMENT_LEFT -> Alignment.LEFT
                StoredAlignment.ALIGNMENT_CENTER -> Alignment.CENTER
                StoredAlignment.ALIGNMENT_RIGHT -> Alignment.RIGHT
                StoredAlignment.ALIGNMENT_JUSTIFIED -> Alignment.JUSTIFIED
                else -> Alignment.NATURAL
            },
            direction = when (stored.direction) {
                StoredDirection.DIRECTION_LEFT_TO_RIGHT -> Direction.LEFT_TO_RIGHT
                StoredDirection.DIRECTION_RIGHT_TO_LEFT -> Direction.RIGHT_TO_LEFT
                else -> Direction.NATURAL
            },
            indent = stored.indent,
            lineSpacing = stored.lineSpacing,
            checked = stored.role == StoredRole.ROLE_CHECKLIST && stored.checked,
            // A language this app does not know yet reads as plain code, and leaving it out is
            // what makes [canEdit] refuse the note rather than save over it.
            language = when (stored.role) {
                StoredRole.ROLE_CODE_BLOCK -> when (stored.language) {
                    StoredCodeLanguage.CODE_LANGUAGE_C -> CodeLanguage.C
                    StoredCodeLanguage.CODE_LANGUAGE_CPP -> CodeLanguage.CPP
                    else -> CodeLanguage.NONE
                }
                else -> CodeLanguage.NONE
            },
        )

    private fun paragraph(document: TextDocument, p: Int): Paragraph {
        val style = document.paragraphStyle(p)
        val start = document.paragraphStart(p)
        val end = document.paragraphEnd(p)
        val text = document.substring(start, end)
        val stored = Paragraph.newBuilder()
            .setText(text)
            .setRole(
                when (style.role) {
                    Role.BODY -> StoredRole.ROLE_BODY
                    Role.TITLE -> StoredRole.ROLE_TITLE
                    Role.HEADING -> StoredRole.ROLE_HEADING
                    Role.SUBHEADING -> StoredRole.ROLE_SUBHEADING
                    Role.QUOTE -> StoredRole.ROLE_QUOTE
                    Role.CODE_BLOCK -> StoredRole.ROLE_CODE_BLOCK
                    Role.CHECKLIST -> StoredRole.ROLE_CHECKLIST
                    Role.DIVIDER -> StoredRole.ROLE_DIVIDER
                },
            )
            .setChecked(style.role == Role.CHECKLIST && style.checked)
            .setLanguage(
                when (if (style.role == Role.CODE_BLOCK) style.language else CodeLanguage.NONE) {
                    CodeLanguage.NONE -> StoredCodeLanguage.CODE_LANGUAGE_NONE
                    CodeLanguage.C -> StoredCodeLanguage.CODE_LANGUAGE_C
                    CodeLanguage.CPP -> StoredCodeLanguage.CODE_LANGUAGE_CPP
                },
            )
            .setAlignment(
                when (style.alignment) {
                    Alignment.NATURAL -> StoredAlignment.ALIGNMENT_NATURAL
                    Alignment.LEFT -> StoredAlignment.ALIGNMENT_LEFT
                    Alignment.CENTER -> StoredAlignment.ALIGNMENT_CENTER
                    Alignment.RIGHT -> StoredAlignment.ALIGNMENT_RIGHT
                    Alignment.JUSTIFIED -> StoredAlignment.ALIGNMENT_JUSTIFIED
                },
            )
            .setDirection(
                when (style.direction) {
                    Direction.NATURAL -> StoredDirection.DIRECTION_NATURAL
                    Direction.LEFT_TO_RIGHT -> StoredDirection.DIRECTION_LEFT_TO_RIGHT
                    Direction.RIGHT_TO_LEFT -> StoredDirection.DIRECTION_RIGHT_TO_LEFT
                },
            )
            .setIndent(style.indent)
            .setLineSpacing(style.lineSpacing)
        for (level in style.lists) {
            stored.addLists(
                StoredListLevel.newBuilder()
                    .setKind(
                        when (level.kind) {
                            ListKind.BULLET -> StoredListKind.LIST_KIND_BULLETED
                            ListKind.DASH -> StoredListKind.LIST_KIND_DASHED
                            ListKind.NUMBER -> StoredListKind.LIST_KIND_NUMBERED
                        },
                    )
                    .setList(level.id)
                    .setStart(level.start),
            )
        }
        val picture = if (style.picture != 0) PictureHandles.picture(style.picture) else null
        for (run in runs(document, start, end, divider = style.role == Role.DIVIDER, picture)) stored.addRuns(run)
        return stored.build()
    }

    /**
     * The paragraph's formatting as runs: a new run wherever anything recorded changes, plain
     * stretches left out. A divider's placeholder character is a run of its own, as each
     * embedded thing is.
     */
    private fun runs(document: TextDocument, start: Int, end: Int, divider: Boolean, picture: Picture?): List<Run> {
        val runs = ArrayList<Run>()
        var a = start
        while (a < end) {
            val here = runAt(document, a, divider, picture)
            var b = a + 1
            if (!here.hasEmbedded()) {
                while (b < end && runAt(document, b, divider, picture) == here) b++
            }
            if (here != PLAIN) runs += here.toBuilder().setStart(a - start).setLength(b - a).build()
            a = b
        }
        return runs
    }

    /** Everything recorded about the character at [offset], as a run without a position. */
    private fun runAt(document: TextDocument, offset: Int, divider: Boolean, picture: Picture? = null): Run {
        val run = Run.newBuilder()
            .setBold(document.hasStyle(CharStyle.BOLD, offset))
            .setItalic(document.hasStyle(CharStyle.ITALIC, offset))
            .setUnderline(document.hasStyle(CharStyle.UNDERLINE, offset))
            .setStrikethrough(document.hasStyle(CharStyle.STRIKETHROUGH, offset))
            .setCode(document.hasStyle(CharStyle.CODE, offset))
            .setHighlight(namedHighlight(document.valueAt(ValueStyle.HIGHLIGHT, offset)))
        document.valueAt(ValueStyle.COLOR, offset)?.let { run.setColor(colors[it] ?: color(it)) }
        val size = relativeSize(document.valueAt(ValueStyle.SIZE, offset))
        if (size != 1f) run.setRelativeSize(size)
        if (divider && document.text[offset] == OBJECT) run.setEmbedded(Embedded.newBuilder().setDivider(Divider.getDefaultInstance()))
        if (picture != null && document.text[offset] == OBJECT) run.setEmbedded(Embedded.newBuilder().setPicture(picture))
        return run.build()
    }

    companion object {
        /** U+FFFC: where something that is not text (a divider, a picture, a table) stands. */
        const val OBJECT = '￼'

        private val PLAIN: Run = Run.getDefaultInstance()

        /**
         * Whether a note can be edited in the engine without losing anything: read in and
         * written out, it must come back exactly as it is stored.
         */
        fun canEdit(note: Note): Boolean {
            val format = NoteFormat()
            val document = TextDocument()
            format.decode(note, document)
            return format.encode(document) == normalised(note)
        }

        /** An empty note stored with no paragraphs reads as one empty paragraph. */
        private fun normalised(note: Note): Note =
            if (note.paragraphsCount > 0) note else note.toBuilder().addParagraphs(Paragraph.getDefaultInstance()).build()

        /**
         * The six named highlights, in the colours the Mac app drew them: the system colours
         * at 35%, so text on top stays readable.
         */
        val HIGHLIGHTS: Map<Highlight, Int> = linkedMapOf(
            Highlight.HIGHLIGHT_YELLOW to 0x59FFCC00,
            Highlight.HIGHLIGHT_GREEN to 0x5934C759,
            Highlight.HIGHLIGHT_BLUE to 0x59007AFF,
            Highlight.HIGHLIGHT_PINK to 0x59FF2D55,
            Highlight.HIGHLIGHT_PURPLE to 0x59AF52DE,
            Highlight.HIGHLIGHT_ORANGE to 0x59FF9500,
        )

        private fun highlightColor(stored: Highlight): Int? = HIGHLIGHTS[stored]

        /** The named highlight for an engine colour: the nearest one, for a colour picked on the phone. */
        private fun namedHighlight(argb: Int?): Highlight {
            if (argb == null) return Highlight.HIGHLIGHT_NONE
            HIGHLIGHTS.entries.firstOrNull { it.value == argb }?.let { return it.key }
            fun distance(a: Int, b: Int) = (0..2).sumOf { shift ->
                val d = ((a shr (shift * 8)) and 0xFF) - ((b shr (shift * 8)) and 0xFF)
                d * d
            }
            return HIGHLIGHTS.minBy { distance(it.value, argb) }.key
        }

        private fun argb(color: Color): Int {
            fun channel(f: Float) = (f.coerceIn(0f, 1f) * 255f).roundToInt()
            return (channel(color.alpha) shl 24) or (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
        }

        /**
         * The colours text can be given from the toolbar, each chosen to read on the light and
         * the dark background, with the very floats the Swift app saved for them.
         */
        val TEXT_COLORS: List<Pair<String, Color>> = listOf(
            "Red" to rgb(0.91f, 0.26f, 0.24f),
            "Orange" to rgb(0.95f, 0.55f, 0.13f),
            "Green" to rgb(0.22f, 0.67f, 0.35f),
            "Blue" to rgb(0.20f, 0.50f, 0.96f),
            "Purple" to rgb(0.64f, 0.38f, 0.90f),
            "Pink" to rgb(0.93f, 0.33f, 0.58f),
            "Gray" to rgb(0.56f, 0.56f, 0.60f),
        )

        /** How the engine shows a stored colour: 0xAARRGGBB. */
        fun argbOf(color: Color): Int = argb(color)

        private fun rgb(r: Float, g: Float, b: Float): Color = Color.newBuilder().setRed(r).setGreen(g).setBlue(b).setAlpha(1f).build()

        private fun color(argb: Int): Color = TEXT_COLORS.firstOrNull { argb(it.second) == argb }?.second ?: Color.newBuilder()
            .setRed(((argb shr 16) and 0xFF) / 255f)
            .setGreen(((argb shr 8) and 0xFF) / 255f)
            .setBlue((argb and 0xFF) / 255f)
            .setAlpha(((argb ushr 24) and 0xFF) / 255f)
            .build()
    }
}
