package com.syncednotes.core

import com.google.protobuf.ByteString
import java.time.Instant
import syncednotes.note.v1.BackupFolder
import syncednotes.note.v1.BackupNote
import syncednotes.note.v1.BackupPicture
import syncednotes.note.v1.Backup as StoredBackup

/**
 * A backup: every note and its pictures in one file, locked under the vault it came from.
 *
 * The file (LockFormat.md, "A backup file") is the vault's header followed by one locked item
 * of kind `backup`, holding a Protobuf `Backup`. Sealed as one, so nothing inside can be read
 * without the passphrase (not even how many notes there are or when they were written), and
 * changing any byte makes the whole file refuse to open.
 */
object Backup {
    private val MAGIC = "SNB1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1

    class NotABackupException(detail: String) : Exception(detail)

    /** What an import did. */
    data class Result(
        /** Notes this store had never had. */
        var added: Int = 0,
        /** Notes deleted here, brought back. */
        var undeleted: Int = 0,
        /** Notes here that the backup had a newer copy of. */
        var updated: Int = 0,
        /** Notes here that were changed after the backup was made, kept as they are. */
        var keptNewer: Int = 0,
        /** Notes here exactly as in the backup. */
        var unchanged: Int = 0,
        /** Of those, notes whose date had been moved to a later import, set back to the backup's. */
        var datesSetBack: Int = 0,
        var pictures: Int = 0,
        /** Folders this store did not have. */
        var folders: Int = 0,
    ) {
        val restored: Int get() = added + undeleted + updated
    }

    /** Every note in [store] that is not deleted, those in the Trash included, with the pictures they show. */
    fun make(store: NoteStore): ByteArray {
        val backup = StoredBackup.newBuilder().setFormatVersion(1)
        for (folder in store.folders()) {
            backup.addFolders(
                BackupFolder.newBuilder().setId(folder.id).setName(folder.name)
                    .setParent(folder.parent ?: ByteString.EMPTY).setCreated(NoteStore.seconds(folder.created)),
            )
        }
        val pictureIds = LinkedHashSet<Id>()
        for (entry in store.everyNote()) {
            val note = store.load(entry.id)
            backup.addNotes(
                BackupNote.newBuilder().setId(entry.id)
                    .setCreated(NoteStore.seconds(entry.created)).setUpdated(NoteStore.seconds(entry.updated))
                    .setTrashed(entry.trashed?.let(NoteStore::seconds) ?: 0.0)
                    .setFolder(entry.folder ?: ByteString.EMPTY)
                    .setNote(note),
            )
            pictureIds += note.pictureIds
        }
        for (id in pictureIds) {
            // A picture whose file is gone cannot be backed up; the note still points to it, as
            // it does here, so nothing more is lost than already was.
            val bytes = runCatching { store.picture(id) }.getOrNull() ?: continue
            backup.addPictures(BackupPicture.newBuilder().setId(id).setBytes(ByteString.copyFrom(bytes)))
        }
        return make(backup.build(), store.vault)
    }

    /** Locks [backup] under [vault] as a backup file's bytes. */
    fun make(backup: StoredBackup, vault: Vault, random: RandomBytes = RandomBytes.SYSTEM): ByteArray {
        val locked = vault.lock(backup.toByteArray(), ItemKind.BACKUP, random.next(16), random)
        return MAGIC + byteArrayOf(VERSION) + vault.header.encoded + locked
    }

    /**
     * The header of the vault a backup was made in: to tell whether it is this vault, whose
     * keys are already in memory, or another, whose passphrase has to be asked for.
     */
    fun vaultHeader(file: ByteArray): VaultHeader {
        if (file.size <= 5 + VaultHeader.SIZE || !file.startsWith(MAGIC)) throw NotABackupException("Not a SyncedNotes backup")
        if (file[4] != VERSION) throw NotABackupException("Backup version ${file[4]}")
        return VaultHeader.decode(file.copyOfRange(5, 5 + VaultHeader.SIZE))
    }

    /** Opens a backup with the keys of the vault it was made in. */
    fun open(file: ByteArray, vault: Vault): StoredBackup {
        val header = vaultHeader(file)
        if (!header.id.contentEquals(vault.header.id)) throw LockException(LockException.Reason.WRONG_VAULT)
        val item = vault.open(file.copyOfRange(5 + VaultHeader.SIZE, file.size), expecting = ItemKind.BACKUP)
        return StoredBackup.parseFrom(item.content)
    }

    /**
     * Brings a backup's notes and pictures into [store], relocked under its own vault.
     *
     * Importing a backup is asking for its notes back, so a note deleted here comes back,
     * however long after the backup it was deleted. Writing is still never lost: a note here
     * that was changed after the backup was made keeps its newer version.
     *
     * Every note keeps the backup's dates, so the list shows when it was really last edited,
     * and sorts it there. What moves to now is only the note's `changed` time, which sync
     * uses: the other device's record of a deletion is newer than the backup, and without it
     * would delete the note again.
     */
    fun restore(backup: StoredBackup, store: NoteStore, now: Instant = Instant.now()): Result {
        val result = Result()
        // Parents come before the folders inside them, so each finds its parent here.
        for (folder in backup.foldersList) {
            val parent = folder.parent.takeIf { !it.isEmpty }
            if (store.restoreFolder(folder.id, folder.name, parent, instant(folder.created), now)) result.folders++
        }
        for (picture in backup.picturesList) {
            store.storePicture(picture.bytes.toByteArray(), picture.id)
            result.pictures++
        }
        for (entry in backup.notesList) {
            val edited = instant(entry.updated)
            val here = store.lastEdited(entry.id)
            if (here != null) {
                when {
                    store.isDeleted(entry.id) -> result.undeleted++
                    store.load(entry.id) == entry.note -> {
                        // Already here, word for word. An earlier import dated restored notes
                        // with the time of the import; set such a date back to the note's own.
                        result.unchanged++
                        if (here > edited) {
                            store.setLastEdited(entry.id, edited)
                            result.datesSetBack++
                        }
                        continue
                    }
                    here >= edited -> {
                        result.keptNewer++
                        continue
                    }
                    else -> result.updated++
                }
            } else {
                result.added++
            }
            val changed = maxOf(now, store.lastChanged(entry.id) ?: now)
            store.save(entry.note, entry.id, now = edited, created = instant(entry.created), changed = changed)
            // Back where it was: a note that was in the Trash when the backup was made goes
            // back to the Trash, where it can still be restored, rather than being dropped.
            store.setTrashed(entry.id, if (entry.trashed > 0) instant(entry.trashed) else null)
            // And in the folder it was in. A note kept as it is here stays in its folder here.
            store.setFolder(entry.id, entry.folder.takeIf { !it.isEmpty })
        }
        return result
    }

    private fun instant(seconds: Double): Instant {
        val whole = Math.floor(seconds)
        return Instant.ofEpochSecond(whole.toLong(), Math.round((seconds - whole) * 1e9))
    }
}
