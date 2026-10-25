package com.syncednotes.engine.editor

import com.syncednotes.engine.RecordingHost
import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.paste.HtmlReader
import com.syncednotes.engine.paste.MarkdownReader
import com.syncednotes.engine.paste.MarkdownWriter
import com.syncednotes.engine.paste.appendFragment
import com.syncednotes.engine.text.CalloutColor
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TextDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Callouts: text set apart on a coloured panel, a run of same-coloured lines one callout. */
class CalloutTest {
    private fun composer(text: String): Composer {
        val c = Composer(TestFonts.family, EditorStyle.macLike(2f), RecordingHost())
        c.setViewport(1200, 2000, 0f, 0f)
        c.type(text, 0)
        return c
    }

    private fun Composer.style(p: Int) = document.paragraphStyle(p)
    private fun Composer.lineTop(offset: Int): Float {
        setSelectionFromInput(offset, offset, 0)
        return caretInView()[1]
    }

    @Test
    fun choosingAColourMakesTheSelectedLinesOneCalloutOfIt() {
        val c = composer("Stack\nx = 20\nafter")
        c.setSelectionFromInput(0, 12, 0)
        c.toggleCallout(CalloutColor.BLUE, 0)
        assertEquals(listOf(Role.CALLOUT, Role.CALLOUT, Role.BODY), (0..2).map { c.style(it).role })
        assertEquals(CalloutColor.BLUE, c.formatState().calloutColor)
    }

    @Test
    fun anotherColourWithTheCaretInACalloutRecoloursTheWholeOfIt() {
        val c = composer("Stack\nx = 20\nafter")
        c.setSelectionFromInput(0, 12, 0)
        c.toggleCallout(CalloutColor.BLUE, 0)
        c.setSelectionFromInput(8, 8, 0)
        c.toggleCallout(CalloutColor.GREEN, 0)
        assertEquals(listOf(CalloutColor.GREEN, CalloutColor.GREEN), (0..1).map { c.style(it).callout })
        assertEquals(Role.BODY, c.style(2).role)
    }

    @Test
    fun choosingTheColourItIsPutsItBackToBodyText() {
        val c = composer("aside")
        c.toggleCallout(CalloutColor.PURPLE, 0)
        c.toggleCallout(CalloutColor.PURPLE, 0)
        assertEquals(Role.BODY, c.style(0).role)
    }

    @Test
    fun returnGoesOnInTheCalloutAndReturnOnAnEmptyLineLeavesIt() {
        val c = composer("aside")
        c.toggleCallout(CalloutColor.BLUE, 0)
        c.setSelectionFromInput(5, 5, 0)
        c.type("\n", 0)
        assertEquals(Role.CALLOUT, c.style(1).role)
        c.type("\n", 0)
        assertEquals(Role.BODY, c.style(1).role)
        assertEquals("aside\n", c.document.toString())
    }

    @Test
    fun aCalloutsTextKeepsClearOfItsPanelAndCalloutsOfTwoColoursAreTwoPanels() {
        val c = composer("before\none\ntwo\nthree\nafter")
        c.setSelectionFromInput(7, 14, 0)
        c.toggleCallout(CalloutColor.BLUE, 0)
        c.setSelectionFromInput(15, 15, 0)
        c.toggleCallout(CalloutColor.GREEN, 0)
        val line = c.style.lineHeight
        val pad = c.style.calloutMargin + c.style.calloutPaddingVertical
        // Room above the first line of each callout, none between the lines of one.
        assertTrue(c.lineTop(7) - c.lineTop(0) > line + pad - 1f)
        assertEquals(line, c.lineTop(11) - c.lineTop(7), 1f)
        assertTrue(c.lineTop(15) - c.lineTop(11) > line + 2 * pad - 1f)
        // And the text keeps clear of the panel's side.
        c.setSelectionFromInput(7, 7, 0)
        assertEquals(c.style.paddingLeft + c.style.calloutPadding, c.caretInView()[0], 1f)
    }

    @Test
    fun aCalloutIsWrittenAsMarkdownAlertsAndReadBackTheSame() {
        val markdown = "Before\n> [!NOTE]\n> **Stack**\n> `x = 20` is local\n> [!TIP]\n> - one\n> - two\nAfter"
        val doc = TextDocument().also { it.appendFragment(MarkdownReader.read(markdown)) }
        assertEquals(listOf(Role.BODY, Role.CALLOUT, Role.CALLOUT, Role.CALLOUT, Role.CALLOUT, Role.BODY), (0 until doc.paragraphCount).map { doc.paragraphStyle(it).role })
        assertEquals(CalloutColor.GREEN, doc.paragraphStyle(3).callout)
        assertEquals(ListType.BULLET, doc.paragraphStyle(4).list)
        assertEquals(doc.paragraphStyle(3).lists, doc.paragraphStyle(4).lists)
        assertEquals(markdown, MarkdownWriter.write(doc))
    }

    @Test
    fun anObsidianCalloutsTitleIsItsFirstLineInBold() {
        val doc = TextDocument().also { it.appendFragment(MarkdownReader.read("> [!warning] Careful\n> the stove is hot")) }
        assertEquals("Careful\nthe stove is hot", doc.toString())
        assertEquals(CalloutColor.ORANGE, doc.paragraphStyle(0).callout)
        assertTrue(doc.hasStyle(CharStyle.BOLD, 0))
    }

    @Test
    fun aColouredBoxOnAPastedPageIsACalloutOfTheNearestColour() {
        val f = HtmlReader.read(
            "<p>Regions:</p>" +
                "<div style=\"background-color: rgb(227, 237, 253); padding: 12px\"><h3>Stack</h3><p><code>x = 20</code> is local.</p></div>" +
                "<div style=\"background: #e3f4e7\"><p>Heap</p></div>" +
                "<div class=\"callout block-color-purple_background\"><p>Notion's</p></div>",
        )
        val callouts = f.paragraphs.filter { it.style.role == Role.CALLOUT }
        assertEquals(listOf("Stack", "x = 20 is local.", "Heap", "Notion's"), callouts.map { it.text })
        assertEquals(listOf(CalloutColor.BLUE, CalloutColor.BLUE, CalloutColor.GREEN, CalloutColor.PURPLE), callouts.map { it.style.callout })
        // A heading in a callout is its first line, in bold.
        assertTrue(callouts[0].spans.any { it.style == CharStyle.BOLD })
    }

    @Test
    fun aGreyOrWhitePagesBackgroundIsNeverACallout() {
        val f = HtmlReader.read(
            "<div style=\"background-color: #ffffff\"><p>page</p><div style=\"background:#f0eee6\"><p>bubble</p></div>" +
                "<div style=\"background-color: rgba(59, 130, 246, 0.02)\"><p>faint</p></div></div>",
        )
        assertTrue(f.paragraphs.none { it.style.role == Role.CALLOUT })
    }
}
