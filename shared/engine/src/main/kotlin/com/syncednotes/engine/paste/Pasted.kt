package com.syncednotes.engine.paste

import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.Role

/**
 * What a paste brings, from what is on the clipboard: its HTML, the form a browser and Notion
 * put rich text in; else its text read as Markdown, when it is Markdown, as the Copy buttons
 * of Claude and ChatGPT give it; else nothing, and the text goes in as it is.
 */
object Pasted {
    fun read(plain: String?, html: String?): Fragment? {
        if (!html.isNullOrBlank()) {
            val fragment = HtmlReader.read(html)
            if (!fragment.isEmpty) return withLanguagesFrom(plain, fragment)
        }
        if (plain != null && MarkdownReader.looksLikeMarkdown(plain)) return MarkdownReader.read(plain).takeIf { !it.isEmpty }
        return null
    }

    /**
     * Code blocks the HTML did not name a language for take the ones the Markdown beside it
     * fences them with, block for block, when both have the same blocks.
     */
    private fun withLanguagesFrom(plain: String?, fragment: Fragment): Fragment {
        if (plain == null) return fragment
        val languages = MarkdownReader.fenceLanguages(plain)
        val blocks = ArrayList<IntRange>()
        var p = 0
        val pieces = fragment.paragraphs
        while (p < pieces.size) {
            if (pieces[p].style.role != Role.CODE_BLOCK) { p++; continue }
            val start = p
            while (p < pieces.size && pieces[p].style.role == Role.CODE_BLOCK) p++
            blocks += start until p
        }
        if (languages.size != blocks.size || blocks.isEmpty()) return fragment
        val out = pieces.toMutableList()
        for ((b, range) in blocks.withIndex()) {
            if (pieces[range.first].style.language != CodeLanguage.NONE || languages[b] == CodeLanguage.NONE) continue
            for (q in range) out[q] = Piece(pieces[q].text, pieces[q].style.copy(language = languages[b]), pieces[q].spans)
        }
        return Fragment(out)
    }
}
