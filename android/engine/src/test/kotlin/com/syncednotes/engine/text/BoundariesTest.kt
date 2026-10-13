package com.syncednotes.engine.text

import kotlin.test.Test
import kotlin.test.assertEquals

class BoundariesTest {
    private fun stops(text: String): List<Int> {
        val out = mutableListOf(0)
        var i = 0
        while (i < text.length) {
            i = Boundaries.nextCluster(text, i)
            out += i
        }
        return out
    }

    @Test
    fun theCursorNeverStopsInsideASurrogatePair() {
        assertEquals(listOf(0, 1, 3, 4), stops("a😀b"))
    }

    @Test
    fun anAccentStaysWithItsLetter() {
        assertEquals(listOf(0, 2, 3), stops("éx"))
    }

    @Test
    fun aFlagIsOneStop() {
        assertEquals(listOf(0, 4), stops("🇩🇿"))
    }

    @Test
    fun anEmojiJoinedWithAZeroWidthJoinerIsOneStop() {
        // woman + ZWJ + laptop
        assertEquals(listOf(0, 5), stops("👩‍💻"))
    }

    @Test
    fun movingBackwardsStopsAtTheSamePlacesAsMovingForwards() {
        val text = "hé́llo 🇩🇿\nnext"
        val forward = stops(text)
        val backward = mutableListOf(text.length)
        var i = text.length
        while (i > 0) {
            i = Boundaries.previousCluster(text, i)
            backward += i
        }
        assertEquals(forward, backward.reversed())
    }

    @Test
    fun aLongPressPicksTheWordUnderTheFingerOrJustBeforeIt() {
        val text = "hello brave world"
        assertEquals(6..10, Boundaries.wordAt(text, 8))
        assertEquals(6..10, Boundaries.wordAt(text, 11))
        assertEquals(true, Boundaries.wordAt("a  b", 2).isEmpty())
    }
}
