package com.syncednotes.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import com.syncednotes.core.Id
import com.syncednotes.core.NoteStore
import com.syncednotes.core.newId
import com.syncednotes.core.sync.SyncService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The notes: All Notes, a folder, or the Trash, newest first, with search, as the Mac's list.
 * The places themselves (the Mac's sidebar) are a sheet from the folder button.
 */
class NotesActivity : Activity() {
    sealed interface Place {
        data object AllNotes : Place
        data class InFolder(val id: Id, val name: String) : Place
        data object Trash : Place
    }

    private var place: Place = Place.AllNotes
    private var search = ""
    private var shown: List<NoteStore.Summary> = emptyList()
    private var folderNames: Map<Id, String> = emptyMap()
    private val scope = MainScope()

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var add: View
    private val adapter = Adapter()
    private val onChange: () -> Unit = { if (notes.isUnlocked) refresh() else lockedOut() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!notes.isUnlocked) {
            lockedOut()
            return
        }
        val folders = iconButton(R.drawable.ic_folder, "Folders") { showPlaces() }
        val more = iconButton(R.drawable.ic_more, "More") { showMenu(it) }
        val top = row(folders, spacer(), more).apply { setPadding(dp(8), dp(4), dp(8), 0) }
        title = label("All Notes", 30f, bold = true)
        status = label("", 13f, Palette.SECONDARY)
        val field = privateField("Search").apply {
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable) { search = s.toString(); refresh() }
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            })
        }
        val list = ListView(this).apply {
            adapter = this@NotesActivity.adapter
            divider = null
            selector = pressable()
            setOnItemClickListener { _, _, position, _ -> open(shown[position].id) }
            setOnItemLongClickListener { _, view, position, _ -> noteMenu(view, shown[position]); true }
        }
        empty = label("", 15f, Palette.SECONDARY).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        add = ImageButton(this).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), rounded(Palette.ACCENT, dp(28).toFloat()), null,
            )
            elevation = dp(4).toFloat()
            contentDescription = "New Note"
            setOnClickListener { newNote() }
        }
        val body = FrameLayout(this).apply {
            addView(list)
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(add, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(20), dp(20)) })
        }
        val content = column(
            top,
            title.margins(left = dp(20), right = dp(20)),
            status.margins(left = dp(20), right = dp(20), bottom = dp(10)),
            field.margins(left = dp(16), right = dp(16), bottom = dp(8)),
            body.margins(height = 0, weight = 1f),
        )
        showScreen(content)

        // Whether the Mac is there, in a word, under the title.
        scope.launch {
            notes.sync.peers.combine(notes.sync.peerStates) { peers, states -> peers to states }.collect { (peers, states) ->
                status.text = when {
                    peers.isEmpty() -> "On this phone only"
                    else -> {
                        val peer = peers.first()
                        when (val state = states[peer.deviceId]) {
                            is SyncService.PeerState.Connected -> "Synced with ${peer.name}"
                            is SyncService.PeerState.Failed -> state.message
                            else -> "Looking for ${peer.name}…"
                        }
                    }
                }
            }
        }
        notes.addListener(onChange)
    }

    override fun onResume() {
        super.onResume()
        if (!notes.isUnlocked) lockedOut() else refresh()
    }

    override fun onDestroy() {
        notes.removeListener(onChange)
        scope.cancel()
        super.onDestroy()
    }

    private fun lockedOut() {
        startActivity(Intent(this, LockActivity::class.java))
        finish()
    }

    private fun refresh() {
        val store = notes.store ?: return
        folderNames = store.folders().associate { it.id to it.name }
        // A folder deleted on the Mac takes the list back to All Notes.
        (place as? Place.InFolder)?.let { if (it.id !in folderNames) place = Place.AllNotes }
        shown = when (val p = place) {
            Place.AllNotes -> store.summaries(search.ifBlank { null })
            is Place.InFolder -> store.summaries(search.ifBlank { null }, NoteStore.Scope.InFolder(p.id))
            Place.Trash -> store.trash().filter { search.isBlank() || it.title.contains(search, ignoreCase = true) }
        }
        title.text = when (val p = place) {
            Place.AllNotes -> "All Notes"
            is Place.InFolder -> folderNames[p.id] ?: p.name
            Place.Trash -> "Trash"
        }
        add.visibility = if (place == Place.Trash) View.GONE else View.VISIBLE
        empty.text = when {
            search.isNotBlank() -> "No notes match."
            place == Place.Trash -> "The Trash is empty.\nNotes stay here 30 days."
            else -> "No notes yet."
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    // --- Notes -------------------------------------------------------------------------------

    private fun open(id: Id) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.NOTE, id.toByteArray()))
    }

    private fun newNote() {
        val folder = (place as? Place.InFolder)?.id
        startActivity(
            Intent(this, EditorActivity::class.java)
                .putExtra(EditorActivity.NOTE, newId().toByteArray())
                .putExtra(EditorActivity.NEW, true)
                .apply { folder?.let { putExtra(EditorActivity.FOLDER, it.toByteArray()) } },
        )
    }

    private fun noteMenu(anchor: View, note: NoteStore.Summary) {
        val store = notes.store ?: return
        PopupMenu(this, anchor, Gravity.END).apply {
            if (place == Place.Trash) {
                menu.add("Restore").setOnMenuItemClickListener { store.restoreFromTrash(note.id); true }
                menu.add("Delete Now").setOnMenuItemClickListener {
                    confirm("Delete this note now?", "It cannot be restored, here or on your Mac.", "Delete") { store.delete(note.id) }
                    true
                }
            } else {
                menu.add("Move to Folder…").setOnMenuItemClickListener { chooseFolder(note.id); true }
                menu.add("Move to Trash").setOnMenuItemClickListener { store.moveToTrash(note.id); true }
            }
            show()
        }
    }

    private fun chooseFolder(note: Id) {
        val store = notes.store ?: return
        val folders = store.folders()
        val depths = depths(folders)
        val names = listOf("No Folder") + folders.map { "    ".repeat(depths.getValue(it.id)) + it.name }
        AlertDialog.Builder(this)
            .setTitle("Move to Folder")
            .setItems(names.toTypedArray()) { _, which -> store.moveNote(note, if (which == 0) null else folders[which - 1].id) }
            .show()
    }

    // --- Places --------------------------------------------------------------------------------

    /** The Mac's sidebar, as a sheet: All Notes, the folders, the Trash. */
    private fun showPlaces() {
        val store = notes.store ?: return
        val folders = store.folders()
        val depths = depths(folders)
        val counts = store.noteCounts()
        val places = listOf<Place>(Place.AllNotes) + folders.map { Place.InFolder(it.id, it.name) } + Place.Trash
        lateinit var dialog: AlertDialog
        val list = ListView(this).apply {
            divider = null
            selector = pressable()
            adapter = object : BaseAdapter() {
                override fun getCount() = places.size
                override fun getItem(position: Int) = places[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val p = places[position]
                    val (icon, name, count) = when (p) {
                        Place.AllNotes -> Triple(R.drawable.ic_notes, "All Notes", counts.values.sum())
                        is Place.InFolder -> Triple(R.drawable.ic_folder, p.name, counts[p.id] ?: 0)
                        Place.Trash -> Triple(R.drawable.ic_trash, "Trash", null)
                    }
                    val indent = (p as? Place.InFolder)?.let { depths.getValue(it.id) } ?: 0
                    val image = android.widget.ImageView(context).apply {
                        setImageResource(icon)
                        imageTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
                    }
                    val text = label(name, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                    val number = label(count?.toString() ?: "", 15f, Palette.SECONDARY)
                    return row(
                        image.margins(right = dp(14), width = dp(22), height = dp(22)),
                        text.margins(width = 0, weight = 1f),
                        number,
                    ).apply {
                        setPadding(dp(20) + dp(20) * indent, dp(13), dp(20), dp(13))
                        if (p == place) setBackgroundColor(0x14000000)
                    }
                }
            }
            setOnItemClickListener { _, _, position, _ ->
                place = places[position]
                refresh()
                dialog.dismiss()
            }
            setOnItemLongClickListener { _, item, position, _ ->
                val p = places[position]
                if (p is Place.InFolder) folderMenu(item, p) { dialog.dismiss() }
                true
            }
        }
        dialog = AlertDialog.Builder(this)
            .setView(list)
            .setPositiveButton("New Folder") { _, _ -> nameFolder("New Folder", "") { name -> store.createFolder(name) } }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun folderMenu(anchor: View, folder: Place.InFolder, done: () -> Unit) {
        val store = notes.store ?: return
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add("Rename…").setOnMenuItemClickListener {
                done()
                nameFolder("Rename Folder", folder.name) { store.renameFolder(folder.id, it) }
                true
            }
            menu.add("New Folder Inside…").setOnMenuItemClickListener {
                done()
                nameFolder("New Folder", "") { store.createFolder(it, folder.id) }
                true
            }
            menu.add("Delete…").setOnMenuItemClickListener {
                done()
                val count = store.noteCountDeletingFolder(folder.id)
                val what = if (count == 0) "It has no notes." else "Its $count ${if (count == 1) "note goes" else "notes go"} to the Trash."
                confirm("Delete “${folder.name}” and the folders inside it?", what, "Delete") { store.deleteFolder(folder.id) }
                true
            }
            show()
        }
    }

    private fun nameFolder(title: String, current: String, save: (String) -> Unit) {
        val field = privateField("Name").apply { setText(current); selectAll() }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(save) }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    // --- The menu ------------------------------------------------------------------------------

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor, Gravity.END).apply {
            val paired = notes.sync.peers.value.isNotEmpty()
            menu.add(if (paired) "Sync with Your Mac" else "Pair with Your Mac").setOnMenuItemClickListener {
                startActivity(Intent(this@NotesActivity, SyncActivity::class.java))
                true
            }
            if (place == Place.Trash && shown.isNotEmpty()) {
                menu.add("Empty Trash").setOnMenuItemClickListener {
                    confirm("Delete every note in the Trash?", "They cannot be restored, here or on your Mac.", "Empty Trash") {
                        notes.store?.emptyTrash()
                    }
                    true
                }
            }
            menu.add("Lock").setOnMenuItemClickListener { notes.lock(); true }
            show()
        }
    }

    private fun confirm(title: String, message: String, action: String, run: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(action) { _, _ -> run() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun iconButton(icon: Int, description: String, onClick: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
        background = pressable(radius = dp(22).toFloat())
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener(onClick)
    }

    /** How deep each folder is, from the list folders() gives: parents always come first. */
    private fun depths(folders: List<NoteStore.Folder>): Map<Id, Int> {
        val depths = mutableMapOf<Id, Int>()
        for (f in folders) depths[f.id] = f.parent?.let { (depths[it] ?: -1) + 1 } ?: 0
        return depths
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val note = shown[position]
            val title = label(note.title.ifEmpty { "New Note" }, 17f, if (note.title.isEmpty()) Palette.SECONDARY else Palette.TEXT, bold = true)
                .apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val date = note.trashed ?: note.updated
            var detail = DateUtils.getRelativeTimeSpanString(date.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            if (place == Place.AllNotes) note.folder?.let(folderNames::get)?.let { detail += "  ·  $it" }
            val sub = label(detail, 14f, Palette.SECONDARY).apply { maxLines = 1 }
            return column(title, sub).apply {
                setPadding(dp(20), dp(12), dp(20), dp(12))
                addView(separator().margins(top = dp(12)))
            }
        }
    }
}
