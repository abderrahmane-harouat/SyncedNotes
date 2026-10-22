package com.syncednotes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Folders hold notes, and folders inside them. */
class FolderTest {
    private val passphrase = "made-up passphrase"
    private val place = temporaryFolder("folders")
    private fun store() = newStore(passphrase, place)

    private fun NoteStore.save(text: String, id: Int, folder: Id? = null, seconds: Long = 100) =
        save(note(text), id(id), title = text, words = text, now = at(seconds), folder = folder)

    private fun NoteStore.titles(scope: NoteStore.Scope = NoteStore.Scope.All) = summaries(scope = scope).map { it.title }

    @Test
    fun aFolderNameIsNotReadableOnDisk() {
        store().use { mine ->
            val secret = mine.createFolder("okapi-folder-7720")
            mine.createFolder("Inside", secret)
            mine.renameFolder(secret, "okapi-renamed-4410")
        }
        for ((file, bytes) in everythingOnDisk(place)) {
            for (name in listOf("okapi-folder-7720", "okapi-renamed-4410")) {
                assertFalse(bytes.contains(name.toByteArray()), "$name is readable in ${file.name}")
            }
        }
    }

    @Test
    fun aNewNoteGoesIntoTheFolderItWasMadeInAndStaysThereWhenSavedAgain(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        mine.save("Agenda", 1, work)
        mine.save("Agenda, edited", 1, seconds = 200)
        assertEquals(work, mine.folderOf(id(1)))
        assertEquals(listOf("Agenda, edited"), mine.titles(NoteStore.Scope.InFolder(work)))
    }

    @Test
    fun eachListShowsItsOwnNotesAndAllNotesShowsEveryOne(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        val meetings = mine.createFolder("Meetings", work)
        mine.save("Loose", 1, seconds = 100)
        mine.save("Agenda", 2, work, 200)
        mine.save("Minutes", 3, meetings, 300)
        assertEquals(listOf("Loose"), mine.titles(NoteStore.Scope.NoFolder))
        assertEquals(listOf("Agenda"), mine.titles(NoteStore.Scope.InFolder(work)))
        assertEquals(listOf("Minutes"), mine.titles(NoteStore.Scope.InFolder(meetings)))
        assertEquals(listOf("Minutes", "Agenda", "Loose"), mine.titles())
        assertEquals(mapOf(null to 1, work to 1, meetings to 1), mine.noteCounts())
    }

    @Test
    fun searchFindsNotesInEveryFolderOrOnlyInOne(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        mine.save("Budget at home", 1)
        mine.save("Budget at work", 2, work)
        assertEquals(setOf("Budget at home", "Budget at work"), mine.summaries("budget").map { it.title }.toSet())
        assertEquals(listOf("Budget at work"), mine.summaries("budget", NoteStore.Scope.InFolder(work)).map { it.title })
    }

    @Test
    fun foldersComeParentsFirstEachLevelInNameOrder(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        val home = mine.createFolder("home")
        mine.createFolder("Meetings", work)
        mine.createFolder("Ideas", work)
        mine.createFolder("Garden", home)
        assertEquals(listOf("home", "Garden", "Work", "Ideas", "Meetings"), mine.folders().map { it.name })
    }

    @Test
    fun aNoteMovesBetweenFoldersAndOutOfThem(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        val home = mine.createFolder("Home")
        mine.save("Keys", 1, work)
        mine.moveNote(id(1), home, at(500))
        assertEquals(home, mine.folderOf(id(1)))
        assertEquals(at(500), mine.lastChanged(id(1)))
        assertEquals(at(100), mine.summaries().first().updated)
        mine.moveNote(id(1), null)
        assertEquals(listOf("Keys"), mine.titles(NoteStore.Scope.NoFolder))
        val e = assertFailsWith<NoteStore.StoreException> { mine.moveNote(id(1), id(99)) }
        assertEquals(NoteStore.StoreException.Reason.NO_SUCH_FOLDER, e.reason)
    }

    @Test
    fun aFolderMovesWithEverythingInItButNeverIntoItself(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        val meetings = mine.createFolder("Meetings", work)
        val home = mine.createFolder("Home")
        mine.save("Minutes", 1, meetings)
        mine.moveFolder(work, home)
        assertEquals(listOf("Home", "Work", "Meetings"), mine.folders().map { it.name })
        assertEquals(listOf("Minutes"), mine.titles(NoteStore.Scope.InFolder(meetings)))
        for ((moved, into) in listOf(work to work, home to meetings)) {
            val e = assertFailsWith<NoteStore.StoreException> { mine.moveFolder(moved, into) }
            assertEquals(NoteStore.StoreException.Reason.FOLDER_INSIDE_ITSELF, e.reason)
        }
        mine.moveFolder(work, null)
        assertNull(mine.folders().first { it.id == work }.parent)
    }

    @Test
    fun aFolderIsRenamed(): Unit = store().use { mine ->
        val work = mine.createFolder("Wrok")
        mine.renameFolder(work, "Work")
        assertEquals(listOf("Work"), mine.folders().map { it.name })
    }

