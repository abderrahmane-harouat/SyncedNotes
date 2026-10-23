package com.syncednotes.engine.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValueRangesTest {
    @Test
    fun settingValuesMatchesASimpleArrayOfValues() {
        val random = Random(5)
        val ranges = ValueRanges()
        val expected = arrayOfNulls<Int>(80)
        repeat(2000) {
            val a = random.nextInt(60)
            val b = a + random.nextInt(12)
            val v = if (random.nextInt(4) == 0) null else random.nextInt(3)
            ranges.set(a, b, v)
            for (i in a until b) expected[i] = v
            assertEquals(expected.toList(), (0 until 80).map { ranges.valueAt(it) })
        }
    }

    @Test
    fun aSelectionWithOneColourAndPlainTextIsMixed() {
        val ranges = ValueRanges()
        ranges.set(2, 5, 7)
        assertEquals(7, ranges.uniformValue(2, 5))
        assertEquals(ValueRanges.MIXED, ranges.uniformValue(0, 5))
        assertEquals(ValueRanges.MIXED, ranges.uniformValue(2, 6))
        assertNull(ranges.uniformValue(6, 9))
    }

    @Test
    fun typingAtTheEndOfAColouredWordKeepsItsColour() {
        val ranges = ValueRanges()
        ranges.set(0, 4, 9)
        ranges.onReplace(4, 0, 2)
        assertEquals(9, ranges.valueAt(5))
        ranges.onReplace(0, 0, 1)
        assertNull(ranges.valueAt(0))
    }
}
