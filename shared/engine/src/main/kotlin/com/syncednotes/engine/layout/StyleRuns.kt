package com.syncednotes.engine.layout

import com.syncednotes.engine.code.TokenKind
import com.syncednotes.engine.text.CharStyle

/**
 * A paragraph cut wherever any character style changes; inside a run every character looks
 * the same. Offsets are relative to the paragraph.
 */
class StyleRuns(
    val starts: IntArray,
    /** Bit `1 shl ordinal` per [CharStyle]. */
    val flags: IntArray,
    /** Text size in pixels. */
    val sizes: FloatArray,
    /** 0xAARRGGBB, or 0 for the editor's text colour. */
    val colors: IntArray,
    /** 0xAARRGGBB, or 0 for none. */
    val highlights: IntArray,
    private val length: Int,
    /**
     * In a code block with a language, what each run is ([TokenKind] ordinal + 1), which picks
     * its colour where no colour was chosen by hand; 0 for code that is nothing in particular.
     * Null outside such a block.
     */
    val syntax: IntArray? = null,
) {
    val count: Int get() = starts.size
    fun start(i: Int) = starts[i]
    fun end(i: Int) = if (i + 1 < starts.size) starts[i + 1] else length
    fun has(i: Int, style: CharStyle) = flags[i] and (1 shl style.ordinal) != 0

    /** What the run is as code; null when it is not code of a language, or nothing in particular. */
    fun token(i: Int): TokenKind? = syntax?.get(i)?.takeIf { it != 0 }?.let { TokenKind.entries[it - 1] }

    /** The run holding the character at [offset]; the last run for the end of the paragraph. */
    fun runAt(offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}
