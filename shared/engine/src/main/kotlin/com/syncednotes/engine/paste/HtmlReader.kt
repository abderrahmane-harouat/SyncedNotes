package com.syncednotes.engine.paste

import com.syncednotes.engine.text.CalloutColor
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListKind
import com.syncednotes.engine.text.ListLevel
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role

/**
 * Reads the HTML a browser or Notion puts on the clipboard with what was copied: the common
 * form every rich editor reads from, as no other is shared by Claude's, ChatGPT's and Notion's
 * pages. Headings, paragraphs, lists, checklists, quotes, code blocks with their language,
 * dividers, tables as lines, and bold, italic, underline, strikethrough and inline code.
 *
 * Only the markup is read. Nothing it points to is fetched, neither a picture nor a page, so
 * pasting never reaches the network; pictures are left out.
 *
 * Written here rather than taken from a library because both apps paste with it and the phone
 * has no HTML parser of Java's; what is on a clipboard is small, and forgiving reading of it is
 * enough: a tag that is never closed is closed by the one around it.
 */
object HtmlReader {
    fun read(html: String): Fragment {
        val root = parse(html)
        val walker = Walker()
        walker.children(root, Context())
        return walker.out.build()
    }

    // --- The tree --------------------------------------------------------------------------

    internal class Element(val name: String, val attributes: Map<String, String>) {
        val children = ArrayList<Any>()
    }

    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
    /** Elements whose content is not markup, read to their end tag and dropped. */
    private val RAW = setOf("script", "style", "textarea", "title", "xmp")
    private val BLOCKS = setOf(
        "address", "article", "aside", "blockquote", "details", "div", "dl", "dd", "dt", "figcaption", "figure", "footer",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "summary",
        "table", "ul",
    )

