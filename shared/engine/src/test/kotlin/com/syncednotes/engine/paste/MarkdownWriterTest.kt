package com.syncednotes.engine.paste

import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TextDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A note written out as Markdown for an AI agent, and its Markdown written back into a note. */
class MarkdownWriterTest {
    private fun document(markdown: String) = TextDocument().also { it.appendFragment(MarkdownReader.read(markdown)) }

    @Test
    fun markdownWrittenIntoANoteAndReadOutAgainComesBackTheSame() {
        val markdown = """
            # Plan
            Some **bold**, *italic*, ~~gone~~ and `code`, with **bold *and italic***.

            1. First
            2. Second
                - nested
            - [x] done
            - [ ] not yet
            > quoted
            ```cpp
            int *p = **q; // stars stay
            ```
            ---
            A line with \*stars\* and a # hash.
        """.trimIndent()
        assertEquals(markdown, MarkdownWriter.write(document(markdown)))
    }

    @Test
    fun aTableIsWrittenAsMarkdownsAndReadBackTheSame() {
        val markdown = """
            Rooms:

            | Room | Seats |
            | --- | --- |
            | `lab/` | **12** |
            | a \| b |  |

            after
        """.trimIndent()
        val d = document(markdown)
        assertEquals(listOf("Room", "Seats", "lab/", "12", "a | b", ""), (2..7).map { d.substring(d.paragraphStart(it), d.paragraphEnd(it)) })
        assertEquals(markdown, MarkdownWriter.write(d))
    }

    @Test
    fun aTableAppendedRightAfterAnotherIsATableOfItsOwn() {
        val d = document("| a |\n| --- |\n| b |")
        d.appendFragment(MarkdownReader.read("| c |\n| --- |"))
        assertEquals(listOf(1, 1, 2), (0..2).map { d.paragraphStyle(it).cell!!.table })
    }

    @Test
    fun theNotesStructureIsWhatTheMarkdownSaid() {
        val d = document("## Steps\n1. Build\n```c\nint x;\n```")
        assertEquals(Role.HEADING, d.paragraphStyle(0).role)
        assertEquals(ListType.NUMBER, d.paragraphStyle(1).list)
        assertEquals(CodeLanguage.C, d.paragraphStyle(2).language)
    }

    @Test
    fun aBodyLineThatLooksLikeMarkdownIsEscapedSoItStaysBodyText() {
        val d = TextDocument()
        d.replace(0, 0, "# not a heading\n- not a list\n3. not a number")
        val written = MarkdownWriter.write(d)
        val back = document(written)
        assertEquals("# not a heading\n- not a list\n3. not a number", back.toString())
        assertTrue((0..2).all { back.paragraphStyle(it).role == Role.BODY && back.paragraphStyle(it).list == ListType.NONE })
    }

    @Test
    fun appendingKeepsWhatWasThereAndDoesNotCarryItsBoldOn() {
        val d = TextDocument()
        d.replace(0, 0, "Loud")
        d.setStyle(CharStyle.BOLD, true, 0, 4)
        d.appendFragment(MarkdownReader.read("quiet"))
        assertEquals("Loud\nquiet", d.toString())
        assertTrue(d.hasStyle(CharStyle.BOLD, 0))
        assertTrue(!d.hasStyle(CharStyle.BOLD, 6))
    }
}
