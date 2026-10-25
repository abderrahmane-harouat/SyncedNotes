package com.syncednotes.engine.paste

import com.syncednotes.engine.text.CalloutColor
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListKind
import com.syncednotes.engine.text.ListLevel
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role

/**
 * Reads Markdown, which the Copy buttons under Claude's and ChatGPT's answers put on the
 * clipboard as plain text, and Notion as the plain text beside its HTML: headings, lists and
 * task lists, quotes, fenced code with its language, dividers, tables as lines, and bold,
 * italic, strikethrough and inline code. A link keeps its words; a picture is left out.
 *
 * Each line is a paragraph of its own, as a note's lines are: lines Markdown would run together
 * stay apart, so nothing written on a line of its own is ever joined to another.
 */
object MarkdownReader {
    private val FENCE = Regex("^(\\s*)(`{3,}|~{3,})\\s*([^`\\s]*).*$")
    private val HEADING = Regex("^ {0,3}(#{1,6})\\s+(.*?)(\\s+#+)?\\s*$")
    private val RULE = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val ITEM = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val TASK = Regex("^\\[([ xX])]\\s+(.*)$")
    private val QUOTE = Regex("^ {0,3}>\\s?(.*)$")
    private val CALLOUT = Regex("^ {0,3}>\\s?\\[!([A-Za-z]+)][+-]?\\s*(.*)$")
    private val TABLE_ROW = Regex("^\\s*\\|.*\\|\\s*$")
    private val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val SETEXT = Regex("^ {0,3}(=+|-+)\\s*$")
    private val INLINE_SIGNS = Regex("\\*\\*[^*\\s][^*]*\\*\\*|`[^`\\n]+`|\\[[^\\]\\n]+]\\([^)\\s]+\\)|~~[^~\\s][^~]*~~")

    /**
     * Whether [text] is written in Markdown, rather than being text that happens to hold a
     * star or a hash: it must have a code fence, a heading, a list, a quote or a table, or bold,
     * code or a link written the Markdown way. Plain text is pasted as it is.
     */
    fun looksLikeMarkdown(text: String): Boolean {
        val lines = text.lines()
        if (lines.any { FENCE.matches(it) || HEADING.matches(it) || TABLE_RULE.matches(it) && '|' in it }) return true
        if (lines.count { ITEM.matches(it) } >= 2 || lines.any { QUOTE.matches(it) }) return true
        return INLINE_SIGNS.containsMatchIn(text)
    }

    /** The languages of [text]'s fenced code, in order, for code the HTML beside it left unnamed. */
    fun fenceLanguages(text: String): List<CodeLanguage> {
        val out = ArrayList<CodeLanguage>()
        var open: String? = null
        for (line in text.lines()) {
            val m = FENCE.matchEntire(line) ?: continue
            if (open == null) {
                open = m.groupValues[2]
                out += codeLanguageNamed(m.groupValues[3]) ?: CodeLanguage.NONE
            } else if (m.groupValues[2].startsWith(open) && m.groupValues[3].isEmpty()) open = null
        }
        return out
    }

