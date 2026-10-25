package com.syncednotes.engine.layout

import com.syncednotes.engine.render.ShapedRun
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.Bidi
import com.syncednotes.engine.text.Boundaries

/**
 * One paragraph shaped once and broken into lines. Positions are relative to the paragraph:
 * x from the left edge of the text area, y from the paragraph's top.
 *
 * Shaping happens on the whole paragraph as one unbroken line, then lines are cut from it, so
 * moving a line break never needs another trip to the shaper. Widths are the same whichever
 * way text runs, so lines are cut in the text's own order; only then is each line put in the
 * order it is seen in, when it holds text running right to left ([levels]).
 */
class ParagraphLayout(
    val text: String,
    run: ShapedRun,
    val runs: StyleRuns,
    /** The width it was broken into lines for, margins included. */
    val maxWidth: Float,
    /** Ascent and descent of the font per pixel of text size. */
    private val ascentPerPx: Float,
    private val descentPerPx: Float,
    /** Line height per pixel of text size: Samsung's lines are 1.35 times the text size. */
    private val lineSpacing: Float,
    /** Where the text starts: the indent, plus room for a bullet or number. */
    val textLeft: Float,
    val alignment: Alignment,
    /** How far the text stops short of the right edge: a code block's inset. */
    val textRight: Float = 0f,
    /** Each unit's bidi level (see [Bidi]); null when all of it runs left to right. */
    private val levels: ByteArray? = null,
    /** The paragraph's level: 1 when it runs right to left. */
    private val baseLevel: Int = 0,
    /**
     * For a picture: its height as drawn. Its one line is that tall, with half a line of text
     * spread above and below; the picture's width is its one glyph's advance.
     */
    val objectHeight: Float = 0f,
    /**
     * Room above the first line and below the last, inside the paragraph: a code block's
     * padding, so its text keeps clear of its panel's edges, with the language's name above.
     */
    val spaceAbove: Float = 0f,
    val spaceBelow: Float = 0f,
) {
    /** Where the picture's top is in its line. */
    val objectTop: Float
    /** The picture's width as drawn. */
    val objectWidth: Float = if (objectHeight > 0f && run.count > 0) run.advances[0] else 0f

    /** Whether the paragraph runs right to left: its lists and indents are on the right. */
    val rtl: Boolean get() = baseLevel % 2 == 1

    /** caretX[i] is the x of the boundary before character i on the unbroken line. */
    private val caretX = FloatArray(text.length + 1)
    val glyphCount = run.count
    val fontIds = run.fontIds
    val glyphs = run.glyphs
    val clusters = run.clusters
    /** Glyph origin x on the unbroken line. */
    private val glyphX = FloatArray(run.count)
    /** Where each glyph's pen starts on the unbroken line, and how far it moves it. */
    private val glyphPen = FloatArray(run.count)
    private val glyphAdvance = run.advances
    private val glyphOffsetX = run.offsetsX
    private val glyphY = run.offsetsY
    private val available = (maxWidth - textLeft - textRight).coerceAtLeast(1f)
    val lineStarts: IntArray
    val lineCount: Int
    /** The text on one unbroken line: what a table's column needs to show it without wrapping. */
    val naturalWidth: Float
    /** The widest stretch between two places a line may break: a column narrower cuts a word. */
    val longestWord: Float
    val height: Float
    private val lineTops: FloatArray
    private val lineHeights: FloatArray
    private val lineBaselines: FloatArray
    /** The largest text size on each line, which sets its height. */
    private val lineSizes: FloatArray

    init {
        var pen = 0f
        var filled = 0
        for (g in 0 until run.count) {
            val cluster = run.clusters[g]
            while (filled <= cluster && filled <= text.length) caretX[filled++] = pen
            glyphX[g] = pen + run.offsetsX[g]
            glyphPen[g] = pen
            pen += run.advances[g]
        }
        while (filled <= text.length) caretX[filled++] = pen

        val starts = ArrayList<Int>()
        starts += 0
        val breaks = LineBreaker.opportunities(text, 0, text.length)
        var lineStart = 0
        while (lineStart < text.length) {
            val end = breakLine(lineStart, breaks, available)
            if (end >= text.length) break
            starts += end
            lineStart = end
        }
        lineStarts = starts.toIntArray()
        lineCount = lineStarts.size
        naturalWidth = widthOf(0, text.length)
        var widest = 0f
        var from = 0
        for (i in 1..text.length) {
            if (i == text.length || breaks[i]) {
                widest = maxOf(widest, widthOf(from, i))
                from = i
            }
        }
        longestWord = widest

        // Each line is as tall as its largest text, with the extra spacing split above and
        // below the glyphs so the text sits centred in its line.
        lineTops = FloatArray(lineCount)
        lineHeights = FloatArray(lineCount)
        lineBaselines = FloatArray(lineCount)
        lineSizes = FloatArray(lineCount)
        var y = spaceAbove
        for (line in 0 until lineCount) {
            val ls = lineStarts[line]
            val le = if (line + 1 < lineCount) lineStarts[line + 1] else text.length
            var size = runs.sizes[runs.runAt(ls)]
            var r = runs.runAt(ls)
            while (r < runs.count && runs.start(r) < le) {
                size = maxOf(size, runs.sizes[r])
                r++
            }
            val ascent = size * ascentPerPx
            val descent = size * descentPerPx
            lineTops[line] = y
            if (objectHeight > 0f) {
                // The caret beside a picture stands at its foot, as next to a tall letter.
                val gap = size * 0.5f
                lineHeights[line] = objectHeight + gap
                lineBaselines[line] = gap / 2f + objectHeight
            } else {
                val h = size * lineSpacing
                lineHeights[line] = h
                lineBaselines[line] = (h - (ascent + descent)) / 2f + ascent
            }
            val h = lineHeights[line]
            lineSizes[line] = size
            y += h
        }
        height = y + spaceBelow
        objectTop = if (objectHeight > 0f) lineSizes.firstOrNull()?.let { it * 0.25f } ?: 0f else 0f
    }

    private fun widthOf(start: Int, end: Int): Float {
        var e = end
        // Spaces at the end of a line hang past the margin rather than forcing a break.
        while (e > start && LineBreaker.isSpace(text[e - 1])) e--
        return caretX[e] - caretX[start]
    }

    private fun breakLine(start: Int, breaks: BooleanArray, maxWidth: Float): Int {
        var lastFit = -1
        var i = start + 1
        while (i <= text.length) {
            if (breaks[i]) {
                if (widthOf(start, i) <= maxWidth) lastFit = i else break
            }
            i++
        }
        if (lastFit > start) return lastFit
        // A single word wider than the line is cut between characters, keeping at least one.
        var end = Boundaries.nextCluster(text, start)
        while (end < text.length) {
            val next = Boundaries.nextCluster(text, end)
            if (caretX[next] - caretX[start] > maxWidth) break
            end = next
        }
        return end
    }

    fun lineStart(line: Int): Int = lineStarts[line]
    fun lineEnd(line: Int): Int = if (line + 1 < lineCount) lineStarts[line + 1] else text.length
    fun lineTop(line: Int): Float = lineTops[line]
    fun lineHeight(line: Int): Float = lineHeights[line]
    /** Distance from the line's top to its baseline. */
    fun baseline(line: Int): Float = lineBaselines[line]

    /** The line under [y], measured from the paragraph's top, clamped to the first and last. */
    fun lineAtY(y: Float): Int {
        var line = 0
        while (line + 1 < lineCount && lineTops[line + 1] <= y) line++
        return line
    }

    /** The text size a caret at [offset] stands for: the character before it, as typing would inherit. */
    fun sizeAtCaret(offset: Int): Float = runs.sizes[runs.runAt(maxOf(0, offset - 1))]

    fun ascentFor(size: Float) = size * ascentPerPx
    fun descentFor(size: Float) = size * descentPerPx

    /** Where the visible text of a line ends, ignoring the spaces that hang past the margin. */
    fun lineWidth(line: Int): Float = widthOf(lineStart(line), lineEnd(line))

    /** The left edge of the text area and its right edge, between the margins. */
    val leftEdge: Float get() = textLeft
    val rightEdge: Float get() = textLeft + available

    /** The x where a line's visible text begins, after indent and alignment. */
    fun lineLeft(line: Int): Float = textLeft + when (shownAlignment) {
        // Justified lines are set ragged for now, which shows every word without stretching
        // the spaces.
        Alignment.NATURAL, Alignment.LEFT, Alignment.JUSTIFIED -> 0f
        Alignment.CENTER -> ((available - lineWidth(line)) / 2f).coerceAtLeast(0f)
        Alignment.RIGHT -> (available - lineWidth(line)).coerceAtLeast(0f)
    }

    /** The x where a line's visible text ends. */
    fun lineRight(line: Int): Float = lineLeft(line) + lineWidth(line)

    /** The side the lines come out on: natural is the paragraph's own direction's side. */
    private val shownAlignment: Alignment = when (alignment) {
        Alignment.NATURAL, Alignment.JUSTIFIED -> if (rtl) Alignment.RIGHT else Alignment.LEFT
        else -> alignment
    }

    /**
     * The line a caret at [offset] is drawn on. At a soft wrap the same offset is both the end of
     * one line and the start of the next; [upstream] picks the earlier line.
     */
    fun lineOf(offset: Int, upstream: Boolean = false): Int {
        var line = 0
        while (line + 1 < lineCount && lineStarts[line + 1] <= offset) line++
        if (upstream && line > 0 && offset == lineStarts[line]) line--
        return line
    }

    // --- Lines in the order they are seen -------------------------------------------------

    /**
     * A line's runs of one direction, left to right as drawn: run k covers units [starts[k],
     * ends[k]) and begins at x[k], measured from where the line's text starts.
     */
    private class Visual(val starts: IntArray, val ends: IntArray, val rtl: BooleanArray, val x: FloatArray, val origin: Float)

    private val visuals = arrayOfNulls<Visual>(lineCount)

    private fun visual(line: Int): Visual {
        visuals[line]?.let { return it }
        val ls = lineStart(line)
        val le = lineEnd(line)
        val v = if (levels == null) {
            Visual(intArrayOf(ls), intArrayOf(le), booleanArrayOf(false), floatArrayOf(0f), 0f)
        } else {
            // L1: the spaces a line ends with take the paragraph's level, so they hang off its
            // trailing side whichever way the words in front of them run.
            var visibleEnd = le
            while (visibleEnd > ls && LineBreaker.isSpace(text[visibleEnd - 1])) visibleEnd--
            fun levelAt(i: Int) = if (i >= visibleEnd) baseLevel else levels[i].toInt()
            val starts = ArrayList<Int>()
            val runLevels = ArrayList<Int>()
            var i = ls
            while (i < le) {
                val level = levelAt(i)
                starts += i
                runLevels += level
                while (i < le && levelAt(i) == level) i++
            }
            if (starts.isEmpty()) { starts += ls; runLevels += baseLevel }
            val order = Bidi.visualOrder(runLevels.toIntArray())
            val n = order.size
            val vs = IntArray(n)
            val ve = IntArray(n)
            val vr = BooleanArray(n)
            val vx = FloatArray(n)
            var x = 0f
            for (k in 0 until n) {
                val r = order[k]
                vs[k] = starts[r]
                ve[k] = if (r + 1 < starts.size) starts[r + 1] else le
                vr[k] = Bidi.isRtl(runLevels[r])
                vx[k] = x
                x += caretX[ve[k]] - caretX[vs[k]]
            }
            // The hanging spaces of a right-to-left line are on its left, outside the margin.
            val hang = (caretX[le] - caretX[ls]) - (caretX[visibleEnd] - caretX[ls])
            Visual(vs, ve, vr, vx, if (rtl) -hang else 0f)
        }
        visuals[line] = v
        return v
    }

    /** The visual run holding unit [i] on [line]. */
    private fun runOf(v: Visual, i: Int): Int {
        for (k in v.starts.indices) if (i >= v.starts[k] && i < v.ends[k]) return k
        return v.starts.size - 1
    }

    /** The x of the edge of unit [i] that comes first in reading order: its left in left-to-right text. */
    private fun leadingEdge(line: Int, v: Visual, k: Int, i: Int): Float {
        val base = lineLeft(line) + v.origin + v.x[k]
        val from = caretX[i] - caretX[v.starts[k]]
        return if (v.rtl[k]) base + (caretX[v.ends[k]] - caretX[v.starts[k]]) - from else base + from
    }

    /**
     * Where a caret at [offset] is drawn on [line]. Between two runs of different directions,
     * it goes with the word before it, where typing would continue it; after a space, with the
     * word that follows, which is where a click before that word was made.
     */
    fun xOf(offset: Int, line: Int): Float {
        val ls = lineStart(line)
        val le = lineEnd(line)
        if (levels == null) return lineLeft(line) + caretX[offset] - caretX[ls]
        val v = visual(line)
        if (offset <= ls) {
            if (ls == le) return if (rtl) lineRight(line) else lineLeft(line)
            return leadingEdge(line, v, runOf(v, ls), ls)
        }
        val before = Boundaries.previousCluster(text, offset).coerceAtLeast(ls)
        if (offset < le && LineBreaker.isSpace(text[before])) return leadingEdge(line, v, runOf(v, offset), offset)
        val k = runOf(v, before)
        return leadingEdge(line, v, k, offset.coerceAtMost(v.ends[k]))
    }

    /** The nearest caret position to [x] on [line], and whether it sits at a soft wrap's end. */
    fun offsetAt(line: Int, x: Float): Pair<Int, Boolean> {
        val start = lineStart(line)
        val end = lineEnd(line)
        val wrapped = line + 1 < lineCount
        val best = if (levels == null) {
            var found = start
            var bestDistance = Float.MAX_VALUE
            var i = start
            while (true) {
                val d = kotlin.math.abs(xOf(i, line) - x)
                if (d < bestDistance) {
                    bestDistance = d
                    found = i
                }
                if (i >= end) break
                i = Boundaries.nextCluster(text, i)
            }
            found
        } else {
            offsetAtMixed(line, x, start, end)
        }
        return best to (wrapped && best == end)
    }

    private fun offsetAtMixed(line: Int, x: Float, start: Int, end: Int): Int {
        val v = visual(line)
        var leftmost = Float.MAX_VALUE
        var rightmost = -Float.MAX_VALUE
        var leftOffset = start
        var rightOffset = end
        for (k in v.starts.indices) {
            var i = v.starts[k]
            while (i < v.ends[k]) {
                val next = Boundaries.nextCluster(text, i).coerceAtMost(v.ends[k])
                val a = leadingEdge(line, v, k, i)
                val b = leadingEdge(line, v, k, next)
                val left = minOf(a, b)
                val right = maxOf(a, b)
                if (x in left..right) {
                    // The half nearer the character's leading edge puts the caret before it.
                    return if (kotlin.math.abs(x - a) <= kotlin.math.abs(x - b)) i else next
                }
                if (left < leftmost) { leftmost = left; leftOffset = if (v.rtl[k]) next else i }
                if (right > rightmost) { rightmost = right; rightOffset = if (v.rtl[k]) i else next }
                i = next
            }
        }
        return if (x < leftmost) leftOffset else rightOffset
    }

    /**
     * Where the units [start, end) are drawn on [line], as left and right x pairs: one pair
     * per stretch, since text that changes direction mid-line is selected in pieces.
     */
    fun spans(line: Int, start: Int, end: Int): FloatArray {
        val v = visual(line)
        val out = ArrayList<Float>(4)
        for (k in v.starts.indices) {
            val a = maxOf(start, v.starts[k])
            val b = minOf(end, v.ends[k])
            if (a >= b) continue
            val x0 = leadingEdge(line, v, k, a)
            val x1 = leadingEdge(line, v, k, b)
            out += minOf(x0, x1)
            out += maxOf(x0, x1)
        }
        return out.toFloatArray()
    }

    /** Glyph origins, relative to the text area's left edge and the paragraph's top. */
    fun glyphX(g: Int, line: Int): Float {
        val ls = lineStarts[line]
        if (levels == null) return lineLeft(line) + glyphX[g] - caretX[ls]
        val v = visual(line)
        val k = runOf(v, clusters[g])
        val base = lineLeft(line) + v.origin + v.x[k]
        val from = glyphPen[g] - caretX[v.starts[k]]
        // Right to left, the pen moves leftwards from the run's right edge.
        return if (v.rtl[k]) base + (caretX[v.ends[k]] - caretX[v.starts[k]]) - from - glyphAdvance[g] + glyphOffsetX[g]
        else base + from + glyphOffsetX[g]
    }

    fun glyphBaselineY(g: Int, line: Int): Float = lineTops[line] + lineBaselines[line] + glyphY[g]
}
