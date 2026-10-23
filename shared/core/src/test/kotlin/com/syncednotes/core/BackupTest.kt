package com.syncednotes.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import syncednotes.note.v1.Embedded
import syncednotes.note.v1.Note
import syncednotes.note.v1.Paragraph
import syncednotes.note.v1.Picture
import syncednotes.note.v1.Run

/** Export and import: a whole vault's notes in one locked file, and back. */
class BackupTest {
    private fun note(text: String, picture: Id? = null): Note {
        val paragraph = Paragraph.newBuilder().setText(text)
        if (picture != null) {
            paragraph.text = text + "￼"
            paragraph.addRuns(
                Run.newBuilder().setStart(text.length).setLength(1).setEmbedded(
                    Embedded.newBuilder().setPicture(Picture.newBuilder().setId(picture).setType("public.png").setWidth(10).setHeight(10)),
                ),
            )
        }
        return Note.newBuilder().setFormatVersion(1).addParagraphs(paragraph).build()
    }

    private fun NoteStore.save(text: String, id: Int, seconds: Long, picture: Id? = null) {
        val stored = note(text, picture)
        save(stored, id(id), title = stored.title, words = stored.words.joinToString(""), now = at(seconds))
    }

    private fun openWith(file: ByteArray, passphrase: String) = Backup.open(file, Vault.unlock(Backup.vaultHeader(file), passphrase))

    @Test
    fun aBackupRestoresEveryNoteIntoAFreshVaultWithADifferentPassphrase() {
        val file = newStore("old made-up passphrase").use { old ->
            old.save("Door code 4812", 1, 100)
            old.save("Shopping", 2, 200)
            Backup.make(old)
        }
        newStore("new made-up passphrase").use { fresh ->
            val result = Backup.restore(openWith(file, "old made-up passphrase"), fresh)
            assertTrue(result.added == 2 && result.restored == 2 && result.keptNewer == 0)
            assertEquals(listOf("Shopping", "Door code 4812"), fresh.summaries().map { it.title })
            assertEquals(note("Door code 4812"), fresh.load(id(1)))
            assertEquals(1, fresh.summaries("4812").size)
        }
    }

    @Test
    fun restoredNotesKeepTheirOwnDates() {
        val file = newStore("made-up passphrase").use { old -> old.save("Written long ago", 1, 1_000); Backup.make(old) }
        newStore("made-up passphrase 2").use { fresh ->
            Backup.restore(openWith(file, "made-up passphrase"), fresh)
            assertEquals(at(1_000), fresh.summaries().first().updated)
        }
    }

    @Test
    fun theWrongPassphraseForTheBackupIsRefused() {
        val file = newStore("made-up passphrase").use(Backup::make)
        val e = assertFailsWith<LockException> { Vault.unlock(Backup.vaultHeader(file), "not the passphrase") }
        assertEquals(LockException.Reason.WRONG_PASSPHRASE, e.reason)
    }

    @Test
    fun aBackupFromThisVaultOpensWithTheKeysAlreadyInMemory(): Unit = newStore("made-up passphrase").use { mine ->
        mine.save("Here", 1, 100)
        val file = Backup.make(mine)
        assertEquals(mine.vault.header, Backup.vaultHeader(file))
        assertEquals(1, Backup.open(file, mine.vault).notesCount)
    }

    @Test
    fun aChangedByteAnywhereMakesTheWholeBackupRefused(): Unit = newStore("made-up passphrase").use { mine ->
        mine.save("Here", 1, 100)
        val file = Backup.make(mine)
        file[file.size - 30] = (file[file.size - 30].toInt() xor 1).toByte()
        assertEquals(LockException.Reason.DAMAGED, assertFailsWith<LockException> { Backup.open(file, mine.vault) }.reason)
    }

    @Test
    fun somethingThatIsNotABackupIsSaidToBeSo() {
        assertFailsWith<Backup.NotABackupException> { Backup.vaultHeader("hello, not a backup at all".toByteArray()) }
    }

    @Test
    fun nothingInTheBackupFileIsReadable(): Unit = newStore("made-up passphrase").use { mine ->
        mine.storePicture("pelican-picture-bytes".toByteArray(), id(9))
        mine.save("zebra-secret-9931", 1, 100, picture = id(9))
        val file = Backup.make(mine)
        for (secret in listOf("zebra-secret-9931", "pelican-picture-bytes", "public.png")) {
            assertFalse(file.contains(secret.toByteArray()), "$secret is readable in the backup")
        }
    }

    @Test
    fun picturesTravelInTheBackupAndComeBackAsTheirVeryBytes() {
        val picture = ByteArray(3000) { (it % 251).toByte() }
        val file = newStore("made-up passphrase").use { old ->
            old.storePicture(picture, id(9))
            old.save("With a picture", 1, 100, picture = id(9))
            Backup.make(old)
        }
        newStore("made-up passphrase 2").use { fresh ->
            assertEquals(1, Backup.restore(openWith(file, "made-up passphrase"), fresh).pictures)
            assertContentEquals(picture, fresh.picture(id(9)))
        }
    }

