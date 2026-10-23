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
            NotesModel.Phase.Unlocked -> Row(Modifier.fillMaxSize()) {
                Sidebar(
                    model, onDeleteFolder = { question = Question.DeleteFolder(it) }, onExport = onExport, onImport = onImport,
                    modifier = Modifier.width(214.dp),
                )
                Divider()
                NoteList(
                    model,
                    onConfirmDelete = { question = Question.DeleteNotes(it) },
                    onConfirmEmptyTrash = { question = Question.EmptyTrash },
                    modifier = Modifier.width(272.dp),
                )
                Divider()
                EditorPane(model, editor, appearance, onAppearance, onConfirmDelete = { question = Question.DeleteNotes(it) }, modifier = Modifier.weight(1f))
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
