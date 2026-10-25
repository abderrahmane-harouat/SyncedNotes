package com.syncednotes.engine.editor

import com.syncednotes.engine.layout.CaretRect
import com.syncednotes.engine.layout.DocumentLayout
import com.syncednotes.engine.layout.ParagraphLayout
import com.syncednotes.engine.layout.StyleRuns
import com.syncednotes.engine.paste.Fragment
import com.syncednotes.engine.paste.Piece
import com.syncednotes.engine.paste.Pasted
import com.syncednotes.engine.render.DrawList
import com.syncednotes.engine.render.FontFamily
import com.syncednotes.engine.render.PathBuilder
import com.syncednotes.engine.layout.roleScale
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.Boundaries
import com.syncednotes.engine.text.CalloutColor
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.Direction
import com.syncednotes.engine.text.ListKind
import com.syncednotes.engine.text.ListLevel
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TableCell
import com.syncednotes.engine.text.TextDocument
import com.syncednotes.engine.text.ValueRanges
import com.syncednotes.engine.text.ValueStyle
import com.syncednotes.engine.text.relativeSize
import com.syncednotes.engine.text.sizeValue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The editor: Samsung's SpenComposerImpl, in Kotlin. It owns the document, its layout, the
 * selection, undo, the composing region, scrolling and gestures, and paints a frame into a
 * [DrawList]. The platform view only forwards events here and puts the pixels on screen.
 *
 * Time always comes in from the caller, never from a clock, so every behaviour (blinking,
 * long-press, flings, undo grouping) is reproducible in tests.
 */
class Composer(fonts: FontFamily, val style: EditorStyle, private val host: ComposerHost) {
    val document = TextDocument().apply {
        // Find's markers follow the text as it is typed or arrives from the other device.
        addListener {
            if (findQuery.isNotEmpty()) {
                findMatches()
                currentMatch = if (matchCount == 0) -1 else currentMatch.coerceIn(0, matchCount - 1)
            }
        }
    }
    private val layout = DocumentLayout(
        document, fonts, style.sizeUnit, style.defaultSize, style.lineSpacing, style.indentWidth, style.listMarkerWidth,
        style.quoteIndent, style.codeIndent, style.pictureScale,
        codeTop = style.codeMargin + style.codePaddingTop, codeBottom = style.codePaddingBottom + style.codeMargin,
        calloutIndent = style.calloutPadding,
        calloutTop = style.calloutMargin + style.calloutPaddingVertical,
        calloutBottom = style.calloutPaddingVertical + style.calloutMargin,
        cellPaddingX = style.cellPaddingX, cellPaddingY = style.cellPaddingY,
        tableMargin = style.tableMargin, columnWidth = style.columnWidth,
    )

    /** Where the note's pictures come from; none shows each as an empty frame. */
    var pictures: PictureSource? = null
        set(value) {
            field = value
            layout.pictureSize = { handle -> value?.size(handle) }
            layout.invalidate()
            host.requestFrame()
        }
    private val history = History(document.snapshot())
    /** Font metrics for 1px text; multiplied by each run's size where it is drawn. */
    private val unitMetrics = fonts.regular.primary.metrics(1f)

    /**
     * Styles chosen with a bare caret, waiting for the next typed text (Samsung's zero-length
     * spans at the cursor). Forgotten as soon as the caret is moved.
     */
    private val typingStyle = HashMap<CharStyle, Boolean>()
    /** The same for colour, highlight and size; a null value means "back to the default". */
    private val typingValues = HashMap<ValueStyle, Int?>()

    var selection = Selection(0)
        private set
    var composingStart = -1
        private set
    var composingEnd = -1
        private set
    var focused = false
        private set
    var scrollY = 0f
        private set

    /**
     * False for a note that is only shown: one in the Trash, or one holding something the
     * engine cannot edit yet. It can still be scrolled, selected and copied; nothing changes it.
     */
    var editable = true
        set(value) {
            field = value
            host.requestFrame()
        }

    /**
     * Shown faintly while the note is empty, where its first line will be ("Title"), so an
     * empty new note reads as one waiting to be written rather than as nothing.
     */
    var placeholder: String? = null
        set(value) {
            field = value
            host.requestFrame()
        }

    private var viewWidth = 0
    private var viewHeight = 0
    private var insetTop = 0f
    private var insetBottom = 0f

    private var caretEpoch = 0L
    private var insertionHandleUntil = 0L
    private var goalX = Float.NaN

    private var batchDepth = 0
    private var selectionDirty = false

    // --- Viewport -------------------------------------------------------------------------

    fun setViewport(width: Int, height: Int, top: Float, bottom: Float) {
        viewWidth = width
        viewHeight = height
        insetTop = top
        insetBottom = bottom
        layout.setWidth((width - style.paddingLeft - style.paddingRight).coerceAtLeast(1f))
        scrollY = scrollY.coerceIn(0f, maxScroll())
        // The keyboard opening shrinks the view; keep the line being typed on screen.
        if (focused) ensureCaretVisible()
        host.requestFrame()
    }

    private fun maxScroll(): Float {
        val content = style.paddingTop + layout.height + style.paddingBottom
        val visible = viewHeight - insetTop - insetBottom
        return (content - visible).coerceAtLeast(0f)
    }

    private fun scrollTo(y: Float) {
        val clamped = y.coerceIn(0f, maxScroll())
        if (clamped != scrollY) {
            scrollY = clamped
            host.requestFrame()
        }
    }

    private val originX: Float get() = style.paddingLeft
    private val originY: Float get() = insetTop + style.paddingTop - scrollY

    private fun ensureCaretVisible() {
        if (viewHeight == 0) return
        val r = layout.caretRect(selection.focus, selection.upstream)
        val top = originY + r.top
        val bottom = originY + r.bottom
        val margin = style.lineHeight * 0.5f
        val visibleTop = insetTop + margin
        val visibleBottom = viewHeight - insetBottom - margin
        if (top < visibleTop) scrollTo(scrollY - (visibleTop - top))
        else if (bottom > visibleBottom) scrollTo(scrollY + (bottom - visibleBottom))
    }

    // --- Loading --------------------------------------------------------------------------

    /**
     * Replaces the whole document with a note read from storage: [fill] writes its text and
     * formatting into the emptied document. That is the starting point, so nothing before it
     * can be undone, and the caret goes to the start of the note.
     */
    fun load(fill: (TextDocument) -> Unit) {
        clearComposing()
        typingStyle.clear()
        typingValues.clear()
        document.replace(0, document.length, "")
        document.setParagraphStyle(0, ParagraphStyle.DEFAULT)
        fill(document)
        history.reset(document.snapshot())
        selection = Selection(0)
        scrollY = 0f
        goalX = Float.NaN
        mouseDrag = MouseDrag.NONE
        host.textChangedOutsideInput()
        reportSelection()
        host.requestFrame()
    }

    // --- Editing core ---------------------------------------------------------------------

    /**
     * Every change to the text goes through here, so history, the composing region, layout and
     * the input method always agree about what happened.
     */
    private fun edit(
        start: Int, end: Int, text: CharSequence, after: Selection, now: Long, mergeable: Boolean, fromInput: Boolean,
        /** Formatting for what was put in, made part of the same undo step. */
        format: (() -> Unit)? = null,
    ) {
        if (!editable) return
        if (!restructuring && keepsTables(start, end, text, after, now, fromInput, format)) return
        val before = selection
        // Typing hides the insertion handle, as on Samsung and stock Android; it comes back
        // with the next tap.
        insertionHandleUntil = 0
        val change = document.replace(start, end, text)
        if (text.isNotEmpty() && (typingStyle.isNotEmpty() || typingValues.isNotEmpty())) {
            for ((style, on) in typingStyle) document.setStyle(style, on, start, start + text.length)
            for ((style, value) in typingValues) document.setValue(style, value, start, start + text.length)
            // From here on, typing extends the styled text by itself.
            typingStyle.clear()
            typingValues.clear()
        }
        format?.invoke()
        history.record(change.start, change.removed, change.inserted, before, after, document.snapshot(), now, mergeable)
        if (composingStart >= 0) {
            val cs = change.map(composingStart)
            val ce = change.map(composingEnd)
            if (cs < ce) setComposing(cs, ce) else clearComposing()
        }
        selection = clampSelection(after)
        afterSelectionMoved(now)
        if (!fromInput) {
            clearComposing()
            host.textChangedOutsideInput()
        }
        reportSelection()
        host.requestFrame()
    }

    private fun clampSelection(s: Selection): Selection {
        val n = document.length
        return Selection(s.anchor.coerceIn(0, n), s.focus.coerceIn(0, n), s.upstream && s.anchor == s.focus)
    }

    private fun afterSelectionMoved(now: Long) {
        caretEpoch = now
        goalX = Float.NaN
        ensureCaretVisible()
    }

    private fun setComposing(start: Int, end: Int) {
        composingStart = start
        composingEnd = end
    }

    private fun clearComposing() {
        composingStart = -1
        composingEnd = -1
    }

    /** A selection change the user made directly (tap, arrow, handle): the IME's word ends. */
    private fun select(s: Selection, now: Long) {
        val clamped = clampSelection(s)
        if (clamped == selection && composingStart < 0) {
            caretEpoch = now
            return
        }
        selection = clamped
        typingStyle.clear()
        typingValues.clear()
        clearComposing()
        history.seal()
        afterSelectionMoved(now)
        reportSelection()
        host.requestFrame()
    }

    private fun reportSelection() {
        if (batchDepth > 0) {
            selectionDirty = true
            return
        }
        selectionDirty = false
        host.selectionChanged(selection.start, selection.end, composingStart, composingEnd)
        host.formatChanged()
    }

    fun undo(now: Long): Boolean {
        if (!editable) return false
        val e = history.undo() ?: return false
        clearComposing()
        typingStyle.clear()
        typingValues.clear()
        if (e.inserted.isNotEmpty() || e.removed.isNotEmpty()) document.replace(e.start, e.start + e.inserted.length, e.removed)
        document.restore(e.formatBefore)
        selection = clampSelection(e.selectionBefore)
        afterSelectionMoved(now)
        host.textChangedOutsideInput()
        reportSelection()
        host.requestFrame()
        return true
    }

    fun redo(now: Long): Boolean {
        if (!editable) return false
        val e = history.redo() ?: return false
        clearComposing()
        typingStyle.clear()
        typingValues.clear()
        if (e.inserted.isNotEmpty() || e.removed.isNotEmpty()) document.replace(e.start, e.start + e.removed.length, e.inserted)
        document.restore(e.formatAfter)
        selection = clampSelection(e.selectionAfter)
        afterSelectionMoved(now)
        host.textChangedOutsideInput()
        reportSelection()
        host.requestFrame()
        return true
    }

    /** Text typed on a hardware keyboard: replaces the selection, like the IME would. */
    fun type(text: CharSequence, now: Long) {
        if (text.length == 1 && text[0] == '\n' && (cellBelow(now) || leaveEmptyListItem(now))) return
        if (text.isNotEmpty() && text[0] != '\n' && text[0] != TextDocument.OBJECT) leaveObjectParagraph(now)
        clearComposing()
        val s = selection.start
        edit(s, selection.end, text, Selection(s + text.length), now, mergeable = true, fromInput = true)
    }

    /**
     * Pastes what the clipboard holds, [plain] text and [html] (either may be missing), in
     * place of the selection, as one undo step. Formatted text from a web page, Notion or
     * Markdown keeps what a note can hold of it ([Pasted]); into a code block, what is pasted is
     * code, and goes in as the text it is.
     */
    fun paste(plain: String?, html: String?, now: Long) {
        if (!editable) return
        val here = document.paragraphStyle(document.paragraphAt(selection.start))
        val fragment = if (here.role == Role.CODE_BLOCK) null else Pasted.read(plain, html)
        if (fragment == null) {
            val text = (plain ?: return).replace("\r\n", "\n").replace('\r', '\n')
            if (text.isNotEmpty()) type(text, now)
            return
        }
        pasteFragment(fragment, now)
    }

    /**
     * Puts [fragment] in place of the selection. Its first paragraph's style is taken only by
     * a paragraph it begins, so a heading pasted into the middle of a sentence leaves that
     * sentence what it was. Lines that are all plain take the style of the line they land on,
     * as typed ones would, so plain lines pasted into a list go on with the list; in what has
     * headings, lists or code, a plain line is body text, as it was where it was copied from.
     */
    private fun pasteFragment(pasted: Fragment, now: Long) {
        leaveObjectParagraph(now)
        // A selection across a table is emptied as typing over it would, before what is pasted.
        if (!selection.isCollapsed && selectedParagraphs().any { document.paragraphStyle(it).cell != null }) deleteSelection(now)
        // Into a cell, what is pasted is text: a cell holds no headings, lists or tables.
        if (document.paragraphStyle(document.paragraphAt(selection.start)).cell != null) {
            type(pasted.text.replace('\n', ' ').replace(TextDocument.OBJECT.toString(), ""), now)
            return
        }
        val fragment = aroundTables(pasted, selection.start == document.paragraphStart(document.paragraphAt(selection.start)))
        val sel = selection
        val start = sel.start
        val text = fragment.text
        val firstParagraph = document.paragraphAt(start)
        val beginsParagraph = start == document.paragraphStart(firstParagraph)
        val lastParagraph = document.paragraphAt(sel.end)
        // The paragraph is nothing but what is pasted: everything after the selection in it goes too.
        val fillsParagraph = beginsParagraph && sel.end == document.paragraphEnd(lastParagraph)
        val structured = fragment.paragraphs.any { it.style != ParagraphStyle.DEFAULT }
        val listBase = (0 until document.paragraphCount).maxOfOrNull { p -> document.paragraphStyle(p).lists.maxOfOrNull { it.id } ?: 0 } ?: 0
        val tableBase = newTableId()
        clearComposing()
        edit(start, sel.end, text, Selection(start + text.length), now, mergeable = false, fromInput = false) {
            val end = start + text.length
            for (style in CharStyle.entries) document.setStyle(style, false, start, end)
            for (style in ValueStyle.entries) document.setValue(style, null, start, end)
            var at = start
            for ((k, piece) in fragment.paragraphs.withIndex()) {
                val p = firstParagraph + k
                val takesStyle = k > 0 || fillsParagraph || (beginsParagraph && fragment.paragraphs.size > 1)
                if (takesStyle && (structured || document.paragraphStyle(p).isObject)) {
                    val lists = piece.style.lists.map { it.copy(id = it.id + listBase) }
                    val cell = piece.style.cell?.let { it.copy(table = it.table + tableBase) }
                    document.setParagraphStyle(p, piece.style.copy(lists = lists, cell = cell, direction = document.paragraphStyle(p).direction))
                }
                for (span in piece.spans) document.setStyle(span.style, true, at + span.start, at + span.end)
                at += piece.text.length + 1
            }
        }
    }

