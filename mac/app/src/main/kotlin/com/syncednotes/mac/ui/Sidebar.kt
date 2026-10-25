package com.syncednotes.mac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.syncednotes.core.Id
import com.syncednotes.core.hex
import com.syncednotes.core.NoteStore
import com.syncednotes.mac.NotesModel
import com.syncednotes.mac.NotesModel.Place

/**
 * The sidebar: All Notes, the notes in no folder, the folders, and the Trash.
 *
 * The folder tree is drawn as a flat list, each row indented by its depth with its own
 * disclosure arrow: every row is then an ordinary row, selected and right-clicked the same way.
 */
@Composable
fun Sidebar(
    model: NotesModel,
    onDeleteFolder: (Id) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = colors
    Column(modifier.fillMaxHeight().background(c.sidebar)) {
        // Room for the window's buttons, which sit over the sidebar as in the Mac's own apps.
        Spacer(Modifier.height(HEADER_HEIGHT))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            item {
                PlaceRow("All Notes", Icons.tray, model.noteCounts.values.sum(), model.place == Place.AllNotes) { model.show(Place.AllNotes) }
            }
            item {
                PlaceRow("Notes", Icons.note, model.noteCounts[null] ?: 0, model.place == Place.NoFolder, help = "Notes in no folder") { model.show(Place.NoFolder) }
            }
            item { SectionTitle("Folders") }
            // A folder being named before it exists sits where it will go: after its parent's
            // folders, or after all of them at the top level.
            val folders = visibleFolders(model)
            val pending = model.namingNewFolderIn
            val parent = pending as? Id
            val after = if (parent == null) folders.lastIndex else folders.indexOfLast { f ->
                var p: Id? = f.id
                while (p != null && p != parent) p = model.folders.firstOrNull { it.id == p }?.parent
                p == parent
            }
            folders.forEachIndexed { i, folder ->
                item(key = folder.id.hex) { FolderRow(model, folder, onDeleteFolder) }
                if (pending != null && i == after) item(key = "new folder") { NewFolderRow(model, parent) }
            }
            if (pending != null && after < 0) item(key = "new folder") { NewFolderRow(model, parent) }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                PlaceRow(
                    "Trash", Icons.trash, model.trashCount, model.place == Place.Trash,
                    help = "Deleted notes stay here for 30 days, and can be restored",
                ) { model.show(Place.Trash) }
            }
        }
        // Backups and new folders in plain sight, under the list, not only in the menus.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tooltip("New Folder (⇧⌘N)") {
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable { model.newFolder() }.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.folderPlus, c.text, size = 16.dp)
                    BasicText("New Folder", style = MacType.body.copy(color = c.text), maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.weight(1f))
            var backupMenu by remember { mutableStateOf(false) }
            Box {
                Tooltip("Export or import an encrypted backup of every note") {
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).clickable { backupMenu = true }.padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.drive, c.text, size = 16.dp)
                        BasicText("Backup", style = MacType.body.copy(color = c.text), maxLines = 1, softWrap = false)
                    }
                }
                MenuPopup(
                    backupMenu, { backupMenu = false },
                    listOf(MenuItem("Export Backup…", action = onExport), MenuItem("Import Backup…", action = onImport)),
                    offset = IntOffset(0, -150),
                )
            }
        }
    }
}

/** The folders whose parents are all open, in tree order. */
private fun visibleFolders(model: NotesModel): List<NoteStore.Folder> = model.folders.filter { folder ->
    var parent = folder.parent
    while (parent != null) {
        if (parent !in model.expandedFolders) return@filter false
        parent = model.folders.firstOrNull { it.id == parent }?.parent
    }
    true
}

@Composable
private fun SectionTitle(title: String) {
    BasicText(title, Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp), style = MacType.section.copy(color = colors.secondaryText))
}

@Composable
private fun PlaceRow(title: String, icon: ImageVector, count: Int, selected: Boolean, help: String? = null, onClick: () -> Unit) {
    val row = @Composable {
        SidebarRow(selected, onClick) {
            Icon(icon, colors.accent, size = 16.dp)
            BasicText(title, Modifier.weight(1f), style = MacType.body.copy(color = colors.text), maxLines = 1)
            Count(count)
        }
    }
    if (help != null) Tooltip(help) { row() } else row()
}

@Composable
private fun Count(count: Int) {
    if (count > 0) BasicText(count.toString(), style = MacType.body.copy(color = colors.secondaryText))
}

