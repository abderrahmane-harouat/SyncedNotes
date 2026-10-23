package com.syncednotes.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.google.protobuf.ByteString
import com.syncednotes.core.Id
import com.syncednotes.core.NoteFormat
import com.syncednotes.core.isEmpty
import com.syncednotes.engine.text.ListType
import com.syncednotes.engine.text.ParagraphStyle
import com.syncednotes.engine.text.Role
import syncednotes.note.v1.Note

/**
 * One note, in the engine, with Samsung's toolbar under it. Saved as typing pauses and when
 * left, as on the Mac (NotesModel); a note left with nothing in it is removed rather than kept.
 */
class EditorActivity : Activity() {
    private lateinit var canvas: NoteCanvasView
    private lateinit var toolbar: View
    private lateinit var textOptions: TextOptionsPanel
    private lateinit var fontColor: ColorPopup
    private lateinit var highlight: ColorPopup
    private lateinit var sizeList: FontSizeList
    private val cards: List<CardPopup> get() = listOf(textOptions, fontColor, highlight)

    private lateinit var id: Id
    private var folder: Id? = null
    private var stored: Note = Note.getDefaultInstance()
    private var inTrash = false
    private var editable = true
    private var format = NoteFormat()
    private var loading = false
    private var unsaved = false
    private val main = Handler(Looper.getMainLooper())
    private val save = Runnable { saveNow() }
    private val onRemote: (Set<Id>) -> Unit = { ids -> if (id in ids) takeRemoteVersion() }
    private val onChange: () -> Unit = { if (!notes.isUnlocked) finish() }