    // --- Input method (the InputConnection maps onto these) -----------------------------

    val inBatchEdit: Boolean get() = batchDepth > 0

    fun beginBatchEdit() {
        batchDepth++
    }

    /**
     * Runs one input-method call as a batch of its own, so the input method hears about the
     * final selection once, never an intermediate state (say, new text without its composing
     * region yet).
     */
    private inline fun batch(block: () -> Unit) {
        beginBatchEdit()
        try {
            block()
        } finally {
            endBatchEdit()
        }
    }

    fun endBatchEdit() {
        if (batchDepth == 0) return
        batchDepth--
        if (batchDepth == 0 && selectionDirty) reportSelection()
    }

    private fun replacementRange(): Pair<Int, Int> =
        if (composingStart >= 0) composingStart to composingEnd else selection.start to selection.end

    /** Android's cursor rule: > 0 is relative to the end of the new text, <= 0 to its start. */
    private fun cursorAfter(start: Int, text: CharSequence, newCursorPosition: Int): Int =
        if (newCursorPosition > 0) start + text.length + newCursorPosition - 1 else start + newCursorPosition

    fun commitText(text: CharSequence, newCursorPosition: Int, now: Long) = batch {
        if (composingStart < 0 && text.length == 1 && text[0] == '\n' && (cellBelow(now) || leaveEmptyListItem(now))) return@batch
        if (composingStart < 0 && text.isNotEmpty() && text[0] != '\n') leaveObjectParagraph(now)
        val (s, e) = replacementRange()
        val cursor = cursorAfter(s, text, newCursorPosition)
        clearComposing()
        edit(s, e, text, Selection(cursor), now, mergeable = true, fromInput = true)
    }

    fun setComposingText(text: CharSequence, newCursorPosition: Int, now: Long) = batch {
        if (composingStart < 0 && text.isNotEmpty()) leaveObjectParagraph(now)
        val (s, e) = replacementRange()
        val cursor = cursorAfter(s, text, newCursorPosition)
        clearComposing()
        edit(s, e, text, Selection(cursor), now, mergeable = true, fromInput = true)
        if (text.isNotEmpty()) setComposing(s, s + text.length)
        reportSelection()
    }

    fun setComposingRegion(start: Int, end: Int) {
        val n = document.length
        val a = minOf(start, end).coerceIn(0, n)
        val b = maxOf(start, end).coerceIn(0, n)
        if (a == b) clearComposing() else setComposing(a, b)
        reportSelection()
        host.requestFrame()
    }

    fun finishComposingText() {
        if (composingStart < 0) return
        clearComposing()
        reportSelection()
        host.requestFrame()
    }

    fun deleteSurroundingText(before: Int, after: Int, now: Long) = batch {
        if (composingStart < 0 && selection.isCollapsed && (before > 0) != (after > 0) && deleteObjectAtCaret(backward = before > 0, now)) return@batch
        if (before > 0 && after == 0 && composingStart < 0 && removeParagraphMarkAtCaret(now)) return@batch
        val text = document.text
        val n = document.length
        val a = selection.start
        val b = selection.end
        var afterEnd = (b + maxOf(0, after)).coerceAtMost(n)
        // Never leave half a surrogate pair behind, whatever count the IME sends.
        if (afterEnd in 1 until n && text[afterEnd].isLowSurrogate() && text[afterEnd - 1].isHighSurrogate()) afterEnd++
        var beforeStart = (a - maxOf(0, before)).coerceAtLeast(0)
        if (beforeStart in 1 until n && text[beforeStart].isLowSurrogate() && text[beforeStart - 1].isHighSurrogate()) beforeStart--
        // Delete after the selection first so the offsets before it stay valid.
        if (afterEnd > b) edit(b, afterEnd, "", Selection(a, b), now, mergeable = true, fromInput = true)
        if (beforeStart < a) {
            val removed = a - beforeStart
            edit(beforeStart, a, "", Selection(beforeStart, b - removed), now, mergeable = true, fromInput = true)
        }
    }

    fun deleteSurroundingTextInCodePoints(before: Int, after: Int, now: Long) {
        val text = document.text
        var units = 0
        var i = selection.start
        repeat(before) {
            if (i <= 0) return@repeat
            val step = if (i >= 2 && text[i - 1].isLowSurrogate() && text[i - 2].isHighSurrogate()) 2 else 1
            i -= step
            units += step
        }
        var unitsAfter = 0
        var j = selection.end
        repeat(after) {
            if (j >= text.length) return@repeat
            val step = if (j + 1 < text.length && text[j].isHighSurrogate() && text[j + 1].isLowSurrogate()) 2 else 1
            j += step
            unitsAfter += step
        }
        deleteSurroundingText(units, unitsAfter, now)
    }

    fun setSelectionFromInput(start: Int, end: Int, now: Long) {
        selection = clampSelection(Selection(start, end))
        history.seal()
        afterSelectionMoved(now)
        reportSelection()
        host.requestFrame()
    }

    fun textBeforeCursor(n: Int): String {
        val s = selection.start
        return document.substring((s - n).coerceAtLeast(0), s)
    }

    fun textAfterCursor(n: Int): String {
        val e = selection.end
        return document.substring(e, (e + n).coerceAtMost(document.length))
    }

    /**
     * The selection as plain text. A table's cells are set apart by tabs and its rows by lines,
     * as a spreadsheet copies them, so they paste into one as cells.
     */
    fun selectedText(): String? {
        val sel = selection
        if (sel.isCollapsed) return null
        val first = document.paragraphAt(sel.start)
        val last = document.paragraphAt(sel.end)
        if ((first..last).none { document.paragraphStyle(it).cell != null }) return document.substring(sel.start, sel.end)
        val out = StringBuilder()
        for (p in first..last) {
            if (p > first) {
                val a = document.paragraphStyle(p - 1).cell
                val b = document.paragraphStyle(p).cell
                out.append(if (a != null && b != null && a.table == b.table && a.row == b.row) '\t' else '\n')
            }
            out.append(document.substring(maxOf(sel.start, document.paragraphStart(p)), minOf(sel.end, document.paragraphEnd(p))))
        }
        return out.toString()
    }

    /** Whether the next letter should be capitalised: at the start of a sentence, or a word. */
    fun capitalisationAtCursor(): Capitalisation {
        val text = document.text
        var i = selection.start
        if (i == 0) return Capitalisation.SENTENCE
        if (!text[i - 1].isWhitespace()) return Capitalisation.NONE
        while (i > 0 && text[i - 1].isWhitespace() && text[i - 1] != '\n') i--
        if (i == 0 || text[i - 1] == '\n') return Capitalisation.SENTENCE
        return if (text[i - 1] in ".!?") Capitalisation.SENTENCE else Capitalisation.WORD
    }

    enum class Capitalisation { NONE, WORD, SENTENCE }

    /**
     * Where the caret is drawn, in view pixels, for the input method's cursor-anchor updates:
     * x, top of the glyphs, baseline, bottom of the glyphs.
     */
    fun caretInView(): FloatArray {
        val r = layout.caretRect(selection.focus, selection.upstream)
        val baseline = originY + r.baseline
        return floatArrayOf(originX + r.x, baseline - r.ascent, baseline, baseline + r.descent)
    }

    // --- Formatting (Samsung's Text options) ------------------------------------------------

    /**
     * What the Text options panel shows for the current selection or caret, computed the way
     * Samsung's RtChecker does: a character style is on only if all of the selection has it;
     * a list type or alignment shows only if every selected paragraph shares it.
     */
    data class FormatState(
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val strikethrough: Boolean,
        /** NONE also when the paragraphs disagree. */
        val list: ListType,
        /** Null when the paragraphs disagree. Never NATURAL: the side it comes out on. */
        val alignment: Alignment?,
        val canIndent: Boolean,
        val canOutdent: Boolean,
        val canUndo: Boolean,
        val canRedo: Boolean,
        /** Text colour; null for the default (also shown when the selection mixes colours, as Samsung does). */
        val color: Int?,
        /** Highlight colour; null for none. */
        val highlight: Int?,
        /** Size in Samsung's units; null when the selection mixes sizes, which leaves the label blank. */
        val size: Int?,
        val checked: Boolean,
        val code: Boolean = false,
        /** Null when the paragraphs disagree. */
        val role: Role? = Role.BODY,
        /** Line height as a multiple of normal; null when the paragraphs disagree. */
        val lineSpacing: Float? = 1f,
        /** The language, when every paragraph is a code block in the same one; null otherwise. */
        val codeLanguage: CodeLanguage? = null,
        /** The colour, when every paragraph is a callout of the same one; null otherwise. */
        val calloutColor: CalloutColor? = null,
        /** Whether the caret is in a table, and whether that table's first row is its header. */
        val inTable: Boolean = false,
        val tableHeader: Boolean = false,
    ) {
        fun has(style: CharStyle): Boolean = when (style) {
            CharStyle.BOLD -> bold
            CharStyle.ITALIC -> italic
            CharStyle.UNDERLINE -> underline
            CharStyle.STRIKETHROUGH -> strikethrough
            CharStyle.CODE -> code
        }
    }

    private fun styleOn(style: CharStyle): Boolean {
        val sel = selection
        return if (sel.isCollapsed) typingStyle[style] ?: document.styleAtCaret(style, sel.start)
        else document.hasStyleThroughout(style, sel.start, sel.end)
    }

    fun formatState(): FormatState {
        val styles = selectedParagraphs().map { document.paragraphStyle(it) }
        val first = styles.first()
        val list = if (styles.all { it.list == first.list }) first.list else ListType.NONE
        val alignment = styles.map { shownAlignment(it) }.distinct().singleOrNull()
        // Indenting a right-aligned paragraph means nothing in a left-to-right note, so Samsung
        // disables both buttons there. Mixed levels can always move.
        val (canIndent, canOutdent) = when {
            styles.any { shownAlignment(it) == Alignment.RIGHT } -> false to false
            styles.any { it.indent != first.indent || it.depth != first.depth } -> true to true
            first.depth > 0 -> (first.depth < ParagraphStyle.MAX_LIST_DEPTH) to true
            else -> (first.indent < ParagraphStyle.MAX_INDENT) to (first.indent > 0)
        }
        val units = style.defaultSize * roleScale(first.role)
        return FormatState(
            styleOn(CharStyle.BOLD), styleOn(CharStyle.ITALIC), styleOn(CharStyle.UNDERLINE), styleOn(CharStyle.STRIKETHROUGH),
            list, alignment, canIndent, canOutdent, history.canUndo, history.canRedo,
            color = valueOn(ValueStyle.COLOR).takeIf { it != ValueRanges.MIXED },
            highlight = valueOn(ValueStyle.HIGHLIGHT).takeIf { it != ValueRanges.MIXED },
            size = when (val v = valueOn(ValueStyle.SIZE)) {
                ValueRanges.MIXED -> null
                else -> Math.round(units * relativeSize(v))
            },
            checked = list == ListType.CHECKBOX && styles.all { it.checked },
            code = styleOn(CharStyle.CODE),
            role = styles.map { it.role }.distinct().singleOrNull(),
            lineSpacing = styles.map { if (it.lineSpacing == 0f) 1f else it.lineSpacing }.distinct().singleOrNull(),
            codeLanguage = styles.takeIf { all -> all.all { it.role == Role.CODE_BLOCK } }?.map { it.language }?.distinct()?.singleOrNull(),
            calloutColor = styles.takeIf { all -> all.all { it.role == Role.CALLOUT } }?.map { it.callout }?.distinct()?.singleOrNull(),
            inTable = inTable,
            tableHeader = document.paragraphStyle(document.paragraphAt(selection.focus)).cell?.header == true,
        )
    }

    private fun valueOn(style: ValueStyle): Int? {
        val sel = selection
        if (sel.isCollapsed && typingValues.containsKey(style)) return typingValues[style]
        return document.uniformValue(style, sel.start, sel.end)
    }

    /** Text colour for the selection, or for what is typed next; null puts back the default. */
    fun setColor(argb: Int?, now: Long) = setValue(ValueStyle.COLOR, argb, now)

    /** Highlight behind the selection, or behind what is typed next; null removes it. */
    fun setHighlight(argb: Int?, now: Long) = setValue(ValueStyle.HIGHLIGHT, argb, now)

    /**
     * Text size in Samsung's units (17 is body text's). Kept relative to the paragraph's normal
     * size, as the note format records it, so a size picked in body text means the same on
     * both devices.
     */
    fun setSize(size: Int, now: Long) {
        val units = style.defaultSize * roleScale(document.paragraphStyle(document.paragraphAt(selection.start)).role)
        setRelativeSize(size / units, now)
    }

    /** Text size as a multiple of the paragraph's normal size: the Mac's Bigger and Smaller. */
    fun setRelativeSize(relative: Float, now: Long) = setValue(ValueStyle.SIZE, sizeValue(relative), now)

    /** The relative size at the caret or of the selection; null when it is mixed. */
    fun relativeSizeHere(): Float? {
        val value = valueOn(ValueStyle.SIZE)
        return if (value == ValueRanges.MIXED) null else relativeSize(value)
    }

    private fun setValue(valueStyle: ValueStyle, value: Int?, now: Long) {
        val sel = selection
        if (sel.isCollapsed) {
            typingValues[valueStyle] = value
            host.formatChanged()
            // A new size changes the caret's height straight away.
            host.requestFrame()
            return
        }
        formatEdit(now) { document.setValue(valueStyle, value, sel.start, sel.end) }
    }

    /** Ticks or unticks the checklist items the selection touches. */
    fun toggleChecked(now: Long) {
        val range = selectedParagraphs().filter { document.paragraphStyle(it).list == ListType.CHECKBOX }
        if (range.isEmpty()) return
        val target = !range.all { document.paragraphStyle(it).checked }
        formatEdit(now) { for (p in range) document.setParagraphStyle(p, document.paragraphStyle(p).copy(checked = target)) }
    }

