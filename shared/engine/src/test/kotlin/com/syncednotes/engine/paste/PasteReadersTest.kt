package com.syncednotes.engine.paste

import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListKind
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TableCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Formatted text as web pages and Notion put it on the clipboard, read into what a note holds.
 * The samples are made up, in the shapes those pages copy as.
 */
class PasteReadersTest {
    private fun Fragment.lines() = paragraphs.map { it.text }
    private fun Fragment.roles() = paragraphs.map { it.style.role }
    private fun Piece.styled(style: CharStyle) = spans.filter { it.style == style }.map { text.substring(it.start, it.end) }
    private fun Fragment.cells() = paragraphs.map { it.style.cell }

    @Test
    fun anAnswerCopiedFromAChatPageKeepsItsHeadingsListsAndCodeInItsLanguage() {
        val html = """
            <meta charset='utf-8'><div class="markdown prose">
            <h2>Reading a file</h2>
            <p>Open it with <code>fopen</code>, and <strong>always</strong> close it.</p>
            <ul><li>Check for <em>NULL</em></li><li>Read a line at a time</li></ul>
            <pre><div class="header"><span>cpp</span><button>Copy code</button></div><code class="hljs language-cpp"><span class="hljs-keyword">int</span> main() {
              return 0;
            }
            </code></pre>
            <p>Done.</p></div>
        """.trimIndent()
        val f = HtmlReader.read(html)
        assertEquals(
            listOf("Reading a file", "Open it with fopen, and always close it.", "", "Check for NULL", "Read a line at a time", "", "int main() {", "  return 0;", "}", "", "Done."),
            f.lines(),
        )
        assertEquals(Role.HEADING, f.paragraphs[0].style.role)
        assertEquals(listOf("fopen"), f.paragraphs[1].styled(CharStyle.CODE))
        assertEquals(listOf("always"), f.paragraphs[1].styled(CharStyle.BOLD))
        assertEquals(ListKind.BULLET, f.paragraphs[3].style.lists.single().kind)
        assertEquals(f.paragraphs[3].style.lists, f.paragraphs[4].style.lists)
        assertEquals(listOf("NULL"), f.paragraphs[3].styled(CharStyle.ITALIC))
        assertTrue((6..8).all { f.paragraphs[it].style.role == Role.CODE_BLOCK && f.paragraphs[it].style.language == CodeLanguage.CPP })
    }

    @Test
    fun nestedNumberedListsAndATaskListBecomeTheNotesListsAndChecklist() {
        val html = "<ol start=\"3\"><li>Third<ol><li>inner</li></ol></li><li>Fourth</li></ol>" +
            "<ul><li><input type=\"checkbox\" checked> bought</li><li><input type=\"checkbox\"> to buy</li></ul>"
        val f = HtmlReader.read(html)
        assertEquals(listOf("Third", "inner", "Fourth", "", "bought", "to buy"), f.lines())
        assertEquals(3, f.paragraphs[0].style.lists.single().start)
        assertEquals(2, f.paragraphs[1].style.depth)
        assertEquals(f.paragraphs[0].style.lists, f.paragraphs[2].style.lists)
        assertEquals(listOf(Role.CHECKLIST, Role.CHECKLIST), f.roles().takeLast(2))
        assertEquals(listOf(true, false), f.paragraphs.takeLast(2).map { it.style.checked })
    }

    @Test
    fun boldGivenByAnInlineStyleCountsAndAWrapperThatUndoesItIsRespected() {
        // Google Docs wraps everything in a <b> whose style says it is not bold.
        val f = HtmlReader.read("<b style=\"font-weight:normal\"><span style=\"font-weight:700\">loud</span> quiet</b>")
        assertEquals(listOf("loud"), f.paragraphs.single().styled(CharStyle.BOLD))
    }

    @Test
    fun aHeadingsOwnBoldIsNotStoredAgainOnItsText() {
        val f = HtmlReader.read("<h1 style=\"font-weight: 600\"><strong>Title</strong></h1>")
        assertEquals(Role.TITLE, f.paragraphs.single().style.role)
        assertTrue(f.paragraphs.single().spans.isEmpty())
    }

