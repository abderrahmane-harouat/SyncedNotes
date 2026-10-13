package com.syncednotes.engine.render

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Glyphs for a run of text, in logical order, with positions along an unbroken line. */
class ShapedRun(
    val count: Int,
    val fontIds: IntArray,
    val glyphs: IntArray,
    /** Index into the shaped text of the first UTF-16 unit each glyph came from. */
    val clusters: IntArray,
    val advances: FloatArray,
    val offsetsX: FloatArray,
    val offsetsY: FloatArray,
) {
    companion object {
        val EMPTY = ShapedRun(0, IntArray(0), IntArray(0), IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0))
    }
}

/**
 * Shapes text through the native shaper. Keeps its buffers between calls so typing does not
 * allocate per keystroke; not thread-safe, like everything else in the editor.
 */
class Shaper(private val stack: FontStack) {
    private var input = direct(4096)
    private var output = direct(24 * 256)

    fun shape(text: CharSequence, start: Int, end: Int, size: Float): ShapedRun {
        val length = end - start
        if (length == 0) return ShapedRun.EMPTY
        val needed = 4 + 4 * stack.fonts.size + 4 + 4 + 2 * length
        if (input.capacity() < needed) input = direct(needed * 2)
        input.clear()
        input.putInt(stack.fonts.size)
        for (f in stack.fonts) input.putInt(f.id)
        input.putFloat(size)
        input.putInt(length)
        for (i in start until end) input.putChar(text[i])

        var n = Native.shape(input, needed, output)
        if (n < 0 && n != Int.MIN_VALUE) {
            output = direct(-n * 24 * 2)
            n = Native.shape(input, needed, output)
        }
        check(n >= 0) { "The shaper refused its input ($n)" }

        val run = ShapedRun(n, IntArray(n), IntArray(n), IntArray(n), FloatArray(n), FloatArray(n), FloatArray(n))
        for (i in 0 until n) {
            val o = i * 24
            run.fontIds[i] = output.getInt(o)
            run.glyphs[i] = output.getInt(o + 4)
            run.clusters[i] = output.getInt(o + 8)
            run.advances[i] = output.getFloat(o + 12)
            run.offsetsX[i] = output.getFloat(o + 16)
            run.offsetsY[i] = output.getFloat(o + 20)
        }
        return run
    }

    private fun direct(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN)
}
