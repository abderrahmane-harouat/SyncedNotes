package com.syncednotes.mac

import com.syncednotes.core.NoteStore
import com.syncednotes.core.createVault
import com.syncednotes.core.newId
import java.io.File
import java.time.Instant
import syncednotes.note.v1.CalloutColor
import syncednotes.note.v1.CodeLanguage
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
        store.createFolder("Ideas for the garden, the kitchen and the long summer holidays")
        var t = Instant.now().minusSeconds(86_400 * 3)
        fun save(note: Note, folder: com.syncednotes.core.Id? = null) {
            t = t.plusSeconds(3_600)
            store.save(note, newId(), now = t, folder = folder)
        }
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
        save(
            note(
                p("Reading a file in C", Role.ROLE_TITLE),
                p("Counts the lines of a file, closing it on every way out."),
                *code(
                    CodeLanguage.CODE_LANGUAGE_C,
                    "#include <stdio.h>",
                    "",
                    "/* Returns the number of lines,",
                    "   or -1 when the file cannot be read. */",
                    "long count_lines(const char *path) {",
                    "    FILE *f = fopen(path, \"r\");",
                    "    if (f == NULL) return -1;",
                    "    long lines = 0;",
                    "    for (int c; (c = fgetc(f)) != EOF;) if (c == '\\n') lines++;",
                    "    fclose(f);",
                    "    return lines; // 0x0 for an empty file",
                    "}",
                ),
            ),
            work,
        )
        save(
            note(
                p("A small C++ cache", Role.ROLE_TITLE),
                *code(
                    CodeLanguage.CODE_LANGUAGE_CPP,
                    "#include <string>",
                    "#include <unordered_map>",
                    "",
                    "template <typename V>",
                    "class Cache {",
                    "public:",
                    "    explicit Cache(std::size_t limit) : limit_(limit) {}",
                    "    bool put(const std::string& key, V value) {",
                    "        if (items_.size() >= limit_) return false;",
                    "        items_.emplace(key, std::move(value));",
                    "        return true;",
                    "    }",
                    "private:",
                    "    std::size_t limit_ = 1'000;",
                    "    std::unordered_map<std::string, V> items_;",
                    "};",
                    "",
                    "auto banner = R\"(Cache \"ready\")\";",
                ),
            ),
            work,
        )
        save(note(p("Weekend plan 🌿", Role.ROLE_TITLE), p("Things to get done before Monday 😀 👍🏽 🇩🇿 👨‍👩‍👧 ❤️, in no particular order."), check("Water the plants", true), check("Call the bakery"), check("Fix the bike's back light")))
        save(
            note(
                p("Where values live", Role.ROLE_TITLE),
                p("A process's virtual address space on macOS typically contains regions like these:"),
                callout(CalloutColor.CALLOUT_COLOR_BLUE, p("Stack").addRuns(run(0, 5).setBold(true).setRelativeSize(1.125f))),
                callout(CalloutColor.CALLOUT_COLOR_BLUE, p("x = 20 — typically stored here because x is a local variable.").addRuns(run(0, 6).setCode(true)).addRuns(run(39, 1).setCode(true))),
                callout(CalloutColor.CALLOUT_COLOR_BLUE, p("Its lifetime is associated with the function call.").addRuns(run(0, 50).setRelativeSize(0.875f).setColor(GRAY))),
                callout(CalloutColor.CALLOUT_COLOR_GREEN, p("Heap").addRuns(run(0, 4).setBold(true).setRelativeSize(1.125f))),
                callout(CalloutColor.CALLOUT_COLOR_GREEN, p("*p = 30 — the allocated memory contains the integer value 30.").addRuns(run(0, 7).setCode(true)).addRuns(run(58, 2).setCode(true))),
                callout(CalloutColor.CALLOUT_COLOR_ORANGE, p("Global/static data region").addRuns(run(0, 25).setBold(true).setRelativeSize(1.125f))),
                callout(CalloutColor.CALLOUT_COLOR_ORANGE, p("global = 10 — typically stored in a data segment.").addRuns(run(0, 11).setCode(true))),
            ),
            work,
        )
        // Saved last, so it is the newest and the one open at launch: arrows come from the
        // symbol fonts, and an emoji by default stays in colour though those fonts have it.
        save(
            note(
                p("Arrows and symbols", Role.ROLE_TITLE),
                p("Pasted arrows: ← → ↑ ↓ ↔ ⇒ ⇐ ⟶ ➜ ⬅ ↩"),
                p("In bold, italic and code: → ⇒ ➜").addRuns(run(26, 1).setBold(true)).addRuns(run(28, 1).setItalic(true)).addRuns(run(30, 1).setCode(true)),
                list("Plan → build → ship", ListKind.LIST_KIND_BULLETED, 6),
                p("Still emoji: ⭐ ✅ 👍 and ↔️ when asked for."),
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
private fun callout(color: CalloutColor, paragraph: Paragraph.Builder) = paragraph.setRole(Role.ROLE_CALLOUT).setCallout(color)

/** The toolbar's Gray, with the very floats the app saves for it. */
private val GRAY = syncednotes.note.v1.Color.newBuilder().setRed(0.56f).setGreen(0.56f).setBlue(0.60f).setAlpha(1f).build()

private fun code(language: CodeLanguage, vararg lines: String) = lines.map { p(it, Role.ROLE_CODE_BLOCK).setLanguage(language) }.toTypedArray()
private fun run(start: Int, length: Int) = Run.newBuilder().setStart(start).setLength(length)