    /** Ticks the checkbox under a tap, if there is one, without moving the caret. */
    private fun tapCheckbox(x: Float, y: Float, now: Long): Boolean {
        val docY = y - originY
        val p = layout.paragraphAtY(docY)
        val ps = document.paragraphStyle(p)
        if (ps.list != ListType.CHECKBOX) return false
        val pl = layout.paragraph(p)
        val localY = docY - layout.paragraphTop(p)
        if (localY < 0 || localY > pl.lineHeight(0)) return false
        val markerLeft = originX + markerLeft(pl)
        // The whole hanging margin is the target, so the small box is easy to hit.
        if (x < markerLeft - style.touchSlop || x > markerLeft + style.listMarkerWidth) return false
        formatEdit(now) { document.setParagraphStyle(p, ps.copy(checked = !ps.checked)) }
        return true
    }

    /**
     * Bold, italic, underline or strikethrough on or off. With a selection it changes the
     * selected text (on unless all of it already has the style); with a bare caret it sets
     * the style of what is typed next.
     */
    fun toggle(style: CharStyle, now: Long) {
        val turnOn = !styleOn(style)
        val sel = selection
        if (sel.isCollapsed) {
            typingStyle[style] = turnOn
            host.formatChanged()
            return
        }
        formatEdit(now) { document.setStyle(style, turnOn, sel.start, sel.end) }
    }

    /**
     * Bullets, dashes, numbering or a checklist for the selected paragraphs; choosing the kind
     * they all have already takes it away. Paragraphs become items of one list, and of the list
     * just above when that is the same kind at the same level, so its numbering runs on.
     */
    fun toggleList(type: ListType, now: Long) {
        val range = selectedBlocks() ?: return
        val allAlready = range.all { document.paragraphStyle(it).list == type }
        val target = if (allAlready) ListType.NONE else type
        formatEdit(now) {
            when (target) {
                ListType.NONE -> for (p in range) document.setParagraphStyle(p, withoutList(document.paragraphStyle(p)))
                ListType.CHECKBOX -> for (p in range) {
                    document.setParagraphStyle(p, document.paragraphStyle(p).copy(role = Role.CHECKLIST, lists = emptyList(), checked = false))
                }
                else -> {
                    val kind = when (target) {
                        ListType.DASH -> ListKind.DASH
                        ListType.NUMBER -> ListKind.NUMBER
                        else -> ListKind.BULLET
                    }
                    val first = document.paragraphStyle(range.first)
                    val level = maxOf(first.depth, 1)
                    val above = if (range.first > 0) document.paragraphStyle(range.first - 1).lists else emptyList()
                    val joined = above.getOrNull(level - 1)?.takeIf { it.kind == kind && above.size >= level }
                    val list = joined ?: ListLevel(kind, newListId())
                    for (p in range) {
                        val s = document.paragraphStyle(p)
                        val role = if (s.role == Role.CHECKLIST || s.role == Role.DIVIDER) Role.BODY else s.role
                        val lists = if (s.lists.isEmpty()) listOf(list) else s.lists.dropLast(1) + list
                        document.setParagraphStyle(p, s.copy(role = role, lists = lists, checked = false))
                    }
                }
            }
        }
    }

    /** A list ID no paragraph uses yet. */
    private fun newListId(): Int {
        var max = 0
        for (p in 0 until document.paragraphCount) for (l in document.paragraphStyle(p).lists) max = maxOf(max, l.id)
        return max + 1
    }

    private fun withoutList(s: ParagraphStyle): ParagraphStyle =
        s.copy(role = if (s.role == Role.CHECKLIST) Role.BODY else s.role, lists = emptyList(), checked = false)

    /**
     * Title, heading, subheading, body, quote or code block for the selected paragraphs;
     * choosing the role they all have already puts them back to body text.
     */
    fun toggleRole(role: Role, now: Long) {
        val range = selectedBlocks() ?: return
        val target = if (role != Role.BODY && range.all { document.paragraphStyle(it).role == role }) Role.BODY else role
        // Code joining a block above or below is in that block's language, as all of a block is.
        val language = if (target == Role.CODE_BLOCK) neighbouringLanguage(range) else CodeLanguage.NONE
        formatEdit(now) {
            for (p in range) {
                val s = document.paragraphStyle(p)
                // A checklist item made a heading stops being an item; a list item keeps its list.
                document.setParagraphStyle(p, s.copy(role = target, checked = s.checked && target == Role.CHECKLIST, language = language))
            }
        }
    }

    /**
     * A code block in [language] for the selected paragraphs, which then colours its syntax.
     * The whole of every block they touch takes the language, since a block is in one; a
     * caret anywhere in a block changes all of it. Choosing the language the paragraphs are
     * already code in puts them back to body text, as choosing a role they have does.
     */
    fun toggleCodeBlock(language: CodeLanguage, now: Long) {
        val range = selectedParagraphs().filter { !document.paragraphStyle(it).isObject && document.paragraphStyle(it).cell == null }
        if (range.isEmpty()) return
        if (range.all { document.paragraphStyle(it).let { s -> s.role == Role.CODE_BLOCK && s.language == language } }) {
            formatEdit(now) {
                for (p in range) document.setParagraphStyle(p, document.paragraphStyle(p).copy(role = Role.BODY, language = CodeLanguage.NONE))
            }
            return
        }
        var first = range.first()
        while (first > 0 && document.paragraphStyle(first - 1).role == Role.CODE_BLOCK) first--
        var last = range.last()
        while (last + 1 < document.paragraphCount && document.paragraphStyle(last + 1).role == Role.CODE_BLOCK) last++
        formatEdit(now) {
            for (p in range) document.setParagraphStyle(p, document.paragraphStyle(p).copy(role = Role.CODE_BLOCK, checked = false))
            for (p in first..last) {
                val s = document.paragraphStyle(p)
                if (s.role == Role.CODE_BLOCK) document.setParagraphStyle(p, s.copy(language = language))
            }
        }
    }

    /**
     * A callout of [color] for the selected paragraphs: text set apart on a coloured panel. The
     * whole of every callout they touch takes the colour, so a caret anywhere in one recolours
     * all of it. Choosing the colour they already are puts them back to body text, as choosing
     * a role they have does.
     */
    fun toggleCallout(color: CalloutColor, now: Long) {
        val range = selectedParagraphs().filter { !document.paragraphStyle(it).isObject && document.paragraphStyle(it).cell == null }
        if (range.isEmpty()) return
        if (range.all { document.paragraphStyle(it).let { s -> s.role == Role.CALLOUT && s.callout == color } }) {
            formatEdit(now) { for (p in range) document.setParagraphStyle(p, document.paragraphStyle(p).copy(role = Role.BODY)) }
            return
        }
        var first = range.first()
        while (first > 0 && document.paragraphStyle(first).role == Role.CALLOUT && layout.samePanel(first - 1, first)) first--
        var last = range.last()
        while (last + 1 < document.paragraphCount && document.paragraphStyle(last).role == Role.CALLOUT && layout.samePanel(last, last + 1)) last++
        formatEdit(now) {
            for (p in first..last) {
                val s = document.paragraphStyle(p)
                if (p in range || s.role == Role.CALLOUT) {
                    // A checklist item made a callout stops being an item; a list item keeps its list.
                    document.setParagraphStyle(p, s.copy(role = Role.CALLOUT, callout = color, checked = false, language = CodeLanguage.NONE))
                }
            }
        }
    }

    /**
     * Gives the whole code block that paragraph [paragraph] is in the [language] its syntax is
     * coloured by: what choosing from the name at the top of the block does.
     */
    fun setBlockLanguage(paragraph: Int, language: CodeLanguage, now: Long) {
        if (paragraph !in 0 until document.paragraphCount || document.paragraphStyle(paragraph).role != Role.CODE_BLOCK) return
        var first = paragraph
        while (first > 0 && document.paragraphStyle(first - 1).role == Role.CODE_BLOCK) first--
        var last = paragraph
        while (last + 1 < document.paragraphCount && document.paragraphStyle(last + 1).role == Role.CODE_BLOCK) last++
        formatEdit(now) { for (p in first..last) document.setParagraphStyle(p, document.paragraphStyle(p).copy(language = language)) }
    }

    /**
     * Where the language's name and its chevron are drawn at the top of the code block
     * starting at [p], in view pixels: left, top, right, bottom.
     */
    private fun codeLabelBox(p: Int, pl: ParagraphLayout, top: Float): FloatArray {
        val size = style.codeLabelSize
        val run = layout.markerRun(document.paragraphStyle(p).language.title, size)
        val width = run.advances.sum() + size * 0.9f
        val left = originX + pl.textLeft
        val labelTop = top + (style.codePaddingTop - size * 1.6f) / 2f + size * 0.15f
        return floatArrayOf(left, labelTop, left + width, labelTop + size * 1.6f)
    }

    /** Opens the languages, if the click is on the name at the top of a code block. */
    private fun tapCodeLanguage(x: Float, y: Float): Boolean {
        if (!editable || style.codePaddingTop <= 0f) return false
        val docY = y - originY
        val p = layout.paragraphAtY(docY)
        if (!layout.isBlockStart(p)) return false
        val top = originY + layout.paragraphTop(p) + style.codeMargin
        if (y > top + style.codePaddingTop) return false
        val box = codeLabelBox(p, layout.paragraph(p), top)
        // A little room around the small name, so it is easy to hit.
        val slop = style.touchSlop
        if (x < box[0] - slop || x > box[2] + slop || y < box[1] - slop || y > box[3] + slop) return false
        host.chooseCodeLanguage(p, document.paragraphStyle(p).language, box[0], box[3])
        return true
    }

    /**
     * The language of code already among [range], or of the block just above it, or else just
     * below; NONE when there is none.
     */
    private fun neighbouringLanguage(range: IntRange): CodeLanguage {
        range.map { document.paragraphStyle(it) }.firstOrNull { it.role == Role.CODE_BLOCK }?.let { return it.language }
        val above = if (range.first > 0) document.paragraphStyle(range.first - 1) else null
        if (above?.role == Role.CODE_BLOCK) return above.language
        val below = if (range.last + 1 < document.paragraphCount) document.paragraphStyle(range.last + 1) else null
        if (below?.role == Role.CODE_BLOCK) return below.language
        return CodeLanguage.NONE
    }

    fun setAlignment(alignment: Alignment, now: Long) {
        formatEdit(now) { for (p in selectedParagraphs()) document.setParagraphStyle(p, document.paragraphStyle(p).copy(alignment = alignment)) }
    }

    /**
     * A divider below the paragraph the caret is in, and the caret on a new line after it. A
     * divider is the note format's: a paragraph of one U+FFFC whose role draws the line.
     */
    fun insertDivider(now: Long) {
        if (!editable) return
        val p = layout.tableRange(document.paragraphAt(selection.end)).last
        select(Selection(document.paragraphEnd(p)), now)
        restructured { type("\n\uFFFC\n", now) }
        formatEdit(now) {
            document.setParagraphStyle(p + 1, ParagraphStyle(role = Role.DIVIDER))
            document.setParagraphStyle(p + 2, ParagraphStyle.DEFAULT)
        }
    }

    /**
     * A picture below the paragraph the caret is in, as a paragraph of its own, and the caret
     * on a new line after it; an empty line takes the picture itself. [handle] is the app's
     * name for the picture ([PictureSource]).
     */
    fun insertPicture(handle: Int, now: Long) {
        if (!editable) return
        val p = layout.tableRange(document.paragraphAt(selection.end)).last
        val empty = document.paragraphStart(p) == document.paragraphEnd(p) && !document.paragraphStyle(p).isObject && document.paragraphStyle(p).cell == null
        if (empty) {
            val at = document.paragraphStart(p)
            select(Selection(at), now)
            type("\uFFFC\n", now)
            formatEdit(now) {
                document.setParagraphStyle(p, ParagraphStyle(picture = handle))
                document.setParagraphStyle(p + 1, ParagraphStyle.DEFAULT)
            }
            return
        }
        select(Selection(document.paragraphEnd(p)), now)
        restructured { type("\n\uFFFC\n", now) }
        formatEdit(now) {
            document.setParagraphStyle(p + 1, ParagraphStyle(picture = handle))
            document.setParagraphStyle(p + 2, ParagraphStyle.DEFAULT)
        }
    }

    /** Line height as a multiple of normal (1, 1.15, 1.5, 2) for the selected paragraphs. */
    fun setLineSpacing(multiple: Float, now: Long) {
        val stored = if (multiple == 1f) 0f else multiple
        formatEdit(now) { for (p in selectedParagraphs()) document.setParagraphStyle(p, document.paragraphStyle(p).copy(lineSpacing = stored)) }
    }

    /** The side a paragraph's lines come out on. */
    private fun shownAlignment(s: ParagraphStyle): Alignment = when (s.alignment) {
        Alignment.NATURAL -> if (s.direction == Direction.RIGHT_TO_LEFT) Alignment.RIGHT else Alignment.LEFT
        else -> s.alignment
    }

    fun indent(now: Long) = changeIndent(+1, now)

    fun outdent(now: Long) = changeIndent(-1, now)

    /**
     * A list item moves a level deeper or shallower in its list; anything else moves by an
     * indent step. A nested item joins the list the item above already started at that
     * level, so its numbering runs on.
     */
    private fun changeIndent(by: Int, now: Long) {
        formatEdit(now) {
            for (p in selectedParagraphs()) {
                val s = document.paragraphStyle(p)
                if (s.cell != null) continue
                val next = when {
                    s.depth > 0 && by > 0 && s.depth < ParagraphStyle.MAX_LIST_DEPTH -> s.copy(lists = s.lists + nestedLevel(p, s))
                    s.depth > 1 && by < 0 -> s.copy(lists = s.lists.dropLast(1))
                    s.depth > 0 && by > 0 -> s
                    else -> s.copy(indent = (s.indent + by).coerceIn(0, ParagraphStyle.MAX_INDENT))
                }
                document.setParagraphStyle(p, next)
            }
        }
    }

    private fun nestedLevel(p: Int, s: ParagraphStyle): ListLevel {
        val above = if (p > 0) document.paragraphStyle(p - 1).lists else emptyList()
        if (above.size > s.depth && above.subList(0, s.depth) == s.lists) return above[s.depth]
        return ListLevel(s.lists.last().kind, newListId())
    }

