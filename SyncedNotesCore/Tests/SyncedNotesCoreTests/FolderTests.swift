import Foundation
import GRDB
import Testing
@testable import SyncedNotesCore

/// Folders hold notes, and folders inside them.
struct FolderTests {

    let passphrase = "made-up passphrase"
    let place = FileManager.default.temporaryDirectory.appendingPathComponent("folders-\(UUID())", isDirectory: true)

    func store() throws -> NoteStore {
        try NoteStore(folder: place, vault: Vault.create(in: place, passphrase: passphrase))
    }

    func note(_ text: String) -> StoredNote {
        StoredNote.with { $0.formatVersion = 1; $0.paragraphs = [StoredParagraph.with { $0.text = text }] }
    }

    func id(_ byte: UInt8) -> Data { Data(repeating: byte, count: 16) }

    func save(_ store: NoteStore, _ text: String, id byte: UInt8, in folder: Data? = nil, at seconds: Double = 100) throws {
        try store.save(note(text), id: id(byte), title: text, words: text, at: Date(timeIntervalSince1970: seconds), folder: folder)
    }

    @Test func aFolderNameIsNotReadableOnDisk() throws {
        let mine = try store()
        let secret = try mine.createFolder(named: "okapi-folder-7720")
        try mine.createFolder(named: "Inside", in: secret)
        try mine.renameFolder(secret, to: "okapi-renamed-4410")
        let files = FileManager.default.enumerator(at: place, includingPropertiesForKeys: nil)!.compactMap { $0 as? URL }
        for file in files where !file.hasDirectoryPath {
            let bytes = try Data(contentsOf: file)
            for name in ["okapi-folder-7720", "okapi-renamed-4410"] {
                #expect(bytes.range(of: Data(name.utf8)) == nil, "\(name) is readable in \(file.lastPathComponent)")
            }
        }
    }

