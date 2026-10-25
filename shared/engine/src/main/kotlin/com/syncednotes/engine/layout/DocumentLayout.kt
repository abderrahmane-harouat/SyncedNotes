package com.syncednotes.engine.layout

import com.syncednotes.engine.code.LineState
import com.syncednotes.engine.code.Syntax
import com.syncednotes.engine.code.Token
import com.syncednotes.engine.render.FontFamily
import com.syncednotes.engine.render.FontStack
import com.syncednotes.engine.render.ShapedRun
import com.syncednotes.engine.render.Shaper
import com.syncednotes.engine.text.Bidi
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TableCell
import com.syncednotes.engine.text.TextChange
import com.syncednotes.engine.text.TextDocument
import com.syncednotes.engine.text.ValueStyle
import com.syncednotes.engine.text.relativeSize

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
    /** The room a bullet, number or checkbox takes before the text (a hanging indent); one per list level. */
    private val listMarkerWidth: Float,
    private val quoteIndent: Float = 0f,
    private val codeIndent: Float = 0f,
    /** Pixels per point for a picture's own size, which it is shown at unless the note is narrower. */
    private val pictureScale: Float = 1f,
    /** A picture's own size in points, by handle; null when unknown. */
    var pictureSize: (Int) -> Pair<Int, Int>? = { null },
    /** Room at the top of a code block, where its language is named, and at its foot. */
    private val codeTop: Float = 0f,
    private val codeBottom: Float = 0f,
    /** A callout's text's room from its panel's sides, and above and below, outside margin included. */
    private val calloutIndent: Float = 0f,
    private val calloutTop: Float = 0f,
    private val calloutBottom: Float = 0f,
    /** A table cell's text's room from its lines, across and down. */
    private val cellPaddingX: Float = 0f,
    private val cellPaddingY: Float = 0f,
    /** Room above a table and below it, apart from the text around. */
    private val tableMargin: Float = 0f,
    /** How wide a column is while its text needs less, so a new table does not jump as it fills. */
    private val columnWidth: Float = 0f,
) {
    private val shapers = HashMap<FontStack, Shaper>()
    private val markerRuns = HashMap<Pair<String, Float>, ShapedRun>()
    private val ascentPerPx: Float
    private val descentPerPx: Float
    private var paragraphs = ArrayList<ParagraphLayout?>()
    /**
     * Each paragraph shaped, kept apart from its lines: a table's column widens or narrows as
     * its cells fill, and its cells are then broken into lines again without shaping them again.
     */
    private var shapes = ArrayList<Shaped?>()
    /**
     * What each paragraph leaves open for the one below as code (see [LineState]); START for
     * one that is not code. Kept current as the text and formatting change, apart from the
     * layouts, which are made only when asked for: lexing a line is cheap, shaping it is not.
     * Only a line whose start changed is laid out again, so typing in a long code block lays
     * out one line, not the rest of the block, until a comment is opened or closed.
     */
    private val exits = ArrayList<LineState>()
    private var tops = FloatArray(1)
    /** Where each paragraph's box starts across, how wide and how tall it is: a cell's, in its row. */
    private var lefts = FloatArray(1)
    private var boxWidths = FloatArray(1)
    private var boxHeights = FloatArray(1)
    private var topsValid = false
    var width = 0f
        private set

    init {
        val m = fonts.regular.primary.metrics(1f)
        ascentPerPx = m.ascent
        descentPerPx = m.descent
        repeat(document.paragraphCount) {
            paragraphs += null
            shapes += null
            exits += LineState.START
        }
        relex(0, exits.size - 1)
        document.addListener(::onChange)
        document.addFormatListener { first, last ->
            // The lines either side too: one that becomes code, or stops being code, moves
            // where a code block begins or ends, and so where its padding goes.
            for (i in maxOf(0, first - 1)..minOf(last + 1, paragraphs.size - 1)) {
                paragraphs[i] = null
                shapes[i] = null
            }
            relex(first, minOf(last, exits.size - 1))
            topsValid = false
        }
    }

    /** The normal text size of a paragraph with [role], in pixels. */
    fun basePixels(role: Role): Float = defaultSize * sizeUnit * roleScale(role)

    /** Where a paragraph's text starts, and how far it stops short of the right edge. */
    fun margins(style: ParagraphStyle): Pair<Float, Float> {
        val roleIndent = when (style.role) {
            Role.QUOTE -> quoteIndent
            Role.CODE_BLOCK -> codeIndent
            Role.CALLOUT -> calloutIndent
            // The circle's room, as a list item has its marker's.
            Role.CHECKLIST -> listMarkerWidth
            else -> 0f
        }
        val left = roleIndent + style.depth * listMarkerWidth + style.indent * indentWidth
        return left to when (style.role) {
            Role.CODE_BLOCK -> codeIndent
            Role.CALLOUT -> calloutIndent
            else -> 0f
        }
    }

    private fun onChange(change: TextChange) {
        repeat(change.paragraphsBefore) {
            paragraphs.removeAt(change.firstParagraph)
            shapes.removeAt(change.firstParagraph)
        }
        repeat(change.paragraphsAfter) {
            paragraphs.add(change.firstParagraph, null)
            shapes.add(change.firstParagraph, null)
        }
        // Lines that came or went may have moved where a code block begins or ends.
        if (change.paragraphsBefore != change.paragraphsAfter) {
            if (change.firstParagraph > 0) paragraphs[change.firstParagraph - 1] = null
            val after = change.firstParagraph + change.paragraphsAfter
            if (after < paragraphs.size) paragraphs[after] = null
        }
        // The new lines start out with what the replaced ones left open for the line below,
        // which is what that line was coloured from.
        val leftOpen = exits[change.firstParagraph + change.paragraphsBefore - 1]
        repeat(change.paragraphsBefore) { exits.removeAt(change.firstParagraph) }
        repeat(change.paragraphsAfter) { exits.add(change.firstParagraph, leftOpen) }
        relex(change.firstParagraph, change.firstParagraph + change.paragraphsAfter - 1)
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

    /** One paragraph as the shaper made it, before it is broken into lines. */
    private class Shaped(val text: String, val run: ShapedRun, val runs: StyleRuns, val levels: ByteArray?, val baseLevel: Int)

    private fun shaped(index: Int): Shaped {
        shapes[index]?.let { return it }
        val start = document.paragraphStart(index)
        val end = document.paragraphEnd(index)
        val text = document.substring(start, end)
        val style = document.paragraphStyle(index)
        val tokens = highlight(index, text, style)
        // A table's header row is bold because it is the header, as a heading is.
        val runs = styleRuns(start, text.length, style.role, tokens, bold = style.cell.let { it != null && it.header && it.row == 0 })
        val baseLevel = Bidi.paragraphLevel(text, style.direction)
        val levels = Bidi.levels(text, baseLevel)

        // Shape each stretch that uses one face at one size and runs one way: bold and italic
        // are separate font files, glyph advances scale with the size, and a mark lands on its
        // letter differently right to left.
        val parts = ArrayList<Pair<Int, ShapedRun>>()
        var r = 0
        while (r < runs.count && text.isNotEmpty()) {
            val face = faceOf(runs.flags[r])
            val size = runs.sizes[r]
            var next = r + 1
            while (next < runs.count && faceOf(runs.flags[next]) == face && runs.sizes[next] == size) next++
            val a = runs.start(r)
            val b = runs.end(next - 1)
            var p = a
            while (p < b) {
                val rtl = levels != null && Bidi.isRtl(levels[p].toInt())
                var q = p + 1
                while (q < b && (levels != null && Bidi.isRtl(levels[q].toInt())) == rtl) q++
                if (rtl) {
                    // Brackets read mirrored in text that runs right to left.
                    val mirrored = String(CharArray(q - p) { Bidi.mirror(text[p + it]) })
                    parts += p to shaper(face).shape(mirrored, 0, mirrored.length, size, rtl = true)
                } else {
                    parts += p to shaper(face).shape(text, p, q, size)
                }
                p = q
            }
            r = next
        }
        return Shaped(text, concatenate(parts), runs, levels, baseLevel).also { shapes[index] = it }
    }

    fun paragraph(index: Int): ParagraphLayout {
        paragraphs[index]?.let { return it }
        // A cell is as wide as its column, which the whole table decides: it is laid out with it.
        if (document.paragraphStyle(index).cell != null) {
            topsValid = false
            ensureTops()
            return paragraphs[index]!!
        }
        return build(index, width).also { paragraphs[index] = it }
    }

    /** Paragraph [index] broken into lines [maxWidth] wide. */
    private fun build(index: Int, maxWidth: Float): ParagraphLayout {
        val shape = shaped(index)
        val text = shape.text
        val style = document.paragraphStyle(index)
        val spacing = lineSpacing * (if (style.lineSpacing > 0f) style.lineSpacing else 1f)
        if (style.cell != null) {
            return ParagraphLayout(
                text, shape.run, shape.runs, maxWidth, ascentPerPx, descentPerPx, spacing, cellPaddingX, style.alignment, cellPaddingX,
                shape.levels, shape.baseLevel, spaceAbove = cellPaddingY, spaceBelow = cellPaddingY,
            )
        }
        val (startMargin, endMargin) = margins(style)
        // A right-to-left paragraph has its indent, list markers and quote bar on the right.
        val (textLeft, textRight) = if (shape.baseLevel % 2 == 1) endMargin to startMargin else startMargin to endMargin
        if (style.picture != 0 && text == OBJECT_TEXT) {
            val (w, h) = pictureBox(style.picture, maxWidth - textLeft - textRight)
            val run = ShapedRun(1, intArrayOf(-1), intArrayOf(0), intArrayOf(0), floatArrayOf(w), floatArrayOf(0f), floatArrayOf(0f))
            return ParagraphLayout(
                text, run, shape.runs, maxWidth, ascentPerPx, descentPerPx, spacing, textLeft, style.alignment, textRight, null, 0, objectHeight = h,
            )
        }
        val (above, below) = panelPadding(index, style)
        return ParagraphLayout(
            text, shape.run, shape.runs, maxWidth, ascentPerPx, descentPerPx, spacing, textLeft, style.alignment, textRight,
            shape.levels, shape.baseLevel, spaceAbove = above, spaceBelow = below,
        )
    }

    /**
     * The first line of a code block or a callout has the room at the top of its panel, its last
     * the room at its foot; the lines between have none, so the panel's text runs on as text.
     */
    private fun panelPadding(index: Int, style: ParagraphStyle): Pair<Float, Float> {
        val (top, bottom) = when (style.role) {
            Role.CODE_BLOCK -> codeTop to codeBottom
            Role.CALLOUT -> calloutTop to calloutBottom
            else -> return 0f to 0f
        }
        return (if (isPanelStart(index)) top else 0f) to (if (isPanelEnd(index)) bottom else 0f)
    }

    /**
     * Whether paragraphs [a] and [b] are drawn on one panel: both code, or both callouts of the
     * same colour. A callout of another colour right after one is a panel of its own.
     */
    fun samePanel(a: Int, b: Int): Boolean {
        if (a !in 0 until document.paragraphCount || b !in 0 until document.paragraphCount) return false
        val x = document.paragraphStyle(a)
        val y = document.paragraphStyle(b)
        return when (x.role) {
            Role.CODE_BLOCK -> y.role == Role.CODE_BLOCK
            Role.CALLOUT -> y.role == Role.CALLOUT && y.callout == x.callout
            else -> false
        }
    }

    fun isPanelStart(index: Int): Boolean = !samePanel(index - 1, index)

    fun isPanelEnd(index: Int): Boolean = !samePanel(index, index + 1)

    /** Whether paragraph [index] is the first of a code block. */
    fun isBlockStart(index: Int): Boolean =
        document.paragraphStyle(index).role == Role.CODE_BLOCK && (index == 0 || document.paragraphStyle(index - 1).role != Role.CODE_BLOCK)

    /** The paragraph's code coloured by its language; null for one that is not code with a language. */
    private fun highlight(index: Int, text: String, style: ParagraphStyle): List<Token>? =
        syntaxOf(style)?.highlight(text, entryState(index))?.tokens

    private fun syntaxOf(style: ParagraphStyle): Syntax? = if (style.role == Role.CODE_BLOCK) Syntax.of(style.language) else null

    /** What the code above leaves open at the start of paragraph [index]. */
    private fun entryState(index: Int): LineState =
        if (index == 0 || document.paragraphStyle(index - 1).role != Role.CODE_BLOCK) LineState.START else exits[index - 1]

    /**
     * Works out again what paragraphs [first]..[last] leave open, and goes on down for as
     * long as that changes what the next one begins inside, throwing away the layout of each
     * line whose colours change with it.
     */
    private fun relex(first: Int, last: Int) {
        var p = first
        while (p < exits.size) {
            val style = document.paragraphStyle(p)
            val exit = syntaxOf(style)?.highlight(document.substring(document.paragraphStart(p), document.paragraphEnd(p)), entryState(p))?.exit
                ?: LineState.START
            val changed = exit != exits[p]
            exits[p] = exit
            if (p >= last && !changed) return
            if (changed && p + 1 < paragraphs.size) {
                paragraphs[p + 1] = null
                shapes[p + 1] = null
            }
            p++
        }
    }

    /**
     * Cuts the paragraph wherever any character style or value starts or stops, and in code,
     * wherever its syntax does. A title or heading is bold throughout and a code block
     * monospaced throughout, because of what it is.
     */
    private fun styleRuns(start: Int, length: Int, role: Role, tokens: List<Token>?, bold: Boolean = false): StyleRuns {
        val roleFlags = when {
            bold || role == Role.TITLE || role == Role.HEADING || role == Role.SUBHEADING -> 1 shl CharStyle.BOLD.ordinal
            role == Role.CODE_BLOCK -> 1 shl CharStyle.CODE.ordinal
            else -> 0
        }
        val base = basePixels(role)
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
        if (tokens != null) for (t in tokens) {
            if (t.start > 0) cuts += t.start
            if (t.end < length) cuts += t.end
        }
        val starts = cuts.toIntArray()
        val n = starts.size
        val syntax = if (tokens != null) IntArray(n) else null
        var t = 0
        val flags = IntArray(n)
        val sizes = FloatArray(n)
        val colors = IntArray(n)
        val highlights = IntArray(n)
        for (r in 0 until n) {
            flags[r] = roleFlags
            // An empty paragraph has no characters; it takes what typing there would get.
            val empty = length == 0
            val at = start + starts[r]
            fun value(style: ValueStyle) = if (empty) document.valueAtCaret(style, at) else document.valueAt(style, at)
            if (!empty) {
                for (style in CharStyle.entries) if (document.hasStyle(style, at)) flags[r] = flags[r] or (1 shl style.ordinal)
            }
            sizes[r] = base * relativeSize(value(ValueStyle.SIZE))
            colors[r] = value(ValueStyle.COLOR) ?: 0
            highlights[r] = value(ValueStyle.HIGHLIGHT) ?: 0
            if (syntax != null) {
                while (t < tokens!!.size && tokens[t].end <= starts[r]) t++
                if (t < tokens.size && tokens[t].start <= starts[r]) syntax[r] = tokens[t].kind.ordinal + 1
            }
        }
        return StyleRuns(starts, flags, sizes, colors, highlights, length, syntax)
    }

    /**
     * The size a picture is drawn at: its own, in points, unless that is wider than the note,
     * when it shrinks to fit; never enlarged. One of unknown size takes the width, at 4:3.
     */
    private fun pictureBox(picture: Int, available: Float): Pair<Float, Float> {
        val room = available.coerceAtLeast(1f)
        val (pw, ph) = pictureSize(picture) ?: return room to room * 0.75f
        if (pw <= 0 || ph <= 0) return room to room * 0.75f
        val w = minOf(pw * pictureScale, room)
        return w to w * ph / pw
    }

    /** Forgets every paragraph's layout: pictures' sizes became known, say. */
    fun invalidate() {
        for (i in paragraphs.indices) paragraphs[i] = null
        topsValid = false
    }

    private fun faceOf(flags: Int): FontStack = fonts.stack(
        isBold = flags and (1 shl CharStyle.BOLD.ordinal) != 0,
        isItalic = flags and (1 shl CharStyle.ITALIC.ordinal) != 0,
        isCode = flags and (1 shl CharStyle.CODE.ordinal) != 0,
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
        val n = paragraphs.size
        if (tops.size < n + 1) {
            tops = FloatArray(n * 2 + 1)
            lefts = FloatArray(n * 2 + 1)
            boxWidths = FloatArray(n * 2 + 1)
            boxHeights = FloatArray(n * 2 + 1)
        }
        // Set before the paragraphs are laid out, which may ask where a cell is.
        topsValid = true
        var y = 0f
        var i = 0
        while (i < n) {
            val cell = document.paragraphStyle(i).cell
            if (cell == null) {
                val h = paragraph(i).height
                tops[i] = y
                lefts[i] = 0f
                boxWidths[i] = width
                boxHeights[i] = h
                y += h
                i++
                continue
            }
            var last = i
            while (last + 1 < n && document.paragraphStyle(last + 1).cell?.table == cell.table) last++
            y = layoutTable(i, last, y)
            i = last + 1
        }
        tops[n] = y
    }

    /**
     * Lays out the table of paragraphs [first]..[last] from [top] down, and returns where it ends.
     * Its columns are as wide as their text, as a web page's table is, and share out the note's
     * width when that is not enough; each row is as tall as its tallest cell.
     */
    private fun layoutTable(first: Int, last: Int, top: Float): Float {
        var columns = 0
        for (k in first..last) columns = maxOf(columns, document.paragraphStyle(k).cell!!.column + 1)
        val natural = FloatArray(columns)
        val word = FloatArray(columns)
        for (k in first..last) {
            val c = document.paragraphStyle(k).cell!!.column
            // Any width tells how wide the text is on one line; the one already made will do.
            val probe = paragraphs[k] ?: build(k, Float.MAX_VALUE / 4)
            natural[c] = maxOf(natural[c], probe.naturalWidth)
            word[c] = maxOf(word[c], probe.longestWord)
        }
        val widths = columnWidths(natural, word)
        val left = FloatArray(columns)
        for (c in 1 until columns) left[c] = left[c - 1] + widths[c - 1]

        var y = top + tableMargin
        var k = first
        while (k <= last) {
            val row = document.paragraphStyle(k).cell!!.row
            var end = k
            while (end + 1 <= last && document.paragraphStyle(end + 1).cell!!.row == row) end++
            var height = 0f
            for (q in k..end) {
                val c = document.paragraphStyle(q).cell!!.column
                val layout = paragraphs[q]?.takeIf { it.maxWidth == widths[c] } ?: build(q, widths[c])
                paragraphs[q] = layout
                height = maxOf(height, layout.height)
            }
            for (q in k..end) {
                val c = document.paragraphStyle(q).cell!!.column
                tops[q] = y
                lefts[q] = left[c]
                boxWidths[q] = widths[c]
                boxHeights[q] = height
            }
            y += height
            k = end + 1
        }
        return y + tableMargin
    }

    /**
     * Each column's width, in whole pixels so the lines between cells are sharp: what its text
     * needs, or [columnWidth] while it needs less. When those add up to more than the note's
     * width, the widest columns give up room first, all held to one width, never below their
     * longest word; a column narrower than that keeps all it needs, as a browser's table does.
     */
    private fun columnWidths(natural: FloatArray, word: FloatArray): FloatArray {
        val n = natural.size
        val pad = 2 * cellPaddingX
        val least = minOf(columnWidth, width / n)
        // Rounded up, so a column just wide enough for its text is not a pixel short of it.
        val wanted = FloatArray(n) { kotlin.math.ceil(maxOf(natural[it] + pad, least)) }
        val floor = FloatArray(n) { minOf(wanted[it], kotlin.math.ceil(maxOf(word[it] + pad, pad + cellPaddingX))) }
        val widths = when {
            wanted.sum() <= width -> wanted
            floor.sum() >= width -> FloatArray(n) { floor[it] * width / floor.sum() }
            else -> {
                // The one width the widest columns are held to, found by halving.
                fun total(cap: Float) = (0 until n).sumOf { maxOf(floor[it], minOf(wanted[it], cap)).toDouble() }.toFloat()
                var lo = 0f
                var hi = wanted.max()
                repeat(40) {
                    val mid = (lo + hi) / 2
                    if (total(mid) > width) hi = mid else lo = mid
                }
                FloatArray(n) { maxOf(floor[it], minOf(wanted[it], lo)) }
            }
        }
        for (c in 0 until n) widths[c] = kotlin.math.floor(widths[c]).coerceAtLeast(1f)
        return widths
    }

    fun paragraphTop(index: Int): Float {
        ensureTops()
        return tops[index]
    }

    /** Where paragraph [index]'s box starts across, from the text area's left: a cell's column. */
    fun paragraphLeft(index: Int): Float {
        ensureTops()
        return lefts[index]
    }

    /** How wide paragraph [index]'s box is: the note's width, or a cell's column's. */
    fun boxWidth(index: Int): Float {
        ensureTops()
        return boxWidths[index]
    }

    /** How tall paragraph [index]'s box is: its own height, or a cell's row's. */
    fun boxHeight(index: Int): Float {
        ensureTops()
        return boxHeights[index]
    }

    /** The cells of the row cell [index] is in; [index] alone for a paragraph outside a table. */
    fun rowRange(index: Int): IntRange {
        val cell = document.paragraphStyle(index).cell ?: return index..index
        fun same(q: Int) = document.paragraphStyle(q).cell.let { it != null && it.table == cell.table && it.row == cell.row }
        var a = index
        while (a > 0 && same(a - 1)) a--
        var b = index
        while (b + 1 < document.paragraphCount && same(b + 1)) b++
        return a..b
    }

    /** The cells of the table cell [index] is in; [index] alone for a paragraph outside a table. */
    fun tableRange(index: Int): IntRange {
        val cell = document.paragraphStyle(index).cell ?: return index..index
        fun same(q: Int) = document.paragraphStyle(q).cell?.table == cell.table
        var a = index
        while (a > 0 && same(a - 1)) a--
        var b = index
        while (b + 1 < document.paragraphCount && same(b + 1)) b++
        return a..b
    }

    val height: Float get() {
        ensureTops()
        return tops[paragraphs.size]
    }

    /** The paragraph under document y, clamped to the first and last; a row's first cell for a table's. */
    fun paragraphAtY(y: Float): Int {
        ensureTops()
        var lo = 0
        var hi = paragraphs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return rowRange(lo).first
    }

    fun caretRect(offset: Int, upstream: Boolean = false): CaretRect {
        val p = document.paragraphAt(offset)
        val layout = paragraph(p)
        val local = offset - document.paragraphStart(p)
        val line = layout.lineOf(local, upstream)
        val top = paragraphTop(p) + layout.lineTop(line)
        val size = layout.sizeAtCaret(local)
        return CaretRect(
            paragraphLeft(p) + layout.xOf(local, line), top, top + layout.lineHeight(line), top + layout.baseline(line),
            layout.ascentFor(size), layout.descentFor(size),
        )
    }

    /** The caret position nearest a point; second is true when it is the end of a wrapped line. */
    fun offsetAt(x: Float, y: Float): Pair<Int, Boolean> {
        // In a table, the cell of the row under the point; past the row's ends, the nearest.
        val row = rowRange(paragraphAtY(y))
        val p = row.firstOrNull { x < paragraphLeft(it) + boxWidth(it) } ?: row.last
        val layout = paragraph(p)
        val line = layout.lineAtY(y - paragraphTop(p))
        val (local, upstream) = layout.offsetAt(line, x - paragraphLeft(p))
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

/**
 * How much larger than body text each role is: the Swift app's 30, 24 and 19 points against
 * its 16-point body.
 */
fun roleScale(role: Role): Float = when (role) {
    Role.TITLE -> 30f / 16f
    Role.HEADING -> 24f / 16f
    Role.SUBHEADING -> 19f / 16f
    else -> 1f
}

/** A picture's or divider's paragraph text: the one U+FFFC. */
private const val OBJECT_TEXT = "\uFFFC"
