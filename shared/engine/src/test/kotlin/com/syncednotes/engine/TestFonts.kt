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
        // Last in every stack: only what no other font has (emoji) comes from it.
        val emoji = load("NotoColorEmoji.ttf")
        FontFamily(
            regular = FontStack(listOf(load("Roboto-Regular.ttf"), arabic, emoji)),
            bold = FontStack(listOf(load("Roboto-Bold.ttf"), arabicBold, emoji)),
            italic = FontStack(listOf(load("Roboto-Italic.ttf"), arabic, emoji)),
            boldItalic = FontStack(listOf(load("Roboto-BoldItalic.ttf"), arabicBold, emoji)),
            monospace = FontStack(listOf(load("RobotoMono-Regular.ttf"), arabic, emoji)),
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
