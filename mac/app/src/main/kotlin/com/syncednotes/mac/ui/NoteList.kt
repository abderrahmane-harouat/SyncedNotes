package com.syncednotes.mac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.syncednotes.core.Id
import com.syncednotes.core.hex
import com.syncednotes.core.NoteStore
import com.syncednotes.mac.NotesModel
import com.syncednotes.mac.NotesModel.Place
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The notes of the place chosen in the sidebar, with search above them. */
@Composable
fun NoteList(
    model: NotesModel,
    onConfirmDelete: (Set<Id>) -> Unit,
    onConfirmEmptyTrash: () -> Unit,
    sidebarShown: Boolean,
    onToggleSidebar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = colors
    val focus = remember { FocusRequester() }
    var listFocused by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    // The open note is always in view: a new one, which goes at the top, or one chosen from
    // the menus or the keyboard further down.
    LaunchedEffect(model.document?.id, model.notesCreated) {
        val index = model.summaries.indexOfFirst { it.id == model.document?.id }
        if (index < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val shown = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= state.layoutInfo.viewportEndOffset }
        if (!shown) state.animateScrollToItem(index)
    }
    // Where ⇧-click extends a selection from.
    var anchor by remember { mutableStateOf<Id?>(null) }

    fun click(id: Id, command: Boolean, shift: Boolean) {
        focus.requestFocus()
        val ids = model.summaries.map { it.id }
        when {
            command -> {
                val now = model.selection
                model.selectNotes(if (id in now) now.minusElement(id) else now.plusElement(id))
                anchor = id
            }
            shift && anchor != null && anchor in ids -> {
                val a = ids.indexOf(anchor)
                val b = ids.indexOf(id)
                model.selectNotes(ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet())
            }
            else -> {
                model.selectNotes(setOf(id))
                anchor = id
            }
        }
    }

    Column(modifier.fillMaxHeight().background(c.window)) {
        Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 8.dp, end = 8.dp, top = TITLE_BAR), verticalAlignment = Alignment.CenterVertically) {
            ToolbarButton(Icons.sidebar, if (sidebarShown) "Hide Sidebar (⌃⌘S)" else "Show Sidebar (⌃⌘S)", onToggleSidebar)
            Spacer(Modifier.width(4.dp))
            BasicText(listTitle(model), Modifier.weight(1f), style = MacType.headline.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            ToolbarButton(Icons.lock, "Lock Notes (⌃⌘L)", model::lock)
            // In plain sight beside Lock, not only in menus: from Settings and the File menu
            // alone, the passphrase change could not be found.
            ToolbarButton(Icons.key, "Change Passphrase…", { model.changingPassphrase = true })
            ToolbarButton(Icons.compose, "New Note (⌘N)", model::newNote)
        }
        SearchField(
            model.searchShown, { model.search = it },
            if (model.showingTrash) "Search the Trash" else "Search ${listTitle(model)}",
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        )
        if (model.showingTrash) TrashHeader(model, onConfirmEmptyTrash)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .focusRequester(focus)
                    .onFocusChanged { listFocused = it.hasFocus }
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val ids = model.summaries.map { it.id }
                        val current = model.document?.id ?: model.selection.firstOrNull()
                        val index = ids.indexOf(current)
                        when (event.key) {
                            Key.DirectionDown -> ids.getOrNull(index + 1)?.let { model.selectNotes(setOf(it)); anchor = it }
                            Key.DirectionUp -> ids.getOrNull(index - 1)?.let { model.selectNotes(setOf(it)); anchor = it }
                            Key.Backspace, Key.Delete -> {
                                val chosen = model.selection
                                if (chosen.isEmpty()) return@onKeyEvent false
                                if (model.showingTrash) onConfirmDelete(chosen) else model.moveToTrash(chosen)
                            }
                            else -> return@onKeyEvent false
                        }
                        true
                    }
                    .padding(horizontal = 10.dp),
                state = state,
            ) {
                itemsIndexed(model.summaries, key = { _, note -> note.id.hex }) { _, note ->
                    // Rows slide into place as notes come, go and move, so a new one is seen arriving.
                    Box(Modifier.animateItem()) {
                        NoteRow(model, note, note.id in model.selection, listFocused, ::click, onConfirmDelete)
                    }
                }
            }
            if (model.summaries.isEmpty()) {
                BasicText(
                    emptyListText(model),
                    Modifier.align(Alignment.Center).padding(24.dp),
                    style = MacType.body.copy(color = c.secondaryText, textAlign = TextAlign.Center),
                )
            }
        }
    }
}

