package com.syncednotes.engine.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangeSetTest {
    private fun RangeSet.members(length: Int) = (0 until length).filter { contains(it) }

    @Test
    fun addingAndRemovingMatchesASimpleSetOfOffsets() {
        val random = Random(3)
        val set = RangeSet()
        val expected = sortedSetOf<Int>()
        repeat(2000) {
            val a = random.nextInt(60)
            val b = a + random.nextInt(10)
            if (random.nextBoolean()) { set.add(a, b); expected.addAll(a until b) }
            else { set.remove(a, b); expected.removeAll((a until b).toSet()) }
            assertEquals(expected.toList(), set.members(80))
        }
    }

    @Test
    fun typingRightAfterAStyledWordContinuesTheStyle() {
        val set = RangeSet()
        set.add(0, 4)
        set.onReplace(4, 0, 3)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), set.members(10))
    }

    @Test
    fun typingRightBeforeAStyledWordDoesNotTakeTheStyle() {
        val set = RangeSet()
        set.add(4, 8)
        set.onReplace(4, 0, 2)
        assertEquals(listOf(6, 7, 8, 9), set.members(12))
    }

    @Test
    fun deletingAStyledWordEntirelyLeavesNoEmptyRangeBehind() {
        val set = RangeSet()
        set.add(2, 5)
        set.onReplace(1, 6, 0)
        assertEquals(0, set.count)
    }

    @Test
    fun aCaretAtTheEndOfAStyledRunTypesInThatStyle() {
        val set = RangeSet()
        set.add(2, 5)
        assertTrue(set.extendsTo(5))
        assertFalse(set.extendsTo(2))
        assertTrue(set.coversAll(2, 5))
        assertFalse(set.coversAll(1, 5))
    }
}
