package com.syncednotes.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Changing the passphrase.
 *
 * Every key is made from the passphrase (LockFormat.md), so a new passphrase means a new
 * vault: a new salt and a new vault ID, the database locked again, and every note and picture
 * locked again. A new ID rather than the old one, so a backup made before the change is known
 * to be from another vault, and asks for the passphrase it was made with.
 *
 * Nothing is changed in place. The notes are written, locked the new way, into a folder beside
 * them; every note and picture is opened there with the new passphrase and compared with the
 * original; only then does that folder take the old one's place, and the old one is deleted,
 * so the old passphrase opens nothing left on the Mac. Interrupted at any point, the next
 * launch either finishes the change or drops it ([finishInterruptedChange]), and the notes
 * always open with exactly one of the two passphrases.
 */
object PassphraseChange {

    /** The copy did not come out the same as the notes, so nothing was changed. */
    class CopyDiffersException(what: String) : Exception("The new copy differs in $what")

    /** Where the new notes are written before they take the old ones' place. */
    internal fun staging(home: File) = File(home.parentFile, home.name + "-changing-passphrase")

    /** Where the old notes go for the moment between the two renames. */
    internal fun replaced(home: File) = File(home.parentFile, home.name + "-old-passphrase")

    /** Written in the new folder once everything in it has been checked. */
    internal fun checkedMark(staging: File) = File(staging, "checked")

