package com.syncednotes.mac.agent

import com.syncednotes.core.Id
import com.syncednotes.core.NoteFormat
import com.syncednotes.core.NoteStore
import com.syncednotes.core.newId
import com.syncednotes.engine.paste.MarkdownReader
import com.syncednotes.engine.paste.MarkdownWriter
import com.syncednotes.engine.paste.appendFragment
import com.syncednotes.engine.text.TextDocument
import com.google.protobuf.ByteString
import syncednotes.note.v1.Note

/**
 * What an AI agent may do with the notes: only those in one folder, the one shared with it,
 * and the folders inside it. Every other note, folder and title is beyond it: a note outside
 * reads as no note at all, so the agent cannot even learn that it exists.
 *
 * Notes go to the agent as Markdown and come back from it as Markdown or as exact text to
 * replace, and are saved locked like any note, so sync carries the agent's changes to the
 * phone. A note the engine cannot edit (one with a picture amid its text, a table) is only
 * read, never written, as everywhere in the app.
 */
class AgentNotes(private val store: NoteStore, private val root: Id) {
    class AgentError(message: String) : Exception(message)

    /** What a call did: its answer for the agent, and the notes it changed, to show again. */
    class Result(val text: String, val changed: Set<Id> = emptySet())

    private val folders: List<NoteStore.Folder> = store.folders()
    private val rootFolder = folders.firstOrNull { it.id == root } ?: throw AgentError("The folder shared with the agent no longer exists.")

    /** The shared folder and every folder inside it. */
    private val inside: Set<Id> = buildSet {
        add(root)
        var grew = true
        while (grew) {
            grew = false
            for (f in folders) if (f.parent in this && add(f.id)) grew = true
        }
    }

    val folderName: String get() = rootFolder.name

    /** A folder's place below the shared one: "" for the shared folder itself, "Recipes/Cakes". */
    private fun pathOf(folder: Id): String {
        val names = ArrayList<String>()
        var at: Id? = folder
        while (at != null && at != root) {
            val f = folders.first { it.id == at }
            names += f.name
            at = f.parent
        }
        return names.reversed().joinToString("/")
    }

    private fun folderAt(path: String?): Id {
        val parts = path.orEmpty().split('/').map { it.trim() }.filter { it.isNotEmpty() }
        var at = root
        for (name in parts) {
            at = folders.firstOrNull { it.parent == at && it.name == name }?.id
                ?: throw AgentError("There is no folder \"$path\" in the shared folder. Use list_notes to see its folders, or create_folder.")
        }
        return at
    }

    private fun notes(search: String? = null): List<NoteStore.Summary> =
        store.summaries(search).filter { it.folder in inside }

