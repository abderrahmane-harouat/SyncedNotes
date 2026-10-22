package com.syncednotes.mac

import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * A Skia bitmap the engine paints into directly: its pixel memory is handed to the Rust
 * compositor as a direct buffer, so a frame is never copied on its way in. Premultiplied RGBA
 * is the compositor's own layout, so nothing is converted either.
 */
class PixelSurface : AutoCloseable {
    var bitmap = Bitmap()
        private set
    var rowBytes = 0
        private set
    private var pixels: ByteBuffer? = null
    private var width = 0
    private var height = 0

    fun pixelsFor(width: Int, height: Int): ByteBuffer {
        pixels?.let { if (width == this.width && height == this.height) return it }
        bitmap.close()
        bitmap = Bitmap()
        check(bitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) { "No memory for a $width×$height frame" }
        val address = bitmap.peekPixels()?.addr ?: error("The frame's pixels cannot be reached")
        rowBytes = bitmap.rowBytes
        this.width = width
        this.height = height
        return MemorySegment.ofAddress(address).reinterpret(rowBytes.toLong() * height)
            .asByteBuffer().order(ByteOrder.LITTLE_ENDIAN)
            .also { pixels = it }
    }

    override fun close() {
        pixels = null
        bitmap.close()
    }
}
