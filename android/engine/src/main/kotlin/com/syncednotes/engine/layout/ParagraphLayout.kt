package com.syncednotes.engine.layout

import com.syncednotes.engine.render.ShapedRun
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.Boundaries

/**
 * One paragraph shaped once and broken into lines. Positions are relative to the paragraph:
 * x from the left edge of the text area, y from the paragraph's top.
 *
 * Shaping happens on the whole paragraph as one unbroken line, then lines are cut from it, so
 * moving a line break never needs another trip to the shaper.
 */
class ParagraphLayout(
    val text: String,
    run: ShapedRun,
    val runs: StyleRuns,
    maxWidth: Float,
    /** Ascent and descent of the font per pixel of text size. */
    private val ascentPerPx: Float,
    private val descentPerPx: Float,
    /** Line height per pixel of text size: Samsung's lines are 1.35 times the text size. */
    private val lineSpacing: Float,
    /** Where the text starts: the indent, plus room for a bullet or number. */
    val textLeft: Float,
    val alignment: Alignment,
) {
    /** caretX[i] is the x of the boundary before character i on the unbroken line. */
    private val caretX = FloatArray(text.length + 1)
    val glyphCount = run.count
    val fontIds = run.fontIds
    val glyphs = run.glyphs
    val clusters = run.clusters
    /** Glyph origin x on the unbroken line. */
    private val glyphX = FloatArray(run.count)
    private val glyphY = run.offsetsY
    private val available = (maxWidth - textLeft).coerceAtLeast(1f)
    val lineStarts: IntArray
    val lineCount: Int
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

        // Each line is as tall as its largest text, with the extra spacing split above and
        // below the glyphs so the text sits centred in its line.
        lineTops = FloatArray(lineCount)
        lineHeights = FloatArray(lineCount)
        lineBaselines = FloatArray(lineCount)
        lineSizes = FloatArray(lineCount)
        var y = 0f
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
            val h = size * lineSpacing
            lineTops[line] = y
            lineHeights[line] = h
            lineBaselines[line] = (h - (ascent + descent)) / 2f + ascent
            lineSizes[line] = size
            y += h
        }
        height = y
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

    /** The x where a line's text begins, after indent and alignment. */
    fun lineLeft(line: Int): Float = textLeft + when (alignment) {
        Alignment.LEFT -> 0f
        Alignment.CENTER -> ((available - lineWidth(line)) / 2f).coerceAtLeast(0f)
        Alignment.RIGHT -> (available - lineWidth(line)).coerceAtLeast(0f)
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

    fun xOf(offset: Int, line: Int): Float = lineLeft(line) + caretX[offset] - caretX[lineStarts[line]]

    /** The nearest caret position to [x] on [line], and whether it sits at a soft wrap's end. */
    fun offsetAt(line: Int, x: Float): Pair<Int, Boolean> {
        val start = lineStart(line)
        val end = lineEnd(line)
        val wrapped = line + 1 < lineCount
        var best = start
        var bestDistance = Float.MAX_VALUE
        var i = start
        while (true) {
            val d = kotlin.math.abs(xOf(i, line) - x)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
            if (i >= end) break
            i = Boundaries.nextCluster(text, i)
        }
        return best to (wrapped && best == end)
    }

    /** Glyph origins, relative to the text area's left edge and the paragraph's top. */
    fun glyphX(g: Int, line: Int): Float = lineLeft(line) + glyphX[g] - caretX[lineStarts[line]]

    fun glyphBaselineY(g: Int, line: Int): Float = lineTops[line] + lineBaselines[line] + glyphY[g]
}