    /**
     * The paragraphs the selection touches, when they are paragraphs a role or a list can be
     * given; null in a table, whose cells are its own.
     */
    private fun selectedBlocks(): IntRange? {
        val range = selectedParagraphs()
        val first = range.firstOrNull { document.paragraphStyle(it).cell == null } ?: return null
        val last = range.last { document.paragraphStyle(it).cell == null }
        return if ((first..last).any { document.paragraphStyle(it).cell != null }) null else first..last
    }

    /** Paragraphs the selection touches; a selection ending right at a paragraph's start leaves that one out. */
    private fun selectedParagraphs(): IntRange {
        val sel = selection
        val last = if (sel.isCollapsed) sel.end else maxOf(sel.start, sel.end - 1)
        return document.paragraphAt(sel.start)..document.paragraphAt(last)
    }

    /** A formatting change as one undo step of its own. */
    private inline fun formatEdit(now: Long, change: () -> Unit) {
        if (!editable) return
        val version = document.formatVersion
        change()
        // Choosing what is already chosen (the selected alignment, say) is not an undo step.
        if (document.formatVersion == version) return
        history.seal()
        clearComposing()
        history.record(selection.start, "", "", selection, selection, document.snapshot(), now, mergeable = false)
        afterSelectionMoved(now)
        // The keyboard keeps its own idea of the text around the caret; nothing it knows changed,
        // but the composing region was dropped.
        reportSelection()
        host.requestFrame()
    }

    /**
     * Enter on an empty list item steps it out a level, and out of the list at the top level,
     * instead of adding a line; on an empty indented line it steps back out, as in Samsung
     * Notes and Apple Notes.
     */
    private fun leaveEmptyListItem(now: Long): Boolean {
        val sel = selection
        if (!sel.isCollapsed) return false
        val p = document.paragraphAt(sel.start)
        if (document.paragraphStart(p) != document.paragraphEnd(p)) return false
        val next = stepOut(document.paragraphStyle(p)) ?: return false
        formatEdit(now) { document.setParagraphStyle(p, next) }
        return true
    }

    /** One step out of whatever marks the paragraph: a list level, a checklist, an indent. */
    private fun stepOut(s: ParagraphStyle): ParagraphStyle? = when {
        s.depth > 1 -> s.copy(lists = s.lists.dropLast(1))
        s.list != ListType.NONE -> withoutList(s)
        // Return on an empty line in a callout ends the callout, as it ends a list.
        s.role == Role.CALLOUT -> s.copy(role = Role.BODY)
        s.indent > 0 -> s.copy(indent = s.indent - 1)
        else -> null
    }

    /**
     * Backspace at the very start of a list item removes its bullet first, then its indent,
     * and only then joins it to the paragraph above.
     */
    private fun removeParagraphMarkAtCaret(now: Long): Boolean {
        val sel = selection
        if (!sel.isCollapsed) return false
        val p = document.paragraphAt(sel.start)
        if (document.paragraphStart(p) != sel.start) return false
        val next = stepOut(document.paragraphStyle(p)) ?: return false
        formatEdit(now) { document.setParagraphStyle(p, next) }
        return true
    }

    // --- Tables ----------------------------------------------------------------------------

    /** Whether the caret is in a table's cell. */
    val inTable: Boolean get() = document.paragraphStyle(document.paragraphAt(selection.focus)).cell != null

    /** Set while a table is rebuilt by a command, whose edit [keepsTables] must leave as it is. */
    private var restructuring = false

    private inline fun restructured(block: () -> Unit) {
        restructuring = true
        try {
            block()
        } finally {
            restructuring = false
        }
    }

    private fun cellOf(p: Int): TableCell? = document.paragraphStyle(p).cell

    /**
     * Keeps every table whole through an edit of [start, end) with [text], whatever made it:
     * typing, Backspace, the phone's keyboard, a paste. Two cells are never joined, nor a cell
     * to the text around its table, and a cell never takes a line break, which would split it.
     * Returns true when it made the edit itself, in the form that keeps the tables.
     */
    private fun keepsTables(start: Int, end: Int, text: CharSequence, after: Selection, now: Long, fromInput: Boolean, format: (() -> Unit)?): Boolean {
        val pa = document.paragraphAt(start)
        val pb = document.paragraphAt(end)
        if (pa == pb) {
            if (cellOf(pa) == null || !text.contains('\n')) return false
            // A line break would split the cell: a space keeps the words apart instead.
            restructured { edit(start, end, text.toString().replace('\n', ' '), after, now, mergeable = false, fromInput = fromInput) }
            return true
        }
        if ((pa..pb).none { cellOf(it) != null }) return false
        if (text.isEmpty() && pb == pa + 1 && end - start == 1) {
            val aEmpty = document.paragraphStart(pa) == document.paragraphEnd(pa)
            val bEmpty = document.paragraphStart(pb) == document.paragraphEnd(pb)
            when {
                // An empty line after a table goes, as any empty line does...
                cellOf(pa) != null && cellOf(pb) == null && bEmpty -> return false
                // ...and one before it, the table's first cell staying a cell.
                cellOf(pa) == null && aEmpty -> {
                    val cell = document.paragraphStyle(pb)
                    restructured { edit(start, end, "", after, now, mergeable = false, fromInput = fromInput) { document.setParagraphStyle(pa, cell) } }
                }
                // Anything else would join a cell to what is beside it: the caret goes there instead.
                else -> select(after, now)
            }
            return true
        }
        val whole = (pa..pb).filter { cellOf(it) != null }.map { layout.tableRange(it) }.distinct()
            .all { document.paragraphStart(it.first) >= start && document.paragraphEnd(it.last) <= end }
        if (whole) {
            // Whole tables go as any lines would; what is left where one began is not a cell.
            val merged = when {
                cellOf(pa) == null -> null
                cellOf(pb) == null -> document.paragraphStyle(pb)
                else -> ParagraphStyle.DEFAULT
            }
            val breaks = text.count { it == '\n' }
            restructured {
                edit(start, end, text, after, now, mergeable = false, fromInput = fromInput) {
                    format?.invoke()
                    if (merged != null) document.setParagraphStyle(pa, merged)
                    for (q in pa..pa + breaks) if (document.paragraphStyle(q).cell != null) document.setParagraphStyle(q, ParagraphStyle.DEFAULT)
                }
            }
            return true
        }
        // Part of a table: each cell and line the selection touches is emptied where it is.
        val styles = (pa..pb).map { document.paragraphStyle(it) }
        val typed = text.toString().replace('\n', ' ')
        val replacement = typed + "\n".repeat(pb - pa)
        if (replacement == document.substring(start, end)) {
            select(Selection(start), now)
            return true
        }
        restructured {
            edit(start, end, replacement, Selection(start + typed.length), now, mergeable = false, fromInput = fromInput) {
                for ((k, style) in styles.withIndex()) document.setParagraphStyle(pa + k, style)
            }
        }
        return true
    }

    /** A table ID no paragraph uses yet. */
    private fun newTableId(): Int {
        var max = 0
        for (p in 0 until document.paragraphCount) document.paragraphStyle(p).cell?.let { max = maxOf(max, it.table) }
        return max + 1
    }

    /**
     * A table pasted is a block of its own: on a new line when the caret is in the middle of
     * one, and with a line after it to go on writing in.
     */
    private fun aroundTables(fragment: Fragment, atParagraphStart: Boolean): Fragment {
        val pieces = fragment.paragraphs
        if (pieces.none { it.style.cell != null }) return fragment
        val out = ArrayList<Piece>()
        if (!atParagraphStart && pieces.first().style.cell != null) out += Piece("", ParagraphStyle.DEFAULT)
        out += pieces
        if (pieces.last().style.cell != null) out += Piece("", ParagraphStyle.DEFAULT)
        return Fragment(out)
    }

    /**
     * Return in a cell goes to the cell below, as in Notion, adding a row under the last; a
     * cell is one paragraph, so there is no line to add inside it.
     */
    private fun cellBelow(now: Long): Boolean {
        if (!selection.isCollapsed) return false
        val p = document.paragraphAt(selection.focus)
        val cell = cellOf(p) ?: return false
        val row = layout.rowRange(p)
        if (row.last == layout.tableRange(p).last) {
            addRow(below = true, now)
            return true
        }
        val next = layout.rowRange(row.last + 1)
        val target = next.firstOrNull { cellOf(it)!!.column == cell.column } ?: next.last
        select(Selection(document.paragraphEnd(target)), now)
        return true
    }

    /** Tab goes on to the next cell, adding a row after the last one; Shift-Tab goes back. */
    private fun nextCell(backward: Boolean, now: Long): Boolean {
        val p = document.paragraphAt(selection.focus)
        if (cellOf(p) == null) return false
        val table = layout.tableRange(p)
        when {
            backward && p > table.first -> select(Selection(document.paragraphEnd(p - 1)), now)
            backward -> {}
            p < table.last -> select(Selection(document.paragraphEnd(p + 1)), now)
            else -> {
                select(Selection(document.paragraphEnd(p)), now)
                addRow(below = true, now)
                select(Selection(document.paragraphEnd(layout.rowRange(document.paragraphAt(selection.focus)).first)), now)
            }
        }
        return true
    }

    /**
     * A table at the very end of a note has no line below it to go on to; going down from its
     * last row adds one, as below a code block.
     */
    private fun leaveTableAtEnd(now: Long): Boolean {
        val p = document.paragraphAt(selection.focus)
        if (cellOf(p) == null || layout.tableRange(p).last != document.paragraphCount - 1 || layout.rowRange(p).last != document.paragraphCount - 1) return false
        if (layout.lineBounds(selection.focus, selection.upstream).last != document.paragraphEnd(p)) return false
        val end = document.length
        clearComposing()
        restructured {
            edit(end, end, "\n", Selection(end + 1), now, mergeable = false, fromInput = false) {
                document.setParagraphStyle(document.paragraphCount - 1, ParagraphStyle.DEFAULT)
            }
        }
        return true
    }

    /**
     * A table of [rows] by [columns] empty cells below the paragraph the caret is in, its first
     * row a header, and the caret in its first cell; an empty line takes the table itself. A line
     * after it is there to go on writing in.
     */
    fun insertTable(rows: Int = 3, columns: Int = 3, now: Long) {
        if (!editable || rows < 1 || columns < 1) return
        val p = layout.tableRange(document.paragraphAt(selection.end)).last
        val ps = document.paragraphStyle(p)
        val replacesLine = document.paragraphStart(p) == document.paragraphEnd(p) && !ps.isObject && ps.cell == null
        val cells = rows * columns
        val id = newTableId()
        val first = if (replacesLine) p else p + 1
        // The line it replaces moves down below it; otherwise a line is added when none follows.
        val lineAfter = replacesLine || p + 1 >= document.paragraphCount || cellOf(p + 1) != null
        val at = if (replacesLine) document.paragraphStart(p) else document.paragraphEnd(p)
        val text = "\n".repeat(if (replacesLine || !lineAfter) cells else cells + 1)
        clearComposing()
        restructured {
            edit(at, at, text, Selection(if (replacesLine) at else at + 1), now, mergeable = false, fromInput = false) {
                for (k in 0 until cells) document.setParagraphStyle(first + k, ParagraphStyle(cell = TableCell(id, k / columns, k % columns, header = true)))
                if (lineAfter) document.setParagraphStyle(first + cells, ParagraphStyle.DEFAULT)
            }
        }
    }

    /** A cell of a table being rebuilt: an old cell's paragraph wherever it moves, or a new empty one. */
    private class NewCell(val from: Int?, val row: Int, val column: Int)

    /** What an old cell holds, to put back wherever it goes. */
    private class SavedCell(
        val text: String,
        val style: ParagraphStyle,
        val charStyles: List<Triple<CharStyle, Int, Int>>,
        val values: List<Pair<ValueStyle, IntArray>>,
    )

    private fun save(p: Int): SavedCell {
        val a = document.paragraphStart(p)
        val b = document.paragraphEnd(p)
        val chars = ArrayList<Triple<CharStyle, Int, Int>>()
        for (style in CharStyle.entries) {
            val set = document.styleRanges(style)
            for (i in 0 until set.count) {
                val s = maxOf(set.start(i), a)
                val e = minOf(set.end(i), b)
                if (s < e) chars += Triple(style, s - a, e - a)
            }
        }
        val values = ArrayList<Pair<ValueStyle, IntArray>>()
        for (style in ValueStyle.entries) {
            val ranges = document.valueRanges(style)
            for (i in 0 until ranges.count) {
                val s = maxOf(ranges.start(i), a)
                val e = minOf(ranges.end(i), b)
                if (s < e) values += style to intArrayOf(s - a, e - a, ranges.value(i))
            }
        }
        return SavedCell(document.substring(a, b), document.paragraphStyle(p), chars, values)
    }

    /**
     * Rebuilds the table that paragraph [p] is in as [cells], in one undo step: each old cell
     * keeps its text and formatting wherever it goes. The caret goes to the end of new cell
     * [caretCell]. A table left with no cells is taken away.
     */
    private fun rebuildTable(p: Int, cells: List<NewCell>, caretCell: Int, now: Long, header: Boolean? = null) {
        val range = layout.tableRange(p)
        if (cells.isEmpty()) {
            deleteTable(now)
            return
        }
        val id = cellOf(range.first)!!.table
        val keepsHeader = header ?: cellOf(range.first)!!.header
        val saved = range.associateWith { save(it) }
        val start = document.paragraphStart(range.first)
        val end = document.paragraphEnd(range.last)
        val texts = cells.map { c -> c.from?.let { saved.getValue(it).text } ?: "" }
        val text = texts.joinToString("\n")
        var caret = start
        for (k in 0..caretCell.coerceIn(0, cells.size - 1)) caret += texts[k].length + if (k < caretCell) 1 else 0
        clearComposing()
        restructured {
            edit(start, end, text, Selection(caret), now, mergeable = false, fromInput = false) {
                for (style in CharStyle.entries) document.setStyle(style, false, start, start + text.length)
                for (style in ValueStyle.entries) document.setValue(style, null, start, start + text.length)
                var at = start
                for ((k, c) in cells.withIndex()) {
                    val old = c.from?.let { saved.getValue(it) }
                    if (old != null) {
                        for ((style, a, b) in old.charStyles) document.setStyle(style, true, at + a, at + b)
                        for ((style, v) in old.values) document.setValue(style, v[2], at + v[0], at + v[1])
                    }
                    val base = old?.style ?: ParagraphStyle.DEFAULT
                    document.setParagraphStyle(range.first + k, base.copy(cell = TableCell(id, c.row, c.column, keepsHeader)))
                    at += texts[k].length + 1
                }
            }
        }
    }

