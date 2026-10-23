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
import com.syncednotes.engine.text.Alignment
import com.syncednotes.engine.text.CharStyle
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.Role
import com.syncednotes.mac.ui.MacTheme
import com.syncednotes.mac.ui.NotesWindow
import com.syncednotes.mac.ui.resize
import com.syncednotes.mac.ui.toggleHighlight
import java.awt.AWTEvent
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.FileDialog
import java.io.File
import javax.swing.SwingUtilities

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

/** Text to type into a new note at launch, through the window's own events (SelfTest). */
private fun selfTestText(): String? = System.getProperty("syncednotes.selfTest")?.takeIf { folderName() != REAL_FOLDER }

fun main() {
    // The menus go in the Mac's menu bar, under the app's own name.
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    System.setProperty("apple.awt.application.name", "SyncedNotes")
    application {
        val scope = rememberCoroutineScope()
        val model = remember { NotesModel(notesFolder(), scope) }
        // Quitting saves first; the keys go with the app, nothing of them is kept.
        remember {
            Desktop.getDesktop().setQuitHandler { _, response ->
                SwingUtilities.invokeLater {
                    model.saveNow()
                    response.performQuit()
                }
            }
        }
        Window(
            onCloseRequest = {
                model.saveNow()
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
            val editor = remember(scale, dark) { Editor(scale, dark) }
            LaunchedEffect(editor) { model.editorAppeared(editor.composer) }
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
                selfTestText()?.let { text ->
                    delay(1500)
                    model.newNote()
                    delay(500)
                    // Into the note pane: right of the two columns, below the toolbar.
                    SelfTest.clickAndType(window, window.width - 300, 200, text)
                }
            }
            Menus(model, editor, appearance, choose)
            CompositionLocalProvider(LocalClipboard provides MacOnlyClipboard, LocalClipboardManager provides MacOnlyClipboard) {
                MacTheme(dark) {
                    NotesWindow(model, editor, appearance, choose, onExport = { exportBackup(model) }, onImport = { importBackup(model) })
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
    val file = File(dialog.directory, if (name.endsWith(".${NotesModel.BACKUP_EXTENSION}")) name else "$name.${NotesModel.BACKUP_EXTENSION}")
    model.exportBackup(file)
}

private fun FrameWindowScope.importBackup(model: NotesModel) {
    val dialog = FileDialog(window, "Import Backup", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return
    model.importBackup(File(dialog.directory, name))
}

/** The File and Format menus, with the Swift app's shortcuts. */
@androidx.compose.runtime.Composable
private fun FrameWindowScope.Menus(model: NotesModel, editor: Editor, appearance: Appearance, onAppearance: (Appearance) -> Unit) {
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
            Item("Change Passphrase…", enabled = unlocked, onClick = { model.changingPassphrase = true })
            Item("Lock Notes", shortcut = KeyShortcut(Key.L, meta = true, ctrl = true), enabled = unlocked, onClick = model::lock)
        }
        Menu("View") {
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
            CheckboxItem("Code Block", checked = state?.role == Role.CODE_BLOCK, enabled = editing, onCheckedChange = { act { c.toggleRole(Role.CODE_BLOCK, now()) }() })
            Separator()
            @androidx.compose.runtime.Composable fun style(title: String, style: CharStyle, shortcut: KeyShortcut?) =
                CheckboxItem(title, checked = state?.has(style) == true, shortcut = shortcut, enabled = editing, onCheckedChange = { act { c.toggle(style, now()) }() })
            style("Bold", CharStyle.BOLD, KeyShortcut(Key.B, meta = true))
            style("Italic", CharStyle.ITALIC, KeyShortcut(Key.I, meta = true))
            style("Underline", CharStyle.UNDERLINE, KeyShortcut(Key.U, meta = true))
            style("Strikethrough", CharStyle.STRIKETHROUGH, KeyShortcut(Key.X, meta = true, shift = true))
            CheckboxItem("Highlight", checked = state?.highlight != null, shortcut = KeyShortcut(Key.H, meta = true, shift = true), enabled = editing, onCheckedChange = { act { toggleHighlight(editor) }() })
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
