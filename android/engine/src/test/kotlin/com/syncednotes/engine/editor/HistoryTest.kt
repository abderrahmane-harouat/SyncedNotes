package com.syncednotes.engine.editor

import com.syncednotes.engine.text.TextDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryTest {
    private val sel = Selection(0)
    private val format = TextDocument().snapshot()

    private fun History.record(start: Int, removed: String, inserted: String, before: Selection, after: Selection, time: Long, mergeable: Boolean) =
        record(start, removed, inserted, before, after, format, time, mergeable)

    @Test
    fun typingAWordLetterByLetterIsOneStep() {
        val h = History(format)
        "word".forEachIndexed { i, c -> h.record(i, "", c.toString(), sel, sel, i * 100L, true) }
        val e = h.undo()!!
        assertEquals("word", e.inserted)
        assertNull(h.undo())
    }

    @Test
    fun aNewWordAfterASpaceIsItsOwnStep() {
        val h = History(format)
        "ab cd".forEachIndexed { i, c -> h.record(i, "", c.toString(), sel, sel, i * 100L, true) }
        assertEquals("cd", h.undo()!!.inserted)
        assertEquals("ab ", h.undo()!!.inserted)
    }

    @Test
    fun anInputMethodRewritingItsComposingWordIsStillOneStep() {
        val h = History(format)
        h.record(0, "", "teh", sel, sel, 0, true)
        h.record(0, "teh", "the", sel, sel, 100, true)
        h.record(3, "", " ", sel, sel, 200, true)
        val e = h.undo()!!
        assertEquals("the ", e.inserted)
        assertEquals("", e.removed)
    }

    @Test
    fun backspacingThroughOldTextIsOneStep() {
        val h = History(format)
        h.record(4, "d", "", sel, sel, 0, true)
        h.record(3, "c", "", sel, sel, 100, true)
        h.record(2, "b", "", sel, sel, 200, true)
        val e = h.undo()!!
        assertEquals(2, e.start)
        assertEquals("bcd", e.removed)
    }

    @Test
    fun aPauseOrACursorMoveStartsANewStep() {
        val h = History(format)
        h.record(0, "", "a", sel, sel, 0, true)
        h.record(1, "", "b", sel, sel, 5000, true)
        h.seal()
        h.record(2, "", "c", sel, sel, 5100, true)
        assertEquals("c", h.undo()!!.inserted)
        assertEquals("b", h.undo()!!.inserted)
        assertEquals("a", h.undo()!!.inserted)
    }
}