    /** The table the caret is in as rows of its paragraphs, and the caret's row and column. */
    private fun grid(): Triple<List<List<Int>>, Int, Int>? {
        val p = document.paragraphAt(selection.focus)
        val cell = cellOf(p) ?: return null
        val rows = layout.tableRange(p).groupBy { cellOf(it)!!.row }.values.toList()
        val row = rows.indexOfFirst { p in it }
        return Triple(rows, row, rows[row].indexOf(p).coerceAtLeast(0).let { minOf(it, cell.column) })
    }

    /** As many columns as the table's widest row: rows left short are filled with empty cells. */
    private fun columnsOf(rows: List<List<Int>>): Int = rows.maxOf { r -> r.maxOf { cellOf(it)!!.column } + 1 }

    private fun rebuilt(rows: List<List<Int?>>): List<NewCell> =
        rows.flatMapIndexed { r, row -> row.mapIndexed { c, from -> NewCell(from, r, c) } }

    private fun cellsOf(rows: List<List<Int>>, columns: Int): List<MutableList<Int?>> = rows.map { row ->
        MutableList(columns) { c -> row.firstOrNull { cellOf(it)!!.column == c } }
    }

    /** A row of empty cells above or below the caret's, the caret in it. */
    fun addRow(below: Boolean, now: Long) {
        val (rows, row, column) = grid() ?: return
        val columns = columnsOf(rows)
        val grid = cellsOf(rows, columns).toMutableList<List<Int?>>()
        val at = if (below) row + 1 else row
        grid.add(at, List(columns) { null })
        rebuildTable(rows[0][0], rebuilt(grid), at * columns + column, now)
    }

    /** A column of empty cells left or right of the caret's, the caret in it. */
    fun addColumn(right: Boolean, now: Long) {
        val (rows, row, column) = grid() ?: return
        val columns = columnsOf(rows)
        val at = if (right) column + 1 else column
        val grid = cellsOf(rows, columns).map { it.apply { add(at, null) } }
        rebuildTable(rows[0][0], rebuilt(grid), row * (columns + 1) + at, now)
    }

    /** Takes away the caret's row; the last row taken takes the table. */
    fun deleteRow(now: Long) {
        val (rows, row, column) = grid() ?: return
        if (rows.size == 1) return deleteTable(now)
        val columns = columnsOf(rows)
        val grid = cellsOf(rows, columns).toMutableList<List<Int?>>()
        grid.removeAt(row)
        rebuildTable(rows[0][0], rebuilt(grid), minOf(row, grid.size - 1) * columns + column, now)
    }

    /** Takes away the caret's column; the last column taken takes the table. */
    fun deleteColumn(now: Long) {
        val (rows, row, column) = grid() ?: return
        val columns = columnsOf(rows)
        if (columns == 1) return deleteTable(now)
        val grid = cellsOf(rows, columns).map { it.apply { removeAt(column) } }
        rebuildTable(rows[0][0], rebuilt(grid), row * (columns - 1) + minOf(column, columns - 2), now)
    }

    /** The table's first row as its header, shaded and bold, or as a row like the others. */
    fun toggleHeaderRow(now: Long) {
        val p = document.paragraphAt(selection.focus)
        val cell = cellOf(p) ?: return
        val range = layout.tableRange(p)
        formatEdit(now) {
            for (q in range) {
                val s = document.paragraphStyle(q)
                document.setParagraphStyle(q, s.copy(cell = s.cell!!.copy(header = !cell.header)))
            }
        }
    }

    /** Takes away the table the caret is in, with its line; the caret goes where it was. */
    fun deleteTable(now: Long) {
        val p = document.paragraphAt(selection.focus)
        if (cellOf(p) == null || !editable) return
        val range = layout.tableRange(p)
        val start = document.paragraphStart(range.first)
        val end = document.paragraphEnd(range.last)
        clearComposing()
        restructured {
            when {
                // The line after it moves up into its place, keeping its own style.
                range.last + 1 < document.paragraphCount -> {
                    val next = document.paragraphStyle(range.last + 1)
                    edit(start, end + 1, "", Selection(start), now, mergeable = false, fromInput = false) {
                        document.setParagraphStyle(range.first, next)
                    }
                }
                range.first > 0 -> edit(start - 1, end, "", Selection(start - 1), now, mergeable = false, fromInput = false)
                else -> edit(start, end, "", Selection(start), now, mergeable = false, fromInput = false) {
                    document.setParagraphStyle(0, ParagraphStyle.DEFAULT)
                }
            }
        }
    }

    // --- Pictures and dividers --------------------------------------------------------------

    /**
     * Backspace or Delete beside a picture or divider takes the whole of it, its line
     * included, in one step: one keystroke, one thing gone, never a picture's line joined to
     * the text beside it.
     */
    private fun deleteObjectAtCaret(backward: Boolean, now: Long): Boolean {
        val at = selection.start
        val p = document.paragraphAt(at)
        val start = document.paragraphStart(p)
        val end = document.paragraphEnd(p)
        // An empty line below a picture goes first, leaving the caret after the picture; the
        // next Backspace takes the picture. A picture is never one stray keystroke from gone.
        if (backward && at == start && start == end && p > 0 && document.paragraphStyle(p - 1).isObject) {
            clearComposing()
            edit(start - 1, start, "", Selection(start - 1), now, mergeable = false, fromInput = true)
            return true
        }
        val target = when {
            document.paragraphStyle(p).isObject -> p
            backward && at == start && p > 0 && document.paragraphStyle(p - 1).isObject -> p - 1
            !backward && at == end && p + 1 < document.paragraphCount && document.paragraphStyle(p + 1).isObject -> p + 1
            else -> return false
        }
        val (from, to) = objectRange(target)
        clearComposing()
        edit(from, to, "", Selection(from), now, mergeable = false, fromInput = true)
        return true
    }

    /** The text that holds paragraph [p] (an object) with one of the line breaks beside it. */
    private fun objectRange(p: Int): Pair<Int, Int> {
        val start = document.paragraphStart(p)
        val end = document.paragraphEnd(p)
        return when {
            p + 1 < document.paragraphCount -> start to end + 1
            p > 0 -> start - 1 to end
            else -> start to end
        }
    }

    /**
     * Typing with the caret beside a picture or divider: the text goes on a line of its own,
     * before it when the caret is before it, after it otherwise.
     */
    private fun leaveObjectParagraph(now: Long) {
        val sel = selection
        if (!sel.isCollapsed) return
        val p = document.paragraphAt(sel.start)
        if (!document.paragraphStyle(p).isObject) return
        val start = document.paragraphStart(p)
        clearComposing()
        if (sel.start == start) edit(start, start, "\n", Selection(start), now, mergeable = false, fromInput = true)
        else {
            val end = document.paragraphEnd(p)
            edit(end, end, "\n", Selection(end + 1), now, mergeable = false, fromInput = true)
        }
    }

    // --- Keys -----------------------------------------------------------------------------

    fun onKey(key: Key, shift: Boolean, word: Boolean, now: Long): Boolean {
        val text = document.text
        val sel = selection
        when (key) {
            Key.BACKSPACE -> {
                if (!sel.isCollapsed) deleteSelection(now)
                else if (deleteObjectAtCaret(backward = true, now)) {}
                else if (removeParagraphMarkAtCaret(now)) {}
                else if (sel.start > 0) {
                    val from = if (word) Boundaries.previousWordStart(text, sel.start) else Boundaries.previousCluster(text, sel.start)
                    clearComposing()
                    edit(from, sel.start, "", Selection(from), now, mergeable = true, fromInput = true)
                }
            }
            Key.DELETE -> {
                if (!sel.isCollapsed) deleteSelection(now)
                else if (deleteObjectAtCaret(backward = false, now)) {}
                else if (sel.end < document.length) {
                    val to = if (word) Boundaries.nextWordEnd(text, sel.end) else Boundaries.nextCluster(text, sel.end)
                    clearComposing()
                    edit(sel.end, to, "", Selection(sel.end), now, mergeable = true, fromInput = true)
                }
            }
            Key.ENTER -> type("\n", now)
            Key.TAB -> if (!nextCell(backward = shift, now)) type("\t", now)
            Key.UNDO -> undo(now)
            Key.REDO -> redo(now)
            Key.SELECT_ALL -> select(Selection(0, document.length), now)
            Key.LEFT, Key.RIGHT -> {
                // In a paragraph that runs right to left, the left arrow goes on through the
                // text and the right one back.
                val forward = (key == Key.RIGHT) != layout.paragraph(document.paragraphAt(sel.focus)).rtl
                if (forward) move(shift, now) {
                    if (!shift && !sel.isCollapsed) sel.end
                    else if (word) Boundaries.nextWordEnd(text, sel.focus)
                    else Boundaries.nextCluster(text, sel.focus)
                } else move(shift, now) {
                    if (!shift && !sel.isCollapsed) sel.start
                    else if (word) Boundaries.previousWordStart(text, sel.focus)
                    else Boundaries.previousCluster(text, sel.focus)
                }
            }
            Key.UP, Key.DOWN, Key.PAGE_UP, Key.PAGE_DOWN -> moveVertically(key, shift, now)
            Key.HOME -> move(shift, now) { if (word) 0 else layout.lineBounds(sel.focus, sel.upstream).first }
            Key.END -> {
                if (word) move(shift, now) { document.length }
                else {
                    // The end of a wrapped line is the same offset as the next line's start; the
                    // caret stays on this line by being upstream.
                    val target = layout.lineBounds(sel.focus, sel.upstream).last
                    val atWrap = target < document.paragraphEnd(document.paragraphAt(sel.focus))
                    select(Selection(if (shift) sel.anchor else target, target, upstream = atWrap && !shift), now)
                }
            }
        }
        return true
    }

    private inline fun move(shift: Boolean, now: Long, target: () -> Int) {
        val t = target()
        select(if (shift) Selection(selection.anchor, t) else Selection(t), now)
    }

    /**
     * A code block at the very end of a note has no line below it to go on to, and Return
     * only adds a line of code, so the caret could never leave it. Going below its last line,
     * by the down arrow or a tap under it, adds a line of plain text there, as Notion does.
     */
    private fun leaveCodeAtEnd(now: Long): Boolean {
        val last = document.paragraphCount - 1
        if (!editable || document.paragraphStyle(last).role != Role.CODE_BLOCK) return false
        val end = document.length
        clearComposing()
        edit(end, end, "\n", Selection(end + 1), now, mergeable = false, fromInput = false) {
            document.setParagraphStyle(last + 1, ParagraphStyle.DEFAULT)
        }
        return true
    }

    private fun moveVertically(key: Key, shift: Boolean, now: Long) {
        if (key == Key.DOWN && !shift && selection.isCollapsed && leaveTableAtEnd(now)) return
        if (key == Key.DOWN && !shift && selection.isCollapsed &&
            layout.lineBounds(selection.focus, selection.upstream).last == document.length && leaveCodeAtEnd(now)
        ) return
        val r = layout.caretRect(selection.focus, selection.upstream)
        val x = if (goalX.isNaN()) r.x else goalX
        fun middle(c: CaretRect) = (c.top + c.bottom) / 2
        // Up and down go to the line itself rather than a line's height away: the room around
        // a code block or a callout is taller than a line, and a step of one only reached it,
        // so the caret could never leave the block.
        val focus = selection.focus
        val line = layout.lineBounds(focus, selection.upstream)
        val p = document.paragraphAt(focus)
        // In a table the line below a cell's last is the row below's, not the next cell's, which
        // is beside it; above, the bottom of the row above, so the caret lands on a cell's last line.
        val below = layout.rowRange(p).last + 1
        val above = layout.rowRange(p).first - 1
        val y = when (key) {
            Key.DOWN -> when {
                line.last < document.paragraphEnd(p) -> middle(layout.caretRect(line.last, false))
                below < document.paragraphCount -> middle(layout.caretRect(document.paragraphStart(below), false))
                else -> layout.height
            }
            Key.UP -> when {
                line.first > document.paragraphStart(p) -> middle(layout.caretRect(line.first, true))
                above >= 0 && document.paragraphStyle(above).cell != null -> layout.paragraphTop(above) + layout.boxHeight(above) - 1f
                above >= 0 -> middle(layout.caretRect(document.paragraphEnd(above), false))
                else -> -1f
            }
            Key.PAGE_UP -> middle(r) - (viewHeight - insetTop - insetBottom)
            else -> middle(r) + viewHeight - insetTop - insetBottom
        }
        val (target, upstream) = when {
            y < 0 -> 0 to false
            y >= layout.height -> document.length to false
            else -> layout.offsetAt(x, y)
        }
        select(if (shift) Selection(selection.anchor, target) else Selection(target, upstream = upstream), now)
        // Moving through a short line must not forget the column we started in.
        goalX = x
    }

    private fun deleteSelection(now: Long) {
        clearComposing()
        val s = selection.start
        edit(s, selection.end, "", Selection(s), now, mergeable = false, fromInput = true)
    }

    // --- Pointer gestures -----------------------------------------------------------------

    private enum class Drag { NONE, PENDING, SCROLL, SELECT, HANDLE }

    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastY = 0f
    private var lastTapTime = -1_000_000L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var grabDx = 0f
    private var grabDy = 0f
    private val velocity = VelocityTracker()
    private var flingVelocity = 0f
    private var flingTime = 0L

    fun onPointer(e: PointerEvent) {
        when (e.action) {
            PointerAction.DOWN -> {
                flingVelocity = 0f
                downX = e.x
                downY = e.y
                downTime = e.time
                lastY = e.y
                velocity.reset()
                velocity.add(e.time, e.y)
                drag = if (grabHandle(e.x, e.y)) Drag.HANDLE else Drag.PENDING
            }
            PointerAction.MOVE -> {
                velocity.add(e.time, e.y)
                when (drag) {
                    Drag.PENDING -> if (hypot(e.x - downX, e.y - downY) > style.touchSlop) {
                        drag = Drag.SCROLL
                        scrollTo(scrollY + (downY - e.y))
                    }
                    Drag.SCROLL -> scrollTo(scrollY + (lastY - e.y))
                    Drag.SELECT -> extendByWords(e.x, e.y, e.time)
                    Drag.HANDLE -> dragHandle(e.x, e.y, e.time)
                    Drag.NONE -> {}
                }
                lastY = e.y
            }
            PointerAction.UP -> {
                when (drag) {
                    Drag.PENDING -> tap(e.x, e.y, e.time)
                    Drag.SCROLL -> {
                        flingVelocity = -velocity.velocity()
                        flingTime = e.time
                        if (abs(flingVelocity) < MIN_FLING_VELOCITY) flingVelocity = 0f else host.requestFrame()
                    }
                    Drag.HANDLE -> if (selection.isCollapsed) insertionHandleUntil = e.time + style.insertionHandleMs
                    else -> {}
                }
                drag = Drag.NONE
            }
            PointerAction.CANCEL -> drag = Drag.NONE
        }
    }

