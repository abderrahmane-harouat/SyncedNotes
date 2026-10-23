package com.syncednotes.core

import com.google.protobuf.ByteString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Collator
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import syncednotes.note.v1.Note

/** A note's, picture's or folder's 16-byte ID. ByteString, unlike ByteArray, compares by value. */
typealias Id = ByteString

fun newId(random: RandomBytes = RandomBytes.SYSTEM): Id = ByteString.copyFrom(random.next(16))

val Id.hex: String get() = toByteArray().joinToString("") { "%02x".format(it) }

/**
 * The notes on this device.
 *
 * A database locked as a whole with the vault's database key, in SQLCipher 4's format, holds
 * each note as a locked item (LockFormat.md) with Protobuf inside (note.proto). Pictures are
 * locked items too, in files beside it with random names. So a note is locked twice while it
 * rests here, and the inner lock is exactly what would travel to a second device. The Swift
 * app's NoteStore.swift, with the same tables.
 */
class NoteStore(folder: File, val vault: Vault, opener: SqlOpener = SqlOpener.current) : AutoCloseable {

    data class Summary(
        val id: Id,
        val title: String,
        val updated: Instant,
        /** When it was moved to the Trash; null for a note that is not in it. */
        val trashed: Instant? = null,
        /** The folder it is in; null for a note in no folder. */
        val folder: Id? = null,
    )

    /** A folder of notes. Folders can hold folders. */
    data class Folder(val id: Id, val name: String, val parent: Id?, val created: Instant)

    /** Which notes a list shows. */
    sealed interface Scope {
        /** Every note, whatever its folder. */
        data object All : Scope
        /** The notes in no folder. */
        data object NoFolder : Scope
        /** The notes in this folder itself, not in the folders inside it. */
        data class InFolder(val id: Id) : Scope
    }

    /** A note in a backup's terms: everything about it but its content. */
    data class Entry(val id: Id, val created: Instant, val updated: Instant, val trashed: Instant?, val folder: Id?)

    class StoreException(val reason: Reason, cause: Throwable? = null) :
        Exception(if (cause == null) reason.name else "${reason.name}: ${cause.message}", cause) {
        enum class Reason {
            /** The database would not open with this vault's key: another vault's, or damaged. */
            CANNOT_OPEN,
            NO_SUCH_NOTE,
            NO_SUCH_PICTURE,
            NO_SUCH_FOLDER,
            /** A folder cannot go inside itself, or inside a folder within it. */
            FOLDER_INSIDE_ITSELF,
        }
    }

    /**
     * Counts every write that was kept, here or from the other device, so sync can tell the
     * other device something changed without being told what.
     */
    val changes = MutableStateFlow(0L)

    private var depth = 0
    private var wrote = false
    private val picturesFolder = File(folder, "pictures").apply { mkdirs() }
    internal val database: SqlDatabase = try {
        opener.open(File(folder, DATABASE_NAME), vault.databaseKey)
    } catch (e: SqlOpenException) {
        throw StoreException(StoreException.Reason.CANNOT_OPEN, e)
    }

    init {
        migrate()
    }

    override fun close() = database.close()

    private fun migrate() {
        update("CREATE TABLE IF NOT EXISTS grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY)")
        val done = query("SELECT identifier FROM grdb_migrations") { it.string(1)!! }.toSet()
        for ((name, statements) in MIGRATIONS) {
            if (name in done) continue
            transaction {
                statements.forEach { update(it) }
                update("INSERT INTO grdb_migrations (identifier) VALUES (?)", name)
            }
        }
    }

    // --- Notes ----------------------------------------------------------------------------

    /**
     * The notes in [scope] that are neither deleted nor in the Trash, newest first; only those
     * matching [search] if given.
     */
    fun summaries(search: String? = null, scope: Scope = Scope.All): List<Summary> {
        val conditions = mutableListOf("notes.deleted IS NULL", "notes.trashed IS NULL")
        val arguments = mutableListOf<Any?>()
        when (scope) {
            Scope.All -> {}
            Scope.NoFolder -> conditions.add(IN_NO_FOLDER)
            is Scope.InFolder -> {
                conditions += "notes.folder = ?"
                arguments.add(scope.id)
            }
        }
        var join = ""
        val pattern = search?.let(::prefixPattern)
        if (pattern != null) {
            join = "JOIN search ON search.note = notes.id"
            conditions += "search MATCH ?"
            arguments += pattern
        }
        return query(
            "SELECT notes.id, notes.title, notes.updated, notes.folder FROM notes $join " +
                "WHERE ${conditions.joinToString(" AND ")} ORDER BY notes.updated DESC",
            *arguments.toTypedArray(),
        ) { Summary(it.id(1)!!, it.string(2)!!, it.instant(3)!!, folder = it.id(4)) }
    }

