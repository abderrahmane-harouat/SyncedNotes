package com.syncednotes.mac

import com.syncednotes.core.PictureDecoder
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Pictures on the Mac, with the JDK's own ImageIO: decoded on this Mac, by code that talks to
 * nothing (Privacy.md). It reads PNG, JPEG, GIF and BMP; a format it does not read (HEIC) is
 * refused when inserted, and an empty frame when it came from elsewhere.
 */
object MacPictures : PictureDecoder {
    override fun decode(bytes: ByteArray, width: Int, height: Int): ByteArray? {
        val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val scaled = scale(source, width, height, BufferedImage.TYPE_INT_ARGB)
        // Premultiplied ARGB ints to premultiplied RGBA bytes, the engine's order.
        val pixels = IntArray(width * height)
        scaled.getRGB(0, 0, width, height, pixels, 0, width)
        val out = ByteArray(width * height * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            // getRGB gives straight colour whatever the image's type; premultiply for the engine.
            out[i * 4] = ((p shr 16 and 0xFF) * a / 255).toByte()
            out[i * 4 + 1] = ((p shr 8 and 0xFF) * a / 255).toByte()
            out[i * 4 + 2] = ((p and 0xFF) * a / 255).toByte()
            out[i * 4 + 3] = a.toByte()
        }
        return out
    }

    /** A picture ready to keep: its bytes, re-encoded, and its size in pixels. */
    class Prepared(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * A chosen file made ready to keep in a note: at most [MAX_SIDE] pixels a side, written
     * afresh as JPEG, or PNG when it has see-through parts. Writing it afresh keeps only the
     * pixels: a photo's location, date and camera stay out of the note. Null when it is not a
     * picture this Mac can read.
     */
    fun prepare(bytes: ByteArray): Prepared? {
        val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val shrink = minOf(1.0, MAX_SIDE.toDouble() / maxOf(source.width, source.height))
        val w = maxOf(1, (source.width * shrink).toInt())
        val h = maxOf(1, (source.height * shrink).toInt())
        val alpha = source.colorModel.hasAlpha()
        val picture = scale(source, w, h, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        if (alpha) {
            ImageIO.write(picture, "png", out)
        } else {
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            ImageIO.createImageOutputStream(out).use { stream ->
                writer.output = stream
                writer.write(null, IIOImage(picture, null, null), param)
            }
            writer.dispose()
        }
        return Prepared(out.toByteArray(), w, h)
    }

    /**
     * [source] at [width] × [height], halved step by step first when it is much larger: one
     * bilinear step down from a photo to a note's width drops most of its pixels unblended.
     */
    private fun scale(source: BufferedImage, width: Int, height: Int, type: Int): BufferedImage {
        var current = source
        while (current.width / 2 >= width && current.height / 2 >= height) {
            current = draw(current, current.width / 2, current.height / 2, type)
        }
        return draw(current, width, height, type)
    }

    private fun draw(source: BufferedImage, width: Int, height: Int, type: Int): BufferedImage {
        val target = BufferedImage(width, height, type)
        val g = target.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        return target
    }

    const val MAX_SIDE = 2560
}
