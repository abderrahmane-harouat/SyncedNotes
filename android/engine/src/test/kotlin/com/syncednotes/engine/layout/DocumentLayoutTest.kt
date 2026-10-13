package com.syncednotes.engine.layout

import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.text.TextDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentLayoutTest {
    private fun layoutOf(text: String, width: Float = 300f): Pair<TextDocument, DocumentLayout> {
        val doc = TextDocument()
        val layout = DocumentLayout(doc, TestFonts.family, 40f, 60f, 50f, 60f)
        layout.setWidth(width)
        doc.replace(0, 0, text)
        return doc to layout
    }

    @Test
    fun aLongParagraphWrapsAtSpacesAndEveryLineFits() {
        val (_, layout) = layoutOf("the quick brown fox jumps over the lazy dog again and again")
        val p = layout.paragraph(0)
        assertTrue(p.lineCount > 2)
        for (line in 0 until p.lineCount) {
            assertTrue(p.lineWidth(line) <= 300f, "line $line is ${p.lineWidth(line)} wide")
            if (line > 0) assertEquals(' ', p.text[p.lineStart(line) - 1], "line $line should start after a space")
        }
    }

    @Test
    fun aWordWiderThanTheLineIsCutBetweenLetters() {
        val (_, layout) = layoutOf("a".repeat(60))
        val p = layout.paragraph(0)
        assertTrue(p.lineCount > 1)
        for (line in 0 until p.lineCount) assertTrue(p.lineWidth(line) <= 300f)
    }

    @Test
    fun anEmptyParagraphStillHasALineForTheCaret() {
        val (_, layout) = layoutOf("one\n\nthree")
        assertEquals(60f, layout.paragraph(1).height)
        assertEquals(120f, layout.paragraphTop(2))
    }

    @Test
    fun editingOneParagraphKeepsTheOthersLaidOut() {
        val (doc, layout) = layoutOf("first\nsecond\nthird")
        val first = layout.paragraph(0)
        val third = layout.paragraph(2)
        doc.replace(7, 7, "XX")
        assertSame(first, layout.paragraph(0))
        assertSame(third, layout.paragraph(2))
        assertEquals("sXXecond", layout.paragraph(1).text)
    }

    @Test
    fun aCaretPositionAndItsPointRoundTrip() {
        val (doc, layout) = layoutOf("the quick brown fox jumps over the lazy dog\nsecond paragraph")
        for (offset in 0..doc.length) {
            val r = layout.caretRect(offset)
            val (back, _) = layout.offsetAt(r.x, (r.top + r.bottom) / 2)
            assertEquals(offset, back, "offset $offset at (${r.x}, ${r.top})")
        }
    }

    @Test
    fun aTapRightOfAWrappedLineLandsAtItsEndOnThatLine() {
        val (_, layout) = layoutOf("the quick brown fox jumps over the lazy dog")
        val p = layout.paragraph(0)
        val (offset, upstream) = layout.offsetAt(1000f, 30f)
        assertEquals(p.lineEnd(0), offset)
        assertTrue(upstream)
        assertEquals(0f, layout.caretRect(offset, upstream).top)
    }
}
