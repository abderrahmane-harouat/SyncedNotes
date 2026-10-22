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
import androidx.compose.foundation.lazy.items
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
fun Sidebar(model: NotesModel, onDeleteFolder: (Id) -> Unit, onExport: () -> Unit, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val c = colors
    Column(modifier.fillMaxHeight().background(c.sidebar)) {
        // Room for the window's buttons, which sit over the sidebar as in the Mac's own apps.
        Spacer(Modifier.height(52.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            item {
                PlaceRow("All Notes", Icons.tray, model.noteCounts.values.sum(), model.place == Place.AllNotes) { model.show(Place.AllNotes) }
            }
            item {
                PlaceRow("Notes", Icons.note, model.noteCounts[null] ?: 0, model.place == Place.NoFolder, help = "Notes in no folder") { model.show(Place.NoFolder) }
            }
            item { SectionTitle("Folders") }
            items(visibleFolders(model), key = { it.id.hex }) { folder ->
                FolderRow(model, folder, onDeleteFolder)
            }
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
                    BasicText("New Folder", style = MacType.body.copy(color = c.text))
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
                        BasicText("Backup", style = MacType.body.copy(color = c.text))
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
            Spacer(Modifier.width((depth * 14).dp))
            Box(
                Modifier.size(12.dp).clickable(enabled = hasChildren) {
                    model.expandedFolders = if (expanded) model.expandedFolders.minusElement(folder.id) else model.expandedFolders.plusElement(folder.id)
                },
                contentAlignment = Alignment.Center,
            ) {
                if (hasChildren) Icon(if (expanded) Icons.chevronDown else Icons.chevronRight, c.secondaryText, size = 11.dp)
            }
            Icon(Icons.folder, c.accent, size = 16.dp)
            if (model.renamingFolder == folder.id) {
                RenameField(folder.name, onDone = { model.renameFolder(folder.id, it) }, onCancel = { model.renamingFolder = null }, modifier = Modifier.weight(1f))
            } else {
                BasicText(folder.name, Modifier.weight(1f), style = MacType.body.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Count(model.noteCounts[folder.id] ?: 0)
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
                MenuItem("Delete Folder…", destructive = true) { onDeleteFolder(folder.id) },
            ),
            offset = menuAt?.let { IntOffset(it.x.toInt(), it.y.toInt()) } ?: IntOffset.Zero,
        )
    }
}

/** A folder's name being typed: Return keeps it, Escape or an empty name leaves the old one. */
@Composable
private fun RenameField(name: String, onDone: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    var text by remember { mutableStateOf(name) }
    val focus = remember { FocusRequester() }
    PlainTextField(text, { text = it }, "Folder name", modifier, onSubmit = { onDone(text) }, onCancel = onCancel, focusRequester = focus)
    LaunchedEffect(Unit) { focus.requestFocus() }
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