    fun load(id: Id): Note {
        val locked = query("SELECT locked FROM notes WHERE id = ? AND deleted IS NULL", id) { it.bytes(1) }.firstOrNull()
            ?: throw StoreException(StoreException.Reason.NO_SUCH_NOTE)
        val item = vault.open(locked, expecting = ItemKind.NOTE)
        if (!item.id.contentEquals(id.toByteArray())) throw LockException(LockException.Reason.DAMAGED)
        return Note.parseFrom(item.content)
    }

    /**
     * Saves [note] under [id], creating it if new, as edited at [now]. [words] is its plain
     * text, for search. [created] and [changed] are only for a note restored from a backup,
     * which keeps its own dates but changes now. [folder] is where a new note goes; a note
     * saved again stays where it is.
     */
    fun save(
        note: Note, id: Id, title: String = note.title, words: String = note.words.joinToString("\n"),
        now: Instant = Instant.now(), created: Instant? = null, changed: Instant? = null, folder: Id? = null,
    ) {
        val locked = vault.lock(note.toByteArray(), ItemKind.NOTE, id.toByteArray())
        transaction {
            update(
                """
                INSERT INTO notes (id, locked, title, created, updated, changed, deleted, folder) VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                ON CONFLICT(id) DO UPDATE SET locked = excluded.locked, title = excluded.title,
                    updated = excluded.updated, changed = excluded.changed, deleted = NULL
                """,
                id, locked, title, seconds(created ?: now), seconds(now), seconds(changed ?: now), folder,
            )
            update("DELETE FROM search WHERE note = ?", id)
            update("INSERT INTO search (note, words) VALUES (?, ?)", id, words)
        }
    }

    // --- The Trash ------------------------------------------------------------------------

    /** The notes in the Trash, the most recently trashed first. */
    fun trash(): List<Summary> = query(
        "SELECT id, title, updated, trashed FROM notes WHERE deleted IS NULL AND trashed IS NOT NULL ORDER BY trashed DESC",
    ) { Summary(it.id(1)!!, it.string(2)!!, it.instant(3)!!, trashed = it.instant(4)) }

    /** Moves a note to the Trash, content and all, from where it can be restored. */
    fun moveToTrash(id: Id, now: Instant = Instant.now()) {
        update("UPDATE notes SET trashed = ?, changed = ? WHERE id = ? AND deleted IS NULL", seconds(now), seconds(now), id)
    }

    /**
     * Takes a note out of the Trash, back to the notes. It goes back to its folder, or, if
     * that folder has been deleted since, to no folder.
     */
    fun restoreFromTrash(id: Id, now: Instant = Instant.now()) {
        update(
            """
            UPDATE notes SET trashed = NULL, changed = ?,
                folder = CASE WHEN folder IN (SELECT id FROM folders) THEN folder END
            WHERE id = ?
            """,
            seconds(now), id,
        )
    }

    /** Deletes for good every note that has been in the Trash longer than [ageSeconds]. */
    fun emptyTrash(ageSeconds: Long = 0, now: Instant = Instant.now()): Int {
        val cutoff = seconds(now.minusSeconds(ageSeconds))
        val expired = query("SELECT id FROM notes WHERE deleted IS NULL AND trashed IS NOT NULL AND trashed <= ?", cutoff) { it.id(1)!! }
        for (id in expired) delete(id, now)
        return expired.size
    }

    /** Whether a note is in the Trash. */
    fun isInTrash(id: Id): Boolean =
        query("SELECT trashed IS NOT NULL FROM notes WHERE id = ? AND deleted IS NULL", id) { it.boolean(1) }.firstOrNull() ?: false

    /**
     * Puts a note in the Trash as of [date], or takes it out with null, without counting it as
     * a change: for restoring a backup, which says where each note was.
     */
    internal fun setTrashed(id: Id, date: Instant?) {
        update("UPDATE notes SET trashed = ? WHERE id = ?", date?.let(::seconds), id)
    }

    // --- Deleting for good ----------------------------------------------------------------

