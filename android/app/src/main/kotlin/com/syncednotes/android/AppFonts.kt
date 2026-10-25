package com.syncednotes.android

import android.content.Context
import com.syncednotes.engine.render.Font
import com.syncednotes.engine.render.FontFamily
import com.syncednotes.engine.render.FontStack

/**
 * The fonts ship inside the app rather than coming from the system, so text looks the same on
 * every phone and the engine never depends on what a manufacturer installed. Loaded once per
 * process: the native side keeps every font it is given.
 */
object AppFonts {
    @Volatile private var family: FontFamily? = null

    fun get(context: Context): FontFamily = family ?: synchronized(this) {
        family ?: run {
            val arabic = load(context, "NotoNaskhArabic-Regular.ttf")
            val arabicBold = load(context, "NotoNaskhArabic-Bold.ttf")
            // Arrows and symbols Roboto lacks (→ ⇒ ➜), which would otherwise draw as empty boxes.
            val math = load(context, "NotoSansMath-Regular.ttf")
            val symbols = load(context, "NotoSansSymbols2-Regular.ttf")
            // Last in every stack: what no other font has, and what is an emoji by default (⭐,
            // 👍) though a symbol font has it too; the shaper picks it for those.
            val emoji = load(context, "NotoColorEmoji.ttf")
            // Naskh has no italic; italic Arabic text uses the upright face.
            FontFamily(
                regular = FontStack(listOf(load(context, "Roboto-Regular.ttf"), arabic, math, symbols, emoji)),
                bold = FontStack(listOf(load(context, "Roboto-Bold.ttf"), arabicBold, math, symbols, emoji)),
                italic = FontStack(listOf(load(context, "Roboto-Italic.ttf"), arabic, math, symbols, emoji)),
                boldItalic = FontStack(listOf(load(context, "Roboto-BoldItalic.ttf"), arabicBold, math, symbols, emoji)),
                monospace = FontStack(listOf(load(context, "RobotoMono-Regular.ttf"), arabic, math, symbols, emoji)),
            )
        }.also { family = it }
    }

    private fun load(context: Context, name: String): Font =
        Font.load(context.assets.open("fonts/$name").use { it.readBytes() })
}
