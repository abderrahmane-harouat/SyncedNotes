package com.syncednotes.mac

import java.awt.Component
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * For a run from the build on made-up notes only: clicks into the note and types, through the
 * window's own event queue, so the path typing takes inside the app can be checked in a
 * screenshot without sending a single keystroke to the Mac (where it could land in another
 * app). Main.kt refuses it with the real notes.
 */
object SelfTest {
    fun clickAndType(window: Window, x: Int, y: Int, text: String) {
        val queue = window.toolkit.systemEventQueue
        val target = SwingUtilities.getDeepestComponentAt(window, x, y) ?: window
        val point = SwingUtilities.convertPoint(window, x, y, target)
        val t = System.currentTimeMillis()
        queue.postEvent(MouseEvent(target, MouseEvent.MOUSE_PRESSED, t, InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON1))
        queue.postEvent(MouseEvent(target, MouseEvent.MOUSE_RELEASED, t + 1, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1))
        EventQueue.invokeLater {
            val owner: Component = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: target
            var at = t + 10
            for (ch in text) {
                if (ch == '\n') {
                    queue.postEvent(KeyEvent(owner, KeyEvent.KEY_PRESSED, at++, 0, KeyEvent.VK_ENTER, '\n'))
                    queue.postEvent(KeyEvent(owner, KeyEvent.KEY_RELEASED, at++, 0, KeyEvent.VK_ENTER, '\n'))
                    continue
                }
                val code = KeyEvent.getExtendedKeyCodeForChar(ch.code)
                queue.postEvent(KeyEvent(owner, KeyEvent.KEY_PRESSED, at++, 0, code, ch))
                queue.postEvent(KeyEvent(owner, KeyEvent.KEY_TYPED, at++, 0, KeyEvent.VK_UNDEFINED, ch))
                queue.postEvent(KeyEvent(owner, KeyEvent.KEY_RELEASED, at++, 0, code, ch))
            }
        }
    }
}
