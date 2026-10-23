package com.syncednotes.engine.render

import com.syncednotes.engine.editor.PointerAction
import com.syncednotes.engine.editor.PointerEvent
import com.syncednotes.engine.newComposer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderTest {
    private val width = 1080
    private val height = 600

    private fun frame(draw: (DrawList) -> Unit): ByteBuffer {
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
        val list = DrawList()
        draw(list)
        assertEquals(0, Native.renderToBuffer(pixels, width, height, width * 4, 0, 0, width, height, list.buffer, list.length))
        return pixels
    }

    private fun ByteBuffer.rgb(x: Int, y: Int): Int {
        val i = (y * width + x) * 4
        return ((get(i).toInt() and 0xFF) shl 16) or ((get(i + 1).toInt() and 0xFF) shl 8) or (get(i + 2).toInt() and 0xFF)
    }

    private fun ByteBuffer.darkPixels(x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) if ((rgb(x, y) shr 16) < 0x80) n++
        return n
    }

    @Test
    fun typedTextPutsInkOnTheFirstLineAndNowhereElse() {
        val c = newComposer(height = height)
        c.commitText("Hello world", 1, 0)
        val px = frame { c.render(it, 0) }
        val lineBottom = (c.style.paddingTop + c.style.lineHeight).toInt()
        assertTrue(px.darkPixels(0, 0, width, lineBottom) > 500, "text should be drawn")
        assertEquals(0, px.darkPixels(0, lineBottom, width, height), "nothing below the first line")
        assertEquals(c.style.background and 0xFFFFFF, px.rgb(width - 1, height - 1))
    }

    @Test
    fun aSelectedWordIsHighlightedAndGetsTwoHandles() {
        val c = newComposer(height = height)
        c.commitText("hello world", 1, 0)
        c.onPointer(PointerEvent(PointerAction.DOWN, 100f, 70f, 1000))
        c.tick(1500)
        c.onPointer(PointerEvent(PointerAction.UP, 100f, 70f, 1600))
        val px = frame { c.render(it, 1600) }
        val selection = c.style.selectionColor and 0xFFFFFF
        val handle = c.style.handleColor and 0xFFFFFF
        var highlighted = 0
        var handlePixels = 0
        for (y in 0 until height) for (x in 0 until width) {
            when (px.rgb(x, y)) {
                selection -> highlighted++
                handle -> handlePixels++
            }
        }
        assertTrue(highlighted > 1000, "the word's background is highlighted ($highlighted)")
        assertTrue(handlePixels > 300, "both handles are drawn ($handlePixels)")
    }

    @Test
    fun anEmojiIsDrawnInItsOwnColours() {
        val c = newComposer(height = height)
        c.commitText("\uD83D\uDE00", 1, 0)
        val px = frame { c.render(it, 0) }
        var yellow = 0
        for (y in 0 until (c.style.paddingTop + c.style.lineHeight).toInt()) for (x in 0 until width / 4) {
            val rgb = px.rgb(x, y)
            if ((rgb shr 16) > 200 && (rgb and 0xFF) < 120) yellow++
        }
        assertTrue(yellow > 100, "a yellow face, not black text: $yellow yellow pixels")
    }
}
