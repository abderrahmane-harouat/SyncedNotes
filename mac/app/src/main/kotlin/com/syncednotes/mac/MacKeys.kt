package com.syncednotes.mac

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.editor.Key
import androidx.compose.ui.input.key.Key as ComposeKey

/** The Mac's keys for moving and editing, mapped onto the engine's. */
object MacKeys {
    fun handle(composer: Composer, event: KeyEvent): Boolean {
        val awt = event.nativeKeyEvent as? java.awt.event.KeyEvent ?: return false
        val t = now()
        // A typed character arrives as its own event, after the key went down, with the
        // keyboard layout already applied: Arabic, accents and Shift come out right here.
        if (awt.id == java.awt.event.KeyEvent.KEY_TYPED) {
            val c = awt.keyChar
            if (event.isMetaPressed || event.isCtrlPressed || c == java.awt.event.KeyEvent.CHAR_UNDEFINED || c < ' ' || c == '\u007f') return false
            composer.type(c.toString(), t)
            return true
        }
        if (event.type != KeyEventType.KeyDown) return false
        val shift = event.isShiftPressed
        val command = event.isMetaPressed
        val option = event.isAltPressed
        if (command) {
            when (event.key) {
                // Bold, italic and the other styles are the Format menu's, with these same keys.
                ComposeKey.Z -> composer.onKey(if (shift) Key.REDO else Key.UNDO, false, false, t)
                ComposeKey.A -> composer.onKey(Key.SELECT_ALL, false, false, t)
                ComposeKey.C -> copy(composer)
                ComposeKey.X -> if (shift) return false else if (copy(composer) && composer.editable) composer.onKey(Key.BACKSPACE, false, false, t)
                ComposeKey.V -> Pasteboard.paste()?.let { composer.type(it.replace("\r\n", "\n").replace('\r', '\n'), t) }
                // ⌘← and ⌘→ go to the ends of the line, ⌘↑ and ⌘↓ to the ends of the note.
                ComposeKey.DirectionLeft -> composer.onKey(Key.HOME, shift, false, t)
                ComposeKey.DirectionRight -> composer.onKey(Key.END, shift, false, t)
                ComposeKey.DirectionUp -> composer.onKey(Key.HOME, shift, true, t)
                ComposeKey.DirectionDown -> composer.onKey(Key.END, shift, true, t)
                else -> return false
            }
            return true
        }
        val key = when (event.key) {
            ComposeKey.DirectionLeft -> Key.LEFT
            ComposeKey.DirectionRight -> Key.RIGHT
            ComposeKey.DirectionUp -> Key.UP
            ComposeKey.DirectionDown -> Key.DOWN
            ComposeKey.MoveHome -> Key.HOME
            ComposeKey.MoveEnd -> Key.END
            ComposeKey.PageUp -> Key.PAGE_UP
            ComposeKey.PageDown -> Key.PAGE_DOWN
            ComposeKey.Backspace -> Key.BACKSPACE
            ComposeKey.Delete -> Key.DELETE
            ComposeKey.Enter, ComposeKey.NumPadEnter -> Key.ENTER
            ComposeKey.Tab -> Key.TAB
            else -> return false
        }
        // Option moves and deletes by words, as everywhere on the Mac.
        composer.onKey(key, shift, option, t)
        return true
    }

    /** Copies the selection as plain text, for this Mac only; false when nothing is selected. */
    private fun copy(composer: Composer): Boolean {
        val text = composer.selectedText() ?: return false
        Pasteboard.copy(text)
        return true
    }
}