    @Test func aNewNoteGoesIntoTheFolderItWasMadeInAndStaysThereWhenSavedAgain() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        try save(mine, "Agenda", id: 1, in: work)
        try save(mine, "Agenda, edited", id: 1, at: 200)
        #expect(try mine.folder(of: id(1)) == work)
        #expect(try mine.summaries(in: .folder(work)).map(\.title) == ["Agenda, edited"])
    }

    @Test func eachListShowsItsOwnNotesAndAllNotesShowsEveryOne() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        let meetings = try mine.createFolder(named: "Meetings", in: work)
        try save(mine, "Loose", id: 1, at: 100)
        try save(mine, "Agenda", id: 2, in: work, at: 200)
        try save(mine, "Minutes", id: 3, in: meetings, at: 300)
        #expect(try mine.summaries(in: .noFolder).map(\.title) == ["Loose"])
        #expect(try mine.summaries(in: .folder(work)).map(\.title) == ["Agenda"])
        #expect(try mine.summaries(in: .folder(meetings)).map(\.title) == ["Minutes"])
        #expect(try mine.summaries().map(\.title) == ["Minutes", "Agenda", "Loose"])
        #expect(try mine.noteCounts() == [nil: 1, work: 1, meetings: 1])
    }

    @Test func searchFindsNotesInEveryFolderOrOnlyInOne() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        try save(mine, "Budget at home", id: 1)
        try save(mine, "Budget at work", id: 2, in: work)
        #expect(Set(try mine.summaries(matching: "budget").map(\.title)) == ["Budget at home", "Budget at work"])
        #expect(try mine.summaries(matching: "budget", in: .folder(work)).map(\.title) == ["Budget at work"])
    }

    @Test func foldersComeParentsFirstEachLevelInNameOrder() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        let home = try mine.createFolder(named: "home")
        try mine.createFolder(named: "Meetings", in: work)
        try mine.createFolder(named: "Ideas", in: work)
        try mine.createFolder(named: "Garden", in: home)
        #expect(try mine.folders().map(\.name) == ["home", "Garden", "Work", "Ideas", "Meetings"])
    }

    @Test func aNoteMovesBetweenFoldersAndOutOfThem() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        let home = try mine.createFolder(named: "Home")
        try save(mine, "Keys", id: 1, in: work)
        try mine.moveNote(id(1), to: home, at: Date(timeIntervalSince1970: 500))
        #expect(try mine.folder(of: id(1)) == home)
        #expect(try mine.lastChanged(id(1)) == Date(timeIntervalSince1970: 500))
        #expect(try mine.summaries().first?.updated == Date(timeIntervalSince1970: 100))
        try mine.moveNote(id(1), to: nil)
        #expect(try mine.summaries(in: .noFolder).map(\.title) == ["Keys"])
        #expect(throws: NoteStore.StoreError.noSuchFolder) { try mine.moveNote(id(1), to: id(99)) }
    }

    @Test func aFolderMovesWithEverythingInItButNeverIntoItself() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        let meetings = try mine.createFolder(named: "Meetings", in: work)
        let home = try mine.createFolder(named: "Home")
        try save(mine, "Minutes", id: 1, in: meetings)
        try mine.moveFolder(work, into: home)
        #expect(try mine.folders().map(\.name) == ["Home", "Work", "Meetings"])
        #expect(try mine.summaries(in: .folder(meetings)).map(\.title) == ["Minutes"])
        #expect(throws: NoteStore.StoreError.folderInsideItself) { try mine.moveFolder(work, into: work) }
        #expect(throws: NoteStore.StoreError.folderInsideItself) { try mine.moveFolder(home, into: meetings) }
        try mine.moveFolder(work, into: nil)
        #expect(try mine.folders().first { $0.id == work }?.parent == nil)
    }

    @Test func aFolderIsRenamed() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Wrok")
        try mine.renameFolder(work, to: "Work")
        #expect(try mine.folders().map(\.name) == ["Work"])
    }

    @Test func deletingAFolderSendsItsNotesAndThoseOfTheFoldersInsideToTheTrash() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        let meetings = try mine.createFolder(named: "Meetings", in: work)
        let home = try mine.createFolder(named: "Home")
        try save(mine, "Agenda", id: 1, in: work)
        try save(mine, "Minutes", id: 2, in: meetings)
        try save(mine, "Garden", id: 3, in: home)
        try save(mine, "Already trashed", id: 4, in: work)
        try mine.moveToTrash(id(4), at: Date(timeIntervalSince1970: 50))
        #expect(try mine.noteCount(deletingFolder: work) == 2)

        #expect(try mine.deleteFolder(work, at: Date(timeIntervalSince1970: 600)) == 2)
        #expect(try mine.folders().map(\.name) == ["Home"])
        #expect(try mine.summaries().map(\.title) == ["Garden"])
        #expect(Set(try mine.trash().map(\.title)) == ["Agenda", "Minutes", "Already trashed"])
        // Nothing is lost: what was trashed keeps its content, and its own time in the Trash.
        #expect(try mine.load(id(2)) == note("Minutes"))
        #expect(try mine.trash().first { $0.title == "Already trashed" }?.trashed == Date(timeIntervalSince1970: 50))
    }

    @Test func aNoteRestoredFromTheTrashGoesBackToItsFolderOrToNoneIfTheFolderIsGone() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        let home = try mine.createFolder(named: "Home")
        try save(mine, "Agenda", id: 1, in: work)
        try save(mine, "Garden", id: 2, in: home)
        try mine.moveToTrash(id(2))
        try mine.restoreFromTrash(id(2))
        #expect(try mine.folder(of: id(2)) == home)

        try mine.deleteFolder(work)
        try mine.restoreFromTrash(id(1))
        #expect(try mine.folder(of: id(1)) == nil)
        #expect(try mine.summaries(in: .noFolder).map(\.title) == ["Agenda"])
    }

    @Test func aBackupKeepsFoldersAndWhichNoteIsInWhich() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        let meetings = try mine.createFolder(named: "Meetings", in: work)
        try save(mine, "Agenda", id: 1, in: work)
        try save(mine, "Minutes", id: 2, in: meetings)
        try save(mine, "Loose", id: 3)
        let file = try Backup.make(from: mine)

        let elsewhere = FileManager.default.temporaryDirectory.appendingPathComponent("folders-\(UUID())", isDirectory: true)
        let theirs = try NoteStore(folder: elsewhere, vault: Vault.create(in: elsewhere, passphrase: "another made-up one"))
        let result = try Backup.restore(Backup.open(file, with: mine.vault), into: theirs)
        #expect(result.folders == 2)
        #expect(try theirs.folders() == mine.folders())
        #expect(try theirs.summaries(in: .folder(work)).map(\.title) == ["Agenda"])
        #expect(try theirs.summaries(in: .folder(meetings)).map(\.title) == ["Minutes"])
        #expect(try theirs.summaries(in: .noFolder).map(\.title) == ["Loose"])
    }

    @Test func restoringABackupLeavesAFolderThatIsAlreadyHereAsItIs() throws {
        let mine = try store()
        let work = try mine.createFolder(named: "Work")
        let file = try Backup.make(from: mine)
        try mine.renameFolder(work, to: "Work, renamed since")
        let result = try Backup.restore(Backup.open(file, with: mine.vault), into: mine)
        #expect(result.folders == 0)
        #expect(try mine.folders().map(\.name) == ["Work, renamed since"])
    }

    @Test func aBackupMadeBeforeFoldersRestoresEveryNoteIntoNoFolder() throws {
        let mine = try store()
        try save(mine, "Old note", id: 1)
        var old = try Backup.open(Backup.make(from: mine), with: mine.vault)
        old.folders = []
        for index in old.notes.indices { old.notes[index].folder = Data() }
        let elsewhere = FileManager.default.temporaryDirectory.appendingPathComponent("folders-\(UUID())", isDirectory: true)
        let theirs = try NoteStore(folder: elsewhere, vault: Vault.create(in: elsewhere, passphrase: passphrase))
        #expect(try Backup.restore(old, into: theirs).added == 1)
        #expect(try theirs.summaries(in: .noFolder).map(\.title) == ["Old note"])
    }

    @Test func aDatabaseFromBeforeFoldersOpensWithEveryNoteInNoFolder() throws {
        // What happens to the real notes on the first unlock after this change.
        let mine = try store()
        try save(mine, "Kept through the upgrade", id: 1)
        try mine.moveToTrash(id(1))
        try save(mine, "Also kept", id: 2)

        var configuration = Configuration()
        let key = "x'\(mine.vault.databaseKeyBytes.hexString)'"
        configuration.prepareDatabase { db in try db.usePassphrase(key) }
        let raw = try DatabaseQueue(path: place.appendingPathComponent("notes.sqlite").path, configuration: configuration)
        try raw.write { db in
            try db.execute(sql: "ALTER TABLE notes DROP COLUMN folder")
            try db.execute(sql: "DROP TABLE folders")
            try db.execute(sql: "DELETE FROM grdb_migrations WHERE identifier LIKE 'v4%'")
        }
        try raw.close()

        let upgraded = try NoteStore(folder: place, vault: Vault.unlock(in: place, passphrase: passphrase))
        #expect(try upgraded.summaries(in: .noFolder).map(\.title) == ["Also kept"])
        #expect(try upgraded.trash().map(\.title) == ["Kept through the upgrade"])
        #expect(try upgraded.folders().isEmpty)
        try upgraded.restoreFromTrash(id(1))
        #expect(try upgraded.load(id(1)) == note("Kept through the upgrade"))
    }
}
