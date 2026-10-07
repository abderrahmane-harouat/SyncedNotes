import Foundation
import GRDB

/// Changing the passphrase.
///
/// Every key is made from the passphrase (LockFormat.md), so a new passphrase means a new
/// vault: a new salt and a new vault ID, the database locked again, and every note and picture
/// locked again. A new ID rather than the old one, so a backup made before the change is known
/// to be from another vault, and asks for the passphrase it was made with.
///
/// Nothing is changed in place. The notes are written, locked the new way, into a folder beside
/// them; every note and picture is opened there with the new passphrase and compared with the
/// original; only then does that folder take the old one's place, and the old one is deleted,
/// so the old passphrase opens nothing left on the Mac. Interrupted at any point, the next
/// launch either finishes the change or drops it (`finishInterruptedChange`), and the notes
/// always open with exactly one of the two passphrases.
public enum PassphraseChange {

    public enum ChangeError: Error, Equatable {
        /// The copy did not come out the same as the notes, so nothing was changed.
        case copyDiffers(String)
    }

    /// Where the new notes are written before they take the old ones' place.
    static func staging(for home: URL) -> URL {
        home.deletingLastPathComponent().appendingPathComponent(home.lastPathComponent + "-changing-passphrase", isDirectory: true)
    }

    /// Where the old notes go for the moment between the two renames.
    static func replaced(for home: URL) -> URL {
        home.deletingLastPathComponent().appendingPathComponent(home.lastPathComponent + "-old-passphrase", isDirectory: true)
    }

    /// Written in the new folder once everything in it has been checked.
    static func checkedMark(in staging: URL) -> URL { staging.appendingPathComponent("checked") }

    /// Writes and checks the notes in [store], locked under a new vault for [new], beside
    /// [home]. Changes nothing in [home]. [current] must be the passphrase in use: asked
    /// again, so an unlocked Mac left alone is not enough to change it.
    ///
    /// Returns the new vault. Then close [store] and call `swap`.
    public static func prepare(store: NoteStore, home: URL, current: String, new: String) throws -> Vault {
        let old = try Vault.unlock(store.vault.header, passphrase: current)
        guard old.header.id == store.vault.header.id else { throw LockError.wrongPassphrase }
        let vault = try Vault.create(passphrase: new)

        let staging = staging(for: home)
        try? FileManager.default.removeItem(at: staging)
        try FileManager.default.createDirectory(at: staging.appendingPathComponent("pictures", isDirectory: true), withIntermediateDirectories: true)
        do {
            try store.export(to: staging.appendingPathComponent("notes.sqlite"), databaseKey: vault.databaseKeyBytes)
            try relockItems(in: staging, from: home, old: old, new: vault)
            try vault.header.encoded.write(to: Vault.headerFile(in: staging), options: .atomic)
            try check(staging, opensLike: store, passphrase: new)
            try Data().write(to: checkedMark(in: staging), options: .atomic)
        } catch {
            try? FileManager.default.removeItem(at: staging)
            throw error
        }
        return vault
    }

    /// Puts the checked new notes in [home]'s place and deletes the old ones. The store on
    /// [home] must be closed first.
    public static func swap(home: URL) throws {
        let staging = staging(for: home)
        let replaced = replaced(for: home)
        guard FileManager.default.fileExists(atPath: checkedMark(in: staging).path) else {
            throw ChangeError.copyDiffers("the new notes were not checked")
        }
        try FileManager.default.moveItem(at: home, to: replaced)
        try FileManager.default.moveItem(at: staging, to: home)
        try FileManager.default.removeItem(at: checkedMark(in: home))
        try FileManager.default.removeItem(at: replaced)
    }

    /// Run at launch, before anything is opened: finishes a change that was checked but not
    /// yet in place, and drops one that was not checked. Returns whether it found one.
    @discardableResult
    public static func finishInterruptedChange(home: URL) throws -> Bool {
        let files = FileManager.default
        let staging = staging(for: home)
        let replaced = replaced(for: home)
        let checked = files.fileExists(atPath: checkedMark(in: staging).path)
        if files.fileExists(atPath: staging.path) {
            if checked {
                // Checked, so it is the notes now: as if the change had finished.
                if files.fileExists(atPath: home.path) {
                    if files.fileExists(atPath: replaced.path) { try files.removeItem(at: replaced) }
                    try files.moveItem(at: home, to: replaced)
                }
                try files.moveItem(at: staging, to: home)
                try files.removeItem(at: checkedMark(in: home))
            } else if files.fileExists(atPath: home.path) {
                // Not checked: the old notes are still the notes.
                try files.removeItem(at: staging)
                return true
            }
        }
        // Stopped after the new notes were in place, before the old ones were deleted.
        if files.fileExists(atPath: checkedMark(in: home).path) { try files.removeItem(at: checkedMark(in: home)) }
        if files.fileExists(atPath: replaced.path), files.fileExists(atPath: home.path) {
            try files.removeItem(at: replaced)
            return true
        }
        return checked
    }