    /**
     * Deletes a note for good, content and all, leaving only the mark the other device needs
     * to learn of it. The Trash is the way to delete with a way back.
     */
    fun delete(id: Id, now: Instant = Instant.now()) {
        val t = seconds(now)
        transaction {
            update("UPDATE notes SET locked = NULL, title = '', updated = ?, changed = ?, deleted = ?, trashed = NULL WHERE id = ?", t, t, t, id)
            update("DELETE FROM search WHERE note = ?", id)
        }
    }

    /** Removes a note that never had anything in it, without a mark: there is nothing to learn. */
    fun discard(id: Id) {
        transaction {
            update("DELETE FROM notes WHERE id = ?", id)
            update("DELETE FROM search WHERE note = ?", id)
        }
    }

    /** When anything about a note last changed here, its deletion included, for sync; null if never here. */
    fun lastChanged(id: Id): Instant? = query("SELECT changed FROM notes WHERE id = ?", id) { it.instant(1) }.firstOrNull()

    /** When a note was last edited, or deleted: the date the list shows. */
    fun lastEdited(id: Id): Instant? = query("SELECT updated FROM notes WHERE id = ?", id) { it.instant(1) }.firstOrNull()

    /** Sets when a note was last edited, leaving everything else as it is. */
    internal fun setLastEdited(id: Id, date: Instant) {
        update("UPDATE notes SET updated = ? WHERE id = ?", seconds(date), id)
    }

    /** Every note that is not deleted, the Trash included, oldest first, for a backup. */
    fun everyNote(): List<Entry> = query("SELECT id, created, updated, trashed, folder FROM notes WHERE deleted IS NULL ORDER BY created") {
        Entry(it.id(1)!!, it.instant(2)!!, it.instant(3)!!, it.instant(4), it.id(5))
    }

    /** Whether a note has been deleted (its mark remains). */
    fun isDeleted(id: Id): Boolean =
        query("SELECT deleted IS NOT NULL FROM notes WHERE id = ?", id) { it.boolean(1) }.firstOrNull() ?: false

    // --- Folders --------------------------------------------------------------------------

    /** Every folder, parents before the folders inside them, each level by name. */
    fun folders(): List<Folder> {
        val all = query("SELECT id, name, parent, created FROM folders") { Folder(it.id(1)!!, it.string(2)!!, it.id(3), it.instant(4)!!) }
        val children = all.groupBy { it.parent }
        // Finder's order: numbers by value, case and accents aside.
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        val placed = HashSet<Id>()
        fun ordered(parent: Id?): List<Folder> =
            (children[parent] ?: emptyList()).filter { placed.add(it.id) }
                .sortedWith { a, b -> naturalCompare(a.name, b.name, collator) }
                .flatMap { listOf(it) + ordered(it.id) }
        val top = ordered(null)
        // Two devices can each move a folder into the other before they sync, or delete a
        // parent while the other adds to it; both then agree on rows that no longer make a
        // tree. Such folders are shown at the top, as if their parent were none, and their
        // rows are left as they are, so both devices still agree.
        val stranded = all.filter { it.id !in placed }.sortedWith { a, b -> naturalCompare(a.name, b.name, collator) }
        return top + stranded.flatMap { folder ->
            if (!placed.add(folder.id)) emptyList() else listOf(folder.copy(parent = null)) + ordered(folder.id)
        }
    }

    /** Makes a folder named [name] inside [parent], or at the top; returns its ID. */
    fun createFolder(name: String, parent: Id? = null, now: Instant = Instant.now()): Id {
        val id = newId()
        transaction {
            if (parent != null && !folderExists(parent)) throw StoreException(StoreException.Reason.NO_SUCH_FOLDER)
            update("INSERT INTO folders (id, name, parent, created, changed) VALUES (?, ?, ?, ?, ?)", id, name, parent, seconds(now), seconds(now))
        }
        return id
    }

    fun renameFolder(id: Id, name: String, now: Instant = Instant.now()) {
        transaction {
            if (!folderExists(id)) throw StoreException(StoreException.Reason.NO_SUCH_FOLDER)
            update("UPDATE folders SET name = ?, changed = ? WHERE id = ?", name, seconds(now), id)
        }
    }

