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
        FontFamily(
            regular = FontStack(listOf(load("Roboto-Regular.ttf"), arabic)),
            bold = FontStack(listOf(load("Roboto-Bold.ttf"), arabicBold)),
            italic = FontStack(listOf(load("Roboto-Italic.ttf"), arabic)),
            boldItalic = FontStack(listOf(load("Roboto-BoldItalic.ttf"), arabicBold)),
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
}

/** A phone-sized editor at density 2.625 (420 dpi), the emulator's. */
fun newComposer(host: RecordingHost = RecordingHost(), width: Int = 1080, height: Int = 1700): Composer {
    val c = Composer(TestFonts.family, EditorStyle.samsungLike(2.625f), host)
    c.setViewport(width, height, 0f, 0f)
    return c
}