    /** The note [id] names, if it is in the shared folder and not in the Trash. */
    private fun noteId(id: String): Id {
        val bytes = id.trim().takeIf { it.length == 32 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
        val note = bytes?.let { ByteString.copyFrom(it) }
        if (note == null || store.isDeleted(note) || store.isInTrash(note) || store.folderOf(note) !in inside) {
            throw AgentError("There is no note $id in the shared folder. Use list_notes or search_notes to find one.")
        }
        return note
    }

    private fun summaryLine(s: NoteStore.Summary): String {
        val where = s.folder?.let(::pathOf).orEmpty()
        return "- ${s.id.hex()}  ${s.title.ifEmpty { "(untitled)" }}" + (if (where.isNotEmpty()) "  [folder: $where]" else "") + "  (edited ${s.updated})"
    }

    fun listNotes(): Result {
        val sub = folders.filter { it.id in inside && it.id != root }.map { pathOf(it.id) }.sorted()
        val list = notes().joinToString("\n", transform = ::summaryLine)
        return Result(
            buildString {
                append("Shared folder: ${rootFolder.name}\n")
                if (sub.isNotEmpty()) append("Folders inside it: ${sub.joinToString(", ")}\n")
                append(if (list.isEmpty()) "No notes yet." else "Notes, newest first:\n$list")
            },
        )
    }

    fun search(query: String): Result {
        val found = notes(query)
        return Result(if (found.isEmpty()) "No note in the shared folder matches \"$query\"." else found.joinToString("\n", transform = ::summaryLine))
    }

    fun read(id: String): Result {
        val note = noteId(id)
        val stored = store.load(note)
        val markdown = MarkdownWriter.write(document(stored))
        val readOnly = if (NoteFormat.canEdit(stored)) "" else "\n\n(This note holds something only the app can show, such as a table; it can be read here but not changed.)"
        return Result(markdown + readOnly)
    }

    fun create(markdown: String, folder: String?): Result {
        val at = folderAt(folder)
        val doc = TextDocument()
        doc.appendFragment(MarkdownReader.read(markdown))
        val id = newId()
        store.save(NoteFormat().encode(doc), id, folder = at)
        return Result("Created note ${id.hex()}.", setOf(id))
    }

    fun replace(id: String, old: String, new: String): Result {
        val note = noteId(id)
        val (format, doc) = editable(note)
        if (old.isEmpty()) throw AgentError("old_text is empty; give the exact text to replace, as read_note shows it without Markdown signs.")
        val text = doc.toString()
        val at = text.indexOf(old)
        if (at < 0) throw AgentError("old_text is not in the note. It must match the note's text exactly, without Markdown signs such as ** or #.")
        if (text.indexOf(old, at + 1) >= 0) throw AgentError("old_text appears more than once in the note; give more of the text around it, so it names one place.")
        doc.replace(at, at + old.length, new)
        save(note, format.encode(doc))
        return Result("Replaced the text in note ${note.hex()}.", setOf(note))
    }

    fun write(id: String, markdown: String): Result {
        val note = noteId(id)
        val (_, doc) = editable(note)
        if ((0 until doc.paragraphCount).any { doc.paragraphStyle(it).picture != 0 }) {
            throw AgentError("This note has pictures, which Markdown cannot hold, so it cannot be rewritten whole. Use replace_in_note or append_to_note.")
        }
        val fresh = TextDocument()
        fresh.appendFragment(MarkdownReader.read(markdown))
        save(note, NoteFormat().encode(fresh))
        return Result("Rewrote note ${note.hex()}.", setOf(note))
    }

    fun append(id: String, markdown: String): Result {
        val note = noteId(id)
        val (format, doc) = editable(note)
        doc.appendFragment(MarkdownReader.read(markdown))
        save(note, format.encode(doc))
        return Result("Added to note ${note.hex()}.", setOf(note))
    }

    fun createFolder(name: String, parent: String?): Result {
        val clean = name.trim()
        if (clean.isEmpty() || '/' in clean) throw AgentError("A folder's name must not be empty or hold a slash.")
        val at = folderAt(parent)
        if (folders.any { it.parent == at && it.name == clean }) throw AgentError("There is already a folder \"$clean\" there.")
        store.createFolder(clean, at)
        return Result("Created the folder \"${listOfNotNull(parent?.trim('/')?.ifEmpty { null }, clean).joinToString("/")}\".")
    }

    fun trash(id: String): Result {
        val note = noteId(id)
        store.moveToTrash(note)
        return Result("Moved note ${note.hex()} to the Trash, where it stays 30 days.", setOf(note))
    }

    private fun document(note: Note): TextDocument = TextDocument().also { NoteFormat().decode(note, it) }

    /** The note read into the engine, with the format that read it, if it may be changed. */
    private fun editable(note: Id): Pair<NoteFormat, TextDocument> {
        val stored = store.load(note)
        if (!NoteFormat.canEdit(stored)) throw AgentError("This note holds something only the app can show, such as a table, and is never changed from outside it.")
        val format = NoteFormat()
        val doc = TextDocument()
        format.decode(stored, doc)
        return format to doc
    }

    private fun save(id: Id, note: Note) = store.save(note, id)
}

/** An ID as the agent sees it: 32 hexadecimal digits. */
fun Id.hex(): String = toByteArray().joinToString("") { "%02x".format(it) }
