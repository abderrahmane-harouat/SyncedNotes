package com.syncednotes.mac

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.syncednotes.core.sync.SyncService
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.CalloutColor
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.CodeLanguage
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.Role
import com.syncednotes.mac.ui.MacTheme
import com.syncednotes.mac.ui.NotesWindow
import com.syncednotes.mac.ui.resize
import com.syncednotes.mac.ui.toggleHighlight
import java.awt.AWTEvent
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.FileDialog
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Where the notes live: `~/Library/Application Support/SyncedNotes`. A run made to try the app
 * out passes `-Dsyncednotes.folder=SyncedNotes-Try` and gets a folder of its own beside it;
 * only a plain name is taken, so it can never point anywhere else.
 */
private fun notesFolder(): File {
    val support = File(System.getProperty("user.home"), "Library/Application Support")
    return File(support, folderName())
}

private const val REAL_FOLDER = "SyncedNotes"

private fun folderName(): String =
    System.getProperty("syncednotes.folder")?.takeIf { it.isNotBlank() && '/' !in it && it != ".." && it != "." } ?: REAL_FOLDER

/**
 * Whether windows may be captured: only when asked for, and never with the real notes, so a
 * screenshot can check how the app looks on made-up ones.
 */
private fun allowCapture(): Boolean = System.getProperty("syncednotes.allowCapture") == "true" && folderName() != REAL_FOLDER

/**
 * A made-up passphrase to unlock made-up notes with at launch, for checking the app on them
 * without typing; ignored for the real notes, whose passphrase is only ever typed.
 */
private fun tryPassphrase(): String? = System.getProperty("syncednotes.tryPassphrase")?.takeIf { folderName() != REAL_FOLDER }

/**
 * For trying sync on made-up notes with a phone: a fixed port to listen on, so the phone can
 * reach it over `adb reverse`; and pairing with the first phone ready to pair, its code
 * accepted here unasked, so the comparing happens on the phone alone. Both ignored for the real
 * notes, whose pairing is only ever confirmed by hand.
 */
private fun trySyncPort(): Int? = System.getProperty("syncednotes.trySyncPort")?.toIntOrNull()?.takeIf { folderName() != REAL_FOLDER }
private fun tryPairing(): Boolean = System.getProperty("syncednotes.tryPairing") == "true" && folderName() != REAL_FOLDER

/** A word for Find in Note to look for in the first note at launch, to look at its markers; made-up notes only. */
private fun tryFind(): String? = System.getProperty("syncednotes.tryFind")?.takeIf { folderName() != REAL_FOLDER }

/** A folder to share with an AI agent at launch, by name, to try an agent on; made-up notes only. */
private fun tryAgentFolder(): String? = System.getProperty("syncednotes.tryAgentFolder")?.takeIf { folderName() != REAL_FOLDER }

/** A picture file to put into the first note at launch, to look at it; made-up notes only. */
private fun tryPicture(): String? = System.getProperty("syncednotes.tryPicture")?.takeIf { folderName() != REAL_FOLDER }

/** Text to type into a new note at launch, through the window's own events (SelfTest). */
private fun selfTestText(): String? = System.getProperty("syncednotes.selfTest")?.takeIf { folderName() != REAL_FOLDER }

