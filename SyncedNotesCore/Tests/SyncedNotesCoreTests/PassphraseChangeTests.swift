import Foundation
import Testing
@testable import SyncedNotesCore

/// Changing the passphrase: everything comes through, only the new passphrase opens it, and
/// stopping at any point leaves notes that open with one of the two.
struct PassphraseChangeTests {

    let parent = FileManager.default.temporaryDirectory.appendingPathComponent("change-\(UUID())", isDirectory: true)
    var home: URL { parent.appendingPathComponent("SyncedNotes", isDirectory: true) }
    let old = "made-up old passphrase"
    let new = "made-up new passphrase"

    func note(_ text: String, picture: Data? = nil) -> StoredNote {
        StoredNote.with {
            $0.formatVersion = 1
            $0.paragraphs = [StoredParagraph.with { $0.text = text }]
            if let picture {
                $0.paragraphs.append(StoredParagraph.with {
                    $0.text = "\u{FFFC}"
                    $0.runs = [StoredRun.with { $0.length = 1; $0.embedded.picture = StoredPicture.with { $0.id = picture; $0.type = "public.png" } }]
                })
            }
        }
    }

    func id(_ byte: UInt8) -> Data { Data(repeating: byte, count: 16) }

    /// A store with a bit of everything: a folder, a picture, the Trash, a deleted note.
    func filledStore() throws -> NoteStore {
        let store = try NoteStore(folder: home, vault: Vault.create(in: home, passphrase: old))
        let work = try store.createFolder(named: "Work")
        try store.storePicture(Data("picture-bytes-heron".utf8), id: id(9))
        try store.save(note("Door code 4812", picture: id(9)), id: id(1), title: "Door code 4812", words: "Door code 4812", folder: work)
        try store.save(note("Shopping"), id: id(2), title: "Shopping", words: "Shopping")
        try store.save(note("Old idea"), id: id(3), title: "Old idea", words: "Old idea")
        try store.moveToTrash(id(3))
        try store.save(note("Gone"), id: id(4), title: "Gone", words: "Gone")
        try store.delete(id(4))
        return store
    }

    func change(_ store: NoteStore) throws {
        _ = try PassphraseChange.prepare(store: store, home: home, current: old, new: new)
        try store.close()
        try PassphraseChange.swap(home: home)
    }

    func leftovers() throws -> [String] {
        try FileManager.default.contentsOfDirectory(atPath: parent.path).filter { $0 != "SyncedNotes" }
    }

    @Test func afterAChangeOnlyTheNewPassphraseOpensTheNotesAndEverythingCameThrough() throws {
        let store = try filledStore()
        let before = (folders: try store.folders(), list: try store.summaries(), trash: try store.trash())
        try change(store)

        #expect(throws: LockError.wrongPassphrase) { try Vault.unlock(in: home, passphrase: old) }
        let reopened = try NoteStore(folder: home, vault: Vault.unlock(in: home, passphrase: new))
        #expect(try reopened.folders() == before.folders)
        #expect(try reopened.summaries() == before.list)
        #expect(try reopened.trash() == before.trash)
        #expect(try reopened.load(id(1)) == note("Door code 4812", picture: id(9)))
        #expect(try reopened.picture(id(9)) == Data("picture-bytes-heron".utf8))
        #expect(try reopened.isDeleted(id(4)))
        #expect(try reopened.summaries(matching: "4812").map(\.title) == ["Door code 4812"])
        #expect(try leftovers().isEmpty)
    }

    @Test func theNewVaultHasANewIDSoOlderBackupsAskForTheOldPassphrase() throws {
        let store = try filledStore()
        let oldID = store.vault.header.id
        let backup = try Backup.make(from: store)
        try change(store)
        let vault = try Vault.unlock(in: home, passphrase: new)
        #expect(vault.header.id != oldID)
        #expect(throws: LockError.wrongVault) { try Backup.open(backup, with: vault) }
        let theirs = try Vault.unlock(Backup.vaultHeader(of: backup), passphrase: old)
        #expect(try Backup.open(backup, with: theirs).notes.count == 3)
    }