    /** Advances time-driven behaviour: long-press and fling. Call before each frame. */
    fun tick(now: Long) {
        if (drag == Drag.PENDING && now - downTime >= style.longPressMs) longPress(downX, downY, now)
        if (flingVelocity != 0f) {
            val dt = (now - flingTime).coerceAtLeast(0)
            flingTime = now
            val before = scrollY
            scrollTo(scrollY + flingVelocity * dt)
            flingVelocity *= exp(-dt / FLING_DECAY_MS)
            if (abs(flingVelocity) < STOP_VELOCITY || (dt > 0 && scrollY == before)) flingVelocity = 0f
            else host.requestFrame()
        }
    }

    /** When the next frame is needed without any input: a blink, a long-press, an animation. */
    fun nextFrameAt(now: Long): Long {
        var next = Long.MAX_VALUE
        if (flingVelocity != 0f) next = now
        if (drag == Drag.PENDING) next = minOf(next, downTime + style.longPressMs)
        if (focused && selection.isCollapsed) {
            val phase = (now - caretEpoch) / style.blinkMs
            next = minOf(next, caretEpoch + (phase + 1) * style.blinkMs)
        }
        if (insertionHandleUntil > now) next = minOf(next, insertionHandleUntil)
        return if (next == Long.MAX_VALUE) -1 else next
    }

    private fun offsetAtView(x: Float, y: Float): Pair<Int, Boolean> =
        layout.offsetAt(x - originX, y - originY)

    private fun tap(x: Float, y: Float, now: Long) {
        if (tapCheckbox(x, y, now)) return
        if (tapCodeLanguage(x, y)) return
        val isDouble = now - lastTapTime < style.doubleTapMs && hypot(x - lastTapX, y - lastTapY) < style.touchSlop * 4
        focused = true
        host.showKeyboard()
        if (isDouble) {
            lastTapTime = -1_000_000L
            selectWordAt(x, y, now)
            return
        }
        lastTapTime = now
        lastTapX = x
        lastTapY = y
        if (y - originY > layout.height && leaveCodeAtEnd(now)) {
            insertionHandleUntil = now + style.insertionHandleMs
            return
        }
        val (offset, upstream) = offsetAtView(x, y)
        insertionHandleUntil = now + style.insertionHandleMs
        select(Selection(offset, upstream = upstream), now)
        host.requestFrame()
    }

    private fun longPress(x: Float, y: Float, now: Long) {
        focused = true
        host.showKeyboard()
        if (selectWordAt(x, y, now)) {
            drag = Drag.SELECT
        } else {
            drag = Drag.NONE
            insertionHandleUntil = now + style.insertionHandleMs
            host.requestFrame()
        }
    }

    // The word a long-press selected; dragging afterwards grows the selection from it.
    private var pressedWordStart = 0
    private var pressedWordEnd = 0

    /**
     * After a long-press, dragging extends the selection a whole word at a time and never
     * loses the word that was pressed, as on Samsung and stock Android.
     */
    private fun extendByWords(x: Float, y: Float, now: Long) {
        val text = document.text
        val (offset, _) = offsetAtView(x, y)
        val target = when {
            offset < pressedWordStart -> Selection(pressedWordEnd, Boundaries.wordAt(text, offset).let { if (it.isEmpty()) offset else it.first })
            offset > pressedWordEnd -> Selection(pressedWordStart, Boundaries.wordAt(text, offset).let { if (it.isEmpty()) offset else it.last + 1 })
            else -> Selection(pressedWordStart, pressedWordEnd)
        }
        if (target != selection) select(target, now)
    }

    private fun selectWordAt(x: Float, y: Float, now: Long): Boolean {
        val (offset, upstream) = offsetAtView(x, y)
        val word = Boundaries.wordAt(document.text, offset)
        if (word.isEmpty()) {
            select(Selection(offset, upstream = upstream), now)
            return false
        }
        pressedWordStart = word.first
        pressedWordEnd = word.last + 1
        select(Selection(word.first, word.last + 1), now)
        return true
    }

    private class HandleSpot(val tipX: Float, val tipY: Float, val centerX: Float, val centerY: Float)

    private fun handleAt(offset: Int, upstream: Boolean, side: Int): HandleSpot {
        val r = layout.caretRect(offset, upstream)
        val tipX = originX + r.x
        val tipY = originY + r.baseline + r.descent
        val radius = style.handleRadius
        return when (side) {
            -1 -> HandleSpot(tipX, tipY, tipX - radius, tipY + radius)
            1 -> HandleSpot(tipX, tipY, tipX + radius, tipY + radius)
            else -> HandleSpot(tipX, tipY, tipX, tipY + radius * SQRT2)
        }
    }

    private fun insertionHandleVisible(now: Long) = focused && selection.isCollapsed && now < insertionHandleUntil

    /** Picks up a handle under the finger and makes its end of the selection the moving end. */
    private fun grabHandle(x: Float, y: Float): Boolean {
        if (!focused) return false
        val sel = selection
        val candidates = if (sel.isCollapsed) {
            if (downTime >= insertionHandleUntil) return false
            listOf(Triple(handleAt(sel.focus, sel.upstream, 0), sel.focus, sel.focus))
        } else {
            listOf(
                Triple(handleAt(sel.start, false, -1), sel.start, sel.end),
                Triple(handleAt(sel.end, true, 1), sel.end, sel.start),
            )
        }
        val hit = candidates.minByOrNull { hypot(x - it.first.centerX, y - it.first.centerY) } ?: return false
        if (hypot(x - hit.first.centerX, y - hit.first.centerY) > style.handleTouchRadius) return false
        val (spot, moving, fixed) = hit
        // Keep the finger's offset from the caret, so the text under it does not jump.
        grabDx = x - spot.tipX
        grabDy = y - (spot.tipY - style.lineHeight / 2)
        selection = Selection(fixed, moving)
        return true
    }

    private fun dragHandle(x: Float, y: Float, now: Long) {
        val (offset, upstream) = offsetAtView(x - grabDx, y - grabDy)
        val sel = selection
        if (sel.anchor == sel.focus && insertionHandleUntil > downTime) {
            insertionHandleUntil = now + style.insertionHandleMs
            select(Selection(offset, upstream = upstream), now)
            return
        }
        // A selection handle never collapses the selection; it can cross the other handle.
        if (offset != sel.anchor) select(Selection(sel.anchor, offset), now)
    }

    // --- Mouse (the Mac) -------------------------------------------------------------------

    private enum class MouseDrag { NONE, CHARACTERS, WORDS, PARAGRAPHS }

    private var mouseDrag = MouseDrag.NONE
    private var mouseAnchor = 0

    /**
     * A mouse button, the Mac's way rather than a finger's: a press puts the caret under it at
     * once, Shift extends the selection to it, and dragging selects instead of scrolling (the
     * wheel and trackpad scroll, through [scrollBy]). [clicks] is the platform's own count, so
     * the user's double-click speed in System Settings decides what a double click is: two
     * select a word and keep selecting by words while dragging, three a paragraph.
     */
    fun onMouse(action: PointerAction, x: Float, y: Float, clicks: Int, shift: Boolean, now: Long) {
        when (action) {
            PointerAction.DOWN -> {
                flingVelocity = 0f
                focused = true
                if (!shift && clicks == 1 && (tapCheckbox(x, y, now) || tapCodeLanguage(x, y))) {
                    mouseDrag = MouseDrag.NONE
                    return
                }
                val (offset, upstream) = offsetAtView(x, y)
                when {
                    shift -> {
                        mouseAnchor = selection.anchor
                        mouseDrag = MouseDrag.CHARACTERS
                        select(Selection(mouseAnchor, offset), now)
                    }
                    clicks >= 3 -> {
                        val p = document.paragraphAt(offset)
                        pressedWordStart = document.paragraphStart(p)
                        pressedWordEnd = document.paragraphEnd(p)
                        mouseDrag = MouseDrag.PARAGRAPHS
                        select(Selection(pressedWordStart, pressedWordEnd), now)
                    }
                    clicks == 2 -> {
                        mouseDrag = MouseDrag.WORDS
                        selectWordAt(x, y, now)
                    }
                    else -> {
                        mouseAnchor = offset
                        mouseDrag = MouseDrag.CHARACTERS
                        select(Selection(offset, upstream = upstream), now)
                    }
                }
                host.requestFrame()
            }
            PointerAction.MOVE -> {
                if (mouseDrag == MouseDrag.NONE) return
                // Dragging past the top or bottom scrolls toward the pointer, by how far past.
                val visibleTop = insetTop
                val visibleBottom = viewHeight - insetBottom
                if (y < visibleTop) scrollTo(scrollY - (visibleTop - y))
                else if (y > visibleBottom) scrollTo(scrollY + (y - visibleBottom))
                when (mouseDrag) {
                    MouseDrag.CHARACTERS -> {
                        val (offset, _) = offsetAtView(x, y)
                        if (offset != selection.focus) select(Selection(mouseAnchor, offset), now)
                    }
                    MouseDrag.WORDS -> extendByWords(x, y, now)
                    MouseDrag.PARAGRAPHS -> extendByParagraphs(x, y, now)
                    MouseDrag.NONE -> {}
                }
            }
            PointerAction.UP, PointerAction.CANCEL -> mouseDrag = MouseDrag.NONE
        }
    }

    private fun extendByParagraphs(x: Float, y: Float, now: Long) {
        val p = document.paragraphAt(offsetAtView(x, y).first)
        val start = document.paragraphStart(p)
        val end = document.paragraphEnd(p)
        val target = when {
            start < pressedWordStart -> Selection(pressedWordEnd, start)
            end > pressedWordEnd -> Selection(pressedWordStart, end)
            else -> Selection(pressedWordStart, pressedWordEnd)
        }
        if (target != selection) select(target, now)
    }

    /** The wheel or the trackpad, in view pixels; positive scrolls toward the end of the note. */
    fun scrollBy(dy: Float) {
        flingVelocity = 0f
        scrollTo(scrollY + dy)
    }

    /**
     * Whether the editor has the keyboard. On the phone a tap gives it; on the Mac the window
     * also takes it away, and an editor that lost it shows no caret.
     */
    fun setFocused(value: Boolean, now: Long) {
        if (focused == value) return
        focused = value
        caretEpoch = now
        host.requestFrame()
    }

    // --- Finding ---------------------------------------------------------------------------

    /** What Find in Note looks for; empty when it is closed. */
    var findQuery = ""
        private set
    /** Every match, as start and end offsets in turn, in order. Drawn behind the text, never part of it. */
    private var matches = IntArray(0)
    /** Which match Find is on, counting from 0; -1 when there is none. */
    var currentMatch = -1
        private set
    val matchCount: Int get() = matches.size / 2

    /**
     * Marks every match of [query] in the note, case and accents aside, and goes to the first
     * one at or after the caret. Returns how many there are. Nothing in the note changes, so
     * nothing is saved and nothing can be undone.
     */
    fun find(query: String): Int {
        findQuery = query
        findMatches()
        currentMatch = when {
            matchCount == 0 -> -1
            else -> (0 until matchCount).firstOrNull { matches[2 * it] >= selection.start } ?: 0
        }
        revealMatch()
        host.requestFrame()
        return matchCount
    }

    /** Goes to the next match, or the previous, round from the end to the start. */
    fun findNext(forward: Boolean = true) {
        if (matchCount == 0) return
        currentMatch = ((if (currentMatch < 0) 0 else currentMatch + if (forward) 1 else -1) + matchCount) % matchCount
        revealMatch()
        host.requestFrame()
    }

    /** Closes Find: the markers go. The caret is left on the match Find was on, to carry on from there. */
    fun endFind(now: Long) {
        if (currentMatch >= 0) select(Selection(matches[2 * currentMatch], matches[2 * currentMatch + 1]), now)
        findQuery = ""
        matches = IntArray(0)
        currentMatch = -1
        host.requestFrame()
    }

    private fun findMatches() {
        val needle = fold(findQuery)
        if (needle.isEmpty()) {
            matches = IntArray(0)
            return
        }
        val hay = fold(document.text)
        val found = ArrayList<Int>()
        var at = hay.indexOf(needle)
        while (at >= 0) {
            found += at
            found += at + needle.length
            at = hay.indexOf(needle, at + needle.length)
        }
        matches = found.toIntArray()
    }

    /** The match Find is on, scrolled into the middle of the view if it is not on screen. */
    private fun revealMatch() {
        if (currentMatch < 0 || viewHeight == 0) return
        val r = layout.caretRect(matches[2 * currentMatch], false)
        val top = originY + r.top
        val bottom = originY + r.bottom
        if (top < insetTop || bottom > viewHeight - insetBottom) {
            scrollTo(scrollY + top - insetTop - (viewHeight - insetTop - insetBottom) / 3f)
        }
    }

    // --- Painting -------------------------------------------------------------------------

    private var glyphIds = IntArray(256)
    private var glyphXs = FloatArray(256)
    private var glyphYs = FloatArray(256)
    private val path = PathBuilder()
    /** Where the paragraph being painted starts across: the text area's left, or its cell's column. */
    private var paintX = 0f

