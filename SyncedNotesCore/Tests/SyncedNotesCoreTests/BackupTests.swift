import Foundation
import Testing
@testable import SyncedNotesCore

/// Export and import: a whole vault's notes in one locked file, and back.
struct BackupTests {

    func folder() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("backup-\(UUID())", isDirectory: true) }

    func store(_ passphrase: String) throws -> NoteStore {
        let at = folder()
        return try NoteStore(folder: at, vault: Vault.create(in: at, passphrase: passphrase))
    }

    func note(_ text: String, picture: Data? = nil) -> StoredNote {
        var paragraph = StoredParagraph()
        paragraph.text = text
        if let picture {
            paragraph.text += "\u{FFFC}"
            paragraph.runs = [StoredRun.with {
                $0.start = UInt32((text as NSString).length)
                $0.length = 1
                $0.embedded = StoredEmbedded.with { $0.picture = StoredPicture.with { ($0.id, $0.type, $0.width, $0.height) = (picture, "public.png", 10, 10) } }
            }]
        }
        return StoredNote.with { $0.formatVersion = 1; $0.paragraphs = [paragraph] }
    }

    func save(_ store: NoteStore, _ text: String, id: UInt8, at seconds: Double, picture: Data? = nil) throws {
        let stored = note(text, picture: picture)
        try store.save(stored, id: Data(repeating: id, count: 16), title: stored.title, words: stored.words.joined(), at: Date(timeIntervalSince1970: seconds))
    }

    @Test func aBackupRestoresEveryNoteIntoAFreshVaultWithADifferentPassphrase() throws {
        let old = try store("old made-up passphrase")
        try save(old, "Door code 4812", id: 1, at: 100)
        try save(old, "Shopping", id: 2, at: 200)
        let file = try Backup.make(from: old)

        let fresh = try store("new made-up passphrase")
        let theirs = try Vault.unlock(Backup.vaultHeader(of: file), passphrase: "old made-up passphrase")
        let result = try Backup.restore(Backup.open(file, with: theirs), into: fresh)

        #expect(result.added == 2 && result.restored == 2 && result.keptNewer == 0)
        #expect(try fresh.summaries().map(\.title) == ["Shopping", "Door code 4812"])
        #expect(try fresh.load(Data(repeating: 1, count: 16)) == note("Door code 4812"))
        #expect(try fresh.summaries(matching: "4812").count == 1)
    }

    @Test func restoredNotesKeepTheirOwnDates() throws {
        let old = try store("made-up passphrase")
        try save(old, "Written long ago", id: 1, at: 1_000)
        let fresh = try store("made-up passphrase 2")
        let file = try Backup.make(from: old)
        _ = try Backup.restore(Backup.open(file, with: Vault.unlock(Backup.vaultHeader(of: file), passphrase: "made-up passphrase")), into: fresh)
        #expect(try fresh.summaries().first?.updated == Date(timeIntervalSince1970: 1_000))
    }

    @Test func theWrongPassphraseForTheBackupIsRefused() throws {
        let file = try Backup.make(from: store("made-up passphrase"))
        #expect(throws: LockError.wrongPassphrase) {
            try Vault.unlock(Backup.vaultHeader(of: file), passphrase: "not the passphrase")
        }
    }

    @Test func aBackupFromThisVaultOpensWithTheKeysAlreadyInMemory() throws {
        let mine = try store("made-up passphrase")
        try save(mine, "Here", id: 1, at: 100)
        let file = try Backup.make(from: mine)
        #expect(try Backup.vaultHeader(of: file).id == mine.vault.header.id)
        #expect(try Backup.open(file, with: mine.vault).notes.count == 1)
    }

    @Test func aChangedByteAnywhereMakesTheWholeBackupRefused() throws {
        let mine = try store("made-up passphrase")
        try save(mine, "Here", id: 1, at: 100)
        var file = try Backup.make(from: mine)
        file[file.count - 30] ^= 1
        #expect(throws: LockError.damaged) { try Backup.open(file, with: mine.vault) }
    }

    @Test func somethingThatIsNotABackupIsSaidToBeSo() throws {
        #expect(throws: Backup.BackupError.notABackup) { try Backup.vaultHeader(of: Data("hello, not a backup at all".utf8)) }
    }

    @Test func nothingInTheBackupFileIsReadable() throws {
        let mine = try store("made-up passphrase")
        let picture = Data("pelican-picture-bytes".utf8)
        try mine.storePicture(picture, id: Data(repeating: 9, count: 16))
        try save(mine, "zebra-secret-9931", id: 1, at: 100, picture: Data(repeating: 9, count: 16))
        let file = try Backup.make(from: mine)
        for secret in ["zebra-secret-9931", "pelican-picture-bytes", "public.png"] {
            #expect(file.range(of: Data(secret.utf8)) == nil, "\(secret) is readable in the backup")
        }
    }

    @Test func picturesTravelInTheBackupAndComeBackAsTheirVeryBytes() throws {
        let old = try store("made-up passphrase")
        let picture = Data((0..<3000).map { UInt8($0 % 251) })
        let pictureID = Data(repeating: 9, count: 16)
        try old.storePicture(picture, id: pictureID)
        try save(old, "With a picture", id: 1, at: 100, picture: pictureID)
        let fresh = try store("made-up passphrase 2")
        let file = try Backup.make(from: old)
        let result = try Backup.restore(Backup.open(file, with: Vault.unlock(Backup.vaultHeader(of: file), passphrase: "made-up passphrase")), into: fresh)
        #expect(result.pictures == 1)
        #expect(try fresh.picture(pictureID) == picture)
    }

    @Test func anOlderBackupNeverOverwritesNewerWriting() throws {
        let mine = try store("made-up passphrase")
        try save(mine, "Version from Monday", id: 1, at: 100)
        let monday = try Backup.make(from: mine)
        try save(mine, "Version from Tuesday", id: 1, at: 200)
        let result = try Backup.restore(Backup.open(monday, with: mine.vault), into: mine)
        #expect(result.restored == 0 && result.keptNewer == 1)
        #expect(try mine.load(Data(repeating: 1, count: 16)) == note("Version from Tuesday"))
    }

    @Test func aNewerBackupBringsANoteUpToDate() throws {
        let laptop = try store("made-up passphrase")
        try save(laptop, "Old", id: 1, at: 100)
        let other = try store("made-up passphrase 2")
        try save(other, "Old", id: 1, at: 100)
        try save(laptop, "Newer", id: 1, at: 300)
        let file = try Backup.make(from: laptop)
        let result = try Backup.restore(Backup.open(file, with: laptop.vault), into: other)
        #expect(result.updated == 1 && result.restored == 1)
        #expect(try other.load(Data(repeating: 1, count: 16)) == note("Newer"))
    }

    @Test func importingABackupBringsBackNotesDeletedAfterItWasMade() throws {
        // What a person does: export, delete everything, import, and expect the notes back.
        let mine = try store("made-up passphrase")
        try save(mine, "First", id: 1, at: 100)
        try save(mine, "Second", id: 2, at: 110)
        try save(mine, "Third", id: 3, at: 120)
        let file = try Backup.make(from: mine)
        for id: UInt8 in [1, 2, 3] { try mine.delete(Data(repeating: id, count: 16), at: Date(timeIntervalSince1970: 200)) }
        #expect(try mine.summaries().isEmpty)

        let result = try Backup.restore(Backup.open(file, with: mine.vault), into: mine, at: Date(timeIntervalSince1970: 300))
        #expect(result.undeleted == 3 && result.restored == 3 && result.keptNewer == 0)
        #expect(Set(try mine.summaries().map(\.title)) == ["First", "Second", "Third"])
        #expect(try mine.load(Data(repeating: 2, count: 16)) == note("Second"))
    }

    @Test func restoredNotesShowWhenTheyWereLastEditedNotWhenTheyWereRestored() throws {
        let mine = try store("made-up passphrase")
        try save(mine, "Oldest", id: 1, at: 100)
        try save(mine, "Middle", id: 2, at: 200)
        try save(mine, "Newest", id: 3, at: 300)
        let file = try Backup.make(from: mine)
        for id: UInt8 in [1, 2, 3] { try mine.delete(Data(repeating: id, count: 16), at: Date(timeIntervalSince1970: 400)) }
        _ = try Backup.restore(Backup.open(file, with: mine.vault), into: mine, at: Date(timeIntervalSince1970: 500))
        let list = try mine.summaries()
        #expect(list.map(\.title) == ["Newest", "Middle", "Oldest"])
        #expect(list.map(\.updated) == [300, 200, 100].map(Date.init(timeIntervalSince1970:)))
    }

    @Test func aNoteBroughtBackFromDeletionStillCountsAsChangedAfterTheDeletion() throws {
        // So the other device, which also saw the deletion, does not delete it again on sync.
        let mine = try store("made-up passphrase")
        try save(mine, "Brought back", id: 1, at: 100)
        let file = try Backup.make(from: mine)
        try mine.delete(Data(repeating: 1, count: 16), at: Date(timeIntervalSince1970: 200))
        _ = try Backup.restore(Backup.open(file, with: mine.vault), into: mine, at: Date(timeIntervalSince1970: 300))
        #expect(try mine.lastChanged(Data(repeating: 1, count: 16)) == Date(timeIntervalSince1970: 300))
        #expect(try mine.lastEdited(Data(repeating: 1, count: 16)) == Date(timeIntervalSince1970: 100))
    }

    @Test func importingAgainSetsBackTheDatesOfNotesAnEarlierImportRedated() throws {
        // The state the first version of restoring left behind: the right content, dated at
        // the import. Importing the same backup again repairs the dates and changes nothing else.
        let mine = try store("made-up passphrase")
        try save(mine, "Same words", id: 1, at: 100)
        let file = try Backup.make(from: mine)
        try save(mine, "Same words", id: 1, at: 900)
        let result = try Backup.restore(Backup.open(file, with: mine.vault), into: mine, at: Date(timeIntervalSince1970: 1000))
        #expect(result.unchanged == 1 && result.datesSetBack == 1 && result.restored == 0)
        #expect(try mine.lastEdited(Data(repeating: 1, count: 16)) == Date(timeIntervalSince1970: 100))
        #expect(try mine.load(Data(repeating: 1, count: 16)) == note("Same words"))
    }

    @Test func aNewNoteMadeAfterTheBackupIsLeftAlone() throws {
        let mine = try store("made-up passphrase")
        try save(mine, "In the backup", id: 1, at: 100)
        let file = try Backup.make(from: mine)
        try save(mine, "Written later", id: 2, at: 200)
        _ = try Backup.restore(Backup.open(file, with: mine.vault), into: mine)
        #expect(Set(try mine.summaries().map(\.title)) == ["In the backup", "Written later"])
    }

    @Test func deletedNotesAreNotInTheBackup() throws {
        let mine = try store("made-up passphrase")
        try save(mine, "Kept", id: 1, at: 100)
        try save(mine, "Thrown away", id: 2, at: 100)
        try mine.delete(Data(repeating: 2, count: 16))
        #expect(try Backup.open(Backup.make(from: mine), with: mine.vault).notes.map(\.note.title) == ["Kept"])
    }
}
