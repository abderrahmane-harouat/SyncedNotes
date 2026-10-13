package com.syncednotes.android

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** Has the Rust compositor draw a frame straight into a bitmap's pixels (see glyphs/src/jni.rs). */
internal object BitmapRenderer {
    init {
        System.loadLibrary("glyphs")
    }

    @JvmStatic external fun render(
        bitmap: Bitmap,
        clipLeft: Int, clipTop: Int, clipRight: Int, clipBottom: Int,
        commands: ByteBuffer, commandsLength: Int,
    ): Int
}
