import Foundation
import Testing
@testable import SyncedNotesCore

/// Deleting a note moves it to the Trash first, from where it can come back.
struct TrashTests {

    func store(_ passphrase: String = "made-up passphrase") throws -> NoteStore {
        let at = FileManager.default.temporaryDirectory.appendingPathComponent("trash-\(UUID())", isDirectory: true)
        return try NoteStore(folder: at, vault: Vault.create(in: at, passphrase: passphrase))
    }

    func note(_ text: String) -> StoredNote {
        StoredNote.with { $0.formatVersion = 1; $0.paragraphs = [StoredParagraph.with { $0.text = text }] }
    }

    func save(_ store: NoteStore, _ text: String, id: UInt8, at seconds: Double = 100) throws {
        try store.save(note(text), id: Data(repeating: id, count: 16), title: text, words: text, at: Date(timeIntervalSince1970: seconds))
    }

    let one = Data(repeating: 1, count: 16)

    @Test func aTrashedNoteLeavesTheListAndSearchButKeepsItsContent() throws {
        let mine = try store()
        try save(mine, "Door code 4812", id: 1)
        try mine.moveToTrash(one, at: Date(timeIntervalSince1970: 200))
        #expect(try mine.summaries().isEmpty)
        #expect(try mine.summaries(matching: "4812").isEmpty)
        #expect(try mine.trash().map(\.title) == ["Door code 4812"])
        #expect(try mine.trash().first?.trashed == Date(timeIntervalSince1970: 200))
        #expect(try mine.load(one) == note("Door code 4812"))
    }

    @Test func aNoteRestoredFromTheTrashComesBackAsItWas() throws {
        let mine = try store()
        try save(mine, "Door code 4812", id: 1, at: 100)
        try mine.moveToTrash(one)
        try mine.restoreFromTrash(one)
        #expect(try mine.trash().isEmpty)
        #expect(try mine.summaries().map(\.title) == ["Door code 4812"])
        #expect(try mine.summaries().first?.updated == Date(timeIntervalSince1970: 100))
        #expect(try mine.summaries(matching: "4812").count == 1)
    }

    @Test func deletingFromTheTrashDeletesForGoodLeavingOnlyTheMark() throws {
        let mine = try store()
        try save(mine, "Door code 4812", id: 1)
        try mine.moveToTrash(one)
        try mine.delete(one)
        #expect(try mine.trash().isEmpty && mine.summaries().isEmpty)
        #expect(try mine.isDeleted(one))
        #expect(throws: NoteStore.StoreError.noSuchNote) { try mine.load(one) }
    }

    @Test func theTrashEmptiesItselfOfNotesOlderThanThirtyDays() throws {
        let mine = try store()
        let day: TimeInterval = 24 * 60 * 60
        try save(mine, "Old", id: 1)
        try save(mine, "Recent", id: 2)
        try mine.moveToTrash(one, at: Date(timeIntervalSince1970: 0))
        try mine.moveToTrash(Data(repeating: 2, count: 16), at: Date(timeIntervalSince1970: 25 * day))
        let removed = try mine.emptyTrash(olderThan: NoteStore.trashKeepsFor, at: Date(timeIntervalSince1970: 31 * day))
        #expect(removed == 1)
        #expect(try mine.trash().map(\.title) == ["Recent"])
        #expect(try mine.isDeleted(one))
    }

    @Test func emptyingTheTrashDeletesEverythingInIt() throws {
        let mine = try store()
        try save(mine, "a", id: 1)
        try save(mine, "b", id: 2)
        try save(mine, "kept", id: 3)
        try mine.moveToTrash(one)
        try mine.moveToTrash(Data(repeating: 2, count: 16))
        #expect(try mine.emptyTrash() == 2)
        #expect(try mine.trash().isEmpty && mine.summaries().map(\.title) == ["kept"])
    }

    @Test func notesInTheTrashAreInTheBackupAndGoBackToTheTrash() throws {
        let mine = try store()
        try save(mine, "Kept", id: 1)
        try save(mine, "Trashed", id: 2)
        try mine.moveToTrash(Data(repeating: 2, count: 16), at: Date(timeIntervalSince1970: 300))
        let file = try Backup.make(from: mine)

        let fresh = try store("made-up passphrase 2")
        _ = try Backup.restore(Backup.open(file, with: Vault.unlock(Backup.vaultHeader(of: file), passphrase: "made-up passphrase")), into: fresh)
        #expect(try fresh.summaries().map(\.title) == ["Kept"])
        #expect(try fresh.trash().map(\.title) == ["Trashed"])
        #expect(try fresh.trash().first?.trashed == Date(timeIntervalSince1970: 300))
    }
}
