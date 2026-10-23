package com.syncednotes.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The encrypted database, and what actually reaches the disk. */
class NoteStoreTest {
    private val folder = temporaryFolder("notestore")
    private val passphrase = "made-up passphrase"
    private fun newStore() = newStore(passphrase, folder)

    @Test
    fun aSavedNoteComesBackExactlyAfterTheAppIsOpenedAgain() {
        newStore().use { it.save(note("Door code 4812"), id(1), title = "Door code", words = "Door code 4812") }
        NoteStore(folder, unlockVault(folder, passphrase)).use { reopened ->
            assertEquals(note("Door code 4812"), reopened.load(id(1)))
            assertEquals(listOf("Door code"), reopened.summaries().map { it.title })
        }
    }

    @Test
    fun nothingReadableReachesTheDiskNotEvenTheTitleOrTheSearchWords() {
        newStore().use { store ->
            store.save(note("zebra-password-9931"), id(2), title = "giraffe-title", words = "zebra-password-9931")
            store.storePicture("PNG-looking-bytes-pelican".toByteArray(), id(3))
        }
        for ((file, bytes) in everythingOnDisk(folder)) {
            for (secret in listOf("zebra-password-9931", "giraffe-title", "pelican", "SQLite format 3")) {
                assertFalse(bytes.contains(secret.toByteArray()), "$secret is readable in ${file.name}")
            }
        }
    }

    @Test
    fun anotherVaultsKeysCannotOpenTheDatabase() {
        newStore().close()
        val e = assertFailsWith<NoteStore.StoreException> { NoteStore(folder, Vault.create(passphrase)) }
        assertEquals(NoteStore.StoreException.Reason.CANNOT_OPEN, e.reason)
    }

    @Test
    fun theListIsNewestFirst(): Unit = newStore().use { store ->
        store.save(note("a"), id(4), title = "older", words = "a", now = at(100))
        store.save(note("b"), id(5), title = "newer", words = "b", now = at(200))
        assertEquals(listOf("newer", "older"), store.summaries().map { it.title })
    }

    @Test
    fun savingAgainReplacesTheNoteButKeepsWhenItWasCreated(): Unit = newStore().use { store ->
        store.save(note("first"), id(6), now = at(100))
        store.save(note("second"), id(6), now = at(200))
        assertEquals(note("second"), store.load(id(6)))
        assertEquals(1, store.summaries().size)
        assertEquals(at(100), store.everyNote().single().created)
    }

    @Test
    fun aDeletedNoteLeavesTheListButKeepsAMarkForTheOtherDevice(): Unit = newStore().use { store ->
        store.save("gone soon", 7)
        store.delete(id(7))
        assertTrue(store.summaries().isEmpty())
        assertTrue(store.isDeleted(id(7)))
        assertEquals(NoteStore.StoreException.Reason.NO_SUCH_NOTE, assertFailsWith<NoteStore.StoreException> { store.load(id(7)) }.reason)
        assertTrue(store.summaries("gone").isEmpty())
    }

    @Test
    fun searchFindsNotesByTheStartOfTheirWordsInAnyScript(): Unit = newStore().use { store ->
        store.save(note("x"), id(8), title = "shopping", words = "Buy apples and pears")
        store.save(note("y"), id(9), title = "arabic", words = "ملاحظة عن التفاح")
        assertEquals(listOf("shopping"), store.summaries("app").map { it.title })
        assertEquals(listOf("arabic"), store.summaries("التفا").map { it.title })
        assertTrue(store.summaries("bananas").isEmpty())
    }

    @Test
    fun aPictureIsALockedFileWithARandomNameAndComesBackUnchanged(): Unit = newStore().use { store ->
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        store.storePicture(bytes, id(10))
        assertContentEquals(bytes, store.picture(id(10)))
        val file = store.pictureFile(id(10))!!
        assertFalse(file.name.contains(id(10).hex))
        assertContentEquals("SNL1".toByteArray(), file.readBytes().copyOf(4))
    }

    @Test
    fun storingThePictureAgainKeepsTheOneFile(): Unit = newStore().use { store ->
        store.storePicture(byteArrayOf(1, 2, 3), id(11))
        store.storePicture(byteArrayOf(1, 2, 3), id(11))
        assertEquals(1, everythingOnDisk(folder).count { it.first.parentFile.name == "pictures" })
    }

    @Test
    fun aDatabaseFromBeforeTheChangedTimeOpensWithEveryNoteAndGainsIt() {
        // What happens to notes on the first unlock after that change: the upgrade must keep
        // every note and give each a change time equal to its edit time.
        newStore().use { store ->
            store.save(note("Kept through the upgrade"), id(20), title = "Kept", words = "Kept", now = at(100))
            // Back into the older shape: no `changed` column, and the upgrade not yet run.
            store.update("ALTER TABLE notes DROP COLUMN changed")
            store.update("DELETE FROM grdb_migrations WHERE identifier LIKE 'v2%'")
        }
        NoteStore(folder, unlockVault(folder, passphrase)).use { upgraded ->
            assertEquals(note("Kept through the upgrade"), upgraded.load(id(20)))
            assertEquals(at(100), upgraded.lastChanged(id(20)))
            assertEquals(listOf("Kept"), upgraded.summaries().map { it.title })
        }
    }
}
