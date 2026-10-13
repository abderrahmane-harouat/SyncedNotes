package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.newComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerTest {
    @Test
    fun composingTextIsRewrittenInPlaceThenCommitted() {
        val c = newComposer()
        c.setComposingText("h", 1, 0)
        c.setComposingText("he", 1, 10)
        c.setComposingText("hel", 1, 20)
        assertEquals("hel", c.document.toString())
        assertEquals(0 to 3, c.composingStart to c.composingEnd)
        c.commitText("hello", 1, 30)
        assertEquals("hello", c.document.toString())
        assertEquals(-1, c.composingStart)
        assertEquals(Selection(5), c.selection)
    }

    @Test
    fun newCursorPositionFollowsAndroidsRule() {
        val c = newComposer()
        c.commitText("abc", 1, 0)
        c.commitText("XY", 0, 10)
        assertEquals("abcXY", c.document.toString())
        assertEquals(3, c.selection.focus)
        c.commitText("Z", 2, 20)
        assertEquals(5, c.selection.focus)
    }

    @Test
    fun deletingAroundTheCursorNeverSplitsASurrogatePair() {
        val c = newComposer()
        c.commitText("a😀", 1, 0)
        c.deleteSurroundingText(1, 0, 10)
        assertEquals("a", c.document.toString())
    }

    @Test
    fun deletingInCodePointsRemovesWholeCharacters() {
        val c = newComposer()
        c.commitText("x😀😀", 1, 0)
        c.deleteSurroundingTextInCodePoints(2, 0, 10)
        assertEquals("x", c.document.toString())
    }

    @Test
    fun theInputMethodHearsAboutTheSelectionOncePerBatch() {
        val host = RecordingHost()
        val c = newComposer(host)
        host.selections.clear()
        c.beginBatchEdit()
        c.setComposingText("he", 1, 0)
        c.commitText("he", 1, 10)
        c.commitText(" ", 1, 20)
        assertTrue(host.selections.isEmpty())
        c.endBatchEdit()
        assertEquals(listOf(listOf(3, 3, -1, -1)), host.selections)
    }

    @Test
    fun undoTakesBackAWholeWordAndMakesTheInputMethodResync() {
        val host = RecordingHost()
        val c = newComposer(host)
        "hi there".forEachIndexed { i, ch -> c.commitText(ch.toString(), 1, i * 50L) }
        c.undo(1000)
        assertEquals("hi ", c.document.toString())
        assertEquals(1, host.restarts)
        c.redo(1100)
        assertEquals("hi there", c.document.toString())
    }

    @Test
    fun backspaceRemovesAWholeEmoji() {
        val c = newComposer()
        c.commitText("ok👩‍💻", 1, 0)
        c.onKey(Key.BACKSPACE, shift = false, word = false, now = 10)
        assertEquals("ok", c.document.toString())
    }

    @Test
    fun arrowsMoveByCharacterAndShiftExtendsTheSelection() {
        val c = newComposer()
        c.commitText("abc", 1, 0)
        c.onKey(Key.LEFT, false, false, 10)
        assertEquals(Selection(2), c.selection)
        c.onKey(Key.LEFT, true, false, 20)
        assertEquals(Selection(2, 1), c.selection)
        c.onKey(Key.RIGHT, false, false, 30)
        assertEquals(Selection(2), c.selection)
    }

    @Test
    fun upAndDownKeepTheColumnAcrossAShortLine() {
        val c = newComposer()
        // The long lines share their first ten characters, so column 10 is the same x on both.
        c.commitText("same start first\nab\nsame start third", 1, 0)
        c.setSelectionFromInput(10, 10, 10)
        c.onKey(Key.DOWN, false, false, 20)
        assertEquals(19, c.selection.focus)
        c.onKey(Key.DOWN, false, false, 30)
        assertEquals(30, c.selection.focus)
    }

    @Test
    fun aTapPlacesTheCaretAndAsksForTheKeyboard() {
        val host = RecordingHost()
        val c = newComposer(host)
        c.commitText("hello world", 1, 0)
        c.onPointer(PointerEvent(PointerAction.DOWN, 0f, 60f, 1000))
        c.onPointer(PointerEvent(PointerAction.UP, 0f, 60f, 1050))
        assertTrue(c.focused)
        assertEquals(1, host.keyboardShown)
        assertEquals(Selection(0), c.selection)
    }

    @Test
    fun aLongPressSelectsTheWordUnderTheFinger() {
        val c = newComposer()
        c.commitText("hello world", 1, 0)
        c.onPointer(PointerEvent(PointerAction.DOWN, 50f + 18 * 2.625f, 70f, 1000))
        c.tick(1500)
        c.onPointer(PointerEvent(PointerAction.UP, 50f + 18 * 2.625f, 70f, 1600))
        assertEquals(Selection(0, 5), c.selection)
    }

    @Test
    fun aFingerHeldStillAfterALongPressKeepsTheWholeWordSelected() {
        val c = newComposer()
        c.commitText("hello world", 1, 0)
        val x = 50f + 18 * 2.625f
        c.onPointer(PointerEvent(PointerAction.DOWN, x, 70f, 1000))
        c.tick(1500)
        c.onPointer(PointerEvent(PointerAction.MOVE, x, 70f, 1550))
        c.onPointer(PointerEvent(PointerAction.UP, x, 70f, 1600))
        assertEquals(Selection(0, 5), c.selection)
    }

    @Test
    fun draggingAfterALongPressExtendsTheSelectionByWholeWords() {
        val c = newComposer()
        c.commitText("hello brave world", 1, 0)
        val x = 50f + 18 * 2.625f
        c.onPointer(PointerEvent(PointerAction.DOWN, x, 70f, 1000))
        c.tick(1500)
        c.onPointer(PointerEvent(PointerAction.MOVE, 500f, 70f, 1550))
        assertEquals(Selection(0, 17), c.selection)
    }

    @Test
    fun aDragScrollsInsteadOfMovingTheCaret() {
        val c = newComposer(height = 400)
        c.commitText((1..60).joinToString("\n") { "line $it" }, 1, 0)
        c.setSelectionFromInput(0, 0, 0)
        val before = c.selection
        c.onPointer(PointerEvent(PointerAction.DOWN, 200f, 300f, 1000))
        c.onPointer(PointerEvent(PointerAction.MOVE, 200f, 250f, 1016))
        c.onPointer(PointerEvent(PointerAction.MOVE, 200f, 100f, 1032))
        c.onPointer(PointerEvent(PointerAction.UP, 200f, 100f, 1048))
        assertTrue(c.scrollY > 150f)
        assertEquals(before, c.selection)
        val afterDrag = c.scrollY
        c.tick(1100)
        c.tick(1200)
        assertTrue(c.scrollY > afterDrag, "a quick drag keeps scrolling after the finger lifts")
    }

    @Test
    fun selectedTextAndTextAroundTheCursorAreWhatTheInputMethodAsksFor() {
        val c = newComposer()
        c.commitText("hello world", 1, 0)
        c.setSelectionFromInput(6, 11, 10)
        assertEquals("hello ", c.textBeforeCursor(100))
        assertEquals("", c.textAfterCursor(5))
        assertEquals("world", c.selectedText())
        c.setSelectionFromInput(3, 3, 20)
        assertNull(c.selectedText())
        assertEquals("lo", c.textAfterCursor(2))
    }

    @Test
    fun theNextLetterIsCapitalisedAtTheStartOfASentence() {
        val c = newComposer()
        assertEquals(Composer.Capitalisation.SENTENCE, c.capitalisationAtCursor())
        c.commitText("Done. ", 1, 0)
        assertEquals(Composer.Capitalisation.SENTENCE, c.capitalisationAtCursor())
        c.commitText("next ", 1, 10)
        assertEquals(Composer.Capitalisation.WORD, c.capitalisationAtCursor())
        c.commitText("x", 1, 20)
        assertFalse(c.capitalisationAtCursor() == Composer.Capitalisation.SENTENCE)
    }
}