    /** Moves a folder, with everything in it, into [parent], or to the top. */
    fun moveFolder(id: Id, parent: Id?, now: Instant = Instant.now()) {
        transaction {
            if (!folderExists(id)) throw StoreException(StoreException.Reason.NO_SUCH_FOLDER)
            if (parent != null) {
                if (!folderExists(parent)) throw StoreException(StoreException.Reason.NO_SUCH_FOLDER)
                // Walking up from the new parent must never meet the folder being moved, or the
                // two would hold each other and drop out of the tree.
                var step: Id? = parent
                while (step != null) {
                    if (step == id) throw StoreException(StoreException.Reason.FOLDER_INSIDE_ITSELF)
                    step = query("SELECT parent FROM folders WHERE id = ?", step) { it.id(1) }.firstOrNull()
                }
            }
            update("UPDATE folders SET parent = ?, changed = ? WHERE id = ?", parent, seconds(now), id)
        }
    }

    /**
     * Deletes a folder and the folders inside it, and moves their notes to the Trash, from
     * where they can still be restored; they come back to no folder. Returns how many notes
     * went to the Trash.
     */
    fun deleteFolder(id: Id, now: Instant = Instant.now()): Int = transaction {
        if (!folderExists(id)) throw StoreException(StoreException.Reason.NO_SUCH_FOLDER)
        val doomed = folderAndInside(id)
        val marks = doomed.joinToString(",") { "?" }
        val t = seconds(now)
        val moved = query("SELECT count(*) FROM notes WHERE folder IN ($marks) AND deleted IS NULL AND trashed IS NULL", *doomed.toTypedArray()) { it.int(1) }.first()
        update("UPDATE notes SET trashed = ?, changed = ? WHERE folder IN ($marks) AND deleted IS NULL AND trashed IS NULL", t, t, *doomed.toTypedArray())
        // Children first, as each row points at its parent.
        for (folder in doomed.reversed()) {
            update("DELETE FROM folders WHERE id = ?", folder)
            update("INSERT OR REPLACE INTO folder_deletions (id, deleted) VALUES (?, ?)", folder, t)
        }
        moved
    }

    /** How many notes outside the Trash would go with [id]: in it and in the folders inside it. */
    fun noteCountDeletingFolder(id: Id): Int {
        val doomed = folderAndInside(id)
        return query(
            "SELECT count(*) FROM notes WHERE folder IN (${doomed.joinToString(",") { "?" }}) AND deleted IS NULL AND trashed IS NULL",
            *doomed.toTypedArray(),
        ) { it.int(1) }.first()
    }

    /** How many notes outside the Trash each folder holds itself, by folder ID; null for no folder. */
    fun noteCounts(): Map<Id?, Int> = query(
        "SELECT CASE WHEN $IN_NO_FOLDER THEN NULL ELSE notes.folder END AS f, count(*) FROM notes " +
            "WHERE deleted IS NULL AND trashed IS NULL GROUP BY f",
    ) { it.id(1) to it.int(2) }.toMap()

    /** Moves a note into [folder], or out of any folder with null. */
    fun moveNote(id: Id, folder: Id?, now: Instant = Instant.now()) {
        transaction {
            if (folder != null && !folderExists(folder)) throw StoreException(StoreException.Reason.NO_SUCH_FOLDER)
            update("UPDATE notes SET folder = ?, changed = ? WHERE id = ? AND deleted IS NULL", folder, seconds(now), id)
        }
    }

    /** The folder a note is in; null for none, or for a folder that is not here. */
    fun folderOf(id: Id): Id? =
        query("SELECT CASE WHEN $IN_NO_FOLDER THEN NULL ELSE notes.folder END FROM notes WHERE id = ?", id) { it.id(1) }.firstOrNull()

    /**
     * Puts a folder back as a backup had it, unless one with its ID is here already, which
     * keeps its own name and place. Returns whether it was added.
     */
    internal fun restoreFolder(id: Id, name: String, parent: Id?, created: Instant, now: Instant): Boolean = transaction {
        if (folderExists(id)) return@transaction false
        // A parent that did not come through stays out of the picture: the folder goes at the top.
        val kept = parent?.takeIf(::folderExists)
        update("INSERT INTO folders (id, name, parent, created, changed) VALUES (?, ?, ?, ?, ?)", id, name, kept, seconds(created), seconds(now))
        // Changed now, so it counts as newer than its deletion on either device.
        update("DELETE FROM folder_deletions WHERE id = ?", id)
        true
    }

    /**
     * Puts a note in a folder as a backup had it, without counting it as a change; a folder
     * that is not here leaves it in none.
     */
    internal fun setFolder(id: Id, folder: Id?) {
        update("UPDATE notes SET folder = ? WHERE id = ?", folder?.takeIf(::folderExists), id)
    }

    internal fun folderExists(id: Id): Boolean = query("SELECT 1 FROM folders WHERE id = ?", id) { true }.isNotEmpty()