    @Test
    fun foldersNumberedInTheirNamesComeInFindersOrder(): Unit = store().use { mine ->
        for (name in listOf("Folder 10", "folder 2", "Folder 1")) mine.createFolder(name)
        assertEquals(listOf("Folder 1", "folder 2", "Folder 10"), mine.folders().map { it.name })
    }

    @Test
    fun deletingAFolderSendsItsNotesAndThoseOfTheFoldersInsideToTheTrash(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        val meetings = mine.createFolder("Meetings", work)
        val home = mine.createFolder("Home")
        mine.save("Agenda", 1, work)
        mine.save("Minutes", 2, meetings)
        mine.save("Garden", 3, home)
        mine.save("Already trashed", 4, work)
        mine.moveToTrash(id(4), at(50))
        assertEquals(2, mine.noteCountDeletingFolder(work))

        assertEquals(2, mine.deleteFolder(work, at(600)))
        assertEquals(listOf("Home"), mine.folders().map { it.name })
        assertEquals(listOf("Garden"), mine.titles())
        assertEquals(setOf("Agenda", "Minutes", "Already trashed"), mine.trash().map { it.title }.toSet())
        // Nothing is lost: what was trashed keeps its content, and its own time in the Trash.
        assertEquals(note("Minutes"), mine.load(id(2)))
        assertEquals(at(50), mine.trash().first { it.title == "Already trashed" }.trashed)
    }

    @Test
    fun aNoteRestoredFromTheTrashGoesBackToItsFolderOrToNoneIfTheFolderIsGone(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        val home = mine.createFolder("Home")
        mine.save("Agenda", 1, work)
        mine.save("Garden", 2, home)
        mine.moveToTrash(id(2))
        mine.restoreFromTrash(id(2))
        assertEquals(home, mine.folderOf(id(2)))

        mine.deleteFolder(work)
        mine.restoreFromTrash(id(1))
        assertNull(mine.folderOf(id(1)))
        assertEquals(listOf("Agenda"), mine.titles(NoteStore.Scope.NoFolder))
    }

    @Test
    fun aBackupKeepsFoldersAndWhichNoteIsInWhich(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        val meetings = mine.createFolder("Meetings", work)
        mine.save("Agenda", 1, work)
        mine.save("Minutes", 2, meetings)
        mine.save("Loose", 3)
        val file = Backup.make(mine)

        newStore("another made-up one").use { theirs ->
            val result = Backup.restore(Backup.open(file, mine.vault), theirs)
            assertEquals(2, result.folders)
            assertEquals(mine.folders(), theirs.folders())
            assertEquals(listOf("Agenda"), theirs.titles(NoteStore.Scope.InFolder(work)))
            assertEquals(listOf("Minutes"), theirs.titles(NoteStore.Scope.InFolder(meetings)))
            assertEquals(listOf("Loose"), theirs.titles(NoteStore.Scope.NoFolder))
        }
    }

    @Test
    fun restoringABackupLeavesAFolderThatIsAlreadyHereAsItIs(): Unit = store().use { mine ->
        val work = mine.createFolder("Work")
        val file = Backup.make(mine)
        mine.renameFolder(work, "Work, renamed since")
        assertEquals(0, Backup.restore(Backup.open(file, mine.vault), mine).folders)
        assertEquals(listOf("Work, renamed since"), mine.folders().map { it.name })
    }

    @Test
    fun aBackupMadeBeforeFoldersRestoresEveryNoteIntoNoFolder(): Unit = store().use { mine ->
        mine.save("Old note", 1)
        val opened = Backup.open(Backup.make(mine), mine.vault).toBuilder().clearFolders()
        for (i in 0 until opened.notesCount) opened.setNotes(i, opened.getNotes(i).toBuilder().clearFolder())
        newStore(passphrase).use { theirs ->
            assertEquals(1, Backup.restore(opened.build(), theirs).added)
            assertEquals(listOf("Old note"), theirs.titles(NoteStore.Scope.NoFolder))
        }
    }

    @Test
    fun aDatabaseFromBeforeFoldersOpensWithEveryNoteInNoFolder() {
        // What happens to notes on the first unlock after that change.
        store().use { mine ->
            mine.save("Kept through the upgrade", 1)
            mine.moveToTrash(id(1))
            mine.save("Also kept", 2)
            mine.database.createStatement().use {
                it.execute("ALTER TABLE notes DROP COLUMN folder")
                it.execute("DROP TABLE folders")
                it.execute("DELETE FROM grdb_migrations WHERE identifier LIKE 'v4%'")
            }
        }
        NoteStore(place, unlockVault(place, passphrase)).use { upgraded ->
            assertEquals(listOf("Also kept"), upgraded.titles(NoteStore.Scope.NoFolder))
            assertEquals(listOf("Kept through the upgrade"), upgraded.trash().map { it.title })
            assertTrue(upgraded.folders().isEmpty())
            upgraded.restoreFromTrash(id(1))
            assertEquals(note("Kept through the upgrade"), upgraded.load(id(1)))
        }
    }
}