    // MARK: - Steps

    /// Every note in the copied database, and every picture file, opened with the old vault
    /// and locked again with the new one.
    private static func relockItems(in staging: URL, from home: URL, old: Vault, new: Vault) throws {
        let database = try DatabaseQueue(path: staging.appendingPathComponent("notes.sqlite").path, configuration: NoteStore.configuration(for: new))
        defer { try? database.close() }
        try database.write { db in
            for row in try Row.fetchAll(db, sql: "SELECT id, locked FROM notes WHERE locked IS NOT NULL") {
                let id: Data = row["id"]
                let item = try old.open(row["locked"], expecting: .note)
                guard item.id == id else { throw LockError.damaged }
                try db.execute(sql: "UPDATE notes SET locked = ? WHERE id = ?", arguments: [new.lock(item.content, kind: .note, id: id), id])
            }
        }
        let pictures = try database.read { db in try Row.fetchAll(db, sql: "SELECT id, file FROM pictures") }
        for row in pictures {
            let id: Data = row["id"]
            let file: String = row["file"]
            let source = home.appendingPathComponent("pictures").appendingPathComponent(file)
            // A picture whose file was already gone stays gone, as it is now; it is not made up.
            guard let locked = try? Data(contentsOf: source) else { continue }
            let item = try old.open(locked, expecting: .picture)
            guard item.id == id else { throw LockError.damaged }
            try new.lock(item.content, kind: .picture, id: id)
                .write(to: staging.appendingPathComponent("pictures").appendingPathComponent(file), options: .atomic)
        }
    }

    /// Opens the new folder from its own header with the new passphrase, as the next unlock
    /// will, and compares everything in it with [store]: every note, deleted or not, every
    /// folder, every picture that could be read before, and search.
    private static func check(_ staging: URL, opensLike store: NoteStore, passphrase: String) throws {
        let vault = try Vault.unlock(in: staging, passphrase: passphrase)
        let copy = try NoteStore(folder: staging, vault: vault)
        defer { try? copy.close() }
        func same<T: Equatable>(_ what: String, _ a: T, _ b: T) throws {
            guard a == b else { throw ChangeError.copyDiffers(what) }
        }
        try same("the folders", try copy.folders(), try store.folders())
        try same("the list of notes", try copy.summaries(), try store.summaries())
        try same("the Trash", try copy.trash(), try store.trash())
        let notes = try store.everyNote()
        try same("the notes", try copy.everyNote().map(\.id), notes.map(\.id))
        for note in notes {
            try same("a note", try copy.load(note.id), try store.load(note.id))
            try same("a note's change time", try copy.lastChanged(note.id), try store.lastChanged(note.id))
            try same("a note's folder", try copy.folder(of: note.id), try store.folder(of: note.id))
        }
        try same("the deleted notes", try copy.deletedIDs(), try store.deletedIDs())
        for id in try store.pictureIDs() {
            try same("a picture", try? copy.picture(id), try? store.picture(id))
        }
        try same("search", try copy.searchRows(), try store.searchRows())
    }
}

extension NoteStore {

    /// The database settings for [vault]'s key: SQLCipher's raw-key form, as `init` uses.
    static func configuration(for vault: Vault) -> Configuration {
        var configuration = Configuration()
        let rawKey = "x'\(vault.databaseKeyBytes.hexString)'"
        configuration.prepareDatabase { db in try db.usePassphrase(rawKey) }
        return configuration
    }

    /// Copies the whole database to [file], locked with [databaseKey]: SQLCipher's own export,
    /// which its makers recommend over changing the key of a database in place.
    func export(to file: URL, databaseKey: Data) throws {
        try database.writeWithoutTransaction { db in
            try db.execute(sql: "ATTACH DATABASE ? AS changed KEY \"x'\(databaseKey.hexString)'\"", arguments: [file.path])
            defer { try? db.execute(sql: "DETACH DATABASE changed") }
            try db.execute(sql: "SELECT sqlcipher_export('changed')")
        }
    }

    /// Closes the database, before its folder is moved.
    public func close() throws {
        try database.close()
    }

    func deletedIDs() throws -> [Data] {
        try database.read { db in try Data.fetchAll(db, sql: "SELECT id FROM notes WHERE deleted IS NOT NULL ORDER BY id") }
    }

    func pictureIDs() throws -> [Data] {
        try database.read { db in try Data.fetchAll(db, sql: "SELECT id FROM pictures ORDER BY id") }
    }

    func searchRows() throws -> [String] {
        try database.read { db in
            try Row.fetchAll(db, sql: "SELECT note, words FROM search ORDER BY note").map { row in
                let note: Data = row["note"]
                let words: String = row["words"]
                return note.hexString + ":" + words
            }
        }
    }
}
