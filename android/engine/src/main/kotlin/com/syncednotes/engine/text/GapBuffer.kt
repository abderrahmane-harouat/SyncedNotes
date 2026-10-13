package com.syncednotes.engine.text

/**
 * The characters of a document. Typing happens at one place at a time, so a gap kept at the
 * cursor makes each keystroke cost O(1) instead of shifting everything after it.
 */
class GapBuffer(initialCapacity: Int = 256) : CharSequence {
    private var chars = CharArray(maxOf(16, initialCapacity))
    private var gapStart = 0
    private var gapEnd = chars.size

    override val length: Int get() = chars.size - (gapEnd - gapStart)

    override fun get(index: Int): Char {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException("$index of $length")
        return if (index < gapStart) chars[index] else chars[index + gapEnd - gapStart]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = substring(startIndex, endIndex)

    fun substring(start: Int, end: Int): String {
        checkRange(start, end)
        val out = CharArray(end - start)
        copyTo(start, end, out, 0)
        return String(out)
    }

    fun copyTo(start: Int, end: Int, dest: CharArray, destOffset: Int) {
        checkRange(start, end)
        val gap = gapEnd - gapStart
        var o = destOffset
        if (start < gapStart) {
            val e = minOf(end, gapStart)
            chars.copyInto(dest, o, start, e)
            o += e - start
        }
        if (end > gapStart) {
            val s = maxOf(start, gapStart)
            chars.copyInto(dest, o, s + gap, end + gap)
        }
    }

    override fun toString(): String = substring(0, length)

    fun replace(start: Int, end: Int, text: CharSequence) {
        checkRange(start, end)
        moveGap(end)
        gapStart = start
        ensureGap(text.length)
        for (i in text.indices) chars[gapStart + i] = text[i]
        gapStart += text.length
    }

    private fun moveGap(to: Int) {
        if (to < gapStart) {
            val n = gapStart - to
            chars.copyInto(chars, gapEnd - n, to, gapStart)
            gapStart = to
            gapEnd -= n
        } else if (to > gapStart) {
            val n = to - gapStart
            chars.copyInto(chars, gapStart, gapEnd, gapEnd + n)
            gapStart += n
            gapEnd += n
        }
    }

    private fun ensureGap(needed: Int) {
        if (gapEnd - gapStart >= needed) return
        val tail = chars.size - gapEnd
        val bigger = CharArray(maxOf(chars.size * 2, length + needed + 64))
        chars.copyInto(bigger, 0, 0, gapStart)
        chars.copyInto(bigger, bigger.size - tail, gapEnd, chars.size)
        gapEnd = bigger.size - tail
        chars = bigger
    }

    private fun checkRange(start: Int, end: Int) {
        if (start < 0 || end > length || start > end) throw IndexOutOfBoundsException("[$start, $end) of $length")
    }
}
