package com.syncednotes.core

import com.google.protobuf.ByteString
import com.syncednotes.engine.text.CalloutColor
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListKind
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TextDocument
import com.syncednotes.engine.text.ValueStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import syncednotes.note.v1.Alignment
import syncednotes.note.v1.CalloutColor as StoredCalloutColor
import syncednotes.note.v1.CodeLanguage as StoredCodeLanguage
import syncednotes.note.v1.Color
import syncednotes.note.v1.Direction
import syncednotes.note.v1.Divider
import syncednotes.note.v1.Embedded
import syncednotes.note.v1.Highlight
import syncednotes.note.v1.ListKind as StoredListKind
import syncednotes.note.v1.ListLevel
import syncednotes.note.v1.Note
import syncednotes.note.v1.Paragraph
import syncednotes.note.v1.Picture
import syncednotes.note.v1.Role as StoredRole
import syncednotes.note.v1.Row
import syncednotes.note.v1.Run
import syncednotes.note.v1.Table

/** Notes as the Swift app saved them, read into the engine and written back. */
class NoteFormatTest {
    private fun paragraph(text: String, role: StoredRole = StoredRole.ROLE_BODY) = Paragraph.newBuilder().setText(text).setRole(role)
    private fun note(vararg paragraphs: Paragraph.Builder) =
        Note.newBuilder().setFormatVersion(1).addAllParagraphs(paragraphs.map { it.build() }).build()
    private fun run(start: Int, length: Int) = Run.newBuilder().setStart(start).setLength(length)
    private fun level(kind: StoredListKind, list: Int, start: Int = 0) = ListLevel.newBuilder().setKind(kind).setList(list).setStart(start)

    private fun decoded(note: Note): TextDocument = TextDocument().also { NoteFormat().decode(note, it) }

    @Test
    fun aNewNoteFromTheMacReadsAsATitleAndBodyAndComesBackTheSame() {
        val stored = note(paragraph("Groceries", StoredRole.ROLE_TITLE), paragraph("milk and bread"))
        val document = decoded(stored)
        assertEquals("Groceries\nmilk and bread", document.toString())
        assertEquals(Role.TITLE, document.paragraphStyle(0).role)
        assertTrue(NoteFormat.canEdit(stored))
    }

    @Test
    fun everyKindOfFormattingTheEngineKnowsComesBackToTheLastBit() {
        val stored = note(
            paragraph("Plan", StoredRole.ROLE_TITLE),
            paragraph("bold italic underlined struck code").addAllRuns(
                listOf(
                    run(0, 4).setBold(true), run(5, 6).setItalic(true), run(12, 10).setUnderline(true),
                    run(23, 6).setStrikethrough(true), run(30, 4).setCode(true),
                ).map { it.build() },
            ),
            paragraph("marked, coloured, bigger").addAllRuns(
                listOf(
                    run(0, 6).setHighlight(Highlight.HIGHLIGHT_YELLOW),
                    // A colour from the colour panel: no eight-bit value holds these floats.
                    run(8, 8).setColor(Color.newBuilder().setRed(0.123f).setGreen(0.456f).setBlue(0.789f).setAlpha(1f)),
                    run(18, 6).setRelativeSize(1.0625f),
                ).map { it.build() },
            ),
            paragraph("centred, right to left, indented, spaced")
                .setAlignment(Alignment.ALIGNMENT_CENTER).setDirection(Direction.DIRECTION_RIGHT_TO_LEFT)
                .setIndent(2).setLineSpacing(1.5f),
            paragraph("first").addLists(level(StoredListKind.LIST_KIND_NUMBERED, 1, start = 2)),
            paragraph("nested").addLists(level(StoredListKind.LIST_KIND_NUMBERED, 1, start = 2)).addLists(level(StoredListKind.LIST_KIND_NUMBERED, 2)),
            paragraph("dashed").addLists(level(StoredListKind.LIST_KIND_DASHED, 3)),
            paragraph("done", StoredRole.ROLE_CHECKLIST).setChecked(true).setIndent(1),
            paragraph("said once", StoredRole.ROLE_QUOTE),
            paragraph("let x = 1", StoredRole.ROLE_CODE_BLOCK),
            paragraph("￼", StoredRole.ROLE_DIVIDER).addRuns(run(0, 1).setEmbedded(Embedded.newBuilder().setDivider(Divider.getDefaultInstance()))),
            paragraph("😀 after an emoji").addRuns(run(3, 5).setBold(true)),
        )
        assertTrue(NoteFormat.canEdit(stored))
        val document = decoded(stored)
        assertEquals(listOf(ListKind.NUMBER, ListKind.NUMBER), document.paragraphStyle(5).lists.map { it.kind })
        assertTrue(document.hasStyle(CharStyle.BOLD, document.paragraphStart(11) + 3))
    }

    @Test
    fun aPictureOnALineOfItsOwnIsEditedAndComesBackTheSame() {
        val stored = Picture.newBuilder().setId(ByteString.copyFrom(ByteArray(16) { 4 })).setType("public.jpeg").setWidth(800).setHeight(600)
        val note = note(
            paragraph("Above"),
            paragraph("￼").addRuns(run(0, 1).setEmbedded(Embedded.newBuilder().setPicture(stored))),
            paragraph("Below"),
        )
        assertTrue(NoteFormat.canEdit(note))
        val document = decoded(note)
        val handle = document.paragraphStyle(1).picture
        assertTrue(handle != 0)
        assertEquals(stored.build(), PictureHandles.picture(handle))
        assertEquals(note, NoteFormat().encode(document))
    }

