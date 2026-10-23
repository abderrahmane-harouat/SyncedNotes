package com.syncednotes.mac

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.syncednotes.core.Backup
import com.syncednotes.core.Id
import com.syncednotes.core.LockException
import com.syncednotes.core.NoteStore
import com.syncednotes.core.PassphraseChange
import com.syncednotes.core.Vault
import com.syncednotes.core.createVault
import com.syncednotes.core.isEmpty
import com.syncednotes.core.newId
import com.syncednotes.core.unlockVault
import com.syncednotes.core.vaultHeaderFile
import com.syncednotes.engine.editor.Composer
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role
import com.syncednotes.mac.format.NoteFormat
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import syncednotes.note.v1.Note
import syncednotes.note.v1.Backup as StoredBackup

/**
 * A note open in the editor. Compared by ID only: the editor loads a note when the ID changes,
 * and otherwise leaves its own text alone (the engine owns the text).
 */
class OpenNote(
    val id: Id,
    /** As last saved, or as opened: what the editor is given if it has to load the note again. */
    note: Note,
    /** A note in the Trash is shown, not edited: it has to be restored first. */
    val inTrash: Boolean = false,
    /** Where a new note goes when it is first saved; a note saved before stays where it is. */
    val folder: Id? = null,
    /** False for a note holding something the engine cannot edit yet: it is shown, never saved. */
    val editable: Boolean = true,
) {
    var note: Note = note
        internal set

    override fun equals(other: Any?) = other is OpenNote && other.id == id
    override fun hashCode() = id.hashCode()
}

/**
 * The notes, locked or unlocked, and everything that saves them: the Swift app's NotesModel,
 * with the same rules.
 *
 * Saving: a note is saved a moment after typing stops, and always before switching notes,
 * locking, or quitting. A note left empty is removed when it is left, as in Notes.
 */
class NotesModel(private val home: File, private val scope: CoroutineScope) {

    sealed interface Phase {
        /** Nothing is unlocked. [firstRun] when no passphrase has been chosen yet. */
        data class Locked(val firstRun: Boolean) : Phase
        data object Unlocked : Phase
    }

    /** What the sidebar can show in the list of notes. */
    sealed interface Place {
        data object AllNotes : Place
        /** The notes in no folder. */
        data object NoFolder : Place
        data class InFolder(val id: Id) : Place
        data object Trash : Place
    }

    var phase: Phase by mutableStateOf(Phase.Locked(firstRun = !vaultHeaderFile(home).exists()))
        private set
    var summaries: List<NoteStore.Summary> by mutableStateOf(emptyList())
        private set
    /** Which notes the list shows. */
    var place: Place by mutableStateOf(Place.AllNotes)
        private set
    val showingTrash: Boolean get() = place == Place.Trash
    var trashCount by mutableIntStateOf(0)
        private set
    /** Every folder, parents before the folders inside them. */
    var folders: List<NoteStore.Folder> by mutableStateOf(emptyList())
        private set
    /** How many notes each folder holds itself; null for the notes in no folder. */
    var noteCounts: Map<Id?, Int> by mutableStateOf(emptyMap())
        private set
    /** The folders shown open in the sidebar. */
    var expandedFolders: Set<Id> by mutableStateOf(emptySet())
    /** The folder whose name is being typed in the sidebar: a new one, or one being renamed. */
    var renamingFolder: Id? by mutableStateOf(null)
    /** The note open in the editor. One selected note is simply the open note. */
    var document: OpenNote? by mutableStateOf(null)
        private set
    /** Notes selected together, when more than one is; none is open then. */
    var selectedNotes: Set<Id> by mutableStateOf(emptySet())
        private set
    /** What the list shows as selected. */
    val selection: Set<Id> get() = selectedNotes.ifEmpty { setOfNotNull(document?.id) }
    var changingPassphrase by mutableStateOf(false)
    /** Shown to the user when something could not be saved or opened. */
    var problem: String? by mutableStateOf(null)
    /** Shown when something worked that the user should hear about: a backup made. */
    var notice: String? by mutableStateOf(null)
    /** A backup from another vault, waiting for that vault's passphrase before it can be read. */
    var importWaitingForPassphrase: ByteArray? by mutableStateOf(null)
    var search: String = ""
        set(value) {
            field = value
            searchShown = value
            refresh()
        }
    /** [search], for the field to show. */
    var searchShown by mutableStateOf("")
        private set

