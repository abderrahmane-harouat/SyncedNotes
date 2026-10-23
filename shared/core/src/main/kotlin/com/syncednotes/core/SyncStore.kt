package com.syncednotes.core

import com.google.protobuf.ByteString
import com.syncednotes.core.NoteStore.Companion.id
import com.syncednotes.core.NoteStore.Companion.instant
import com.syncednotes.core.NoteStore.Companion.seconds
import java.time.Instant
import syncednotes.note.v1.Note
import syncednotes.sync.v1.FolderRecord
import syncednotes.sync.v1.Manifest
import syncednotes.sync.v1.NoteRecord
import syncednotes.sync.v1.PictureRecord
import syncednotes.sync.v1.Records
import syncednotes.sync.v1.Stamp

// The store's half of sync (Docs/Sync.md, "Merging"): what this device has, what the other
// lacks, and taking in what it sends. Each note and each folder is decided on its own, by when
// it last changed; the network half is in the sync package.

/** This device as its paired devices know it. The private key never leaves the locked database. */
class SyncIdentity(val deviceId: Id, val name: String, val privateKey: ByteArray, val publicKey: ByteArray)

/** A device this one is paired with. */
data class SyncPeer(val deviceId: Id, val name: String, val publicKey: ByteString, val paired: Instant)

/** How far ahead of this device's clock a record may be dated before it is ignored. */
const val MAX_CLOCK_SKEW_SECONDS = 5L * 60

/**
 * Whether a record stamped [theirs] wins over this device's [mine]: the later change wins, and
 * on a tie a deletion wins, so both devices decide the same way.
 */
fun wins(theirs: Stamp, mine: Stamp?): Boolean = when {
    mine == null -> true
    theirs.changed != mine.changed -> theirs.changed > mine.changed
    else -> theirs.deleted && !mine.deleted
}

/** What this device has, for the other to compare with its own. */
fun NoteStore.manifest(): Manifest = Manifest.newBuilder()
    .addAllNotes(noteStamps().values)
    .addAllFolders(folderStamps().values)
    .addAllPictures(pictureFiles().map { it.first })
    .build()

/** Whether [theirs] lists anything that would win over what is here, so it is worth asking for. */
fun NoteStore.hasRecordsNeededBy(mine: Manifest, theirs: Manifest): Boolean {
    val notes = mine.notesList.associateBy { it.id }
    val folders = mine.foldersList.associateBy { it.id }
    val pictures = mine.picturesList.toHashSet()
    return theirs.notesList.any { wins(it, notes[it.id]) } ||
        theirs.foldersList.any { wins(it, folders[it.id]) } ||
        theirs.picturesList.any { it !in pictures }
}

/**
 * The notes, folders and pictures [theirs] lacks, or has in an older version, in messages of
 * about [budget] bytes at most, so one large picture does not hold back every note.
 */
fun NoteStore.recordsNeededBy(theirs: Manifest, budget: Int = 4 * 1024 * 1024): List<Records> {
    val theirNotes = theirs.notesList.associateBy { it.id }
    val theirFolders = theirs.foldersList.associateBy { it.id }
    val theirPictures = theirs.picturesList.toHashSet()
    val batches = mutableListOf<Records>()
    var batch = Records.newBuilder()
    fun add(size: Int, put: (Records.Builder) -> Unit) {
        if (batch.notesCount + batch.foldersCount + batch.picturesCount > 0 && batch.build().serializedSize + size > budget) {
            batches += batch.build()
            batch = Records.newBuilder()
        }
        put(batch)
    }
    // Folders first: a note that arrives in the same message then finds its folder.
    for ((id, stamp) in folderStamps()) {
        if (!wins(stamp, theirFolders[id])) continue
        val record = folderRecord(id) ?: continue
        add(record.serializedSize) { it.addFolders(record) }
    }
    for ((id, stamp) in noteStamps()) {
        if (!wins(stamp, theirNotes[id])) continue
        val record = noteRecord(id) ?: continue
        add(record.serializedSize) { it.addNotes(record) }
    }
    for ((id, file) in pictureFiles()) {
        if (id in theirPictures) continue
        val bytes = pictureFile(id)?.takeIf { it.exists() }?.readBytes() ?: continue
        val record = PictureRecord.newBuilder().setId(id).setLocked(ByteString.copyFrom(bytes)).build()
        add(record.serializedSize) { it.addPictures(record) }
    }
    if (batch.notesCount + batch.foldersCount + batch.picturesCount > 0) batches += batch.build()
    return batches
}

