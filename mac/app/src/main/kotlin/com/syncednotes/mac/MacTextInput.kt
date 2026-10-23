package com.syncednotes.mac

import com.syncednotes.engine.editor.Composer
import java.awt.AWTEvent
import java.awt.event.AWTEventListener
import java.awt.event.InputMethodEvent
import java.awt.event.KeyEvent
import java.text.CharacterIterator

/**
 * Text typed into the note, taken straight from the window's events, as the phone's canvas
 * takes it straight from the keyboard. On the Mac, letters arrive as typed-key events, and
 * anything an input method builds (an accent from a dead key or a held key, an emoji from the
 * picker, a word in Chinese) as input-method events; Compose hands both only to its own text
 * fields, so the note listens for them itself, and only while it has the keyboard. Keys that
 * move or delete (arrows, Return, Backspace, ⌘Z) are the note's own the same way ([MacKeys]):
 * one path for everything the note receives, as on the phone.
 */
class MacTextInput(private val composer: () -> Composer?) : AWTEventListener {
    override fun eventDispatched(event: AWTEvent) {
        val c = composer() ?: return
        if (!c.focused) return
        when (event) {
            is KeyEvent -> when (event.id) {
                KeyEvent.KEY_PRESSED -> if (MacKeys.handle(c, event)) event.consume()
                KeyEvent.KEY_TYPED -> if (typed(c, event)) event.consume()
            }
            is InputMethodEvent -> if (event.id == InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) {
                inputMethod(c, event)
                event.consume()
            }
        }
    }

    private fun typed(c: Composer, event: KeyEvent): Boolean {
        val ch = event.keyChar
        // Command and Control are shortcuts; control characters are keys MacKeys handles.
        if (event.isMetaDown || event.isControlDown || ch == KeyEvent.CHAR_UNDEFINED || ch < ' ' || ch == '\u007f') return false
        c.type(ch.toString(), now())
        return true
    }

    /**
     * The input method's text so far: its first committed characters are final, the rest is
     * still being composed and shown underlined until it is.
     */
    private fun inputMethod(c: Composer, event: InputMethodEvent) {
        val text = StringBuilder()
        event.text?.let { it ->
            var ch = it.first()
            while (ch != CharacterIterator.DONE) {
                text.append(ch)
                ch = it.next()
            }
        }
        val committed = text.substring(0, event.committedCharacterCount.coerceIn(0, text.length))
        val composing = text.substring(committed.length)
        val t = now()
        if (committed.isNotEmpty()) c.commitText(committed, 1, t)
        if (composing.isNotEmpty() || (committed.isEmpty() && c.composingStart >= 0)) c.setComposingText(composing, 1, t)
    }
}
