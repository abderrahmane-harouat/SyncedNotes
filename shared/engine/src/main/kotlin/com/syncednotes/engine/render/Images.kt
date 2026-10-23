package com.syncednotes.engine.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A note's pictures, handed to the Rust half at the size they are shown. The platform decodes
 * them (Android's own decoder, the JDK's ImageIO on the Mac), so the engine only ever copies
 * pixels and holds no image format's code.
 */
object Images {
    /**
     * Keeps [rgba], premultiplied RGBA rows of [width] pixels, for [DrawList.image]; returns its
     * id, or a negative number when the bytes do not make that size.
     */
    fun load(rgba: ByteArray, width: Int, height: Int): Int {
        val buffer = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(rgba).flip()
        return Native.loadImage(buffer, width, height)
    }

    fun free(id: Int) {
        Native.freeImage(id)
    }
}
