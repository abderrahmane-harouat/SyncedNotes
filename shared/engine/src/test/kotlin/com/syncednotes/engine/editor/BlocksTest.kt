package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListKind
import com.syncednotes.engine.text.ListLevel
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.ValueStyle
import com.syncednotes.engine.text.relativeSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Roles, lists in levels, line spacing and relative sizes: the note format's paragraphs. */
class BlocksTest {
    private fun composer(text: String): Composer {
        val c = Composer(TestFonts.family, EditorStyle.macLike(2f), RecordingHost())
        c.setViewport(1200, 2000, 0f, 0f)
        c.type(text, 0)
        return c
    }

    private fun Composer.caretTo(offset: Int) = setSelectionFromInput(offset, offset, 0)
    private fun Composer.style(p: Int) = document.paragraphStyle(p)

    /** The caret's height at [offset], which follows the text size there. */
    private fun Composer.caretHeight(offset: Int): Float {
        caretTo(offset)
        return caretInView().let { it[3] - it[1] }
    }

    @Test
    fun aTitleIsLargerAndBoldWithoutAnyBoldOrSizeStoredOnItsText() {
        val c = composer("Groceries\nmilk")
        c.caretTo(0)
        c.toggleRole(Role.TITLE, 0)
        assertEquals(Role.TITLE, c.style(0).role)
        assertTrue(c.caretHeight(3) > c.caretHeight(12) * 1.7f)
        assertFalse(c.document.hasStyle(CharStyle.BOLD, 0))
        assertNull(c.document.valueAt(ValueStyle.SIZE, 0))
    }

    @Test
    fun choosingTheRoleEveryParagraphHasAlreadyPutsThemBackToBodyText() {
        val c = composer("Heading")
        c.toggleRole(Role.HEADING, 0)
        c.toggleRole(Role.HEADING, 10)
        assertEquals(Role.BODY, c.style(0).role)
    }

    @Test
    fun numberingAParagraphBelowANumberedOneJoinsItsListSoTheCountRunsOn() {
        val c = composer("one\ntwo")
        c.caretTo(0)
        c.toggleList(ListType.NUMBER, 0)
        c.caretTo(5)
        c.toggleList(ListType.NUMBER, 10)
        assertEquals(c.style(0).lists, c.style(1).lists)
        assertEquals(listOf(1, 2), (0..1).map { c.itemNumber(it, c.style(it)) })
    }

    @Test
    fun writingBetweenTwoNumberedItemsStartsTheSecondAsANewList() {
        val c = composer("one\nnote\ntwo")
        c.caretTo(0)
        c.toggleList(ListType.NUMBER, 0)
        c.caretTo(10)
        c.toggleList(ListType.NUMBER, 10)
        assertEquals(1, c.itemNumber(2, c.style(2)))
    }

    @Test
    fun aListThatStartsAtTwoCountsOnFromTwo() {
        val c = composer("b\nc")
        val list = listOf(ListLevel(ListKind.NUMBER, id = 7, start = 2))
        for (p in 0..1) c.document.setParagraphStyle(p, ParagraphStyle(lists = list))
        assertEquals(listOf(2, 3), (0..1).map { c.itemNumber(it, c.style(it)) })
    }

    @Test
    fun tabNestsAListItemUnderTheOneAboveAndShiftTabBringsItBack() {
        val c = composer("fruit\napples\npears")
        c.setSelectionFromInput(0, 18, 0)
        c.toggleList(ListType.BULLET, 0)
        c.caretTo(8)
        c.indent(10)
        c.caretTo(16)
        c.indent(20)
        assertEquals(2, c.style(1).depth)
        // The two nested items are one list, so a numbered one would count 1, 2.
        assertEquals(c.style(1).lists, c.style(2).lists)
        assertEquals(c.style(0).lists, c.style(1).lists.take(1))
        c.outdent(30)
        assertEquals(1, c.style(2).depth)
    }

    @Test
    fun enterOnAnEmptyNestedItemStepsOutALevelThenOutOfTheList() {
        val c = composer("a")
        c.toggleList(ListType.BULLET, 0)
        // Return continues the list on a new, empty item.
        c.type("\n", 5)
        c.indent(10)
        assertEquals(2, c.style(1).depth)
        c.type("\n", 20)
        assertEquals(1, c.style(1).depth)
        c.type("\n", 30)
        assertEquals(ListType.NONE, c.style(1).list)
        assertEquals("a\n", c.document.toString())
    }

