package com.syncednotes.engine.editor

import com.syncednotes.engine.layout.DocumentLayout
import com.syncednotes.engine.render.DrawList
import com.syncednotes.engine.render.FontFamily
import com.syncednotes.engine.render.PathBuilder
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.Boundaries
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.TextDocument
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
    val document = TextDocument()
    private val layout = DocumentLayout(document, fonts, style.textSize, style.lineHeight, style.indentWidth, style.listMarkerWidth)
    private val history = History(document.snapshot())
    private val metrics = fonts.regular.primary.metrics(style.textSize)

    /**
     * Styles chosen with a bare caret, waiting for the next typed text (Samsung's zero-length
     * spans at the cursor). Forgotten as soon as the caret is moved.
     */
    private val typingStyle = HashMap<CharStyle, Boolean>()

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

    // --- Editing core ---------------------------------------------------------------------

    /**
     * Every change to the text goes through here, so history, the composing region, layout and
     * the input method always agree about what happened.
     */
    private fun edit(start: Int, end: Int, text: CharSequence, after: Selection, now: Long, mergeable: Boolean, fromInput: Boolean) {
        val before = selection
        // Typing hides the insertion handle, as on Samsung and stock Android; it comes back
        // with the next tap.
        insertionHandleUntil = 0
        val change = document.replace(start, end, text)
        if (text.isNotEmpty() && typingStyle.isNotEmpty()) {
            for ((style, on) in typingStyle) document.setStyle(style, on, start, start + text.length)
            // From here on, typing extends the styled text by itself.
            typingStyle.clear()
        }
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
        val e = history.undo() ?: return false
        clearComposing()
        typingStyle.clear()
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
        val e = history.redo() ?: return false
        clearComposing()
        typingStyle.clear()
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
        if (text.length == 1 && text[0] == '\n' && leaveEmptyListItem(now)) return
        clearComposing()
        val s = selection.start
        edit(s, selection.end, text, Selection(s + text.length), now, mergeable = true, fromInput = true)
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
        if (composingStart < 0 && text.length == 1 && text[0] == '\n' && leaveEmptyListItem(now)) return@batch
        val (s, e) = replacementRange()
        val cursor = cursorAfter(s, text, newCursorPosition)
        clearComposing()
        edit(s, e, text, Selection(cursor), now, mergeable = true, fromInput = true)
    }

    fun setComposingText(text: CharSequence, newCursorPosition: Int, now: Long) = batch {
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

    fun selectedText(): String? = if (selection.isCollapsed) null else document.substring(selection.start, selection.end)

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
        return floatArrayOf(originX + r.x, baseline - metrics.ascent, baseline, baseline + metrics.descent)
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
        /** Null when the paragraphs disagree. */
        val alignment: Alignment?,
        val canIndent: Boolean,
        val canOutdent: Boolean,
        val canUndo: Boolean,
        val canRedo: Boolean,
    ) {
        fun has(style: CharStyle): Boolean = when (style) {
            CharStyle.BOLD -> bold
            CharStyle.ITALIC -> italic
            CharStyle.UNDERLINE -> underline
            CharStyle.STRIKETHROUGH -> strikethrough
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
        val alignment = if (styles.all { it.alignment == first.alignment }) first.alignment else null
        // Indenting a right-aligned paragraph means nothing in a left-to-right note, so Samsung
        // disables both buttons there. Mixed levels can always move.
        val (canIndent, canOutdent) = when {
            styles.any { it.alignment == Alignment.RIGHT } -> false to false
            styles.any { it.indent != first.indent } -> true to true
            else -> (first.indent < ParagraphStyle.MAX_INDENT) to (first.indent > 0)
        }
        return FormatState(
            styleOn(CharStyle.BOLD), styleOn(CharStyle.ITALIC), styleOn(CharStyle.UNDERLINE), styleOn(CharStyle.STRIKETHROUGH),
            list, alignment, canIndent, canOutdent, history.canUndo, history.canRedo,
        )
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

    /** Bullets or numbering for the selected paragraphs; choosing the current kind removes it. */
    fun toggleList(type: ListType, now: Long) {
        val range = selectedParagraphs()
        val allAlready = range.all { document.paragraphStyle(it).list == type }
        val target = if (allAlready) ListType.NONE else type
        formatEdit(now) { for (p in range) document.setParagraphStyle(p, document.paragraphStyle(p).copy(list = target)) }
    }

    fun setAlignment(alignment: Alignment, now: Long) {
        formatEdit(now) { for (p in selectedParagraphs()) document.setParagraphStyle(p, document.paragraphStyle(p).copy(alignment = alignment)) }
    }

    fun indent(now: Long) = changeIndent(+1, now)

    fun outdent(now: Long) = changeIndent(-1, now)

    private fun changeIndent(by: Int, now: Long) {
        formatEdit(now) {
            for (p in selectedParagraphs()) {
                val s = document.paragraphStyle(p)
                document.setParagraphStyle(p, s.copy(indent = (s.indent + by).coerceIn(0, ParagraphStyle.MAX_INDENT)))
            }
        }
    }

    /** Paragraphs the selection touches; a selection ending right at a paragraph's start leaves that one out. */
    private fun selectedParagraphs(): IntRange {
        val sel = selection
        val last = if (sel.isCollapsed) sel.end else maxOf(sel.start, sel.end - 1)
        return document.paragraphAt(sel.start)..document.paragraphAt(last)
    }

    /** A formatting change as one undo step of its own. */
    private inline fun formatEdit(now: Long, change: () -> Unit) {
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
     * Enter on an empty bullet or numbered line ends the list instead of adding a line, and on
     * an empty indented line steps back out, as in Samsung Notes.
     */
    private fun leaveEmptyListItem(now: Long): Boolean {
        val sel = selection
        if (!sel.isCollapsed) return false
        val p = document.paragraphAt(sel.start)
        if (document.paragraphStart(p) != document.paragraphEnd(p)) return false
        val s = document.paragraphStyle(p)
        val next = when {
            s.list != ListType.NONE -> s.copy(list = ListType.NONE)
            s.indent > 0 -> s.copy(indent = s.indent - 1)
            else -> return false
        }
        formatEdit(now) { document.setParagraphStyle(p, next) }
        return true
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
        val s = document.paragraphStyle(p)
        val next = when {
            s.list != ListType.NONE -> s.copy(list = ListType.NONE)
            s.indent > 0 -> s.copy(indent = s.indent - 1)
            else -> return false
        }
        formatEdit(now) { document.setParagraphStyle(p, next) }
        return true
    }

    // --- Keys -----------------------------------------------------------------------------

    fun onKey(key: Key, shift: Boolean, word: Boolean, now: Long): Boolean {
        val text = document.text
        val sel = selection
        when (key) {
            Key.BACKSPACE -> {
                if (!sel.isCollapsed) deleteSelection(now)
                else if (removeParagraphMarkAtCaret(now)) {}
                else if (sel.start > 0) {
                    val from = if (word) Boundaries.previousWordStart(text, sel.start) else Boundaries.previousCluster(text, sel.start)
                    clearComposing()
                    edit(from, sel.start, "", Selection(from), now, mergeable = true, fromInput = true)
                }
            }
            Key.DELETE -> {
                if (!sel.isCollapsed) deleteSelection(now)
                else if (sel.end < document.length) {
                    val to = if (word) Boundaries.nextWordEnd(text, sel.end) else Boundaries.nextCluster(text, sel.end)
                    clearComposing()
                    edit(sel.end, to, "", Selection(sel.end), now, mergeable = true, fromInput = true)
                }
            }
            Key.ENTER -> type("\n", now)
            Key.TAB -> type("\t", now)
            Key.UNDO -> undo(now)
            Key.REDO -> redo(now)
            Key.SELECT_ALL -> select(Selection(0, document.length), now)
            Key.LEFT -> move(shift, now) {
                if (!shift && !sel.isCollapsed) sel.start
                else if (word) Boundaries.previousWordStart(text, sel.focus)
                else Boundaries.previousCluster(text, sel.focus)
            }
            Key.RIGHT -> move(shift, now) {
                if (!shift && !sel.isCollapsed) sel.end
                else if (word) Boundaries.nextWordEnd(text, sel.focus)
                else Boundaries.nextCluster(text, sel.focus)
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

    private fun moveVertically(key: Key, shift: Boolean, now: Long) {
        val r = layout.caretRect(selection.focus, selection.upstream)
        val x = if (goalX.isNaN()) r.x else goalX
        val distance = when (key) {
            Key.UP -> -style.lineHeight
            Key.DOWN -> style.lineHeight
            Key.PAGE_UP -> -(viewHeight - insetTop - insetBottom)
            else -> viewHeight - insetTop - insetBottom
        }
        val y = (r.top + r.bottom) / 2 + distance
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
        val tipY = originY + r.baseline + metrics.descent
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

    // --- Painting -------------------------------------------------------------------------

    private var glyphIds = IntArray(256)
    private var glyphXs = FloatArray(256)
    private var glyphYs = FloatArray(256)
    private val path = PathBuilder()

    fun render(list: DrawList, now: Long) {
        list.reset()
        list.rect(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat(), style.background)
        if (viewWidth == 0 || viewHeight == 0) return
        val sel = selection
        val newlineWidth = style.textSize * 0.3f
        val first = layout.paragraphAtY(scrollY - style.paddingTop - insetTop)
        for (p in first until document.paragraphCount) {
            val top = originY + layout.paragraphTop(p)
            if (top > viewHeight) break
            val pl = layout.paragraph(p)
            if (top + pl.height < 0) continue
            val base = document.paragraphStart(p)
            drawListMarker(list, p, originX + pl.lineLeft(0) - style.listMarkerWidth, top + pl.baseline)
            var g = 0
            for (line in 0 until pl.lineCount) {
                val lineTop = top + pl.lineTop(line)
                val ls = pl.lineStart(line)
                val le = pl.lineEnd(line)
                val lastLine = line == pl.lineCount - 1
                // Skip this line's glyphs if it is off screen, but keep g in step.
                if (lineTop + pl.lineHeight < 0 || lineTop > viewHeight) {
                    while (g < pl.glyphCount && pl.clusters[g] < le) g++
                    continue
                }

                if (!sel.isCollapsed) {
                    val s = sel.start - base
                    val e = sel.end - base
                    val lineLimit = if (lastLine) le + 1 else le
                    if (e > ls && s < lineLimit) {
                        val x0 = pl.xOf(maxOf(s, ls), line)
                        var x1 = pl.xOf(minOf(e, le), line)
                        if (lastLine && e > le) x1 += newlineWidth
                        list.rect(originX + x0, lineTop, x1 - x0, pl.lineHeight, style.selectionColor)
                    }
                }

                if (composingStart >= 0) {
                    val s = composingStart - base
                    val e = composingEnd - base
                    if (e > ls && s < le) {
                        val x0 = pl.xOf(maxOf(s, ls), line)
                        val x1 = pl.xOf(minOf(e, le), line)
                        val y = lineTop + pl.baseline + metrics.descent * 0.35f
                        list.rect(originX + x0, y, x1 - x0, style.composingUnderline, style.textColor)
                    }
                }

                // Runs of consecutive glyphs from the same font become one draw command.
                var runFont = -1
                var n = 0
                while (g < pl.glyphCount && pl.clusters[g] < le) {
                    if (pl.clusters[g] >= ls) {
                        if (pl.fontIds[g] != runFont && n > 0) {
                            list.glyphs(runFont, style.textSize, style.textColor, n, glyphIds, glyphXs, glyphYs)
                            n = 0
                        }
                        runFont = pl.fontIds[g]
                        if (n == glyphIds.size) growGlyphScratch()
                        glyphIds[n] = pl.glyphs[g]
                        glyphXs[n] = originX + pl.glyphX(g, line)
                        glyphYs[n] = top + pl.glyphBaselineY(g, line)
                        n++
                    }
                    g++
                }
                if (n > 0) list.glyphs(runFont, style.textSize, style.textColor, n, glyphIds, glyphXs, glyphYs)

                // Underline and strikethrough, one stroke per styled stretch of the line.
                for (r in 0 until pl.styleRunCount) {
                    val a = maxOf(pl.styleRunStart(r), ls)
                    val b = minOf(pl.styleRunEnd(r), le)
                    if (a >= b) continue
                    val x0 = originX + pl.xOf(a, line)
                    val x1 = originX + pl.xOf(b, line)
                    val baselineY = lineTop + pl.baseline
                    if (pl.styleRunHas(r, CharStyle.UNDERLINE)) {
                        list.rect(x0, baselineY + metrics.underlineOffset, x1 - x0, maxOf(1f, metrics.underlineThickness), style.textColor)
                    }
                    if (pl.styleRunHas(r, CharStyle.STRIKETHROUGH)) {
                        list.rect(x0, baselineY + metrics.strikeoutOffset, x1 - x0, maxOf(1f, metrics.strikeoutThickness), style.textColor)
                    }
                }
            }
        }

        if (focused) {
            if (sel.isCollapsed) {
                val visible = ((now - caretEpoch) / style.blinkMs) % 2 == 0L
                if (visible) {
                    val r = layout.caretRect(sel.focus, sel.upstream)
                    val top = originY + r.baseline - metrics.ascent
                    val height = metrics.ascent + metrics.descent
                    list.rect(originX + r.x - style.caretWidth / 2, top, style.caretWidth, height, style.caretColor)
                }
                if (insertionHandleVisible(now)) drawHandle(list, handleAt(sel.focus, sel.upstream, 0), 0)
            } else {
                drawHandle(list, handleAt(sel.start, false, -1), -1)
                drawHandle(list, handleAt(sel.end, true, 1), 1)
            }
        }
    }

    /**
     * A bullet or a number in the hanging space before a list paragraph's first line, which
     * travels with the line when it is centred or right-aligned. Numbers
     * count the numbered paragraphs just above at the same indent, so they renumber themselves
     * as items come and go without being stored.
     */
    private fun drawListMarker(list: DrawList, p: Int, markerLeft: Float, baselineY: Float) {
        val ps = document.paragraphStyle(p)
        if (ps.list == ListType.NONE) return
        when (ps.list) {
            ListType.BULLET -> {
                // Centred on the x-height, where the strikethrough also sits.
                val cy = baselineY + metrics.strikeoutOffset + metrics.strikeoutThickness / 2
                path.reset()
                path.circle(markerLeft + style.listMarkerWidth / 2, cy, style.bulletRadius)
                list.path(style.textColor, path)
            }
            ListType.NUMBER -> {
                var number = 1
                var q = p - 1
                while (q >= 0) {
                    val qs = document.paragraphStyle(q)
                    if (qs.list != ListType.NUMBER || qs.indent < ps.indent) break
                    if (qs.indent == ps.indent) number++
                    q--
                }
                val run = layout.markerRun("$number.")
                var width = 0f
                for (i in 0 until run.count) width += run.advances[i]
                // Right-aligned against the text, like a numbered list in any word processor.
                var x = markerLeft + style.listMarkerWidth - style.listMarkerGap - width
                for (i in 0 until run.count) {
                    if (i == glyphIds.size) growGlyphScratch()
                    glyphIds[i] = run.glyphs[i]
                    glyphXs[i] = x + run.offsetsX[i]
                    glyphYs[i] = baselineY + run.offsetsY[i]
                    x += run.advances[i]
                }
                list.glyphs(run.fontIds.firstOrNull() ?: return, style.textSize, style.textColor, run.count, glyphIds, glyphXs, glyphYs)
            }
            ListType.NONE -> {}
        }
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
