import AppKit
import Observation
import SyncedNotesCore

/// A note loaded for the editor. Compared by ID only: the editor loads a document when the ID
/// changes, and otherwise leaves its own text alone (the text view owns the text).
struct EditorDocument: Equatable {
    let id: Data
    let text: NSAttributedString
    /// A note in the Trash is shown, not edited: it has to be restored first.
    var inTrash = false
    /// Where a new note goes when it is first saved; a note saved before stays where it is.
    var folder: Data? = nil

    static func == (a: EditorDocument, b: EditorDocument) -> Bool { a.id == b.id }
}

/// The notes, locked or unlocked, and everything that saves them.
///
/// Saving: a note is saved a moment after typing stops, and always before switching notes,
/// locking, or quitting. A note left empty is removed when it is left, as in Notes.
@Observable
final class NotesModel {

    enum Phase: Equatable {
        /// Nothing is unlocked. `firstRun` when no passphrase has been chosen yet.
        case locked(firstRun: Bool)
        case unlocked
    }

    /// What the sidebar can show in the list of notes.
    enum Place: Hashable {
        case allNotes
        /// The notes in no folder.
        case noFolder
        case folder(Data)
        case trash
    }

    private(set) var phase: Phase
    private(set) var summaries: [NoteStore.Summary] = []
    /// Which notes the list shows.
    private(set) var place = Place.allNotes
    /// Whether the list shows the Trash instead of the notes.
    var showingTrash: Bool { place == .trash }
    private(set) var trashCount = 0
    /// Every folder, parents before the folders inside them.
    private(set) var folders: [NoteStore.Folder] = []
    /// How many notes each folder holds itself; nil for the notes in no folder.
    private(set) var noteCounts: [Data?: Int] = [:]
    /// The folders shown open in the sidebar.
    var expandedFolders: Set<Data> = []
    /// The folder whose name is being typed in the sidebar: a new one, or one being renamed.
    var renamingFolder: Data?
    /// The note open in the editor. One selected note is simply the open note.
    private(set) var document: EditorDocument? {
        didSet { if document != nil { selectedNotes = [] } }
    }
    /// Notes selected together, when more than one is; none is open then.
    private(set) var selectedNotes: Set<Data> = []
    /// What the list shows as selected.
    var selection: Set<Data> { selectedNotes.isEmpty ? Set([document?.id].compactMap { $0 }) : selectedNotes }
    /// Whether the Change Passphrase sheet is up.
    var changingPassphrase = false
    /// Shown to the user when something could not be saved or opened.
    var problem: String?
    /// Shown to the user when something worked that they should hear about: a backup made.
    var notice: String?
    /// A backup from another vault, waiting for that vault's passphrase before it can be read.
    var importWaitingForPassphrase: Data?
    var search = "" { didSet { refresh() } }

    /// In the app's own sandboxed folder: ~/Library/Containers/com.syncednotes.mac/…
    static let folder: URL = {
        var name = "SyncedNotes"
        #if DEBUG
        // Trying the app out without touching the real notes: launch with
        // `-NotesFolder SyncedNotes-Try`. Debug builds only, and only a folder beside the real
        // one, so it can never point anywhere else.
        if let other = UserDefaults.standard.string(forKey: "NotesFolder"), !other.isEmpty, !other.contains("/"), other != ".." {
            name = other
        }
        #endif
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(name, isDirectory: true)
    }()

    /// The shortest passphrase accepted. Argon2id is set for speed (LockFormat.md), so it is
    /// the passphrase's own length that keeps guessing slow.
    static let shortestPassphrase = 8

    /// Where this model keeps the notes: `folder`, except in checks, which pass their own.
    private let home: URL

    private var store: NoteStore?
    @ObservationIgnored private weak var editor: NoteTextView?
    @ObservationIgnored private var unsaved = false
    @ObservationIgnored private var pendingSave: Task<Void, Never>?

