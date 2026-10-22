package com.syncednotes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Deleting a note moves it to the Trash first, from where it can come back. */
class TrashTest {
    @Test
    fun aTrashedNoteLeavesTheListAndSearchButKeepsItsContent(): Unit = newStore().use { mine ->
        mine.save("Door code 4812", 1)
        mine.moveToTrash(id(1), at(200))
        assertTrue(mine.summaries().isEmpty())
        assertTrue(mine.summaries("4812").isEmpty())
        assertEquals(listOf("Door code 4812"), mine.trash().map { it.title })
        assertEquals(at(200), mine.trash().first().trashed)
        assertEquals(note("Door code 4812"), mine.load(id(1)))
    }

    @Test
    fun aNoteRestoredFromTheTrashComesBackAsItWas(): Unit = newStore().use { mine ->
        mine.save("Door code 4812", 1, seconds = 100)
        mine.moveToTrash(id(1))
        mine.restoreFromTrash(id(1))
        assertTrue(mine.trash().isEmpty())
        assertEquals(listOf("Door code 4812"), mine.summaries().map { it.title })
        assertEquals(at(100), mine.summaries().first().updated)
        assertEquals(1, mine.summaries("4812").size)
    }

    @Test
    fun deletingFromTheTrashDeletesForGoodLeavingOnlyTheMark(): Unit = newStore().use { mine ->
        mine.save("Door code 4812", 1)
        mine.moveToTrash(id(1))
        mine.delete(id(1))
        assertTrue(mine.trash().isEmpty() && mine.summaries().isEmpty())
        assertTrue(mine.isDeleted(id(1)))
        assertFailsWith<NoteStore.StoreException> { mine.load(id(1)) }
    }

    @Test
    fun theTrashEmptiesItselfOfNotesOlderThanThirtyDays(): Unit = newStore().use { mine ->
        val day = 24L * 60 * 60
        mine.save("Old", 1)
        mine.save("Recent", 2)
        mine.moveToTrash(id(1), at(0))
        mine.moveToTrash(id(2), at(25 * day))
        assertEquals(1, mine.emptyTrash(NoteStore.TRASH_KEEPS_FOR_SECONDS, at(31 * day)))
        assertEquals(listOf("Recent"), mine.trash().map { it.title })
        assertTrue(mine.isDeleted(id(1)))
    }

    @Test
    fun emptyingTheTrashDeletesEverythingInIt(): Unit = newStore().use { mine ->
        mine.save("a", 1)
        mine.save("b", 2)
        mine.save("kept", 3)
        mine.moveToTrash(id(1))
        mine.moveToTrash(id(2))
        assertEquals(2, mine.emptyTrash())
        assertTrue(mine.trash().isEmpty())
        assertEquals(listOf("kept"), mine.summaries().map { it.title })
    }

    @Test
    fun notesInTheTrashAreInTheBackupAndGoBackToTheTrash() {
        val file = newStore().use { mine ->
            mine.save("Kept", 1)
            mine.save("Trashed", 2)
            mine.moveToTrash(id(2), at(300))
            Backup.make(mine)
        }
        newStore("made-up passphrase 2").use { fresh ->
            Backup.restore(Backup.open(file, Vault.unlock(Backup.vaultHeader(file), "made-up passphrase")), fresh)
            assertEquals(listOf("Kept"), fresh.summaries().map { it.title })
            assertEquals(listOf("Trashed"), fresh.trash().map { it.title })
            assertEquals(at(300), fresh.trash().first().trashed)
        }
    }
}