    @Test func theWrongCurrentPassphraseChangesNothing() throws {
        let store = try filledStore()
        #expect(throws: LockError.wrongPassphrase) {
            try PassphraseChange.prepare(store: store, home: home, current: "not it at all", new: new)
        }
        #expect(try leftovers().isEmpty)
        #expect(try Vault.unlock(in: home, passphrase: old).header == store.vault.header)
        #expect(try store.load(id(2)) == note("Shopping"))
    }

    @Test func nothingReadableReachesTheDiskDuringOrAfterTheChange() throws {
        let store = try filledStore()
        _ = try PassphraseChange.prepare(store: store, home: home, current: old, new: new)
        func check() throws {
            for file in FileManager.default.enumerator(at: parent, includingPropertiesForKeys: nil)!.compactMap({ $0 as? URL }) where !file.hasDirectoryPath {
                let bytes = try Data(contentsOf: file)
                for secret in ["Door code 4812", "Shopping", "heron", "Work", "SQLite format 3"] {
                    #expect(bytes.range(of: Data(secret.utf8)) == nil, "\(secret) is readable in \(file.lastPathComponent)")
                }
            }
        }
        try check()
        try store.close()
        try PassphraseChange.swap(home: home)
        try check()
    }

    // MARK: - Stopped part way

    @Test func stoppedBeforeTheCopyWasCheckedTheOldPassphraseStillOpensEverything() throws {
        let store = try filledStore()
        _ = try PassphraseChange.prepare(store: store, home: home, current: old, new: new)
        try store.close()
        // As if the app had stopped while still writing the copy.
        try FileManager.default.removeItem(at: PassphraseChange.checkedMark(in: PassphraseChange.staging(for: home)))

        #expect(try PassphraseChange.finishInterruptedChange(home: home))
        #expect(try leftovers().isEmpty)
        let reopened = try NoteStore(folder: home, vault: Vault.unlock(in: home, passphrase: old))
        #expect(try reopened.load(id(1)) == note("Door code 4812", picture: id(9)))
    }

    @Test func stoppedAfterTheCopyWasCheckedTheChangeIsFinishedAtTheNextLaunch() throws {
        let store = try filledStore()
        _ = try PassphraseChange.prepare(store: store, home: home, current: old, new: new)
        try store.close()

        #expect(try PassphraseChange.finishInterruptedChange(home: home))
        #expect(try leftovers().isEmpty)
        let reopened = try NoteStore(folder: home, vault: Vault.unlock(in: home, passphrase: new))
        #expect(try reopened.load(id(2)) == note("Shopping"))
        #expect(throws: LockError.wrongPassphrase) { try Vault.unlock(in: home, passphrase: old) }
    }

    @Test func stoppedBetweenTheTwoRenamesTheChangeIsFinishedAtTheNextLaunch() throws {
        let store = try filledStore()
        _ = try PassphraseChange.prepare(store: store, home: home, current: old, new: new)
        try store.close()
        try FileManager.default.moveItem(at: home, to: PassphraseChange.replaced(for: home))

        #expect(try PassphraseChange.finishInterruptedChange(home: home))
        #expect(try leftovers().isEmpty)
        #expect(try NoteStore(folder: home, vault: Vault.unlock(in: home, passphrase: new)).load(id(2)) == note("Shopping"))
    }

    @Test func stoppedBeforeTheOldNotesWereDeletedTheyAreDeletedAtTheNextLaunch() throws {
        let store = try filledStore()
        _ = try PassphraseChange.prepare(store: store, home: home, current: old, new: new)
        try store.close()
        try FileManager.default.moveItem(at: home, to: PassphraseChange.replaced(for: home))
        try FileManager.default.moveItem(at: PassphraseChange.staging(for: home), to: home)

        #expect(try PassphraseChange.finishInterruptedChange(home: home))
        #expect(try leftovers().isEmpty)
        #expect(!FileManager.default.fileExists(atPath: PassphraseChange.checkedMark(in: home).path))
        #expect(try NoteStore(folder: home, vault: Vault.unlock(in: home, passphrase: new)).load(id(2)) == note("Shopping"))
    }

    @Test func aLaunchWithNoChangeUnderwayTouchesNothing() throws {
        let store = try filledStore()
        try store.close()
        #expect(try !PassphraseChange.finishInterruptedChange(home: home))
        #expect(try NoteStore(folder: home, vault: Vault.unlock(in: home, passphrase: old)).load(id(2)) == note("Shopping"))
    }
}