    @Test
    fun aChecklistIsARoleAndABulletTurnsItBackIntoAListItem() {
        val c = composer("buy milk")
        c.toggleList(ListType.CHECKBOX, 0)
        assertEquals(Role.CHECKLIST, c.style(0).role)
        assertEquals(ListType.CHECKBOX, c.style(0).list)
        c.toggleList(ListType.BULLET, 10)
        assertEquals(Role.BODY, c.style(0).role)
        assertEquals(ListType.BULLET, c.style(0).list)
    }

    @Test
    fun aSizePickedInBodyTextIsKeptRelativeAndShownInSamsungsUnits() {
        val c = composer("text")
        c.setSelectionFromInput(0, 4, 0)
        c.setSize(34, 10)
        assertEquals(2f, relativeSize(c.document.valueAt(ValueStyle.SIZE, 0)))
        assertEquals(34, c.formatState().size)
        c.setRelativeSize(1f, 20)
        assertNull(c.document.valueAt(ValueStyle.SIZE, 0))
    }

    @Test
    fun doubleLineSpacingMakesAParagraphTwiceAsTall() {
        val c = composer("one line")
        val single = c.caretInView()
        c.setLineSpacing(2f, 10)
        val double = c.caretInView()
        assertEquals(2f, c.style(0).lineSpacing)
        // The caret keeps the text's height, centred in a line twice as tall.
        assertTrue(double[1] > single[1])
        c.setLineSpacing(1f, 20)
        assertEquals(0f, c.style(0).lineSpacing)
    }

    @Test
    fun aQuoteSitsInFromTheMarginByItsOwnIndent() {
        val c = composer("said")
        c.caretTo(0)
        val before = c.caretInView()[0]
        c.toggleRole(Role.QUOTE, 10)
        c.caretTo(0)
        assertEquals(before + c.style.quoteIndent, c.caretInView()[0])
    }

    @Test
    fun aCodeBlockIsMonospacedSoNarrowAndWideLettersTakeTheSameRoom() {
        val c = composer("iiii\nWWWW")
        c.setSelectionFromInput(0, 9, 0)
        c.toggleRole(Role.CODE_BLOCK, 0)
        c.caretTo(4)
        val narrow = c.caretInView()[0]
        c.caretTo(9)
        assertEquals(narrow, c.caretInView()[0], 0.5f)
    }

    @Test
    fun choosingALanguageWithTheCaretInABlockGivesTheWholeBlockThatLanguage() {
        val c = composer("intro\nint a;\nint b;\noutro")
        c.setSelectionFromInput(6, 19, 0)
        c.toggleRole(Role.CODE_BLOCK, 0)
        c.caretTo(8)
        c.toggleCodeBlock(CodeLanguage.CPP, 0)
        assertEquals(listOf(CodeLanguage.NONE, CodeLanguage.CPP, CodeLanguage.CPP, CodeLanguage.NONE), (0..3).map { c.style(it).language })
        assertEquals(Role.BODY, c.style(0).role)
        assertEquals(CodeLanguage.CPP, c.formatState().codeLanguage)
    }

    @Test
    fun choosingALanguageOnBodyTextMakesItACodeBlockInThatLanguage() {
        val c = composer("int main(void);")
        c.toggleCodeBlock(CodeLanguage.C, 0)
        assertEquals(Role.CODE_BLOCK, c.style(0).role)
        assertEquals(CodeLanguage.C, c.style(0).language)
    }

    @Test
    fun choosingTheLanguageTheBlockIsAlreadyInPutsItBackToBodyText() {
        val c = composer("int a;")
        c.toggleCodeBlock(CodeLanguage.C, 0)
        c.toggleCodeBlock(CodeLanguage.C, 0)
        assertEquals(ParagraphStyle.DEFAULT, c.style(0))
        assertEquals(null, c.formatState().codeLanguage)
    }

    @Test
    fun returnInACodeBlockGoesOnInItsLanguage() {
        val c = composer("int a;")
        c.toggleCodeBlock(CodeLanguage.C, 0)
        c.caretTo(6)
        c.type("\n", 0)
        assertEquals(Role.CODE_BLOCK, c.style(1).role)
        assertEquals(CodeLanguage.C, c.style(1).language)
    }

