package com.syncednotes.engine.code

import com.syncednotes.engine.text.CodeLanguage

/**
 * What a stretch of code is, which is what it is coloured by. The colours are the editor's
 * ([com.syncednotes.engine.editor.EditorStyle.codeColors]), one for each kind in this order,
 * so the same code reads alike on a light page and a dark one.
 */
enum class TokenKind { KEYWORD, TYPE, LITERAL, STRING, COMMENT, PREPROCESSOR, FUNCTION }

/** A stretch [start, end) of a line, in the line's own offsets. */
data class Token(val start: Int, val end: Int, val kind: TokenKind)

/**
 * What a line of code leaves open for the next line of its block to carry on with: a comment
 * begun with slash-star, a C++ raw string, or a comment or string continued by a backslash.
 * The next line is coloured from here, so a comment over three lines is a comment on all three.
 */
data class LineState(val within: Within, val rawDelimiter: String = "") {
    enum class Within { CODE, BLOCK_COMMENT, LINE_COMMENT, STRING, RAW_STRING }

    companion object {
        val START = LineState(Within.CODE)
    }
}

class Highlighted(val tokens: List<Token>, val exit: LineState)

/**
 * Colours C and C++ a line at a time, as a lexer would read them, not by matching patterns
 * over the text: a keyword inside a string or a comment stays a string or a comment. Lines are
 * a code block's paragraphs, each laid out on its own, so what one line leaves open is handed
 * to the next as a [LineState] rather than the block being read as a whole on every keystroke.
 *
 * Written here rather than taken from a library because the note is the app's own engine, on
 * the phone as on the Mac, and nothing about it may depend on a text service of the system's.
 */