/**
 * Takes in what the other device sent, as one transaction: each note and folder only if it
 * still wins over what is here, each locked item only if it opens with this vault. Anything
 * that does not open refuses the whole message, so nothing here is written over with it.
 * Returns the IDs of the notes that changed.
 */
fun NoteStore.apply(records: Records, now: Instant = Instant.now()): Set<Id> {
    val latest = seconds(now) + MAX_CLOCK_SKEW_SECONDS
    // Opened before anything is written: a record that does not open stops it all.
    val opened = records.notesList.filter { !it.hasDeleted() }.associate { record ->
        val item = vault.open(record.locked.toByteArray(), expecting = ItemKind.NOTE)
        if (!item.id.contentEquals(record.id.toByteArray())) throw LockException(LockException.Reason.DAMAGED)
        record.id to Note.parseFrom(item.content)
    }
    for (picture in records.picturesList) {
        val item = vault.open(picture.locked.toByteArray(), expecting = ItemKind.PICTURE)
        if (!item.id.contentEquals(picture.id.toByteArray())) throw LockException(LockException.Reason.DAMAGED)
    }
    val changed = mutableSetOf<Id>()
    transaction {
        val folders = folderStamps()
        for (record in records.foldersList) {
            if (record.id.size() != 16) continue
            val stamp = stamp(record.id, record.changed, record.hasDeleted())
            // A device whose clock runs ahead would otherwise win over every real edit.
            if (record.changed > latest || !wins(stamp, folders[record.id])) continue
            if (record.hasDeleted()) {
                update("DELETE FROM folders WHERE id = ?", record.id)
                update("INSERT OR REPLACE INTO folder_deletions (id, deleted) VALUES (?, ?)", record.id, record.deleted)
            } else {
                update(
                    """
                    INSERT INTO folders (id, name, parent, created, changed) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET name = excluded.name, parent = excluded.parent,
                        created = excluded.created, changed = excluded.changed
                    """,
                    record.id, record.name, record.parent.takeUnless { it.isEmpty }, record.created, record.changed,
                )
                update("DELETE FROM folder_deletions WHERE id = ?", record.id)
            }
        }
        val notes = noteStamps()
        for (record in records.notesList) {
            if (record.id.size() != 16) continue
            val stamp = stamp(record.id, record.changed, record.hasDeleted())
            if (record.changed > latest || !wins(stamp, notes[record.id])) continue
            val folder = record.folder.takeUnless { it.isEmpty }
            val trashed = if (record.hasTrashed()) record.trashed else null
            if (record.hasDeleted()) {
                update(
                    """
                    INSERT INTO notes (id, locked, title, created, updated, changed, deleted, trashed, folder) VALUES (?, NULL, '', ?, ?, ?, ?, NULL, ?)
                    ON CONFLICT(id) DO UPDATE SET locked = NULL, title = '', created = excluded.created, updated = excluded.updated,
                        changed = excluded.changed, deleted = excluded.deleted, trashed = NULL, folder = excluded.folder
                    """,
                    record.id, record.created, record.updated, record.changed, record.deleted, folder,
                )
                update("DELETE FROM search WHERE note = ?", record.id)
            } else {
                val note = opened.getValue(record.id)
                update(
                    """
                    INSERT INTO notes (id, locked, title, created, updated, changed, deleted, trashed, folder) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET locked = excluded.locked, title = excluded.title, created = excluded.created,
                        updated = excluded.updated, changed = excluded.changed, deleted = NULL, trashed = excluded.trashed, folder = excluded.folder
                    """,
                    record.id, record.locked, note.title, record.created, record.updated, record.changed, trashed, folder,
                )
                update("DELETE FROM search WHERE note = ?", record.id)
                update("INSERT INTO search (note, words) VALUES (?, ?)", record.id, note.words.joinToString("\n"))
            }
            changed += record.id
        }
        for (picture in records.picturesList) storeLockedPicture(picture.id, picture.locked.toByteArray())
    }
    return changed
}

