package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TableCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tables: each cell a paragraph of the note, a row's cells side by side, the table kept whole. */
class TableTest {
    private fun composer(): Composer {
        val c = Composer(TestFonts.family, EditorStyle.macLike(2f), RecordingHost())
        c.setViewport(1200, 2000, 0f, 0f)
        return c
    }

    /** A table of [rows], typed cell after cell as a person would, Tab between them. */
    private fun table(vararg rows: List<String>): Composer {
        val c = composer()
        c.insertTable(rows.size, rows[0].size, 0)
        val cells = rows.flatMap { it }
        for ((k, text) in cells.withIndex()) {
            c.type(text, 0)
            if (k < cells.size - 1) c.onKey(Key.TAB, false, false, 0)
        }
        return c
    }

    private fun Composer.cell(p: Int) = document.paragraphStyle(p).cell
    private fun Composer.start(p: Int) = document.paragraphStart(p)
    private fun Composer.end(p: Int) = document.paragraphEnd(p)
    private fun Composer.texts() = (0 until document.paragraphCount).map { document.substring(start(it), end(it)) }
    private fun Composer.caretAt(offset: Int): FloatArray {
        setSelectionFromInput(offset, offset, 0)
        return caretInView()
    }
    private fun Composer.caretParagraph() = document.paragraphAt(selection.focus)
    private fun Composer.place(offset: Int) = setSelectionFromInput(offset, offset, 0)

    @Test
    fun aTableInsertedOnAnEmptyLineIsRowsOfCellsWithAHeaderAndALineAfterIt() {
        val c = composer()
        c.insertTable(2, 3, 0)
        assertEquals(7, c.document.paragraphCount)
        assertEquals((0 until 6).map { TableCell(1, it / 3, it % 3, header = true) }, (0 until 6).map { c.cell(it) })
        assertNull(c.cell(6))
        assertEquals(0, c.selection.focus)
        assertTrue(c.formatState().inTable)
    }

    @Test
    fun aTableInsertedAfterTextGoesBelowItsLine() {
        val c = composer()
        c.type("Files in Blink", 0)
        c.insertTable(1, 2, 0)
        assertEquals(listOf("Files in Blink", "", "", ""), c.texts())
        assertEquals(listOf(null, 0, 1, null), (0..3).map { c.cell(it)?.column })
        assertEquals(1, c.caretParagraph())
    }

    @Test
    fun aRowsCellsSitSideBySideAndTheNextRowIsBelowThem() {
        val c = table(listOf("Folder", "Files"), listOf("renderer/", "19,005"))
        val a = c.caretAt(c.start(0))
        val b = c.caretAt(c.start(1))
        val d = c.caretAt(c.start(2))
        assertEquals(a[1], b[1])
        assertTrue(b[0] > a[0] + 100, "${b[0]} beside ${a[0]}")
        assertTrue(d[1] > a[1])
        assertEquals(a[0], d[0])
    }

    @Test
    fun aLongCellWrapsInsideTheNoteWhileAShortColumnStaysNarrow() {
        val long = "The engine itself, the code we have been reading all week, and far more of it than anyone could read in a month"
        val c = table(listOf("core/", long), listOf("x", "y"))
        val first = c.caretAt(c.start(1))
        val last = c.caretAt(c.end(1))
        assertTrue(last[1] > first[1], "the long cell wraps")
        assertTrue(last[0] < 1200, "and stays inside the note")
        assertTrue(first[0] - c.caretAt(c.start(0))[0] < 400, "the short column is not given half the note")
    }

    @Test
    fun tabGoesToTheNextCellAndAfterTheLastAddsARow() {
        val c = table(listOf("a", "b"), listOf("c", "d"))
        c.place(c.end(0))
        c.onKey(Key.TAB, false, false, 0)
        assertEquals(1, c.caretParagraph())
        c.onKey(Key.TAB, true, false, 0)
        assertEquals(0, c.caretParagraph())
        c.place(c.end(3))
        c.onKey(Key.TAB, false, false, 0)
        assertEquals(listOf("a", "b", "c", "d", "", "", ""), c.texts())
        assertEquals(TableCell(1, 2, 0, header = true), c.cell(c.caretParagraph()))
    }

    @Test
    fun returnInACellGoesToTheCellBelowAndNeverSplitsIt() {
        val c = table(listOf("a", "b"), listOf("c", "d"))
        c.place(c.end(1))
        c.type("\n", 0)
        assertEquals(3, c.caretParagraph())
        assertEquals(listOf("a", "b", "c", "d", ""), c.texts())
        c.commitText("one\ntwo", 1, 0)
        assertEquals("done two", c.texts()[3])
    }

