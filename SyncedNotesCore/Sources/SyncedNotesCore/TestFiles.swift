import CryptoKit
import Foundation

// The lock's test files: made-up items locked under a made-up passphrase, and a manifest
// saying exactly what each holds or why it must be refused: a reader written from
// Docs/LockFormat.md alone is checked against them. Everything random comes from a fixed
// sequence, so writing them again gives the very
// same bytes: the committed copies can always be checked against this, and are never edited
// by hand. Used by the make-fixtures tool and by the test that compares the two.

@_spi(TestFiles) public enum TestFiles {

    /// Writes the vault, the items and the manifest into [directory]; returns how many items.
    @discardableResult
    public static func write(to directory: URL) throws -> Int {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let fixed = FixedSequence()
        let random: RandomBytes = { fixed.bytes($0) }

        // Made up, and guards nothing. The é tests that the passphrase is normalised to NFC.
        let passphrase = "made-up café passphrase"
        let vault = try Vault.create(passphrase: passphrase, random: random)
        try vault.header.encoded.write(to: directory.appendingPathComponent("vault.bin"))

        var items: [Manifest.Item] = []

        func write(_ name: String, _ bytes: Data) throws {
            try bytes.write(to: directory.appendingPathComponent(name))
        }

        /// Locks [content], writes it, and records that it must open to exactly that.
        @discardableResult
        func good(_ name: String, _ description: String, _ kind: ItemKind, _ content: Data, text: Bool) throws -> Data {
            let id = random(16)
            let locked = try vault.lock(content, kind: kind, id: id, random: random)
            try write(name, locked)
            items.append(.init(
                file: name, description: description, kind: kind == .note ? "note" : "picture", id: id.hex, opens: true,
                contentSha256: Data(SHA256.hash(data: content)).hex, contentLength: content.count,
                contentUtf8: text ? String(decoding: content, as: UTF8.self) : nil
            ))
            return locked
        }

        func bad(_ name: String, _ description: String, _ bytes: Data, refusedAs reason: String) throws {
            try write(name, bytes)
            items.append(.init(file: name, description: description, opens: false, refusedAs: reason))
        }

        let plain = try good(
            "note-plain.snl", "A short note in English.", .note,
            Data("A made-up note, to prove another reader can open what the Mac locked.".utf8), text: true
        )
        try good(
            "note-scripts.snl", "Arabic, an emoji, and accented letters, to check UTF-8 survives the trip.", .note,
            Data("ملاحظة مختلقة، made up: 🗒️ café, naïve.".utf8), text: true
        )
        try good("note-empty.snl", "A note with no content at all.", .note, Data(), text: true)
        try good(
            "note-long.snl", "A long note, 2,000 lines.", .note,
            // Checked by its SHA-256 and length; spelled out, it would bury the manifest.
            Data((1...2000).map { "Line \($0) of a long made-up note.\n" }.joined().utf8), text: false
        )
        // The smallest valid PNG: one red pixel.
        let pixel = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")!
        try good("picture-pixel.snl", "A picture: a one-pixel PNG, its bytes unchanged.", .picture, pixel, text: false)

        var flipped = plain
        flipped[flipped.count - 20] ^= 0x01
        try bad("refuse-tampered.snl", "note-plain.snl with one bit of its locked content changed.", flipped, refusedAs: "damaged")

        var renamed = plain
        renamed[22] ^= 0x01
        try bad(
            "refuse-renamed.snl", "note-plain.snl with its item ID changed: the label is sealed to the content.",
            renamed, refusedAs: "damaged"
        )
        try bad("refuse-truncated.snl", "note-plain.snl with its last 5 bytes cut off.", plain.dropLast(5), refusedAs: "damaged")

        let otherVault = try Vault.create(passphrase: passphrase, random: random)
        let stranger = try otherVault.lock(Data("From a different vault.".utf8), kind: .note, id: random(16), random: random)
        try bad(
            "refuse-other-vault.snl", "A note locked in a different vault, with the same passphrase.",
            stranger, refusedAs: "wrongVault"
        )

        let manifest = Manifest(
            about: "Made up by SyncedNotesCore (swift run make-fixtures). Guards nothing. Format: Docs/LockFormat.md.",
            passphrase: passphrase,
            passphraseDecomposed: passphrase.decomposedStringWithCanonicalMapping,
            wrongPassphrase: "made-up cafe passphrase",
            vault: .init(
                file: "vault.bin",
                id: vault.header.id.hex,
                memoryKiB: vault.header.settings.memoryKiB,
                passes: vault.header.settings.passes,
                lanes: vault.header.settings.lanes,
                salt: vault.header.salt.hex,
                keys: Dictionary(uniqueKeysWithValues: vault.keysForTestFiles.map { ($0.name, $0.bytes.hex) })
            ),
            items: items
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        try encoder.encode(manifest).write(to: directory.appendingPathComponent("manifest.json"))
        return items.count
    }
}

/// The same "random" bytes every run: SHA-256 of a counter. Used from one thread only, in
/// order, which is the point: the order is what makes the bytes repeat.
final class FixedSequence: @unchecked Sendable {
    private var counter: UInt64 = 0

    func bytes(_ count: Int) -> Data {
        var out = Data()
        while out.count < count {
            var input = Data("syncednotes test files".utf8)
            withUnsafeBytes(of: counter.littleEndian) { input.append(contentsOf: $0) }
            out.append(contentsOf: SHA256.hash(data: input))
            counter += 1
        }
        return out.prefix(count)
    }
}

struct Manifest: Codable {
    struct VaultEntry: Codable {
        var file: String
        var id: String
        var memoryKiB: UInt32
        var passes: UInt32
        var lanes: UInt32
        var salt: String
        /// Each key, so a reader that gets something wrong can tell which step.
        var keys: [String: String]
    }

    struct Item: Codable {
        var file: String
        var description: String
        var kind: String?
        var id: String?
        var opens: Bool
        var contentSha256: String?
        var contentLength: Int?
        var contentUtf8: String?
        var refusedAs: String?
    }

    var about: String
    var passphrase: String
    var passphraseDecomposed: String
    var wrongPassphrase: String
    var vault: VaultEntry
    var items: [Item]
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
}
