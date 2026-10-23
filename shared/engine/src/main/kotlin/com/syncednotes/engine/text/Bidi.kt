package com.syncednotes.engine.text

/**
 * The Unicode bidirectional algorithm (UAX #9), for one paragraph: which characters run right
 * to left, so Arabic reads from the right and English inside it still reads from the left.
 *
 * The rules a note needs are all here: the paragraph's own direction (P2, P3), the weak types
 * (W1-W7), neutrals between strong text (N1, N2), and the implicit levels (I1, I2). Explicit
 * embeddings and isolates (the invisible LRE, RLI... controls) are read as nothing, like other
 * boundary neutrals: notes do not use them, and text pasted with them still lays out, only
 * without their override. Bracket pairs (N0) are left to N1, which gets them right whenever
 * the brackets sit in text of one direction.
 *
 * Character classes come from the JDK's Unicode data ([Character.getDirectionality]); the
 * algorithm is ours.
 */
object Bidi {
    // The classes the algorithm works with, after the JDK's are read.
    private const val L = 0
    private const val R = 1
    private const val AL = 2
    private const val EN = 3
    private const val ES = 4
    private const val ET = 5
    private const val AN = 6
    private const val CS = 7
    private const val NSM = 8
    private const val BN = 9
    private const val B = 10
    private const val S = 11
    private const val WS = 12
    private const val ON = 13

    private fun classOf(cp: Int): Int = when (Character.getDirectionality(cp)) {
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> L
        Character.DIRECTIONALITY_RIGHT_TO_LEFT -> R
        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> AL
        Character.DIRECTIONALITY_EUROPEAN_NUMBER -> EN
        Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR -> ES
        Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR -> ET
        Character.DIRECTIONALITY_ARABIC_NUMBER -> AN
        Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR -> CS
        Character.DIRECTIONALITY_NONSPACING_MARK -> NSM
        Character.DIRECTIONALITY_PARAGRAPH_SEPARATOR -> B
        Character.DIRECTIONALITY_SEGMENT_SEPARATOR -> S
        Character.DIRECTIONALITY_WHITESPACE -> WS
        Character.DIRECTIONALITY_OTHER_NEUTRALS -> ON
        // Boundary neutrals and every explicit control (see above).
        else -> BN
    }

