package com.syncednotes.engine.render

import java.nio.ByteBuffer

/**
 * Paints a frame into pixels the platform owns: RGBA, premultiplied, [stride] bytes a row.
 * The phone paints into an Android bitmap through its own entry point; the Mac paints into
 * the memory of the window's Skia bitmap through this one, so a frame is never copied.
 */
object Compositor {
    /** 0 when the frame was painted; a negative error from the native side otherwise. */
    fun render(pixels: ByteBuffer, width: Int, height: Int, stride: Int, list: DrawList): Int =
        Native.renderToBuffer(pixels, width, height, stride, 0, 0, width, height, list.buffer, list.length)
}
