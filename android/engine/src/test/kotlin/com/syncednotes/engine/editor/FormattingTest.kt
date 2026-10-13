package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.newComposer
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormattingTest {
    private fun Composer.boldOffsets() = (0 until document.length).filter { document.hasStyle(CharStyle.BOLD, it) }

    @Test
    fun boldOnASelectionMakesExactlyThatTextBold() {
        val c = newComposer()
        c.commitText("hello world", 1, 0)
        c.setSelectionFromInput(6, 11, 10)
        c.toggle(CharStyle.BOLD, 20)
        assertEquals((6..10).toList(), c.boldOffsets())
        assertTrue(c.formatState().bold)
    }

    @Test
    fun boldOnPartlyBoldTextMakesAllOfItBoldAndAgainMakesNoneOfIt() {
        val c = newComposer()
        c.commitText("hello world", 1, 0)
        c.setSelectionFromInput(0, 3, 10)
        c.toggle(CharStyle.BOLD, 20)
        c.setSelectionFromInput(0, 11, 30)
        assertFalse(c.formatState().bold)
        c.toggle(CharStyle.BOLD, 40)
        assertEquals((0..10).toList(), c.boldOffsets())
        c.toggle(CharStyle.BOLD, 50)
        assertEquals(emptyList(), c.boldOffsets())
    }

    @Test
    fun boldWithABareCaretAppliesToWhatIsTypedNext() {
        val c = newComposer()
        c.commitText("plain ", 1, 0)
        c.toggle(CharStyle.BOLD, 10)
        assertTrue(c.formatState().bold)
        c.setComposingText("st", 1, 20)
        c.setComposingText("strong", 1, 30)
        c.commitText("strong", 1, 40)
        assertEquals((6..11).toList(), c.boldOffsets())
        c.commitText("er", 1, 50)
        assertEquals((6..13).toList(), c.boldOffsets())
    }

    @Test
    fun movingTheCaretForgetsAStyleChosenForTyping() {
        val c = newComposer()
        c.commitText("abc", 1, 0)
        c.toggle(CharStyle.ITALIC, 10)
        c.onKey(Key.LEFT, false, false, 20)
        assertFalse(c.formatState().italic)
    }

    @Test
    fun undoingADeletionBringsTheBoldBack() {
        val c = newComposer()
        c.commitText("one two", 1, 0)
        c.setSelectionFromInput(4, 7, 10)
        c.toggle(CharStyle.BOLD, 20)
        c.onKey(Key.BACKSPACE, false, false, 30)
        assertEquals("one ", c.document.toString())
        c.undo(40)
        assertEquals("one two", c.document.toString())
        assertEquals((4..6).toList(), c.boldOffsets())
        c.undo(50)
        assertEquals(emptyList(), c.boldOffsets())
    }

    @Test
    fun bulletsApplyToEveryParagraphTheSelectionTouchesAndToggleOff() {
        val c = newComposer()
        c.commitText("a\nb\nc", 1, 0)
        c.setSelectionFromInput(0, 3, 10)
        c.toggleList(ListType.BULLET, 20)
        assertEquals(listOf(ListType.BULLET, ListType.BULLET, ListType.NONE), (0..2).map { c.document.paragraphStyle(it).list })
        assertEquals(ListType.BULLET, c.formatState().list)
        c.toggleList(ListType.BULLET, 30)
        assertEquals(ListType.NONE, c.document.paragraphStyle(0).list)
    }

    @Test
    fun enterInABulletContinuesTheListAndEnterOnAnEmptyItemEndsIt() {
        val c = newComposer()
        c.commitText("item", 1, 0)
        c.toggleList(ListType.BULLET, 10)
        c.commitText("\n", 1, 20)
        assertEquals(ListType.BULLET, c.document.paragraphStyle(1).list)
        c.commitText("\n", 1, 30)
        assertEquals("item\n", c.document.toString())
        assertEquals(ListType.NONE, c.document.paragraphStyle(1).list)
    }

    @Test
    fun backspaceAtTheStartOfABulletRemovesTheBulletNotTheLineBreak() {
        val c = newComposer()
        c.commitText("one\ntwo", 1, 0)
        c.setSelectionFromInput(4, 4, 10)
        c.toggleList(ListType.NUMBER, 20)
        c.deleteSurroundingText(1, 0, 30)
        assertEquals("one\ntwo", c.document.toString())
        assertEquals(ListType.NONE, c.document.paragraphStyle(1).list)
        c.deleteSurroundingText(1, 0, 40)
        assertEquals("onetwo", c.document.toString())
    }

    @Test
    fun mixedAlignmentSelectsNoAlignmentButton() {
        val c = newComposer()
        c.commitText("a\nb", 1, 0)
        c.setSelectionFromInput(0, 0, 10)
        c.setAlignment(Alignment.CENTER, 20)
        assertEquals(Alignment.CENTER, c.formatState().alignment)
        c.setSelectionFromInput(0, 3, 30)
        assertNull(c.formatState().alignment)
    }

    @Test
    fun indentStopsAtSixteenAndOutdentAtZero() {
        val c = newComposer()
        c.commitText("text", 1, 0)
        assertFalse(c.formatState().canOutdent)
        repeat(20) { c.indent(10L + it) }
        assertEquals(16, c.document.paragraphStyle(0).indent)
        assertFalse(c.formatState().canIndent)
        assertTrue(c.formatState().canOutdent)
    }

    @Test
    fun aRightAlignedParagraphCannotBeIndented() {
        val c = newComposer()
        c.commitText("text", 1, 0)
        c.setAlignment(Alignment.RIGHT, 10)
        assertFalse(c.formatState().canIndent)
        assertFalse(c.formatState().canOutdent)
    }

    @Test
    fun eachFormattingChangeIsItsOwnUndoStep() {
        val c = newComposer()
        c.commitText("text", 1, 0)
        c.setSelectionFromInput(0, 4, 10)
        c.toggle(CharStyle.UNDERLINE, 20)
        c.setAlignment(Alignment.CENTER, 30)
        c.undo(40)
        assertEquals(Alignment.LEFT, c.document.paragraphStyle(0).alignment)
        assertTrue(c.document.hasStyle(CharStyle.UNDERLINE, 0))
        c.undo(50)
        assertFalse(c.document.hasStyle(CharStyle.UNDERLINE, 0))
        assertEquals("text", c.document.toString())
    }

    @Test
    fun choosingTheAlignmentAlreadyChosenIsNotAnUndoStep() {
        val c = newComposer()
        c.commitText("text", 1, 0)
        c.setAlignment(Alignment.CENTER, 3000)
        c.setAlignment(Alignment.CENTER, 4000)
        c.undo(5000)
        assertEquals(Alignment.LEFT, c.document.paragraphStyle(0).alignment)
        assertEquals("text", c.document.toString())
    }

    @Test
    fun thePanelHearsAboutFormattingChanges() {
        val host = RecordingHost()
        val c = newComposer(host)
        c.commitText("text", 1, 0)
        val before = host.formatChanges
        c.toggle(CharStyle.BOLD, 10)
        assertTrue(host.formatChanges > before)
    }
}