    @Test
    fun theDownArrowOnTheLastLineOfACodeBlockAtTheEndOfANoteGoesOnToANewLineOfText() {
        val c = composer("int a;\nint b;")
        c.setSelectionFromInput(0, c.document.length, 0)
        c.toggleCodeBlock(CodeLanguage.C, 0)
        c.caretTo(9)
        c.onKey(Key.DOWN, false, false, 10)
        assertEquals(3, c.document.paragraphCount)
        assertEquals(Role.CODE_BLOCK, c.style(1).role)
        assertEquals(Role.BODY, c.style(2).role)
        assertEquals(CodeLanguage.NONE, c.style(2).language)
        assertEquals(c.document.length, c.selection.start)
        c.type("done", 20)
        assertEquals("int a;\nint b;\ndone", c.document.toString())
        // Down again on plain text at the end adds nothing.
        c.onKey(Key.DOWN, false, false, 30)
        assertEquals(3, c.document.paragraphCount)
        // One undo takes the line away again.
        c.undo(40)
        c.undo(50)
        assertEquals("int a;\nint b;", c.document.toString())
    }

    @Test
    fun theArrowsGoLineByLineIntoAndOutOfACodeBlockWhateverItsPadding() {
        val c = composer("before\nint a;\nint b;\nafter")
        c.setSelectionFromInput(8, 16, 0)
        c.toggleCodeBlock(CodeLanguage.C, 0)
        c.caretTo(3)
        val down = (1..3).map { c.onKey(Key.DOWN, false, false, 10L * it); c.document.paragraphAt(c.selection.start) }
        assertEquals(listOf(1, 2, 3), down)
        val up = (1..3).map { c.onKey(Key.UP, false, false, 100L + it); c.document.paragraphAt(c.selection.start) }
        assertEquals(listOf(2, 1, 0), up)
        assertEquals(4, c.document.paragraphCount)
    }

    @Test
    fun aTapBelowACodeBlockAtTheEndOfANoteStartsALineOfTextUnderIt() {
        val c = composer("int a;")
        c.toggleCodeBlock(CodeLanguage.C, 0)
        c.onPointer(PointerEvent(PointerAction.DOWN, 100f, 1500f, 1000))
        c.onPointer(PointerEvent(PointerAction.UP, 100f, 1500f, 1050))
        assertEquals(2, c.document.paragraphCount)
        assertEquals(Role.BODY, c.style(1).role)
        assertEquals(c.document.length, c.selection.start)
    }

    @Test
    fun aLineMadeCodeUnderABlockJoinsItInItsLanguage() {
        val c = composer("int a;\nint b;")
        c.caretTo(0)
        c.toggleCodeBlock(CodeLanguage.CPP, 0)
        c.caretTo(8)
        c.toggleRole(Role.CODE_BLOCK, 0)
        assertEquals(CodeLanguage.CPP, c.style(1).language)
    }

    @Test
    fun aCodeBlockMadeAnythingElseForgetsItsLanguage() {
        val c = composer("int a;")
        c.toggleCodeBlock(CodeLanguage.C, 0)
        c.toggleRole(Role.QUOTE, 0)
        assertEquals(CodeLanguage.NONE, c.style(0).language)
    }

    @Test
    fun undoPutsBackTheLanguageABlockHadBefore() {
        val c = composer("int a;")
        c.toggleCodeBlock(CodeLanguage.C, 0)
        c.toggleCodeBlock(CodeLanguage.CPP, 1000)
        c.undo(2000)
        assertEquals(CodeLanguage.C, c.style(0).language)
    }

    @Test
    fun aCodeBlocksTextKeepsClearOfItsPanelWithItsLanguageNamedAbove() {
        val c = composer("above\nint a;\nint b;\nbelow")
        c.setSelectionFromInput(6, 19, 0)
        c.toggleCodeBlock(CodeLanguage.C, 0)
        fun lineTop(offset: Int): Float { c.caretTo(offset); return c.caretInView()[1] }
        val plainGap = lineTop(19 + 1) - lineTop(13)
        // The first line sits below the room for the language's name, the next straight after it.
        assertTrue(lineTop(6) - lineTop(0) > c.style.lineHeight + c.style.codePaddingTop)
        assertEquals(c.style.lineHeight, lineTop(13) - lineTop(6), 1f)
        // And the text after the block is held off by the room at its foot.
        assertTrue(plainGap > c.style.lineHeight + c.style.codePaddingBottom)
    }