    /** [id] and every folder inside it, at any depth, parents first. */
    private fun folderAndInside(id: Id): List<Id> {
        val found = mutableListOf(id)
        var index = 0
        while (index < found.size) {
            found += query("SELECT id FROM folders WHERE parent = ?", found[index]) { it.id(1)!! }
            index++
        }
        return found
    }

    // --- Pictures -------------------------------------------------------------------------

    /**
     * Stores a picture's bytes, locked, under a random file name; does nothing if [id] is
     * stored already, since a picture's bytes never change.
     */
    fun storePicture(bytes: ByteArray, id: Id) {
        if (query("SELECT 1 FROM pictures WHERE id = ?", id) { true }.isNotEmpty()) return
        // Random, never derived from the picture: nothing about it can be guessed from the name.
        val file = newId().hex + ".snl"
        writeAtomically(File(picturesFolder, file), vault.lock(bytes, ItemKind.PICTURE, id.toByteArray()))
        update("INSERT INTO pictures (id, file) VALUES (?, ?)", id, file)
    }

    /** Stores a picture that came locked from the other device, as it came; it was opened first. */
    internal fun storeLockedPicture(id: Id, locked: ByteArray) {
        if (query("SELECT 1 FROM pictures WHERE id = ?", id) { true }.isNotEmpty()) return
        val file = newId().hex + ".snl"
        writeAtomically(File(picturesFolder, file), locked)
        update("INSERT INTO pictures (id, file) VALUES (?, ?)", id, file)
    }

    fun picture(id: Id): ByteArray {
        val file = pictureFile(id) ?: throw StoreException(StoreException.Reason.NO_SUCH_PICTURE)
        val item = vault.open(file.readBytes(), expecting = ItemKind.PICTURE)
        if (!item.id.contentEquals(id.toByteArray())) throw LockException(LockException.Reason.DAMAGED)
        return item.content
    }

    /** Where a picture's locked file is, for tests that check what reaches the disk. */
    internal fun pictureFile(id: Id): File? =
        query("SELECT file FROM pictures WHERE id = ?", id) { it.string(1) }.firstOrNull()?.let { File(picturesFolder, it) }

    /** Every picture's ID and locked file, for changing the passphrase. */
    internal fun pictureFiles(): List<Pair<Id, String>> = query("SELECT id, file FROM pictures") { it.id(1)!! to it.string(2)!! }

    // --- SQL ------------------------------------------------------------------------------

    // Synchronized, every one of them: sync reads and writes from its own threads while the
    // window does from its own, over the one connection. A transaction holds the lock to the
    // end, so no other thread's statement lands in the middle of it.

    /** Runs [body] as one transaction; nested calls join the outer one. */
    @Synchronized
    internal fun <T> transaction(body: () -> T): T {
        if (depth > 0) return body()
        database.begin()
        depth++
        try {
            return body().also {
                database.commit()
                if (wrote) changes.value++
            }
        } catch (e: Throwable) {
            database.rollback()
            throw e
        } finally {
            depth--
            wrote = false
        }
    }

    @Synchronized
    internal fun update(sql: String, vararg arguments: Any?): Int = database.update(sql, arguments).also {
        if (depth > 0) wrote = true else changes.value++
    }

    @Synchronized
    internal fun <T> query(sql: String, vararg arguments: Any?, row: (SqlRow) -> T): List<T> = database.query(sql, arguments, row)

