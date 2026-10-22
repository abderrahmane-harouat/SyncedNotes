package com.syncednotes.mac

import com.syncednotes.engine.render.Font
import com.syncednotes.engine.render.FontFamily
import com.syncednotes.engine.render.FontStack

/**
 * The phone's fonts, inside the app rather than the Mac's own: a note looks the same on both
 * devices, and the engine never asks macOS for anything. Loaded once: the native side keeps
 * every font it is given.
 */
object AppFonts {
    val family: FontFamily by lazy {
        val arabic = load("NotoNaskhArabic-Regular.ttf")
        val arabicBold = load("NotoNaskhArabic-Bold.ttf")
        // Naskh has no italic; italic Arabic text uses the upright face.
        FontFamily(
            regular = FontStack(listOf(load("Roboto-Regular.ttf"), arabic)),
            bold = FontStack(listOf(load("Roboto-Bold.ttf"), arabicBold)),
            italic = FontStack(listOf(load("Roboto-Italic.ttf"), arabic)),
            boldItalic = FontStack(listOf(load("Roboto-BoldItalic.ttf"), arabicBold)),
            monospace = FontStack(listOf(load("RobotoMono-Regular.ttf"), arabic)),
        )
    }

    private fun load(name: String): Font {
        val stream = AppFonts::class.java.getResourceAsStream("/fonts/$name") ?: error("The app is missing its font $name")
        return Font.load(stream.use { it.readBytes() })
    }
}