    /**
     * Writes and checks the notes in [store], locked under a new vault for [new], beside
     * [home]. Changes nothing in [home]. [current] must be the passphrase in use: asked again,
     * so an unlocked Mac left alone is not enough to change it.
     *
     * Returns the new vault. Then close [store] and call [swap].
     */
    fun prepare(store: NoteStore, home: File, current: String, new: String): Vault {
        val old = Vault.unlock(store.vault.header, current)
        val vault = Vault.create(new)
        val staging = staging(home)
        staging.deleteRecursively()
        File(staging, "pictures").mkdirs()
        try {
            NoteStore.writeAtomically(vaultHeaderFile(staging), vault.header.encoded)
            NoteStore(staging, vault).use { copy -> copyRows(store, copy, old, vault) }
            relockPictures(store, home, staging, old, vault)
            check(staging, store, new)
            checkedMark(staging).writeBytes(ByteArray(0))
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        return vault
    }

    /** Puts the checked new notes in [home]'s place and deletes the old ones. The store on [home] must be closed first. */
    fun swap(home: File) {
        val staging = staging(home)
        val replaced = replaced(home)
        if (!checkedMark(staging).exists()) throw CopyDiffersException("nothing: the new notes were not checked")
        move(home, replaced)
        move(staging, home)
        Files.delete(checkedMark(home).toPath())
        if (!replaced.deleteRecursively()) error("The notes under the old passphrase could not be deleted: $replaced")
    }

    /**
     * Run at launch, before anything is opened: finishes a change that was checked but not yet
     * in place, and drops one that was not checked. Returns whether it found one.
     */
    fun finishInterruptedChange(home: File): Boolean {
        val staging = staging(home)
        val replaced = replaced(home)
        val checked = checkedMark(staging).exists()
        if (staging.exists()) {
            if (checked) {
                // Checked, so it is the notes now: as if the change had finished.
                if (home.exists()) {
                    if (replaced.exists()) replaced.deleteRecursively()
                    move(home, replaced)
                }
                move(staging, home)
                Files.delete(checkedMark(home).toPath())
            } else if (home.exists()) {
                // Not checked: the old notes are still the notes.
                staging.deleteRecursively()
                return true
            }
        }
        // Stopped after the new notes were in place, before the old ones were deleted.
        checkedMark(home).delete()
        if (replaced.exists() && home.exists()) {
            replaced.deleteRecursively()
            return true
        }
        return checked
    }

    // --- Steps ----------------------------------------------------------------------------

    /**
     * Every row of the database into [copy], whose own key already locks it as a whole; each
     * note's content opened with the old vault and locked again with the new one. The rows
     * keep every value as it is, so the check below can compare them one for one.
     */
    private fun copyRows(store: NoteStore, copy: NoteStore, old: Vault, new: Vault) = copy.transaction {
        store.query("SELECT id, name, parent, created, changed FROM folders ORDER BY rowid") { r -> List(5) { r.getObject(it + 1) } }
            .forEach { copy.update("INSERT INTO folders (id, name, parent, created, changed) VALUES (?, ?, ?, ?, ?)", *it.toTypedArray()) }
        store.query("SELECT id, locked, title, created, updated, deleted, changed, trashed, folder FROM notes ORDER BY rowid") { r -> List(9) { r.getObject(it + 1) } }
            .forEach { row ->
                val id = row[0] as ByteArray
                val locked = (row[1] as ByteArray?)?.let { bytes ->
                    val item = old.open(bytes, expecting = ItemKind.NOTE)
                    if (!item.id.contentEquals(id)) throw LockException(LockException.Reason.DAMAGED)
                    new.lock(item.content, ItemKind.NOTE, id)
                }
                copy.update(
                    "INSERT INTO notes (id, locked, title, created, updated, deleted, changed, trashed, folder) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, locked, *row.drop(2).toTypedArray(),
                )
            }
        store.query("SELECT note, words FROM search ORDER BY rowid") { r -> r.getBytes(1) to r.getString(2) }
            .forEach { (note, words) -> copy.update("INSERT INTO search (note, words) VALUES (?, ?)", note, words) }
        store.query("SELECT id, file FROM pictures ORDER BY rowid") { r -> r.getBytes(1) to r.getString(2) }
            .forEach { (id, file) -> copy.update("INSERT INTO pictures (id, file) VALUES (?, ?)", id, file) }
    }

    /** Every picture file, opened with the old vault and locked again with the new one, under the same name. */
    private fun relockPictures(store: NoteStore, home: File, staging: File, old: Vault, new: Vault) {
        for ((id, file) in store.pictureFiles()) {
            val source = File(File(home, "pictures"), file)
            // A picture whose file was already gone stays gone, as it is now; it is not made up.
            if (!source.exists()) continue
            val item = old.open(source.readBytes(), expecting = ItemKind.PICTURE)
            if (!item.id.contentEquals(id.toByteArray())) throw LockException(LockException.Reason.DAMAGED)
            NoteStore.writeAtomically(File(File(staging, "pictures"), file), new.lock(item.content, ItemKind.PICTURE, id.toByteArray()))
        }
    }

    /**
     * Opens the new folder from its own header with the new passphrase, as the next unlock
     * will, and compares everything in it with [store]: every note, deleted or not, every
     * folder, every picture that could be read before, and search.
     */
    private fun check(staging: File, store: NoteStore, passphrase: String) {
        NoteStore(staging, unlockVault(staging, passphrase)).use { copy ->
            fun same(what: String, a: Any?, b: Any?) {
                if (a != b) throw CopyDiffersException(what)
            }
            same("the folders", copy.folders(), store.folders())
            same("the list of notes", copy.summaries(), store.summaries())
            same("the Trash", copy.trash(), store.trash())
            val notes = store.everyNote()
            same("the notes", copy.everyNote().map { it.id }, notes.map { it.id })
            for (note in notes) {
                same("a note", copy.load(note.id), store.load(note.id))
                same("a note's change time", copy.lastChanged(note.id), store.lastChanged(note.id))
                same("a note's folder", copy.folderOf(note.id), store.folderOf(note.id))
            }
            same("the deleted notes", copy.deletedIds(), store.deletedIds())
            for ((id, _) in store.pictureFiles()) {
                same("a picture", runCatching { copy.picture(id) }.getOrNull()?.toList(), runCatching { store.picture(id) }.getOrNull()?.toList())
            }
            same("search", copy.searchRows(), store.searchRows())
        }
    }

    private fun NoteStore.deletedIds(): List<Id> =
        query("SELECT id FROM notes WHERE deleted IS NOT NULL ORDER BY id") { Id.copyFrom(it.getBytes(1)) }

    private fun NoteStore.searchRows(): List<String> =
        query("SELECT note, words FROM search ORDER BY note") { Id.copyFrom(it.getBytes(1)).hex + ":" + it.getString(2) }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
