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
        // Arrows and symbols Roboto lacks (→ ⇒ ➜), which would otherwise draw as empty boxes.
        val math = load("NotoSansMath-Regular.ttf")
        val symbols = load("NotoSansSymbols2-Regular.ttf")
        // Last in every stack: what no other font has, and what is an emoji by default (⭐,
        // 👍) though a symbol font has it too; the shaper picks it for those.
        val emoji = load("NotoColorEmoji.ttf")
        // Naskh has no italic; italic Arabic text uses the upright face.
        FontFamily(
            regular = FontStack(listOf(load("Roboto-Regular.ttf"), arabic, math, symbols, emoji)),
            bold = FontStack(listOf(load("Roboto-Bold.ttf"), arabicBold, math, symbols, emoji)),
            italic = FontStack(listOf(load("Roboto-Italic.ttf"), arabic, math, symbols, emoji)),
            boldItalic = FontStack(listOf(load("Roboto-BoldItalic.ttf"), arabicBold, math, symbols, emoji)),
            monospace = FontStack(listOf(load("RobotoMono-Regular.ttf"), arabic, math, symbols, emoji)),
        )
    }

    private fun load(name: String): Font {
        val stream = AppFonts::class.java.getResourceAsStream("/fonts/$name") ?: error("The app is missing its font $name")
        return Font.load(stream.use { it.readBytes() })
    }
}
