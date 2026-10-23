package com.syncednotes.mac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncednotes.core.Id
import com.syncednotes.engine.layout.roleScale
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.Role
import com.syncednotes.mac.Appearance
import com.syncednotes.mac.Editor
import com.syncednotes.mac.NoteCanvas
import com.syncednotes.mac.NotesModel
import com.syncednotes.mac.format.NoteFormat
import com.syncednotes.mac.now
import syncednotes.note.v1.Highlight

/** The note being written, with its formatting tools; or what to do with several notes. */
@Composable
fun EditorPane(
    model: NotesModel,
    editor: Editor,
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    onConfirmDelete: (Set<Id>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val open = model.document
    // A new note is ready to be written in at once, without a click first.
    LaunchedEffect(model.notesCreated) {
        if (model.notesCreated > 0) editor.focus.requestFocus()
    }
    Column(modifier.fillMaxSize().background(c.window)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppearanceButton(appearance, onAppearance)
            Spacer(Modifier.weight(1f))
            if (open != null && open.editable && !open.inTrash) FormatToolbar(editor)
        }
        when {
            open != null -> {
                if (open.inTrash) {
                    Banner(Icons.trash, "This note is in the Trash.") {
                        MacButton("Restore", { model.restoreFromTrash(setOf(open.id)) })
                        MacButton("Delete Permanently…", { onConfirmDelete(setOf(open.id)) }, destructive = true)
                    }
                } else if (!open.editable) {
                    Banner(Icons.lock, "This note has a picture or a table, which cannot be edited here yet. It is shown as it is, and never saved over.") {}
                }
            }
            model.selectedNotes.size > 1 -> {}
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Always there, so the engine keeps its place; covered when no note is open.
            NoteCanvas(editor, Modifier.fillMaxSize())
            if (open == null) {
                Box(Modifier.fillMaxSize().background(c.window), contentAlignment = Alignment.Center) {
                    if (model.selectedNotes.size > 1) SelectedNotesPane(model, onConfirmDelete)
                    else BasicText(
                        when {
                            model.summaries.isEmpty() -> ""
                            model.showingTrash -> "Choose a note to restore it or delete it for good."
                            else -> "Choose a note."
                        },
                        style = MacType.body.copy(color = c.secondaryText),
                    )
                }
            }
        }
    }
}

@Composable
private fun Banner(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, actions: @Composable () -> Unit) {
    val c = colors
    Row(
        Modifier.fillMaxWidth().background(c.banner).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, c.secondaryText, size = 16.dp)
        BasicText(text, Modifier.weight(1f), style = MacType.body.copy(color = c.text))
        actions()
    }
}

/** In place of the note, while several are selected: what can be done with all of them. */
@Composable
private fun SelectedNotesPane(model: NotesModel, onConfirmDelete: (Set<Id>) -> Unit) {
    val c = colors
    val ids = model.selectedNotes
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.stack, c.secondaryText, size = 40.dp)
        BasicText("${ids.size} notes selected", style = MacType.title.copy(color = c.text))
        BasicText(if (model.showingTrash) "Restore them, or delete them for good." else "Move them, or:", style = MacType.body.copy(color = c.secondaryText))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (model.showingTrash) {
                MacButton("Restore", { model.restoreFromTrash(ids) })
                MacButton("Delete Permanently…", { onConfirmDelete(ids) }, destructive = true)
            } else {
                var moveMenu by remember { mutableStateOf(false) }
                Box {
                    MacButton("Move To…", { moveMenu = true })
                    MenuPopup(moveMenu, { moveMenu = false }, moveItems(model, ids), offset = IntOffset(0, 60))
                }
                MacButton("Move to Trash", { model.moveToTrash(ids) }, destructive = true)
            }
        }
    }
}

private fun moveItems(model: NotesModel, ids: Set<Id>): List<MenuItem> = buildList {
    add(MenuItem("Notes") { model.moveNotes(ids, null) })
    if (model.folders.isNotEmpty()) add(MenuItem.SEPARATOR)
    for (f in model.folders) add(MenuItem("    ".repeat(model.depthOf(f)) + f.name) { model.moveNotes(ids, f.id) })
}

/**
 * The formatting tools above the note, as in the Swift app: text style and size, the three
 * lists, bold, highlight and text colour. A button stays pressed while its style is on at the
 * selection, as the engine reports it, so it cannot disagree with the text.
 */
