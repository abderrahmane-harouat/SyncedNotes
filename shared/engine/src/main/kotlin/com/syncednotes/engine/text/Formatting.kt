package com.syncednotes.engine.text

/**
 * Character styles that are either on or off for each character, like Samsung's B/I/U/S, and
 * inline code, which the Mac's notes have.
 */
enum class CharStyle { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH, CODE }

/**
 * The marker a paragraph shows, worked out from its [ParagraphStyle]: what a toolbar button
 * reflects. A checklist is a role in the note format, and the others are the innermost list.
 */
enum class ListType { NONE, BULLET, DASH, NUMBER, CHECKBOX }

/** NATURAL follows the paragraph's writing direction: left for English, right for Arabic. */
enum class Alignment { NATURAL, LEFT, CENTER, RIGHT, JUSTIFIED }

enum class Direction { NATURAL, LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/**
 * What a paragraph is, as the note format (note.proto) records it: meaning, never looks. A
 * title is drawn large and bold because it is a title; nothing about its font is stored.
 */
enum class Role { BODY, TITLE, HEADING, SUBHEADING, QUOTE, CODE_BLOCK, CHECKLIST, DIVIDER, CALLOUT }

enum class ListKind { BULLET, DASH, NUMBER }

/**
 * The language a code block is written in, which its syntax is coloured by. NONE is code
 * shown as it is, monospaced in the text colour.
 */
enum class CodeLanguage(val title: String) { NONE("Plain"), C("C"), CPP("C++") }

/**
 * The colour of a callout's panel, by name: each is drawn in a shade that reads on the light
 * look and another for the dark, as the note format records the name, never the colour.
 */
enum class CalloutColor(val title: String) { BLUE("Blue"), GREEN("Green"), ORANGE("Orange"), PURPLE("Purple"), PINK("Pink"), GRAY("Gray") }

/**
 * One list a paragraph is an item of. Paragraphs next to each other with the same [id] at a
 * level are items of one list, so its numbering runs on; a different id starts a new list.
 * [start] is the number a numbered list starts at, 0 meaning 1.
 */
data class ListLevel(val kind: ListKind, val id: Int, val start: Int = 0)

/**
 * What a paragraph carries as a whole, field for field the note format's Paragraph, so a note
 * read into the engine is saved back exactly as it was.
 */
data class ParagraphStyle(
    val role: Role = Role.BODY,
    /** The lists this paragraph is an item of, outermost first; empty when it is in none. */
    val lists: List<ListLevel> = emptyList(),
    val alignment: Alignment = Alignment.NATURAL,
    val direction: Direction = Direction.NATURAL,
    /** Indent steps the user added, beyond what the role or the lists bring. */
    val indent: Int = 0,
    /** Line height as a multiple of normal; 0 means normal. */
    val lineSpacing: Float = 0f,
    /** Only meaningful for a checklist item. */
    val checked: Boolean = false,
    /**
     * The picture the paragraph shows, as the app's handle for it ([PictureSource]); 0 for
     * none. A picture is a paragraph of its own, holding one U+FFFC, as a divider is.
     */
    val picture: Int = 0,
    /** Only meaningful for a code block; every paragraph of one block has the same. */
    val language: CodeLanguage = CodeLanguage.NONE,
    /** Only meaningful for a callout; paragraphs next to each other with the same are one callout. */
    val callout: CalloutColor = CalloutColor.BLUE,
) {
    /** A paragraph that is one thing rather than text: a divider or a picture. */
    val isObject: Boolean get() = role == Role.DIVIDER || picture != 0

    val list: ListType
        get() = when {
            role == Role.CHECKLIST -> ListType.CHECKBOX
            lists.isEmpty() -> ListType.NONE
            else -> when (lists.last().kind) {
                ListKind.BULLET -> ListType.BULLET
                ListKind.DASH -> ListType.DASH
                ListKind.NUMBER -> ListType.NUMBER
            }
        }

    /** How deeply the paragraph sits in lists. */
    val depth: Int get() = lists.size

    companion object {
        val DEFAULT = ParagraphStyle()
        // Samsung clamps indent levels to 0..16.
        const val MAX_INDENT = 16
        /** As deep as the Mac app nested lists. */
        const val MAX_LIST_DEPTH = 8
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
    internal val values: Map<ValueStyle, ValueRanges>,
    internal val paragraphs: List<ParagraphStyle>,
)
