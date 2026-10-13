package com.syncednotes.engine.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class TextDocumentTest {
    private fun paragraphStartsByScanning(text: String): List<Int> =
        listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }

    @Test
    fun paragraphStartsStayCorrectThroughRandomEdits() {
        val random = Random(11)
        val doc = TextDocument()
        repeat(3000) {
            val start = random.nextInt(doc.length + 1)
            val end = start + random.nextInt(minOf(6, doc.length - start) + 1)
            val text = listOf("", "a", "\n", "ab\ncd", "\n\n", "xyz").random(random)
            doc.replace(start, end, text)
            val expected = paragraphStartsByScanning(doc.toString())
            assertEquals(expected, (0 until doc.paragraphCount).map { doc.paragraphStart(it) })
        }
    }

    @Test
    fun aChangeReportsWhichParagraphsItReplaced() {
        val doc = TextDocument()
        doc.replace(0, 0, "one\ntwo\nthree")
        val change = doc.replace(2, 9, "X\nY")
        assertEquals(0, change.firstParagraph)
        assertEquals(3, change.paragraphsBefore)
        assertEquals(2, change.paragraphsAfter)
        assertEquals("onX\nYhree", doc.toString())
    }

    @Test
    fun offsetsInsideRemovedTextMoveToWhereTheEditHappened() {
        val doc = TextDocument()
        doc.replace(0, 0, "hello world")
        val change = doc.replace(2, 8, "Y")
        assertEquals(1, change.map(1))
        assertEquals(2, change.map(5))
        assertEquals(4, change.map(9))
    }
}