    @Test
    fun clickingTheLanguageAtTheTopOfABlockAsksForTheLanguagesAndChoosingOneChangesTheBlock() {
        val host = RecordingHost()
        val c = Composer(TestFonts.family, EditorStyle.macLike(2f), host)
        c.setViewport(1200, 2000, 0f, 0f)
        c.type("int a;\nint b;", 0)
        c.setSelectionFromInput(0, 13, 0)
        c.toggleCodeBlock(CodeLanguage.C, 0)
        val x = c.style.paddingLeft + c.style.codeIndent + 4f
        val y = c.style.paddingTop + c.style.codeMargin + c.style.codePaddingTop / 2f
        c.onMouse(PointerAction.DOWN, x, y, 1, false, 0)
        c.onMouse(PointerAction.UP, x, y, 1, false, 0)
        assertEquals(listOf(0 to CodeLanguage.C), host.languageMenus)
        c.setBlockLanguage(0, CodeLanguage.CPP, 0)
        assertEquals(listOf(CodeLanguage.CPP, CodeLanguage.CPP), (0..1).map { c.style(it).language })
    }

    @Test
    fun clickingBesideTheLanguageNamePlacesTheCaretAsUsual() {
        val host = RecordingHost()
        val c = Composer(TestFonts.family, EditorStyle.macLike(2f), host)
        c.setViewport(1200, 2000, 0f, 0f)
        c.type("int a;", 0)
        c.toggleCodeBlock(CodeLanguage.C, 0)
        val y = c.style.paddingTop + c.style.codeMargin + c.style.codePaddingTop / 2f
        c.onMouse(PointerAction.DOWN, 900f, y, 1, false, 0)
        c.onMouse(PointerAction.UP, 900f, y, 1, false, 0)
        assertTrue(host.languageMenus.isEmpty())
    }

    @Test
    fun returnAtTheEndOfATitleStartsBodyText() {
        val c = composer("Groceries")
        c.toggleRole(Role.TITLE, 0)
        c.caretTo(9)
        c.type("\n", 10)
        assertEquals(Role.TITLE, c.style(0).role)
        assertEquals(Role.BODY, c.style(1).role)
    }

    @Test
    fun returnInsideAHeadingLeavesBothHalvesHeadings() {
        val c = composer("Two words")
        c.toggleRole(Role.HEADING, 0)
        c.caretTo(3)
        c.type("\n", 10)
        assertEquals(listOf(Role.HEADING, Role.HEADING), (0..1).map { c.style(it).role })
    }

    @Test
    fun aNoteThatIsOnlyShownCannotBeChangedButCanStillBeSelected() {
        val c = composer("read only")
        c.editable = false
        c.type("x", 10)
        c.onKey(Key.BACKSPACE, false, false, 20)
        c.toggleRole(Role.TITLE, 30)
        c.onKey(Key.SELECT_ALL, false, false, 40)
        assertEquals("read only", c.document.toString())
        assertEquals(Role.BODY, c.style(0).role)
        assertEquals(Selection(0, 9), c.selection)
        assertFalse(c.undo(50))
    }

    @Test
    fun aDividerGoesBelowTheParagraphWithTheCaretOnANewLineAfterIt() {
        val c = composer("Above\nBelow")
        c.caretTo(2)
        c.insertDivider(0)
        assertEquals(4, c.document.paragraphCount)
        assertEquals(Role.DIVIDER, c.style(1).role)
        assertEquals("\uFFFC", c.document.substring(c.document.paragraphStart(1), c.document.paragraphEnd(1)))
        assertEquals(Role.BODY, c.style(2).role)
        assertEquals(c.document.paragraphStart(2), c.selection.start)
        c.type("After", 0)
        assertEquals("Above\n\uFFFC\nAfter\nBelow", c.document.text.toString())
    }

    @Test
    fun theLineSpacingShowsWhatTheParagraphsHaveAndNothingWhenTheyDisagree() {
        val c = composer("one\ntwo")
        assertEquals(1f, c.formatState().lineSpacing)
        c.caretTo(0)
        c.setLineSpacing(1.5f, 0)
        assertEquals(1.5f, c.formatState().lineSpacing)
        c.setSelectionFromInput(0, 7, 0)
        assertNull(c.formatState().lineSpacing)
    }
}
