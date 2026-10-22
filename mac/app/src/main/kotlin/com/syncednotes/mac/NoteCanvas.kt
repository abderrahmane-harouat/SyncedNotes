package com.syncednotes.mac

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.editor.ComposerHost
import com.syncednotes.engine.editor.EditorStyle
import com.syncednotes.engine.editor.PointerAction
import com.syncednotes.engine.render.Compositor
import com.syncednotes.engine.render.DrawList
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image

/** Milliseconds on a clock that never jumps, the engine's only notion of time. */
internal fun now(): Long = System.nanoTime() / 1_000_000

/**
 * The engine for one window at one scale and appearance, and what Compose watches of it: when
 * to draw again, and what the formatting at the selection is, for the toolbar.
 */
class Editor(scale: Float, dark: Boolean) : ComposerHost {
    var frame by mutableLongStateOf(0L)
        private set
    var format: Composer.FormatState? by mutableStateOf(null)
        private set
    val focus = FocusRequester()
    val composer = Composer(AppFonts.family, EditorStyle.macLike(scale, dark), this)

    override fun requestFrame() { frame++ }
    override fun showKeyboard() {}
    override fun selectionChanged(selectionStart: Int, selectionEnd: Int, composingStart: Int, composingEnd: Int) {}
    override fun textChangedOutsideInput() {}
    override fun formatChanged() { format = composer.formatState() }
}

/**
 * The note, drawn by the engine: like the phone's NoteCanvasView, this owns no text logic. It
 * forwards the mouse and keys to the [Composer] and puts the frame the engine painted on
 * screen. Nothing of Compose's or Apple's text system is involved, so no system text service
 * (Writing Tools, Look Up, autocorrect, Services) has anything to attach to.
 */
@Composable
fun NoteCanvas(editor: Editor, modifier: Modifier = Modifier) {
    val composer = editor.composer
    val drawList = remember { DrawList() }
    val surface = remember { PixelSurface() }

    DisposableEffect(surface) { onDispose { surface.close() } }
    // The caret blinks without any input: wake for the engine's next frame, whenever it is.
    LaunchedEffect(composer) {
        while (true) {
            val t = now()
            val next = composer.nextFrameAt(t)
            if (next < 0) delay(250) else {
                if (next > t) delay(next - t)
                withFrameMillis { editor.requestFrame() }
            }
        }
    }

    Canvas(
        modifier
            .onSizeChanged { composer.setViewport(it.width, it.height, 0f, 0f) }
            .pointerHoverIcon(PointerIcon.Text)
            .focusRequester(editor.focus)
            .onFocusChanged { composer.setFocused(it.isFocused, now()) }
            .focusable()
            .onKeyEvent { MacKeys.handle(composer, it) }
            .pointerInput(composer) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val mouse = event.nativeEvent as? java.awt.event.MouseEvent
                        val clicks = mouse?.clickCount ?: 1
                        val shift = event.keyboardModifiers.isShiftPressed
                        val x = change.position.x
                        val y = change.position.y
                        when (event.type) {
                            PointerEventType.Press -> {
                                // A right click is for a menu, not for moving the caret.
                                if (mouse != null && (mouse.button != java.awt.event.MouseEvent.BUTTON1 || mouse.isControlDown)) continue
                                editor.focus.requestFocus()
                                composer.onMouse(PointerAction.DOWN, x, y, clicks, shift, now())
                            }
                            PointerEventType.Move -> if (change.pressed) composer.onMouse(PointerAction.MOVE, x, y, clicks, shift, now())
                            PointerEventType.Release -> composer.onMouse(PointerAction.UP, x, y, clicks, shift, now())
                            PointerEventType.Scroll -> composer.scrollBy(change.scrollDelta.y * SCROLL_STEP * density)
                        }
                        change.consume()
                    }
                }
            },
    ) {
        editor.frame
        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return@Canvas
        val t = now()
        composer.tick(t)
        composer.render(drawList, t)
        val pixels = surface.pixelsFor(width, height)
        val result = Compositor.render(pixels, width, height, surface.rowBytes, drawList)
        check(result == 0) { "The compositor rejected a frame ($result)" }
        surface.bitmap.notifyPixelsChanged()
        drawIntoCanvas { canvas ->
            Image.makeFromBitmap(surface.bitmap).use { canvas.nativeCanvas.drawImage(it, 0f, 0f) }
        }
    }
}

/** Points scrolled per wheel notch; a trackpad sends fractions of one. */
private const val SCROLL_STEP = 40f
