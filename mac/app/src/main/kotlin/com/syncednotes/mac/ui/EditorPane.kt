package com.syncednotes.mac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.collectAsState
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
import com.syncednotes.engine.text.CalloutColor
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.Role
import com.syncednotes.mac.Appearance
import com.syncednotes.mac.Editor
import com.syncednotes.mac.NoteCanvas
import com.syncednotes.mac.NotesModel
import com.syncednotes.core.NoteFormat
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
    onInsertPicture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val open = model.document
    // A new note is ready to be written in at once, without a click first.
    LaunchedEffect(model.notesCreated) {
        if (model.notesCreated > 0) editor.focus.requestFocus()
    }
    Column(modifier.fillMaxSize().background(c.window)) {
        Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 12.dp, end = 12.dp, top = TITLE_BAR), verticalAlignment = Alignment.CenterVertically) {
            // The writing tools start where the note's text does, and scroll sideways when the
            // pane is too narrow for them all, as the phone's toolbar does, rather than running
            // off its edge. The app's own controls keep to the far side, apart from them.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (open != null && open.editable && !open.inTrash) {
                    Box(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) { FormatToolbar(editor, onInsertPicture) }
                }
            }
            Box(Modifier.padding(horizontal = 8.dp).size(1.dp, 20.dp).background(c.separator))
            model.sync?.let { SyncButton(model, it) }
            AppearanceButton(appearance, onAppearance)
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
        if (editor.finding && open != null) FindBar(editor)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Always there, so the engine keeps its place; covered when no note is open.
            NoteCanvas(editor, Modifier.fillMaxSize())
            // The languages of the code block whose name was clicked, just under the name.
            editor.languageMenu?.let { menu ->
                MenuPopup(
                    true, { editor.languageMenu = null },
                    CodeLanguage.entries.map { language ->
                        MenuItem(if (language == CodeLanguage.NONE) "Plain Code" else language.title, checked = menu.current == language) {
                            editor.languageMenu = null
                            editor.composer.setBlockLanguage(menu.paragraph, language, now())
                            editor.focus.requestFocus()
                        }
                    },
                    offset = IntOffset(menu.x.toInt(), menu.y.toInt() + 4),
                )
            }
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

/**
 * Find in Note: a field over the note, how many matches there are, and buttons to go from one
 * to the next. The matches are marked in the note itself, by the engine.
 */
@Composable
private fun FindBar(editor: Editor) {
    val c = colors
    val composer = editor.composer
    // Read so the count follows the note as it changes under the bar.
    editor.frame
    LaunchedEffect(Unit) { editor.findFocus.requestFocus() }
    LaunchedEffect(editor.findText) { composer.find(editor.findText) }
    val count = composer.matchCount
    Row(
        Modifier.fillMaxWidth().background(c.banner).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PlainTextField(
            editor.findText, { editor.findText = it }, "Find in Note",
            Modifier.width(260.dp),
            onSubmit = { composer.findNext() },
            onCancel = { closeFind(editor) },
            focusRequester = editor.findFocus,
            leading = { Icon(Icons.search, c.secondaryText, size = 14.dp) },
        )
        BasicText(
            when {
                editor.findText.isEmpty() -> ""
                count == 0 -> "Not found"
                else -> "${composer.currentMatch + 1} of $count"
            },
            Modifier.width(80.dp),
            style = MacType.body.copy(color = c.secondaryText),
        )
        ToolbarButton(Icons.chevronUp, "Previous (⇧⌘G)", { composer.findNext(forward = false) }, enabled = count > 0)
        ToolbarButton(Icons.chevronDown, "Next (⌘G)", { composer.findNext() }, enabled = count > 0)
        Spacer(Modifier.weight(1f))
        MacButton("Done", { closeFind(editor) })
    }
}