    private fun now() = SystemClock.uptimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = notes.store
        if (store == null) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }
        id = ByteString.copyFrom(intent.getByteArrayExtra(NOTE) ?: ByteArray(16))
        folder = intent.getByteArrayExtra(FOLDER)?.let(ByteString::copyFrom)
        if (!intent.getBooleanExtra(NEW, false)) {
            try {
                stored = store.load(id)
            } catch (e: Exception) {
                Toast.makeText(this, "This note could not be opened: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            inTrash = store.isInTrash(id)
            editable = NoteFormat.canEdit(stored) && !inTrash
        }
        setContentView(R.layout.editor)
        keepToThisPhone()
        toolbar = findViewById(R.id.toolbar)
        canvas = NoteCanvasView(this, AppFonts.get(this))
        findViewById<ViewGroup>(R.id.canvas_holder).addView(canvas, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val composer = canvas.composer
        val topBar = topBar(composer.style.background)
        (findViewById<ViewGroup>(R.id.root)).addView(topBar, 0)
        // A note only shown (in the Trash, or more than the engine can edit) has no toolbar.
        if (!editable) findViewById<View>(R.id.toolbar).visibility = View.GONE
        load()
        composer.document.addListener { if (!loading) changed() }
        composer.document.addFormatListener { _, _ -> if (!loading) changed() }
        notes.addRemoteListener(onRemote)
        notes.addListener(onChange)
        val pageColor = composer.style.background

        textOptions = TextOptionsPanel(this, composer)
        fontColor = ColorPopup(this, R.string.font_color, Palettes.text, pageColor,
            current = { composer.formatState().color ?: composer.style.textColor },
            onPick = { composer.setColor(it.takeIf { c -> c != composer.style.textColor }, now()) })
        highlight = ColorPopup(this, R.string.font_background, Palettes.highlight, pageColor,
            current = { composer.formatState().highlight ?: 0 },
            onPick = { composer.setHighlight(it.takeIf { c -> c != 0 }, now()) })
        sizeList = FontSizeList(this) { composer.setSize(it, now()) }

        findViewById<View>(R.id.checklist).setOnClickListener {
            closeCards()
            composer.toggleList(ListType.CHECKBOX, now())
            canvas.showKeyboard()
        }
        findViewById<View>(R.id.text_options).setOnClickListener { toggleCard(textOptions) }
        findViewById<View>(R.id.font_color).setOnClickListener { toggleCard(fontColor) }
        findViewById<View>(R.id.font_highlight).setOnClickListener { toggleCard(highlight) }
        findViewById<View>(R.id.font_size).setOnClickListener {
            closeCards()
            sizeList.show(it, composer.formatState().size)
        }
        findViewById<View>(R.id.undo).setOnClickListener { composer.undo(now()) }
        findViewById<View>(R.id.redo).setOnClickListener { composer.redo(now()) }
        canvas.onFormatChanged = ::refreshToolbar
        canvas.onTextOptionsShortcut = { toggleCard(textOptions) }
        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> cards.forEach { it.reposition() } }
        refreshToolbar()

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            // Android 15 draws apps edge to edge: keep the text below the status bar and the
            // toolbar above the navigation bar and the keyboard.
            val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            // The bar above the note takes the status bar's room; the toolbar sits on the keyboard.
            v.setPadding(0, top, 0, bottom)
            insets
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = pageColor
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        canvas.requestFocus()
        // A new note is for typing: the keyboard comes up with it.
        if (intent.getBooleanExtra(NEW, false)) canvas.post { canvas.showKeyboard() }
    }

    /** Opening a card closes any other, as Samsung's toolbar does. */
    private fun toggleCard(card: CardPopup) {
        val wasShowing = card.isShowing
        closeCards()
        if (!wasShowing) card.show(toolbar)
    }

    private fun closeCards() {
        cards.forEach { it.dismiss() }
        sizeList.dismiss()
    }

    private fun refreshToolbar() {
        val composer = canvas.composer
        val s = composer.formatState()
        findViewById<View>(R.id.checklist).isSelected = s.list == ListType.CHECKBOX
        findViewById<ImageView>(R.id.font_color_bar).imageTintList = ColorStateList.valueOf(s.color ?: composer.style.textColor)
        // No highlight shows an empty bar: the page colour inside its outline.
        findViewById<ImageView>(R.id.font_highlight_bar).imageTintList = ColorStateList.valueOf(s.highlight ?: composer.style.background)
        // A selection of mixed sizes leaves the label blank, as in Samsung Notes.
        findViewById<TextView>(R.id.font_size_label).text = s.size?.toString() ?: ""
        setEnabled(findViewById(R.id.undo), s.canUndo)
        setEnabled(findViewById(R.id.redo), s.canRedo)
        cards.filter { it.isShowing }.forEach { it.refresh() }
    }

    private fun setEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        // Samsung dims an unavailable undo or redo to 40%.
        button.alpha = if (enabled) 1f else 0.4f
    }

    override fun onPause() {
        saveNow()
        super.onPause()
    }

    override fun onDestroy() {
        if (::canvas.isInitialized) {
            closeCards()
            leave()
            notes.removeRemoteListener(onRemote)
            notes.removeListener(onChange)
        }
        super.onDestroy()
    }

    // --- The note ------------------------------------------------------------------------------

    /** Puts [stored] into the engine. */
    private fun load() {
        val composer = canvas.composer
        loading = true
        try {
            format = NoteFormat()
            composer.load { doc ->
                if (stored.paragraphsCount == 0 || stored.isEmpty) {
                    // An empty note starts with its title, as on the Mac: the first line is the
                    // note's name in the list anyway.
                    doc.setParagraphStyle(0, ParagraphStyle(role = Role.TITLE))
                } else {
                    format.decode(stored, doc)
                }
            }
            composer.editable = editable
        } finally {
            loading = false
        }
    }

    private fun changed() {
        unsaved = true
        main.removeCallbacks(save)
        main.postDelayed(save, 800)
    }

    private fun saveNow() {
        main.removeCallbacks(save)
        val store = notes.store ?: return
        if (!unsaved || !editable || !::canvas.isInitialized) return
        val note = format.encode(canvas.composer.document)
        // A new note that is still empty is not kept: there is nothing in it to sync or list.
        if (note.isEmpty && stored.paragraphsCount == 0) return
        try {
            store.save(note, id, folder = folder)
            stored = note
            unsaved = false
        } catch (e: Exception) {
            Toast.makeText(this, "The note could not be saved: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Saves the note being left, and removes it if nothing is left in it. */
    private fun leave() {
        saveNow()
        val store = notes.store ?: return
        if (editable && stored.paragraphsCount > 0 && stored.isEmpty && !store.isDeleted(id)) {
            // Straight out, not to the Trash: there is nothing in it to want back. Deleted
            // rather than forgotten, so the Mac learns of it too.
            runCatching { store.delete(id) }
        }
    }

    /**
     * The Mac sent a newer version of this note. Taken in only when nothing typed here waits
     * to be saved; otherwise the next save is newer, and the Mac takes that instead.
     */
    private fun takeRemoteVersion() {
        val store = notes.store ?: return
        if (unsaved) return
        if (store.isDeleted(id)) {
            finish()
            return
        }
        stored = runCatching { store.load(id) }.getOrNull() ?: return
        inTrash = store.isInTrash(id)
        editable = NoteFormat.canEdit(stored) && !inTrash
        findViewById<View>(R.id.toolbar).visibility = if (editable) View.VISIBLE else View.GONE
        load()
    }

    private fun topBar(color: Int): View {
        fun button(icon: Int, description: String, onClick: (View) -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Palette.ACCENT)
            background = pressable(radius = dp(22).toFloat())
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener(onClick)
        }
        val back = button(R.drawable.ic_back, "Notes") { finish() }
        val more = button(R.drawable.ic_more, "More") { noteMenu(it) }
        return row(back, spacer(), more).apply {
            setBackgroundColor(color)
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
    }

    private fun noteMenu(anchor: View) {
        val store = notes.store ?: return
        PopupMenu(this, anchor, Gravity.END).apply {
            if (inTrash) {
                menu.add("Restore").setOnMenuItemClickListener {
                    store.restoreFromTrash(id)
                    finish()
                    true
                }
            } else {
                menu.add("Move to Folder…").setOnMenuItemClickListener { chooseFolder(); true }
                menu.add("Move to Trash").setOnMenuItemClickListener {
                    saveNow()
                    if (!store.isDeleted(id) && stored.paragraphsCount > 0) store.moveToTrash(id)
                    finish()
                    true
                }
            }
            show()
        }
    }

    private fun chooseFolder() {
        val store = notes.store ?: return
        saveNow()
        val folders = store.folders()
        val depths = mutableMapOf<Id, Int>()
        for (f in folders) depths[f.id] = f.parent?.let { (depths[it] ?: -1) + 1 } ?: 0
        val names = listOf("No Folder") + folders.map { "    ".repeat(depths.getValue(it.id)) + it.name }
        AlertDialog.Builder(this)
            .setTitle("Move to Folder")
            .setItems(names.toTypedArray()) { _, which ->
                val chosen = if (which == 0) null else folders[which - 1].id
                folder = chosen
                // A note not saved yet goes there when it is; one saved already moves now.
                if (stored.paragraphsCount > 0) store.moveNote(id, chosen)
            }
            .show()
    }

    companion object {
        /** The note's ID, 16 bytes. */
        const val NOTE = "note"
        /** A note not made yet: saved once something is typed. */
        const val NEW = "new"
        /** Where a new note goes. */
        const val FOLDER = "folder"
    }
}