    companion object {
        /** How long a note stays in the Trash before it is deleted for good, as in Apple Notes. */
        const val TRASH_KEEPS_FOR_SECONDS = 30L * 24 * 60 * 60
        internal const val DATABASE_NAME = "notes.sqlite"

        /**
         * The Swift app's migrations, under the names GRDB recorded them: a database it made
         * opens here as it is, and one made here would open there.
         */
        private val MIGRATIONS = listOf(
            // `title` is kept in the clear inside the locked database, so the list of notes
            // does not have to open every note. `locked` is empty for a deleted note, whose row
            // stays for a while so that the other device learns of the deletion instead of
            // sending the note back.
            "v1" to listOf(
                "CREATE TABLE notes (id BLOB PRIMARY KEY NOT NULL, locked BLOB, title TEXT NOT NULL DEFAULT '', created REAL NOT NULL, updated REAL NOT NULL, deleted REAL)",
                "CREATE TABLE pictures (id BLOB PRIMARY KEY NOT NULL, file TEXT NOT NULL UNIQUE)",
                "CREATE VIRTUAL TABLE \"search\" USING fts5(note UNINDEXED, words, tokenize='unicode61')",
            ),
            // `updated` is when the note was last edited: shown in the list, and what it is
            // sorted by. `changed` is when anything about the row last changed, for sync: a
            // note brought back from a backup keeps its own edit date, but must still count as
            // newer than its deletion, or the other device deletes it again.
            "v2: when a note last changed, apart from when it was edited" to listOf(
                "ALTER TABLE notes ADD COLUMN changed REAL",
                "UPDATE notes SET changed = updated",
            ),
            // A deleted note first goes to the Trash with its content, and can be restored;
            // only deleting it from there, by hand or after 30 days, leaves the bare mark.
            "v3: the Trash" to listOf("ALTER TABLE notes ADD COLUMN trashed REAL"),
            // A folder's name is kept in the clear inside the locked database, as a note's
            // title is. A note in the Trash keeps its folder, so restoring it puts it back there.
            "v4: folders" to listOf(
                "CREATE TABLE folders (id BLOB PRIMARY KEY NOT NULL, name TEXT NOT NULL, parent BLOB REFERENCES folders(id), created REAL NOT NULL, changed REAL NOT NULL)",
                "ALTER TABLE notes ADD COLUMN folder BLOB",
            ),
            // Sync (Docs/Sync.md). A deleted folder leaves a mark, as a deleted note does, so
            // the other device deletes it too instead of sending it back. This device's
            // identity and the devices it is paired with live here, inside the locked
            // database, so the identity's private key is only ever at hand while unlocked.
            "v5: sync" to listOf(
                "CREATE TABLE folder_deletions (id BLOB PRIMARY KEY NOT NULL, deleted REAL NOT NULL)",
                "CREATE TABLE sync_identity (device_id BLOB NOT NULL, name TEXT NOT NULL, private_key BLOB NOT NULL, public_key BLOB NOT NULL)",
                "CREATE TABLE sync_peers (device_id BLOB PRIMARY KEY NOT NULL, name TEXT NOT NULL, public_key BLOB NOT NULL, paired REAL NOT NULL)",
            ),
        )

        /**
         * GRDB's FTS5Pattern(matchingAllPrefixesIn:): every word typed must start a word in the
         * note, in any order. Null when there is nothing to search for.
         */
        internal fun prefixPattern(search: String): String? {
            val tokens = Regex("[\\p{L}\\p{N}\\p{M}]+").findAll(search).map { it.value }.toList()
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"*" }
        }

        /** A note in no folder, or in one that is not on this device. */
        private const val IN_NO_FOLDER = "(notes.folder IS NULL OR notes.folder NOT IN (SELECT id FROM folders))"

        internal fun seconds(t: Instant): Double = t.epochSecond + t.nano / 1e9

        internal fun instant(s: Double): Instant {
            val whole = Math.floor(s)
            return Instant.ofEpochSecond(whole.toLong(), Math.round((s - whole) * 1e9))
        }

        internal fun SqlRow.instant(column: Int): Instant? = double(column)?.let(::instant)

        internal fun SqlRow.id(column: Int): Id? = bytes(column)?.let(ByteString::copyFrom)

        /** Folder names in Finder's order: "Folder 2" before "Folder 10". */
        private fun naturalCompare(a: String, b: String, collator: Collator): Int {
            val pa = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
            val pb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else collator.compare(x, y)
                if (c != 0) return c
            }
            return pa.size.compareTo(pb.size)
        }

        /** Writes [bytes] whole or not at all: to a file beside [file], then moved into its place. */
        fun writeAtomically(file: File, bytes: ByteArray) {
            val temporary = File(file.parentFile, ".${file.name}.writing")
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

// --- The vault on disk --------------------------------------------------------------------

/** The vault header's file in [folder]. Present once a passphrase has been set. */
fun vaultHeaderFile(folder: File): File = File(folder, "vault.bin")

/** Creates a new vault in [folder] for [passphrase], and writes its header. */
fun createVault(folder: File, passphrase: String): Vault {
    folder.mkdirs()
    val vault = Vault.create(passphrase)
    NoteStore.writeAtomically(vaultHeaderFile(folder), vault.header.encoded)
    return vault
}

/** Unlocks the vault in [folder] with [passphrase]. */
fun unlockVault(folder: File, passphrase: String): Vault =
    Vault.unlock(VaultHeader.decode(vaultHeaderFile(folder).readBytes()), passphrase)
