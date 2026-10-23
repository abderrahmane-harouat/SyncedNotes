package com.syncednotes.mac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syncednotes.core.Id
import com.syncednotes.mac.Appearance
import com.syncednotes.mac.Editor
import com.syncednotes.mac.NotesModel
import com.syncednotes.mac.WindowLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import java.awt.Cursor

/** What the window asks before doing something that cannot be undone. */
sealed interface Question {
    data class DeleteNotes(val ids: Set<Id>) : Question
    data class DeleteFolder(val id: Id) : Question
    data object EmptyTrash : Question
}

/** The lock screen until the notes are unlocked, then the notes in three columns, as in Apple Notes. */
@Composable
fun NotesWindow(
    model: NotesModel,
    editor: Editor,
    layout: WindowLayout,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val c = colors
    var question by remember { mutableStateOf<Question?>(null) }
    Box(Modifier.fillMaxSize().background(c.window)) {
        when (val phase = model.phase) {
            is NotesModel.Phase.Locked -> LockScreen(model, phase.firstRun)
            NotesModel.Phase.Unlocked -> Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    // The sidebar slides out and back in, keeping its width.
                    AnimatedVisibility(layout.sidebarShown, enter = expandHorizontally(), exit = shrinkHorizontally()) {
                        Row {
                            Sidebar(
                                model, onDeleteFolder = { question = Question.DeleteFolder(it) }, onExport = onExport, onImport = onImport,
                                modifier = Modifier.width(layout.sidebarWidth.dp),
                            )
                            Divider()
                        }
                    }
                    NoteList(
                        model,
                        onConfirmDelete = { question = Question.DeleteNotes(it) },
                        onConfirmEmptyTrash = { question = Question.EmptyTrash },
                        sidebarShown = layout.sidebarShown,
                        onToggleSidebar = layout::toggleSidebar,
                        modifier = Modifier.width(layout.listWidth.dp),
                    )
                    Divider()
                    EditorPane(model, editor, appearance, onAppearance, onConfirmDelete = { question = Question.DeleteNotes(it) }, modifier = Modifier.weight(1f))
                }
                // The edges between the columns can be dragged. Their handles lie over the lines,
                // wider than them, so they are easy to catch.
                val sidebarEdge = if (layout.sidebarShown) layout.sidebarWidth else 0f
                if (layout.sidebarShown) {
                    ColumnEdge(sidebarEdge, onDrag = { layout.sidebarWidth = (layout.sidebarWidth + it).coerceIn(WindowLayout.SIDEBAR_MIN, WindowLayout.SIDEBAR_MAX) }, onDone = layout::save)
                }
                ColumnEdge(
                    sidebarEdge + (if (layout.sidebarShown) 1f else 0f) + layout.listWidth,
                    onDrag = { layout.listWidth = (layout.listWidth + it).coerceIn(WindowLayout.LIST_MIN, WindowLayout.LIST_MAX) },
                    onDone = layout::save,
                )
            }
        }

        when (val q = question) {
            is Question.DeleteNotes -> {
                val many = q.ids.size > 1
                ConfirmSheet(
                    if (many) "Delete these ${q.ids.size} notes permanently?" else "Delete this note permanently?",
                    if (many) "They cannot be restored afterwards, except from a backup that has them."
                    else "It cannot be restored afterwards, except from a backup that has it.",
                    "Delete Permanently",
                    onConfirm = { model.deletePermanently(q.ids); question = null },
                    onCancel = { question = null },
                )
            }
            is Question.DeleteFolder -> {
                val count = model.noteCountDeletingFolder(q.id)
                val inside = if (model.folders.any { it.parent == q.id }) " and the folders inside it" else ""
                ConfirmSheet(
                    "Delete “${model.nameOf(q.id)}”?",
                    if (count == 0) "The folder$inside will be deleted. There are no notes in it."
                    else "The folder$inside will be deleted, and ${if (count == 1) "the note" else "its $count notes"} moved to the Trash, where ${if (count == 1) "it" else "they"} can be restored for 30 days.",
                    "Delete Folder",
                    onConfirm = { model.deleteFolder(q.id); question = null },
                    onCancel = { question = null },
                )
            }
            Question.EmptyTrash -> ConfirmSheet(
                "Empty the Trash?",
                "The ${model.trashCount} ${if (model.trashCount == 1) "note" else "notes"} in it will be deleted for good, and cannot be restored afterwards, except from a backup that has them.",
                "Empty Trash",
                onConfirm = { model.emptyTrash(); question = null },
                onCancel = { question = null },
            )
            null -> {}
        }
        if (model.changingPassphrase) ChangePassphraseSheet(model)
        if (model.importWaitingForPassphrase != null) BackupPassphraseSheet(model)
        model.problem?.let { AlertSheet("Something went wrong", it) { model.problem = null } }
        model.notice?.let { AlertSheet("Done", it) { model.notice = null } }
    }
}

@Composable
private fun Divider() = Box(Modifier.width(1.dp).fillMaxHeight().background(colors.separator))

/** A handle over the line at [x] points from the left, which resizes the column on its left. */
@Composable
private fun ColumnEdge(x: Float, onDrag: (Float) -> Unit, onDone: () -> Unit) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .offset(x = (x - 4f).dp)
            .width(8.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                rememberDraggableState { pixels -> onDrag(pixels / density) },
                Orientation.Horizontal,
                onDragStopped = { onDone() },
            ),
    )
}