    fun render(list: DrawList, now: Long) {
        list.reset()
        list.rect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), style.background)
        if (viewWidth == 0 || viewHeight == 0) return
        drawPlaceholder(list)
        val first = layout.paragraphAtY(scrollY - style.paddingTop - insetTop)
        for (p in first until document.paragraphCount) {
            val top = originY + layout.paragraphTop(p)
            if (top > viewHeight) break
            val pl = layout.paragraph(p)
            if (top + layout.boxHeight(p) < 0) continue
            renderParagraph(list, p, pl, top)
        }

        val sel = selection
        if (focused) {
            if (sel.isCollapsed) {
                // A note that is only shown has no caret: there is nowhere to type.
                val visible = editable && ((now - caretEpoch) / style.blinkMs) % 2 == 0L
                if (visible) {
                    val r = layout.caretRect(sel.focus, sel.upstream)
                    val top = originY + r.baseline - r.ascent
                    list.rect(originX + r.x - style.caretWidth / 2, top, style.caretWidth, r.ascent + r.descent, style.caretColor)
                }
                if (style.handles && insertionHandleVisible(now)) drawHandle(list, handleAt(sel.focus, sel.upstream, 0), 0)
            } else if (style.handles) {
                drawHandle(list, handleAt(sel.start, false, -1), -1)
                drawHandle(list, handleAt(sel.end, true, 1), 1)
            }
        }
    }

    /**
     * A picture at its size in its line, or an empty frame while it is decoded; when it is
     * selected, the selection's colour lies over it, see-through.
     */
    private fun drawPicture(list: DrawList, handle: Int, pl: ParagraphLayout, top: Float, base: Int) {
        val x = paintX + pl.glyphX(0, 0)
        val y = top + pl.lineTop(0) + pl.objectTop
        val w = pl.objectWidth
        val h = pl.objectHeight
        val image = pictures?.image(handle, Math.round(w), Math.round(h))
        if (image != null) list.image(image, x, y) else list.rect(x, y, w, h, style.codeBackground)
        val sel = selection
        if (!sel.isCollapsed && sel.start <= base && sel.end > base) {
            list.rect(x, y, w, h, (style.selectionColor and 0x00FFFFFF) or 0x66000000)
        }
    }

    /** Find's markers on one line: the line's full height, as a highlighter would draw. */
    private fun drawMatches(list: DrawList, pl: ParagraphLayout, line: Int, base: Int, ls: Int, le: Int, lineTop: Float, lineHeight: Float) {
        if (matches.isEmpty()) return
        // The first match that could end on this line, found by halving rather than walking.
        var lo = 0
        var hi = matchCount
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (matches[2 * mid + 1] <= base + ls) lo = mid + 1 else hi = mid
        }
        var m = lo
        while (m < matchCount && matches[2 * m] < base + le) {
            val spans = pl.spans(line, maxOf(matches[2 * m] - base, ls), minOf(matches[2 * m + 1] - base, le))
            val color = if (m == currentMatch) style.findCurrentColor else style.findColor
            val edge = style.findEdge
            var k = 0
            while (k < spans.size) {
                val x = paintX + spans[k]
                val w = spans[k + 1] - spans[k]
                list.rect(x, lineTop, w, lineHeight, style.findEdgeColor)
                list.rect(x + edge, lineTop + edge, w - 2 * edge, lineHeight - 2 * edge, color)
                k += 2
            }
            m++
        }
    }

    private fun renderParagraph(list: DrawList, p: Int, pl: ParagraphLayout, top: Float) {
        val sel = selection
        val base = document.paragraphStart(p)
        val runs = pl.runs
        val pstyle = document.paragraphStyle(p)
        // A ticked checklist item is struck through and faded, as in Samsung Notes.
        val done = pstyle.list == ListType.CHECKBOX && pstyle.checked
        paintX = originX + layout.paragraphLeft(p)
        drawBlock(list, p, pstyle, pl, top)
        if (pstyle.role == Role.DIVIDER) return
        if (pl.objectHeight > 0f) {
            drawPicture(list, pstyle.picture, pl, top, base)
            return
        }
        drawListMarker(list, p, pstyle, paintX + markerLeft(pl), top + pl.baseline(0), runs.sizes[0], pl.rtl)

        var g = 0
        for (line in 0 until pl.lineCount) {
            val lineTop = top + pl.lineTop(line)
            val lineHeight = pl.lineHeight(line)
            val baselineY = lineTop + pl.baseline(line)
            val ls = pl.lineStart(line)
            val le = pl.lineEnd(line)
            val lastLine = line == pl.lineCount - 1
            // Skip this line's glyphs if it is off screen, but keep g in step.
            if (lineTop + lineHeight < 0 || lineTop > viewHeight) {
                while (g < pl.glyphCount && pl.clusters[g] < le) g++
                continue
            }

            // Highlight first, behind the selection and the text: the full line box with square
            // corners, as Samsung's background colour span draws.
            forEachRun(pl, line) { r, x0, x1 ->
                if (runs.highlights[r] != 0) list.rect(x0, lineTop, x1 - x0, lineHeight, runs.highlights[r])
            }

            // Inline code on a chip of its own, as code in a sentence is shown on the web; a code
            // block, all code, has its panel instead.
            if (pstyle.role != Role.CODE_BLOCK && style.inlineCodeBackground != 0) forEachRun(pl, line) { r, x0, x1 ->
                if (runs.has(r, CharStyle.CODE)) {
                    val size = runs.sizes[r]
                    val pad = size * 0.12f
                    val chipTop = baselineY - size * 0.95f
                    val chipBottom = baselineY + size * 0.32f
                    path.reset()
                    roundRectInto(x0 - pad, chipTop, x1 - x0 + 2 * pad, chipBottom - chipTop, minOf(style.inlineCodeRadius, size * 0.3f), clockwise = true)
                    list.path(style.inlineCodeBackground, path)
                }
            }

            drawMatches(list, pl, line, base, ls, le, lineTop, lineHeight)

            if (!sel.isCollapsed) {
                val s = sel.start - base
                val e = sel.end - base
                val lineLimit = if (lastLine) le + 1 else le
                if (e > ls && s < lineLimit) {
                    val spans = pl.spans(line, maxOf(s, ls), minOf(e, le))
                    var k = 0
                    while (k < spans.size) {
                        list.rect(paintX + spans[k], lineTop, spans[k + 1] - spans[k], lineHeight, style.selectionColor)
                        k += 2
                    }
                    // A selection running on past the paragraph shows a little of its end, on
                    // the side the paragraph ends on.
                    if (lastLine && e > le) {
                        val w = style.textSize * 0.3f
                        val x = if (pl.rtl) pl.lineLeft(line) - w else pl.lineRight(line)
                        list.rect(paintX + x, lineTop, w, lineHeight, style.selectionColor)
                    }
                }
            }

            // Consecutive glyphs sharing a font, size and colour become one draw command.
            var runFont = -1
            var runSize = 0f
            var runColor = 0
            var n = 0
            var r = runs.runAt(ls)
            while (g < pl.glyphCount && pl.clusters[g] < le) {
                if (pl.clusters[g] >= ls) {
                    while (r + 1 < runs.count && runs.start(r + 1) <= pl.clusters[g]) r++
                    val size = runs.sizes[r]
                    val color = textColor(runs, r, done)
                    if (n > 0 && (pl.fontIds[g] != runFont || size != runSize || color != runColor)) {
                        list.glyphs(runFont, runSize, runColor, n, glyphIds, glyphXs, glyphYs)
                        n = 0
                    }
                    runFont = pl.fontIds[g]
                    runSize = size
                    runColor = color
                    if (n == glyphIds.size) growGlyphScratch()
                    glyphIds[n] = pl.glyphs[g]
                    glyphXs[n] = paintX + pl.glyphX(g, line)
                    glyphYs[n] = top + pl.glyphBaselineY(g, line)
                    n++
                }
                g++
            }
            if (n > 0) list.glyphs(runFont, runSize, runColor, n, glyphIds, glyphXs, glyphYs)

            // Underline and strikethrough, one stroke per styled stretch, sized to its text.
            forEachRun(pl, line) { i, x0, x1 ->
                val size = runs.sizes[i]
                val color = textColor(runs, i, done)
                if (runs.has(i, CharStyle.UNDERLINE)) {
                    list.rect(x0, baselineY + size * unitMetrics.underlineOffset, x1 - x0, maxOf(1f, size * unitMetrics.underlineThickness), color)
                }
                if (runs.has(i, CharStyle.STRIKETHROUGH) || done) {
                    list.rect(x0, baselineY + size * unitMetrics.strikeoutOffset, x1 - x0, maxOf(1f, size * unitMetrics.strikeoutThickness), color)
                }
            }

            if (composingStart >= 0) {
                val s = composingStart - base
                val e = composingEnd - base
                if (e > ls && s < le) {
                    val size = pl.sizeAtCaret(minOf(e, le))
                    val y = baselineY + size * unitMetrics.descent * 0.35f
                    val spans = pl.spans(line, maxOf(s, ls), minOf(e, le))
                    var k = 0
                    while (k < spans.size) {
                        list.rect(paintX + spans[k], y, spans[k + 1] - spans[k], style.composingUnderline, style.textColor)
                        k += 2
                    }
                }
            }
        }
    }

    /**
     * Calls [block] with each style run's part of [line] and where it starts and ends on
     * screen: once per stretch, as a run that changes direction mid-line is drawn in pieces.
     */
    private inline fun forEachRun(pl: ParagraphLayout, line: Int, block: (run: Int, x0: Float, x1: Float) -> Unit) {
        val ls = pl.lineStart(line)
        val le = pl.lineEnd(line)
        var r = pl.runs.runAt(ls)
        while (r < pl.runs.count && pl.runs.start(r) < le) {
            val a = maxOf(pl.runs.start(r), ls)
            val b = minOf(pl.runs.end(r), le)
            if (a < b) {
                val spans = pl.spans(line, a, b)
                var k = 0
                while (k < spans.size) {
                    block(r, paintX + spans[k], paintX + spans[k + 1])
                    k += 2
                }
            }
            r++
        }
    }

    /** A run's colour: one picked by hand, else its syntax's in code, else the text colour. */
    private fun textColor(runs: StyleRuns, r: Int, done: Boolean): Int {
        val color = when {
            runs.colors[r] != 0 -> runs.colors[r]
            else -> runs.token(r)?.let { style.codeColors[it.ordinal] } ?: style.textColor
        }
        if (!done) return color
        val alpha = ((color ushr 24) * style.checkedTextAlpha).toInt()
        return (alpha shl 24) or (color and 0xFFFFFF)
    }

    private fun drawPlaceholder(list: DrawList) {
        val text = placeholder ?: return
        if (document.length != 0 || text.isEmpty()) return
        val pl = layout.paragraph(0)
        val size = pl.runs.sizes[0]
        val run = layout.markerRun(text, size)
        var x = originX + if (pl.rtl) pl.lineRight(0) - run.advances.sum() else pl.lineLeft(0)
        val y = originY + pl.baseline(0)
        for (i in 0 until run.count) {
            if (i == glyphIds.size) growGlyphScratch()
            glyphIds[i] = run.glyphs[i]
            glyphXs[i] = x + run.offsetsX[i]
            glyphYs[i] = y + run.offsetsY[i]
            x += run.advances[i]
        }
        val faint = ((style.textColor ushr 24) * 0.3f).toInt() shl 24 or (style.textColor and 0xFFFFFF)
        if (run.count > 0) list.glyphs(run.fontIds[0], size, faint, run.count, glyphIds, glyphXs, glyphYs)
    }

    /**
     * What a paragraph's role draws besides its text: the bar beside a quote, the panel behind
     * a code block (one panel for consecutive code paragraphs, rounded at its two ends), the
     * line of a divider.
     */
    private fun drawBlock(list: DrawList, p: Int, ps: ParagraphStyle, pl: ParagraphLayout, top: Float) {
        when (ps.role) {
            Role.QUOTE -> if (style.quoteBarWidth > 0f) {
                val x = if (pl.rtl) paintX + pl.rightEdge + style.quoteIndent - style.quoteBarWidth else paintX + pl.textLeft - style.quoteIndent
                list.rect(x, top, style.quoteBarWidth, pl.height, style.quoteBarColor)
            }
            Role.CODE_BLOCK -> {
                val left = paintX + pl.textLeft - style.codeIndent
                val right = originX + layout.width
                val roundTop = p == 0 || document.paragraphStyle(p - 1).role != Role.CODE_BLOCK
                val roundBottom = p + 1 >= document.paragraphCount || document.paragraphStyle(p + 1).role != Role.CODE_BLOCK
                val r = minOf(style.codeCornerRadius, pl.height / 2)
                // The panel stops short of the block's margin, which keeps it off the text around.
                val panelTop = Math.round(if (roundTop) top + style.codeMargin else top).toFloat()
                val panelBottom = Math.round(if (roundBottom) top + pl.height - style.codeMargin else top + pl.height).toFloat()
                path.reset()
                panelInto(left, panelTop, right, panelBottom, if (roundTop) r else 0f, if (roundBottom) r else 0f)
                list.path(style.codeBackground, path)
                if (roundTop && style.codePaddingTop > 0f) drawCodeLabel(list, p, pl, panelTop)
            }
            Role.CALLOUT -> {
                // One panel for each callout, the whole width of the note, rounded at its two
                // ends and held off the text around by its margin.
                val start = layout.isPanelStart(p)
                val end = layout.isPanelEnd(p)
                // On whole pixels, so the slices each line draws meet without a seam.
                val panelTop = Math.round(if (start) top + style.calloutMargin else top).toFloat()
                val panelBottom = Math.round(if (end) top + pl.height - style.calloutMargin else top + pl.height).toFloat()
                val r = minOf(style.calloutCornerRadius, (panelBottom - panelTop) / 2)
                path.reset()
                panelInto(originX, panelTop, originX + layout.width, panelBottom, if (start) r else 0f, if (end) r else 0f)
                list.path(style.calloutColors[ps.callout.ordinal], path)
            }
            Role.DIVIDER -> {
                val y = top + pl.height / 2 - style.dividerThickness / 2
                list.rect(originX + pl.textLeft, y, layout.width - pl.textLeft, style.dividerThickness, style.dividerColor)
            }
            else -> ps.cell?.let { drawCell(list, p, it, top) }
        }
    }

    /**
     * A table cell's box: its shade when it is in the header row, and its lines. Each cell
     * draws the line on its right and below it, and the first row and column the table's top
     * and left edges, so no line is drawn twice, which would show where see-through ones cross.
     */
    private fun drawCell(list: DrawList, p: Int, cell: TableCell, top: Float) {
        val x = Math.round(paintX).toFloat()
        val y = Math.round(top).toFloat()
        val w = layout.boxWidth(p)
        val h = Math.round(top + layout.boxHeight(p)).toFloat() - y
        val t = style.tableLineWidth
        if (cell.header && cell.row == 0 && style.tableHeaderColor != 0) list.rect(x, y, w, h, style.tableHeaderColor)
        if (t <= 0f) return
        val color = style.tableLineColor
        val firstRow = cell.row == 0
        val below = if (firstRow) t else 0f
        if (firstRow) list.rect(x, y, w + t, t, color)
        if (cell.column == 0) list.rect(x, y + below, t, h - below, color)
        list.rect(x + w, y + below, t, h - below, color)
        list.rect(x, y + h, w + t, t, color)
    }

    /**
     * The block's language at its top, small and quiet, with a chevron that says it can be
     * changed there: the way code shown on the web names its language.
     */
    private fun drawCodeLabel(list: DrawList, p: Int, pl: ParagraphLayout, top: Float) {
        val size = style.codeLabelSize
        val box = codeLabelBox(p, pl, top)
        val run = layout.markerRun(document.paragraphStyle(p).language.title, size)
        val baseline = box[1] + size * 1.15f
        var x = box[0]
        for (i in 0 until run.count) {
            if (i == glyphIds.size) growGlyphScratch()
            glyphIds[i] = run.glyphs[i]
            glyphXs[i] = x + run.offsetsX[i]
            glyphYs[i] = baseline + run.offsetsY[i]
            x += run.advances[i]
        }
        if (run.count > 0) list.glyphs(run.fontIds[0], size, style.codeLabelColor, run.count, glyphIds, glyphXs, glyphYs)
        // A small chevron pointing down, drawn as two strokes.
        val w = size * 0.5f
        val cx = x + size * 0.25f + w / 2f
        val cy = baseline - size * 0.32f
        val half = maxOf(0.6f, size * 0.06f)
        path.reset()
        segment(cx - w / 2f, cy - w / 4f, cx, cy + w / 4f, half)
        segment(cx, cy + w / 4f, cx + w / 2f, cy - w / 4f, half)
        list.path(style.codeLabelColor, path)
    }

    /**
     * A bullet, dash, number or checkbox in the hanging space before a list paragraph's first
     * line; it travels with the line when that is centred or right-aligned, and scales with the
     * size of the text it marks. Each level of nesting has its own marker, as on the Mac:
     * ● ○ ▪ for bullets, 1. a. i. for numbers. Numbers count the items of the same list just
     * above, so they renumber themselves as items come and go without being stored.
     */
    /** Where a list paragraph's marker box starts: before its first line, on the side it begins. */
    private fun markerLeft(pl: ParagraphLayout): Float =
        if (pl.rtl) pl.lineRight(0) else pl.lineLeft(0) - style.listMarkerWidth

    private fun drawListMarker(list: DrawList, p: Int, ps: ParagraphStyle, markerLeft: Float, baselineY: Float, size: Float, rtl: Boolean) {
        val scale = size / style.textSize
        // Centred on the x-height, where the strikethrough also sits.
        val cy = baselineY + size * (unitMetrics.strikeoutOffset + unitMetrics.strikeoutThickness / 2)
        val cx = markerLeft + style.listMarkerWidth / 2
        val level = (ps.depth - 1).coerceAtLeast(0) % 3
        when (ps.list) {
            ListType.NONE -> {}
            ListType.BULLET -> {
                val r = style.bulletRadius * scale
                path.reset()
                when (level) {
                    0 -> path.circle(cx, cy, r)
                    1 -> {
                        // A ring: the outer circle and an inner one wound the other way.
                        path.circle(cx, cy, r)
                        circleReversed(cx, cy, r * 0.55f)
                    }
                    else -> square(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f)
                }
                list.path(style.textColor, path)
            }
            ListType.DASH -> {
                val w = style.bulletRadius * scale * 2.6f
                val h = maxOf(1f, size * unitMetrics.strikeoutThickness)
                list.rect(cx - w / 2, cy - h / 2, w, h, style.textColor)
            }
            ListType.NUMBER -> {
                val label = numberLabel(itemNumber(p, ps), level) + "."
                val run = layout.markerRun(label, size)
                var width = 0f
                for (i in 0 until run.count) width += run.advances[i]
                // Against the text, like a numbered list in any word processor: on its left, or on
                // its right in a paragraph that runs right to left.
                var x = if (rtl) markerLeft + style.listMarkerGap else markerLeft + style.listMarkerWidth - style.listMarkerGap - width
                for (i in 0 until run.count) {
                    if (i == glyphIds.size) growGlyphScratch()
                    glyphIds[i] = run.glyphs[i]
                    glyphXs[i] = x + run.offsetsX[i]
                    glyphYs[i] = baselineY + run.offsetsY[i]
                    x += run.advances[i]
                }
                if (run.count > 0) list.glyphs(run.fontIds[0], size, style.textColor, run.count, glyphIds, glyphXs, glyphYs)
            }
            ListType.CHECKBOX -> drawCheckbox(list, checkboxBox(markerLeft, baselineY, size), ps.checked)
        }
    }

    /**
     * The number of a numbered item: its list's start, plus the items of the same list just
     * above at the same level. Deeper items in between do not interrupt it; anything else does.
     */
    internal fun itemNumber(p: Int, ps: ParagraphStyle): Int {
        val depth = ps.depth
        val own = ps.lists.last()
        var number = maxOf(own.start, 1)
        var q = p - 1
        while (q >= 0) {
            val qs = document.paragraphStyle(q)
            if (qs.depth < depth || qs.lists[depth - 1].id != own.id) break
            if (qs.depth == depth) number++
            q--
        }
        return number
    }

    private fun numberLabel(n: Int, level: Int): String = when (level) {
        1 -> letters(n)
        2 -> roman(n)
        else -> n.toString()
    }

    private fun letters(n: Int): String {
        var k = n
        val out = StringBuilder()
        while (k > 0) {
            k--
            out.insert(0, 'a' + k % 26)
            k /= 26
        }
        return out.toString()
    }

    private fun roman(n: Int): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        var k = n
        val out = StringBuilder()
        for (i in values.indices) while (k >= values[i]) { out.append(symbols[i]); k -= values[i] }
        return out.toString()
    }

    /** A rectangle whose top corners are rounded by [top] and bottom corners by [bottom]. */
    private fun panelInto(left: Float, topY: Float, right: Float, bottomY: Float, top: Float, bottom: Float) {
        path.moveTo(left + top, topY)
        path.lineTo(right - top, topY); path.quadTo(right, topY, right, topY + top)
        path.lineTo(right, bottomY - bottom); path.quadTo(right, bottomY, right - bottom, bottomY)
        path.lineTo(left + bottom, bottomY); path.quadTo(left, bottomY, left, bottomY - bottom)
        path.lineTo(left, topY + top); path.quadTo(left, topY, left + top, topY)
        path.close()
    }

    /** A circle wound the other way from [PathBuilder.circle], to cut a hole in one. */
    private fun circleReversed(cx: Float, cy: Float, r: Float) {
        val step = Math.PI / 4
        val control = r / Math.cos(step / 2).toFloat()
        path.moveTo(cx + r, cy)
        for (i in 1..8) {
            val mid = -step * (i - 0.5)
            val end = -step * i
            path.quadTo(
                cx + control * Math.cos(mid).toFloat(), cy + control * Math.sin(mid).toFloat(),
                cx + r * Math.cos(end).toFloat(), cy + r * Math.sin(end).toFloat(),
            )
        }
        path.close()
    }

    /** The checkbox square for a list item, as left, top, side. */
    private fun checkboxBox(markerLeft: Float, baselineY: Float, size: Float): FloatArray {
        val side = style.checkboxSize * size / style.textSize
        // Centred on the x-height, like the bullet.
        val cy = baselineY + size * (unitMetrics.strikeoutOffset + unitMetrics.strikeoutThickness / 2)
        val left = markerLeft + (style.listMarkerWidth - side) / 2
        return floatArrayOf(left, cy - side / 2, side)
    }

    private fun drawCheckbox(list: DrawList, box: FloatArray, checked: Boolean) {
        val (left, top, side) = Triple(box[0], box[1], box[2])
        val scale = side / style.checkboxSize
        val radius = style.checkboxRadius * scale
        val stroke = style.checkboxStroke * scale
        // An outlined square: the outer rounded square, and the inner one wound the other way
        // so it cuts a hole. The stroke is centred on the square's edge, as in a vector icon.
        val half = stroke / 2
        path.reset()
        roundRectInto(left - half, top - half, side + stroke, side + stroke, radius + half, clockwise = true)
        roundRectInto(left + half, top + half, side - stroke, side - stroke, maxOf(0f, radius - half), clockwise = false)
        list.path(style.checkboxColor, path)
        if (checked) {
            path.reset()
            tick(left, top, side, stroke)
            list.path(style.checkboxColor, path)
        }
    }

    private fun roundRect(left: Float, top: Float, w: Float, h: Float, r: Float) {
        path.reset()
        roundRectInto(left, top, w, h, r, clockwise = true)
    }

    private fun roundRectInto(left: Float, top: Float, w: Float, h: Float, r: Float, clockwise: Boolean) {
        val right = left + w
        val bottom = top + h
        if (clockwise) {
            path.moveTo(left + r, top)
            path.lineTo(right - r, top); path.quadTo(right, top, right, top + r)
            path.lineTo(right, bottom - r); path.quadTo(right, bottom, right - r, bottom)
            path.lineTo(left + r, bottom); path.quadTo(left, bottom, left, bottom - r)
            path.lineTo(left, top + r); path.quadTo(left, top, left + r, top)
        } else {
            path.moveTo(left + r, top)
            path.quadTo(left, top, left, top + r); path.lineTo(left, bottom - r)
            path.quadTo(left, bottom, left + r, bottom); path.lineTo(right - r, bottom)
            path.quadTo(right, bottom, right, bottom - r); path.lineTo(right, top + r)
            path.quadTo(right, top, right - r, top); path.lineTo(left + r, top)
        }
        path.close()
    }

    /** A check mark through the same points as Samsung's, as fractions of the box. */
    private fun tick(left: Float, top: Float, side: Float, thickness: Float) {
        fun x(f: Float) = left + side * f
        fun y(f: Float) = top + side * f
        val h = thickness / 2
        // Two thick segments, each a quadrilateral, wound the same way so their overlap stays solid.
        segment(x(0.234f), y(0.450f), x(0.430f), y(0.647f), h)
        segment(x(0.430f), y(0.647f), x(0.766f), y(0.346f), h)
    }

    private fun segment(x0: Float, y0: Float, x1: Float, y1: Float, half: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val nx = -dy / len * half
        val ny = dx / len * half
        val ex = dx / len * half
        val ey = dy / len * half
        path.moveTo(x0 - ex + nx, y0 - ey + ny)
        path.lineTo(x1 + ex + nx, y1 + ey + ny)
        path.lineTo(x1 + ex - nx, y1 + ey - ny)
        path.lineTo(x0 - ex - nx, y0 - ey - ny)
        path.close()
    }

    private fun growGlyphScratch() {
        glyphIds = glyphIds.copyOf(glyphIds.size * 2)
        glyphXs = glyphXs.copyOf(glyphXs.size * 2)
        glyphYs = glyphYs.copyOf(glyphYs.size * 2)
    }

    /**
     * A teardrop: a circle plus the square corner that points at the text. Both parts are wound
     * clockwise, so where they overlap the shape stays solid.
     */
    private fun drawHandle(list: DrawList, h: HandleSpot, side: Int) {
        val r = style.handleRadius
        path.reset()
        path.circle(h.centerX, h.centerY, r)
        when (side) {
            -1 -> square(h.centerX, h.tipY, h.tipX, h.centerY)
            1 -> square(h.tipX, h.tipY, h.centerX, h.centerY)
            else -> {
                val d = r / SQRT2
                path.moveTo(h.tipX, h.tipY)
                path.lineTo(h.tipX + d, h.tipY + d)
                path.lineTo(h.centerX, h.centerY)
                path.lineTo(h.tipX - d, h.tipY + d)
                path.close()
            }
        }
        list.path(style.handleColor, path)
    }

    private fun square(left: Float, top: Float, right: Float, bottom: Float) {
        path.moveTo(left, top)
        path.lineTo(right, top)
        path.lineTo(right, bottom)
        path.lineTo(left, bottom)
        path.close()
    }

    private companion object {
        val SQRT2 = sqrt(2f)
        // Pixels per millisecond.
        const val MIN_FLING_VELOCITY = 0.3f
        const val STOP_VELOCITY = 0.02f
        // How quickly a fling slows: velocity falls to a third about every 325 ms.
        const val FLING_DECAY_MS = 325f
    }
}

