package com.syncednotes.mac

import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.editor.Key
import java.awt.event.KeyEvent

/**
 * The Mac's keys for moving and editing, mapped onto the engine's. Called with the window's own
 * key events by [MacTextInput], only while the note has the keyboard. Bold, italic and the
 * other styles are the Format menu's, with their usual keys.
 */
object MacKeys {
    /** Whether the key was the note's; it is then used up. */
    fun handle(composer: Composer, event: KeyEvent): Boolean {
        val t = now()
        val shift = event.isShiftDown
        if (event.isMetaDown) {
            when (event.keyCode) {
                KeyEvent.VK_Z -> composer.onKey(if (shift) Key.REDO else Key.UNDO, false, false, t)
                KeyEvent.VK_A -> composer.onKey(Key.SELECT_ALL, false, false, t)
                KeyEvent.VK_C -> copy(composer)
                KeyEvent.VK_X -> if (shift) return false else if (copy(composer) && composer.editable) composer.onKey(Key.BACKSPACE, false, false, t)
                // Formatted as it was where it was copied; ⌥⇧⌘V, Paste and Match Style, as plain text.
                KeyEvent.VK_V -> composer.paste(Pasteboard.paste(), if (shift && event.isAltDown) null else Pasteboard.pasteHtml(), t)
                // ⌘← and ⌘→ go to the ends of the line, ⌘↑ and ⌘↓ to the ends of the note.
                KeyEvent.VK_LEFT -> composer.onKey(Key.HOME, shift, false, t)
                KeyEvent.VK_RIGHT -> composer.onKey(Key.END, shift, false, t)
                KeyEvent.VK_UP -> composer.onKey(Key.HOME, shift, true, t)
                KeyEvent.VK_DOWN -> composer.onKey(Key.END, shift, true, t)
                // ⌘⌫ deletes back to the start of the line, as everywhere on the Mac.
                KeyEvent.VK_BACK_SPACE -> {
                    composer.onKey(Key.HOME, true, false, t)
                    composer.onKey(Key.BACKSPACE, false, false, t)
                }
                else -> return false
            }
            return true
        }
        if (event.isControlDown) return false
        val key = when (event.keyCode) {
            KeyEvent.VK_LEFT -> Key.LEFT
            KeyEvent.VK_RIGHT -> Key.RIGHT
            KeyEvent.VK_UP -> Key.UP
            KeyEvent.VK_DOWN -> Key.DOWN
            KeyEvent.VK_HOME -> Key.HOME
            KeyEvent.VK_END -> Key.END
            KeyEvent.VK_PAGE_UP -> Key.PAGE_UP
            KeyEvent.VK_PAGE_DOWN -> Key.PAGE_DOWN
            KeyEvent.VK_BACK_SPACE -> Key.BACKSPACE
            KeyEvent.VK_DELETE -> Key.DELETE
            KeyEvent.VK_ENTER -> Key.ENTER
            KeyEvent.VK_TAB -> if (shift) null else Key.TAB
            else -> return false
        }
        // In a table, Tab goes on to the next cell and Shift-Tab back, as in Notion.
        if (event.keyCode == KeyEvent.VK_TAB && composer.inTable) {
            composer.onKey(Key.TAB, shift, false, t)
            return true
        }
        // Shift-Tab steps a list item or indent back out, as Tab steps it in.
        if (key == null) {
            composer.outdent(t)
            return true
        }
        if (key == Key.TAB && composer.formatState().list != com.syncednotes.engine.text.ListType.NONE) {
            composer.indent(t)
            return true
        }
        // Option moves and deletes by words, as everywhere on the Mac.
        composer.onKey(key, shift, event.isAltDown, t)
        return true
    }

    /** Copies the selection as plain text, for this Mac only; false when nothing is selected. */
    private fun copy(composer: Composer): Boolean {
        val text = composer.selectedText() ?: return false
        Pasteboard.copy(text)
        return true
    }
}