    private var store: NoteStore? = null
    /** The engine showing the open note; the note's text lives there while it is open. */
    private var editor: Composer? = null
    /** What read the open note into the engine, and writes it out again. */
    private var format = NoteFormat()
    /** Which note the editor holds now. */
    private var editorNote: Id? = null
    private var unsaved = false
    private var loading = false
    private var pendingSave: Job? = null

    init {
        // A passphrase change the app was stopped in the middle of is finished, or dropped,
        // before anything is read: see PassphraseChange.
        try {
            PassphraseChange.finishInterruptedChange(home)
        } catch (e: Exception) {
            problem = "A passphrase change that was stopped part way could not be finished: ${e.message}"
        }
        phase = Phase.Locked(firstRun = !vaultHeaderFile(home).exists())
    }

    // --- Locking --------------------------------------------------------------------------

    class Message(text: String) : Exception(text)

    /** First run: makes the vault. Throws a [Message] fit to show. */
    fun create(passphrase: String, repeated: String) {
        if (passphrase.length < SHORTEST_PASSPHRASE) {
            throw Message("Use at least $SHORTEST_PASSPHRASE characters. There is no way to recover it, so a longer one is worth it.")
        }
        if (passphrase != repeated) throw Message("The two passphrases are not the same.")
        open(createVault(home, passphrase))
    }

    /** Unlocks with [passphrase]; false if it is wrong. */
    fun unlock(passphrase: String): Boolean = try {
        open(unlockVault(home, passphrase))
        true
    } catch (e: LockException) {
        if (e.reason != LockException.Reason.WRONG_PASSPHRASE) problem = "The notes could not be opened: ${e.message}"
        false
    } catch (e: Exception) {
        problem = "The notes could not be opened: ${e.message}"
        false
    }

    private fun open(vault: Vault) {
        val opened = NoteStore(home, vault)
        // As in Apple Notes: a note stays in the Trash for 30 days, then goes for good.
        opened.emptyTrash(NoteStore.TRASH_KEEPS_FOR_SECONDS)
        store = opened
        phase = Phase.Unlocked
        refresh()
        summaries.firstOrNull()?.let { select(it.id) }
    }

    /** Saves, then forgets the keys and everything decrypted. */
    fun lock() {
        leaveCurrentNote()
        store?.close()
        store = null
        document = null
        summaries = emptyList()
        folders = emptyList()
        noteCounts = emptyMap()
        selectedNotes = emptySet()
        place = Place.AllNotes
        search = ""
        editor?.load { }
        editorNote = null
        phase = Phase.Locked(firstRun = false)
    }

    // --- The editor -----------------------------------------------------------------------

    /** The engine on screen. Its document's changes are what gets saved. */
    fun editorAppeared(composer: Composer) {
        if (editor === composer) return
        // A new engine (the appearance changed) loads the note again: save what the old one
        // holds first, or the typing since the last save would be lost.
        saveNow()
        editor = composer
        composer.document.addListener { if (!loading) contentChanged() }
        composer.document.addFormatListener { _, _ -> if (!loading) contentChanged() }
        editorNote = null
        showInEditor()
    }

    /** Puts the open note into the engine, unless it holds it already. */
    private fun showInEditor() {
        val composer = editor ?: return
        val open = document
        if (open?.id == editorNote) return
        loading = true
        try {
            format = NoteFormat()
            composer.load { doc ->
                if (open == null) return@load
                if (open.note.paragraphsCount == 0 || open.note.isEmpty) {
                    // An empty note starts with its title, as the first line is the note's
                    // name in the list anyway; Return ends it and the body follows.
                    doc.setParagraphStyle(0, ParagraphStyle(role = Role.TITLE))
                } else {
                    format.decode(open.note, doc)
                }
            }
            composer.editable = open != null && open.editable && !open.inTrash
        } finally {
            loading = false
        }
        editorNote = open?.id
    }