// --- This device and its peers --------------------------------------------------------------

fun NoteStore.syncIdentity(): SyncIdentity? =
    query("SELECT device_id, name, private_key, public_key FROM sync_identity") {
        SyncIdentity(it.id(1)!!, it.string(2)!!, it.bytes(3)!!, it.bytes(4)!!)
    }.firstOrNull()

fun NoteStore.saveSyncIdentity(identity: SyncIdentity) = transaction {
    update("DELETE FROM sync_identity")
    update(
        "INSERT INTO sync_identity (device_id, name, private_key, public_key) VALUES (?, ?, ?, ?)",
        identity.deviceId, identity.name, identity.privateKey, identity.publicKey,
    )
}

fun NoteStore.peers(): List<SyncPeer> =
    query("SELECT device_id, name, public_key, paired FROM sync_peers ORDER BY paired") {
        SyncPeer(it.id(1)!!, it.string(2)!!, it.id(3)!!, it.instant(4)!!)
    }

fun NoteStore.savePeer(peer: SyncPeer) {
    update(
        "INSERT OR REPLACE INTO sync_peers (device_id, name, public_key, paired) VALUES (?, ?, ?, ?)",
        peer.deviceId, peer.name, peer.publicKey, seconds(peer.paired),
    )
}

fun NoteStore.removePeer(deviceId: Id) {
    update("DELETE FROM sync_peers WHERE device_id = ?", deviceId)
}

// --- Rows ----------------------------------------------------------------------------------

private fun stamp(id: Id, changed: Double, deleted: Boolean): Stamp =
    Stamp.newBuilder().setId(id).setChanged(changed).setDeleted(deleted).build()

private fun NoteStore.noteStamps(): Map<Id, Stamp> =
    query("SELECT id, changed, deleted IS NOT NULL FROM notes") { stamp(it.id(1)!!, it.double(2)!!, it.boolean(3)) }
        .associateBy { it.id }

private fun NoteStore.folderStamps(): Map<Id, Stamp> =
    (query("SELECT id, changed FROM folders") { stamp(it.id(1)!!, it.double(2)!!, false) } +
        query("SELECT id, deleted FROM folder_deletions") { stamp(it.id(1)!!, it.double(2)!!, true) })
        .associateBy { it.id }

private fun NoteStore.noteRecord(id: Id): NoteRecord? =
    query("SELECT locked, created, updated, changed, deleted, trashed, folder FROM notes WHERE id = ?", id) {
        NoteRecord.newBuilder().apply {
            this.id = id
            it.bytes(1)?.let { locked = ByteString.copyFrom(it) }
            created = it.double(2)!!
            updated = it.double(3)!!
            changed = it.double(4)!!
            it.double(5)?.let { deleted = it }
            it.double(6)?.let { trashed = it }
            it.id(7)?.let { folder = it }
        }.build()
    }.firstOrNull()

private fun NoteStore.folderRecord(id: Id): FolderRecord? {
    query("SELECT name, parent, created, changed FROM folders WHERE id = ?", id) {
        FolderRecord.newBuilder().apply {
            this.id = id
            name = it.string(1)!!
            it.id(2)?.let { parent = it }
            created = it.double(3)!!
            changed = it.double(4)!!
        }.build()
    }.firstOrNull()?.let { return it }
    return query("SELECT deleted FROM folder_deletions WHERE id = ?", id) {
        FolderRecord.newBuilder().setId(id).setChanged(it.double(1)!!).setDeleted(it.double(1)!!).build()
    }.firstOrNull()
}