    init(folder: URL = NotesModel.folder) {
        home = folder
        // A passphrase change the app was stopped in the middle of is finished, or dropped,
        // before anything is read: see PassphraseChange.
        do { try PassphraseChange.finishInterruptedChange(home: folder) } catch {
            problem = "A passphrase change that was stopped part way could not be finished: \(error)"
        }
        phase = .locked(firstRun: !FileManager.default.fileExists(atPath: Vault.headerFile(in: folder).path))
        // Quitting saves first. The key goes with the app: nothing of it is kept.
        NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.saveNow() }
        }
    }

    // MARK: - Locking

    /// First run: makes the vault. Throws with a message fit to show.
    func create(passphrase: String, repeated: String) throws {
        guard passphrase.count >= Self.shortestPassphrase else {
            throw Message("Use at least \(Self.shortestPassphrase) characters. There is no way to recover it, so a longer one is worth it.")
        }
        guard passphrase == repeated else { throw Message("The two passphrases are not the same.") }
        let vault = try Vault.create(in: home, passphrase: passphrase)
        try open(vault)
    }

    /// Unlocks with [passphrase]; false if it is wrong.
    func unlock(passphrase: String) -> Bool {
        do {
            try open(Vault.unlock(in: home, passphrase: passphrase))
            return true
        } catch LockError.wrongPassphrase {
            return false
        } catch {
            problem = "The notes could not be opened: \(error)"
            return false
        }
    }

    private func open(_ vault: Vault) throws {
        store = try NoteStore(folder: home, vault: vault)
        // As in Apple Notes: a note stays in the Trash for 30 days, then goes for good.
        try store?.emptyTrash(olderThan: NoteStore.trashKeepsFor)
        phase = .unlocked
        refresh()
        if let newest = summaries.first { select(newest.id) }
    }

    /// Saves, then forgets the keys and everything decrypted.
    func lock() {
        leaveCurrentNote()
        store = nil
        document = nil
        summaries = []
        folders = []
        noteCounts = [:]
        selectedNotes = []
        place = .allNotes
        search = ""
        phase = .locked(firstRun: false)
    }

    // MARK: - Notes

    /// Selects the notes [ids]: one is opened; several are selected together, with none open.
    func select(notes ids: Set<Data>) {
        guard ids.count > 1 else {
            selectedNotes = []
            return select(ids.first)
        }
        guard ids != selectedNotes else { return }
        leaveCurrentNote()
        selectedNotes = ids
    }

    func select(_ id: Data?) {
        if id == nil || id != document?.id { selectedNotes = [] }
        guard id != document?.id, let store else { return }
        leaveCurrentNote()
        guard let id else { return document = nil }
        do {
            let stored = try store.load(id)
            document = EditorDocument(
                id: id, text: NoteFormat.decode(stored) { try? store.picture($0) }, inTrash: try store.isInTrash(id)
            )
        } catch {
            problem = "This note could not be opened: \(error)"
            document = nil
        }
    }

    /// A new note, in the folder the list shows, if it shows one.
    func newNote() {
        guard store != nil else { return }
        if showingTrash { show(.allNotes) }
        leaveCurrentNote()
        let folder: Data? = if case .folder(let id) = place { id } else { nil }
        document = EditorDocument(id: Data((0..<16).map { _ in UInt8.random(in: .min ... .max) }), text: NSAttributedString(), folder: folder)
        // Saved once so it shows in the list straight away; removed again if left empty.
        unsaved = true
        saveNow(force: true)
    }

    /// Moves notes to the Trash, from where they can be restored for 30 days.
    func moveToTrash(_ ids: Set<Data>) {
        guard let store else { return }
        if let open = document?.id, ids.contains(open) {
            saveNow()
            document = nil
        }
        for id in ids {
            do { try store.moveToTrash(id) } catch { problem = "A note could not be moved to the Trash: \(error)" }
        }
        selectedNotes.subtract(ids)
        refresh()
        if document == nil, selectedNotes.isEmpty, let next = summaries.first { select(next.id) }
    }

    func moveToTrash(_ id: Data) { moveToTrash([id]) }

    /// Brings notes back from the Trash, and shows them among the notes.
    func restoreFromTrash(_ ids: Set<Data>) {
        guard let store else { return }
        for id in ids {
            do { try store.restoreFromTrash(id) } catch { return problem = "A note could not be restored: \(error)" }
        }
        document = nil
        showTrash(false)
        document = nil
        select(notes: ids)
    }

    func restoreFromTrash(_ id: Data) { restoreFromTrash([id]) }

    /// Deletes notes for good. Only from the Trash, and only after asking.
    func deletePermanently(_ ids: Set<Data>) {
        guard let store else { return }
        if let open = document?.id, ids.contains(open) { document = nil }
        for id in ids {
            do { try store.delete(id) } catch { problem = "A note could not be deleted: \(error)" }
        }
        selectedNotes.subtract(ids)
        refresh()
        if document == nil, selectedNotes.isEmpty, let next = summaries.first { select(next.id) }
    }

    func deletePermanently(_ id: Data) { deletePermanently([id]) }

    func emptyTrash() {
        guard let store else { return }
        if document?.inTrash == true { document = nil }
        do { try store.emptyTrash() } catch { problem = "The Trash could not be emptied: \(error)" }
        refresh()
    }

    /// Switches the list between the Trash and the notes, all of them.
    func showTrash(_ show: Bool) {
        self.show(show ? .trash : .allNotes)
    }

    /// Shows [place] in the list, and opens its newest note.
    func show(_ place: Place) {
        guard place != self.place else { return }
        leaveCurrentNote()
        selectedNotes = []
        self.place = place
        // A folder with folders inside opens as it is chosen: its arrow is small to aim at, and
        // choosing a folder and seeing nothing inside it looked like the app had not responded.
        if case .folder(let id) = place, folders.contains(where: { $0.parent == id }) { expandedFolders.insert(id) }
        search = ""
        refresh()
        if let first = summaries.first { select(first.id) }
    }

    func refresh() {
        guard let store else { return }
        do {
            let trash = try store.trash()
            trashCount = trash.count
            folders = try store.folders()
            noteCounts = try store.noteCounts()
            // A folder deleted while it was shown leaves the list on the notes in no folder.
            if case .folder(let id) = place, !folders.contains(where: { $0.id == id }) { place = .noFolder }
            let words = search.isEmpty ? nil : search
            defer {
                // Notes that left the list (moved, trashed, filtered out) leave the selection.
                selectedNotes = selectedNotes.filter { id in summaries.contains { $0.id == id } }
                if selectedNotes.count < 2 { selectedNotes = [] }
            }
            summaries = switch place {
            case .trash: trash.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) }
            case .allNotes: try store.summaries(matching: words)
            case .noFolder: try store.summaries(matching: words, in: .noFolder)
            case .folder(let id): try store.summaries(matching: words, in: .folder(id))
            }
        } catch {
            problem = "The list of notes could not be read: \(error)"
        }
    }

    // MARK: - Folders

    /// Makes a folder inside [parent], or at the top, shows it, and asks for its name.
    func newFolder(in parent: Data? = nil) {
        guard let store else { return }
        let taken = Set(folders.filter { $0.parent == parent }.map(\.name))
        let name = sequence(first: 1) { $0 + 1 }.lazy.map { $0 == 1 ? "New Folder" : "New Folder \($0)" }.first { !taken.contains($0) }!
        do {
            let id = try store.createFolder(named: name, in: parent)
            if let parent { expandedFolders.insert(parent) }
            refresh()
            show(.folder(id))
            renamingFolder = id
        } catch {
            problem = "The folder could not be made: \(error)"
        }
    }

    /// Gives a folder a new name. An empty name leaves the name it had.
    func renameFolder(_ id: Data, to name: String) {
        renamingFolder = nil
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let store, !name.isEmpty else { return }
        do { try store.renameFolder(id, to: name) } catch { problem = "The folder could not be renamed: \(error)" }
        refresh()
    }

    /// Moves a folder into another, or to the top with nil. Into itself, or a folder inside
    /// it, nothing happens: that is a drop in the wrong place, not something to explain.
    func moveFolder(_ id: Data, into parent: Data?) {
        guard let store, id != parent else { return }
        do {
            try store.moveFolder(id, into: parent)
            if let parent { expandedFolders.insert(parent) }
        } catch NoteStore.StoreError.folderInsideItself {
            return
        } catch {
            problem = "The folder could not be moved: \(error)"
        }
        refresh()
    }

    /// How many notes deleting a folder would send to the Trash, for asking first.
    func noteCount(deletingFolder id: Data) -> Int {
        (try? store?.noteCount(deletingFolder: id)) ?? 0
    }

    /// Deletes a folder and the folders inside it; their notes go to the Trash.
    func deleteFolder(_ id: Data) {
        guard let store else { return }
        // The note on screen may be one of those going to the Trash: saved first, and closed.
        leaveCurrentNote()
        do { try store.deleteFolder(id) } catch { problem = "The folder could not be deleted: \(error)" }
        expandedFolders.remove(id)
        refresh()
        if let first = summaries.first { select(first.id) }
    }

    /// Moves notes into [folder], or out of every folder with nil.
    func moveNotes(_ ids: Set<Data>, to folder: Data?) {
        guard let store else { return }
        if let open = document?.id, ids.contains(open) { saveNow(force: true) }
        for id in ids {
            do { try store.moveNote(id, to: folder) } catch { problem = "A note could not be moved: \(error)" }
        }
        refresh()
    }

    func moveNote(_ id: Data, to folder: Data?) { moveNotes([id], to: folder) }

    /// The folder's name, for the list's title and the menus.
    func name(of folder: Data?) -> String {
        guard let folder else { return "Notes" }
        return folders.first { $0.id == folder }?.name ?? "Notes"
    }

    /// How deep a folder is: 0 at the top.
    func depth(of folder: NoteStore.Folder) -> Int {
        var depth = 0
        var parent = folder.parent
        while let id = parent, let above = folders.first(where: { $0.id == id }) {
            depth += 1
            parent = above.parent
        }
        return depth
    }

    // MARK: - Saving

    /// The editor on screen, whose text is what gets saved.
    func editorAppeared(_ view: NoteTextView) { editor = view }

    /// The note changed: save it once typing pauses.
    func contentChanged() {
        unsaved = true
        pendingSave?.cancel()
        pendingSave = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    /// Saves the note on screen now, if it has changed.
    func saveNow(force: Bool = false) {
        pendingSave?.cancel()
        // A note in the Trash is only shown; nothing about it is saved until it is restored.
        guard unsaved || force, let store, let document, !document.inTrash else { return }
        let encoded = NoteFormat.encode(text(of: document))
        do {
            for picture in encoded.pictures { try store.storePicture(picture.bytes, id: picture.id) }
            try store.save(encoded.note, id: document.id, title: encoded.title, words: encoded.words, folder: document.folder)
            unsaved = false
            refresh()
        } catch {
            // Kept as unsaved, so the next change or switch tries again; and said out loud.
            problem = "The note could not be saved: \(error)"
        }
    }

    /// The text to save for [document]: the editor's, if the editor is showing that very note;
    /// otherwise the note as it was loaded.
    ///
    /// Taking the editor's text without asking which note it holds saved the previous note's
    /// whole text into every new one: ⌘N saves the new note at once, before SwiftUI has shown
    /// it, while the editor still holds the note before.
    private func text(of document: EditorDocument) -> NSAttributedString {
        if let editor, editor.noteID == document.id, let storage = editor.textStorage {
            return NSAttributedString(attributedString: storage)
        }
        return document.text
    }

    /// Saves the note being left, and removes it if nothing was ever put in it.
    private func leaveCurrentNote() {
        saveNow()
        guard let document, let store else { return }
        guard !document.inTrash else { return self.document = nil }
        if NoteFormat.encode(text(of: document)).note.isEmpty {
            // Straight out, not to the Trash: there is nothing in it to want back.
            try? store.delete(document.id)
            refresh()
        }
        self.document = nil
    }

    // MARK: - Changing the passphrase

    /// Changes the passphrase, locking every note and picture again under it. Throws with a
    /// message fit to show; nothing is changed then.
    ///
    /// The slow part (writing and checking the new copy) runs away from the main thread. The
    /// notes stay open meanwhile, and the sheet asking for this covers the window, so nothing
    /// is written to them while they are copied.
    func changePassphrase(current: String, new: String, repeated: String) async throws {
        guard new.count >= Self.shortestPassphrase else {
            throw Message("Use at least \(Self.shortestPassphrase) characters for the new passphrase.")
        }
        guard new == repeated else { throw Message("The two new passphrases are not the same.") }
        guard new != current else { throw Message("That is the passphrase you have now.") }
        guard let store else { return }
        saveNow()
        let home = self.home
        let vault: Vault
        do {
            vault = try await Task.detached {
                try PassphraseChange.prepare(store: store, home: home, current: current, new: new)
            }.value
        } catch LockError.wrongPassphrase {
            throw Message("That is not your current passphrase.")
        } catch {
            throw Message("The passphrase was not changed, and your notes are as they were. \(error)")
        }

        // Swapped with the notes closed, so nothing can write to the folder as it moves.
        let open = document?.id
        let shown = place
        document = nil
        try? store.close()
        self.store = nil
        var opened: Vault = vault
        do {
            try PassphraseChange.swap(home: home)
        } catch {
            // Settle on whichever notes are now in place: the change finished, or did not.
            _ = try? PassphraseChange.finishInterruptedChange(home: home)
            if (try? Vault.unlock(in: home, passphrase: new)) == nil { opened = store.vault }
        }
        do {
            self.store = try NoteStore(folder: home, vault: opened)
        } catch {
            lock()
            throw Message("The notes could not be opened again; unlock them with \(opened.header.id == vault.header.id ? "the new" : "your current") passphrase. \(error)")
        }
        place = shown
        refresh()
        if let open { select(open) }
        guard opened.header.id == vault.header.id else {
            throw Message("The passphrase was not changed, and your notes are as they were.")
        }
    }

    // MARK: - Backups

    /// Asks where to save a backup of every note, and writes it.
    func exportBackup() {
        saveNow()
        guard let store else { return }
        let panel = NSSavePanel()
        panel.title = "Export Backup"
        panel.message = "Every note and picture, locked with your passphrase. Keep it somewhere safe; without the passphrase it cannot be opened, by anyone."
        panel.nameFieldStringValue = "SyncedNotes Backup \(Date().formatted(.iso8601.year().month().day())).\(Self.backupExtension)"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let backup = try Backup.make(from: store)
            try backup.write(to: url, options: .atomic)
            let count = try store.summaries().count
            notice = "Backed up \(count) \(count == 1 ? "note" : "notes") to “\(url.lastPathComponent)”."
        } catch {
            problem = "The backup could not be written: \(error)"
        }
    }

    /// Asks for a backup to import. One from this vault is read straight away; one from
    /// another vault waits for that vault's passphrase.
    func importBackup() {
        saveNow()
        guard let store else { return }
        let panel = NSOpenPanel()
        panel.title = "Import Backup"
        panel.message = "Notes already here that are newer than the backup's copy are kept as they are."
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            let file = try Data(contentsOf: url)
            let header = try Backup.vaultHeader(of: file)
            if header.id == store.vault.header.id {
                try restore(Backup.open(file, with: store.vault))
            } else {
                importWaitingForPassphrase = file
            }
        } catch Backup.BackupError.notABackup {
            problem = "“\(url.lastPathComponent)” is not a SyncedNotes backup."
        } catch {
            problem = "The backup could not be read: \(error)"
        }
    }

    /// Finishes an import from another vault; false if [passphrase] is not that vault's.
    func importBackup(passphrase: String) -> Bool {
        guard let file = importWaitingForPassphrase else { return true }
        do {
            let theirs = try Vault.unlock(Backup.vaultHeader(of: file), passphrase: passphrase)
            importWaitingForPassphrase = nil
            try restore(Backup.open(file, with: theirs))
            return true
        } catch LockError.wrongPassphrase {
            return false
        } catch {
            importWaitingForPassphrase = nil
            problem = "The backup could not be read: \(error)"
            return true
        }
    }

    private func restore(_ backup: StoredBackup) throws {
        guard let store else { return }
        let result = try Backup.restore(backup, into: store)
        let shown = document?.id
        refresh()
        // The note on screen may be one the backup just brought up to date.
        if let shown, result.restored > 0 {
            document = nil
            select(shown)
        } else if document == nil, let newest = summaries.first {
            select(newest.id)
        }
        notice = Self.describe(result)
    }

    /// What an import did, in words. Each kind of note is named for what actually happened to
    /// it: an earlier message called deleted notes "already here".
    static func describe(_ result: Backup.Result) -> String {
        func notes(_ count: Int) -> String { "\(count) \(count == 1 ? "note" : "notes")" }
        let datesSetBack = result.datesSetBack > 0
            ? " \(result.datesSetBack == 1 ? "The date of 1 note was" : "The dates of \(result.datesSetBack) notes were") set back to when \(result.datesSetBack == 1 ? "it was" : "they were") last edited."
            : ""
        let keptNewer = result.keptNewer > 0
            ? " \(notes(result.keptNewer)) changed here after the backup \(result.keptNewer == 1 ? "was" : "were") kept as \(result.keptNewer == 1 ? "it is" : "they are")."
            : ""
        guard result.restored > 0 else {
            if result.unchanged + result.keptNewer == 0 { return "The backup has no notes in it." }
            return "Nothing to restore: every note in the backup is already here." + datesSetBack + keptNewer
        }
        var parts: [String] = []
        if result.undeleted > 0 { parts.append("\(result.undeleted) that had been deleted") }
        if result.added > 0 { parts.append("\(result.added) new here") }
        if result.updated > 0 { parts.append("\(result.updated) brought up to date") }
        var text = "Restored \(notes(result.restored))"
        text += parts.count == 1 && result.undeleted > 0 ? ", which had been deleted." : ": " + parts.joined(separator: ", ") + "."
        return text + datesSetBack + keptNewer
    }

    static let backupExtension = "snbackup"

    struct Message: LocalizedError {
        let text: String
        init(_ text: String) { self.text = text }
        var errorDescription: String? { text }
    }
}