    @Test
    fun quotesDividersTablesAndEntitiesAreRead() {
        val f = HtmlReader.read("<blockquote><p>Less &amp; more</p></blockquote><hr><table><tr><th>Name</th><th>Age</th></tr><tr><td><p>Ann</p></td><td>30</td></tr></table>")
        assertEquals(listOf("Less & more", "￼", "Name", "Age", "Ann", "30"), f.lines())
        assertEquals(listOf(Role.QUOTE, Role.DIVIDER, Role.BODY, Role.BODY, Role.BODY, Role.BODY), f.roles())
        assertEquals(listOf(null, null, TableCell(1, 0, 0, true), TableCell(1, 0, 1, true), TableCell(1, 1, 0, true), TableCell(1, 1, 1, true)), f.cells())
        // The header row is bold by being the header, not by a style of its text.
        assertEquals(emptyList(), f.paragraphs[2].styled(CharStyle.BOLD))
    }

    @Test
    fun aTableCopiedFromAnEditorIsATableWithItsHeaderAndTheFormattingOfItsCells() {
        // The shape VS Code and the Claude Code panel copy a rendered Markdown table in.
        val html = """
            <meta charset='utf-8'><p style="white-space: pre-wrap; color: rgb(204, 204, 204); background-color: rgb(31, 31, 31);">Made-up rooms, by <strong>floor</strong>:</p><table style="border-collapse: collapse; background-color: rgb(31, 31, 31);"><thead><tr><th style="padding: 2px;">Room</th><th style="padding: 2px;">Seats</th><th style="padding: 2px;">What it is for</th></tr></thead><tbody><tr><td style="padding: 2px;"><code>lab/</code></td><td style="padding: 2px;">12</td><td style="padding: 2px;">Trying <strong>new</strong> things</td></tr><tr><td style="padding: 2px;">hall</td><td style="padding: 2px;">300</td><td style="padding: 2px;"></td></tr></tbody></table><h3>After the table</h3>
        """.trimIndent()
        val f = HtmlReader.read(html)
        assertEquals(
            listOf("Made-up rooms, by floor:", "", "Room", "Seats", "What it is for", "lab/", "12", "Trying new things", "hall", "300", "", "", "After the table"),
            f.lines(),
        )
        assertEquals((0 until 9).map { TableCell(1, it / 3, it % 3, header = true) }, f.cells().subList(2, 11))
        assertEquals(listOf("lab/"), f.paragraphs[5].styled(CharStyle.CODE))
        assertEquals(listOf("new"), f.paragraphs[7].styled(CharStyle.BOLD))
        assertEquals(Role.SUBHEADING, f.paragraphs[12].style.role)
        assertEquals(null, f.paragraphs[12].style.cell)
    }

    @Test
    fun aRowShortOfCellsIsFilledOutAndACellSpanningColumnsKeepsTheRestInPlace() {
        val f = HtmlReader.read("<table><tr><td colspan=\"2\">wide</td><td>c</td></tr><tr><td>a</td></tr></table>")
        assertEquals(listOf("wide", "", "c", "a", "", ""), f.lines())
        assertEquals(listOf(0, 1, 2, 0, 1, 2), f.cells().map { it!!.column })
        assertEquals(false, f.cells()[0]!!.header)
    }

    @Test
    fun aMarkdownTableIsATableAndABarInCodeOrEscapedStaysInItsCell() {
        val f = MarkdownReader.read("| Name | Pipe |\n|---|:---:|\n| `a|b` | x \\| y |\n| **bold** |\n\nafter")
        assertEquals(listOf("Name", "Pipe", "a|b", "x | y", "bold", "", "", "after"), f.lines())
        assertEquals(listOf(true, true), f.cells().take(2).map { it!!.header })
        assertEquals(listOf(2, 2), f.cells().subList(4, 6).map { it!!.row })
        assertEquals(listOf("bold"), f.paragraphs[4].styled(CharStyle.BOLD))
        assertEquals(listOf("a|b"), f.paragraphs[2].styled(CharStyle.CODE))
    }

