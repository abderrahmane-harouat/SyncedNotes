package com.syncednotes.engine.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A frame's drawing, recorded as bytes for the native compositor to execute in one call.
 * The layout is the contract in `glyphs/src/engine.rs` (`Engine::render`).
 */
class DrawList {
    var buffer: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        private set

    val length: Int get() = buffer.position()

    fun reset() {
        buffer.clear()
    }

    private fun ensure(bytes: Int) {
        if (buffer.remaining() >= bytes) return
        val bigger = ByteBuffer.allocateDirect(maxOf(buffer.capacity() * 2, buffer.position() + bytes)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.flip()
        bigger.put(buffer)
        buffer = bigger
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, argb: Int) {
        ensure(24)
        buffer.putInt(OP_RECT).putFloat(x).putFloat(y).putFloat(w).putFloat(h).putInt(argb)
    }

    /** Glyph origins are on the baseline, in view pixels. */
    fun glyphs(fontId: Int, size: Float, argb: Int, count: Int, glyphs: IntArray, xs: FloatArray, ys: FloatArray) {
        if (count == 0) return
        ensure(20 + 12 * count)
        buffer.putInt(OP_GLYPHS).putInt(fontId).putFloat(size).putInt(argb).putInt(count)
        for (i in 0 until count) buffer.putInt(glyphs[i]).putFloat(xs[i]).putFloat(ys[i])
    }

    fun path(argb: Int, path: PathBuilder) {
        ensure(12 + path.bytes.position())
        buffer.putInt(OP_PATH).putInt(argb).putInt(path.segments)
        val src = path.bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        src.flip()
        buffer.put(src)
    }

    private companion object {
        const val OP_RECT = 1
        const val OP_GLYPHS = 2
        const val OP_PATH = 3
    }
}

/** A filled outline; overlapping parts wound the same way stay solid. */
class PathBuilder {
    internal val bytes: ByteBuffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    internal var segments = 0
        private set

    fun reset(): PathBuilder {
        bytes.clear()
        segments = 0
        return this
    }

    fun moveTo(x: Float, y: Float) = seg(SEG_MOVE) { bytes.putFloat(x).putFloat(y) }
    fun lineTo(x: Float, y: Float) = seg(SEG_LINE) { bytes.putFloat(x).putFloat(y) }
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float) = seg(SEG_QUAD) { bytes.putFloat(cx).putFloat(cy).putFloat(x).putFloat(y) }
    fun close() = seg(SEG_CLOSE) {}

    /** A circle from eight quadratic arcs; at handle sizes the error is a fraction of a pixel. */
    fun circle(cx: Float, cy: Float, r: Float) {
        val step = Math.PI / 4
        val control = r / Math.cos(step / 2).toFloat()
        moveTo(cx + r, cy)
        for (i in 1..8) {
            val mid = step * (i - 0.5)
            val end = step * i
            quadTo(
                cx + control * Math.cos(mid).toFloat(), cy + control * Math.sin(mid).toFloat(),
                cx + r * Math.cos(end).toFloat(), cy + r * Math.sin(end).toFloat(),
            )
        }
        close()
    }

    private inline fun seg(kind: Int, write: () -> Unit) {
        bytes.putInt(kind)
        write()
        segments++
    }

    private companion object {
        const val SEG_MOVE = 0
        const val SEG_LINE = 1
        const val SEG_QUAD = 2
        const val SEG_CLOSE = 3
    }
}
