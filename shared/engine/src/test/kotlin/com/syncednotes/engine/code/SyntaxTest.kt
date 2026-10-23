package com.syncednotes.engine.code

import com.syncednotes.engine.text.CodeLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** C and C++ coloured a line at a time, as a lexer reads them. */
class SyntaxTest {
    private val c = Syntax.of(CodeLanguage.C)!!
    private val cpp = Syntax.of(CodeLanguage.CPP)!!

    /** Each coloured stretch of [line] as its text and kind. */
    private fun Syntax.tokens(line: String, entry: LineState = LineState.START): List<Pair<String, TokenKind>> =
        highlight(line, entry).tokens.map { line.substring(it.start, it.end) to it.kind }

    private fun Syntax.kindOf(line: String, word: String): TokenKind? =
        tokens(line).firstOrNull { it.first == word }?.second

    @Test
    fun keywordsTypesNumbersAndCallsEachHaveTheirKind() {
        assertEquals(
            listOf(
                "static" to TokenKind.KEYWORD, "int" to TokenKind.TYPE, "count" to TokenKind.FUNCTION,
                "const" to TokenKind.KEYWORD, "char" to TokenKind.TYPE, "return" to TokenKind.KEYWORD, "42" to TokenKind.LITERAL,
            ),
            c.tokens("static int count(const char *s) { return 42; }"),
        )
    }

    @Test
    fun aKeywordInsideAStringOrACommentIsPartOfTheStringOrComment() {
        assertEquals(listOf("\"return if while\"" to TokenKind.STRING, "// int main" to TokenKind.COMMENT), c.tokens("\"return if while\"; // int main"))
    }

    @Test
    fun cPlusPlusWordsAreOnlyKeywordsInCPlusPlus() {
        assertNull(c.kindOf("class template;", "class"))
        assertEquals(TokenKind.KEYWORD, cpp.kindOf("class template;", "class"))
        assertEquals(TokenKind.KEYWORD, cpp.kindOf("auto p = new Widget;", "new"))
    }

    @Test
    fun anIncludeIsADirectiveAndItsFileReadsAsAString() {
        assertEquals(listOf("#include" to TokenKind.PREPROCESSOR, "<stdio.h>" to TokenKind.STRING), c.tokens("#include <stdio.h>"))
        assertEquals(listOf("#  include" to TokenKind.PREPROCESSOR, "\"app.h\"" to TokenKind.STRING), c.tokens("#  include \"app.h\""))
    }

    @Test
    fun whatADefineStandsForIsColouredAsCode() {
        assertEquals(
            listOf("#define" to TokenKind.PREPROCESSOR, "LIMIT" to TokenKind.FUNCTION, "sizeof" to TokenKind.KEYWORD, "long" to TokenKind.TYPE),
            c.tokens("#define LIMIT(x) (sizeof(long) * x)"),
        )
    }

    @Test
    fun numbersAreReadWholeWithTheirBasesSeparatorsExponentsAndSuffixes() {
        for (number in listOf("0x1F", "0b1010", "1'000'000", "3.5e-2f", "0x1p-3", "10ull", ".5")) {
            assertEquals(listOf(number to TokenKind.LITERAL), c.tokens(number), number)
        }
        // In hexadecimal, e is a digit, so the sign after it is a minus of its own.
        assertEquals(listOf("0x1e" to TokenKind.LITERAL, "5" to TokenKind.LITERAL), c.tokens("0x1e-5"))
    }

    @Test
    fun anEscapedQuoteDoesNotEndAStringAndPrefixesBelongToIt() {
        assertEquals(listOf("\"say \\\"hi\\\"\"" to TokenKind.STRING), c.tokens("\"say \\\"hi\\\"\""))
        assertEquals(listOf("u8\"text\"" to TokenKind.STRING, "L'x'" to TokenKind.STRING), cpp.tokens("u8\"text\" L'x'"))
    }

    @Test
    fun aCommentLeftOpenCarriesOnIntoTheNextLinesUntilItIsClosed() {
        val first = c.highlight("int a; /* one", LineState.START)
        assertEquals(LineState.Within.BLOCK_COMMENT, first.exit.within)
        val middle = c.highlight("int still a comment", first.exit)
        assertEquals(listOf("int still a comment" to TokenKind.COMMENT), c.tokens("int still a comment", first.exit))
        assertEquals(first.exit, middle.exit)
        assertEquals(listOf("end */" to TokenKind.COMMENT, "int" to TokenKind.TYPE), c.tokens("end */ int b;", middle.exit))
        assertEquals(LineState.START, c.highlight("end */ int b;", middle.exit).exit)
    }

    @Test
    fun aRawStringRunsOverLinesToItsOwnDelimiter() {
        val open = cpp.highlight("auto s = R\"x(first", LineState.START)
        assertEquals(LineState(LineState.Within.RAW_STRING, "x"), open.exit)
        // A plain )" does not close a raw string with a delimiter.
        assertEquals(listOf("still )\" in it" to TokenKind.STRING), cpp.tokens("still )\" in it", open.exit))
        assertEquals(listOf("end)x\"" to TokenKind.STRING), cpp.tokens("end)x\";", open.exit))
        // C has no raw strings: R is a name like any other.
        assertEquals(listOf("\"x(first\"" to TokenKind.STRING), c.tokens("R\"x(first\""))
    }

    @Test
    fun aBackslashAtTheEndCarriesALineCommentOrAStringOn() {
        val comment = c.highlight("// one \\", LineState.START)
        assertEquals(listOf("and two" to TokenKind.COMMENT), c.tokens("and two", comment.exit))
        val string = c.highlight("\"one \\", LineState.START)
        assertEquals(LineState.Within.STRING, string.exit.within)
        assertEquals(listOf("two\"" to TokenKind.STRING, "x" to TokenKind.FUNCTION), c.tokens("two\" x()", string.exit))
    }

    @Test
    fun aHashAfterCodeIsNotADirective() {
        assertEquals(listOf("int" to TokenKind.TYPE), c.tokens("int a # b"))
    }

    @Test
    fun plainCodeHasNoHighlighter() {
        assertNull(Syntax.of(CodeLanguage.NONE))
    }
}