    /** The paragraph's level: 1 when it runs right to left. [direction] NATURAL looks at its first letter. */
    fun paragraphLevel(text: CharSequence, direction: Direction): Int = when (direction) {
        Direction.LEFT_TO_RIGHT -> 0
        Direction.RIGHT_TO_LEFT -> 1
        Direction.NATURAL -> {
            var level = 0
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                when (classOf(cp)) {
                    L -> break
                    R, AL -> { level = 1; break }
                }
                i += Character.charCount(cp)
            }
            level
        }
    }

    /**
     * Each UTF-16 unit's embedding level: even runs left to right, odd right to left. Null when
     * the whole paragraph is level 0, which is most notes and needs no reordering at all.
     */
    fun levels(text: CharSequence, base: Int): ByteArray? {
        val n = text.length
        if (base == 0 && (0 until n).none { isRightToLeftUnit(text[it]) }) return null
        val types = IntArray(n)
        var i = 0
        while (i < n) {
            val cp = Character.codePointAt(text, i)
            val t = classOf(cp)
            types[i] = t
            // The low half of a pair takes the pair's class, so every unit has one.
            if (Character.charCount(cp) == 2 && i + 1 < n) types[i + 1] = t
            i += Character.charCount(cp)
        }
        val original = types.copyOf()
        val sos = if (base % 2 == 0) L else R

        // W1: a mark takes the type of what it follows; boundary neutrals are looked through.
        var prev = sos
        for (k in 0 until n) {
            when (types[k]) {
                NSM -> types[k] = prev
                BN -> {}
                else -> prev = types[k]
            }
        }
        // W2: European digits after Arabic letters are Arabic digits. W3: AL becomes R.
        var lastStrong = sos
        for (k in 0 until n) {
            when (types[k]) {
                L, R -> lastStrong = types[k]
                AL -> { lastStrong = AL; types[k] = R }
                EN -> if (lastStrong == AL) types[k] = AN
            }
        }
        // W4: one separator between two numbers of a kind joins them.
        for (k in 1 until n - 1) {
            val t = types[k]
            if (t != ES && t != CS) continue
            val before = prevNonBn(types, k)
            val after = nextNonBn(types, k)
            if (before < 0 || after < 0) continue
            val a = types[before]
            val b = types[after]
            if (a == EN && b == EN && (t == ES || t == CS)) types[k] = EN
            else if (a == AN && b == AN && t == CS) types[k] = AN
        }
        // W5: terminators (%, °, currency) next to European digits are digits.
        var k = 0
        while (k < n) {
            if (types[k] != ET && types[k] != BN) { k++; continue }
            val start = k
            while (k < n && (types[k] == ET || types[k] == BN)) k++
            val touches = (start > 0 && types[start - 1] == EN) || (k < n && types[k] == EN)
            if (touches) for (m in start until k) if (types[m] == ET) types[m] = EN
        }
        // W6: other separators and terminators are neutral.
        for (m in 0 until n) if (types[m] == ES || types[m] == ET || types[m] == CS) types[m] = ON
        // W7: European digits after Latin letters are Latin.
        lastStrong = sos
        for (m in 0 until n) {
            when (types[m]) {
                L, R -> lastStrong = types[m]
                EN -> if (lastStrong == L) types[m] = L
            }
        }
        // N1, N2: neutrals between text of one direction take it; others the paragraph's.
        k = 0
        while (k < n) {
            if (!isNeutral(types[k])) { k++; continue }
            val start = k
            while (k < n && isNeutral(types[k])) k++
            val before = if (start == 0) sos else strongOf(types[start - 1])
            val after = if (k == n) sos else strongOf(types[k])
            val resolved = if (before == after) before else sos
            for (m in start until k) types[m] = resolved
        }
        // I1, I2: implicit levels.
        val levels = ByteArray(n)
        for (m in 0 until n) {
            val t = types[m]
            levels[m] = when {
                base % 2 == 0 && t == R -> base + 1
                base % 2 == 0 && (t == AN || t == EN) -> base + 2
                base % 2 == 1 && (t == L || t == EN || t == AN) -> base + 1
                else -> base
            }.toByte()
        }
        // L1, in part: separators and the white space before them go back to the paragraph's
        // level. The white space at a line's end does too, which layout does per line.
        for (m in 0 until n) {
            if (original[m] == S || original[m] == B) {
                levels[m] = base.toByte()
                var w = m - 1
                while (w >= 0 && (original[w] == WS || original[w] == BN)) levels[w--] = base.toByte()
            }
        }
        return levels
    }

    /** Whether a level runs right to left. */
    fun isRtl(level: Int): Boolean = level % 2 == 1

    private fun isRightToLeftUnit(c: Char): Boolean {
        // A quick test on the units: Hebrew, Arabic and their presentation forms, and the
        // high surrogates of the right-to-left blocks beyond the BMP.
        val u = c.code
        return u in 0x0590..0x08FF || u in 0xFB1D..0xFDFF || u in 0xFE70..0xFEFF || u == 0xD802 || u == 0xD803 || u == 0xD83A || u == 0xD83B ||
            u == 0x200F || u == 0x202B || u == 0x202E || u == 0x2067
    }

    private fun isNeutral(t: Int) = t == B || t == S || t == WS || t == ON || t == BN

    /** Numbers count as right to left for the neutrals beside them (N1). */
    private fun strongOf(t: Int) = if (t == L) L else R

    private fun prevNonBn(types: IntArray, k: Int): Int {
        var j = k - 1
        while (j >= 0 && types[j] == BN) j--
        return j
    }

    private fun nextNonBn(types: IntArray, k: Int): Int {
        var j = k + 1
        while (j < types.size && types[j] == BN) j++
        return if (j < types.size) j else -1
    }

    /**
     * The visual order of runs on a line (L2): from the highest level down to the lowest odd
     * one, every stretch at that level or above is reversed. [runLevels] in logical order; the
     * result lists run indices from left to right.
     */
    fun visualOrder(runLevels: IntArray): IntArray {
        val order = IntArray(runLevels.size) { it }
        if (runLevels.isEmpty()) return order
        val highest = runLevels.max()
        val lowestOdd = runLevels.filter { it % 2 == 1 }.minOrNull() ?: return order
        for (level in highest downTo lowestOdd) {
            var i = 0
            while (i < order.size) {
                if (runLevels[order[i]] < level) { i++; continue }
                val start = i
                while (i < order.size && runLevels[order[i]] >= level) i++
                order.reverse(start, i)
            }
        }
        return order
    }

    /** Brackets and the like, drawn mirrored in text that runs right to left (L4). */
    fun mirror(c: Char): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        '[' -> ']'
        ']' -> '['
        '{' -> '}'
        '}' -> '{'
        '<' -> '>'
        '>' -> '<'
        '«' -> '»'
        '»' -> '«'
        '‹' -> '›'
        '›' -> '‹'
        else -> c
    }
}