    @Test
    fun picturesScriptsAndStylesInThePageAreLeftOut() {
        val f = HtmlReader.read("<style>p{color:red}</style><p>before<img src=\"https://example.com/x.png\">after</p><script>alert(1)</script>")
        assertEquals(listOf("beforeafter"), f.lines())
    }

    @Test
    fun aLineBreakStartsANewLineAndSpacesRunTogetherAsOnThePage() {
        val f = HtmlReader.read("<p>one   two\n three<br>four</p>")
        assertEquals(listOf("one two three", "four"), f.lines())
    }

    @Test
    fun markdownFromACopyButtonKeepsItsStructure() {
        val md = """
            # Plan
            Some **bold** and *italic* and `code` and a [link](https://example.com).

            1. First
            2. Second
               - nested
            - [x] done
            - [ ] not yet

            > quoted

            ```c
            int x = 1;
            ```
            ---
            | a | b |
            |---|---|
            | 1 | 2 |
        """.trimIndent()
        assertTrue(MarkdownReader.looksLikeMarkdown(md))
        val f = MarkdownReader.read(md)
        assertEquals(
            listOf(
                "Plan", "Some bold and italic and code and a link.", "", "First", "Second", "nested", "done", "not yet", "",
                "quoted", "", "int x = 1;", "￼", "a", "b", "1", "2",
            ),
            f.lines(),
        )
        assertEquals(Role.TITLE, f.paragraphs[0].style.role)
        val line = f.paragraphs[1]
        assertEquals(listOf("bold"), line.styled(CharStyle.BOLD))
        assertEquals(listOf("italic"), line.styled(CharStyle.ITALIC))
        assertEquals(listOf("code"), line.styled(CharStyle.CODE))
        assertEquals(ListKind.NUMBER, f.paragraphs[3].style.lists.single().kind)
        assertEquals(2, f.paragraphs[5].style.depth)
        assertEquals(listOf(Role.CHECKLIST, Role.CHECKLIST), listOf(f.paragraphs[6].style.role, f.paragraphs[7].style.role))
        assertEquals(listOf(true, false), listOf(f.paragraphs[6].style.checked, f.paragraphs[7].style.checked))
        assertEquals(Role.QUOTE, f.paragraphs[9].style.role)
        assertEquals(CodeLanguage.C, f.paragraphs[11].style.language)
        assertEquals(Role.DIVIDER, f.paragraphs[12].style.role)
        // A table's header row is bold by being the header, not by a style of its text.
        assertEquals(listOf(true, true), listOf(f.paragraphs[13].style.cell!!.header, f.paragraphs[14].style.cell!!.header))
        assertEquals(emptyList(), f.paragraphs[13].styled(CharStyle.BOLD))
    }

    @Test
    fun codeInAFenceIsKeptAsItIsWithItsStarsAndHashes() {
        val f = MarkdownReader.read("```cpp\n# define X\nint *p = **q;\n```")
        assertEquals(listOf("# define X", "int *p = **q;"), f.lines())
        assertTrue(f.paragraphs.all { it.style.role == Role.CODE_BLOCK && it.style.language == CodeLanguage.CPP && it.spans.isEmpty() })
    }

    @Test
    fun plainTextWithAStarOrAnUnderscoreIsNotTakenForMarkdown() {
        assertFalse(MarkdownReader.looksLikeMarkdown("2*3*4 is 24, see my_file_name.txt"))
        assertNull(Pasted.read("2*3*4 is 24", null))
        val f = MarkdownReader.read("snake_case_name and a * b * c")
        assertTrue(f.paragraphs.single().spans.isEmpty())
    }

    @Test
    fun codeTheHtmlLeftUnnamedTakesTheLanguageItsMarkdownFenceGives() {
        val f = Pasted.read("```cpp\nint x;\n```", "<pre><code>int x;</code></pre>")!!
        assertEquals(CodeLanguage.CPP, f.paragraphs.single().style.language)
    }

    @Test
    fun theHtmlIsPreferredToTheTextBesideIt() {
        val f = Pasted.read("Title\nbody", "<h2>Title</h2><p>body</p>")!!
        assertEquals(Role.HEADING, f.paragraphs.first().style.role)
    }
}
