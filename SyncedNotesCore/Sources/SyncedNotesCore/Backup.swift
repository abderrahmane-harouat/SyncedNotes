import Foundation

/// A backup: every note and its pictures in one file, locked under the vault it came from.
///
/// The file (LockFormat.md, "A backup file") is the vault's header followed by one locked item
/// of kind `backup`, holding a Protobuf `Backup`. Sealed as one, so nothing inside can be read
/// without the passphrase (not even how many notes there are or when they were written), and
/// changing any byte makes the whole file refuse to open.
public enum Backup {

    static let magic = Data("SNB1".utf8)
    static let version: UInt8 = 1

    public enum BackupError: Error, Equatable {
        case notABackup
        case unsupportedVersion(UInt8)
    }

    /// What an import did.
    public struct Result: Equatable, Sendable {
        /// Notes this store had never had.
        public var added = 0
        /// Notes deleted here, brought back.
        public var undeleted = 0
        /// Notes here that the backup had a newer copy of.
        public var updated = 0
        /// Notes here that were changed after the backup was made, kept as they are.
        public var keptNewer = 0
        /// Notes here exactly as in the backup.
        public var unchanged = 0
        /// Of those, notes whose date had been moved to a later import, set back to the
        /// backup's.
        public var datesSetBack = 0
        public var pictures = 0
        /// Folders this store did not have.
        public var folders = 0

        public var restored: Int { added + undeleted + updated }

        public init() {}
    }

    // MARK: - Making one

    /// Every note in [store] that is not deleted, those in the Trash included, with the
    /// pictures they show.
    public static func make(from store: NoteStore) throws -> Data {
        var backup = StoredBackup()
        backup.formatVersion = 1
        backup.folders = try store.folders().map { folder in
            StoredBackupFolder.with {
                $0.id = folder.id
                $0.name = folder.name
                $0.parent = folder.parent ?? Data()
                $0.created = folder.created.timeIntervalSince1970
            }
        }
        var pictureIDs: [Data] = []
        for entry in try store.everyNote() {
            let note = try store.load(entry.id)
            backup.notes.append(StoredBackupNote.with {
                $0.id = entry.id
                $0.created = entry.created.timeIntervalSince1970
                $0.updated = entry.updated.timeIntervalSince1970
                $0.trashed = entry.trashed?.timeIntervalSince1970 ?? 0
                $0.folder = entry.folder ?? Data()
                $0.note = note
            })
            pictureIDs += note.pictureIDs
        }
        var seen = Set<Data>()
        for id in pictureIDs where seen.insert(id).inserted {
            // A picture whose file is gone cannot be backed up; the note still points to it,
            // as it does here, so nothing more is lost than already was.
            guard let bytes = try? store.picture(id) else { continue }
            backup.pictures.append(StoredBackupPicture.with { ($0.id, $0.bytes) = (id, bytes) })
        }
        let locked = try store.vault.lock(backup.serializedData(), kind: .backup, id: systemRandom(16))
        var file = magic
        file.append(version)
        file.append(store.vault.header.encoded)
        file.append(locked)
        return file
    }

    // MARK: - Opening one

    /// The header of the vault a backup was made in: to tell whether it is this vault, whose
    /// keys are already in memory, or another, whose passphrase has to be asked for.
    public static func vaultHeader(of file: Data) throws -> VaultHeader {
        let file = Data(file)
        guard file.count > 5 + VaultHeader.size, file.prefix(4) == magic else { throw BackupError.notABackup }
        guard file[4] == version else { throw BackupError.unsupportedVersion(file[4]) }
        return try VaultHeader(decoding: file[5..<(5 + VaultHeader.size)])
    }

    /// Opens a backup with the keys of the vault it was made in.
    public static func open(_ file: Data, with vault: Vault) throws -> StoredBackup {
        let header = try vaultHeader(of: file)
        guard header.id == vault.header.id else { throw LockError.wrongVault }
        let item = try vault.open(Data(file.dropFirst(5 + VaultHeader.size)), expecting: .backup)
        return try StoredBackup(serializedBytes: item.content)
    }

    // MARK: - Restoring one

    /// Brings a backup's notes and pictures into [store], relocked under its own vault.
    ///
    /// Importing a backup is asking for its notes back, so a note deleted here comes back,
    /// however long after the backup it was deleted. (An earlier version let the deletion win
    /// as the newer change, as sync does, and an import after deleting brought nothing back.)
    /// Writing is still never lost: a note here that was changed after the backup was made
    /// keeps its newer version.
    ///
    /// Every note keeps the backup's dates, so the list shows when it was really last edited,
    /// and sorts it there. What moves to now is only the note's `changed` time, which sync
    /// uses: the other device's record of a deletion is newer than the backup, and without it
    /// would delete the note again.
    public static func restore(_ backup: StoredBackup, into store: NoteStore, at now: Date = Date()) throws -> Result {
        var result = Result()
        // Parents come before the folders inside them, so each finds its parent here.
        for folder in backup.folders {
            if try store.restoreFolder(
                folder.id, name: folder.name, parent: folder.parent.isEmpty ? nil : folder.parent,
                created: Date(timeIntervalSince1970: folder.created), at: now
            ) { result.folders += 1 }
        }
        for picture in backup.pictures {
            try store.storePicture(picture.bytes, id: picture.id)
            result.pictures += 1
        }
        for entry in backup.notes {
            let edited = Date(timeIntervalSince1970: entry.updated)
            if let here = try store.lastEdited(entry.id) {
                if try store.isDeleted(entry.id) {
                    result.undeleted += 1
                } else if try store.load(entry.id) == entry.note {
                    // Already here, word for word. An earlier import dated restored notes with
                    // the time of the import; set such a date back to the note's own.
                    result.unchanged += 1
                    if here > edited {
                        try store.setLastEdited(entry.id, to: edited)
                        result.datesSetBack += 1
                    }
                    continue
                } else if here >= edited {
                    result.keptNewer += 1
                    continue
                } else {
                    result.updated += 1
                }
            } else {
                result.added += 1
            }
            let changed = max(now, try store.lastChanged(entry.id) ?? now)
            try store.save(
                entry.note, id: entry.id, title: entry.note.title, words: entry.note.words.joined(separator: "\n"),
                at: edited, created: Date(timeIntervalSince1970: entry.created), changed: changed
            )
            // Back where it was: a note that was in the Trash when the backup was made goes
            // back to the Trash, where it can still be restored, rather than being dropped.
            try store.setTrashed(entry.id, at: entry.trashed > 0 ? Date(timeIntervalSince1970: entry.trashed) : nil)
            // And in the folder it was in. A note kept as it is here stays in its folder here.
            try store.setFolder(entry.id, to: entry.folder.isEmpty ? nil : entry.folder)
        }
        return result
    }
}
