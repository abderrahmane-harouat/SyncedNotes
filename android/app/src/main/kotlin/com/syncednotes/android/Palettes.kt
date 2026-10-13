package com.syncednotes.android

/** Samsung Notes' swatches, in its order. */
object Palettes {
    /** The four text-colour pages Samsung shows by default (palette sets 1, 4, 21 and 5). */
    val text: List<IntArray> = listOf(
        argb(0xff3636, 0xffcb30, 0xf7eb00, 0x2febd2, 0x3396ff, 0x1447ff, 0x9933ff, 0x252525),
        argb(0xef806c, 0xd94d3c, 0x123644, 0x3f8881, 0x6088b1, 0x50479a, 0xa56133, 0xceb8a1),
        argb(0xfbe156, 0xf05268, 0x6cdd8f, 0x7289ff, 0xdadada, 0x676767, 0xffc000, 0x37d366),
        argb(0x6ca9e8, 0x92b0e2, 0xb0d29f, 0xba9500, 0xeb7461, 0x743aa2, 0xc767a6, 0xc27f65),
    )

    /** Highlights: "none" first (0), then Samsung's seven marker colours. */
    val highlight: List<IntArray> = listOf(
        intArrayOf(0) + argb(0xff3636, 0xffb426, 0xfff400, 0x00ebad, 0x0037ff, 0xae33ff, 0x252525),
    )

    private fun argb(vararg rgb: Int) = IntArray(rgb.size) { rgb[it] or 0xFF000000.toInt() }
}
