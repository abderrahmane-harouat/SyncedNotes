package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.TestFonts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MouseTest {
    private fun macComposer(): Composer {
        val c = Composer(TestFonts.family, EditorStyle.macLike(2f), RecordingHost())
        c.setViewport(1200, 800, 0f, 0f)
        return c
    }

    /** The middle of the line the caret is on, in view pixels. */
    private fun Composer.lineY(): Float = caretInView().let { (it[1] + it[3]) / 2 }

    private fun Composer.click(x: Float, y: Float, clicks: Int = 1, shift: Boolean = false, now: Long = 0) {
        onMouse(PointerAction.DOWN, x, y, clicks, shift, now)
        onMouse(PointerAction.UP, x, y, clicks, shift, now)
    }

    @Test
    fun aClickPutsTheCaretUnderThePointerAndFocusesTheEditor() {
        val c = macComposer()
        c.type("hello world", 0)
        c.setFocused(false, 0)
        c.click(c.style.paddingLeft, c.lineY())
        assertEquals(Selection(0), c.selection)
        assertTrue(c.focused)
    }

    @Test
    fun draggingSelectsInsteadOfScrolling() {
        val c = macComposer()
        c.type("hello world", 0)
        val y = c.lineY()
        c.onMouse(PointerAction.DOWN, c.style.paddingLeft, y, 1, false, 0)
        c.onMouse(PointerAction.MOVE, 1100f, y, 1, false, 10)
        c.onMouse(PointerAction.UP, 1100f, y, 1, false, 20)
        assertEquals(Selection(0, 11), c.selection)
        assertEquals(0f, c.scrollY)
    }

    @Test
    fun aDoubleClickSelectsTheWordAndDraggingGrowsItWordByWord() {
        val c = macComposer()
        c.type("one two three", 0)
        val y = c.lineY()
        c.onMouse(PointerAction.DOWN, c.style.paddingLeft + 2f, y, 2, false, 10)
        assertEquals(Selection(0, 3), c.selection)
        c.onMouse(PointerAction.MOVE, 1100f, y, 2, false, 20)
        assertEquals(Selection(0, 13), c.selection)
    }

    @Test
    fun aTripleClickSelectsTheParagraph() {
        val c = macComposer()
        c.type("first line\nsecond line", 0)
        c.onMouse(PointerAction.DOWN, c.style.paddingLeft + 4f, c.lineY(), 3, false, 0)
        assertEquals(Selection(11, 22), c.selection)
    }

    @Test
    fun shiftClickExtendsTheSelectionFromWhereItWas() {
        val c = macComposer()
        c.type("hello world", 0)
        c.click(c.style.paddingLeft, c.lineY())
        c.click(1100f, c.lineY(), shift = true)
        assertEquals(Selection(0, 11), c.selection)
    }

    @Test
    fun theWheelScrollsALongNote() {
        val c = macComposer()
        c.type((1..100).joinToString("\n") { "line $it" }, 0)
        c.scrollBy(-1_000_000f)
        assertEquals(0f, c.scrollY)
        c.scrollBy(300f)
        assertEquals(300f, c.scrollY)
    }
}