    @Test
    fun aPictureAmidTextOrATableIsNotEditableYet() {
        val picture = note(
            paragraph("See ￼ here").addRuns(
                run(4, 1).setEmbedded(Embedded.newBuilder().setPicture(Picture.newBuilder().setId(ByteString.copyFrom(ByteArray(16))).setType("public.png"))),
            ),
        )
        val table = note(paragraph("￼").addRuns(run(0, 1).setEmbedded(Embedded.newBuilder().setTable(Table.newBuilder().addRows(Row.getDefaultInstance())))))
        assertFalse(NoteFormat.canEdit(picture))
        assertFalse(NoteFormat.canEdit(table))
    }

    @Test
    fun editingOneParagraphLeavesEveryOtherExactlyAsItWasStored() {
        val coloured = Color.newBuilder().setRed(0.1f).setGreen(0.2f).setBlue(0.3f).setAlpha(1f)
        val stored = note(paragraph("Title", StoredRole.ROLE_TITLE), paragraph("keep me").addRuns(run(0, 4).setColor(coloured)), paragraph("change me"))
        val format = NoteFormat()
        val document = TextDocument()
        format.decode(stored, document)
        val at = document.paragraphEnd(2)
        document.replace(at, at, ", please")
        val saved = format.encode(document)
        assertEquals(stored.getParagraphs(0), saved.getParagraphs(0))
        assertEquals(stored.getParagraphs(1), saved.getParagraphs(1))
        assertEquals("change me, please", saved.getParagraphs(2).text)
    }

    @Test
    fun runsAreWrittenAsTheMacWroteThemPlainTextLeftOutPositionsInTheParagraph() {
        val document = TextDocument()
        document.replace(0, 0, "first\nsome bold words")
        document.setStyle(CharStyle.BOLD, true, 11, 15)
        document.setValue(ValueStyle.HIGHLIGHT, NoteFormat.HIGHLIGHTS.getValue(Highlight.HIGHLIGHT_GREEN), 11, 15)
        val saved = NoteFormat().encode(document)
        assertEquals(0, saved.getParagraphs(0).runsCount)
        assertEquals(listOf(run(5, 4).setBold(true).setHighlight(Highlight.HIGHLIGHT_GREEN).build()), saved.getParagraphs(1).runsList)
    }

    @Test
    fun aCodeBlocksLanguageIsSavedWithItAndComesBackTheSame() {
        val stored = note(
            paragraph("#include <stdio.h>", StoredRole.ROLE_CODE_BLOCK).setLanguage(StoredCodeLanguage.CODE_LANGUAGE_C),
            paragraph("std::vector<int> v;", StoredRole.ROLE_CODE_BLOCK).setLanguage(StoredCodeLanguage.CODE_LANGUAGE_CPP),
            paragraph("plain code", StoredRole.ROLE_CODE_BLOCK),
        )
        assertTrue(NoteFormat.canEdit(stored))
        val document = decoded(stored)
        assertEquals(listOf(CodeLanguage.C, CodeLanguage.CPP, CodeLanguage.NONE), (0..2).map { document.paragraphStyle(it).language })
        assertEquals(stored, NoteFormat().encode(document))
    }

    @Test
    fun aCodeLanguageThisAppDoesNotKnowYetOpensTheNoteReadOnly() {
        val fromANewerApp = note(paragraph("fn main() {}", StoredRole.ROLE_CODE_BLOCK).setLanguageValue(99))
        assertFalse(NoteFormat.canEdit(fromANewerApp))
    }

    @Test
    fun aCalloutsColourIsSavedWithItAndComesBackTheSame() {
        val stored = note(
            paragraph("Stack", StoredRole.ROLE_CALLOUT),
            paragraph("Heap", StoredRole.ROLE_CALLOUT).setCallout(StoredCalloutColor.CALLOUT_COLOR_GREEN),
            paragraph("Data", StoredRole.ROLE_CALLOUT).setCallout(StoredCalloutColor.CALLOUT_COLOR_ORANGE),
        )
        assertTrue(NoteFormat.canEdit(stored))
        val document = decoded(stored)
        assertEquals(listOf(CalloutColor.BLUE, CalloutColor.GREEN, CalloutColor.ORANGE), (0..2).map { document.paragraphStyle(it).callout })
        assertEquals(stored, NoteFormat().encode(document))
    }

    @Test
    fun aCalloutColourThisAppDoesNotKnowYetOpensTheNoteReadOnly() {
        assertFalse(NoteFormat.canEdit(note(paragraph("aside", StoredRole.ROLE_CALLOUT).setCalloutValue(42))))
    }

    @Test
    fun aParagraphThatIsNoLongerCodeIsSavedWithoutALanguage() {
        val document = TextDocument()
        document.replace(0, 0, "int x;")
        document.setParagraphStyle(0, document.paragraphStyle(0).copy(language = CodeLanguage.C))
        assertEquals(StoredCodeLanguage.CODE_LANGUAGE_NONE, NoteFormat().encode(document).getParagraphs(0).language)
    }

    @Test
    fun anEmptyNoteIsOneEmptyParagraph() {
        assertEquals(note(paragraph("")), NoteFormat().encode(TextDocument()))
        assertTrue(NoteFormat.canEdit(Note.newBuilder().setFormatVersion(1).build()))
    }
}
