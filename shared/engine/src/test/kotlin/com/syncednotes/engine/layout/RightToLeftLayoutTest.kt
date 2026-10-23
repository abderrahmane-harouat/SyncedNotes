package com.syncednotes.engine.layout

import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.text.TextDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Arabic laid out on the screen: from the right, with English inside it from the left. */
class RightToLeftLayoutTest {
    private val arabic = "مرحبا"

    private fun layoutOf(text: String, width: Float = 1000f): ParagraphLayout {
        val doc = TextDocument()
        val layout = DocumentLayout(doc, TestFonts.family, 40f / 17, 17, 1.5f, 50f, 60f)
        layout.setWidth(width)
        doc.replace(0, 0, text)
        return layout.paragraph(0)
    }

    @Test
    fun anArabicParagraphStartsOnTheRightAndReadsLeftwards() {
        val p = layoutOf(arabic)
        assertTrue(p.rtl)
        val start = p.xOf(0, 0)
        val end = p.xOf(arabic.length, 0)
        assertEquals(1000f, start, 0.5f)
        assertTrue(end < start, "the caret moves left as Arabic is written: $end < $start")
    }

    @Test
    fun theFirstLetterOfAnArabicWordIsDrawnRightmost() {
        val p = layoutOf(arabic)
        val first = (0 until p.glyphCount).first { p.clusters[it] == 0 }
        val last = (0 until p.glyphCount).last { p.clusters[it] == arabic.length - 1 }
        assertTrue(p.glyphX(first, 0) > p.glyphX(last, 0))
    }

    @Test
    fun englishInsideArabicStillReadsLeftToRight() {
        val text = "$arabic abc"
        val p = layoutOf(text)
        val a = text.indexOf('a')
        // Within the English, a before b before c from the left; all of it left of the Arabic.
        assertTrue(p.xOf(a, 0) < p.xOf(a + 1, 0) && p.xOf(a + 1, 0) < p.xOf(a + 2, 0))
        val arabicStart = p.xOf(0, 0)
        assertTrue(p.xOf(a + 3, 0) < arabicStart)
    }

    @Test
    fun arabicInsideEnglishSitsBetweenTheWordsReadingRightToLeft() {
        val text = "one $arabic two"
        val p = layoutOf(text)
        val one = p.spans(0, 0, 3)
        val word = p.spans(0, 4, 9)
        val two = p.spans(0, 10, 13)
        assertTrue(one[1] <= word[0] + 0.5f && word[1] <= two[0] + 0.5f, "one, then the Arabic, then two")
        // Its first letter is on its right.
        assertEquals(word[1], p.spans(0, 4, 5)[1], 0.5f)
    }

    @Test
    fun clickingAnArabicLineAtItsRightEndPutsTheCaretAtItsStart() {
        val p = layoutOf(arabic)
        assertEquals(0, p.offsetAt(0, 999f).first)
        assertEquals(arabic.length, p.offsetAt(0, 0f).first)
    }

    @Test
    fun aSelectionAcrossBothDirectionsIsDrawnInPieces() {
        val text = "one $arabic"
        val p = layoutOf(text)
        // "ne مر": English on the left, the first Arabic letters on the far right of their run.
        val spans = p.spans(0, 1, 6)
        assertEquals(4, spans.size)
    }
}
