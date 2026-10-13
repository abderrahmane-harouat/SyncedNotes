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
            // Naskh has no italic; italic Arabic text uses the upright face.
            FontFamily(
                regular = FontStack(listOf(load(context, "Roboto-Regular.ttf"), arabic)),
                bold = FontStack(listOf(load(context, "Roboto-Bold.ttf"), arabicBold)),
                italic = FontStack(listOf(load(context, "Roboto-Italic.ttf"), arabic)),
                boldItalic = FontStack(listOf(load(context, "Roboto-BoldItalic.ttf"), arabicBold)),
            )
        }.also { family = it }
    }

    private fun load(context: Context, name: String): Font =
        Font.load(context.assets.open("fonts/$name").use { it.readBytes() })
}
