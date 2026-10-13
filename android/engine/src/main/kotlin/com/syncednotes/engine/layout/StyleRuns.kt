package com.syncednotes.engine.layout

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
) {
    val count: Int get() = starts.size
    fun start(i: Int) = starts[i]
    fun end(i: Int) = if (i + 1 < starts.size) starts[i + 1] else length
    fun has(i: Int, style: CharStyle) = flags[i] and (1 shl style.ordinal) != 0

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
