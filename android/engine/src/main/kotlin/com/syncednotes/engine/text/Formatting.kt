package com.syncednotes.engine.text

/** Character styles that are either on or off for each character, like Samsung's B/I/U/S. */
enum class CharStyle { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }

enum class ListType { NONE, BULLET, NUMBER }

enum class Alignment { LEFT, CENTER, RIGHT }

/** What a paragraph carries as a whole: Samsung keeps these by paragraph, not by character. */
data class ParagraphStyle(
    val list: ListType = ListType.NONE,
    val alignment: Alignment = Alignment.LEFT,
    val indent: Int = 0,
) {
    companion object {
        val DEFAULT = ParagraphStyle()
        // Samsung clamps indent levels to 0..16.
        const val MAX_INDENT = 16
    }
}

/**
 * The characters that carry one style, as sorted, disjoint, non-adjacent ranges [start, end).
 *
 * Edits move the ranges the way Samsung's default span expansion does (exclusive at the start,
 * inclusive at the end): typing right after a bold word continues the bold, typing right
 * before it does not.
 */
class RangeSet private constructor(private var bounds: IntArray, private var size: Int) {
    constructor() : this(IntArray(8), 0)

    /** Number of ranges. */
    val count: Int get() = size / 2

    fun start(i: Int) = bounds[2 * i]
    fun end(i: Int) = bounds[2 * i + 1]

    fun copy(): RangeSet = RangeSet(bounds.copyOf(maxOf(size, 8)), size)

    /** Whether the character at [offset] has the style. */
    fun contains(offset: Int): Boolean {
        val i = indexAtOrAfter(offset)
        return i < count && start(i) <= offset
    }

    /** Whether text typed at a caret at [offset] would get the style. */
    fun extendsTo(offset: Int): Boolean {
        for (i in 0 until count) {
            if (start(i) < offset && offset <= end(i)) return true
            if (start(i) >= offset) break
        }
        return false
    }

    /** Whether every character in [start, end) has the style; false for an empty range. */
    fun coversAll(start: Int, end: Int): Boolean {
        if (start >= end) return false
        val i = indexAtOrAfter(start)
        return i < count && start(i) <= start && end(i) >= end
    }

    /** First range whose end is after [offset]. */
    private fun indexAtOrAfter(offset: Int): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (end(mid) <= offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun add(start: Int, end: Int) {
        if (start >= end) return
        var s = start
        var e = end
        val out = ArrayList<Int>(size + 2)
        var placed = false
        for (i in 0 until count) {
            val a = start(i)
            val b = end(i)
            // Touching or overlapping ranges merge into one.
            if (b < s) { out += a; out += b; continue }
            if (a > e) {
                if (!placed) { out += s; out += e; placed = true }
                out += a; out += b
                continue
            }
            s = minOf(s, a)
            e = maxOf(e, b)
        }
        if (!placed) { out += s; out += e }
        replaceAll(out)
    }

    fun remove(start: Int, end: Int) {
        if (start >= end) return
        val out = ArrayList<Int>(size + 2)
        for (i in 0 until count) {
            val a = start(i)
            val b = end(i)
            if (b <= start || a >= end) { out += a; out += b; continue }
            if (a < start) { out += a; out += start }
            if (b > end) { out += end; out += b }
        }
        replaceAll(out)
    }

    /** Moves the ranges for a text edit replacing [removed] characters at [at] with [inserted]. */
    fun onReplace(at: Int, removed: Int, inserted: Int) {
        val out = ArrayList<Int>(size)
        val removedEnd = at + removed
        for (i in 0 until count) {
            fun map(x: Int) = when {
                x <= at -> x
                x >= removedEnd -> x - removed
                else -> at
            }
            var a = map(start(i))
            var b = map(end(i))
            if (a >= b) continue
            if (inserted > 0) {
                when {
                    b < at -> {}
                    // Exclusive start: text inserted where a range begins stays outside it.
                    a >= at -> { a += inserted; b += inserted }
                    // Inside the range, or at its end (inclusive): the range grows.
                    else -> b += inserted
                }
            }
            out += a
            out += b
        }
        replaceAll(out)
        normalize()
    }

    /** Character ranges where this set and [other] disagree, as [start, end) pairs. */
    fun differences(other: RangeSet): List<IntRange> {
        val edges = sortedSetOf<Int>()
        for (i in 0 until count) { edges += start(i); edges += end(i) }
        for (i in 0 until other.count) { edges += other.start(i); edges += other.end(i) }
        val out = ArrayList<IntRange>()
        val list = edges.toList()
        for (k in 0 until list.size - 1) {
            val a = list[k]
            if (contains(a) != other.contains(a)) out += a until list[k + 1]
        }
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is RangeSet && size == other.size && (0 until size).all { bounds[it] == other.bounds[it] }

    override fun hashCode(): Int = (0 until size).fold(size) { h, i -> 31 * h + bounds[i] }

    override fun toString(): String = (0 until count).joinToString(prefix = "[", postfix = "]") { "${start(it)}..${end(it)}" }

    private fun replaceAll(values: List<Int>) {
        if (bounds.size < values.size) bounds = IntArray(values.size * 2)
        for (i in values.indices) bounds[i] = values[i]
        size = values.size
    }

    /** Merges ranges that came to touch after an edit, and drops empty ones. */
    private fun normalize() {
        val out = ArrayList<Int>(size)
        for (i in 0 until count) {
            val a = start(i)
            val b = end(i)
            if (a >= b) continue
            if (out.isNotEmpty() && out[out.size - 1] >= a) {
                out[out.size - 1] = maxOf(out[out.size - 1], b)
            } else {
                out += a
                out += b
            }
        }
        replaceAll(out)
    }
}

/** The formatting of a whole document at one moment, for undo. */
class FormatSnapshot(
    internal val styles: Map<CharStyle, RangeSet>,
    internal val paragraphs: List<ParagraphStyle>,
)
