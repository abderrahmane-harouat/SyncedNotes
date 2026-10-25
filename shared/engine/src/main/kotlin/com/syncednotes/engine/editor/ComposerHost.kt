package com.syncednotes.engine.editor

/**
 * What the engine needs from the platform. The Android view implements it; tests use a fake.
 * This is the whole of the engine's dependency on the outside world.
 */
interface ComposerHost {
    /** Something on screen changed; draw a frame soon. */
    fun requestFrame()

    fun showKeyboard()

    /** Selection or composing region changed; tell the input method. -1 means no composing. */
    fun selectionChanged(selectionStart: Int, selectionEnd: Int, composingStart: Int, composingEnd: Int)

    /** Text changed by something other than the input method (undo, redo); it must resync. */
    fun textChangedOutsideInput()

    /** The formatting under the caret may have changed; refresh the Text options panel. */
    fun formatChanged() {}

    /**
     * The language named at the top of a code block was clicked or tapped: show the languages
     * at ([x], [y]) in view pixels, just under the name, and call [Composer.setBlockLanguage]
     * with the one chosen for the block starting at [paragraph].
     */
    fun chooseCodeLanguage(paragraph: Int, current: com.syncednotes.engine.text.CodeLanguage, x: Float, y: Float) {}
}

enum class PointerAction { DOWN, MOVE, UP, CANCEL }

/** One finger, in view pixels, with the platform's monotonic time in milliseconds. */
data class PointerEvent(val action: PointerAction, val x: Float, val y: Float, val time: Long)

enum class Key { LEFT, RIGHT, UP, DOWN, HOME, END, PAGE_UP, PAGE_DOWN, BACKSPACE, DELETE, ENTER, TAB, UNDO, REDO, SELECT_ALL }
