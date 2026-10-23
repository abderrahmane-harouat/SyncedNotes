package com.syncednotes.engine.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class GapBufferTest {
    @Test
    fun editsAnywhereReadBackAsTheSameStringAStringBuilderHolds() {
        val random = Random(7)
        val gap = GapBuffer(4)
        val expected = StringBuilder()
        repeat(2000) {
            val start = random.nextInt(expected.length + 1)
            val end = start + random.nextInt(minOf(5, expected.length - start) + 1)
            val text = "abcdefgh\n".substring(0, random.nextInt(9))
            gap.replace(start, end, text)
            expected.replace(start, end, text)
            assertEquals(expected.toString(), gap.toString())
        }
        assertEquals(expected.length, gap.length)
        for (i in expected.indices) assertEquals(expected[i], gap[i])
    }
}
