package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pasting text from elsewhere into a note, formatted as it was there. */
class PasteTest {
    private fun composer(text: String = ""): Composer {
        val c = Composer(TestFonts.family, EditorStyle.macLike(2f), RecordingHost())
        c.setViewport(1200, 2000, 0f, 0f)
        if (text.isNotEmpty()) c.type(text, 0)
        return c
    }

    private fun Composer.style(p: Int) = document.paragraphStyle(p)

    @Test
    fun aPageCopiedIntoAnEmptyNoteKeepsItsHeadingsBoldListsAndCode() {
        val c = composer()
        c.paste("ignored", "<h2>Setup</h2><p>Run <strong>this</strong>:</p><pre><code class=\"language-c\">int main(void);</code></pre><ul><li>one</li></ul>", 0)
        assertEquals("Setup\nRun this:\n\nint main(void);\n\none", c.document.toString())
        assertEquals(Role.HEADING, c.style(0).role)
        assertTrue(c.document.hasStyle(CharStyle.BOLD, c.document.paragraphStart(1) + 5))
        assertFalse(c.document.hasStyle(CharStyle.BOLD, c.document.paragraphStart(1)))
        assertEquals(Role.CODE_BLOCK, c.style(3).role)
        assertEquals(CodeLanguage.C, c.style(3).language)
        assertEquals(ListType.BULLET, c.style(5).list)
    }

    @Test
    fun oneUndoTakesAWholePasteAwayTextAndFormattingTogether() {
        val c = composer("Notes")
        c.setSelectionFromInput(5, 5, 0)
        c.type("\n", 0)
        c.paste(null, "<h2>Setup</h2><ul><li>one</li></ul>", 1000)
        c.undo(2000)
        assertEquals("Notes\n", c.document.toString())
        assertEquals(Role.BODY, c.style(1).role)
        assertEquals(ListType.NONE, c.style(1).list)
    }

    @Test
    fun aHeadingPastedIntoTheMiddleOfASentenceLeavesTheSentenceBodyText() {
        val c = composer("before after")
        c.setSelectionFromInput(7, 7, 0)
        c.paste("Big", "<h1>Big</h1>", 0)
        assertEquals("before Bigafter", c.document.toString())
        assertEquals(Role.BODY, c.style(0).role)
    }

    @Test
    fun pastedBoldTextDoesNotSpreadTheBoldAroundTheCaretToTheRest() {
        val c = composer("bold")
        c.setSelectionFromInput(0, 4, 0)
        c.toggle(CharStyle.BOLD, 0)
        c.setSelectionFromInput(4, 4, 0)
        c.paste(" and *plain*", null, 0)
        // Not Markdown enough to be read as such: pasted as the text it is.
        assertEquals("bold and *plain*", c.document.toString())
        c.paste(null, "<p> then <em>this</em></p>", 0)
        assertFalse(c.document.hasStyle(CharStyle.BOLD, c.document.length - 1))
        assertTrue(c.document.hasStyle(CharStyle.ITALIC, c.document.length - 1))
    }

    @Test
    fun intoACodeBlockWhatIsPastedIsCodeAsItIs() {
        val c = composer("x")
        c.toggleCodeBlock(CodeLanguage.CPP, 0)
        c.setSelectionFromInput(1, 1, 0)
        c.paste("\n# not a title\n**p = 0;", "<h1>not a title</h1>", 0)
        assertEquals("x\n# not a title\n**p = 0;", c.document.toString())
        assertTrue((0..2).all { c.style(it).role == Role.CODE_BLOCK && c.style(it).language == CodeLanguage.CPP })
        assertTrue(c.document.styleRanges(CharStyle.BOLD).count == 0)
    }

    @Test
    fun plainLinesPastedIntoAListGoOnWithTheList() {
        val c = composer("first")
        c.toggleList(ListType.BULLET, 0)
        c.setSelectionFromInput(5, 5, 0)
        c.paste("\nsecond\nthird", null, 0)
        assertEquals(listOf(ListType.BULLET, ListType.BULLET, ListType.BULLET), (0..2).map { c.style(it).list })
    }

    @Test
    fun aPastedListIsANewListNotOneAlreadyInTheNote() {
        val c = composer("mine")
        c.toggleList(ListType.NUMBER, 0)
        c.setSelectionFromInput(4, 4, 0)
        c.type("\n\n", 0)
        c.paste(null, "<ol><li>theirs</li></ol>", 0)
        assertTrue(c.style(0).lists.single().id != c.style(2).lists.single().id)
    }

    @Test
    fun markdownFromACopyButtonBecomesFormattedText() {
        val c = composer()
        c.paste("## Steps\n1. Build\n2. Test\n\n```cpp\nauto x = 1;\n```", null, 0)
        assertEquals("Steps\nBuild\nTest\n\nauto x = 1;", c.document.toString())
        assertEquals(Role.HEADING, c.style(0).role)
        assertEquals(ListType.NUMBER, c.style(2).list)
        assertEquals(CodeLanguage.CPP, c.style(4).language)
    }
}
