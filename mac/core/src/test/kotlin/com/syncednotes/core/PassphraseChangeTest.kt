package com.syncednotes.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import syncednotes.note.v1.Embedded
import syncednotes.note.v1.Note
import syncednotes.note.v1.Paragraph
import syncednotes.note.v1.Picture
import syncednotes.note.v1.Run

/**
 * Changing the passphrase: everything comes through, only the new passphrase opens it, and
 * stopping at any point leaves notes that open with one of the two.
 */
class PassphraseChangeTest {
    private val parent = temporaryFolder("change")
    private val home = File(parent, "SyncedNotes")
    private val old = "made-up old passphrase"
    private val new = "made-up new passphrase"

    private fun note(text: String, picture: Id? = null): Note {
        val note = Note.newBuilder().setFormatVersion(1).addParagraphs(Paragraph.newBuilder().setText(text))
        if (picture != null) {
            note.addParagraphs(
                Paragraph.newBuilder().setText("￼").addRuns(
                    Run.newBuilder().setLength(1).setEmbedded(Embedded.newBuilder().setPicture(Picture.newBuilder().setId(picture).setType("public.png"))),
                ),
            )
        }
        return note.build()
    }

    /** A store with a bit of everything: a folder, a picture, the Trash, a deleted note. */
    private fun filledStore(): NoteStore {
        val store = NoteStore(home, createVault(home, old))
        val work = store.createFolder("Work")
        store.storePicture("picture-bytes-heron".toByteArray(), id(9))
        store.save(note("Door code 4812", picture = id(9)), id(1), title = "Door code 4812", words = "Door code 4812", folder = work)
        store.save(note("Shopping"), id(2), title = "Shopping", words = "Shopping")
        store.save(note("Old idea"), id(3), title = "Old idea", words = "Old idea")
        store.moveToTrash(id(3))
        store.save(note("Gone"), id(4), title = "Gone", words = "Gone")
        store.delete(id(4))
        return store
    }

    private fun change(store: NoteStore) {
        PassphraseChange.prepare(store, home, old, new)
        store.close()
        PassphraseChange.swap(home)
    }

    private fun leftovers(): List<String> = parent.list()!!.filter { it != "SyncedNotes" }

    private fun reopen(passphrase: String) = NoteStore(home, unlockVault(home, passphrase))

    private fun assertWrongPassphrase(block: () -> Unit) =
        assertEquals(LockException.Reason.WRONG_PASSPHRASE, assertFailsWith<LockException> { block() }.reason)

    @Test
    fun afterAChangeOnlyTheNewPassphraseOpensTheNotesAndEverythingCameThrough() {
        val store = filledStore()
        val folders = store.folders()
        val list = store.summaries()
        val trash = store.trash()
        change(store)

        assertWrongPassphrase { unlockVault(home, old) }
        reopen(new).use { reopened ->
            assertEquals(folders, reopened.folders())
            assertEquals(list, reopened.summaries())
            assertEquals(trash, reopened.trash())
            assertEquals(note("Door code 4812", picture = id(9)), reopened.load(id(1)))
            assertContentEquals("picture-bytes-heron".toByteArray(), reopened.picture(id(9)))
            assertTrue(reopened.isDeleted(id(4)))
            assertEquals(listOf("Door code 4812"), reopened.summaries("4812").map { it.title })
        }
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun theNewVaultHasANewIdSoOlderBackupsAskForTheOldPassphrase() {
        val store = filledStore()
        val oldId = store.vault.header.id
        val backup = Backup.make(store)
        change(store)
        val vault = unlockVault(home, new)
        assertFalse(vault.header.id.contentEquals(oldId))
        assertEquals(LockException.Reason.WRONG_VAULT, assertFailsWith<LockException> { Backup.open(backup, vault) }.reason)
        assertEquals(3, Backup.open(backup, Vault.unlock(Backup.vaultHeader(backup), old)).notesCount)
    }

    @Test
    fun theWrongCurrentPassphraseChangesNothing(): Unit = filledStore().use { store ->
        assertWrongPassphrase { PassphraseChange.prepare(store, home, "not it at all", new) }
        assertTrue(leftovers().isEmpty())
        assertEquals(store.vault.header, unlockVault(home, old).header)
        assertEquals(note("Shopping"), store.load(id(2)))
    }

    @Test
    fun nothingReadableReachesTheDiskDuringOrAfterTheChange() {
        val store = filledStore()
        PassphraseChange.prepare(store, home, old, new)
        fun check() {
            for ((file, bytes) in everythingOnDisk(parent)) {
                for (secret in listOf("Door code 4812", "Shopping", "heron", "Work", "SQLite format 3")) {
                    assertFalse(bytes.contains(secret.toByteArray()), "$secret is readable in ${file.name}")
                }
            }
        }
        check()
        store.close()
        PassphraseChange.swap(home)
        check()
    }

    // --- Stopped part way ---

    @Test
    fun stoppedBeforeTheCopyWasCheckedTheOldPassphraseStillOpensEverything() {
        val store = filledStore()
        PassphraseChange.prepare(store, home, old, new)
        store.close()
        // As if the app had stopped while still writing the copy.
        Files.delete(PassphraseChange.checkedMark(PassphraseChange.staging(home)).toPath())

        assertTrue(PassphraseChange.finishInterruptedChange(home))
        assertTrue(leftovers().isEmpty())
        reopen(old).use { assertEquals(note("Door code 4812", picture = id(9)), it.load(id(1))) }
    }

    @Test
    fun stoppedAfterTheCopyWasCheckedTheChangeIsFinishedAtTheNextLaunch() {
        val store = filledStore()
        PassphraseChange.prepare(store, home, old, new)
        store.close()

        assertTrue(PassphraseChange.finishInterruptedChange(home))
        assertTrue(leftovers().isEmpty())
        reopen(new).use { assertEquals(note("Shopping"), it.load(id(2))) }
        assertWrongPassphrase { unlockVault(home, old) }
    }

    @Test
    fun stoppedBetweenTheTwoRenamesTheChangeIsFinishedAtTheNextLaunch() {
        val store = filledStore()
        PassphraseChange.prepare(store, home, old, new)
        store.close()
        Files.move(home.toPath(), PassphraseChange.replaced(home).toPath())

        assertTrue(PassphraseChange.finishInterruptedChange(home))
        assertTrue(leftovers().isEmpty())
        reopen(new).use { assertEquals(note("Shopping"), it.load(id(2))) }
    }

    @Test
    fun stoppedBeforeTheOldNotesWereDeletedTheyAreDeletedAtTheNextLaunch() {
        val store = filledStore()
        PassphraseChange.prepare(store, home, old, new)
        store.close()
        Files.move(home.toPath(), PassphraseChange.replaced(home).toPath())
        Files.move(PassphraseChange.staging(home).toPath(), home.toPath())

        assertTrue(PassphraseChange.finishInterruptedChange(home))
        assertTrue(leftovers().isEmpty())
        assertFalse(PassphraseChange.checkedMark(home).exists())
        reopen(new).use { assertEquals(note("Shopping"), it.load(id(2))) }
    }

    @Test
    fun aLaunchWithNoChangeUnderwayTouchesNothing() {
        filledStore().close()
        assertFalse(PassphraseChange.finishInterruptedChange(home))
        reopen(old).use { assertEquals(note("Shopping"), it.load(id(2))) }
    }
}
