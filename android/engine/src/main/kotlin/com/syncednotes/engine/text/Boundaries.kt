package com.syncednotes.engine.text

/**
 * Where the cursor may stop, and what a "word" is. The cursor never lands between the halves
 * of a surrogate pair, between a letter and its accents, or inside an emoji sequence.
 *
 * This is a working subset of Unicode's grapheme rules (UAX #29); the full tables come with
 * the bidi and Arabic work, where clusters matter even more.
 */
object Boundaries {
    private fun codePointAt(text: CharSequence, i: Int): Int {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            return Character.toCodePoint(c, text[i + 1])
        }
        return c.code
    }

    private fun codePointBefore(text: CharSequence, i: Int): Int {
        val c = text[i - 1]
        if (c.isLowSurrogate() && i - 2 >= 0 && text[i - 2].isHighSurrogate()) {
            return Character.toCodePoint(text[i - 2], c)
        }
        return c.code
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    /** Code points that attach to whatever comes before them. */
    private fun extends(cp: Int): Boolean {
        if (cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }

    fun nextCluster(text: CharSequence, offset: Int): Int {
        val n = text.length
        if (offset >= n) return n
        if (text[offset] == '\r' && offset + 1 < n && text[offset + 1] == '\n') return offset + 2
        var cp = codePointAt(text, offset)
        var i = offset + Character.charCount(cp)
        if (isRegionalIndicator(cp) && i < n && isRegionalIndicator(codePointAt(text, i))) {
            return i + 2
        }
        while (i < n) {
            val next = codePointAt(text, i)
            if (extends(next)) {
                i += Character.charCount(next)
                // A zero-width joiner glues the following pictograph into the same emoji.
                if (next == 0x200D && i < n) {
                    cp = codePointAt(text, i)
                    if (cp != '\n'.code) i += Character.charCount(cp)
                }
            } else {
                break
            }
        }
        return i
    }

    fun previousCluster(text: CharSequence, offset: Int): Int {
        if (offset <= 0) return 0
        // Walk forward from a point that is surely a boundary: the start of the paragraph.
        var start = offset - 1
        while (start > 0 && text[start - 1] != '\n') start--
        var prev = start
        var i = start
        while (i < offset) {
            prev = i
            i = nextCluster(text, i)
        }
        return prev
    }

    /** Snaps an offset that falls inside a cluster to the cluster's start. */
    fun snap(text: CharSequence, offset: Int): Int {
        if (offset <= 0 || offset >= text.length) return offset.coerceIn(0, text.length)
        val before = previousCluster(text, offset)
        return if (nextCluster(text, before) == offset) offset else before
    }

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '\'' || c == '’' ||
        Character.getType(c).toByte().let { it == Character.NON_SPACING_MARK || it == Character.COMBINING_SPACING_MARK }

    /** The word around [offset], or an empty range at [offset] when there is none. */
    fun wordAt(text: CharSequence, offset: Int): IntRange {
        val n = text.length
        var s = offset.coerceIn(0, n)
        // On the boundary right after a word, take that word, as a long-press there does.
        if ((s >= n || !isWordChar(text[s])) && s > 0 && isWordChar(text[s - 1])) s--
        if (s >= n || !isWordChar(text[s])) return offset until offset
        var e = s
        while (s > 0 && isWordChar(text[s - 1])) s--
        while (e < n && isWordChar(text[e])) e++
        return s until e
    }

    fun nextWordEnd(text: CharSequence, offset: Int): Int {
        var i = offset
        val n = text.length
        while (i < n && !isWordChar(text[i])) i++
        while (i < n && isWordChar(text[i])) i++
        return i
    }

    fun previousWordStart(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && !isWordChar(text[i - 1])) i--
        while (i > 0 && isWordChar(text[i - 1])) i--
        return i
    }
}
