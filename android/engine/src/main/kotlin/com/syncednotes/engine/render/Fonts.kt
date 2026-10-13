package com.syncednotes.engine.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vertical metrics in pixels for a given size, all measured down from the baseline as
 * positive-is-below except [ascent], which is the height above it.
 */
data class FontMetrics(
    val ascent: Float,
    val descent: Float,
    val lineGap: Float,
    /** Top of the underline, below the baseline. */
    val underlineOffset: Float,
    val underlineThickness: Float,
    /** Top of the strikethrough line, above the baseline (so usually negative here). */
    val strikeoutOffset: Float,
    val strikeoutThickness: Float,
)

class Font internal constructor(val id: Int, private val units: IntArray) {
    fun metrics(size: Float): FontMetrics {
        val scale = size / units[0]
        return FontMetrics(
            ascent = units[1] * scale,
            descent = -units[2] * scale,
            lineGap = units[3] * scale,
            // OpenType gives the top edge of each line, measured up from the baseline.
            underlineOffset = -units[4] * scale,
            underlineThickness = units[5] * scale,
            strikeoutOffset = -units[6] * scale,
            strikeoutThickness = units[7] * scale,
        )
    }

    companion object {
        /** Reads a TrueType file. Fails loudly: an editor with no font cannot show anything. */
        fun load(bytes: ByteArray): Font {
            val data = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            data.put(bytes)
            val id = Native.loadFont(data, bytes.size)
            require(id >= 0) { "Not a TrueType font we can read (error $id)" }
            val out = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN)
            check(Native.fontMetrics(id, out) == 0)
            return Font(id, IntArray(8) { out.getInt(it * 4) })
        }
    }
}

/**
 * The fonts a run of text is shaped with, in order: each character takes the first font that
 * has it. The first font also sets the line metrics.
 */
class FontStack(val fonts: List<Font>) {
    init {
        require(fonts.isNotEmpty() && fonts.size <= 8)
    }

    val primary: Font get() = fonts[0]
}

/**
 * One stack per face. A face a script does not have (Arabic has no italic) simply repeats the
 * nearest one inside the stack, so styled text in any script still draws.
 */
class FontFamily(
    val regular: FontStack,
    val bold: FontStack = regular,
    val italic: FontStack = regular,
    val boldItalic: FontStack = bold,
) {
    fun stack(isBold: Boolean, isItalic: Boolean): FontStack = when {
        isBold && isItalic -> boldItalic
        isBold -> bold
        isItalic -> italic
        else -> regular
    }
}
