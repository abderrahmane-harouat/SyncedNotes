import Foundation
import GRDB
import Testing
@testable import SyncedNotesCore

/// The encrypted database, and what actually reaches the disk.
struct NoteStoreTests {

    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("notestore-\(UUID())", isDirectory: true)
    let passphrase = "made-up passphrase"

    func note(_ text: String) -> StoredNote {
        var paragraph = StoredParagraph()
        paragraph.text = text
        var note = StoredNote()
        note.formatVersion = 1
        note.paragraphs = [paragraph]
        return note
    }

    func newStore() throws -> NoteStore {
        try NoteStore(folder: folder, vault: Vault.create(in: folder, passphrase: passphrase))
    }

    /// Every file under the store's folder, read raw.
    func everythingOnDisk() throws -> [(URL, Data)] {
        let files = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: nil)!.compactMap { $0 as? URL }
        return try files.filter { !$0.hasDirectoryPath }.map { ($0, try Data(contentsOf: $0)) }
    }

    @Test func aSavedNoteComesBackExactlyAfterTheAppIsOpenedAgain() throws {
        let id = Data(repeating: 1, count: 16)
        try newStore().save(note("Door code 4812"), id: id, title: "Door code", words: "Door code 4812")
        let reopened = try NoteStore(folder: folder, vault: Vault.unlock(in: folder, passphrase: passphrase))
        #expect(try reopened.load(id) == note("Door code 4812"))
        #expect(try reopened.summaries().map(\.title) == ["Door code"])
    }

    @Test func nothingReadableReachesTheDiskNotEvenTheTitleOrTheSearchWords() throws {
        let store = try newStore()
        try store.save(note("zebra-password-9931"), id: Data(repeating: 2, count: 16), title: "giraffe-title", words: "zebra-password-9931")
        try store.storePicture(Data("PNG-looking-bytes-pelican".utf8), id: Data(repeating: 3, count: 16))
        for (file, bytes) in try everythingOnDisk() {
            for secret in ["zebra-password-9931", "giraffe-title", "pelican", "SQLite format 3"] {
                #expect(bytes.range(of: Data(secret.utf8)) == nil, "\(secret) is readable in \(file.lastPathComponent)")
            }
        }
    }

    @Test func anotherVaultsKeysCannotOpenTheDatabase() throws {
        _ = try newStore()
        let stranger = try Vault.create(passphrase: passphrase)
        #expect(throws: NoteStore.StoreError.cannotOpen) { try NoteStore(folder: folder, vault: stranger) }
    }

    @Test func theListIsNewestFirst() throws {
        let store = try newStore()
        try store.save(note("a"), id: Data(repeating: 4, count: 16), title: "older", words: "a", at: Date(timeIntervalSince1970: 100))
        try store.save(note("b"), id: Data(repeating: 5, count: 16), title: "newer", words: "b", at: Date(timeIntervalSince1970: 200))
        #expect(try store.summaries().map(\.title) == ["newer", "older"])
    }

    @Test func savingAgainReplacesTheNoteButKeepsWhenItWasCreated() throws {
        let store = try newStore()
        let id = Data(repeating: 6, count: 16)
        try store.save(note("first"), id: id, title: "first", words: "first", at: Date(timeIntervalSince1970: 100))
        try store.save(note("second"), id: id, title: "second", words: "second", at: Date(timeIntervalSince1970: 200))
        #expect(try store.load(id) == note("second"))
        #expect(try store.summaries().count == 1)
    }

    @Test func aDeletedNoteLeavesTheListButKeepsAMarkForTheOtherDevice() throws {
        let store = try newStore()
        let id = Data(repeating: 7, count: 16)
        try store.save(note("gone soon"), id: id, title: "gone soon", words: "gone soon")
        try store.delete(id)
        #expect(try store.summaries().isEmpty)
        #expect(try store.isDeleted(id))
        #expect(throws: NoteStore.StoreError.noSuchNote) { try store.load(id) }
        #expect(try store.summaries(matching: "gone").isEmpty)
    }

    @Test func searchFindsNotesByTheStartOfTheirWordsInAnyScript() throws {
        let store = try newStore()
        try store.save(note("x"), id: Data(repeating: 8, count: 16), title: "shopping", words: "Buy apples and pears")
        try store.save(note("y"), id: Data(repeating: 9, count: 16), title: "arabic", words: "ملاحظة عن التفاح")
        #expect(try store.summaries(matching: "app").map(\.title) == ["shopping"])
        #expect(try store.summaries(matching: "التفا").map(\.title) == ["arabic"])
        #expect(try store.summaries(matching: "bananas").isEmpty)
    }

    @Test func aPictureIsALockedFileWithARandomNameAndComesBackUnchanged() throws {
        let store = try newStore()
        let id = Data(repeating: 10, count: 16)
        let bytes = Data((0..<5000).map { UInt8($0 % 251) })
        try store.storePicture(bytes, id: id)
        #expect(try store.picture(id) == bytes)
        let file = try #require(try store.pictureFile(id))
        #expect(!file.lastPathComponent.contains(id.hexString))
        #expect(try Data(contentsOf: file).prefix(4) == Data("SNL1".utf8))
    }

    @Test func storingThePictureAgainKeepsTheOneFile() throws {
        let store = try newStore()
        let id = Data(repeating: 11, count: 16)
        try store.storePicture(Data([1, 2, 3]), id: id)
        try store.storePicture(Data([1, 2, 3]), id: id)
        #expect(try everythingOnDisk().filter { $0.0.path.contains("/pictures/") }.count == 1)
    }

    @Test func aDatabaseFromBeforeTheChangedTimeOpensWithEveryNoteAndGainsIt() throws {
        // What happens to the real notes on the first unlock after this change: the upgrade
        // must keep every note and give each a change time equal to its edit time.
        let store = try newStore()
        let id = Data(repeating: 20, count: 16)
        try store.save(note("Kept through the upgrade"), id: id, title: "Kept", words: "Kept", at: Date(timeIntervalSince1970: 100))

        // Turn it back into the older shape: no `changed` column, and the upgrade not yet run.
        var configuration = Configuration()
        let key = "x'\(store.vault.databaseKeyBytes.hexString)'"
        configuration.prepareDatabase { db in try db.usePassphrase(key) }
        let raw = try DatabaseQueue(path: folder.appendingPathComponent("notes.sqlite").path, configuration: configuration)
        try raw.write { db in
            try db.execute(sql: "ALTER TABLE notes DROP COLUMN changed")
            try db.execute(sql: "DELETE FROM grdb_migrations WHERE identifier LIKE 'v2%'")
        }
        try raw.close()

        let upgraded = try NoteStore(folder: folder, vault: Vault.unlock(in: folder, passphrase: passphrase))
        #expect(try upgraded.load(id) == note("Kept through the upgrade"))
        #expect(try upgraded.lastChanged(id) == Date(timeIntervalSince1970: 100))
        #expect(try upgraded.summaries().map(\.title) == ["Kept"])
    }
}