    @Test
    fun anOlderBackupNeverOverwritesNewerWriting(): Unit = newStore("made-up passphrase").use { mine ->
        mine.save("Version from Monday", 1, 100)
        val monday = Backup.make(mine)
        mine.save("Version from Tuesday", 1, 200)
        val result = Backup.restore(Backup.open(monday, mine.vault), mine)
        assertTrue(result.restored == 0 && result.keptNewer == 1)
        assertEquals(note("Version from Tuesday"), mine.load(id(1)))
    }

    @Test
    fun aNewerBackupBringsANoteUpToDate() {
        newStore("made-up passphrase").use { laptop ->
            laptop.save("Old", 1, 100)
            newStore("made-up passphrase 2").use { other ->
                other.save("Old", 1, 100)
                laptop.save("Newer", 1, 300)
                val result = Backup.restore(Backup.open(Backup.make(laptop), laptop.vault), other)
                assertTrue(result.updated == 1 && result.restored == 1)
                assertEquals(note("Newer"), other.load(id(1)))
            }
        }
    }

    @Test
    fun importingABackupBringsBackNotesDeletedAfterItWasMade(): Unit = newStore("made-up passphrase").use { mine ->
        // What a person does: export, delete everything, import, and expect the notes back.
        mine.save("First", 1, 100)
        mine.save("Second", 2, 110)
        mine.save("Third", 3, 120)
        val file = Backup.make(mine)
        for (i in 1..3) mine.delete(id(i), at(200))
        assertTrue(mine.summaries().isEmpty())

        val result = Backup.restore(Backup.open(file, mine.vault), mine, at(300))
        assertTrue(result.undeleted == 3 && result.restored == 3 && result.keptNewer == 0)
        assertEquals(setOf("First", "Second", "Third"), mine.summaries().map { it.title }.toSet())
        assertEquals(note("Second"), mine.load(id(2)))
    }

    @Test
    fun restoredNotesShowWhenTheyWereLastEditedNotWhenTheyWereRestored(): Unit = newStore("made-up passphrase").use { mine ->
        mine.save("Oldest", 1, 100)
        mine.save("Middle", 2, 200)
        mine.save("Newest", 3, 300)
        val file = Backup.make(mine)
        for (i in 1..3) mine.delete(id(i), at(400))
        Backup.restore(Backup.open(file, mine.vault), mine, at(500))
        val list = mine.summaries()
        assertEquals(listOf("Newest", "Middle", "Oldest"), list.map { it.title })
        assertEquals(listOf(at(300), at(200), at(100)), list.map { it.updated })
    }

    @Test
    fun aNoteBroughtBackFromDeletionStillCountsAsChangedAfterTheDeletion(): Unit = newStore("made-up passphrase").use { mine ->
        // So the other device, which also saw the deletion, does not delete it again on sync.
        mine.save("Brought back", 1, 100)
        val file = Backup.make(mine)
        mine.delete(id(1), at(200))
        Backup.restore(Backup.open(file, mine.vault), mine, at(300))
        assertEquals(at(300), mine.lastChanged(id(1)))
        assertEquals(at(100), mine.lastEdited(id(1)))
    }

    @Test
    fun importingAgainSetsBackTheDatesOfNotesAnEarlierImportRedated(): Unit = newStore("made-up passphrase").use { mine ->
        // The state the first version of restoring left behind: the right content, dated at
        // the import. Importing the same backup again repairs the dates and changes nothing else.
        mine.save("Same words", 1, 100)
        val file = Backup.make(mine)
        mine.save("Same words", 1, 900)
        val result = Backup.restore(Backup.open(file, mine.vault), mine, at(1000))
        assertTrue(result.unchanged == 1 && result.datesSetBack == 1 && result.restored == 0)
        assertEquals(at(100), mine.lastEdited(id(1)))
        assertEquals(note("Same words"), mine.load(id(1)))
    }

    @Test
    fun aNewNoteMadeAfterTheBackupIsLeftAlone(): Unit = newStore("made-up passphrase").use { mine ->
        mine.save("In the backup", 1, 100)
        val file = Backup.make(mine)
        mine.save("Written later", 2, 200)
        Backup.restore(Backup.open(file, mine.vault), mine)
        assertEquals(setOf("In the backup", "Written later"), mine.summaries().map { it.title }.toSet())
    }

    @Test
    fun deletedNotesAreNotInTheBackup(): Unit = newStore("made-up passphrase").use { mine ->
        mine.save("Kept", 1, 100)
        mine.save("Thrown away", 2, 100)
        mine.delete(id(2))
        assertEquals(listOf("Kept"), Backup.open(Backup.make(mine), mine.vault).notesList.map { it.note.title })
    }
}
