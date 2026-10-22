package com.syncednotes.mac

import com.syncednotes.core.NoteStore
import com.syncednotes.core.createVault
import com.syncednotes.core.newId
import java.io.File
import java.time.Instant
import syncednotes.note.v1.Embedded
import syncednotes.note.v1.Divider
import syncednotes.note.v1.Highlight
import syncednotes.note.v1.ListKind
import syncednotes.note.v1.ListLevel
import syncednotes.note.v1.Note
import syncednotes.note.v1.Paragraph
import syncednotes.note.v1.Role
import syncednotes.note.v1.Run

/** The made-up passphrase of the made-up try-out notes. Guards nothing. */
const val TRY_PASSPHRASE = "made-up try passphrase"

/**
 * Writes made-up notes into `~/Library/Application Support/SyncedNotes-Try`, replacing what
 * is there: never the real notes' folder.
 */
fun main() {
    val home = File(System.getProperty("user.home"), "Library/Application Support/SyncedNotes-Try")
    home.deleteRecursively()
    NoteStore(home, createVault(home, TRY_PASSPHRASE)).use { store ->
        val recipes = store.createFolder("Recipes")
        val work = store.createFolder("Work")
        store.createFolder("Meetings", work)
        var t = Instant.now().minusSeconds(86_400 * 3)
        fun save(note: Note, folder: com.syncednotes.core.Id? = null) {
            t = t.plusSeconds(3_600)
            store.save(note, newId(), now = t, folder = folder)
        }
        save(note(p("Weekend plan", Role.ROLE_TITLE), p("Things to get done before Monday, in no particular order."), check("Water the plants", true), check("Call the bakery"), check("Fix the bike's back light")))
        save(note(p("Lemon cake", Role.ROLE_TITLE), p("Ingredients", Role.ROLE_HEADING), list("200 g flour", ListKind.LIST_KIND_BULLETED, 1), list("3 eggs", ListKind.LIST_KIND_BULLETED, 1), list("2 lemons, zest and juice", ListKind.LIST_KIND_BULLETED, 1), p("Method", Role.ROLE_HEADING), list("Beat the eggs and sugar.", ListKind.LIST_KIND_NUMBERED, 2), list("Fold in the flour.", ListKind.LIST_KIND_NUMBERED, 2), list("Bake 35 minutes at 180 °C.", ListKind.LIST_KIND_NUMBERED, 2)), recipes)
        save(note(p("Books to read", Role.ROLE_TITLE), p("A few that came up this month."), list("The Pragmatic Programmer", ListKind.LIST_KIND_BULLETED, 4), list("A Philosophy of Software Design", ListKind.LIST_KIND_BULLETED, 4)))
        save(
            note(
                p("Project notes", Role.ROLE_TITLE),
                p("The release moves to Thursday; everything else stays.").addRuns(run(4, 7).setBold(true)).addRuns(run(21, 8).setHighlight(Highlight.HIGHLIGHT_YELLOW)),
                p("Done is better than perfect.", Role.ROLE_QUOTE),
                p("make build && make test", Role.ROLE_CODE_BLOCK),
                p("￼", Role.ROLE_DIVIDER).addRuns(run(0, 1).setEmbedded(Embedded.newBuilder().setDivider(Divider.getDefaultInstance()))),
                p("Open questions", Role.ROLE_SUBHEADING),
                list("Who reviews the copy?", ListKind.LIST_KIND_DASHED, 3),
                list("Do we need a second build?", ListKind.LIST_KIND_DASHED, 3),
            ),
            work,
        )
        save(
            note(
                p("ملاحظات الأسبوع", Role.ROLE_TITLE),
                p("هذه ملاحظة مختلقة لتجربة الكتابة من اليمين إلى اليسار، مع كلمة English في وسطها."),
                p("السَّلامُ عَلَيْكُم، والأرقام 2026 تبقى من اليسار."),
                list("شراء الخبز", ListKind.LIST_KIND_NUMBERED, 5),
                list("الاتصال بالمخبز", ListKind.LIST_KIND_NUMBERED, 5),
                check("سقي النباتات", true),
                p("An English line with عربي inside it, and (brackets)."),
            ),
        )
        val old = newId()
        store.save(note(p("Old shopping list", Role.ROLE_TITLE), p("milk, bread")), old, now = t.minusSeconds(90_000))
        store.moveToTrash(old)
    }
    println("Made-up notes written to $home")
}

private fun note(vararg paragraphs: Paragraph.Builder): Note = Note.newBuilder().setFormatVersion(1).addAllParagraphs(paragraphs.map { it.build() }).build()
private fun p(text: String, role: Role = Role.ROLE_BODY) = Paragraph.newBuilder().setText(text).setRole(role)
private fun check(text: String, checked: Boolean = false) = p(text, Role.ROLE_CHECKLIST).setChecked(checked)
private fun list(text: String, kind: ListKind, id: Int) = p(text).addLists(ListLevel.newBuilder().setKind(kind).setList(id))
private fun run(start: Int, length: Int) = Run.newBuilder().setStart(start).setLength(length)
