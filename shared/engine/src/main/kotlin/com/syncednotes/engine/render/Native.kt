package com.syncednotes.engine.render

import java.nio.ByteBuffer

/**
 * The Rust half of the engine (`shared/glyphs`). Every buffer passed here must be a direct
 * [ByteBuffer] in little-endian order; the byte layouts are documented in `glyphs/src/engine.rs`
 * and must change together with it.
 */
internal object Native {
    init {
        System.loadLibrary("glyphs")
    }

    /** The new font's id, or a negative number when the bytes are not a font we can read. */
    @JvmStatic external fun loadFont(data: ByteBuffer, length: Int): Int

    /** Writes unitsPerEm, ascender, descender, lineGap as four Ints into [out]. */
    @JvmStatic external fun fontMetrics(font: Int, out: ByteBuffer): Int

    /** Glyph count; minus the count needed when [output] is too small. */
    @JvmStatic external fun shape(input: ByteBuffer, inputLength: Int, output: ByteBuffer): Int

    @JvmStatic external fun renderToBuffer(
        pixels: ByteBuffer, width: Int, height: Int, stride: Int,
        clipLeft: Int, clipTop: Int, clipRight: Int, clipBottom: Int,
        commands: ByteBuffer, commandsLength: Int,
    ): Int
}
