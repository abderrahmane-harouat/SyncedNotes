package com.syncednotes.core

import com.google.protobuf.ByteString
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two devices holding the same vault, merging what each has (Docs/Sync.md, "Merging"). */
class SyncStoreTest {
    private val passphrase = "made-up passphrase"
    private val macFolder = temporaryFolder("sync-mac")
    private val phoneFolder = temporaryFolder("sync-phone")
    private val vault = createVault(macFolder, passphrase)
    private val mac = NoteStore(macFolder, vault)
    // The phone holds the Mac's vault header, as pairing leaves it.
    private val phone = NoteStore(phoneFolder.also { File(it, "vault.bin").writeBytes(vault.header.encoded) }, vault)

    private val later = at(1_000)

    /** Both sides send what the other needs until neither needs anything, as a session does. */
    private fun sync(now: Instant = later) {
        repeat(3) {
            for ((from, to) in listOf(mac to phone, phone to mac)) {
                from.recordsNeededBy(to.manifest()).forEach { to.apply(it, now) }
            }
        }
    }

    private fun NoteStore.titles(scope: NoteStore.Scope = NoteStore.Scope.All) = summaries(scope = scope).map { it.title }

    @Test
    fun aNoteWrittenOnOneDeviceArrivesOnTheOtherWithItsFolderAndSearch() {
        val work = mac.createFolder("Work", now = at(50))
        mac.save(note("Agenda for Monday"), id(1), now = at(100), folder = work)
        sync()
        assertEquals(note("Agenda for Monday"), phone.load(id(1)))
        assertEquals(listOf("Work"), phone.folders().map { it.name })
        assertEquals(listOf("Agenda for Monday"), phone.titles(NoteStore.Scope.InFolder(work)))
        assertEquals(listOf("Agenda for Monday"), phone.summaries(search = "mon").map { it.title })
        assertEquals(at(100), phone.lastEdited(id(1)))
        assertEquals(at(100), phone.lastChanged(id(1)))
    }

    @Test
    fun eachNoteIsDecidedOnItsOwnAndTheLaterEditWins() {
        mac.save(note("A"), id(1), now = at(100))
        mac.save(note("B"), id(2), now = at(100))
        sync()
        mac.save(note("A, edited on the Mac"), id(1), now = at(200))
        phone.save(note("B, edited on the phone"), id(2), now = at(210))
        phone.save(note("A, edited on the phone earlier"), id(1), now = at(150))
        sync()
        for (device in listOf(mac, phone)) {
            assertEquals(note("A, edited on the Mac"), device.load(id(1)))
            assertEquals(note("B, edited on the phone"), device.load(id(2)))
        }
    }

    @Test
    fun aDeletionNewerThanTheEditDeletesTheNoteAndOnATieTheDeletionWins() {
        mac.save(note("Old"), id(1), now = at(100))
        mac.save(note("Tied"), id(2), now = at(100))
        sync()
        phone.delete(id(1), now = at(300))
        mac.save(note("Old, edited before the deletion"), id(1), now = at(200))
        phone.delete(id(2), now = at(400))
        mac.save(note("Tied, edited at the same moment"), id(2), now = at(400))
        sync()
        for (device in listOf(mac, phone)) {
            assertTrue(device.isDeleted(id(1)))
            assertTrue(device.isDeleted(id(2)))
            assertTrue(device.titles().isEmpty())
            assertTrue(device.summaries(search = "edited").isEmpty())
        }
    }

    @Test
    fun anEditNewerThanTheDeletionBringsTheNoteBack() {
        mac.save(note("Kept"), id(1), now = at(100))
        sync()
        mac.delete(id(1), now = at(200))
        phone.save(note("Kept, edited after"), id(1), now = at(300))
        sync()
        assertEquals(note("Kept, edited after"), mac.load(id(1)))
        assertFalse(mac.isDeleted(id(1)))
    }

    @Test
    fun theTrashTravelsWithTheNote() {
        mac.save(note("Thrown away"), id(1), now = at(100))
        sync()
        mac.moveToTrash(id(1), now = at(200))
        sync()
        assertEquals(listOf("Thrown away"), phone.trash().map { it.title })
        phone.restoreFromTrash(id(1), now = at(300))
        sync()
        assertEquals(listOf("Thrown away"), mac.titles())
    }

    @Test
    fun aDeletedFolderIsDeletedOnTheOtherDeviceInsteadOfComingBack() {
        val old = mac.createFolder("Old", now = at(100))
        mac.save(note("In it"), id(1), now = at(100), folder = old)
        sync()
        phone.deleteFolder(old, now = at(200))
        sync()
        for (device in listOf(mac, phone)) {
            assertTrue(device.folders().isEmpty())
            assertEquals(listOf("In it"), device.trash().map { it.title })
        }
        // And stays deleted when the Mac sends its manifest again.
        sync(at(5_000))
        assertTrue(phone.folders().isEmpty())
    }

