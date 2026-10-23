package com.syncednotes.engine.paste

import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role

/**
 * Formatted text from somewhere else, ready to paste: paragraphs, each with its style and the
 * character styles over parts of it, in the paragraph's own offsets. Only what a note can hold
 * is kept, and only as meaning: a heading, bold, code in C++; never a web page's fonts or
 * colours, which are the page's looks rather than the writing's.
 *
 * List IDs are the fragment's own; pasting gives them new ones the note does not use yet.
 */
class Fragment(val paragraphs: List<Piece>) {
    /** The text alone, paragraphs joined with line breaks. */
    val text: String get() = paragraphs.joinToString("\n") { it.text }

    val isEmpty: Boolean get() = paragraphs.isEmpty() || paragraphs.all { it.text.isEmpty() && it.style == ParagraphStyle.DEFAULT }
}

class Piece(val text: String, val style: ParagraphStyle, val spans: List<Span> = emptyList())

data class Span(val style: CharStyle, val start: Int, val end: Int)

/**
 * The language named in code from elsewhere: a Markdown fence's word, or an HTML class such as
 * `language-cpp`. Null for a language no code block has yet, whose code is then plain.
 */
fun codeLanguageNamed(name: String): CodeLanguage? = when (name.trim().lowercase()) {
    "c", "h" -> CodeLanguage.C
    "cpp", "c++", "cxx", "cc", "hpp", "hxx", "hh", "cplusplus" -> CodeLanguage.CPP
    else -> null
}

/**
 * Writes a [Fragment] a stretch of text at a time, as the readers come upon it. Text goes into
 * the paragraph being written, in the style it was given when it began.
 */
internal class FragmentBuilder {
    private val pieces = ArrayList<Piece>()
    private val text = StringBuilder()
    private val spans = ArrayList<Span>()
    private var style = ParagraphStyle.DEFAULT

    /** Paragraphs finished so far. */
    val count: Int get() = pieces.size

    val hasContent: Boolean get() = pieces.isNotEmpty() || text.isNotEmpty()

    /** Whether the paragraph being written has no text yet. */
    val atParagraphStart: Boolean get() = text.isEmpty()

    /** What the paragraph being written ends with; a space at its start, where none is wanted. */
    val lastChar: Char get() = if (text.isEmpty()) ' ' else text[text.length - 1]

    fun append(s: String, styles: Set<CharStyle> = emptySet()) {
        if (s.isEmpty()) return
        val a = text.length
        text.append(s)
        for (st in styles) {
            // A style going on from the stretch before is one span, as the note stores it.
            val last = spans.indexOfLast { it.style == st }.takeIf { it >= 0 && spans[it].end == a }
            if (last != null) spans[last] = spans[last].copy(end = text.length) else spans += Span(st, a, text.length)
        }
    }

    /** Ends the paragraph being written, if it has any text; the next is in [next]. */
    fun startParagraph(next: ParagraphStyle) {
        if (text.isNotEmpty()) finish()
        style = next
    }

    /** A line break: ends the paragraph being written even when empty; the next is in the same style. */
    fun lineBreak() = finish()

    /** A whole paragraph at once, after the one being written. */
    fun line(s: String, lineStyle: ParagraphStyle) {
        if (text.isNotEmpty()) finish()
        pieces += Piece(s, lineStyle)
    }

    /** An empty line, to set blocks apart as the page did with space between them. */
    fun blankLine() {
        if (text.isNotEmpty()) finish()
        if (pieces.isEmpty() || pieces.last().let { it.text.isEmpty() && it.style == ParagraphStyle.DEFAULT }) return
        pieces += Piece("", ParagraphStyle.DEFAULT)
    }

    /** A divider: a paragraph of its own, as the note format has one. */
    fun divider() = line(DIVIDER_TEXT, ParagraphStyle(role = Role.DIVIDER))

    private fun finish() {
        // Spaces at a paragraph's end are the page's layout, not the writing.
        var end = text.length
        if (style.role != Role.CODE_BLOCK) while (end > 0 && text[end - 1] == ' ') end--
        val kept = text.substring(0, end)
        pieces += Piece(kept, style, spans.mapNotNull { sp -> if (sp.start >= end) null else sp.copy(end = minOf(sp.end, end)) })
        text.setLength(0)
        spans.clear()
    }

    fun build(): Fragment {
        if (text.isNotEmpty()) finish()
        // Empty lines at either end are the page's spacing around what was copied.
        fun blank(p: Piece) = p.text.isEmpty() && p.style.role != Role.CODE_BLOCK
        while (pieces.isNotEmpty() && blank(pieces.last())) pieces.removeAt(pieces.lastIndex)
        while (pieces.isNotEmpty() && blank(pieces.first())) pieces.removeAt(0)
        return Fragment(pieces.toList())
    }

    companion object {
        const val DIVIDER_TEXT = "\uFFFC"
    }
}
