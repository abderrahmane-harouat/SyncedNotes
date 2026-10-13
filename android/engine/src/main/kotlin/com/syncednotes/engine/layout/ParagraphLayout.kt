package com.syncednotes.engine.layout

import com.syncednotes.engine.render.ShapedRun
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.Boundaries
import com.syncednotes.engine.text.CharStyle

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
    /** Style runs: styleStarts[i] is where run i begins; styleFlags[i] has bit `1 shl ordinal` per CharStyle. */
    private val styleStarts: IntArray,
    private val styleFlags: IntArray,
    maxWidth: Float,
    val lineHeight: Float,
    /** Distance from a line's top to its baseline. */
    val baseline: Float,
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
        height = lineCount * lineHeight
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
    fun lineTop(line: Int): Float = line * lineHeight

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

    fun glyphBaselineY(g: Int, line: Int): Float = lineTop(line) + baseline + glyphY[g]

    val styleRunCount: Int get() = styleStarts.size
    fun styleRunStart(i: Int): Int = styleStarts[i]
    fun styleRunEnd(i: Int): Int = if (i + 1 < styleStarts.size) styleStarts[i + 1] else text.length
    fun styleRunHas(i: Int, style: CharStyle): Boolean = styleFlags[i] and (1 shl style.ordinal) != 0
}