fun main() {
    // The menus go in the Mac's menu bar, under the app's own name.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "SyncedNotes")
    application {
        val scope = rememberCoroutineScope()
        val model = remember { NotesModel(notesFolder(), scope, BonjourDiscovery(), trySyncPort()) }
        // Quitting saves first; the keys go with the app, nothing of them is kept.
        remember {
            Desktop.getDesktop().setQuitHandler { _, response ->
                SwingUtilities.invokeLater {
                    model.quit()
                    response.performQuit()
                }
            }
        }
        Window(
            onCloseRequest = {
                model.quit()
                exitApplication()
            },
            title = "SyncedNotes",
            state = rememberWindowState(width = 1180.dp, height = 760.dp),
        ) {
            // The content runs under the title bar, as in the Mac's own apps: the window's
            // buttons sit over the sidebar. Set before the window is first shown.
            remember {
                window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                window.minimumSize = java.awt.Dimension(900, 480)
            }
            val scale = LocalDensity.current.density
            var appearance by remember { mutableStateOf(Appearance.load()) }
            val dark = appearance.dark(isSystemInDarkTheme())
            // The window's frame follows the app's choice, not only its content.
            LaunchedEffect(appearance) {
                KeepOnThisMac.setAppearance(if (appearance == Appearance.SYSTEM) null else appearance == Appearance.DARK)
            }
            val choose: (Appearance) -> Unit = { appearance = it; it.save() }
            val layout = remember { WindowLayout() }
            val editor = remember(scale, dark) { Editor(scale, dark) }
            LaunchedEffect(editor) { model.editorAppeared(editor.composer, editor::requestFrame) }
            // Typed text reaches the note this way (see MacTextInput).
            DisposableEffect(editor) {
                val input = MacTextInput { editor.composer }
                val mask = AWTEvent.KEY_EVENT_MASK or AWTEvent.INPUT_METHOD_EVENT_MASK
                Toolkit.getDefaultToolkit().addAWTEventListener(input, mask)
                onDispose { Toolkit.getDefaultToolkit().removeAWTEventListener(input) }
            }
            LaunchedEffect(Unit) {
                KeepOnThisMac.install(allowCapture())
                tryPassphrase()?.let { model.unlock(it) }
                tryPicture()?.let {
                    delay(800)
                    model.insertPicture(File(it))
                }
                tryAgentFolder()?.let { name -> model.folders.firstOrNull { it.name == name }?.let { model.shareWithAgent(it.id) } }
                tryFind()?.let {
                    delay(800)
                    editor.findText = it
                    editor.finding = true
                }
                if (tryPairing()) model.sync?.let { sync ->
                    model.pairingPhone = true
                    launch {
                        // The first phone that turns up, then its code, accepted here.
                        sync.phonesReadyToPair.first { it.isNotEmpty() }.first().let(sync::pairWith)
                        val code = sync.pairing.first { it is SyncService.PairingState.Confirming } as SyncService.PairingState.Confirming
                        println("Pairing code: ${code.code}")
                        sync.confirmPairing(true)
                    }
                }
                selfTestText()?.let { text ->
                    delay(1500)
                    // "<folder>" starts naming a new folder instead, to look at its row.
                    if (text == "<folder>") {
                        model.newFolder()
                        return@LaunchedEffect
                    }
                    model.newNote()
                    delay(500)
                    // Into the note pane: right of the two columns, below the toolbar.
                    SelfTest.clickAndType(window, window.width - 300, 200, text)
                }
            }
            Menus(model, editor, layout, appearance, choose, scope)
            CompositionLocalProvider(LocalClipboard provides MacOnlyClipboard, LocalClipboardManager provides MacOnlyClipboard) {
                MacTheme(dark) {
                    NotesWindow(
                        model, editor, layout, appearance, choose,
                        onExport = { exportBackup(model) }, onImport = { importBackup(model) },
                        onInsertPicture = { insertPicture(model, scope) },
                    )
                }
            }
        }
    }
}

private fun FrameWindowScope.exportBackup(model: NotesModel) {
    val dialog = FileDialog(window, "Export Backup", FileDialog.SAVE)
    dialog.file = model.backupFileName()
    dialog.isVisible = true
    val name = dialog.file ?: return
    // Exactly the file chosen: the sandbox opens that one to the app, and a name changed here
    // (an extension added) would be another file, which it refuses.
    model.exportBackup(File(dialog.directory, name))
}

private fun FrameWindowScope.importBackup(model: NotesModel) {
    val dialog = FileDialog(window, "Import Backup", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return
    model.importBackup(File(dialog.directory, name))
}

/** Asks for a picture file and puts it in the open note. */
private fun FrameWindowScope.insertPicture(model: NotesModel, scope: kotlinx.coroutines.CoroutineScope) {
    val dialog = FileDialog(window, "Insert Picture", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "bmp") }
    dialog.isVisible = true
    val name = dialog.file ?: return
    scope.launch { model.insertPicture(File(dialog.directory, name)) }
}

