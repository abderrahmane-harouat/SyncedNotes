package com.syncednotes.mac

import com.syncednotes.core.NoteStore
import com.syncednotes.core.newId
import com.syncednotes.core.unlockVault
import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.editor.ComposerHost
import com.syncednotes.engine.editor.EditorStyle
import com.syncednotes.engine.editor.Key
import com.syncednotes.engine.text.Role
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.google.protobuf.ByteString
import syncednotes.note.v1.Embedded
import syncednotes.note.v1.Note
import syncednotes.note.v1.Paragraph
import syncednotes.note.v1.Picture
import syncednotes.note.v1.Run

/** The window's rules, with the real engine and the real store, in a temporary folder. */
class NotesModelTest {
    private val home = Files.createTempDirectory("syncednotes-model-").toFile().resolve("SyncedNotes")
    private val passphrase = "made-up passphrase"

    private object Host : ComposerHost {
        override fun requestFrame() {}
        override fun showKeyboard() {}
        override fun selectionChanged(selectionStart: Int, selectionEnd: Int, composingStart: Int, composingEnd: Int) {}
        override fun textChangedOutsideInput() {}
    }

    private val composer = Composer(AppFonts.family, EditorStyle.macLike(2f), Host).also { it.setViewport(1200, 800, 0f, 0f) }

    private fun unlocked(): NotesModel {
        val model = NotesModel(home, CoroutineScope(Dispatchers.Unconfined))
        model.editorAppeared(composer)
        model.create(passphrase, passphrase)
        return model
    }

    private fun reopened(): NoteStore = NoteStore(home, unlockVault(home, passphrase))

    @Test
    fun aNewNoteStartsAsItsTitleAndIsSavedUnderItsFirstLine() {
        val model = unlocked()
        model.newNote()
        assertEquals(Role.TITLE, composer.document.paragraphStyle(0).role)
        composer.type("Groceries", 0)
        composer.onKey(Key.ENTER, false, false, 10)
        composer.type("milk", 20)
        model.saveNow()
        assertEquals(Role.BODY, composer.document.paragraphStyle(1).role)
        assertEquals(listOf("Groceries"), model.summaries.map { it.title })
        model.lock()
        reopened().use { assertEquals("Groceries\nmilk", it.load(it.summaries().single().id).paragraphsList.joinToString("\n") { p -> p.text }) }
    }

    @Test
    fun aNoteLeftEmptyIsRemovedWhenAnotherIsChosen() {
        val model = unlocked()
        model.newNote()
        composer.type("Kept", 0)
        model.newNote()
        assertEquals(2, model.summaries.size)
        model.select(model.summaries.first { it.title == "Kept" }.id)
        assertEquals(listOf("Kept"), model.summaries.map { it.title })
    }

    @Test
    fun switchingNotesSavesTheOneBeingLeftAndShowsTheOther() {
        val model = unlocked()
        model.newNote()
        composer.type("First", 0)
        model.newNote()
        composer.type("Second", 0)
        val first = model.summaries.first { it.title == "First" }.id
        model.select(first)
        assertEquals("First", composer.document.toString())
        assertEquals(setOf("First", "Second"), model.summaries.map { it.title }.toSet())
    }

    @Test
    fun aNoteWithAPictureIsShownButNeverSavedOver() {
        val model = unlocked()
        val id = newId()
        val picture = Note.newBuilder().setFormatVersion(1).addParagraphs(Paragraph.newBuilder().setText("Photo")).addParagraphs(
            Paragraph.newBuilder().setText("￼").addRuns(
                Run.newBuilder().setLength(1).setEmbedded(Embedded.newBuilder().setPicture(Picture.newBuilder().setId(ByteString.copyFrom(ByteArray(16))).setType("public.png"))),
            ),
        ).build()
        model.lock()
        reopened().use { it.save(picture, id) }
        assertTrue(model.unlock(passphrase))
        model.select(id)
        assertFalse(model.document!!.editable)
        composer.type("typed over", 0)
        model.saveNow(force = true)
        model.select(null)
        model.lock()
        reopened().use { assertEquals(picture, it.load(id)) }
    }

    @Test
    fun aNoteInTheTrashCannotBeChanged() {
        val model = unlocked()
        model.newNote()
        composer.type("Old", 0)
        val id = model.document!!.id
        model.moveToTrash(setOf(id))
        model.show(NotesModel.Place.Trash)
        model.select(id)
        assertTrue(model.document!!.inTrash)
        assertFalse(composer.editable)
        composer.type("changed", 0)
        assertEquals("Old", composer.document.toString())
    }

    @Test
    fun aNewNoteInAFolderGoesIntoThatFolder() {
        val model = unlocked()
        model.newFolder()
        model.createFolder("Work")
        val folder = (model.place as NotesModel.Place.InFolder).id
        model.newNote()
        composer.type("Agenda", 0)
        model.saveNow()
        assertEquals(listOf("Work"), model.folders.map { it.name })
        assertEquals(folder, model.summaries.single().folder)
    }

    @Test
    fun lockingForgetsEveryNoteOnScreen() {
        val model = unlocked()
        model.newNote()
        composer.type("Secret", 0)
        model.lock()
        assertEquals(NotesModel.Phase.Locked(firstRun = false), model.phase)
        assertTrue(model.summaries.isEmpty())
        assertNull(model.document)
        assertEquals("", composer.document.toString())
    }

    @Test
    fun theWrongPassphraseIsRefusedWithoutAProblemToShow() {
        val model = unlocked()
        model.lock()
        assertFalse(model.unlock("not it"))
        assertNull(model.problem)
    }

    @Test
    fun aNewEngineForTheOtherAppearanceKeepsWhatWasJustTyped() {
        val model = unlocked()
        model.newNote()
        composer.type("Written just before switching to dark", 0)
        // No pause for the save: the switch comes at once.
        val dark = Composer(AppFonts.family, EditorStyle.macLike(2f, dark = true), Host).also { it.setViewport(1200, 800, 0f, 0f) }
        model.editorAppeared(dark)
        assertEquals("Written just before switching to dark", dark.document.toString())
    }

    @Test
    fun aNewFolderLeftWithoutANameIsNeverMade() {
        val model = unlocked()
        model.newFolder()
        model.createFolder("   ")
        model.newFolder()
        model.cancelNewFolder()
        assertTrue(model.folders.isEmpty())
        assertNull(model.namingNewFolderIn)
    }

    @Test
    fun deletingAnEmptyNoteRemovesItForGoodRatherThanToTheTrash() {
        val model = unlocked()
        model.newNote()
        composer.type("Kept", 0)
        model.newNote()
        val empty = model.document!!.id
        model.moveToTrash(setOf(empty))
        assertEquals(0, model.trashCount)
        assertEquals(listOf("Kept"), model.summaries.map { it.title })
        val kept = model.summaries.single().id
        model.moveToTrash(setOf(kept))
        assertEquals(1, model.trashCount)
    }

    @Test
    fun anEmptyNoteLeftBehindByACrashIsGoneAtTheNextUnlock() {
        val model = unlocked()
        model.newNote()
        composer.type("Kept", 0)
        model.newNote()
        // As if the app stopped here: the empty note was saved once, and never left.
        val again = NotesModel(home, CoroutineScope(Dispatchers.Unconfined))
        assertTrue(again.unlock(passphrase))
        assertEquals(listOf("Kept"), again.summaries.map { it.title })
    }
}
