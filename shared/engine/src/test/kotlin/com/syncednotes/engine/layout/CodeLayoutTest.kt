package com.syncednotes.engine.layout

import com.syncednotes.engine.TestFonts
import com.syncednotes.engine.code.TokenKind
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role
import com.syncednotes.engine.text.TextDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A code block's paragraphs laid out with their syntax, from where the line above leaves off. */
class CodeLayoutTest {
    private fun codeBlock(text: String, language: CodeLanguage = CodeLanguage.C): Pair<TextDocument, DocumentLayout> {
        val doc = TextDocument()
        val layout = DocumentLayout(doc, TestFonts.family, 40f / 17, 17, 1.5f, 50f, 60f)
        layout.setWidth(2000f)
        doc.replace(0, 0, text)
        for (p in 0 until doc.paragraphCount) doc.setParagraphStyle(p, ParagraphStyle(role = Role.CODE_BLOCK, language = language))
        return doc to layout
    }

    /** What the character at [offset] of paragraph [p] is as code. */
    private fun DocumentLayout.tokenAt(p: Int, offset: Int): TokenKind? = paragraph(p).runs.let { it.token(it.runAt(offset)) }

    @Test
    fun aKeywordIsARunOfItsOwnAndStaysMonospaced() {
        val (_, layout) = codeBlock("return x;")
        val runs = layout.paragraph(0).runs
        assertEquals(TokenKind.KEYWORD, layout.tokenAt(0, 0))
        assertNull(layout.tokenAt(0, 7))
        assertTrue((0 until runs.count).all { runs.has(it, CharStyle.CODE) })
    }

    @Test
    fun plainCodeAndBodyTextHaveNoSyntax() {
        val (doc, layout) = codeBlock("return x;", CodeLanguage.NONE)
        assertNull(layout.paragraph(0).runs.syntax)
        doc.setParagraphStyle(0, ParagraphStyle(language = CodeLanguage.C))
        assertNull(layout.paragraph(0).runs.syntax)
    }

    @Test
    fun aCommentOpenedOnOneLineColoursTheLinesBelowUntilItCloses() {
        val (_, layout) = codeBlock("int a; /* starts\nint b;\nends */ int c;")
        assertEquals(TokenKind.TYPE, layout.tokenAt(0, 0))
        assertEquals(TokenKind.COMMENT, layout.tokenAt(1, 0))
        assertEquals(TokenKind.COMMENT, layout.tokenAt(2, 0))
        assertEquals(TokenKind.TYPE, layout.tokenAt(2, 8))
    }

    @Test
    fun closingTheCommentOnTheFirstLineRecoloursTheLinesBelowIt() {
        val (doc, layout) = codeBlock("/* note\nint b;\nint c;")
        assertEquals(TokenKind.COMMENT, layout.tokenAt(2, 0))
        doc.replace(7, 7, " */")
        assertEquals(TokenKind.TYPE, layout.tokenAt(1, 0))
        assertEquals(TokenKind.TYPE, layout.tokenAt(2, 0))
        doc.replace(7, 10, "")
        assertEquals(TokenKind.COMMENT, layout.tokenAt(2, 0))
    }

    @Test
    fun aLineThatStopsBeingCodeEndsTheCommentItOpened() {
        val (doc, layout) = codeBlock("/* note\nint b;")
        assertEquals(TokenKind.COMMENT, layout.tokenAt(1, 0))
        doc.setParagraphStyle(0, ParagraphStyle())
        assertEquals(TokenKind.TYPE, layout.tokenAt(1, 0))
    }

    @Test
    fun typingWithinALineLeavesTheLinesBelowItLaidOutAsTheyWere() {
        val (doc, layout) = codeBlock("int a;\nint b;\nint c;")
        layout.height
        val below = layout.paragraph(2)
        doc.replace(5, 5, " = 1")
        layout.height
        assertSame(below, layout.paragraph(2))
    }
}