    @Test
    fun aRenamedFolderKeepsTheLaterName() {
        val folder = mac.createFolder("Ideas", now = at(100))
        sync()
        mac.renameFolder(folder, "Ideas on the Mac", now = at(200))
        phone.renameFolder(folder, "Ideas on the phone", now = at(300))
        sync()
        assertEquals(listOf("Ideas on the phone"), mac.folders().map { it.name })
    }

    @Test
    fun aNoteInAFolderThatIsNotHereShowsInNoFolder() {
        val gone = mac.createFolder("Gone", now = at(100))
        sync()
        phone.deleteFolder(gone, now = at(200))
        // Made in the folder on the Mac before the deletion reached it.
        mac.save(note("Late"), id(1), now = at(250), folder = gone)
        sync()
        for (device in listOf(mac, phone)) {
            assertEquals(listOf("Late"), device.titles(NoteStore.Scope.NoFolder))
            assertNull(device.folderOf(id(1)))
            assertEquals(mapOf<Id?, Int>(null to 1), device.noteCounts())
        }
    }

    @Test
    fun twoFoldersMovedIntoEachOtherAreStillShown() {
        val a = mac.createFolder("A", now = at(100))
        val b = mac.createFolder("B", now = at(100))
        sync()
        mac.moveFolder(a, b, now = at(200))
        phone.moveFolder(b, a, now = at(210))
        sync()
        for (device in listOf(mac, phone)) assertEquals(setOf("A", "B"), device.folders().map { it.name }.toSet())
    }

    @Test
    fun aRecordDatedFarAheadOfThisClockIsIgnored() {
        mac.save(note("From the future"), id(1), now = later.plusSeconds(MAX_CLOCK_SKEW_SECONDS + 60))
        sync(later)
        assertTrue(phone.titles().isEmpty())
    }

    @Test
    fun aNoteLockedWithAnotherVaultIsRefusedAndNothingIsWritten() {
        val strangerFolder = temporaryFolder("sync-stranger")
        NoteStore(strangerFolder, createVault(strangerFolder, "another made-up passphrase")).use { stranger ->
            stranger.save(note("Not ours"), id(1), now = at(100))
            mac.save(note("Ours"), id(2), now = at(100))
            val records = stranger.recordsNeededBy(phone.manifest()).single().toBuilder()
                .addAllNotes(mac.recordsNeededBy(phone.manifest()).single().notesList).build()
            assertFailsWith<LockException> { phone.apply(records, later) }
            assertTrue(phone.titles().isEmpty())
        }
    }

    @Test
    fun aPictureTravelsLockedAndOpensOnTheOtherDevice() {
        val bytes = ByteArray(5_000) { (it * 7).toByte() }
        mac.storePicture(bytes, id(9))
        sync()
        assertContentEquals(bytes, phone.picture(id(9)))
        assertFalse(phone.pictureFile(id(9))!!.readBytes().contains(bytes.copyOf(64)))
    }

    @Test
    fun manyLargeRecordsGoInSeveralMessages() {
        repeat(4) { mac.storePicture(ByteArray(3_000) { b -> (b + it).toByte() }, id(10 + it)) }
        val messages = mac.recordsNeededBy(phone.manifest(), budget = 7_000)
        assertTrue(messages.size >= 2)
        assertEquals(4, messages.sumOf { it.picturesCount })
    }

    @Test
    fun theOtherSideIsOnlyAskedWhenItHasSomethingNewer() {
        mac.save(note("Same"), id(1), now = at(100))
        sync()
        assertFalse(phone.hasRecordsNeededBy(phone.manifest(), mac.manifest()))
        mac.save(note("Newer"), id(1), now = at(200))
        assertTrue(phone.hasRecordsNeededBy(phone.manifest(), mac.manifest()))
    }

    @Test
    fun theDevicesIdentityAndPeersAreKeptInTheLockedDatabase() {
        val identity = SyncIdentity(id(1), "made-up Mac", ByteArray(32) { 3 }, ByteArray(91) { 4 })
        mac.saveSyncIdentity(identity)
        val peer = SyncPeer(id(2), "made-up phone", ByteString.copyFrom(ByteArray(91) { 5 }), at(100))
        mac.savePeer(peer)
        assertEquals(id(1), mac.syncIdentity()!!.deviceId)
        assertContentEquals(identity.privateKey, mac.syncIdentity()!!.privateKey)
        assertEquals(listOf(peer), mac.peers())
        mac.removePeer(id(2))
        assertTrue(mac.peers().isEmpty())
    }
}
