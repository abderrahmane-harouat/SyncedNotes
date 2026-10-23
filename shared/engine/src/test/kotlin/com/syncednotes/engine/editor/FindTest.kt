package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.render.DrawList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Find in Note: every match marked behind the text, one at a time to go through. */
class FindTest {
    private val style = EditorStyle.macLike(2f)

    private fun composer(text: String): Composer {
        val c = Composer(TestFonts.family, style, RecordingHost())
        c.setViewport(1200, 2000, 0f, 0f)
        c.type(text, 0)
        return c
    }

    /** Whether the frame paints a rectangle of [color]; 1 is a rectangle in the draw list (engine.rs). */
    private fun Composer.paints(color: Int): Boolean {
        val list = DrawList()
        render(list, 0)
        val ints = list.buffer.duplicate().flip().order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return (0 until ints.limit() - 5).any { ints[it] == 1 && ints[it + 5] == color }
    }

    @Test
    fun everyMatchIsFoundWhateverItsCaseOrAccents() {
        val c = composer("Café at noon.\nThe CAFE opens at nine; cafe au lait.")
        assertEquals(3, c.find("cafe"))
        assertEquals(1, c.find("NOON"))
        assertEquals(0, c.find("tea"))
    }

    @Test
    fun theMatchesAreMarkedAndTheCurrentOneStandsOut() {
        val c = composer("one two one")
        assertFalse(c.paints(style.findColor))
        c.find("one")
        assertTrue(c.paints(style.findColor))
        assertTrue(c.paints(style.findCurrentColor))
    }

    @Test
    fun goingThroughTheMatchesWrapsRoundAtTheEnds() {
        val c = composer("a x a x a")
        c.setSelectionFromInput(0, 0, 0)
        c.find("a")
        assertEquals(0, c.currentMatch)
        c.findNext()
        c.findNext()
        assertEquals(2, c.currentMatch)
        c.findNext()
        assertEquals(0, c.currentMatch)
        c.findNext(forward = false)
        assertEquals(2, c.currentMatch)
    }

    @Test
    fun findingChangesNothingInTheNoteAndClosingLeavesTheCaretOnTheMatch() {
        val c = composer("keep this text")
        val before = c.document.text.toString()
        c.find("this")
        c.endFind(0)
        assertEquals(before, c.document.text.toString())
        assertEquals(5, c.selection.start)
        assertEquals(9, c.selection.end)
        assertFalse(c.paints(style.findColor))
    }

    @Test
    fun theMarkersFollowTheTextAsItChanges() {
        val c = composer("one")
        c.find("one")
        c.setSelectionFromInput(3, 3, 0)
        c.type(" and one more", 0)
        assertEquals(2, c.matchCount)
    }
}
