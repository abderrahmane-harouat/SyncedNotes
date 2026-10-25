package com.syncednotes.mac.agent

import com.syncednotes.core.NoteFormat
import com.syncednotes.core.NoteStore
import com.syncednotes.core.createVault
import com.syncednotes.core.newId
import com.syncednotes.engine.paste.MarkdownReader
import com.syncednotes.engine.paste.appendFragment
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.TextDocument
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An AI agent's view of the notes: one folder, and nothing outside it. Every note is made up. */
class AgentNotesTest {
    private val home = Files.createTempDirectory("syncednotes-agent-").toFile().resolve("SyncedNotes-Test")
    private val store = NoteStore(home.also { it.mkdirs() }, createVault(home, "made-up passphrase"))
    private val shared = store.createFolder("Shared")
    private val inside = store.createFolder("Inside", shared)
    private val other = store.createFolder("Private")

    private fun save(markdown: String, folder: com.syncednotes.core.Id?) = newId().also { id ->
        val doc = TextDocument().also { it.appendFragment(MarkdownReader.read(markdown)) }
        store.save(NoteFormat().encode(doc), id, folder = folder)
    }

    private val agent get() = AgentNotes(store, shared)

    @Test
    fun theAgentSeesTheSharedFolderAndTheFoldersInsideItAndNothingElse() {
        save("# Shopping\nmilk", shared)
        save("# Deep note\nhere", inside)
        save("# Diary\nokapi-secret-4411", other)
        save("# Loose\nokapi-secret-4412", null)
        val listed = agent.listNotes().text
        assertTrue("Shopping" in listed && "Deep note" in listed && "Inside" in listed)
        assertFalse("Diary" in listed || "Loose" in listed || "Private" in listed)
        // Words only the notes outside hold find nothing: no title, no text of theirs.
        val found = agent.search("okapi").text
        assertTrue(found.startsWith("No note"))
        assertFalse("Diary" in found || "Loose" in found || "4411" in found || "4412" in found)
    }

    @Test
    fun aNoteOutsideTheFolderReadsAsNoNoteAtAllWhateverIsAskedOfIt() {
        val diary = save("# Diary\nprivate", other).hex()
        val outside = listOf<() -> Unit>(
            { agent.read(diary) },
            { agent.replace(diary, "private", "public") },
            { agent.append(diary, "more") },
            { agent.write(diary, "gone") },
            { agent.trash(diary) },
        )
        for (attempt in outside) {
            val e = assertFailsWith<AgentNotes.AgentError> { attempt() }
            assertTrue("no note" in e.message.orEmpty())
        }
        assertEquals("Diary\nprivate", store.load(com.google.protobuf.ByteString.copyFrom(diary.chunked(2).map { it.toInt(16).toByte() }.toByteArray())).paragraphsList.joinToString("\n") { it.text })
    }

    @Test
    fun aNoteIsReadAsMarkdownAndCreatedFromIt() {
        val id = agent.create("# Plan\n- **one**\n```cpp\nint x;\n```", "Inside").text.substringAfter("note ").removeSuffix(".")
        assertEquals("# Plan\n- **one**\n```cpp\nint x;\n```", agent.read(id).text)
        assertTrue("Plan" in AgentNotes(store, shared).listNotes().text)
    }

    @Test
    fun replacingTextKeepsTheFormattingAroundIt() {
        val doc = TextDocument().also { it.replace(0, 0, "Buy milk today") }
        doc.setStyle(CharStyle.BOLD, true, 0, 3)
        doc.setStyle(CharStyle.UNDERLINE, true, 9, 14)
        val id = newId()
        store.save(NoteFormat().encode(doc), id, folder = shared)
        agent.replace(id.hex(), "milk", "bread")
        val back = TextDocument().also { NoteFormat().decode(store.load(id), it) }
        assertEquals("Buy bread today", back.toString())
        assertTrue(back.hasStyle(CharStyle.BOLD, 0))
        assertTrue(back.hasStyle(CharStyle.UNDERLINE, back.length - 1))
    }

    @Test
    fun textToReplaceMustNameOnePlace() {
        val id = save("one one", shared).hex()
        assertFailsWith<AgentNotes.AgentError> { agent.replace(id, "one", "two") }
        assertFailsWith<AgentNotes.AgentError> { agent.replace(id, "three", "two") }
    }

    @Test
    fun aNoteMovedToTheTrashIsOutOfTheAgentsReach() {
        val id = save("# Old", shared).hex()
        agent.trash(id)
        assertFailsWith<AgentNotes.AgentError> { agent.read(id) }
    }

    @Test
    fun foldersAreMadeOnlyInsideTheSharedFolder() {
        agent.createFolder("Ideas", "Inside")
        assertTrue(store.folders().any { it.name == "Ideas" && it.parent == inside })
        assertFailsWith<AgentNotes.AgentError> { agent.create("x", "Private") }
        assertFailsWith<AgentNotes.AgentError> { agent.createFolder("Sneaky", "../Private") }
    }
}
