package com.syncednotes.engine.editor

import com.syncednotes.engine.text.FormatSnapshot

/**
 * One undoable step: replacing [removed] at [start] with [inserted], and the selection and the
 * formatting on either side of it. A formatting-only step replaces nothing.
 *
 * Keeping whole formatting snapshots, rather than working out how each edit moved each style,
 * makes undo exact by construction: deleting a bold word and undoing brings the bold back.
 */
class Edit(
    var start: Int,
    var removed: String,
    var inserted: String,
    val selectionBefore: Selection,
    var selectionAfter: Selection,
    val formatBefore: FormatSnapshot,
    var formatAfter: FormatSnapshot,
    var time: Long,
)

/**
 * Undo and redo. Consecutive typing merges into one step per word, which is how Samsung's
 * TextHistoryManager behaves: undo takes back the last word, not the last letter, and an IME
 * rewriting its composing word in place never produces a step per suggestion.
 */
class History(initialFormat: FormatSnapshot, private val limit: Int = 200) {
    private val undo = ArrayList<Edit>()
    private val redo = ArrayList<Edit>()
    private var sealed = true

    /**
     * The document's formatting as of the newest step. Every formatting change is recorded, so
     * this is also the "before" of the next step and needs no fresh copy.
     */
    private var current: FormatSnapshot = initialFormat

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Ends the current step: the next edit starts a new one (cursor moved, text pasted...). */
    fun seal() {
        sealed = true
    }

    fun record(
        start: Int, removed: String, inserted: String,
        before: Selection, after: Selection, formatAfter: FormatSnapshot,
        time: Long, mergeable: Boolean,
    ) {
        redo.clear()
        val last = undo.lastOrNull()
        if (mergeable && !sealed && last != null && time - last.time < MERGE_WINDOW_MS && merge(last, start, removed, inserted)) {
            last.selectionAfter = after
            last.formatAfter = formatAfter
            last.time = time
            current = formatAfter
            return
        }
        undo += Edit(start, removed, inserted, before, after, current, formatAfter, time)
        current = formatAfter
        if (undo.size > limit) undo.removeAt(0)
        sealed = !mergeable || inserted.contains('\n')
    }

    private fun merge(last: Edit, start: Int, removed: String, inserted: String): Boolean {
        val lastEnd = last.start + last.inserted.length
        // The edit stays inside text this step inserted: typing on, or the IME rewriting it.
        if (start >= last.start && start + removed.length <= lastEnd &&
            last.inserted.regionMatches(start - last.start, removed, 0, removed.length)
        ) {
            // A new word after a space starts a new step.
            val local = start - last.start
            if (removed.isEmpty() && local == last.inserted.length && inserted.isNotEmpty() &&
                last.inserted.isNotEmpty() && last.inserted.last().isWhitespace() && !inserted.first().isWhitespace()
            ) return false
            last.inserted = last.inserted.substring(0, local) + inserted + last.inserted.substring(local + removed.length)
            return true
        }
        // Deleting next to a deletion step: backspace grows it leftwards, forward-delete rightwards.
        if (inserted.isEmpty() && removed.isNotEmpty() && last.inserted.isEmpty() && last.removed.isNotEmpty()) {
            if (start + removed.length == last.start) {
                last.start = start
                last.removed = removed + last.removed
                return true
            }
            if (start == last.start) {
                last.removed += removed
                return true
            }
        }
        return false
    }

    fun undo(): Edit? {
        val e = undo.removeLastOrNull() ?: return null
        redo += e
        sealed = true
        current = e.formatBefore
        return e
    }

    fun redo(): Edit? {
        val e = redo.removeLastOrNull() ?: return null
        undo += e
        sealed = true
        current = e.formatAfter
        return e
    }

    private companion object {
        const val MERGE_WINDOW_MS = 2000L
    }
}