/** Finger speed over the last few samples, in pixels per millisecond. */
internal class VelocityTracker {
    private val times = LongArray(SIZE)
    private val ys = FloatArray(SIZE)
    private var count = 0
    private var next = 0

    fun reset() {
        count = 0
        next = 0
    }

    fun add(time: Long, y: Float) {
        times[next] = time
        ys[next] = y
        next = (next + 1) % SIZE
        if (count < SIZE) count++
    }

    fun velocity(): Float {
        if (count < 2) return 0f
        val newest = (next - 1 + SIZE) % SIZE
        var oldest = newest
        // Only the last 100 ms matter: a finger that paused before lifting should not fling.
        for (k in 1 until count) {
            val i = (newest - k + SIZE) % SIZE
            if (times[newest] - times[i] > WINDOW_MS) break
            oldest = i
        }
        val dt = (times[newest] - times[oldest]).toFloat()
        return if (dt <= 0f) 0f else (ys[newest] - ys[oldest]) / dt
    }

    private companion object {
        const val SIZE = 20
        const val WINDOW_MS = 100L
    }
}

/**
 * Text as Find compares it: lower case, and a letter with an accent as the letter without, so
 * "cafe" finds "Café". One character for one, so an offset found in the folded text is the
 * same offset in the note.
 */
internal fun fold(text: CharSequence): String {
    val out = CharArray(text.length)
    for (i in text.indices) {
        val c = text[i]
        val lower = Character.toLowerCase(c)
        // Below U+00C0 nothing has an accent to take off.
        out[i] = if (lower.code < 0xC0) lower else {
            val parts = java.text.Normalizer.normalize(lower.toString(), java.text.Normalizer.Form.NFD)
            if (parts.length > 1 && Character.getType(parts[1]) == Character.NON_SPACING_MARK.toInt()) parts[0] else lower
        }
    }
    return String(out)
}
