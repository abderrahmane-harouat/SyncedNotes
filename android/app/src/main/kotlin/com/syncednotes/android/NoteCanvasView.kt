package com.syncednotes.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.editor.ComposerHost
import com.syncednotes.engine.editor.EditorStyle
import com.syncednotes.engine.editor.Key
import com.syncednotes.engine.editor.PointerAction
import com.syncednotes.engine.editor.PointerEvent
import com.syncednotes.engine.render.DrawList
import com.syncednotes.engine.render.FontFamily
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType

/**
 * The note's canvas, like Samsung's SpenComposer: a plain View that owns no text logic. It
 * forwards touches, keys and the input method to the [Composer] and puts the frame the engine
 * painted on screen.
 */
class NoteCanvasView(context: Context, fonts: FontFamily) : View(context), ComposerHost {
    val composer = Composer(fonts, EditorStyle.samsungLike(resources.displayMetrics.density, minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels), context.isDark), this)
    private val drawList = DrawList()
    private var bitmap: Bitmap? = null
    private val inputMethods = context.getSystemService(InputMethodManager::class.java)
    private val wake = Runnable { invalidate() }
    private var insetTop = 0f
    private var insetBottom = 0f
    // Whether the input method asked to hear every time the caret moves on screen.
    private var monitorCursor = false
    private var lastCaret = FloatArray(4)
    private val screenOrigin = IntArray(2)

    /** Called when the formatting under the caret, or what can be undone, may have changed. */
    var onFormatChanged: (() -> Unit)? = null
    var onTextOptionsShortcut: (() -> Unit)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setInsets(top: Int, bottom: Int) {
        insetTop = top.toFloat()
        insetBottom = bottom.toFloat()
        if (width > 0) composer.setViewport(width, height, insetTop, insetBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bitmap?.recycle()
        bitmap = if (w > 0 && h > 0) Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) else null
        composer.setViewport(w, h, insetTop, insetBottom)
    }

    override fun onDraw(canvas: Canvas) {
        val target = bitmap ?: return
        val now = SystemClock.uptimeMillis()
        composer.tick(now)
        composer.render(drawList, now)
        val result = BitmapRenderer.render(target, 0, 0, target.width, target.height, drawList.buffer, drawList.length)
        if (result != 0) Log.e(TAG, "The compositor rejected a frame ($result)")
        canvas.drawBitmap(target, 0f, 0f, null)
        // Scrolling moves the caret without moving the selection, so check on every frame.
        if (monitorCursor && !composer.caretInView().contentEquals(lastCaret)) sendCursorAnchor()
        scheduleFrame(now)
    }

    private fun scheduleFrame(now: Long) {
        removeCallbacks(wake)
        val next = composer.nextFrameAt(now)
        when {
            next < 0 -> {}
            next <= now -> postInvalidateOnAnimation()
            else -> postDelayed(wake, next - now)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PointerAction.DOWN
            MotionEvent.ACTION_MOVE -> PointerAction.MOVE
            MotionEvent.ACTION_UP -> PointerAction.UP
            MotionEvent.ACTION_CANCEL -> PointerAction.CANCEL
            // A second finger is for zooming, which comes later; until then it ends the gesture.
            MotionEvent.ACTION_POINTER_DOWN -> PointerAction.CANCEL
            else -> return true
        }
        if (action == PointerAction.MOVE) {
            // Batched samples between frames give the fling its real speed.
            for (h in 0 until event.historySize) {
                composer.onPointer(PointerEvent(action, event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalEventTime(h)))
            }
        }
        composer.onPointer(PointerEvent(action, event.x, event.y, event.eventTime))
        scheduleFrame(SystemClock.uptimeMillis())
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        val word = ctrl || event.isAltPressed
        if (formattingShortcut(keyCode, ctrl, shift, event.isAltPressed)) return true
        val key = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_MOVE_HOME -> Key.HOME
            KeyEvent.KEYCODE_MOVE_END -> Key.END
            KeyEvent.KEYCODE_PAGE_UP -> Key.PAGE_UP
            KeyEvent.KEYCODE_PAGE_DOWN -> Key.PAGE_DOWN
            KeyEvent.KEYCODE_DEL -> Key.BACKSPACE
            KeyEvent.KEYCODE_FORWARD_DEL -> Key.DELETE
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.ENTER
            KeyEvent.KEYCODE_TAB -> Key.TAB
            KeyEvent.KEYCODE_Z -> if (ctrl) (if (shift) Key.REDO else Key.UNDO) else null
            KeyEvent.KEYCODE_Y -> if (ctrl) Key.REDO else null
            KeyEvent.KEYCODE_A -> if (ctrl) Key.SELECT_ALL else null
            else -> null
        }
        val now = SystemClock.uptimeMillis()
        if (key != null) {
            composer.onKey(key, shift, word, now)
            return true
        }
        val unicode = event.getUnicodeChar(event.metaState)
        if (unicode > 0 && !ctrl && unicode and KeyCharacterMapCombining == 0) {
            composer.type(String(Character.toChars(unicode)), now)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Samsung Notes' formatting shortcuts for a hardware keyboard. */
    private fun formattingShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        when {
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_B -> composer.toggle(CharStyle.BOLD, now)
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_I -> composer.toggle(CharStyle.ITALIC, now)
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_U -> composer.toggle(CharStyle.UNDERLINE, now)
            alt && shift && keyCode == KeyEvent.KEYCODE_5 -> composer.toggle(CharStyle.STRIKETHROUGH, now)
            ctrl && shift && keyCode == KeyEvent.KEYCODE_8 -> composer.toggleList(ListType.BULLET, now)
            ctrl && shift && keyCode == KeyEvent.KEYCODE_7 -> composer.toggleList(ListType.NUMBER, now)
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_L -> composer.setAlignment(Alignment.LEFT, now)
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_E -> composer.setAlignment(Alignment.CENTER, now)
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_R -> composer.setAlignment(Alignment.RIGHT, now)
            ctrl && !shift && keyCode == KeyEvent.KEYCODE_T -> onTextOptionsShortcut?.invoke()
            else -> return false
        }
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        // No extract UI or full-screen editor: the canvas is the only editor. No personalised
        // learning: notes stay on the device, so the keyboard should not learn from them either.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = composer.selection.start
        outAttrs.initialSelEnd = composer.selection.end
        outAttrs.initialCapsMode = capsMode(outAttrs.inputType)
        return NoteInputConnection(this, composer)
    }

    fun capsMode(requested: Int): Int {
        val mode = when (composer.capitalisationAtCursor()) {
            Composer.Capitalisation.SENTENCE -> InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            Composer.Capitalisation.WORD -> InputType.TYPE_TEXT_FLAG_CAP_WORDS
            Composer.Capitalisation.NONE -> 0
        }
        return mode and requested
    }

    /** [InputConnection.requestCursorUpdates]: report the caret now, and/or whenever it moves. */
    fun requestCursorUpdates(mode: Int): Boolean {
        monitorCursor = mode and InputConnection.CURSOR_UPDATE_MONITOR != 0
        if (mode and InputConnection.CURSOR_UPDATE_IMMEDIATE != 0) sendCursorAnchor()
        return true
    }

    private fun sendCursorAnchor() {
        val caret = composer.caretInView()
        lastCaret = caret
        getLocationOnScreen(screenOrigin)
        val toScreen = Matrix().apply { setTranslate(screenOrigin[0].toFloat(), screenOrigin[1].toFloat()) }
        val info = CursorAnchorInfo.Builder()
            .setMatrix(toScreen)
            .setSelectionRange(composer.selection.start, composer.selection.end)
            .setInsertionMarkerLocation(caret[0], caret[1], caret[2], caret[3], CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
        if (composer.composingStart >= 0) {
            info.setComposingText(composer.composingStart, composer.document.substring(composer.composingStart, composer.composingEnd))
        }
        inputMethods.updateCursorAnchorInfo(this, info.build())
    }

    // --- ComposerHost ---

    override fun requestFrame() {
        postInvalidateOnAnimation()
    }

    override fun showKeyboard() {
        requestFocus()
        inputMethods.showSoftInput(this, 0)
    }

    override fun selectionChanged(selectionStart: Int, selectionEnd: Int, composingStart: Int, composingEnd: Int) {
        inputMethods.updateSelection(this, selectionStart, selectionEnd, composingStart, composingEnd)
        if (monitorCursor) sendCursorAnchor()
    }

    override fun textChangedOutsideInput() {
        inputMethods.restartInput(this)
    }

    override fun formatChanged() {
        onFormatChanged?.invoke()
    }

    private companion object {
        const val TAG = "NoteCanvasView"
        // KeyCharacterMap.COMBINING_ACCENT: a dead key, which waits for the next letter.
        const val KeyCharacterMapCombining = 0x80000000.toInt()
    }
}