@Composable
private fun FormatToolbar(editor: Editor) {
    val c = colors
    val composer = editor.composer
    val state = editor.format
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        var styleMenu by remember { mutableStateOf(false) }
        Box {
            TextButton("Aa", "Text Style and Size", { styleMenu = true })
            MenuPopup(styleMenu, { styleMenu = false }, textStyleItems(editor), offset = IntOffset(0, 60))
        }
        Gap()
        ToolbarButton(Icons.bullets, "Bulleted List (⇧⌘7)", { composer.toggleList(ListType.BULLET, now()); editor.focus.requestFocus() }, selected = state?.list == ListType.BULLET)
        ToolbarButton(Icons.numbers, "Numbered List (⇧⌘9)", { composer.toggleList(ListType.NUMBER, now()); editor.focus.requestFocus() }, selected = state?.list == ListType.NUMBER)
        ToolbarButton(Icons.checklist, "Checklist (⇧⌘L)", { composer.toggleList(ListType.CHECKBOX, now()); editor.focus.requestFocus() }, selected = state?.list == ListType.CHECKBOX)
        Gap()
        TextButton("B", "Bold (⌘B)", { composer.toggle(CharStyle.BOLD, now()); editor.focus.requestFocus() }, selected = state?.bold == true, bold = true)
        ToolbarButton(Icons.highlighter, "Highlight (⇧⌘H)", { toggleHighlight(editor); editor.focus.requestFocus() }, selected = state?.highlight != null)
        var colorMenu by remember { mutableStateOf(false) }
        Box {
            Tooltip("Text Color") {
                Box(
                    Modifier.size(30.dp, 28.dp).clip(RoundedCornerShape(7.dp)).clickable { colorMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(state?.color?.let { Color(it) } ?: c.text))
                }
            }
            MenuPopup(colorMenu, { colorMenu = false }, colorItems(editor), offset = IntOffset(-120, 60))
        }
    }
}

@Composable
private fun Gap() = Spacer(Modifier.width(8.dp))

/** Light, dark, or as the Mac is: across from the formatting tools, and in the View menu. */
@Composable
private fun AppearanceButton(appearance: Appearance, onAppearance: (Appearance) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ToolbarButton(Icons.appearance, "Appearance: ${appearance.title}", { menu = true })
        MenuPopup(
            menu, { menu = false },
            Appearance.entries.map { choice -> MenuItem(choice.title, checked = appearance == choice) { onAppearance(choice) } },
            offset = IntOffset(0, 60),
        )
    }
}

/** A toolbar button whose face is a letter or two. */
@Composable
private fun TextButton(text: String, help: String, onClick: () -> Unit, selected: Boolean = false, bold: Boolean = false) {
    val c = colors
    Tooltip(help) {
        Box(
            Modifier.size(30.dp, 28.dp).clip(RoundedCornerShape(7.dp)).background(if (selected) c.controlPressed else Color.Transparent).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(text, style = MacType.body.copy(color = c.secondaryText, fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center))
        }
    }
}

/** The Mac's highlighter button: on with the colour last used, off when the selection has one. */
fun toggleHighlight(editor: Editor) {
    val current = editor.composer.formatState().highlight
    editor.composer.setHighlight(if (current != null) null else lastHighlight, now())
}

private var lastHighlight: Int = NoteFormat.HIGHLIGHTS.getValue(Highlight.HIGHLIGHT_YELLOW)

private fun textStyleItems(editor: Editor): List<MenuItem> {
    val composer = editor.composer
    val role = editor.format?.role
    fun item(title: String, target: Role) = MenuItem(title, checked = role == target) {
        composer.toggleRole(target, now())
        editor.focus.requestFocus()
    }
    return listOf(
        item("Title", Role.TITLE), item("Heading", Role.HEADING), item("Subheading", Role.SUBHEADING), item("Body", Role.BODY),
        MenuItem.SEPARATOR,
        MenuItem("Bigger") { resize(editor, +1f) },
        MenuItem("Smaller") { resize(editor, -1f) },
    )
}

/**
 * Bigger and Smaller, a point at a time between 8 and 96, as on the Mac. Kept relative to the
 * paragraph's own size, as the note format records it.
 */
fun resize(editor: Editor, step: Float) {
    val composer = editor.composer
    val relative = composer.relativeSizeHere() ?: 1f
    val role = composer.formatState().role ?: Role.BODY
    val base = 16f * roleScale(role)
    val points = (relative * base + step).coerceIn(8f, 96f)
    composer.setRelativeSize(points / base, now())
    editor.focus.requestFocus()
}

private fun colorItems(editor: Editor): List<MenuItem> {
    val composer = editor.composer
    val current = editor.format?.color
    return buildList {
        add(MenuItem("Automatic", checked = current == null) { composer.setColor(null, now()); editor.focus.requestFocus() })
        add(MenuItem.SEPARATOR)
        for ((name, color) in NoteFormat.TEXT_COLORS) {
            val argb = NoteFormat.argbOf(color)
            add(MenuItem(name, checked = current == argb) { composer.setColor(argb, now()); editor.focus.requestFocus() })
        }
    }
}

/** The six highlight colours, for the Format menu. */
fun highlightItems(editor: Editor): List<Pair<String, Int>> = NoteFormat.HIGHLIGHTS.map { (named, argb) ->
    named.name.removePrefix("HIGHLIGHT_").lowercase().replaceFirstChar { it.uppercase() } to argb
}

fun setHighlightColor(editor: Editor, argb: Int?) {
    if (argb != null) lastHighlight = argb
    editor.composer.setHighlight(argb, now())
}