class Syntax private constructor(
    private val keywords: Set<String>,
    private val types: Set<String>,
    private val literals: Set<String>,
    /** C++'s R"delimiter( ... )delimiter", which may run over many lines. */
    private val rawStrings: Boolean,
) {
    fun highlight(text: String, entry: LineState): Highlighted {
        val tokens = ArrayList<Token>()
        val n = text.length
        fun add(a: Int, b: Int, kind: TokenKind) {
            if (a < b) tokens += Token(a, b, kind)
        }
        var i = 0
        when (entry.within) {
            LineState.Within.CODE -> {}
            LineState.Within.BLOCK_COMMENT -> {
                val close = text.indexOf("*/")
                if (close < 0) {
                    add(0, n, TokenKind.COMMENT)
                    return Highlighted(tokens, entry)
                }
                i = close + 2
                add(0, i, TokenKind.COMMENT)
            }
            LineState.Within.LINE_COMMENT -> {
                add(0, n, TokenKind.COMMENT)
                return Highlighted(tokens, if (continues(text)) entry else LineState.START)
            }
            LineState.Within.STRING -> {
                val end = quotedEnd(text, -1, '"')
                add(0, minOf(end, n), TokenKind.STRING)
                if (end > n) return Highlighted(tokens, entry)
                i = end
            }
            LineState.Within.RAW_STRING -> {
                val close = ")" + entry.rawDelimiter + "\""
                val at = text.indexOf(close)
                if (at < 0) {
                    add(0, n, TokenKind.STRING)
                    return Highlighted(tokens, entry)
                }
                i = at + close.length
                add(0, i, TokenKind.STRING)
            }
        }

        // Only spaces so far on this line: where a # begins a preprocessor directive.
        var lineStart = i == 0
        while (i < n) {
            val ch = text[i]
            if (ch == ' ' || ch == '\t') {
                i++
                continue
            }
            val atLineStart = lineStart
            lineStart = false
            val next = if (i + 1 < n) text[i + 1] else '\u0000'
            when {
                ch == '/' && next == '/' -> {
                    add(i, n, TokenKind.COMMENT)
                    return Highlighted(tokens, if (continues(text)) LineState(LineState.Within.LINE_COMMENT) else LineState.START)
                }
                ch == '/' && next == '*' -> {
                    val close = text.indexOf("*/", i + 2)
                    if (close < 0) {
                        add(i, n, TokenKind.COMMENT)
                        return Highlighted(tokens, LineState(LineState.Within.BLOCK_COMMENT))
                    }
                    add(i, close + 2, TokenKind.COMMENT)
                    i = close + 2
                }
                ch == '#' && atLineStart -> i = directive(text, i, ::add)
                ch == '"' || ch == '\'' -> {
                    val end = quotedEnd(text, i, ch)
                    add(i, minOf(end, n), TokenKind.STRING)
                    // A string a backslash carries onto the next line; a character never is.
                    if (end > n) return Highlighted(tokens, if (ch == '"') LineState(LineState.Within.STRING) else LineState.START)
                    i = end
                }
                isIdentifierStart(ch) -> {
                    var j = i + 1
                    while (j < n && isIdentifierPart(text[j])) j++
                    val word = text.substring(i, j)
                    val quote = if (j < n) text[j] else '\u0000'
                    if ((quote == '"' || quote == '\'') && word in STRING_PREFIXES) {
                        val end = quotedEnd(text, j, quote)
                        add(i, minOf(end, n), TokenKind.STRING)
                        if (end > n) return Highlighted(tokens, if (quote == '"') LineState(LineState.Within.STRING) else LineState.START)
                        i = end
                    } else if (quote == '"' && rawStrings && word in RAW_PREFIXES) {
                        val open = text.indexOf('(', j + 1)
                        val delimiter = if (open < 0) "" else text.substring(j + 1, open)
                        val close = ")$delimiter\""
                        val at = if (open < 0) -1 else text.indexOf(close, open + 1)
                        if (at < 0) {
                            add(i, n, TokenKind.STRING)
                            return Highlighted(tokens, LineState(LineState.Within.RAW_STRING, delimiter))
                        }
                        add(i, at + close.length, TokenKind.STRING)
                        i = at + close.length
                    } else {
                        when (word) {
                            in keywords -> add(i, j, TokenKind.KEYWORD)
                            in types -> add(i, j, TokenKind.TYPE)
                            in literals -> add(i, j, TokenKind.LITERAL)
                            else -> if (isCall(text, j)) add(i, j, TokenKind.FUNCTION)
                        }
                        i = j
                    }
                }
                ch.isDigit() || (ch == '.' && next.isDigit()) -> {
                    val end = numberEnd(text, i)
                    add(i, end, TokenKind.LITERAL)
                    i = end
                }
                else -> i++
            }
        }
        return Highlighted(tokens, LineState.START)
    }

    /**
     * A directive's # and name, and an #include's file, which reads as a string whether it is
     * in quotes or angle brackets. What follows a #define is code and is coloured as code.
     */
    private fun directive(text: String, hash: Int, add: (Int, Int, TokenKind) -> Unit): Int {
        val n = text.length
        var j = hash + 1
        while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
        val nameStart = j
        while (j < n && isIdentifierPart(text[j])) j++
        add(hash, j, TokenKind.PREPROCESSOR)
        if (text.substring(nameStart, j) !in FILE_DIRECTIVES) return j
        var k = j
        while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
        if (k < n && text[k] == '<') {
            val close = text.indexOf('>', k + 1)
            val end = if (close < 0) n else close + 1
            add(k, end, TokenKind.STRING)
            return end
        }
        return j
    }

    companion object {
        /** The highlighter for [language]; none for plain code, which is shown as it is. */
        fun of(language: CodeLanguage): Syntax? = when (language) {
            CodeLanguage.NONE -> null
            CodeLanguage.C -> C
            CodeLanguage.CPP -> CPP
        }

        private fun words(s: String): Set<String> = s.split(' ', '\n').filter { it.isNotEmpty() }.toHashSet()

        /** Types every C and C++ program has to hand, from the language or its standard headers. */
        private val COMMON_TYPES = words(
            """
            void char short int long float double signed unsigned
            size_t ssize_t ptrdiff_t intptr_t uintptr_t intmax_t uintmax_t max_align_t
            int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t
            wchar_t char16_t char32_t FILE va_list
            """,
        )

        // C23's keywords as well as C11's, written both ways where C23 renamed them.
        private val C = Syntax(
            keywords = words(
                """
                auto break case const constexpr continue default do else enum extern for goto if
                inline register restrict return sizeof static static_assert struct switch
                thread_local typedef typeof typeof_unqual union volatile while alignas alignof
                _Alignas _Alignof _Atomic _Generic _Noreturn _Static_assert _Thread_local
                """,
            ),
            types = COMMON_TYPES + words("bool _Bool _Complex _Imaginary _BitInt _Decimal32 _Decimal64 _Decimal128 nullptr_t"),
            literals = words("true false NULL nullptr"),
            rawStrings = false,
        )

        // C++23's keywords, and override and final, which are keywords where they matter.
        private val CPP = Syntax(
            keywords = words(
                """
                alignas alignof and and_eq asm auto bitand bitor break case catch class compl
                concept const consteval constexpr constinit const_cast continue co_await co_return
                co_yield decltype default delete do dynamic_cast else enum explicit export extern
                final for friend goto if import inline module mutable namespace new noexcept not
                not_eq operator or or_eq override private protected public register
                reinterpret_cast requires return sizeof static static_assert static_cast struct
                switch template this thread_local throw try typedef typeid typename union using
                virtual volatile while xor xor_eq
                """,
            ),
            types = COMMON_TYPES + words("bool char8_t nullptr_t"),
            literals = words("true false nullptr NULL"),
            rawStrings = true,
        )

        private val STRING_PREFIXES = setOf("L", "u", "U", "u8")
        private val RAW_PREFIXES = setOf("R", "LR", "uR", "UR", "u8R")
        private val FILE_DIRECTIVES = setOf("include", "include_next", "import", "embed")

        private fun isIdentifierStart(c: Char) = c == '_' || c == '$' || c.isLetter()

        private fun isIdentifierPart(c: Char) = c == '_' || c == '$' || c.isLetterOrDigit()

        /** A line a backslash at its very end carries on into the next. */
        private fun continues(text: String) = text.endsWith('\\')

        /**
         * Just past the quote that closes the one at [open] (-1 for a string carried over from
         * the line before); text.length + 1 when the line ends first, with a backslash carrying
         * the string on, and text.length when it simply ends unclosed.
         */
        private fun quotedEnd(text: String, open: Int, quote: Char): Int {
            val n = text.length
            var j = open + 1
            while (j < n) {
                when (text[j]) {
                    '\\' -> {
                        if (j + 1 >= n) return n + 1
                        j += 2
                    }
                    quote -> return j + 1
                    else -> j++
                }
            }
            return n
        }

        /**
         * Where a number ends: 42, 0x1F, 0b1010, 1'000'000, 3.5e-2f, 0x1p-3, 10ull. A sign
         * belongs to it only after an exponent, which in hexadecimal is p, since e is a digit.
         */
        private fun numberEnd(text: String, start: Int): Int {
            val n = text.length
            val hex = start + 1 < n && text[start] == '0' && (text[start + 1] == 'x' || text[start + 1] == 'X')
            var j = start
            while (j < n) {
                val c = text[j]
                val prev = if (j > start) text[j - 1] else '\u0000'
                j += when {
                    c.isLetterOrDigit() || c == '.' || c == '_' -> 1
                    // A digit separator, between two digits: 1'000.
                    c == '\'' && j > start && j + 1 < n && text[j + 1].isLetterOrDigit() -> 1
                    (c == '+' || c == '-') && (prev == 'p' || prev == 'P' || (!hex && (prev == 'e' || prev == 'E'))) -> 1
                    else -> return j
                }
            }
            return j
        }

        /** Whether the name ending at [end] is called or declared as a function: a ( follows it. */
        private fun isCall(text: String, end: Int): Boolean {
            var k = end
            while (k < text.length && (text[k] == ' ' || text[k] == '\t')) k++
            return k < text.length && text[k] == '('
        }
    }
}
