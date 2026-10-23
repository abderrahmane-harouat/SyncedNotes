package com.syncednotes.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.syncednotes.core.PictureDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Pictures on the phone, with Android's own decoder: on the phone, by code that talks to
 * nothing (Privacy.md).
 */
object AndroidPictures : PictureDecoder {
    override fun decode(bytes: ByteArray, width: Int, height: Int): ByteArray? {
        val bitmap = decodeNear(bytes, width, height) ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val argb = if (scaled.config == Bitmap.Config.ARGB_8888) scaled else scaled.copy(Bitmap.Config.ARGB_8888, false)
        // An ARGB_8888 bitmap holds premultiplied RGBA bytes: the engine's very order.
        val out = ByteBuffer.allocate(width * height * 4)
        argb.copyPixelsToBuffer(out)
        return out.array()
    }

    /** A picture ready to keep: its bytes, re-encoded, and its size in pixels. */
    class Prepared(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * A chosen photo made ready to keep in a note: turned the way the camera said it was held,
     * at most [MAX_SIDE] pixels a side, written afresh as JPEG, or PNG when it has see-through
     * parts. Writing it afresh keeps only the pixels: where and when it was taken, and the
     * phone that took it, stay out of the note. Null when it is not a picture.
     */
    fun prepare(bytes: ByteArray): Prepared? {
        var bitmap = decodeNear(bytes, MAX_SIDE, MAX_SIDE) ?: return null
        val shrink = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val turn = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (shrink < 1f || turn != 0f) {
            val m = Matrix().apply { postScale(shrink, shrink); postRotate(turn) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        val out = ByteArrayOutputStream()
        if (bitmap.hasAlpha()) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        else bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Prepared(out.toByteArray(), bitmap.width, bitmap.height)
    }

    /** Decoded at the nearest power of two above the size wanted: a 12-megapixel photo never whole in memory. */
    private fun decodeNear(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    const val MAX_SIDE = 2560
}
