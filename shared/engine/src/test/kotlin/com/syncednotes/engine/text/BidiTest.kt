package com.syncednotes.engine.text

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The bidirectional algorithm on the cases a note meets. */
class BidiTest {
    private val arabic = "مرحبا"

    private fun levels(text: String, direction: Direction = Direction.NATURAL): List<Int>? {
        val base = Bidi.paragraphLevel(text, direction)
        return Bidi.levels(text, base)?.map { it.toInt() }
    }

    @Test
    fun englishAloneNeedsNoLevelsAtAll() {
        assertNull(levels("plain English, 42 times."))
    }

    @Test
    fun aParagraphTakesTheDirectionOfItsFirstLetter() {
        assertEquals(1, Bidi.paragraphLevel("$arabic abc", Direction.NATURAL))
        assertEquals(0, Bidi.paragraphLevel("abc $arabic", Direction.NATURAL))
        assertEquals(0, Bidi.paragraphLevel("123 $arabic", Direction.LEFT_TO_RIGHT))
        assertEquals(1, Bidi.paragraphLevel("abc", Direction.RIGHT_TO_LEFT))
    }

    @Test
    fun englishInsideArabicRunsLeftToRightOneLevelUp() {
        val text = "$arabic abc $arabic"
        val l = levels(text)!!
        assertEquals(List(5) { 1 }, l.subList(0, 5))
        assertEquals(listOf(2, 2, 2), l.subList(6, 9))
        // The spaces between Arabic and English take the paragraph's direction.
        assertEquals(1, l[5])
    }

    @Test
    fun arabicInsideEnglishRunsRightToLeftAndTheSpacesAroundItStayEnglish() {
        val l = levels("one $arabic two")!!
        assertEquals(listOf(0, 0, 0, 0), l.subList(0, 4))
        assertEquals(List(5) { 1 }, l.subList(4, 9))
        assertEquals(0, l[9])
    }

    @Test
    fun digitsAfterArabicLettersStillReadLeftToRight() {
        val l = levels("$arabic 2026")!!
        // Numbers in right-to-left text are two levels up: left to right inside it.
        assertEquals(listOf(2, 2, 2, 2), l.subList(6, 10))
    }

    @Test
    fun runsAreShownWithTheRightToLeftOnesReversed() {
        // English, Arabic, English, Arabic at levels 0 1 0 1: the order is unchanged at level
        // 0, and each level-1 run is reversed on its own.
        assertContentEquals(intArrayOf(0, 1, 2), Bidi.visualOrder(intArrayOf(0, 1, 0)))
        // In an Arabic paragraph, level 1 2 1: the whole line is reversed, the English kept.
        assertContentEquals(intArrayOf(2, 1, 0), Bidi.visualOrder(intArrayOf(1, 2, 1)))
    }

    @Test
    fun bracketsAreMirroredInRightToLeftText() {
        assertEquals(')', Bidi.mirror('('))
        assertEquals('«', Bidi.mirror('»'))
        assertEquals('a', Bidi.mirror('a'))
    }
}
