package com.syncednotes.engine.paste

import com.syncednotes.engine.text.CalloutColor
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TextDocument
import com.syncednotes.engine.text.ValueStyle

/**
 * A note as Markdown, the form an AI agent reads and writes, line for line as [MarkdownReader]
 * reads it back: each paragraph a line, headings, lists, checklists, quotes, fenced code in its
 * language, callouts as GitHub's alerts (`> [!NOTE]`), dividers, bold, italic, strikethrough
 * and inline code.
 *
 * What Markdown has no way to say is left out: underline, text colours, highlights, sizes,
 * alignment, indents. A picture is shown as `[picture]`.
 */
object MarkdownWriter {
    fun write(document: TextDocument): String {
        val lines = ArrayList<String>()
        var p = 0
        while (p < document.paragraphCount) {
            val style = document.paragraphStyle(p)
            if (style.role == Role.CODE_BLOCK) {
                // One fence for the whole block, in its language.
                val language = when (style.language) {
                    CodeLanguage.NONE -> ""
                    CodeLanguage.C -> "c"
                    CodeLanguage.CPP -> "cpp"
                }
                val code = ArrayList<String>()
                while (p < document.paragraphCount && document.paragraphStyle(p).role == Role.CODE_BLOCK) {
                    code += document.substring(document.paragraphStart(p), document.paragraphEnd(p))
                    p++
                }
                val fence = "`".repeat(maxOf(3, (code.maxOfOrNull { longestRun(it, '`') } ?: 0) + 1))
                lines += fence + language
                lines += code
                lines += fence
                continue
            }
            if (style.role == Role.CALLOUT) {
                // A callout as GitHub and Obsidian write one: a quote whose first line names its
                // kind, here its colour; each of its lines after that, a line of the quote.
                lines += "> [!${calloutName(style.callout)}]"
                val first = p
                while (p < document.paragraphCount && (p == first || sameCallout(document, p - 1, p))) {
                    val inner = line(document, p, document.paragraphStyle(p).copy(role = Role.BODY))
                    lines += if (inner.isEmpty()) ">" else "> $inner"
                    p++
                }
                continue
            }
            lines += line(document, p, style)
            p++
        }
        return lines.joinToString("\n")
    }

    private fun sameCallout(document: TextDocument, a: Int, b: Int): Boolean {
        val x = document.paragraphStyle(a)
        val y = document.paragraphStyle(b)
        return x.role == Role.CALLOUT && y.role == Role.CALLOUT && x.callout == y.callout
    }

    /** GitHub's five kinds of alert for five of the colours, and gray by its own name. */
    fun calloutName(color: CalloutColor): String = when (color) {
        CalloutColor.BLUE -> "NOTE"
        CalloutColor.GREEN -> "TIP"
        CalloutColor.ORANGE -> "WARNING"
        CalloutColor.PURPLE -> "IMPORTANT"
        CalloutColor.PINK -> "CAUTION"
        CalloutColor.GRAY -> "GRAY"
    }

    private fun line(document: TextDocument, p: Int, style: ParagraphStyle): String {
        if (style.role == Role.DIVIDER) return "---"
        if (style.picture != 0) return "[picture]"
        val text = inline(document, document.paragraphStart(p), document.paragraphEnd(p))
        val nesting = "    ".repeat(maxOf(0, style.depth - 1))
        return when {
            style.role == Role.TITLE -> "# $text"
            style.role == Role.HEADING -> "## $text"
            style.role == Role.SUBHEADING -> "### $text"
            style.role == Role.QUOTE -> "> $text"
            style.list == ListType.CHECKBOX -> "    ".repeat(style.depth) + (if (style.checked) "- [x] " else "- [ ] ") + text
            style.list == ListType.NUMBER -> "$nesting${number(document, p, style)}. $text"
            style.list == ListType.BULLET || style.list == ListType.DASH -> "$nesting- $text"
            else -> escapeStart(text)
        }
    }

    /** The number a numbered item shows: its list's start, counted on through the items above. */
    private fun number(document: TextDocument, p: Int, style: ParagraphStyle): Int {
        val level = style.lists.last()
        var n = 0
        var q = p - 1
        while (q >= 0) {
            val above = document.paragraphStyle(q)
            if (above.depth < style.depth) break
            if (above.depth == style.depth) {
                if (above.lists.last().id != level.id) break
                n++
            }
            q--
        }
        return (if (level.start > 0) level.start else 1) + n
    }