fun listTitle(model: NotesModel): String = when (val p = model.place) {
    Place.AllNotes -> "All Notes"
    Place.NoFolder -> "Notes"
    is Place.InFolder -> model.nameOf(p.id)
    Place.Trash -> "Trash"
}

private fun emptyListText(model: NotesModel): String = when {
    model.searchShown.isNotEmpty() -> "No notes match."
    model.place == Place.Trash -> "The Trash is empty."
    model.place is Place.InFolder -> "No notes in this folder. ⌘N makes one here."
    else -> "No notes yet. ⌘N makes one."
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun date(instant: java.time.Instant): String = dateFormat.format(instant.atZone(ZoneId.systemDefault()))

/** A note in the list: its name and date, and where it is when the list shows every folder. */
@Composable
private fun NoteRow(
    model: NotesModel,
    note: NoteStore.Summary,
    selected: Boolean,
    listFocused: Boolean,
    onClick: (Id, Boolean, Boolean) -> Unit,
    onConfirmDelete: (Set<Id>) -> Unit,
) {
    val c = colors
    val lit = selected && listFocused
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val background = when {
        lit -> c.selection
        selected -> c.selectionInactive
        hovered -> c.control
        else -> Color.Transparent
    }
    val primary = if (lit) Color.White else c.text
    val secondary = if (lit) Color.White.copy(alpha = 0.8f) else c.secondaryText
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    // What the menu acts on: the whole selection when this note is part of it, as in Finder.
    val targets = if (note.id in model.selection) model.selection else setOf(note.id)
    val many = if (targets.size > 1) " ${targets.size} Notes" else ""

    Box(
        Modifier
            .hoverable(hover)
            .onRightClick { menuAt = it }
            .pointerInput(note.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed && !event.keyboardModifiers.isCtrlPressed) {
                            onClick(note.id, event.keyboardModifiers.isMetaPressed, event.keyboardModifiers.isShiftPressed)
                        }
                    }
                }
            },
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(background).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TruncatedText(
                note.title.ifEmpty { "New Note" },
                style = MacType.bodyStrong.copy(color = if (note.title.isEmpty() && !lit) c.secondaryText else primary),
            )
            val folder = note.folder?.takeIf { model.place == Place.AllNotes }?.let { "  ·  " + model.nameOf(it) } ?: ""
            BasicText(
                (note.trashed?.let { "Deleted " + date(it) } ?: date(note.updated)) + folder,
                style = MacType.small.copy(color = secondary), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        val items = if (model.showingTrash) {
            listOf(
                MenuItem("Restore$many") { model.restoreFromTrash(targets) },
                MenuItem("Delete$many Permanently…", destructive = true) { onConfirmDelete(targets) },
            )
        } else {
            val folders = model.summaries.filter { it.id in targets }.map { it.folder }.toSet()
            buildList {
                add(MenuItem("Move$many to Notes", enabled = folders != setOf(null)) { model.moveNotes(targets, null) })
                for (f in model.folders) {
                    val indent = "    ".repeat(model.depthOf(f))
                    add(MenuItem("Move$many to $indent“${f.name}”", enabled = folders != setOf(f.id)) { model.moveNotes(targets, f.id) })
                }
                add(MenuItem.SEPARATOR)
                add(MenuItem("Move$many to Trash", destructive = true) { model.moveToTrash(targets) })
            }
        }
        MenuPopup(menuAt != null, { menuAt = null }, items, offset = menuAt?.let { IntOffset(it.x.toInt(), it.y.toInt()) } ?: IntOffset.Zero)
    }
}

/** Above the list while it shows the Trash: Empty Trash, and how long notes stay. */
@Composable
private fun TrashHeader(model: NotesModel, onConfirmEmptyTrash: () -> Unit) {
    val c = colors
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("Notes are deleted for good 30 days after they were moved here.", style = MacType.small.copy(color = c.secondaryText))
        BasicText(
            "Empty Trash",
            Modifier.clip(RoundedCornerShape(4.dp)).then(
                if (model.trashCount > 0) Modifier.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Release) onConfirmEmptyTrash()
                        }
                    }
                } else Modifier,
            ),
            style = MacType.body.copy(color = if (model.trashCount > 0) c.danger else c.tertiaryText),
        )
    }
}
