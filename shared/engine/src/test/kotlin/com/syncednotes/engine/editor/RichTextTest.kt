package com.syncednotes.engine.editor

import com.syncednotes.engine.newComposer
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.ValueStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RichTextTest {
    private val red = 0xFFFF3636.toInt()

    @Test
    fun colourAppliesToTheSelectionAndShowsInThePanel() {
        val c = newComposer()
        c.commitText("red text", 1, 0)
        c.setSelectionFromInput(0, 3, 10)
        c.setColor(red, 20)
        assertEquals(red, c.document.valueAt(ValueStyle.COLOR, 0))
        assertNull(c.document.valueAt(ValueStyle.COLOR, 4))
        assertEquals(red, c.formatState().color)
        c.setSelectionFromInput(0, 8, 30)
        assertNull(c.formatState().color, "a mixed selection shows the default colour")
    }

    @Test
    fun aHighlightChosenAtTheCaretColoursWhatIsTypedNext() {
        val c = newComposer()
        c.commitText("plain ", 1, 0)
        c.setHighlight(0xFFFFF400.toInt(), 10)
        c.commitText("marked", 1, 20)
        assertNull(c.document.valueAt(ValueStyle.HIGHLIGHT, 2))
        assertEquals(0xFFFFF400.toInt(), c.document.valueAt(ValueStyle.HIGHLIGHT, 8))
    }

    @Test
    fun aMixedSizeSelectionLeavesTheSizeBlankAndTheDefaultReadsSeventeen() {
        val c = newComposer()
        c.commitText("big small", 1, 0)
        assertEquals(17, c.formatState().size)
        c.setSelectionFromInput(0, 3, 10)
        c.setSize(30, 20)
        assertEquals(30, c.formatState().size)
        c.setSelectionFromInput(0, 9, 30)
        assertNull(c.formatState().size)
    }

    @Test
    fun aLineIsAsTallAsItsLargestText() {
        val c = newComposer()
        c.commitText("one\ntwo", 1, 0)
        val normal = c.caretInView()
        val lineOne = normal[3] - normal[1]
        c.setSelectionFromInput(0, 3, 10)
        c.setSize(40, 20)
        c.setSelectionFromInput(7, 7, 30)
        val after = c.caretInView()
        // The second line moved down by the growth of the first.
        val growth = 40 * c.style.sizeUnit * c.style.lineSpacing - c.style.lineHeight
        assertEquals(normal[2] + growth, after[2], 0.5f)
        assertEquals(lineOne, after[3] - after[1], 0.5f)
    }

    @Test
    fun aChecklistItemTicksAndANewItemStartsUnticked() {
        val c = newComposer()
        c.commitText("milk", 1, 0)
        c.toggleList(ListType.CHECKBOX, 10)
        c.toggleChecked(20)
        assertTrue(c.formatState().checked)
        c.commitText("\n", 1, 30)
        assertEquals(ListType.CHECKBOX, c.document.paragraphStyle(1).list)
        assertFalse(c.document.paragraphStyle(1).checked)
        c.undo(40)
        c.undo(50)
        assertFalse(c.document.paragraphStyle(0).checked)
    }

    @Test
    fun tappingACheckboxTicksItWithoutMovingTheCaret() {
        val c = newComposer()
        c.commitText("eggs", 1, 0)
        c.toggleList(ListType.CHECKBOX, 10)
        val caret = c.selection
        // The checkbox sits in the hanging margin, left of the text.
        val y = c.caretInView()[2] - 5
        val x = c.style.paddingLeft + c.style.listMarkerWidth / 2
        c.onPointer(PointerEvent(PointerAction.DOWN, x, y, 1000))
        c.onPointer(PointerEvent(PointerAction.UP, x, y, 1050))
        assertTrue(c.document.paragraphStyle(0).checked)
        assertEquals(caret, c.selection)
    }

    @Test
    fun choosingTheDefaultSizeAgainStoresNothing() {
        val c = newComposer()
        c.commitText("text", 1, 0)
        c.setSelectionFromInput(0, 4, 10)
        c.setSize(17, 20)
        assertEquals(0, c.document.valueRanges(ValueStyle.SIZE).count)
    }
}
