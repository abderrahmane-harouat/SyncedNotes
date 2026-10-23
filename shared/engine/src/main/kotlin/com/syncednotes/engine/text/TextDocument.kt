package com.syncednotes.engine.text

/**
 * One edit, in the shape Samsung's engine reports them (index, before, count) plus the strings,
 * so history can invert it and layout can tell which paragraphs to measure again.
 */
class TextChange(
    val start: Int,
    val removed: String,
    val inserted: String,
    /** The first paragraph the edit touched, and how many paragraphs it replaced with how many. */
    val firstParagraph: Int,
    val paragraphsBefore: Int,
    val paragraphsAfter: Int,
) {
    val oldEnd: Int get() = start + removed.length
    val newEnd: Int get() = start + inserted.length
    val delta: Int get() = inserted.length - removed.length

    /** Where an offset from before the edit ends up. Offsets inside removed text move to its start. */
    fun map(offset: Int): Int = when {
        offset <= start -> offset
        offset >= oldEnd -> offset + delta
        else -> start
    }
}

/**
 * The text of a note: one flat string, the same model as Samsung's body text. Paragraphs are
 * the stretches between '\n' characters; their start offsets are kept up to date on every edit
 * instead of being found by scanning.
 *
 * Formatting lives beside the text the way Samsung keeps it: character styles as ranges of
 * offsets, paragraph styles one per paragraph. Both follow every edit.
 */
class TextDocument {
    private val buffer = GapBuffer()
    // paragraphStarts[i] is the offset of paragraph i's first character; [0] is always 0.
    private var paragraphStarts = IntArray(16)
    private var paragraphCountField = 1
    private val listeners = ArrayList<(TextChange) -> Unit>()
    private val formatListeners = ArrayList<(Int, Int) -> Unit>()
    private var styles = CharStyle.entries.associateWith { RangeSet() }
    private var values = ValueStyle.entries.associateWith { ValueRanges() }
    private var paragraphStyles = arrayListOf(ParagraphStyle.DEFAULT)

    val text: CharSequence get() = buffer
    val length: Int get() = buffer.length
    val paragraphCount: Int get() = paragraphCountField

    fun substring(start: Int, end: Int): String = buffer.substring(start, end)

    override fun toString(): String = buffer.toString()

    fun addListener(listener: (TextChange) -> Unit) {
        listeners += listener
    }

    /** Called with the first and last paragraph whose formatting changed. */
    fun addFormatListener(listener: (first: Int, last: Int) -> Unit) {
        formatListeners += listener
    }

    /** Goes up whenever any formatting changes, so a caller can tell whether a command did anything. */
    var formatVersion = 0L
        private set

    private fun formatChanged(first: Int, last: Int) {
        formatVersion++
        for (l in formatListeners) l(first, last)
    }

    // --- Character styles ---

    fun styleRanges(style: CharStyle): RangeSet = styles.getValue(style)

    fun hasStyle(style: CharStyle, offset: Int): Boolean = styles.getValue(style).contains(offset)

    /** Whether every character of [start, end) has [style]. */
    fun hasStyleThroughout(style: CharStyle, start: Int, end: Int): Boolean = styles.getValue(style).coversAll(start, end)

    /** The style text typed at a caret here would inherit. */
    fun styleAtCaret(style: CharStyle, offset: Int): Boolean = styles.getValue(style).extendsTo(offset)

    fun setStyle(style: CharStyle, on: Boolean, start: Int, end: Int) {
        if (start >= end) return
        val set = styles.getValue(style)
        val before = set.copy()
        if (on) set.add(start, end) else set.remove(start, end)
        if (set != before) formatChanged(paragraphAt(start), paragraphAt(end))
    }

    fun valueRanges(style: ValueStyle): ValueRanges = values.getValue(style)

    fun valueAt(style: ValueStyle, offset: Int): Int? = values.getValue(style).valueAt(offset)

    fun valueAtCaret(style: ValueStyle, offset: Int): Int? = values.getValue(style).valueAtCaret(offset)

    /** The value all of [start, end) shares, null for the default, or [ValueRanges.MIXED]. */
    fun uniformValue(style: ValueStyle, start: Int, end: Int): Int? = values.getValue(style).uniformValue(start, end)

    /** Gives [start, end) the [value]; null puts back the default. */
    fun setValue(style: ValueStyle, value: Int?, start: Int, end: Int) {
        if (start >= end) return
        val ranges = values.getValue(style)
        val before = ranges.copy()
        ranges.set(start, end, value)
        if (ranges != before) formatChanged(paragraphAt(start), paragraphAt(end))
    }

    // --- Paragraph styles ---

    fun paragraphStyle(index: Int): ParagraphStyle = paragraphStyles[index]

    fun setParagraphStyle(index: Int, style: ParagraphStyle) {
        if (paragraphStyles[index] == style) return
        paragraphStyles[index] = style
        formatChanged(index, index)
    }

    // --- Undo support ---

    fun snapshot(): FormatSnapshot =
        FormatSnapshot(styles.mapValues { it.value.copy() }, values.mapValues { it.value.copy() }, ArrayList(paragraphStyles))

