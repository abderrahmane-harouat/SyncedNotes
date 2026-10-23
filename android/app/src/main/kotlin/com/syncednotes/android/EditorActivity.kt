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
class EditorActivity : ScreenActivity() {
    private lateinit var canvas: NoteCanvasView
    private lateinit var toolbar: View
    private lateinit var textOptions: TextOptionsPanel
    private lateinit var fontColor: ColorPopup
    private lateinit var highlight: ColorPopup
    private lateinit var sizeList: FontSizeList
    private val cards: List<CardPopup> get() = listOf(textOptions, fontColor, highlight)

    private lateinit var id: Id
    /** Samsung's reading mode: the note shown without the toolbar or the keyboard, to read. */
    private var reading = false
    private lateinit var readingButton: ImageButton
    private lateinit var pictureButton: ImageButton
    private lateinit var findBar: View
    private lateinit var findField: android.widget.EditText
    private lateinit var findCount: TextView
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
    private val onPictureReady: () -> Unit = { if (::canvas.isInitialized) canvas.requestFrame() }

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
        composer.pictures = notes.pictures
        notes.addPictureListener(onPictureReady)
        val topBar = topBar(composer.style.background)
        (findViewById<ViewGroup>(R.id.root)).addView(topBar, 0)
        findBar = findBar(composer.style.background)
        (findViewById<ViewGroup>(R.id.root)).addView(findBar, 1)
        // A note only shown (in the Trash, or more than the engine can edit) has no toolbar.
        if (!editable) findViewById<View>(R.id.toolbar).visibility = View.GONE
        load()
        composer.document.addListener { if (!loading) changed() }
        composer.document.addFormatListener { _, _ -> if (!loading) changed() }
        notes.addRemoteListener(onRemote)
        notes.addListener(onChange)
        val pageColor = composer.style.background

        textOptions = TextOptionsPanel(this, composer)
        // Default shows as the note's own text colour; None, for a highlight, as the page.
        fontColor = ColorPopup(this, R.string.font_color, Palettes.text, composer.style.textColor,
            current = { composer.formatState().color ?: 0 },
            onPick = { composer.setColor(it.takeIf { c -> c != 0 }, now()) })
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
        window.decorView.systemUiVisibility = if (isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
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
            notes.removePictureListener(onPictureReady)
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
            composer.editable = editable && !reading
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
        findViewById<View>(R.id.toolbar).visibility = if (editable && !reading) View.VISIBLE else View.GONE
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
        readingButton = button(R.drawable.ic_book, getString(R.string.reading_mode)) { setReading(!reading) }
        // Samsung's attach button, for the one thing it attaches here: a picture.
        pictureButton = button(R.drawable.ic_photo, "Insert picture") { choosePicture() }
        if (!editable) pictureButton.visibility = View.GONE
        return row(back, spacer(), pictureButton, readingButton, more).apply {
            setBackgroundColor(color)
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
    }

    /**
     * The system's photo picker: the app sees only the one picture chosen, and needs no
     * permission to read the others. Older Android versions get the file picker instead.
     */
    private fun choosePicture() {
        closeCards()
        val intent = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_PICTURE)
    }

    @Deprecated("The platform's own, without AndroidX's activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_PICTURE || resultCode != RESULT_OK || uri == null) return
        kotlin.concurrent.thread {
            val handle = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(notes::addPicture) }.getOrNull()
            runOnUiThread {
                if (handle == null) Toast.makeText(this, "That picture could not be added.", Toast.LENGTH_LONG).show()
                else canvas.composer.insertPicture(handle, now())
            }
        }
    }

    private fun setReading(on: Boolean) {
        reading = on
        closeCards()
        canvas.composer.editable = editable && !on
        findViewById<View>(R.id.toolbar).visibility = if (editable && !on) View.VISIBLE else View.GONE
        pictureButton.visibility = if (editable && !on) View.VISIBLE else View.GONE
        if (on) getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(canvas.windowToken, 0)
        readingButton.background = if (on) rounded(Palette.SELECTED, dp(22).toFloat()) else pressable(radius = dp(22).toFloat())
    }

    private fun noteMenu(anchor: View) {
        val store = notes.store ?: return
        PopupMenu(this, anchor, Gravity.END).apply {
            // A note in the Trash can still be searched: it is only read, never changed.
            menu.add("Find in Note").setOnMenuItemClickListener { openFind(); true }
            if (inTrash) {
                menu.add("Restore").setOnMenuItemClickListener {
                    store.restoreFromTrash(id)
                    finish()
                    true
                }
            } else {
                // Formatted text from a web page or Notion keeps its headings, lists and code.
                if (editable && !reading && getSystemService(android.content.ClipboardManager::class.java)?.hasPrimaryClip() == true) {
                    menu.add("Paste").setOnMenuItemClickListener { canvas.paste(); true }
                }
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

    // --- Find in Note ---------------------------------------------------------------------------

    /**
     * A field over the note, how many matches there are, and arrows from one to the next. The
     * matches are marked in the note itself, by the engine, as on the Mac.
     */
    private fun findBar(color: Int): View {
        findField = privateField("Find in Note").apply {
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable) {
                    canvas.composer.find(s.toString())
                    showCount()
                }
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            })
            setOnEditorActionListener { _, _, _ -> canvas.composer.findNext(); showCount(); true }
        }
        findCount = label("", 14f, Palette.SECONDARY).apply { minWidth = dp(64); gravity = Gravity.CENTER }
        fun arrow(icon: Int, description: String, forward: Boolean) = ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Palette.ACCENT)
            background = pressable(radius = dp(20).toFloat())
            contentDescription = description
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                canvas.composer.findNext(forward)
                showCount()
            }
        }
        return row(
            findField.margins(width = 0, weight = 1f),
            findCount,
            arrow(R.drawable.ic_chevron_up, "Previous", false),
            arrow(R.drawable.ic_chevron_down, "Next", true),
            plainButton("Done") { closeFind() }.margins(width = ViewGroup.LayoutParams.WRAP_CONTENT),
        ).apply {
            setBackgroundColor(color)
            setPadding(dp(12), dp(4), dp(6), dp(6))
            visibility = View.GONE
        }
    }

    private fun openFind() {
        closeCards()
        findBar.visibility = View.VISIBLE
        findField.requestFocus()
        // Asked once the menu that opened Find has closed and the window has its focus back;
        // asked before that, the keyboard does not come.
        findField.postDelayed({
            findField.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(findField, 0)
        }, 250)
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        findField.setText("")
        canvas.composer.endFind(now())
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(findField.windowToken, 0)
    }

    private fun showCount() {
        val c = canvas.composer
        findCount.text = when {
            c.findQuery.isEmpty() -> ""
            c.matchCount == 0 -> "None"
            else -> "${c.currentMatch + 1} of ${c.matchCount}"
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
        private const val PICK_PICTURE = 1
    }
}
