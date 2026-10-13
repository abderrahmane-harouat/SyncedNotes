package com.syncednotes.engine.text

/** Character styles that carry a value: Samsung's foreground colour, background colour and size spans. */
enum class ValueStyle { COLOR, HIGHLIGHT, SIZE }

/**
 * Ranges of characters carrying a value, sorted and disjoint; neighbours with the same value
 * are merged. Characters outside every range have the default. Edits move the ranges with the
 * same rule as [RangeSet]: exclusive at the start, inclusive at the end.
 */
class ValueRanges private constructor(private var data: IntArray, private var size: Int) {
    constructor() : this(IntArray(12), 0)

    val count: Int get() = size / 3
    fun start(i: Int) = data[3 * i]
    fun end(i: Int) = data[3 * i + 1]
    fun value(i: Int) = data[3 * i + 2]

    fun copy(): ValueRanges = ValueRanges(data.copyOf(maxOf(size, 12)), size)

    /** The value at the character at [offset], or null for the default. */
    fun valueAt(offset: Int): Int? {
        for (i in 0 until count) {
            if (start(i) > offset) break
            if (offset < end(i)) return value(i)
        }
        return null
    }

    /** The value text typed at a caret at [offset] would inherit, or null for the default. */
    fun valueAtCaret(offset: Int): Int? {
        for (i in 0 until count) {
            if (start(i) >= offset) break
            if (offset <= end(i)) return value(i)
        }
        return null
    }

    /** The one value all of [start, end) shares (null: all default), or [MIXED]. */
    fun uniformValue(start: Int, end: Int): Int? {
        if (start >= end) return valueAtCaret(start)
        val seen = HashSet<Int?>()
        var covered = start
        for (i in 0 until count) {
            if (end(i) <= start) continue
            if (start(i) >= end) break
            if (start(i) > covered) seen += null
            seen += value(i)
            covered = end(i)
        }
        if (covered < end) seen += null
        return if (seen.size == 1) seen.first() else MIXED
    }

    /** Gives [start, end) the [value], or the default when null. */
    fun set(start: Int, end: Int, value: Int?) {
        if (start >= end) return
        val out = ArrayList<Int>(size + 6)
        var placed = value == null
        for (i in 0 until count) {
            val a = start(i)
            val b = end(i)
            val v = value(i)
            if (b <= start || a >= end) {
                if (!placed && a >= end) { out += start; out += end; out += value!!; placed = true }
                out += a; out += b; out += v
                continue
            }
            if (a < start) { out += a; out += start; out += v }
            if (!placed) { out += start; out += end; out += value!!; placed = true }
            if (b > end) { out += end; out += b; out += v }
        }
        if (!placed) { out += start; out += end; out += value!! }
        replaceAll(out)
        normalize()
    }

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
                    a >= at -> { a += inserted; b += inserted }
                    else -> b += inserted
                }
            }
            out += a; out += b; out += value(i)
        }
        replaceAll(out)
        normalize()
    }

    /** Character ranges where this and [other] give different values. */
    fun differences(other: ValueRanges): List<IntRange> {
        val edges = sortedSetOf<Int>()
        for (i in 0 until count) { edges += start(i); edges += end(i) }
        for (i in 0 until other.count) { edges += other.start(i); edges += other.end(i) }
        val list = edges.toList()
        val out = ArrayList<IntRange>()
        for (k in 0 until list.size - 1) {
            if (valueAt(list[k]) != other.valueAt(list[k])) out += list[k] until list[k + 1]
        }
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is ValueRanges && size == other.size && (0 until size).all { data[it] == other.data[it] }

    override fun hashCode(): Int = (0 until size).fold(size) { h, i -> 31 * h + data[i] }

    override fun toString(): String = (0 until count).joinToString(prefix = "[", postfix = "]") { "${start(it)}..${end(it)}=${value(it)}" }

    private fun replaceAll(values: List<Int>) {
        if (data.size < values.size) data = IntArray(values.size * 2)
        for (i in values.indices) data[i] = values[i]
        size = values.size
    }

    /** Ranges in order, empty ones dropped, touching ones with equal values merged. */
    private fun normalize() {
        val out = ArrayList<Int>(size)
        for (i in 0 until count) {
            val a = start(i)
            val b = end(i)
            val v = value(i)
            if (a >= b) continue
            val n = out.size
            if (n > 0 && out[n - 2] >= a && out[n - 1] == v) out[n - 2] = maxOf(out[n - 2], b)
            else { out += a; out += b; out += v }
        }
        replaceAll(out)
    }

    companion object {
        /** Returned by [uniformValue] when the characters disagree. */
        const val MIXED = Int.MIN_VALUE
    }
}
