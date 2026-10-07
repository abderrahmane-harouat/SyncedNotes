import Foundation
import GRDB
import SwiftProtobuf

/// The notes on this device.
///
/// An SQLCipher database, locked as a whole with the vault's database key, holds each note as
/// a locked item (LockFormat.md) with Protobuf inside (note.proto). Pictures are locked items
/// too, in files beside it with random names. So a note is locked twice while it rests here,
/// and the inner lock is exactly what would travel to a second device.
public final class NoteStore: Sendable {

    public struct Summary: Identifiable, Equatable, Sendable {
        public let id: Data
        public let title: String
        public let updated: Date
        /// When it was moved to the Trash; nil for a note that is not in it.
        public var trashed: Date? = nil
        /// The folder it is in; nil for a note in no folder.
        public var folder: Data? = nil
    }

    /// A folder of notes. Folders can hold folders.
    public struct Folder: Identifiable, Equatable, Sendable {
        public let id: Data
        public var name: String
        /// The folder it is in; nil for one at the top.
        public var parent: Data?
        public let created: Date
    }

    /// Which notes a list shows.
    public enum Scope: Equatable, Sendable {
        /// Every note, whatever its folder.
        case all
        /// The notes in no folder.
        case noFolder
        /// The notes in this folder itself, not in the folders inside it.
        case folder(Data)
    }

    /// How long a note stays in the Trash before it is deleted for good, as in Apple Notes.
    public static let trashKeepsFor: TimeInterval = 30 * 24 * 60 * 60

    public enum StoreError: Error, Equatable {
        /// The database would not open with this vault's key: another vault's, or damaged.
        case cannotOpen
        case noSuchNote
        case noSuchPicture
        case noSuchFolder
        /// A folder cannot go inside itself, or inside a folder within it.
        case folderInsideItself
    }

    public let vault: Vault
    /// Not private: changing the passphrase exports it (PassphraseChange).
    let database: DatabaseQueue
    private let picturesFolder: URL

    /// Opens (or creates) the store in [folder] with [vault]'s keys.
    public init(folder: URL, vault: Vault) throws {
        self.vault = vault
        picturesFolder = folder.appendingPathComponent("pictures", isDirectory: true)
        try FileManager.default.createDirectory(at: picturesFolder, withIntermediateDirectories: true)

        // SQLCipher's raw-key form: the key is already the output of Argon2id and HKDF, so it
        // is used as it is, not run through SQLCipher's own key derivation a second time.
        let configuration = Self.configuration(for: vault)
        do {
            database = try DatabaseQueue(path: folder.appendingPathComponent("notes.sqlite").path, configuration: configuration)
            // SQLCipher only finds out the key is wrong on the first read.
            try database.read { db in _ = try Int.fetchOne(db, sql: "SELECT count(*) FROM sqlite_master") }
        } catch {
            throw StoreError.cannotOpen
        }
        try Self.migrator.migrate(database)
    }