    // --- Notes ----------------------------------------------------------------------------

    /** Selects the notes [ids]: one is opened; several are selected together, with none open. */
    fun selectNotes(ids: Set<Id>) {
        if (ids.size <= 1) {
            selectedNotes = emptySet()
            select(ids.firstOrNull())
            return
        }
        if (ids == selectedNotes) return
        leaveCurrentNote()
        selectedNotes = ids
    }

    fun select(id: Id?) {
        if (id == null || id != document?.id) selectedNotes = emptySet()
        val store = store ?: return
        if (id == document?.id) return
        leaveCurrentNote()
        if (id == null) return
        try {
            val stored = store.load(id)
            document = OpenNote(id, stored, inTrash = store.isInTrash(id), editable = NoteFormat.canEdit(stored))
        } catch (e: Exception) {
            problem = "This note could not be opened: ${e.message}"
            document = null
        }
        showInEditor()
    }

    /** A new note, in the folder the list shows, if it shows one. */
    fun newNote() {
        if (store == null) return
        if (showingTrash) show(Place.AllNotes)
        leaveCurrentNote()
        val folder = (place as? Place.InFolder)?.id
        document = OpenNote(newId(), Note.getDefaultInstance(), folder = folder)
        showInEditor()
        // Saved once so it shows in the list straight away; removed again if left empty.
        unsaved = true
        saveNow(force = true)
    }

    /** Moves notes to the Trash, from where they can be restored for 30 days. */
    fun moveToTrash(ids: Set<Id>) {
        val store = store ?: return
        document?.id?.takeIf { it in ids }?.let {
            saveNow()
            closeEditor()
        }
        for (id in ids) {
            try { store.moveToTrash(id) } catch (e: Exception) { problem = "A note could not be moved to the Trash: ${e.message}" }
        }
        selectedNotes = selectedNotes - ids
        refresh()
        if (document == null && selectedNotes.isEmpty()) summaries.firstOrNull()?.let { select(it.id) }
    }

    /** Brings notes back from the Trash, and shows them among the notes. */
    fun restoreFromTrash(ids: Set<Id>) {
        val store = store ?: return
        for (id in ids) {
            try { store.restoreFromTrash(id) } catch (e: Exception) { problem = "A note could not be restored: ${e.message}"; return }
        }
        closeEditor()
        show(Place.AllNotes)
        closeEditor()
        selectNotes(ids)
    }

    /** Deletes notes for good. Only from the Trash, and only after asking. */
    fun deletePermanently(ids: Set<Id>) {
        val store = store ?: return
        if (document?.id in ids) closeEditor()
        for (id in ids) {
            try { store.delete(id) } catch (e: Exception) { problem = "A note could not be deleted: ${e.message}" }
        }
        selectedNotes = selectedNotes - ids
        refresh()
        if (document == null && selectedNotes.isEmpty()) summaries.firstOrNull()?.let { select(it.id) }
    }

    fun emptyTrash() {
        val store = store ?: return
        if (document?.inTrash == true) closeEditor()
        try { store.emptyTrash() } catch (e: Exception) { problem = "The Trash could not be emptied: ${e.message}" }
        refresh()
    }

    /** Shows [place] in the list, and opens its newest note. */
    fun show(place: Place) {
        if (place == this.place) return
        leaveCurrentNote()
        selectedNotes = emptySet()
        this.place = place
        // A folder with folders inside opens as it is chosen: choosing a folder and seeing
        // nothing inside it looked like the app had not responded.
        if (place is Place.InFolder && folders.any { it.parent == place.id }) expandedFolders = expandedFolders.plusElement(place.id)
        search = ""
        summaries.firstOrNull()?.let { select(it.id) }
    }