    fun read(markdown: String): Fragment {
        val out = FragmentBuilder()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // The lists open at this line: each with the indent its items' markers are at.
        val lists = ArrayList<Pair<Int, ListLevel>>()
        var listIds = 0
        var blankPending = false
        var i = 0
        fun blank() {
            if (blankPending) out.blankLine()
            blankPending = false
        }
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            if (fence != null) {
                lists.clear()
                blank()
                val indent = fence.groupValues[1].length
                val marker = fence.groupValues[2]
                val style = ParagraphStyle(role = Role.CODE_BLOCK, language = codeLanguageNamed(fence.groupValues[3]) ?: CodeLanguage.NONE)
                i++
                while (i < lines.size) {
                    val close = FENCE.matchEntire(lines[i])
                    if (close != null && close.groupValues[2].startsWith(marker) && close.groupValues[3].isEmpty()) break
                    // Code fenced inside a list is indented with it; that indent is not the code's.
                    var code = lines[i]
                    var cut = 0
                    while (cut < indent && cut < code.length && code[cut] == ' ') cut++
                    code = code.substring(cut)
                    out.line(code, style)
                    i++
                }
                i++
                continue
            }
            if (line.isBlank()) {
                // Blank lines between the items of a list, or before what is indented under
                // one, keep the list going; anywhere else they set blocks apart.
                val following = lines.subList(i + 1, lines.size).firstOrNull { it.isNotBlank() }
                val listGoesOn = lists.isNotEmpty() && following != null && (ITEM.matches(following) || following.startsWith(" "))
                if (!listGoesOn) {
                    lists.clear()
                    blankPending = out.hasContent
                }
                i++
                continue
            }
            val next = lines.getOrNull(i + 1)
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                lists.clear()
                blank()
                val role = when (heading.groupValues[1].length) {
                    1 -> Role.TITLE
                    2 -> Role.HEADING
                    else -> Role.SUBHEADING
                }
                inline(out, heading.groupValues[2], ParagraphStyle(role = role), heading = true)
                i++
                continue
            }
            if (RULE.matches(line)) {
                lists.clear()
                blank()
                out.divider()
                i++
                continue
            }
            // A line underlined with = or - is a heading too.
            if (next != null && lists.isEmpty() && SETEXT.matches(next) && !ITEM.matches(line) && !QUOTE.matches(line)) {
                blank()
                inline(out, line.trim(), ParagraphStyle(role = if (next.trim().startsWith("=")) Role.TITLE else Role.HEADING), heading = true)
                i += 2
                continue
            }
            val callout = CALLOUT.matchEntire(line)
            if (callout != null) {
                lists.clear()
                blank()
                val color = calloutColorNamed(callout.groupValues[1])
                // A title after the kind, as Obsidian has, is the callout's first line, in bold.
                val title = callout.groupValues[2].trim()
                if (title.isNotEmpty()) inline(out, "**$title**", ParagraphStyle(role = Role.CALLOUT, callout = color))
                i++
                // Items next to each other in the callout are one list, so its numbers run on.
                var list: ListLevel? = null
                while (i < lines.size) {
                    // Another kind marker is a callout of its own, straight after this one.
                    if (CALLOUT.matches(lines[i])) break
                    val inner = QUOTE.matchEntire(lines[i]) ?: break
                    val content = inner.groupValues[1]
                    val item = ITEM.matchEntire(content)
                    if (item != null) {
                        val kind = if (item.groupValues[2][0].isDigit()) ListKind.NUMBER else ListKind.BULLET
                        val level = list?.takeIf { it.kind == kind } ?: ListLevel(kind, ++listIds)
                        list = level
                        inline(out, item.groupValues[3], ParagraphStyle(role = Role.CALLOUT, callout = color, lists = listOf(level)))
                    } else {
                        list = null
                        inline(out, content, ParagraphStyle(role = Role.CALLOUT, callout = color))
                    }
                    i++
                }
                continue
            }
            val quote = QUOTE.matchEntire(line)
            if (quote != null) {
                lists.clear()
                blank()
                inline(out, quote.groupValues[1], ParagraphStyle(role = Role.QUOTE))
                i++
                continue
            }
            if (TABLE_ROW.matches(line)) {
                lists.clear()
                blank()
                val header = next != null && TABLE_RULE.matches(next)
                val cells = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
                val joined = cells.joinToString(" | ")
                inline(out, if (header) "**" + cells.joinToString("** | **") + "**" else joined, ParagraphStyle.DEFAULT)
                i += if (header) 2 else 1
                continue
            }
            val item = ITEM.matchEntire(line)
            if (item != null) {
                blank()
                val indent = item.groupValues[1].replace("\t", "    ").length
                val marker = item.groupValues[2]
                val numbered = marker[0].isDigit()
                val kind = if (numbered) ListKind.NUMBER else ListKind.BULLET
                while (lists.isNotEmpty() && lists.last().first > indent) lists.removeAt(lists.lastIndex)
                if (lists.isNotEmpty() && lists.last().first == indent && lists.last().second.kind != kind) lists.removeAt(lists.lastIndex)
                if (lists.isEmpty() || lists.last().first < indent) {
                    val start = if (numbered) marker.dropLast(1).toInt().takeIf { it > 1 } ?: 0 else 0
                    lists += indent to ListLevel(kind, ++listIds, start)
                }
                val levels = lists.map { it.second }
                var content = item.groupValues[3]
                val task = TASK.matchEntire(content)
                val style = if (task != null) {
                    content = task.groupValues[2]
                    ParagraphStyle(role = Role.CHECKLIST, lists = levels.dropLast(1), checked = task.groupValues[1] != " ")
                } else ParagraphStyle(lists = levels)
                inline(out, content, style)
                i++
                continue
            }
            // Text under a list item, indented to it, belongs with it.
            val trimmed = line.trim()
            val style = if (lists.isNotEmpty() && line.startsWith(" ")) ParagraphStyle(indent = lists.size) else {
                lists.clear()
                ParagraphStyle.DEFAULT
            }
            blank()
            inline(out, trimmed, style)
            i++
        }
        return out.build()
    }

    /** One line of text, with its bold, italic, strikethrough, code and links read. */
    private fun inline(out: FragmentBuilder, text: String, style: ParagraphStyle, heading: Boolean = false) {
        out.startParagraph(style)
        spans(out, text, emptySet(), heading)
        out.lineBreak()
    }

    private fun spans(out: FragmentBuilder, s: String, styles: Set<CharStyle>, heading: Boolean) {
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            out.append(plain.toString(), styles)
            plain.setLength(0)
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                    plain.append(s[i + 1])
                    i += 2
                }
                c == '`' -> {
                    var n = 0
                    while (i + n < s.length && s[i + n] == '`') n++
                    val ticks = "`".repeat(n)
                    val close = s.indexOf(ticks, i + n)
                    if (close < 0) {
                        plain.append(ticks)
                        i += n
                    } else {
                        flush()
                        out.append(s.substring(i + n, close).let { if (it.length > 1 && it.startsWith(' ') && it.endsWith(' ')) it.substring(1, it.length - 1) else it }, styles + CharStyle.CODE)
                        i = close + n
                    }
                }
                c == '!' && i + 1 < s.length && s[i + 1] == '[' -> {
                    // A picture: left out, as nothing is fetched.
                    val end = link(s, i + 1)
                    if (end == null) { plain.append(c); i++ } else i = end.second
                }
                c == '[' -> {
                    val end = link(s, i)
                    if (end == null) { plain.append(c); i++ } else {
                        flush()
                        spans(out, end.first, styles, heading)
                        i = end.second
                    }
                }
                (c == '*' || c == '_' || c == '~') && emphasis(s, i) != null -> {
                    val (marker, close) = emphasis(s, i)!!
                    flush()
                    val style = when {
                        marker.startsWith("~") -> CharStyle.STRIKETHROUGH
                        marker.length >= 2 -> CharStyle.BOLD
                        else -> CharStyle.ITALIC
                    }
                    // A heading is bold by what it is; its bold is not stored again.
                    val inner = if (style == CharStyle.BOLD && heading) styles else styles + style
                    val innerStyles = if (marker.length == 3) inner + CharStyle.ITALIC else inner
                    spans(out, s.substring(i + marker.length, close), innerStyles, heading)
                    i = close + marker.length
                }
                else -> {
                    plain.append(c)
                    i++
                }
            }
        }
        flush()
    }

    /** A link's words and where it ends, for a `[words](target)` at [open]; null if it is not one. */
    private fun link(s: String, open: Int): Pair<String, Int>? {
        val close = s.indexOf(']', open + 1)
        if (close < 0 || close + 1 >= s.length || s[close + 1] != '(') return null
        val end = s.indexOf(')', close + 2)
        if (end < 0) return null
        return s.substring(open + 1, close) to end + 1
    }

    /**
     * The marker of emphasis opening at [i] and where its closing marker starts, or null when
     * the stars or underscores there do not open any: a marker must touch the words it marks,
     * and an underscore inside a word (snake_case) marks nothing.
     */
    private fun emphasis(s: String, i: Int): Pair<String, Int>? {
        val c = s[i]
        var n = 0
        while (i + n < s.length && s[i + n] == c) n++
        if (c == '~' && n != 2) return null
        if (n > 3) return null
        val marker = s.substring(i, i + n)
        val after = s.getOrNull(i + n) ?: return null
        if (after.isWhitespace()) return null
        if (c == '_' && i > 0 && s[i - 1].isLetterOrDigit()) return null
        var from = i + n + 1
        while (true) {
            val close = s.indexOf(marker, from)
            if (close < 0) return null
            val before = s[close - 1]
            val following = s.getOrNull(close + n)
            val longer = following == c
            if (!before.isWhitespace() && !longer && !(c == '_' && following?.isLetterOrDigit() == true)) return marker to close
            from = close + 1
        }
    }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"

    /**
     * The colour for a callout's kind: GitHub's alerts and Obsidian's callouts, by what they
     * mean, and the colours by their names; any other kind is blue, a callout's own colour.
     */
    fun calloutColorNamed(name: String): CalloutColor = when (name.lowercase()) {
        "tip", "hint", "success", "check", "done", "green" -> CalloutColor.GREEN
        "warning", "attention", "orange", "yellow" -> CalloutColor.ORANGE
        "important", "abstract", "summary", "tldr", "purple" -> CalloutColor.PURPLE
        "caution", "danger", "error", "bug", "failure", "fail", "pink", "red" -> CalloutColor.PINK
        "gray", "grey", "quote", "cite", "example" -> CalloutColor.GRAY
        else -> CalloutColor.BLUE
    }
}