    private static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("v1") { db in
            // `title` is kept in the clear inside the locked database, so the list of notes
            // does not have to open every note. `locked` is empty for a deleted note, whose
            // row stays for a while so that the other device learns of the deletion instead
            // of sending the note back.
            try db.execute(sql: """
                CREATE TABLE notes (
                    id BLOB PRIMARY KEY NOT NULL,
                    locked BLOB,
                    title TEXT NOT NULL DEFAULT '',
                    created REAL NOT NULL,
                    updated REAL NOT NULL,
                    deleted REAL
                );
                CREATE TABLE pictures (
                    id BLOB PRIMARY KEY NOT NULL,
                    file TEXT NOT NULL UNIQUE
                );
                """)
            try db.create(virtualTable: "search", using: FTS5()) { table in
                table.tokenizer = .unicode61()
                table.column("note").notIndexed()
                table.column("words")
            }
        }
        migrator.registerMigration("v2: when a note last changed, apart from when it was edited") { db in
            // `updated` is when the note was last edited: shown in the list, and what it is
            // sorted by. `changed` is when anything about the row last changed, for sync: a
            // note brought back from a backup keeps its own edit date, but must still count as
            // newer than its deletion, or the other device deletes it again.
            try db.execute(sql: "ALTER TABLE notes ADD COLUMN changed REAL")
            try db.execute(sql: "UPDATE notes SET changed = updated")
        }
        migrator.registerMigration("v3: the Trash") { db in
            // A deleted note first goes to the Trash with its content, and can be restored;
            // only deleting it from there, by hand or after 30 days, leaves the bare mark.
            try db.execute(sql: "ALTER TABLE notes ADD COLUMN trashed REAL")
        }
        migrator.registerMigration("v4: folders") { db in
            // A folder's name is kept in the clear inside the locked database, as a note's
            // title is. A note's `folder` is NULL when it is in no folder. A note in the Trash
            // keeps its folder, so restoring it puts it back there, if the folder still exists.
            try db.execute(sql: """
                CREATE TABLE folders (
                    id BLOB PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    parent BLOB REFERENCES folders(id),
                    created REAL NOT NULL,
                    changed REAL NOT NULL
                );
                ALTER TABLE notes ADD COLUMN folder BLOB;
                """)
        }
        return migrator
    }

    // MARK: - Notes

    /// The notes in [scope] that are neither deleted nor in the Trash, newest first; only
    /// those matching [search] if given.
    public func summaries(matching search: String? = nil, in scope: Scope = .all) throws -> [Summary] {
        try database.read { db in
            var conditions = ["notes.deleted IS NULL", "notes.trashed IS NULL"]
            var arguments: StatementArguments = []
            switch scope {
            case .all: break
            case .noFolder: conditions.append("notes.folder IS NULL")
            case .folder(let id):
                conditions.append("notes.folder = ?")
                arguments += [id]
            }
            var join = ""
            if let search, let pattern = FTS5Pattern(matchingAllPrefixesIn: search) {
                join = "JOIN search ON search.note = notes.id"
                conditions.append("search MATCH ?")
                arguments += [pattern]
            }
            let rows = try Row.fetchAll(db, sql: """
                SELECT notes.id, notes.title, notes.updated, notes.folder FROM notes \(join)
                WHERE \(conditions.joined(separator: " AND ")) ORDER BY notes.updated DESC
                """, arguments: arguments)
            return rows.map {
                Summary(id: $0["id"], title: $0["title"], updated: Date(timeIntervalSince1970: $0["updated"]), folder: $0["folder"])
            }
        }
    }

    public func load(_ id: Data) throws -> StoredNote {
        guard let locked = try database.read({ db in
            try Data.fetchOne(db, sql: "SELECT locked FROM notes WHERE id = ? AND deleted IS NULL", arguments: [id])
        }) else { throw StoreError.noSuchNote }
        let item = try vault.open(locked, expecting: .note)
        guard item.id == id else { throw LockError.damaged }
        return try StoredNote(serializedBytes: item.content)
    }

    /// Saves [note] under [id], creating it if new, as edited at [now]. [words] is its plain
    /// text, for search. [created] and [changed] are only for a note restored from a backup,
    /// which keeps its own dates but changes now.
    /// [folder] is where a new note goes; a note saved again stays where it is.
    public func save(
        _ note: StoredNote, id: Data, title: String, words: String,
        at now: Date = Date(), created: Date? = nil, changed: Date? = nil, folder: Data? = nil
    ) throws {
        let locked = try vault.lock(note.serializedData(), kind: .note, id: id)
        try database.write { db in
            try db.execute(sql: """
                INSERT INTO notes (id, locked, title, created, updated, changed, deleted, folder) VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                ON CONFLICT(id) DO UPDATE SET locked = excluded.locked, title = excluded.title,
                    updated = excluded.updated, changed = excluded.changed, deleted = NULL
                """, arguments: [
                    id, locked, title, (created ?? now).timeIntervalSince1970,
                    now.timeIntervalSince1970, (changed ?? now).timeIntervalSince1970, folder,
                ])
            try db.execute(sql: "DELETE FROM search WHERE note = ?", arguments: [id])
            try db.execute(sql: "INSERT INTO search (note, words) VALUES (?, ?)", arguments: [id, words])
        }
    }

    // MARK: - The Trash

    /// The notes in the Trash, the most recently trashed first.
    public func trash() throws -> [Summary] {
        try database.read { db in
            try Row.fetchAll(db, sql: """
                SELECT id, title, updated, trashed FROM notes WHERE deleted IS NULL AND trashed IS NOT NULL ORDER BY trashed DESC
                """).map {
                Summary(id: $0["id"], title: $0["title"], updated: Date(timeIntervalSince1970: $0["updated"]),
                        trashed: Date(timeIntervalSince1970: $0["trashed"]))
            }
        }
    }

    /// Moves a note to the Trash, content and all, from where it can be restored.
    public func moveToTrash(_ id: Data, at now: Date = Date()) throws {
        try database.write { db in
            try db.execute(sql: "UPDATE notes SET trashed = ?, changed = ? WHERE id = ? AND deleted IS NULL",
                           arguments: [now.timeIntervalSince1970, now.timeIntervalSince1970, id])
        }
    }

    /// Takes a note out of the Trash, back to the notes.
    /// It goes back to its folder, or, if that folder has been deleted since, to no folder.
    public func restoreFromTrash(_ id: Data, at now: Date = Date()) throws {
        try database.write { db in
            try db.execute(sql: """
                UPDATE notes SET trashed = NULL, changed = ?,
                    folder = CASE WHEN folder IN (SELECT id FROM folders) THEN folder END
                WHERE id = ?
                """, arguments: [now.timeIntervalSince1970, id])
        }
    }

    /// Deletes for good every note that has been in the Trash longer than [age].
    @discardableResult
    public func emptyTrash(olderThan age: TimeInterval = 0, at now: Date = Date()) throws -> Int {
        let cutoff = now.addingTimeInterval(-age).timeIntervalSince1970
        let expired = try database.read { db in
            try Data.fetchAll(db, sql: "SELECT id FROM notes WHERE deleted IS NULL AND trashed IS NOT NULL AND trashed <= ?", arguments: [cutoff])
        }
        for id in expired { try delete(id, at: now) }
        return expired.count
    }

    /// Whether a note is in the Trash.
    public func isInTrash(_ id: Data) throws -> Bool {
        try database.read { db in
            try Bool.fetchOne(db, sql: "SELECT trashed IS NOT NULL FROM notes WHERE id = ? AND deleted IS NULL", arguments: [id])
        } ?? false
    }

    /// When a note was moved to the Trash, for a backup; nil if it is not in it.
    func trashedAt(_ id: Data) throws -> Date? {
        try database.read { db in
            try Double.fetchOne(db, sql: "SELECT trashed FROM notes WHERE id = ?", arguments: [id])
        }.map(Date.init(timeIntervalSince1970:))
    }

    /// Puts a note in the Trash as of [date], or takes it out with nil, without counting it as
    /// a change: for restoring a backup, which says where each note was.
    func setTrashed(_ id: Data, at date: Date?) throws {
        try database.write { db in
            try db.execute(sql: "UPDATE notes SET trashed = ? WHERE id = ?", arguments: [date?.timeIntervalSince1970, id])
        }
    }

    // MARK: - Deleting for good

    /// Deletes a note for good, content and all, leaving only the mark the other device needs
    /// to learn of it. The Trash is the way to delete with a way back.
    public func delete(_ id: Data, at now: Date = Date()) throws {
        try database.write { db in
            try db.execute(sql: """
                UPDATE notes SET locked = NULL, title = '', updated = ?, changed = ?, deleted = ?, trashed = NULL WHERE id = ?
                """, arguments: [now.timeIntervalSince1970, now.timeIntervalSince1970, now.timeIntervalSince1970, id])
            try db.execute(sql: "DELETE FROM search WHERE note = ?", arguments: [id])
        }
    }

    /// Removes a note that never had anything in it, without a mark: there is nothing for the
    /// other device to learn.
    public func discard(_ id: Data) throws {
        try database.write { db in
            try db.execute(sql: "DELETE FROM notes WHERE id = ?", arguments: [id])
            try db.execute(sql: "DELETE FROM search WHERE note = ?", arguments: [id])
        }
    }

    /// When anything about a note last changed here, its deletion included, for sync; nil if
    /// this store has never had it.
    public func lastChanged(_ id: Data) throws -> Date? {
        try database.read { db in
            try Double.fetchOne(db, sql: "SELECT changed FROM notes WHERE id = ?", arguments: [id])
        }.map(Date.init(timeIntervalSince1970:))
    }

    /// When a note was last edited, or deleted: the date the list shows.
    public func lastEdited(_ id: Data) throws -> Date? {
        try database.read { db in
            try Double.fetchOne(db, sql: "SELECT updated FROM notes WHERE id = ?", arguments: [id])
        }.map(Date.init(timeIntervalSince1970:))
    }

    /// Sets when a note was last edited, leaving everything else as it is.
    func setLastEdited(_ id: Data, to date: Date) throws {
        try database.write { db in
            try db.execute(sql: "UPDATE notes SET updated = ? WHERE id = ?", arguments: [date.timeIntervalSince1970, id])
        }
    }

    /// Every note that is not deleted, the Trash included, oldest first, for a backup.
    public func everyNote() throws -> [(id: Data, created: Date, updated: Date, trashed: Date?, folder: Data?)] {
        try database.read { db in
            try Row.fetchAll(db, sql: "SELECT id, created, updated, trashed, folder FROM notes WHERE deleted IS NULL ORDER BY created").map {
                let trashed: Double? = $0["trashed"]
                return ($0["id"], Date(timeIntervalSince1970: $0["created"]), Date(timeIntervalSince1970: $0["updated"]),
                        trashed.map(Date.init(timeIntervalSince1970:)), $0["folder"])
            }
        }
    }

    /// Whether a note has been deleted (its mark remains).
    public func isDeleted(_ id: Data) throws -> Bool {
        try database.read { db in
            try Bool.fetchOne(db, sql: "SELECT deleted IS NOT NULL FROM notes WHERE id = ?", arguments: [id]) ?? false
        }
    }

    // MARK: - Folders

    /// Every folder, parents before the folders inside them, each level by name.
    public func folders() throws -> [Folder] {
        let all = try database.read { db in
            try Row.fetchAll(db, sql: "SELECT id, name, parent, created FROM folders").map {
                Folder(id: $0["id"], name: $0["name"], parent: $0["parent"], created: Date(timeIntervalSince1970: $0["created"]))
            }
        }
        let children = Dictionary(grouping: all, by: \.parent)
        func ordered(in parent: Data?) -> [Folder] {
            (children[parent] ?? [])
                .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
                .flatMap { [$0] + ordered(in: $0.id) }
        }
        return ordered(in: nil)
    }

    /// Makes a folder named [name] inside [parent], or at the top; returns its ID.
    @discardableResult
    public func createFolder(named name: String, in parent: Data? = nil, at now: Date = Date()) throws -> Data {
        let id = systemRandom(16)
        try database.write { db in
            if let parent, try !Self.folderExists(parent, db) { throw StoreError.noSuchFolder }
            try db.execute(sql: "INSERT INTO folders (id, name, parent, created, changed) VALUES (?, ?, ?, ?, ?)",
                           arguments: [id, name, parent, now.timeIntervalSince1970, now.timeIntervalSince1970])
        }
        return id
    }

    public func renameFolder(_ id: Data, to name: String, at now: Date = Date()) throws {
        try database.write { db in
            guard try Self.folderExists(id, db) else { throw StoreError.noSuchFolder }
            try db.execute(sql: "UPDATE folders SET name = ?, changed = ? WHERE id = ?", arguments: [name, now.timeIntervalSince1970, id])
        }
    }

    /// Moves a folder, with everything in it, into [parent], or to the top.
    public func moveFolder(_ id: Data, into parent: Data?, at now: Date = Date()) throws {
        try database.write { db in
            guard try Self.folderExists(id, db) else { throw StoreError.noSuchFolder }
            if let parent {
                guard try Self.folderExists(parent, db) else { throw StoreError.noSuchFolder }
                // Walking up from the new parent must never meet the folder being moved, or the
                // two would hold each other and drop out of the tree.
                var step: Data? = parent
                while let current = step {
                    if current == id { throw StoreError.folderInsideItself }
                    step = try Data.fetchOne(db, sql: "SELECT parent FROM folders WHERE id = ?", arguments: [current])
                }
            }
            try db.execute(sql: "UPDATE folders SET parent = ?, changed = ? WHERE id = ?", arguments: [parent, now.timeIntervalSince1970, id])
        }
    }

    /// Deletes a folder and the folders inside it, and moves their notes to the Trash, from
    /// where they can still be restored; they come back to no folder. Returns how many notes
    /// went to the Trash.
    @discardableResult
    public func deleteFolder(_ id: Data, at now: Date = Date()) throws -> Int {
        try database.write { db in
            guard try Self.folderExists(id, db) else { throw StoreError.noSuchFolder }
            let doomed = try Self.folderAndInside(id, db)
            let marks = databaseQuestionMarks(count: doomed.count)
            let time = now.timeIntervalSince1970
            let moved = try Int.fetchOne(db, sql: """
                SELECT count(*) FROM notes WHERE folder IN (\(marks)) AND deleted IS NULL AND trashed IS NULL
                """, arguments: StatementArguments(doomed)) ?? 0
            try db.execute(sql: """
                UPDATE notes SET trashed = ?, changed = ? WHERE folder IN (\(marks)) AND deleted IS NULL AND trashed IS NULL
                """, arguments: [time, time] + StatementArguments(doomed))
            // Children first, as each row points at its parent.
            for folder in doomed.reversed() {
                try db.execute(sql: "DELETE FROM folders WHERE id = ?", arguments: [folder])
            }
            return moved
        }
    }

    /// How many notes outside the Trash would go with [id]: in it and in the folders inside it.
    public func noteCount(deletingFolder id: Data) throws -> Int {
        try database.read { db in
            let doomed = try Self.folderAndInside(id, db)
            return try Int.fetchOne(db, sql: """
                SELECT count(*) FROM notes WHERE folder IN (\(databaseQuestionMarks(count: doomed.count))) AND deleted IS NULL AND trashed IS NULL
                """, arguments: StatementArguments(doomed)) ?? 0
        }
    }

    /// How many notes outside the Trash each folder holds itself, by folder ID; nil for the
    /// notes in no folder.
    public func noteCounts() throws -> [Data?: Int] {
        try database.read { db in
            var counts: [Data?: Int] = [:]
            for row in try Row.fetchAll(db, sql: "SELECT folder, count(*) AS n FROM notes WHERE deleted IS NULL AND trashed IS NULL GROUP BY folder") {
                counts[row["folder"] as Data?] = row["n"]
            }
            return counts
        }
    }

    /// Moves a note into [folder], or out of any folder with nil.
    public func moveNote(_ id: Data, to folder: Data?, at now: Date = Date()) throws {
        try database.write { db in
            if let folder, try !Self.folderExists(folder, db) { throw StoreError.noSuchFolder }
            try db.execute(sql: "UPDATE notes SET folder = ?, changed = ? WHERE id = ? AND deleted IS NULL",
                           arguments: [folder, now.timeIntervalSince1970, id])
        }
    }

    /// The folder a note is in; nil for none.
    public func folder(of id: Data) throws -> Data? {
        try database.read { db in
            try Data.fetchOne(db, sql: "SELECT folder FROM notes WHERE id = ?", arguments: [id])
        }
    }

    /// Puts a folder back as a backup had it, unless one with its ID is here already, which
    /// keeps its own name and place. Returns whether it was added.
    func restoreFolder(_ id: Data, name: String, parent: Data?, created: Date, at now: Date) throws -> Bool {
        try database.write { db in
            guard try !Self.folderExists(id, db) else { return false }
            // A parent that did not come through stays out of the picture: the folder goes at the top.
            let kept = try parent.flatMap { try Self.folderExists($0, db) ? $0 : nil }
            try db.execute(sql: "INSERT INTO folders (id, name, parent, created, changed) VALUES (?, ?, ?, ?, ?)",
                           arguments: [id, name, kept, created.timeIntervalSince1970, now.timeIntervalSince1970])
            return true
        }
    }

    /// Puts a note in a folder as a backup had it, without counting it as a change; a folder
    /// that is not here leaves it in none.
    func setFolder(_ id: Data, to folder: Data?) throws {
        try database.write { db in
            let kept = try folder.flatMap { try Self.folderExists($0, db) ? $0 : nil }
            try db.execute(sql: "UPDATE notes SET folder = ? WHERE id = ?", arguments: [kept, id])
        }
    }

    private static func folderExists(_ id: Data, _ db: Database) throws -> Bool {
        try Bool.fetchOne(db, sql: "SELECT 1 FROM folders WHERE id = ?", arguments: [id]) == true
    }

    /// [id] and every folder inside it, at any depth, parents first.
    private static func folderAndInside(_ id: Data, _ db: Database) throws -> [Data] {
        var found = [id]
        var index = 0
        while index < found.count {
            found += try Data.fetchAll(db, sql: "SELECT id FROM folders WHERE parent = ?", arguments: [found[index]])
            index += 1
        }
        return found
    }

    // MARK: - Pictures

    /// Stores a picture's bytes, locked, under a random file name; does nothing if [id] is
    /// stored already, since a picture's bytes never change.
    public func storePicture(_ bytes: Data, id: Data) throws {
        if try database.read({ db in try Bool.fetchOne(db, sql: "SELECT 1 FROM pictures WHERE id = ?", arguments: [id]) }) == true {
            return
        }
        // Random, never derived from the picture: nothing about it can be guessed from the name.
        let file = systemRandom(16).hexString + ".snl"
        let locked = try vault.lock(bytes, kind: .picture, id: id)
        try locked.write(to: picturesFolder.appendingPathComponent(file), options: .atomic)
        try database.write { db in
            try db.execute(sql: "INSERT INTO pictures (id, file) VALUES (?, ?)", arguments: [id, file])
        }
    }

    public func picture(_ id: Data) throws -> Data {
        guard let file = try database.read({ db in
            try String.fetchOne(db, sql: "SELECT file FROM pictures WHERE id = ?", arguments: [id])
        }) else { throw StoreError.noSuchPicture }
        let item = try vault.open(Data(contentsOf: picturesFolder.appendingPathComponent(file)), expecting: .picture)
        guard item.id == id else { throw LockError.damaged }
        return item.content
    }

    /// Where a picture's locked file is, for tests that check what reaches the disk.
    func pictureFile(_ id: Data) throws -> URL? {
        try database.read { db in
            try String.fetchOne(db, sql: "SELECT file FROM pictures WHERE id = ?", arguments: [id])
        }.map { picturesFolder.appendingPathComponent($0) }
    }
}

// MARK: - The vault on disk

extension Vault {
    /// The vault header's file in [folder]. Present once a passphrase has been set.
    public static func headerFile(in folder: URL) -> URL { folder.appendingPathComponent("vault.bin") }

    /// Creates a new vault in [folder] for [passphrase], and writes its header.
    public static func create(in folder: URL, passphrase: String) throws -> Vault {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let vault = try create(passphrase: passphrase)
        try vault.header.encoded.write(to: headerFile(in: folder), options: .atomic)
        return vault
    }

    /// Unlocks the vault in [folder] with [passphrase].
    public static func unlock(in folder: URL, passphrase: String) throws -> Vault {
        try unlock(VaultHeader(decoding: Data(contentsOf: headerFile(in: folder))), passphrase: passphrase)
    }
}

extension Data {
    public var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