/** The File and Format menus, with the Swift app's shortcuts. */
@androidx.compose.runtime.Composable
private fun FrameWindowScope.Menus(model: NotesModel, editor: Editor, layout: WindowLayout, appearance: Appearance, onAppearance: (Appearance) -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val unlocked = model.phase == NotesModel.Phase.Unlocked
    val editing = unlocked && model.document?.let { it.editable && !it.inTrash } == true
    val c = editor.composer
    val state = editor.format
    fun act(block: () -> Unit): () -> Unit = {
        block()
        editor.focus.requestFocus()
    }
    MenuBar {
        Menu("File") {
            Item("New Note", shortcut = KeyShortcut(Key.N, meta = true), enabled = unlocked, onClick = model::newNote)
            Item("New Folder", shortcut = KeyShortcut(Key.N, meta = true, shift = true), enabled = unlocked, onClick = { model.newFolder() })
            Separator()
            Item("Export Backup…", shortcut = KeyShortcut(Key.E, meta = true, shift = true), enabled = unlocked, onClick = { exportBackup(model) })
            Item("Import Backup…", shortcut = KeyShortcut(Key.I, meta = true, shift = true), enabled = unlocked, onClick = { importBackup(model) })
            Separator()
            Item("Pair a Phone…", enabled = unlocked, onClick = { model.pairingPhone = true })
            Item("Change Passphrase…", enabled = unlocked, onClick = { model.changingPassphrase = true })
            Item("Lock Notes", shortcut = KeyShortcut(Key.L, meta = true, ctrl = true), enabled = unlocked, onClick = model::lock)
        }
        Menu("View") {
            Item(
                if (layout.sidebarShown) "Hide Sidebar" else "Show Sidebar",
                shortcut = KeyShortcut(Key.S, meta = true, ctrl = true),
                enabled = unlocked,
                onClick = layout::toggleSidebar,
            )
            Separator()
            Item("Find in Note…", shortcut = KeyShortcut(Key.F, meta = true), enabled = model.document != null, onClick = {
                editor.finding = true
                editor.findFocus.requestFocus()
            })
            Item("Find Next", shortcut = KeyShortcut(Key.G, meta = true), enabled = editor.finding, onClick = { editor.composer.findNext() })
            Item("Find Previous", shortcut = KeyShortcut(Key.G, meta = true, shift = true), enabled = editor.finding, onClick = { editor.composer.findNext(forward = false) })
            Separator()
            Menu("Appearance") {
                for (choice in Appearance.entries) {
                    RadioButtonItem(choice.title, selected = appearance == choice, onClick = { onAppearance(choice) })
                }
            }
        }
        Menu("Format") {
            @androidx.compose.runtime.Composable fun role(title: String, role: Role, key: Key) =
                CheckboxItem(title, checked = state?.role == role, shortcut = KeyShortcut(key, meta = true, alt = true), enabled = editing, onCheckedChange = { act { c.toggleRole(role, now()) }() })
            role("Title", Role.TITLE, Key.One)
            role("Heading", Role.HEADING, Key.Two)
            role("Subheading", Role.SUBHEADING, Key.Three)
            role("Body", Role.BODY, Key.Zero)
            Separator()
            @androidx.compose.runtime.Composable fun list(title: String, type: ListType, shortcut: KeyShortcut) =
                CheckboxItem(title, checked = state?.list == type, shortcut = shortcut, enabled = editing, onCheckedChange = { act { c.toggleList(type, now()) }() })
            list("Bulleted List", ListType.BULLET, KeyShortcut(Key.Seven, meta = true, shift = true))
            list("Dashed List", ListType.DASH, KeyShortcut(Key.Eight, meta = true, shift = true))
            list("Numbered List", ListType.NUMBER, KeyShortcut(Key.Nine, meta = true, shift = true))
            list("Checklist", ListType.CHECKBOX, KeyShortcut(Key.L, meta = true, shift = true))
            CheckboxItem(
                "Mark as Checked", checked = state?.checked == true, shortcut = KeyShortcut(Key.U, meta = true, shift = true),
                enabled = editing && state?.list == ListType.CHECKBOX, onCheckedChange = { act { c.toggleChecked(now()) }() },
            )
            Separator()
            CheckboxItem("Block Quote", checked = state?.role == Role.QUOTE, shortcut = KeyShortcut(Key.Apostrophe, meta = true), enabled = editing, onCheckedChange = { act { c.toggleRole(Role.QUOTE, now()) }() })
            // The language is what the block's syntax is coloured by; choosing the one it has
            // already puts it back to body text, as choosing a role it has does.
            Menu("Code Block", enabled = editing) {
                for (language in CodeLanguage.entries) {
                    CheckboxItem(language.title, checked = state?.codeLanguage == language, enabled = editing, onCheckedChange = { act { c.toggleCodeBlock(language, now()) }() })
                }
            }
            // A callout of the colour chosen; choosing the one it is already puts it back to body text.
            Menu("Callout", enabled = editing) {
                for (color in CalloutColor.entries) {
                    CheckboxItem(color.title, checked = state?.calloutColor == color, enabled = editing, onCheckedChange = { act { c.toggleCallout(color, now()) }() })
                }
            }
            // A table, and with the caret in one its rows, columns and header row.
            Menu("Table", enabled = editing) {
                val inTable = state?.inTable == true
                Item("Insert Table", shortcut = KeyShortcut(Key.T, meta = true, alt = true), enabled = editing, onClick = act { c.insertTable(now = now()) })
                Separator()
                Item("Add Row Above", enabled = inTable, onClick = act { c.addRow(below = false, now()) })
                Item("Add Row Below", enabled = inTable, onClick = act { c.addRow(below = true, now()) })
                Item("Add Column Left", enabled = inTable, onClick = act { c.addColumn(right = false, now()) })
                Item("Add Column Right", enabled = inTable, onClick = act { c.addColumn(right = true, now()) })
                Separator()
                CheckboxItem("Header Row", checked = state?.tableHeader == true, enabled = inTable, onCheckedChange = { act { c.toggleHeaderRow(now()) }() })
                Separator()
                Item("Delete Row", enabled = inTable, onClick = act { c.deleteRow(now()) })
                Item("Delete Column", enabled = inTable, onClick = act { c.deleteColumn(now()) })
                Item("Delete Table", enabled = inTable, onClick = act { c.deleteTable(now()) })
            }
            Item("Insert Divider", shortcut = KeyShortcut(Key.D, meta = true, shift = true), enabled = editing, onClick = act { c.insertDivider(now()) })
            Item("Insert Picture…", shortcut = KeyShortcut(Key.P, meta = true, shift = true), enabled = editing, onClick = { insertPicture(model, scope) })
            Separator()
            @androidx.compose.runtime.Composable fun style(title: String, style: CharStyle, shortcut: KeyShortcut?) =
                CheckboxItem(title, checked = state?.has(style) == true, shortcut = shortcut, enabled = editing, onCheckedChange = { act { c.toggle(style, now()) }() })
            style("Bold", CharStyle.BOLD, KeyShortcut(Key.B, meta = true))
            style("Italic", CharStyle.ITALIC, KeyShortcut(Key.I, meta = true))
            style("Underline", CharStyle.UNDERLINE, KeyShortcut(Key.U, meta = true))
            style("Strikethrough", CharStyle.STRIKETHROUGH, KeyShortcut(Key.X, meta = true, shift = true))
            CheckboxItem("Highlight", checked = state?.highlight != null, shortcut = KeyShortcut(Key.H, meta = true, shift = true), enabled = editing, onCheckedChange = { act { toggleHighlight(editor) }() })
            Menu("Highlight Color") {
                RadioButtonItem("None", selected = state?.highlight == null, enabled = editing, onClick = act { c.setHighlight(null, now()) })
                for ((name, argb) in com.syncednotes.mac.ui.highlightItems(editor)) {
                    RadioButtonItem(name, selected = state?.highlight == argb, enabled = editing, onClick = act { c.setHighlight(argb, now()) })
                }
            }
            style("Inline Code", CharStyle.CODE, null)
            Separator()
            Item("Bigger", shortcut = KeyShortcut(Key.Equals, meta = true), enabled = editing, onClick = { resize(editor, +1f) })
            Item("Smaller", shortcut = KeyShortcut(Key.Minus, meta = true), enabled = editing, onClick = { resize(editor, -1f) })
            Separator()
            Menu("Alignment") {
                @androidx.compose.runtime.Composable fun align(title: String, alignment: Alignment, shortcut: KeyShortcut?) =
                    CheckboxItem(title, checked = state?.alignment == alignment, shortcut = shortcut, enabled = editing, onCheckedChange = { act { c.setAlignment(alignment, now()) }() })
                align("Align Left", Alignment.LEFT, KeyShortcut(Key.LeftBracket, meta = true, shift = true))
                align("Center", Alignment.CENTER, KeyShortcut(Key.Backslash, meta = true, shift = true))
                align("Justify", Alignment.JUSTIFIED, null)
                align("Align Right", Alignment.RIGHT, KeyShortcut(Key.RightBracket, meta = true, shift = true))
            }
            Item("Increase Indent", shortcut = KeyShortcut(Key.RightBracket, meta = true), enabled = editing, onClick = act { c.indent(now()) })
            Item("Decrease Indent", shortcut = KeyShortcut(Key.LeftBracket, meta = true), enabled = editing, onClick = act { c.outdent(now()) })
            Menu("Line Spacing") {
                for ((title, multiple) in listOf("Single" to 1f, "1.15" to 1.15f, "1.5" to 1.5f, "Double" to 2f)) {
                    Item(title, enabled = editing, onClick = act { c.setLineSpacing(multiple, now()) })
                }
            }
        }
    }
}