    fun refresh() {
        val store = store ?: return
        try {
            val trash = store.trash()
            trashCount = trash.size
            folders = store.folders()
            noteCounts = store.noteCounts()
            // A folder deleted while it was shown leaves the list on the notes in no folder.
            val shown = place
            if (shown is Place.InFolder && folders.none { it.id == shown.id }) place = Place.NoFolder
            val words = search.ifEmpty { null }
            summaries = when (val p = place) {
                Place.Trash -> trash.filter { search.isEmpty() || it.title.contains(search, ignoreCase = true) }
                Place.AllNotes -> store.summaries(words)
                Place.NoFolder -> store.summaries(words, NoteStore.Scope.NoFolder)
                is Place.InFolder -> store.summaries(words, NoteStore.Scope.InFolder(p.id))
            }
            // Notes that left the list (moved, trashed, filtered out) leave the selection.
            val listed = summaries.map { it.id }.toSet()
            selectedNotes = selectedNotes.filter { it in listed }.toSet().takeIf { it.size >= 2 } ?: emptySet()
        } catch (e: Exception) {
            problem = "The list of notes could not be read: ${e.message}"
        }
    }

    // --- Folders --------------------------------------------------------------------------

    /** Makes a folder inside [parent], or at the top, shows it, and asks for its name. */
    fun newFolder(parent: Id? = null) {
        val store = store ?: return
        val taken = folders.filter { it.parent == parent }.map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) "New Folder" else "New Folder $it" }.first { it !in taken }
        try {
            val id = store.createFolder(name, parent)
            if (parent != null) expandedFolders = expandedFolders.plusElement(parent)
            refresh()
            show(Place.InFolder(id))
            renamingFolder = id
        } catch (e: Exception) {
            problem = "The folder could not be made: ${e.message}"
        }
    }

    /** Gives a folder a new name. An empty name leaves the name it had. */
    fun renameFolder(id: Id, name: String) {
        renamingFolder = null
        val trimmed = name.trim()
        val store = store ?: return
        if (trimmed.isEmpty()) return
        try { store.renameFolder(id, trimmed) } catch (e: Exception) { problem = "The folder could not be renamed: ${e.message}" }
        refresh()
    }

    /**
     * Moves a folder into another, or to the top with null. Into itself, or a folder inside
     * it, nothing happens: that is a drop in the wrong place, not something to explain.
     */
    fun moveFolder(id: Id, parent: Id?) {
        val store = store ?: return
        if (id == parent) return
        try {
            store.moveFolder(id, parent)
            if (parent != null) expandedFolders = expandedFolders.plusElement(parent)
        } catch (e: NoteStore.StoreException) {
            if (e.reason == NoteStore.StoreException.Reason.FOLDER_INSIDE_ITSELF) return
            problem = "The folder could not be moved: ${e.message}"
        }
        refresh()
    }

    /** How many notes deleting a folder would send to the Trash, for asking first. */
    fun noteCountDeletingFolder(id: Id): Int = runCatching { store?.noteCountDeletingFolder(id) }.getOrNull() ?: 0

    /** Deletes a folder and the folders inside it; their notes go to the Trash. */
    fun deleteFolder(id: Id) {
        val store = store ?: return
        // The note on screen may be one of those going to the Trash: saved first, and closed.
        leaveCurrentNote()
        try { store.deleteFolder(id) } catch (e: Exception) { problem = "The folder could not be deleted: ${e.message}" }
        expandedFolders = expandedFolders.minusElement(id)
        refresh()
        summaries.firstOrNull()?.let { select(it.id) }
    }

    /** Moves notes into [folder], or out of every folder with null. */
    fun moveNotes(ids: Set<Id>, folder: Id?) {
        val store = store ?: return
        if (document?.id in ids) saveNow(force = true)
        for (id in ids) {
            try { store.moveNote(id, folder) } catch (e: Exception) { problem = "A note could not be moved: ${e.message}" }
        }
        refresh()
    }

    /** The folder's name, for the list's title and the menus. */
    fun nameOf(folder: Id?): String = folders.firstOrNull { it.id == folder }?.name ?: "Notes"

    /** How deep a folder is: 0 at the top. */
    fun depthOf(folder: NoteStore.Folder): Int {
        var depth = 0
        var parent = folder.parent
        while (parent != null) {
            val above = folders.firstOrNull { it.id == parent } ?: break
            depth++
            parent = above.parent
        }
        return depth
    }

    // --- Saving ---------------------------------------------------------------------------

    /** The note changed: save it once typing pauses. */
    fun contentChanged() {
        unsaved = true
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(800)
            saveNow()
        }
    }

    /** Saves the note on screen now, if it has changed. */
    fun saveNow(force: Boolean = false) {
        pendingSave?.cancel()
        val store = store ?: return
        val open = document ?: return
        // A note in the Trash, or one the engine cannot edit, is only shown: nothing about it
        // is saved.
        if (!(unsaved || force) || open.inTrash || !open.editable) return
        val note = noteOf(open) ?: return
        try {
            store.save(note, open.id, folder = open.folder)
            open.note = note
            unsaved = false
            refresh()
        } catch (e: Exception) {
            // Kept as unsaved, so the next change or switch tries again; and said out loud.
            problem = "The note could not be saved: ${e.message}"
        }
    }

    /**
     * The note to save for [open]: the engine's, if it holds that very note; otherwise the note
     * as it was loaded. Taking the editor's text without asking which note it holds once saved
     * the previous note's whole text into every new one.
     */
    private fun noteOf(open: OpenNote): Note? {
        val composer = editor
        if (composer != null && editorNote == open.id) return format.encode(composer.document)
        return open.note
    }

    /** Saves the note being left, and removes it if nothing was ever put in it. */
    private fun leaveCurrentNote() {
        saveNow()
        val open = document ?: return
        val store = store ?: return
        if (!open.inTrash && open.editable && noteOf(open)?.isEmpty == true) {
            // Straight out, not to the Trash: there is nothing in it to want back.
            runCatching { store.delete(open.id) }
            refresh()
        }
        closeEditor()
    }

    private fun closeEditor() {
        document = null
        showInEditor()
    }

    // --- Changing the passphrase ----------------------------------------------------------

    /**
     * Changes the passphrase, locking every note and picture again under it. Throws a
     * [Message] fit to show; nothing is changed then. The slow part runs away from the window's
     * thread; the dialog asking for it covers the window, so nothing is written meanwhile.
     */
    suspend fun changePassphrase(current: String, new: String, repeated: String) {
        if (new.length < SHORTEST_PASSPHRASE) throw Message("Use at least $SHORTEST_PASSPHRASE characters for the new passphrase.")
        if (new != repeated) throw Message("The two new passphrases are not the same.")
        if (new == current) throw Message("That is the passphrase you have now.")
        val store = store ?: return
        saveNow()
        val vault = try {
            withContext(Dispatchers.IO) { PassphraseChange.prepare(store, home, current, new) }
        } catch (e: LockException) {
            if (e.reason == LockException.Reason.WRONG_PASSPHRASE) throw Message("That is not your current passphrase.")
            throw Message("The passphrase was not changed, and your notes are as they were. ${e.message}")
        } catch (e: Exception) {
            throw Message("The passphrase was not changed, and your notes are as they were. ${e.message}")
        }

        // Swapped with the notes closed, so nothing can write to the folder as it moves.
        val open = document?.id
        val shown = place
        closeEditor()
        store.close()
        this.store = null
        var opened = vault
        try {
            PassphraseChange.swap(home)
        } catch (_: Exception) {
            // Settle on whichever notes are now in place: the change finished, or did not.
            runCatching { PassphraseChange.finishInterruptedChange(home) }
            if (runCatching { unlockVault(home, new) }.isFailure) opened = store.vault
        }
        try {
            this.store = NoteStore(home, opened)
        } catch (e: Exception) {
            lock()
            val which = if (opened.header == vault.header) "the new" else "your current"
            throw Message("The notes could not be opened again; unlock them with $which passphrase. ${e.message}")
        }
        place = shown
        refresh()
        open?.let { select(it) }
        if (opened.header != vault.header) throw Message("The passphrase was not changed, and your notes are as they were.")
    }

    // --- Backups --------------------------------------------------------------------------

    /** The name a new backup file is offered under. */
    fun backupFileName(): String = "SyncedNotes Backup ${LocalDate.now()}.$BACKUP_EXTENSION"

    /** Writes a backup of every note to [file]. */
    fun exportBackup(file: File) {
        saveNow()
        val store = store ?: return
        try {
            NoteStore.writeAtomically(file, Backup.make(store))
            val count = store.summaries().size
            notice = "Backed up $count ${if (count == 1) "note" else "notes"} to “${file.name}”."
        } catch (e: Exception) {
            problem = "The backup could not be written: ${e.message}"
        }
    }

    /** Reads a backup. One from this vault is restored straight away; one from another waits for its passphrase. */
    fun importBackup(file: File) {
        saveNow()
        val store = store ?: return
        try {
            val bytes = file.readBytes()
            val header = Backup.vaultHeader(bytes)
            if (header == store.vault.header) restore(Backup.open(bytes, store.vault)) else importWaitingForPassphrase = bytes
        } catch (_: Backup.NotABackupException) {
            problem = "“${file.name}” is not a SyncedNotes backup."
        } catch (e: Exception) {
            problem = "The backup could not be read: ${e.message}"
        }
    }

    /** Finishes an import from another vault; false if [passphrase] is not that vault's. */
    fun importBackup(passphrase: String): Boolean {
        val file = importWaitingForPassphrase ?: return true
        return try {
            val theirs = Vault.unlock(Backup.vaultHeader(file), passphrase)
            importWaitingForPassphrase = null
            restore(Backup.open(file, theirs))
            true
        } catch (e: LockException) {
            if (e.reason == LockException.Reason.WRONG_PASSPHRASE) return false
            importWaitingForPassphrase = null
            problem = "The backup could not be read: ${e.message}"
            true
        } catch (e: Exception) {
            importWaitingForPassphrase = null
            problem = "The backup could not be read: ${e.message}"
            true
        }
    }

    private fun restore(backup: StoredBackup) {
        val store = store ?: return
        val result = Backup.restore(backup, store)
        val shown = document?.id
        refresh()
        // The note on screen may be one the backup just brought up to date.
        if (shown != null && result.restored > 0) {
            closeEditor()
            select(shown)
        } else if (document == null) {
            summaries.firstOrNull()?.let { select(it.id) }
        }
        notice = describe(result)
    }

    companion object {
        /**
         * The shortest passphrase accepted. Argon2id is set for speed (LockFormat.md), so it is
         * the passphrase's own length that keeps guessing slow.
         */
        const val SHORTEST_PASSPHRASE = 8
        const val BACKUP_EXTENSION = "snbackup"

        /**
         * What an import did, in words. Each kind of note is named for what actually happened
         * to it: an earlier message called deleted notes "already here".
         */
        fun describe(result: Backup.Result): String {
            fun notes(count: Int) = "$count ${if (count == 1) "note" else "notes"}"
            val datesSetBack = if (result.datesSetBack > 0) {
                val one = result.datesSetBack == 1
                " ${if (one) "The date of 1 note was" else "The dates of ${result.datesSetBack} notes were"} set back to when ${if (one) "it was" else "they were"} last edited."
            } else ""
            val keptNewer = if (result.keptNewer > 0) {
                val one = result.keptNewer == 1
                " ${notes(result.keptNewer)} changed here after the backup ${if (one) "was" else "were"} kept as ${if (one) "it is" else "they are"}."
            } else ""
            if (result.restored == 0) {
                if (result.unchanged + result.keptNewer == 0) return "The backup has no notes in it."
                return "Nothing to restore: every note in the backup is already here.$datesSetBack$keptNewer"
            }
            val parts = buildList {
                if (result.undeleted > 0) add("${result.undeleted} that had been deleted")
                if (result.added > 0) add("${result.added} new here")
                if (result.updated > 0) add("${result.updated} brought up to date")
            }
            var text = "Restored ${notes(result.restored)}"
            text += if (parts.size == 1 && result.undeleted > 0) ", which had been deleted." else ": " + parts.joinToString(", ") + "."
            return text + datesSetBack + keptNewer
        }
    }
}