    internal fun parse(html: String): Element {
        val root = Element("#root", emptyMap())
        val stack = arrayListOf(root)
        fun top() = stack.last()
        fun closeTo(name: String, limit: Set<String> = emptySet()): Boolean {
            for (i in stack.indices.reversed()) {
                val n = stack[i].name
                if (n == name) {
                    while (stack.size > i) stack.removeAt(stack.lastIndex)
                    return true
                }
                if (n in limit || i == 0) return false
            }
            return false
        }
        var i = 0
        val n = html.length
        val text = StringBuilder()
        fun flushText() {
            if (text.isNotEmpty()) {
                top().children += decodeEntities(text.toString())
                text.setLength(0)
            }
        }
        while (i < n) {
            val c = html[i]
            if (c != '<' || i + 1 >= n) {
                text.append(c)
                i++
                continue
            }
            val next = html[i + 1]
            when {
                html.startsWith("<!--", i) -> {
                    flushText()
                    val end = html.indexOf("-->", i + 4)
                    i = if (end < 0) n else end + 3
                }
                next == '!' || next == '?' -> {
                    flushText()
                    val end = html.indexOf('>', i)
                    i = if (end < 0) n else end + 1
                }
                next == '/' -> {
                    flushText()
                    val end = html.indexOf('>', i)
                    val name = html.substring(i + 2, if (end < 0) n else end).trim().lowercase().takeWhile { !it.isWhitespace() }
                    // A list item, cell or row ends where its list, row or table does.
                    when (name) {
                        "li" -> closeTo("li", setOf("ul", "ol"))
                        "td", "th" -> closeTo(name, setOf("tr", "table"))
                        else -> closeTo(name)
                    }
                    i = if (end < 0) n else end + 1
                }
                next.isLetter() -> {
                    flushText()
                    val (element, end, selfClosing) = startTag(html, i)
                    i = end
                    val name = element.name
                    if (name in RAW) {
                        val close = html.indexOf("</$name", i, ignoreCase = true)
                        i = if (close < 0) n else html.indexOf('>', close).let { if (it < 0) n else it + 1 }
                        continue
                    }
                    when (name) {
                        "li" -> closeTo("li", setOf("ul", "ol"))
                        "td", "th" -> { closeTo("td", setOf("tr", "table")); closeTo("th", setOf("tr", "table")) }
                        "tr" -> closeTo("tr", setOf("table"))
                    }
                    // A block inside a paragraph ends the paragraph, as a browser reads it.
                    if (name in BLOCKS && top().name == "p") stack.removeAt(stack.lastIndex)
                    top().children += element
                    if (name !in VOID && !selfClosing) stack += element
                }
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        flushText()
        return root
    }

    /** The element starting at [at], where its tag ends, and whether it closed itself. */
    private fun startTag(html: String, at: Int): Triple<Element, Int, Boolean> {
        val n = html.length
        var i = at + 1
        while (i < n && (html[i].isLetterOrDigit() || html[i] == '-' || html[i] == ':')) i++
        val name = html.substring(at + 1, i).lowercase()
        val attributes = HashMap<String, String>()
        var selfClosing = false
        while (i < n) {
            while (i < n && html[i].isWhitespace()) i++
            if (i >= n) break
            if (html[i] == '>') return Triple(Element(name, attributes), i + 1, selfClosing)
            if (html[i] == '/') {
                selfClosing = true
                i++
                continue
            }
            val nameStart = i
            while (i < n && !html[i].isWhitespace() && html[i] != '=' && html[i] != '>' && html[i] != '/') i++
            val key = html.substring(nameStart, i).lowercase()
            while (i < n && html[i].isWhitespace()) i++
            var value = ""
            if (i < n && html[i] == '=') {
                i++
                while (i < n && html[i].isWhitespace()) i++
                if (i < n && (html[i] == '"' || html[i] == '\'')) {
                    val quote = html[i]
                    val end = html.indexOf(quote, i + 1).let { if (it < 0) n else it }
                    value = html.substring(i + 1, end)
                    i = minOf(n, end + 1)
                } else {
                    val start = i
                    while (i < n && !html[i].isWhitespace() && html[i] != '>') i++
                    value = html.substring(start, i)
                }
            }
            if (key.isNotEmpty()) attributes[key] = decodeEntities(value)
            if (i == nameStart) i++
        }
        return Triple(Element(name, attributes), n, selfClosing)
    }

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        "bull" to "•", "middot" to "·", "times" to "×", "divide" to "÷", "deg" to "°", "euro" to "€",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "le" to "≤", "ge" to "≥", "ne" to "≠",
    )

    internal fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val semi = if (c == '&') s.indexOf(';', i + 1).takeIf { it in (i + 2)..(i + 10) } else null
            if (semi == null) {
                out.append(c)
                i++
                continue
            }
            val name = s.substring(i + 1, semi)
            val decoded = when {
                name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)?.let(::codePoint)
                name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePoint)
                else -> ENTITIES[name]
            }
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun codePoint(cp: Int): String? = if (Character.isValidCodePoint(cp) && cp != 0) String(Character.toChars(cp)) else null

    // --- Reading the tree into a fragment ---------------------------------------------------

    /**
     * Where in the page the walk is: the character styles of the text here, the style a
     * paragraph begun here takes, and whether blocks here are the page's own, set apart from
     * each other by an empty line, rather than inside a list item, a quote or a table.
     */
    private data class Context(
        val styles: Set<CharStyle> = emptySet(),
        val paragraph: ParagraphStyle = ParagraphStyle.DEFAULT,
        val topLevel: Boolean = true,
        /** The lists around, outermost first. */
        val lists: List<ListLevel> = emptyList(),
        /** Inside a heading or a table's header, which is bold by what it is, not by a style. */
        val boldByRole: Boolean = false,
        val item: Item? = null,
        /** In a table's cell, which is part of its row's one line. */
        val inCell: Boolean = false,
        /** Inside a box the page gave a colour: a callout of the nearest colour. */
        val callout: CalloutColor? = null,
    )

    /**
     * A list item being read: its first paragraph has the bullet; any after it, inside the
     * same item, sit in line with its text instead of starting another item.
     */
    private class Item(val style: ParagraphStyle, val firstAt: Int)

    private val SKIPPED = setOf("head", "button", "svg", "template", "noscript", "select", "iframe", "object", "canvas", "img", "picture", "video", "audio", "input", "math")

    private class Walker {
        val out = FragmentBuilder()
        private var listIds = 0
        /** The last block was a heading or a divider, which needs no empty line after it. */
        private var afterHeading = false

        /** The style a paragraph begun here takes. */
        private fun paragraphStyle(ctx: Context): ParagraphStyle {
            val item = ctx.item ?: return inCallout(ctx.paragraph, ctx)
            return inCallout(if (out.count > item.firstAt) ParagraphStyle(indent = item.style.lists.size) else item.style, ctx)
        }

        /**
         * Text in a coloured box is a callout's, whatever else it is: a heading in one is a bold
         * line of it, a list in one keeps its list. Code, a divider and a checklist stay what
         * they are, as a callout cannot also be one.
         */
        private fun inCallout(style: ParagraphStyle, ctx: Context): ParagraphStyle {
            val color = ctx.callout ?: return style
            return when (style.role) {
                Role.CODE_BLOCK, Role.DIVIDER, Role.CHECKLIST -> style
                else -> style.copy(role = Role.CALLOUT, callout = color)
            }
        }

        private fun separate(ctx: Context) {
            if (ctx.topLevel && out.hasContent && !afterHeading) out.blankLine()
            afterHeading = false
        }

        fun children(element: Element, ctx: Context) {
            for (child in element.children) {
                if (child is String) text(child, ctx) else node(child as Element, ctx)
            }
        }

        private fun text(raw: String, ctx: Context) {
            // Spaces, tabs and line breaks in a page are all one space, as a browser shows them.
            var s = raw.replace(WHITESPACE, " ").replace(' ', ' ')
            if (out.atParagraphStart || out.lastChar == ' ') s = s.trimStart(' ')
            if (s.isEmpty()) return
            if (out.atParagraphStart) out.startParagraph(paragraphStyle(ctx))
            out.append(s, ctx.styles)
        }

        private fun block(element: Element, ctx: Context, inner: Context = ctx) {
            out.startParagraph(paragraphStyle(inner))
            children(element, inner)
            out.startParagraph(paragraphStyle(ctx))
        }

        private fun node(e: Element, outer: Context) {
            if (e.name in SKIPPED) return
            val boxed = calloutColor(e)?.takeIf { it != outer.callout && e.name in BOXES && !outer.inCell }
            if (boxed != null) {
                // A coloured box: set apart from what is around it, as a callout of its own.
                if (outer.topLevel) separate(outer)
                val inside = outer.copy(callout = boxed, topLevel = false, item = null, paragraph = ParagraphStyle(role = Role.CALLOUT, callout = boxed))
                out.startParagraph(paragraphStyle(inside))
                node(e, inside)
                out.startParagraph(paragraphStyle(outer))
                afterHeading = false
                return
            }
            val ctx = styled(e, outer)
            // Whatever a cell holds is part of its row's line.
            if (ctx.inCell) {
                if (e.name == "br" || e.name == "p" || e.name == "div" || e.name == "li") text(" ", ctx)
                if (e.name != "br") children(e, ctx)
                return
            }
            when (e.name) {
                "br" -> {
                    if (out.atParagraphStart) out.startParagraph(paragraphStyle(ctx))
                    out.lineBreak()
                }
                "hr" -> {
                    out.startParagraph(paragraphStyle(ctx))
                    out.divider()
                    afterHeading = true
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    separate(ctx)
                    val role = when (e.name) {
                        "h1" -> Role.TITLE
                        "h2" -> Role.HEADING
                        else -> Role.SUBHEADING
                    }
                    // In a callout, a heading is the callout's line in bold: it cannot be both.
                    if (ctx.callout != null) block(e, ctx, ctx.copy(paragraph = ParagraphStyle(role = role), item = null, styles = ctx.styles + CharStyle.BOLD))
                    else block(e, ctx, ctx.copy(paragraph = ParagraphStyle(role = role), boldByRole = true, item = null, styles = ctx.styles - CharStyle.BOLD))
                    afterHeading = true
                }
                "p" -> {
                    separate(ctx)
                    block(e, ctx)
                }
                "blockquote" -> {
                    separate(ctx)
                    block(e, ctx, ctx.copy(paragraph = ParagraphStyle(role = Role.QUOTE), topLevel = false, item = null))
                }
                "pre" -> {
                    separate(ctx)
                    pre(e)
                }
                "ul", "ol" -> {
                    if (ctx.lists.isEmpty()) separate(ctx)
                    val start = e.attributes["start"]?.trim()?.toIntOrNull()?.takeIf { it > 1 } ?: 0
                    val level = ListLevel(if (e.name == "ol") ListKind.NUMBER else ListKind.BULLET, ++listIds, start)
                    out.startParagraph(paragraphStyle(ctx))
                    children(e, ctx.copy(lists = ctx.lists + level, topLevel = false, item = null))
                    out.startParagraph(paragraphStyle(ctx))
                }
                "li" -> {
                    val box = checkbox(e)
                    val style = when {
                        box != null -> ParagraphStyle(role = Role.CHECKLIST, lists = ctx.lists.dropLast(1), checked = box)
                        ctx.lists.isEmpty() -> ParagraphStyle(lists = listOf(ListLevel(ListKind.BULLET, ++listIds)))
                        else -> ParagraphStyle(lists = ctx.lists)
                    }
                    val shown = inCallout(style, ctx)
                    out.startParagraph(shown)
                    children(e, ctx.copy(topLevel = false, item = Item(shown, out.count)))
                    out.startParagraph(paragraphStyle(outer))
                }
                "table" -> {
                    separate(ctx)
                    rows(e, ctx.copy(topLevel = false, item = null))
                    out.startParagraph(paragraphStyle(ctx))
                }
                "div", "section", "article", "header", "footer", "main", "nav", "aside", "figure", "figcaption",
                "details", "summary", "address", "dl", "dt", "dd" -> block(e, ctx)
                else -> children(e, ctx)
            }
        }

        /** The character styles an element gives what is inside it, by its tag or its inline style. */
        private fun styled(e: Element, ctx: Context): Context {
            val styles = ctx.styles.toMutableSet()
            when (e.name) {
                "b", "strong" -> if (!ctx.boldByRole) styles += CharStyle.BOLD
                "i", "em", "cite", "var", "dfn" -> styles += CharStyle.ITALIC
                "u", "ins" -> styles += CharStyle.UNDERLINE
                "s", "strike", "del" -> styles += CharStyle.STRIKETHROUGH
                "code", "kbd", "samp", "tt" -> styles += CharStyle.CODE
            }
            // Google Docs and Safari say bold and italic with inline styles rather than tags.
            e.attributes["style"]?.let { css ->
                for (declaration in css.split(';')) {
                    val colon = declaration.indexOf(':')
                    if (colon < 0) continue
                    val property = declaration.substring(0, colon).trim().lowercase()
                    val value = declaration.substring(colon + 1).trim().lowercase().removeSuffix("!important").trim()
                    when (property) {
                        "font-weight" -> if (!ctx.boldByRole) {
                            val weight = value.toIntOrNull()
                            if (value == "bold" || value == "bolder" || (weight != null && weight >= 600)) styles += CharStyle.BOLD
                            else if (value == "normal" || value == "lighter" || (weight != null && weight < 600)) styles -= CharStyle.BOLD
                        }
                        "font-style" -> if (value.startsWith("italic") || value.startsWith("oblique")) styles += CharStyle.ITALIC
                            else if (value == "normal") styles -= CharStyle.ITALIC
                        "text-decoration", "text-decoration-line" -> {
                            if ("underline" in value) styles += CharStyle.UNDERLINE
                            if ("line-through" in value) styles += CharStyle.STRIKETHROUGH
                            if (value == "none") { styles -= CharStyle.UNDERLINE; styles -= CharStyle.STRIKETHROUGH }
                        }
                    }
                }
            }
            return if (styles == ctx.styles) ctx else ctx.copy(styles = styles)
        }

        /**
         * A list item's checkbox, as a task list or a to-do list writes one: ticked or not, or
         * null for an item with none. Only one before any list inside the item counts.
         */
        private fun checkbox(li: Element): Boolean? {
            fun find(e: Element): Boolean? {
                for (child in e.children) {
                    if (child !is Element) continue
                    if (child.name == "ul" || child.name == "ol") return null
                    if (child.name == "input" && child.attributes["type"]?.lowercase() == "checkbox") return "checked" in child.attributes
                    if (child.attributes["role"] == "checkbox") return child.attributes["aria-checked"] == "true"
                    find(child)?.let { return it }
                }
                return null
            }
            return find(li)
        }

        /**
         * A code block, a paragraph a line, in the language its markup names. Code shown with a
         * bar above it (the language's name, a Copy button) has its code in a code element of
         * its own; only that is the code.
         */
        private fun pre(e: Element) {
            val codes = descendants(e, "code")
            val language = language(e) ?: codes.firstNotNullOfOrNull { language(it) } ?: CodeLanguage.NONE
            val raw = if (codes.isNotEmpty()) codes.joinToString("\n") { rawText(it) } else rawText(e)
            val lines = raw.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ').removeSuffix("\n").split('\n')
            val style = ParagraphStyle(role = Role.CODE_BLOCK, language = language)
            for (line in lines) out.line(line, style)
            afterHeading = false
        }

        private fun language(e: Element): CodeLanguage? {
            e.attributes["data-language"]?.let { name -> codeLanguageNamed(name)?.let { return it } }
            e.attributes["data-lang"]?.let { name -> codeLanguageNamed(name)?.let { return it } }
            for (token in e.attributes["class"].orEmpty().split(' ', '\t')) {
                val name = token.removePrefix("language-").removePrefix("lang-")
                if (name != token) codeLanguageNamed(name)?.let { return it }
            }
            return null
        }

        private fun descendants(e: Element, name: String): List<Element> {
            val found = ArrayList<Element>()
            fun visit(x: Element) {
                for (child in x.children) {
                    if (child !is Element) continue
                    // Code inside code is part of it, and read with it.
                    if (child.name == name) found += child else visit(child)
                }
            }
            visit(e)
            return found
        }

        /** Text as it stands in code, spaces and line breaks kept. */
        private fun rawText(e: Element): String {
            val sb = StringBuilder()
            fun visit(x: Element) {
                for (child in x.children) {
                    when {
                        child is String -> sb.append(child)
                        (child as Element).name == "br" -> sb.append('\n')
                        child.name in SKIPPED -> {}
                        else -> {
                            visit(child)
                            // A line drawn as a block of its own (as some editors copy code) ends with a break.
                            if ((child.name == "div" || child.name == "p") && !sb.endsWith("\n")) sb.append('\n')
                        }
                    }
                }
            }
            visit(e)
            return sb.toString()
        }

        /** A table as a line a row, cells set apart by a bar; a header cell's text is bold. */
        private fun rows(table: Element, ctx: Context) {
            for (child in table.children) {
                if (child !is Element) continue
                when (child.name) {
                    "tr" -> {
                        out.startParagraph(ParagraphStyle.DEFAULT)
                        var first = true
                        for (cell in child.children) {
                            if (cell !is Element || (cell.name != "td" && cell.name != "th")) continue
                            if (!first) out.append(" | ")
                            first = false
                            val cellCtx = styled(cell, ctx).let { if (cell.name == "th") it.copy(styles = it.styles + CharStyle.BOLD) else it }
                            children(cell, cellCtx.copy(paragraph = ParagraphStyle.DEFAULT, inCell = true))
                        }
                        out.startParagraph(ParagraphStyle.DEFAULT)
                    }
                    "thead", "tbody", "tfoot" -> rows(child, ctx)
                    else -> {}
                }
            }
        }
    }

    private val WHITESPACE = Regex("[ \\t\\n\\r\\f]+")

    /** The elements a page draws a coloured box with. */
    private val BOXES = setOf("div", "section", "aside", "figure", "blockquote", "article", "details", "p")

    /**
     * The callout colour an element's box has: by a class that says it is a callout or names a
     * background colour (Notion's `block-color-blue_background`), or by a background colour
     * in its inline style, as browsers write what was copied. Grey, white and black boxes are
     * not callouts, so a page's own background never becomes one.
     */
    internal fun calloutColor(e: Element): CalloutColor? {
        val classes = e.attributes["class"].orEmpty().lowercase().split(' ', '\t').filter { it.isNotEmpty() }
        for (c in classes) {
            if (!c.endsWith("_background") && !c.endsWith("-background")) continue
            NAMED_BACKGROUNDS.entries.firstOrNull { (name, _) -> name in c }?.let { return it.value }
        }
        val css = e.attributes["style"].orEmpty().lowercase()
        val background = Regex("background(-color)?\\s*:\\s*([^;]+)").find(css)?.groupValues?.get(2)
        val color = background?.let { COLOR_VALUE.find(it)?.value }?.let(::rgba)
        if (color != null) nearest(color)?.let { return it }
        return if (classes.any { "callout" in it }) CalloutColor.BLUE else null
    }

    private val NAMED_BACKGROUNDS = linkedMapOf(
        "blue" to CalloutColor.BLUE, "green" to CalloutColor.GREEN, "orange" to CalloutColor.ORANGE,
        "yellow" to CalloutColor.ORANGE, "brown" to CalloutColor.ORANGE, "purple" to CalloutColor.PURPLE,
        "pink" to CalloutColor.PINK, "red" to CalloutColor.PINK, "gray" to CalloutColor.GRAY, "grey" to CalloutColor.GRAY,
    )

    private val COLOR_VALUE = Regex("#[0-9a-f]{3,8}\\b|rgba?\\([^)]*\\)")

    /** Red, green and blue from 0 to 255, and alpha from 0 to 1, of a CSS colour; null if unread. */
    private fun rgba(value: String): FloatArray? {
        if (value.startsWith("#")) {
            val hex = value.substring(1)
            val full = when (hex.length) {
                3, 4 -> hex.map { "$it$it" }.joinToString("")
                6, 8 -> hex
                else -> return null
            }
            val n = full.chunked(2).map { it.toInt(16).toFloat() }
            return floatArrayOf(n[0], n[1], n[2], if (n.size == 4) n[3] / 255f else 1f)
        }
        val parts = value.substringAfter('(').substringBefore(')').split(',', ' ', '/').filter { it.isNotBlank() }
        if (parts.size < 3) return null
        fun channel(s: String) = if (s.endsWith("%")) s.dropLast(1).toFloatOrNull()?.times(2.55f) else s.toFloatOrNull()
        val r = channel(parts[0]) ?: return null
        val g = channel(parts[1]) ?: return null
        val b = channel(parts[2]) ?: return null
        val a = parts.getOrNull(3)?.let { if (it.endsWith("%")) it.dropLast(1).toFloatOrNull()?.div(100f) else it.toFloatOrNull() } ?: 1f
        return floatArrayOf(r, g, b, a)
    }

    /**
     * The callout colour nearest [rgba] by hue; none for a colour too see-through or too near
     * grey to be meant as one, judged by how far its channels stand apart rather than by HSL's
     * saturation, which calls a pale grey with a hint of warmth saturated.
     */
    private fun nearest(rgba: FloatArray): CalloutColor? {
        val (r, g, b, a) = rgba.toList()
        if (a < 0.08f) return null
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if ((max - min) * a < 14f) return null
        val hue = when (max) {
            r -> 60f * (((g - b) / (max - min)) % 6f)
            g -> 60f * ((b - r) / (max - min) + 2f)
            else -> 60f * ((r - g) / (max - min) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return when {
            hue < 15f -> CalloutColor.PINK
            hue < 70f -> CalloutColor.ORANGE
            hue < 170f -> CalloutColor.GREEN
            hue < 255f -> CalloutColor.BLUE
            hue < 290f -> CalloutColor.PURPLE
            else -> CalloutColor.PINK
        }
    }
}
