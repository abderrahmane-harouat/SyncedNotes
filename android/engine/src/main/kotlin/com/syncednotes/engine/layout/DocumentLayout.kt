package com.syncednotes.engine.layout

import com.syncednotes.engine.render.FontFamily
import com.syncednotes.engine.render.FontStack
import com.syncednotes.engine.render.ShapedRun
import com.syncednotes.engine.render.Shaper
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.TextChange
import com.syncednotes.engine.text.TextDocument
import com.syncednotes.engine.text.ValueStyle

/**
 * Where a caret is drawn, in document coordinates: its line, its baseline, and the height of
 * the text it stands next to.
 */
data class CaretRect(val x: Float, val top: Float, val bottom: Float, val baseline: Float, val ascent: Float, val descent: Float)

/**
 * Lays out the whole document, one paragraph at a time. An edit throws away only the layouts of
 * the paragraphs it touched; everything else is kept, which is what keeps typing in a long note
 * as fast as typing in a short one.
 *
 * Sizes are in Samsung's unit: 1/360 of the page width, so "17" is the same share of the page
 * on every phone ([sizeUnit] is that unit in pixels).
 */
class DocumentLayout(
    private val document: TextDocument,
    private val fonts: FontFamily,
    private val sizeUnit: Float,
    private val defaultSize: Int,
    private val lineSpacing: Float,
    /** How far each indent level moves a paragraph in. */
    private val indentWidth: Float,
    /** The room a bullet, number or checkbox takes before the text (a hanging indent). */
    private val listMarkerWidth: Float,
) {
    private val shapers = HashMap<FontStack, Shaper>()
    private val markerRuns = HashMap<Pair<String, Float>, ShapedRun>()
    private val ascentPerPx: Float
    private val descentPerPx: Float
    private var paragraphs = ArrayList<ParagraphLayout?>()
    private var tops = FloatArray(1)
    private var topsValid = false
    var width = 0f
        private set

    init {
        val m = fonts.regular.primary.metrics(1f)
        ascentPerPx = m.ascent
        descentPerPx = m.descent
        repeat(document.paragraphCount) { paragraphs += null }
        document.addListener(::onChange)
        document.addFormatListener { first, last ->
            for (i in first..minOf(last, paragraphs.size - 1)) paragraphs[i] = null
            topsValid = false
        }
    }

    /** Pixels for a size in Samsung's units; null means the default size. */
    fun pixels(size: Int?): Float = (size ?: defaultSize) * sizeUnit

    private fun onChange(change: TextChange) {
        repeat(change.paragraphsBefore) { paragraphs.removeAt(change.firstParagraph) }
        repeat(change.paragraphsAfter) { paragraphs.add(change.firstParagraph, null) }
        topsValid = false
    }

    private fun shaper(stack: FontStack) = shapers.getOrPut(stack) { Shaper(stack) }

    /** A list number's glyphs, shaped once per label and size in the regular face. */
    fun markerRun(label: String, size: Float): ShapedRun =
        markerRuns.getOrPut(label to size) { shaper(fonts.regular).shape(label, 0, label.length, size) }

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
        val runs = styleRuns(start, text.length)

        // Shape each stretch that uses one face at one size: bold and italic are separate
        // font files, and glyph advances scale with the size.
        val parts = ArrayList<Pair<Int, ShapedRun>>()
        var r = 0
        while (r < runs.count && text.isNotEmpty()) {
            val face = faceOf(runs.flags[r])
            val size = runs.sizes[r]
            var next = r + 1
            while (next < runs.count && faceOf(runs.flags[next]) == face && runs.sizes[next] == size) next++
            val a = runs.start(r)
            val b = runs.end(next - 1)
            parts += a to shaper(face).shape(text, a, b, size)
            r = next
        }

        val style = document.paragraphStyle(index)
        val textLeft = style.indent * indentWidth + if (style.list != ListType.NONE) listMarkerWidth else 0f
        val layout = ParagraphLayout(text, concatenate(parts), runs, width, ascentPerPx, descentPerPx, lineSpacing, textLeft, style.alignment)
        paragraphs[index] = layout
        return layout
    }

    /** Cuts the paragraph wherever any character style or value starts or stops. */
    private fun styleRuns(start: Int, length: Int): StyleRuns {
        val cuts = sortedSetOf(0)
        fun cut(a: Int, b: Int) {
            val la = a - start
            val lb = b - start
            if (lb > 0 && la < length) {
                if (la > 0) cuts += la
                if (lb < length) cuts += lb
            }
        }
        for (style in CharStyle.entries) {
            val set = document.styleRanges(style)
            for (i in 0 until set.count) cut(set.start(i), set.end(i))
        }
        for (style in ValueStyle.entries) {
            val ranges = document.valueRanges(style)
            for (i in 0 until ranges.count) cut(ranges.start(i), ranges.end(i))
        }
        val starts = cuts.toIntArray()
        val n = starts.size
        val flags = IntArray(n)
        val sizes = FloatArray(n)
        val colors = IntArray(n)
        val highlights = IntArray(n)
        for (r in 0 until n) {
            // An empty paragraph has no characters; it takes what typing there would get.
            val empty = length == 0
            val at = start + starts[r]
            fun value(style: ValueStyle) = if (empty) document.valueAtCaret(style, at) else document.valueAt(style, at)
            if (!empty) {
                for (style in CharStyle.entries) if (document.hasStyle(style, at)) flags[r] = flags[r] or (1 shl style.ordinal)
            }
            sizes[r] = pixels(value(ValueStyle.SIZE))
            colors[r] = value(ValueStyle.COLOR) ?: 0
            highlights[r] = value(ValueStyle.HIGHLIGHT) ?: 0
        }
        return StyleRuns(starts, flags, sizes, colors, highlights, length)
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
        val size = layout.sizeAtCaret(local)
        return CaretRect(
            layout.xOf(local, line), top, top + layout.lineHeight(line), top + layout.baseline(line),
            layout.ascentFor(size), layout.descentFor(size),
        )
    }

    /** The caret position nearest a point; second is true when it is the end of a wrapped line. */
    fun offsetAt(x: Float, y: Float): Pair<Int, Boolean> {
        val p = paragraphAtY(y)
        val layout = paragraph(p)
        val line = layout.lineAtY(y - paragraphTop(p))
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