    @Test
    fun backspaceAtTheStartOfACellNeverJoinsItToTheCellBefore() {
        val c = table(listOf("a", "b"), listOf("c", "d"))
        c.place(c.start(2))
        c.onKey(Key.BACKSPACE, false, false, 0)
        assertEquals(listOf("a", "b", "c", "d", ""), c.texts())
        assertEquals(c.end(1), c.selection.focus)
        // Delete at the end of the last cell takes the empty line after the table, never a cell.
        c.place(c.end(3))
        c.onKey(Key.DELETE, false, false, 0)
        assertEquals(listOf("a", "b", "c", "d"), c.texts())
        c.place(c.end(1))
        c.onKey(Key.DELETE, false, false, 0)
        assertEquals(listOf("a", "b", "c", "d"), c.texts())
    }

    @Test
    fun backspaceOnTheEmptyLineAfterATableTakesTheLineAway() {
        val c = table(listOf("a", "b"))
        c.type("", 0)
        c.place(c.start(2))
        c.type("x", 0)
        c.onKey(Key.BACKSPACE, false, false, 0)
        c.onKey(Key.BACKSPACE, false, false, 0)
        assertEquals(listOf("a", "b"), c.texts())
        assertEquals(TableCell(1, 0, 1, header = true), c.cell(1))
    }

    @Test
    fun deletingASelectionAcrossCellsEmptiesThemAndKeepsTheGrid() {
        val c = table(listOf("alpha", "beta"), listOf("gamma", "delta"))
        c.setSelectionFromInput(2, c.start(2) + 3, 0)
        c.onKey(Key.BACKSPACE, false, false, 0)
        assertEquals(listOf("al", "", "ma", "delta", ""), c.texts())
        assertEquals(listOf(0, 0, 1, 1), (0..3).map { c.cell(it)!!.row })
        c.undo(0)
        assertEquals(listOf("alpha", "beta", "gamma", "delta", ""), c.texts())
        c.setSelectionFromInput(2, c.start(2) + 3, 0)
        c.type("z", 0)
        assertEquals(listOf("alz", "", "ma", "delta", ""), c.texts())
    }

    @Test
    fun aWholeTableSelectedWithTheTextAroundItIsDeletedAsLinesAre() {
        val c = composer()
        c.type("before\n", 0)
        c.insertTable(1, 2, 0)
        c.type("x", 0)
        c.place(c.end(3))
        c.type("after", 0)
        c.setSelectionFromInput(3, c.start(3) + 2, 0)
        c.onKey(Key.BACKSPACE, false, false, 0)
        assertEquals(listOf("befter"), c.texts())
        assertNull(c.cell(0))
    }

    @Test
    fun aColumnAddedBetweenTwoKeepsEveryCellsTextAndBold() {
        val c = table(listOf("a", "b"), listOf("c", "d"))
        c.setSelectionFromInput(c.start(2), c.start(2) + 1, 0)
        c.toggle(CharStyle.BOLD, 0)
        c.place(c.start(0))
        c.addColumn(right = true, 0)
        assertEquals(listOf("a", "", "b", "c", "", "d", ""), c.texts())
        assertEquals((0 until 6).map { TableCell(1, it / 3, it % 3, header = true) }, (0 until 6).map { c.cell(it) })
        assertTrue(c.document.hasStyle(CharStyle.BOLD, c.start(3)))
        assertEquals(1, c.caretParagraph())
        c.undo(0)
        assertEquals(listOf("a", "b", "c", "d", ""), c.texts())
        assertTrue(c.document.hasStyle(CharStyle.BOLD, c.start(2)))
    }

    @Test
    fun aRowAddedAboveTheFirstBecomesTheHeaderRow() {
        val c = table(listOf("a", "b"), listOf("c", "d"))
        c.place(c.start(0))
        c.addRow(below = false, 0)
        assertEquals(listOf("", "", "a", "b", "c", "d", ""), c.texts())
        assertEquals(listOf(0, 0, 1, 1, 2, 2), (0..5).map { c.cell(it)!!.row })
        assertEquals(0, c.caretParagraph())
    }

    @Test
    fun deletingRowsAndColumnsTakesTheirCellsAndTheLastTakesTheTable() {
        val c = table(listOf("a", "b"), listOf("c", "d"))
        c.place(c.start(3))
        c.deleteRow(0)
        assertEquals(listOf("a", "b", ""), c.texts())
        c.deleteColumn(0)
        assertEquals(listOf("a", ""), c.texts())
        c.deleteColumn(0)
        assertEquals(listOf(""), c.texts())
        assertNull(c.cell(0))
    }

