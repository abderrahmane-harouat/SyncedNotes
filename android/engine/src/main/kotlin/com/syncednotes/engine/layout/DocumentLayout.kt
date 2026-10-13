package com.syncednotes.engine.layout

import com.syncednotes.engine.render.FontFamily
import com.syncednotes.engine.render.FontStack
import com.syncednotes.engine.render.ShapedRun
import com.syncednotes.engine.render.Shaper
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.TextChange
import com.syncednotes.engine.text.TextDocument

/** Where a caret is drawn, in document coordinates. */
data class CaretRect(val x: Float, val top: Float, val bottom: Float, val baseline: Float)

/**
 * Lays out the whole document, one paragraph at a time. An edit throws away only the layouts of
 * the paragraphs it touched; everything else is kept, which is what keeps typing in a long note
 * as fast as typing in a short one.
 */
class DocumentLayout(
    private val document: TextDocument,
    private val fonts: FontFamily,
    private val textSize: Float,
    private val lineHeight: Float,
    /** How far each indent level moves a paragraph in. */
    private val indentWidth: Float,
    /** The room a bullet or number takes before the text (a hanging indent). */
    private val listMarkerWidth: Float,
) {
    private val shapers = HashMap<FontStack, Shaper>()
    private val markerRuns = HashMap<String, ShapedRun>()
    private val baseline: Float
    private var paragraphs = ArrayList<ParagraphLayout?>()
    private var tops = FloatArray(1)
    private var topsValid = false
    var width = 0f
        private set

    init {
        val m = fonts.regular.primary.metrics(textSize)
        // Extra line spacing is split above and below the glyphs, keeping text centred in its line.
        baseline = (lineHeight - (m.ascent + m.descent)) / 2f + m.ascent
        repeat(document.paragraphCount) { paragraphs += null }
        document.addListener(::onChange)
        document.addFormatListener { first, last ->
            for (i in first..minOf(last, paragraphs.size - 1)) paragraphs[i] = null
            topsValid = false
        }
    }

    private fun shaper(stack: FontStack) = shapers.getOrPut(stack) { Shaper(stack) }

    /** A bullet's or number's glyphs, shaped once per label in the regular face. */
    fun markerRun(label: String): ShapedRun =
        markerRuns.getOrPut(label) { shaper(fonts.regular).shape(label, 0, label.length, textSize) }

    private fun onChange(change: TextChange) {
        repeat(change.paragraphsBefore) { paragraphs.removeAt(change.firstParagraph) }
        repeat(change.paragraphsAfter) { paragraphs.add(change.firstParagraph, null) }
        topsValid = false
    }

    fun setWidth(newWidth: Float) {
        if (newWidth == width) return
        width = newWidth
        for (i in paragraphs.indices) paragraphs[i] = null
        topsValid = false
    }

    fun paragraph(index: Int): ParagraphLayout {
        paragraphs[index]?.let { return it }
        val start = document.paragraphStart(index)
        val end = document.paragraphEnd(index)
        val text = document.substring(start, end)

        // Style runs: cut the paragraph wherever any character style starts or stops.
        val cuts = sortedSetOf(0)
        for (style in CharStyle.entries) {
            val set = document.styleRanges(style)
            for (i in 0 until set.count) {
                val a = set.start(i) - start
                val b = set.end(i) - start
                if (b > 0 && a < text.length) {
                    if (a > 0) cuts += a
                    if (b < text.length) cuts += b
                }
            }
        }
        val styleStarts = cuts.toIntArray()
        val styleFlags = IntArray(styleStarts.size) { r ->
            if (text.isEmpty()) 0
            else CharStyle.entries.fold(0) { flags, style ->
                if (document.hasStyle(style, start + styleStarts[r])) flags or (1 shl style.ordinal) else flags
            }
        }

        // Shape each stretch that uses one face; bold and italic are separate font files.
        val parts = ArrayList<Pair<Int, ShapedRun>>()
        var r = 0
        while (r < styleStarts.size && text.isNotEmpty()) {
            val face = faceOf(styleFlags[r])
            var next = r + 1
            while (next < styleStarts.size && faceOf(styleFlags[next]) == face) next++
            val a = styleStarts[r]
            val b = if (next < styleStarts.size) styleStarts[next] else text.length
            parts += a to shaper(face).shape(text, a, b, textSize)
            r = next
        }
        val run = concatenate(parts)

        val style = document.paragraphStyle(index)
        val textLeft = style.indent * indentWidth + if (style.list != ListType.NONE) listMarkerWidth else 0f
        val layout = ParagraphLayout(text, run, styleStarts, styleFlags, width, lineHeight, baseline, textLeft, style.alignment)
        paragraphs[index] = layout
        return layout
    }

    private fun faceOf(flags: Int): FontStack = fonts.stack(
        isBold = flags and (1 shl CharStyle.BOLD.ordinal) != 0,
        isItalic = flags and (1 shl CharStyle.ITALIC.ordinal) != 0,
    )

    private fun concatenate(parts: List<Pair<Int, ShapedRun>>): ShapedRun {
        if (parts.size == 1 && parts[0].first == 0) return parts[0].second
        val n = parts.sumOf { it.second.count }
        val out = ShapedRun(n, IntArray(n), IntArray(n), IntArray(n), FloatArray(n), FloatArray(n), FloatArray(n))
        var o = 0
        for ((offset, part) in parts) {
            for (g in 0 until part.count) {
                out.fontIds[o] = part.fontIds[g]
                out.glyphs[o] = part.glyphs[g]
                out.clusters[o] = part.clusters[g] + offset
                out.advances[o] = part.advances[g]
                out.offsetsX[o] = part.offsetsX[g]
                out.offsetsY[o] = part.offsetsY[g]
                o++
            }
        }
        return out
    }

    private fun ensureTops() {
        if (topsValid) return
        if (tops.size < paragraphs.size + 1) tops = FloatArray(paragraphs.size * 2 + 1)
        var y = 0f
        for (i in paragraphs.indices) {
            tops[i] = y
            y += paragraph(i).height
        }
        tops[paragraphs.size] = y
        topsValid = true
    }

    fun paragraphTop(index: Int): Float {
        ensureTops()
        return tops[index]
    }

    val height: Float get() {
        ensureTops()
        return tops[paragraphs.size]
    }

    /** The paragraph under document y, clamped to the first and last. */
    fun paragraphAtY(y: Float): Int {
        ensureTops()
        var lo = 0
        var hi = paragraphs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun caretRect(offset: Int, upstream: Boolean = false): CaretRect {
        val p = document.paragraphAt(offset)
        val layout = paragraph(p)
        val local = offset - document.paragraphStart(p)
        val line = layout.lineOf(local, upstream)
        val top = paragraphTop(p) + layout.lineTop(line)
        return CaretRect(layout.xOf(local, line), top, top + layout.lineHeight, top + layout.baseline)
    }

    /** The caret position nearest a point; second is true when it is the end of a wrapped line. */
    fun offsetAt(x: Float, y: Float): Pair<Int, Boolean> {
        val p = paragraphAtY(y)
        val layout = paragraph(p)
        val localY = y - paragraphTop(p)
        val line = (localY / layout.lineHeight).toInt().coerceIn(0, layout.lineCount - 1)
        val (local, upstream) = layout.offsetAt(line, x)
        return document.paragraphStart(p) + local to upstream
    }

    /** Document offsets where the visual line holding [offset] starts and ends. */
    fun lineBounds(offset: Int, upstream: Boolean = false): IntRange {
        val p = document.paragraphAt(offset)
        val layout = paragraph(p)
        val base = document.paragraphStart(p)
        val line = layout.lineOf(offset - base, upstream)
        return (base + layout.lineStart(line))..(base + layout.lineEnd(line))
    }
}