    /**
     * Puts formatting back to [snapshot], which must have been taken when the text was what it
     * is now. Only the paragraphs whose formatting differs are reported, so undo stays cheap.
     */
    fun restore(snapshot: FormatSnapshot) {
        check(snapshot.paragraphs.size == paragraphCountField) { "A snapshot from a different text" }
        var first = Int.MAX_VALUE
        var last = -1
        for (i in 0 until paragraphCountField) {
            if (paragraphStyles[i] != snapshot.paragraphs[i]) {
                first = minOf(first, i)
                last = maxOf(last, i)
            }
        }
        for ((style, ranges) in snapshot.styles) {
            for (r in styles.getValue(style).differences(ranges)) {
                first = minOf(first, paragraphAt(r.first))
                last = maxOf(last, paragraphAt(r.last))
            }
        }
        for ((style, ranges) in snapshot.values) {
            for (r in values.getValue(style).differences(ranges)) {
                first = minOf(first, paragraphAt(r.first))
                last = maxOf(last, paragraphAt(r.last))
            }
        }
        styles = snapshot.styles.mapValues { it.value.copy() }
        values = snapshot.values.mapValues { it.value.copy() }
        paragraphStyles = ArrayList(snapshot.paragraphs)
        if (last >= 0) formatChanged(first, last)
    }

    fun paragraphStart(index: Int): Int = paragraphStarts[index]

    /** End of the paragraph's text, not counting its '\n'. */
    fun paragraphEnd(index: Int): Int =
        if (index + 1 < paragraphCountField) paragraphStarts[index + 1] - 1 else length

    /** The paragraph containing [offset]; an offset right after a '\n' belongs to the next one. */
    fun paragraphAt(offset: Int): Int {
        var lo = 0
        var hi = paragraphCountField - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (paragraphStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun replace(start: Int, end: Int, inserted: CharSequence): TextChange {
        require(start in 0..end && end <= length) { "[$start, $end) is outside 0..$length" }
        val firstParagraph = paragraphAt(start)
        val lastParagraph = paragraphAt(end)
        val removed = buffer.substring(start, end)
        val insertedText = inserted.toString()
        // Whether the edit reaches the end of its last paragraph: Return there, at the end of
        // a heading, starts body text rather than another heading.
        val atParagraphEnd = end == paragraphEnd(lastParagraph)
        // Text typed over a selection takes the style of the first character it replaces.
        val replacing = removed.isNotEmpty() && insertedText.isNotEmpty()
        val replacedStyles = if (replacing) CharStyle.entries.filter { styles.getValue(it).contains(start) } else null
        val replacedValues = if (replacing) ValueStyle.entries.associateWith { values.getValue(it).valueAt(start) } else null
        buffer.replace(start, end, insertedText)
        for ((style, set) in styles) {
            set.onReplace(start, removed.length, insertedText.length)
            if (replacedStyles != null) {
                if (style in replacedStyles) set.add(start, start + insertedText.length)
                else set.remove(start, start + insertedText.length)
            }
        }
        for ((style, ranges) in values) {
            ranges.onReplace(start, removed.length, insertedText.length)
            if (replacedValues != null) ranges.set(start, start + insertedText.length, replacedValues[style])
        }

        // Starts in (start, end] followed a removed '\n'; starts after `end` shift; each new
        // '\n' adds a start right after itself.
        val newStarts = ArrayList<Int>()
        for (i in insertedText.indices) if (insertedText[i] == '\n') newStarts += start + i + 1
        val delta = insertedText.length - removed.length
        val keptBefore = firstParagraph + 1
        val tailFrom = lastParagraph + 1
        val tailCount = paragraphCountField - tailFrom
        val newCount = keptBefore + newStarts.size + tailCount
        val next = if (newCount > paragraphStarts.size) IntArray(newCount * 2) else paragraphStarts
        paragraphStarts.copyInto(next, keptBefore + newStarts.size, tailFrom, paragraphCountField)
        if (next !== paragraphStarts) paragraphStarts.copyInto(next, 0, 0, keptBefore)
        for (i in newStarts.indices) next[keptBefore + i] = newStarts[i]
        for (i in keptBefore + newStarts.size until newCount) next[i] += delta
        paragraphStarts = next
        paragraphCountField = newCount

        // A paragraph split by Enter keeps its style in both halves (a bullet list goes on);
        // paragraphs joined by deleting a '\n' take the style of the first.
        val kept = paragraphStyles[firstParagraph]
        val lastStyle = paragraphStyles[lastParagraph]
        for (i in lastParagraph downTo firstParagraph + 1) paragraphStyles.removeAt(i)
        // A new checklist item starts unchecked, whatever the one it was split from. After a
        // title, heading or divider comes body text, in the same writing direction.
        val endsBlock = kept.role == Role.TITLE || kept.role == Role.HEADING || kept.role == Role.SUBHEADING || kept.role == Role.DIVIDER
        val continued = when {
            endsBlock && atParagraphEnd -> ParagraphStyle(direction = kept.direction)
            kept.checked -> kept.copy(checked = false)
            else -> kept
        }
        for (i in newStarts.indices) paragraphStyles.add(firstParagraph + 1, continued)
        // A divider's or picture's style stays only with the paragraph that still holds its
        // object. Text split off it is plain; text joined to it after the object went keeps
        // the style it had, so deleting a picture above a heading leaves the heading one.
        if (kept.isObject) {
            for (q in firstParagraph..firstParagraph + newStarts.size) {
                val holdsObject = buffer.length > paragraphStarts[q] && buffer[paragraphStarts[q]] == OBJECT && paragraphEnd(q) == paragraphStarts[q] + 1
                paragraphStyles[q] = when {
                    holdsObject -> kept
                    q == firstParagraph + newStarts.size && !lastStyle.isObject && lastParagraph > firstParagraph -> lastStyle
                    else -> ParagraphStyle(direction = kept.direction)
                }
            }
        }

        val change = TextChange(start, removed, insertedText, firstParagraph, lastParagraph - firstParagraph + 1, newStarts.size + 1)
        for (l in listeners) l(change)
        return change
    }

    companion object {
        /** U+FFFC: where something that is not text (a divider, a picture) stands. */
        const val OBJECT = '\uFFFC'
    }
}