    @Test
    fun theHeaderRowCanBeTurnedOffAndOnAgain() {
        val c = table(listOf("a", "b"), listOf("c", "d"))
        assertTrue(c.formatState().tableHeader)
        c.toggleHeaderRow(0)
        assertEquals(false, c.cell(0)!!.header)
        assertEquals(false, c.cell(3)!!.header)
        c.toggleHeaderRow(0)
        assertTrue(c.cell(2)!!.header)
    }

    @Test
    fun theDownArrowGoesToTheCellBelowNotTheOneBeside() {
        val c = table(listOf("a", "b"), listOf("c", "d"))
        c.place(c.end(1))
        c.onKey(Key.DOWN, false, false, 0)
        assertEquals(3, c.caretParagraph())
        c.onKey(Key.DOWN, false, false, 0)
        assertEquals(4, c.caretParagraph())
        c.onKey(Key.UP, false, false, 0)
        assertEquals(3, c.caretParagraph())
        c.onKey(Key.UP, false, false, 0)
        assertEquals(1, c.caretParagraph())
    }

    @Test
    fun goingDownFromATableAtTheEndOfTheNoteAddsALineBelowIt() {
        val c = table(listOf("a", "b"))
        c.deleteTable(0)
        c.insertTable(1, 2, 0)
        c.place(c.end(2))
        c.onKey(Key.BACKSPACE, false, false, 0)
        assertEquals(2, c.document.paragraphCount)
        c.place(c.end(0))
        c.onKey(Key.DOWN, false, false, 0)
        assertEquals(3, c.document.paragraphCount)
        assertNull(c.cell(c.caretParagraph()))
    }

    @Test
    fun aClickInACellPutsTheCaretInThatCell() {
        val c = table(listOf("Folder", "Files"), listOf("renderer/", "19,005"))
        val target = c.caretAt(c.start(3) + 2)
        c.place(0)
        c.onPointer(PointerEvent(PointerAction.DOWN, target[0], target[2] - 2, 1000))
        c.onPointer(PointerEvent(PointerAction.UP, target[0], target[2] - 2, 1010))
        assertEquals(c.start(3) + 2, c.selection.focus)
    }

    @Test
    fun copiedCellsHaveTabsBetweenThemAndLinesBetweenRows() {
        val c = table(listOf("a", "b"), listOf("c", "d"))
        c.setSelectionFromInput(0, c.end(3), 0)
        assertEquals("a\tb\nc\td", c.selectedText())
    }

    @Test
    fun aTablePastedInTheMiddleOfALineGoesOnLinesOfItsOwn() {
        val c = composer()
        c.type("before after", 0)
        c.place(6)
        c.paste("A\tB\n1\t2", "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>", 0)
        assertEquals(listOf("before", "A", "B", "1", "2", " after"), c.texts())
        assertEquals(listOf(null, 0, 1, 0, 1, null), (0..5).map { c.cell(it)?.column })
        assertTrue(c.cell(1)!!.header)
    }

    @Test
    fun aTablePastedAtTheEndOfTheNoteHasALineAfterItToGoOnIn() {
        val c = composer()
        c.paste(null, "<table><tr><td>x</td><td>y</td></tr></table>", 0)
        assertEquals(listOf("x", "y", ""), c.texts())
        assertNull(c.cell(2))
        assertEquals(2, c.caretParagraph())
    }

    @Test
    fun whatIsPastedIntoACellIsItsTextOnOneLine() {
        val c = table(listOf("a", "b"))
        c.place(1)
        c.paste("x\ny", "<h2>x</h2><p>y</p>", 0)
        assertEquals(listOf("ax y", "b", ""), c.texts())
        assertEquals(Role.BODY, c.document.paragraphStyle(0).role)
    }

    @Test
    fun headingsListsAndCalloutsLeaveACellBodyText() {
        val c = table(listOf("a", "b"))
        c.place(0)
        c.toggleRole(Role.HEADING, 0)
        c.toggleList(com.syncednotes.engine.text.ListType.BULLET, 0)
        c.toggleCallout(com.syncednotes.engine.text.CalloutColor.BLUE, 0)
        assertEquals(Role.BODY, c.document.paragraphStyle(0).role)
        assertTrue(c.document.paragraphStyle(0).lists.isEmpty())
    }

    @Test
    fun aDividerInsertedFromACellGoesBelowTheTable() {
        val c = table(listOf("a", "b"))
        c.place(0)
        c.insertDivider(0)
        assertEquals(listOf("a", "b", "￼", "", ""), c.texts())
        assertEquals(Role.DIVIDER, c.document.paragraphStyle(2).role)
        assertEquals(TableCell(1, 0, 1, header = true), c.cell(1))
    }
}
