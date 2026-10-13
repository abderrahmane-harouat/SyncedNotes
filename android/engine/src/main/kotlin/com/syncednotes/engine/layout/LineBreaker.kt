package com.syncednotes.engine.layout

/**
 * Where a line may end. A working subset of Unicode line breaking (UAX #14): after spaces,
 * after a hyphen inside a word, and between ideographs; never before closing punctuation.
 * Arabic and other space-separated scripts are covered by the space rule.
 */
object LineBreaker {
    fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '　'

    private fun isIdeographic(c: Char): Boolean {
        val cp = c.code
        return cp in 0x2E80..0x9FFF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFF00..0xFFEF
    }

    private fun isClosing(c: Char) = c in ".,;:!?)]}”’»、。，．：；！？）」』】"

    /** breaks[i] is true when a line may start at i (0 < i < length). */
    fun opportunities(text: CharSequence, start: Int, end: Int): BooleanArray {
        val n = end - start
        val breaks = BooleanArray(n + 1)
        for (i in 1 until n) {
            val prev = text[start + i - 1]
            val cur = text[start + i]
            breaks[i] = when {
                isSpace(cur) -> false
                isSpace(prev) -> true
                isClosing(cur) -> false
                prev == '-' && i >= 2 && text[start + i - 2].isLetterOrDigit() && cur.isLetterOrDigit() -> true
                isIdeographic(prev) || isIdeographic(cur) -> true
                else -> false
            }
        }
        breaks[n] = true
        return breaks
    }
}