@Composable
private fun SidebarRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) colors.sidebarSelection else Color.Transparent)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        content = content,
    )
}

@Composable
private fun FolderRow(model: NotesModel, folder: NoteStore.Folder, onDeleteFolder: (Id) -> Unit) {
    val c = colors
    val depth = model.depthOf(folder)
    val hasChildren = model.folders.any { it.parent == folder.id }
    val expanded = folder.id in model.expandedFolders
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.onRightClick { menuAt = it }) {
        SidebarRow(model.place == Place.InFolder(folder.id), { model.show(Place.InFolder(folder.id)) }) {
            // A top-level folder lines up with All Notes; each level inside goes 16 points in.
            Icon(Icons.folder, c.accent, Modifier.padding(start = (depth * 16).dp), size = 16.dp)
            if (model.renamingFolder == folder.id) {
                NameField(folder.name, onDone = { model.renameFolder(folder.id, it) }, onCancel = { model.renamingFolder = null }, modifier = Modifier.weight(1f))
            } else {
                TruncatedText(folder.name, Modifier.weight(1f), MacType.body.copy(color = c.text))
                // The folder an AI agent can read and change, marked so it is never forgotten.
                if (model.agentFolder == folder.id) {
                    Tooltip("Shared with an AI agent") { Icon(Icons.agent, c.accent, size = 13.dp) }
                }
                Count(model.noteCounts[folder.id] ?: 0)
            }
            // The arrow that opens a folder's folders, at the row's end, as in the Mac's sidebars.
            if (hasChildren) {
                Box(
                    Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).clickable {
                        model.expandedFolders = if (expanded) model.expandedFolders.minusElement(folder.id) else model.expandedFolders.plusElement(folder.id)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (expanded) Icons.chevronDown else Icons.chevronRight, c.secondaryText, size = 11.dp)
                }
            }
        }
        MenuPopup(
            menuAt != null, { menuAt = null },
            listOf(
                MenuItem("New Folder Inside") { model.newFolder(folder.id) },
                MenuItem("Rename") { model.renamingFolder = folder.id },
                MenuItem.SEPARATOR,
                MenuItem("Move to Top Level", enabled = folder.parent != null) { model.moveFolder(folder.id, null) },
                MenuItem.SEPARATOR,
                MenuItem(if (model.agentFolder == folder.id) "AI Agent Access…" else "Share with AI Agent…") { model.sharingWithAgent = folder.id },
                MenuItem.SEPARATOR,
                MenuItem("Delete Folder…", destructive = true) { onDeleteFolder(folder.id) },
            ),
            offset = menuAt?.let { IntOffset(it.x.toInt(), it.y.toInt()) } ?: IntOffset.Zero,
        )
    }
}

/**
 * A folder's name being typed: Return or a click elsewhere keeps it, Escape leaves it as it
 * was. Every name call happens once, whichever way the field closes.
 */
@Composable
private fun NameField(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var text by remember { mutableStateOf(initial) }
    var closed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun close(keep: Boolean) {
        if (closed) return
        closed = true
        if (keep) onDone(text) else onCancel()
    }
    PlainTextField(
        text, { text = it }, "Folder name", modifier,
        onSubmit = { close(true) }, onCancel = { close(false) }, onFocusLost = { close(true) }, focusRequester = focus,
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** The row of a folder being named before it exists: empty, ready to type its name into. */
@Composable
private fun NewFolderRow(model: NotesModel, parent: Id?) {
    val c = colors
    val depth = parent?.let { p -> model.folders.firstOrNull { it.id == p }?.let { model.depthOf(it) + 1 } } ?: 0
    SidebarRow(false, {}) {
        Icon(Icons.folder, c.accent, Modifier.padding(start = (depth * 16).dp), size = 16.dp)
        NameField("", onDone = model::createFolder, onCancel = model::cancelNewFolder, modifier = Modifier.weight(1f))
    }
}

/** A right click, or a Control-click, as the Mac takes both: [onClick] gets where it was. */
fun Modifier.onRightClick(onClick: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type != PointerEventType.Press) continue
            val secondary = event.buttons.isSecondaryPressed || event.keyboardModifiers.isCtrlPressed
            if (!secondary) continue
            val change = event.changes.firstOrNull() ?: continue
            change.consume()
            onClick(change.position)
        }
    }
}
