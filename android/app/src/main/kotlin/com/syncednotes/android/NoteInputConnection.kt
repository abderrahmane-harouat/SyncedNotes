package com.syncednotes.android

import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.editor.Key

/**
 * The keyboard's door into the engine, implemented directly on [InputConnection]: no Editable,
 * no BaseInputConnection. Every call maps onto one [Composer]
 * operation, so the document stays the single source of truth (Samsung keeps an Editable mirror
 * and syncs it both ways; with no mirror there is nothing to keep in sync).
 */
class NoteInputConnection(private val view: NoteCanvasView, private val composer: Composer) : InputConnection {
    private fun now() = SystemClock.uptimeMillis()

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = composer.textBeforeCursor(n)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = composer.textAfterCursor(n)

    override fun getSelectedText(flags: Int): CharSequence? = composer.selectedText()

    override fun getCursorCapsMode(reqModes: Int): Int = view.capsMode(reqModes)

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText = ExtractedText().apply {
        text = composer.document.toString()
        startOffset = 0
        partialStartOffset = -1
        partialEndOffset = -1
        selectionStart = composer.selection.start
        selectionEnd = composer.selection.end
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        composer.deleteSurroundingText(beforeLength, afterLength, now())
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        composer.deleteSurroundingTextInCodePoints(beforeLength, afterLength, now())
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        composer.setComposingText(text, newCursorPosition, now())
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composer.setComposingRegion(start, end)
        return true
    }

    override fun finishComposingText(): Boolean {
        composer.finishComposingText()
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        composer.commitText(text, newCursorPosition, now())
        return true
    }

    override fun commitCompletion(text: CompletionInfo): Boolean {
        text.text?.let { composer.commitText(it, 1, now()) }
        return true
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo): Boolean = true

    override fun setSelection(start: Int, end: Int): Boolean {
        composer.setSelectionFromInput(start, end, now())
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        composer.onKey(Key.ENTER, shift = false, word = false, now = now())
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean {
        when (id) {
            android.R.id.selectAll -> composer.onKey(Key.SELECT_ALL, shift = false, word = false, now = now())
            // A keyboard's paste key: the clipboard read into the note, formatted or plain.
            android.R.id.paste -> view.paste()
            android.R.id.pasteAsPlainText -> view.paste(plainOnly = true)
            // Cut and copy wait for a decision about the clipboard: a keyboard may sync what is
            // copied to its cloud, which a note must never reach.
            else -> return false
        }
        return true
    }

    override fun beginBatchEdit(): Boolean {
        composer.beginBatchEdit()
        return true
    }

    override fun endBatchEdit(): Boolean {
        composer.endBatchEdit()
        return composer.inBatchEdit
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean = view.dispatchKeyEvent(event)

    override fun clearMetaKeyStates(states: Int): Boolean = false

    override fun reportFullscreenMode(enabled: Boolean): Boolean = false

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = view.requestCursorUpdates(cursorUpdateMode)

    override fun getHandler(): Handler? = null

    override fun closeConnection() {
        while (composer.inBatchEdit) composer.endBatchEdit()
        composer.finishComposingText()
    }

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
}
