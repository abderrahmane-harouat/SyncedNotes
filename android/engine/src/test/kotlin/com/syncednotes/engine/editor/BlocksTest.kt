package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.text.CharStyle
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
}