    /**
     * A paragraph's text with its bold, italic, strikethrough and code written the Markdown
     * way. Markers open and close as a stack, so styles that overlap nest properly, and never
     * have a space just inside them, where Markdown would not read them.
     */
    private fun inline(document: TextDocument, start: Int, end: Int): String {
        val out = StringBuilder()
        val open = ArrayList<CharStyle>()
        fun markerOf(style: CharStyle) = when (style) {
            CharStyle.BOLD -> "**"
            // A star, since an underscore inside a word is not read as one.
            CharStyle.ITALIC -> "*"
            CharStyle.STRIKETHROUGH -> "~~"
            else -> ""
        }
        fun close(style: CharStyle) {
            // Spaces just before a closing marker go after it.
            var spaces = 0
            while (out.isNotEmpty() && out[out.length - 1] == ' ') { out.setLength(out.length - 1); spaces++ }
            out.append(markerOf(style))
            repeat(spaces) { out.append(' ') }
        }
        var i = start
        while (i < end) {
            val here = MARKED.filter { document.hasStyle(it, i) }
            val code = document.hasStyle(CharStyle.CODE, i)
            // Close back to the first style that stops here, then open what starts.
            val stop = open.indexOfFirst { it !in here }
            if (stop >= 0) {
                for (k in open.lastIndex downTo stop) close(open[k])
                while (open.size > stop) open.removeAt(open.lastIndex)
            }
            var j = i + 1
            while (j < end && MARKED.filter { document.hasStyle(it, j) } == here && document.hasStyle(CharStyle.CODE, j) == code) j++
            var segment = document.substring(i, j)
            val toOpen = here.filter { it !in open }
            if (toOpen.isNotEmpty()) {
                // Spaces just after an opening marker go before it.
                val lead = segment.takeWhile { it == ' ' }
                out.append(lead)
                segment = segment.substring(lead.length)
                for (s in toOpen) {
                    out.append(markerOf(s))
                    open += s
                }
            }
            if (code) {
                val ticks = "`".repeat(longestRun(segment, '`') + 1)
                val pad = if (segment.startsWith('`') || segment.endsWith('`')) " " else ""
                out.append(ticks).append(pad).append(segment).append(pad).append(ticks)
            } else {
                for (c in segment) {
                    if (c in ESCAPED) out.append('\\')
                    out.append(c)
                }
            }
            i = j
        }
        for (k in open.lastIndex downTo 0) close(open[k])
        return out.toString()
    }

    /** A body line that would read as a heading, list or quote has its first sign escaped. */
    private fun escapeStart(text: String): String {
        if (text.isEmpty()) return text
        val first = text[0]
        if (first == '#' || first == '>' || first == '+' || first == '-' && text.getOrNull(1) == ' ') return "\\$text"
        val digits = text.takeWhile { it.isDigit() }
        if (digits.isNotEmpty() && text.getOrNull(digits.length).let { it == '.' || it == ')' }) {
            return digits + "\\" + text.substring(digits.length)
        }
        return text
    }

    private fun longestRun(s: String, c: Char): Int {
        var best = 0
        var run = 0
        for (ch in s) {
            run = if (ch == c) run + 1 else 0
            best = maxOf(best, run)
        }
        return best
    }

    /** The styles written with markers; code is written as a span of its own, innermost. */
    private val MARKED = listOf(CharStyle.BOLD, CharStyle.ITALIC, CharStyle.STRIKETHROUGH)
    private const val ESCAPED = "\\`*_[]~"
}

/**
 * Writes [fragment] after the document's text as paragraphs of their own, formatted as the
 * fragment has them; into an empty document, in place of its one empty line. List IDs are moved
 * past the ones the document uses, so a new list never joins one already there.
 */
fun TextDocument.appendFragment(fragment: Fragment) {
    if (fragment.paragraphs.isEmpty()) return
    val empty = length == 0 && paragraphCount == 1
    val listBase = if (empty) 0 else (0 until paragraphCount).maxOf { p -> paragraphStyle(p).lists.maxOfOrNull { it.id } ?: 0 }
    val at = length
    val inserted = (if (empty) "" else "\n") + fragment.text
    replace(at, at, inserted)
    // What was there before does not run on into what is written after it.
    for (style in CharStyle.entries) setStyle(style, false, at, length)
    for (style in ValueStyle.entries) setValue(style, null, at, length)
    val first = paragraphCount - fragment.paragraphs.size
    for ((k, piece) in fragment.paragraphs.withIndex()) {
        val p = first + k
        setParagraphStyle(p, piece.style.copy(lists = piece.style.lists.map { it.copy(id = it.id + listBase) }))
        val base = paragraphStart(p)
        for (span in piece.spans) setStyle(span.style, true, base + span.start, base + span.end)
    }
}
