package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.render.DrawList
import com.syncednotes.engine.text.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pictures: a paragraph each, shown at their size, and always taken whole. */
class PictureTest {
    private val style = EditorStyle.macLike(1f)

    /** A made-up picture, 400 × 300 points, and the frames it was asked for. */
    private class Source : PictureSource {
        val asked = mutableListOf<Triple<Int, Int, Int>>()
        override fun size(picture: Int) = 400 to 300
        override fun image(picture: Int, width: Int, height: Int): Int? {
            asked += Triple(picture, width, height)
            return null
        }
    }

    private val source = Source()

    private fun composer(text: String, width: Int = 1000): Composer {
        val c = Composer(TestFonts.family, style, RecordingHost())
        c.pictures = source
        c.setViewport(width, 2000, 0f, 0f)
        c.type(text, 0)
        return c
    }

    private fun Composer.caretTo(offset: Int) = setSelectionFromInput(offset, offset, 0)
    private fun Composer.texts() = (0 until document.paragraphCount).map { document.substring(document.paragraphStart(it), document.paragraphEnd(it)) }
    private fun Composer.draw() = render(DrawList(), 0)

    @Test
    fun aPictureGoesBelowTheParagraphAsOneOfItsOwnWithTheCaretAfterIt() {
        val c = composer("Above\nBelow")
        c.caretTo(2)
        c.insertPicture(7, 0)
        assertEquals(listOf("Above", "￼", "", "Below"), c.texts())
        assertEquals(7, c.document.paragraphStyle(1).picture)
        assertEquals(0, c.document.paragraphStyle(2).picture)
        assertEquals(c.document.paragraphStart(2), c.selection.start)
    }

    @Test
    fun anEmptyLineTakesThePictureItself() {
        val c = composer("Above\n")
        c.insertPicture(7, 0)
        assertEquals(listOf("Above", "￼", ""), c.texts())
    }

    @Test
    fun aPictureIsShownAtItsSizeAndShrunkToANarrowerNote() {
        composer("x", width = 1000).apply { caretTo(1); insertPicture(7, 0); draw() }
        assertEquals(Triple(7, 400, 300), source.asked.last())
        // 200 wide less the margins of 20 each: 160 × 120.
        composer("x", width = 200).apply { caretTo(1); insertPicture(7, 0); draw() }
        assertEquals(Triple(7, 160, 120), source.asked.last())
    }

    @Test
    fun backspaceAfterAPictureTakesItWholeAndTheLineBelowKeepsItsStyle() {
        val c = composer("Above\nHeading")
        c.caretTo(5)
        c.insertPicture(7, 0)
        // Above, picture, empty, Heading: make the last a heading, then backspace twice from it.
        c.caretTo(c.document.length)
        c.toggleRole(Role.HEADING, 0)
        c.caretTo(c.document.paragraphStart(2))
        c.onKey(Key.BACKSPACE, false, false, 0)
        assertEquals(listOf("Above", "￼", "Heading"), c.texts())
        c.onKey(Key.BACKSPACE, false, false, 0)
        assertEquals(listOf("Above", "Heading"), c.texts())
        assertEquals(Role.HEADING, c.document.paragraphStyle(1).role)
        assertEquals(0, c.document.paragraphStyle(1).picture)
    }

    @Test
    fun typingBesideAPictureGoesOnALineOfItsOwn() {
        val c = composer("Above")
        c.insertPicture(7, 0)
        val picture = c.document.paragraphStart(1)
        c.caretTo(picture)
        c.type("before", 0)
        c.caretTo(c.document.paragraphEnd(2))
        c.type("after", 0)
        assertEquals(listOf("Above", "before", "￼", "after", ""), c.texts())
        assertEquals(7, c.document.paragraphStyle(2).picture)
        assertFalse(c.document.paragraphStyle(1).isObject)
        assertFalse(c.document.paragraphStyle(3).isObject)
    }

    @Test
    fun undoBringsAPictureBack() {
        val c = composer("Above")
        c.insertPicture(7, 0)
        c.caretTo(c.document.paragraphEnd(1))
        c.onKey(Key.BACKSPACE, false, false, 0)
        assertTrue(c.texts().none { it == "￼" })
        c.undo(10)
        assertEquals(7, c.document.paragraphStyle(1).picture)
    }
}