/** Closes Find, leaving the caret on the match it was on, in the note. */
fun closeFind(editor: Editor) {
    editor.finding = false
    editor.findText = ""
    editor.composer.endFind(now())
    editor.focus.requestFocus()
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
 * The formatting tools above the note, in groups that each do one kind of thing: text style
 * and size; the three lists; the four character styles; text colour and highlight; code and
 * pictures; undo and redo. They are the phone's toolbar's own tools; the Aa menu holds the
 * rest of what its Text options card does, so every tool is in reach on both. A tool stays
 * tinted while its style is on at the selection, as the engine reports it, so it cannot
 * disagree with the text; one that opens a menu shows a chevron.
 */
@Composable
private fun FormatToolbar(editor: Editor, onInsertPicture: () -> Unit) {
    val c = colors
    val composer = editor.composer
    val state = editor.format
    fun act(run: () -> Unit): () -> Unit = {
        run()
        editor.focus.requestFocus()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolGroup {
            var styleMenu by remember { mutableStateOf(false) }
            Box {
                ToolButton("Text Style and Size", { styleMenu = true }, menu = true) { tint ->
                    BasicText("Aa", style = MacType.body.copy(color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                }
                MenuPopup(styleMenu, { styleMenu = false }, textStyleItems(editor), offset = IntOffset(0, 60))
            }
        }
        ToolGroup {
            ToolbarButton(Icons.bullets, "Bulleted List (⇧⌘7)", act { composer.toggleList(ListType.BULLET, now()) }, selected = state?.list == ListType.BULLET)
            ToolbarButton(Icons.numbers, "Numbered List (⇧⌘9)", act { composer.toggleList(ListType.NUMBER, now()) }, selected = state?.list == ListType.NUMBER)
            ToolbarButton(Icons.checklist, "Checklist (⇧⌘L)", act { composer.toggleList(ListType.CHECKBOX, now()) }, selected = state?.list == ListType.CHECKBOX)
        }
        ToolGroup {
            LetterButton("B", "Bold (⌘B)", act { composer.toggle(CharStyle.BOLD, now()) }, selected = state?.bold == true, bold = true)
            LetterButton("I", "Italic (⌘I)", act { composer.toggle(CharStyle.ITALIC, now()) }, selected = state?.italic == true, decoration = Decoration.ITALIC)
            LetterButton("U", "Underline (⌘U)", act { composer.toggle(CharStyle.UNDERLINE, now()) }, selected = state?.underline == true, decoration = Decoration.UNDERLINE)
            LetterButton("S", "Strikethrough (⇧⌘X)", act { composer.toggle(CharStyle.STRIKETHROUGH, now()) }, selected = state?.strikethrough == true, decoration = Decoration.STRIKETHROUGH)
        }
        ToolGroup {
            // An A over a bar of the colour the text has: the sign every editor uses for it,
            // where a dot of colour alone said nothing about what it would colour.
            var colorMenu by remember { mutableStateOf(false) }
            Box {
                ToolButton("Text Color", { colorMenu = true }, menu = true) { tint ->
                    Swatch(state?.color?.let { Color(it) } ?: c.text) {
                        BasicText("A", style = MacType.body.copy(color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
                MenuPopup(colorMenu, { colorMenu = false }, colorItems(editor), offset = IntOffset(-40, 60))
            }
            var highlightMenu by remember { mutableStateOf(false) }
            Box {
                ToolButton("Highlight", { highlightMenu = true }, selected = state?.highlight != null, menu = true) { tint ->
                    // The bar shows the colour a highlight will be: the one there, or the last used.
                    Swatch(Color(state?.highlight ?: lastHighlight).copy(alpha = 1f)) { Icon(Icons.marker, tint, size = 15.dp) }
                }
                MenuPopup(highlightMenu, { highlightMenu = false }, highlightMenuItems(editor), offset = IntOffset(-40, 60))
            }
        }
        ToolGroup {
            var codeMenu by remember { mutableStateOf(false) }
            Box {
                ToolbarButton(Icons.code, "Code Block", { codeMenu = true }, selected = state?.role == Role.CODE_BLOCK, menu = true)
                MenuPopup(codeMenu, { codeMenu = false }, codeItems(editor), offset = IntOffset(-40, 60))
            }
            var calloutMenu by remember { mutableStateOf(false) }
            Box {
                ToolbarButton(Icons.callout, "Callout", { calloutMenu = true }, selected = state?.role == Role.CALLOUT, menu = true)
                MenuPopup(calloutMenu, { calloutMenu = false }, calloutItems(editor), offset = IntOffset(-40, 60))
            }
            var tableMenu by remember { mutableStateOf(false) }
            Box {
                ToolbarButton(Icons.table, "Table", { tableMenu = true }, selected = state?.inTable == true, menu = true)
                MenuPopup(tableMenu, { tableMenu = false }, tableItems(editor), offset = IntOffset(-40, 60))
            }
            // Samsung's attach button, for the one thing it attaches here: a picture.
            ToolbarButton(Icons.photo, "Insert Picture (⇧⌘P)", onInsertPicture)
        }
        ToolGroup {
            ToolbarButton(Icons.undo, "Undo (⌘Z)", act { composer.undo(now()) }, enabled = state?.canUndo == true)
            ToolbarButton(Icons.redo, "Redo (⇧⌘Z)", act { composer.redo(now()) }, enabled = state?.canRedo == true)
        }
    }
}

/** A tool's face over a short bar of [color]: what colour the tool will give. */
@Composable
private fun Swatch(color: Color, face: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(17.dp), contentAlignment = Alignment.Center) { face() }
        Box(Modifier.size(14.dp, 3.dp).clip(RoundedCornerShape(1.5.dp)).background(color))
    }
}

/**
 * The callout colours, each with its panel's own shade beside it, in the look the note is in;
 * the one there is ticked, and choosing it again puts the text back to body text.
 */
fun calloutItems(editor: Editor): List<MenuItem> {
    val current = editor.format?.calloutColor
    val shades = editor.composer.style.calloutColors
    return CalloutColor.entries.map { color ->
        MenuItem(color.title, checked = current == color, swatch = Color(shades[color.ordinal])) {
            editor.composer.toggleCallout(color, now())
            editor.focus.requestFocus()
        }
    }
}

/**
 * A new table, and with the caret in one, its rows and columns added or taken away and its
 * header row on or off, as Notion's table menu offers.
 */
fun tableItems(editor: Editor): List<MenuItem> {
    val composer = editor.composer
    val inTable = editor.format?.inTable == true
    fun act(title: String, enabled: Boolean = inTable, checked: Boolean = false, destructive: Boolean = false, run: () -> Unit) =
        MenuItem(title, enabled = enabled, checked = checked, destructive = destructive) {
            run()
            editor.focus.requestFocus()
        }
    return listOf(
        act("Insert Table", enabled = true) { composer.insertTable(now = now()) },
        MenuItem.SEPARATOR,
        act("Add Row Above") { composer.addRow(below = false, now()) },
        act("Add Row Below") { composer.addRow(below = true, now()) },
        act("Add Column Left") { composer.addColumn(right = false, now()) },
        act("Add Column Right") { composer.addColumn(right = true, now()) },
        MenuItem.SEPARATOR,
        act("Header Row", checked = editor.format?.tableHeader == true) { composer.toggleHeaderRow(now()) },
        MenuItem.SEPARATOR,
        act("Delete Row") { composer.deleteRow(now()) },
        act("Delete Column") { composer.deleteColumn(now()) },
        act("Delete Table", destructive = true) { composer.deleteTable(now()) },
    )
}

/** Plain code, or code in a language its syntax is coloured by; the one there is ticked. */
private fun codeItems(editor: Editor): List<MenuItem> {
    val current = editor.format?.codeLanguage
    return CodeLanguage.entries.map { language ->
        MenuItem(if (language == CodeLanguage.NONE) "Plain Code" else language.title, checked = current == language) {
            editor.composer.toggleCodeBlock(language, now())
            editor.focus.requestFocus()
        }
    }
}

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

/**
 * Pair a Phone, beside the appearance: green while a paired phone is connected, and its
 * tooltip says how sync stands, so it shows at a glance whether the two are in step.
 */
@Composable
private fun SyncButton(model: NotesModel, sync: com.syncednotes.core.sync.SyncService) {
    val peers by sync.peers.collectAsState()
    val states by sync.peerStates.collectAsState()
    val peer = peers.firstOrNull()
    val state = peer?.let { states[it.deviceId] }
    val help = when {
        peer == null -> "Pair a Phone"
        state is com.syncednotes.core.sync.SyncService.PeerState.Connected -> "In sync with ${peer.name}"
        state is com.syncednotes.core.sync.SyncService.PeerState.Failed -> state.message
        else -> "Looking for ${peer.name}"
    }
    val connected = state is com.syncednotes.core.sync.SyncService.PeerState.Connected
    ToolbarButton(Icons.phoneSync, help, { model.pairingPhone = true }, tint = if (connected) SYNCED_GREEN else null)
}

/** The Mac's system green, for a phone in sync. */
private val SYNCED_GREEN = Color(0xFF34C759)

/** How a letter on a toolbar button shows the style it stands for. */
private enum class Decoration { NONE, ITALIC, UNDERLINE, STRIKETHROUGH }

/** A toolbar button whose face is a letter that shows the style it stands for. */
@Composable
private fun LetterButton(text: String, help: String, onClick: () -> Unit, selected: Boolean = false, bold: Boolean = false, decoration: Decoration = Decoration.NONE) {
    ToolButton(help, onClick, selected = selected) { tint ->
        BasicText(
            text,
            style = MacType.body.copy(
                color = tint, fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center,
                fontStyle = if (decoration == Decoration.ITALIC) androidx.compose.ui.text.font.FontStyle.Italic else null,
                textDecoration = when (decoration) {
                    Decoration.UNDERLINE -> androidx.compose.ui.text.style.TextDecoration.Underline
                    Decoration.STRIKETHROUGH -> androidx.compose.ui.text.style.TextDecoration.LineThrough
                    else -> null
                },
            ),
        )
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
    val state = editor.format
    fun act(title: String, checked: Boolean = false, enabled: Boolean = true, run: () -> Unit) = MenuItem(title, enabled = enabled, checked = checked) {
        run()
        editor.focus.requestFocus()
    }
    return listOf(
        item("Title", Role.TITLE), item("Heading", Role.HEADING), item("Subheading", Role.SUBHEADING), item("Body", Role.BODY),
        MenuItem.SEPARATOR,
        item("Block Quote", Role.QUOTE),
        act("Code Block", checked = state?.codeLanguage == CodeLanguage.NONE) { composer.toggleCodeBlock(CodeLanguage.NONE, now()) },
        act("C Code Block", checked = state?.codeLanguage == CodeLanguage.C) { composer.toggleCodeBlock(CodeLanguage.C, now()) },
        act("C++ Code Block", checked = state?.codeLanguage == CodeLanguage.CPP) { composer.toggleCodeBlock(CodeLanguage.CPP, now()) },
        act("Callout", checked = state?.role == Role.CALLOUT) { composer.toggleCallout(state?.calloutColor ?: CalloutColor.BLUE, now()) },
        act("Inline Code", checked = state?.code == true) { composer.toggle(CharStyle.CODE, now()) },
        act("Dashed List", checked = state?.list == ListType.DASH) { composer.toggleList(ListType.DASH, now()) },
        act("Insert Divider") { composer.insertDivider(now()) },
        MenuItem.SEPARATOR,
        act("Align Left", checked = state?.alignment == com.syncednotes.engine.text.Alignment.LEFT) { composer.setAlignment(com.syncednotes.engine.text.Alignment.LEFT, now()) },
        act("Center", checked = state?.alignment == com.syncednotes.engine.text.Alignment.CENTER) { composer.setAlignment(com.syncednotes.engine.text.Alignment.CENTER, now()) },
        act("Justify", checked = state?.alignment == com.syncednotes.engine.text.Alignment.JUSTIFIED) { composer.setAlignment(com.syncednotes.engine.text.Alignment.JUSTIFIED, now()) },
        act("Align Right", checked = state?.alignment == com.syncednotes.engine.text.Alignment.RIGHT) { composer.setAlignment(com.syncednotes.engine.text.Alignment.RIGHT, now()) },
        MenuItem.SEPARATOR,
        act("Increase Indent") { composer.indent(now()) },
        act("Decrease Indent") { composer.outdent(now()) },
        MenuItem.SEPARATOR,
        act("Line Spacing 1.0", checked = state?.lineSpacing == 1f) { composer.setLineSpacing(1f, now()) },
        act("Line Spacing 1.15", checked = state?.lineSpacing == 1.15f) { composer.setLineSpacing(1.15f, now()) },
        act("Line Spacing 1.5", checked = state?.lineSpacing == 1.5f) { composer.setLineSpacing(1.5f, now()) },
        act("Line Spacing 2.0", checked = state?.lineSpacing == 2f) { composer.setLineSpacing(2f, now()) },
        MenuItem.SEPARATOR,
        MenuItem("Bigger") { resize(editor, +1f) },
        MenuItem("Smaller") { resize(editor, -1f) },
    )
}

/** None, then the six highlights a note can have: the phone's Font background card's colours. */
private fun highlightMenuItems(editor: Editor): List<MenuItem> {
    val composer = editor.composer
    val current = editor.format?.highlight
    return listOf(MenuItem("None", checked = current == null) { composer.setHighlight(null, now()); editor.focus.requestFocus() }, MenuItem.SEPARATOR) +
        highlightItems(editor).map { (name, argb) ->
            MenuItem(name, checked = current == argb) {
                composer.setHighlight(argb, now())
                lastHighlight = argb
                editor.focus.requestFocus()
            }
        }
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
