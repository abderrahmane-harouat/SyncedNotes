package com.syncednotes.engine

import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.editor.ComposerHost
import com.syncednotes.engine.editor.EditorStyle
import com.syncednotes.engine.render.Font
import com.syncednotes.engine.render.FontFamily
import com.syncednotes.engine.render.FontStack
import java.io.File

/** The app's own fonts, loaded once for every test. */
object TestFonts {
    private val dir by lazy { File(System.getProperty("syncednotes.fonts")) }
    private fun load(name: String) = Font.load(File(dir, name).readBytes())

    val family: FontFamily by lazy {
        val arabic = load("NotoNaskhArabic-Regular.ttf")
        val arabicBold = load("NotoNaskhArabic-Bold.ttf")
        // Arrows and symbols Roboto lacks (→ ⇒ ➜), which would otherwise draw as empty boxes.
        val math = load("NotoSansMath-Regular.ttf")
        val symbols = load("NotoSansSymbols2-Regular.ttf")
        // Last in every stack: what no other font has, and what is an emoji by default (⭐,
        // 👍) though a symbol font has it too; the shaper picks it for those.
        val emoji = load("NotoColorEmoji.ttf")
        FontFamily(
            regular = FontStack(listOf(load("Roboto-Regular.ttf"), arabic, math, symbols, emoji)),
            bold = FontStack(listOf(load("Roboto-Bold.ttf"), arabicBold, math, symbols, emoji)),
            italic = FontStack(listOf(load("Roboto-Italic.ttf"), arabic, math, symbols, emoji)),
            boldItalic = FontStack(listOf(load("Roboto-BoldItalic.ttf"), arabicBold, math, symbols, emoji)),
            monospace = FontStack(listOf(load("RobotoMono-Regular.ttf"), arabic, math, symbols, emoji)),
        )
    }
}

class RecordingHost : ComposerHost {
    var invalidations = 0
    var keyboardShown = 0
    var restarts = 0
    val selections = ArrayList<List<Int>>()

    override fun requestFrame() { invalidations++ }
    override fun showKeyboard() { keyboardShown++ }
    override fun selectionChanged(selectionStart: Int, selectionEnd: Int, composingStart: Int, composingEnd: Int) {
        selections += listOf(selectionStart, selectionEnd, composingStart, composingEnd)
    }
    override fun textChangedOutsideInput() { restarts++ }
    var formatChanges = 0
    override fun formatChanged() { formatChanges++ }
    /** Each time the languages were asked for: the block's first paragraph and its language. */
    val languageMenus = ArrayList<Pair<Int, com.syncednotes.engine.text.CodeLanguage>>()
    override fun chooseCodeLanguage(paragraph: Int, current: com.syncednotes.engine.text.CodeLanguage, x: Float, y: Float) {
        languageMenus += paragraph to current
    }
}

/** A phone-sized editor at density 2.625 (420 dpi), the emulator's. */
fun newComposer(host: RecordingHost = RecordingHost(), width: Int = 1080, height: Int = 1700): Composer {
    val c = Composer(TestFonts.family, EditorStyle.samsungLike(2.625f, 1080), host)
    c.setViewport(width, height, 0f, 0f)
    return c
}
