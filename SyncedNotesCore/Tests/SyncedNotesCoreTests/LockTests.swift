import Foundation
import Testing
@_spi(TestFiles) @testable import SyncedNotesCore

/// The lock, as Docs/LockFormat.md describes it.
struct LockTests {

    let passphrase = "made-up café passphrase"

    @Test func aLockedNoteOpensToExactlyWhatWasLocked() throws {
        let vault = try Vault.create(passphrase: passphrase)
        let content = Data("A made-up note.".utf8)
        let id = Data(repeating: 7, count: 16)
        let opened = try vault.open(vault.lock(content, kind: .note, id: id))
        #expect(opened == LockedItem(kind: .note, id: id, content: content))
    }

    @Test func theNoteTextIsNotInTheLockedBytes() throws {
        let vault = try Vault.create(passphrase: passphrase)
        let locked = try vault.lock(Data("secret words".utf8), kind: .note, id: Data(count: 16))
        #expect(locked.range(of: Data("secret words".utf8)) == nil)
    }

    @Test func theSamePassphraseUnlocksTheVaultAgainFromItsHeader() throws {
        let vault = try Vault.create(passphrase: passphrase)
        let header = try VaultHeader(decoding: vault.header.encoded)
        let again = try Vault.unlock(header, passphrase: passphrase)
        let item = try vault.lock(Data("hello".utf8), kind: .note, id: Data(count: 16))
        #expect(try again.open(item).content == Data("hello".utf8))
    }

    @Test func aWrongPassphraseIsRefusedBeforeAnythingIsOpened() throws {
        let vault = try Vault.create(passphrase: passphrase)
        #expect(throws: LockError.wrongPassphrase) {
            try Vault.unlock(vault.header, passphrase: "made-up cafe passphrase")
        }
    }

    @Test func thePassphraseTypedWithADecomposedAccentStillUnlocks() throws {
        let vault = try Vault.create(passphrase: passphrase)
        let decomposed = passphrase.decomposedStringWithCanonicalMapping
        #expect(Array(decomposed.utf8) != Array(passphrase.utf8))
        #expect(throws: Never.self) { try Vault.unlock(vault.header, passphrase: decomposed) }
    }

    @Test func aChangedByteMakesTheWholeItemRefused() throws {
        let vault = try Vault.create(passphrase: passphrase)
        var locked = try vault.lock(Data("hello there".utf8), kind: .note, id: Data(count: 16))
        locked[locked.count - 1] ^= 1
        #expect(throws: LockError.damaged) { try vault.open(locked) }
    }

    @Test func aNoteCannotBePassedOffUnderAnotherID() throws {
        let vault = try Vault.create(passphrase: passphrase)
        var locked = try vault.lock(Data("hello".utf8), kind: .note, id: Data(count: 16))
        locked[22] ^= 1
        #expect(throws: LockError.damaged) { try vault.open(locked) }
    }

    @Test func aPictureIsNotOpenedWhereANoteIsExpected() throws {
        let vault = try Vault.create(passphrase: passphrase)
        let picture = try vault.lock(Data([1, 2, 3]), kind: .picture, id: Data(count: 16))
        #expect(throws: LockError.wrongKind(expected: .note, found: .picture)) { try vault.open(picture, expecting: .note) }
    }

    @Test func anItemFromAnotherVaultIsRefusedEvenWithTheSamePassphrase() throws {
        let mine = try Vault.create(passphrase: passphrase)
        let theirs = try Vault.create(passphrase: passphrase)
        let item = try theirs.lock(Data("hello".utf8), kind: .note, id: Data(count: 16))
        #expect(throws: LockError.wrongVault) { try mine.open(item) }
    }

    @Test func lockingTheSameNoteTwiceGivesDifferentBytes() throws {
        let vault = try Vault.create(passphrase: passphrase)
        let a = try vault.lock(Data("same".utf8), kind: .note, id: Data(count: 16))
        let b = try vault.lock(Data("same".utf8), kind: .note, id: Data(count: 16))
        #expect(a != b)
    }

    @Test func theVaultHeaderIsNinetyThreeBytesLaidOutAsDocumented() throws {
        let header = try Vault.create(passphrase: passphrase).header
        let bytes = header.encoded
        #expect(bytes.count == 93)
        #expect(bytes.prefix(4) == Data("SNV1".utf8) && bytes[4] == 1)
        #expect(bytes.littleEndianUInt32(at: 21) == 19_456)
        #expect(bytes.littleEndianUInt32(at: 25) == 2 && bytes.littleEndianUInt32(at: 29) == 1)
    }

    @Test func theCommittedTestFilesAreExactlyWhatTheGeneratorWrites() throws {
        let here = URL(fileURLWithPath: #filePath)
        let package = here.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let committed = package.appendingPathComponent("Fixtures/lock-v1")
        let fresh = FileManager.default.temporaryDirectory.appendingPathComponent("lock-v1-\(UUID())")
        try TestFiles.write(to: fresh)
        let names = try FileManager.default.contentsOfDirectory(atPath: fresh.path).sorted()
        #expect(names == (try FileManager.default.contentsOfDirectory(atPath: committed.path).filter { $0 != "README.md" }.sorted()))
        for name in names {
            let same = try Data(contentsOf: fresh.appendingPathComponent(name)) == Data(contentsOf: committed.appendingPathComponent(name))
            #expect(same, "\(name) differs from what the generator writes")
        }
    }
}
