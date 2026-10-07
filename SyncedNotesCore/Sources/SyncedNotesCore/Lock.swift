import CryptoKit
import Foundation
import Sodium

// The lock format, exactly as Docs/LockFormat.md describes it. The notes on disk, the
// backups and the test files all depend on it, so anything changed here must change there
// too, as a new format version.

/// Why something could not be unlocked or opened. Every failure refuses the whole thing:
/// there is never partial content.
public enum LockError: Error, Equatable {
    case notAVaultHeader
    case notALockedItem
    case unsupportedVersion(UInt8)
    /// Settings this build cannot use, such as more than one Argon2id lane.
    case unsupportedSettings
    case wrongPassphrase
    case wrongVault
    case wrongKind(expected: ItemKind, found: ItemKind)
    case unknownKind(UInt8)
    /// The tag did not verify: the item was altered, cut short, or damaged.
    case damaged
    case keyDerivationFailed
}

public enum ItemKind: UInt8, Sendable {
    case note = 1
    case picture = 2
    /// A whole backup: every note and picture, sealed as one (see Backup).
    case backup = 3
}

/// How hard the passphrase is to guess. See "Choices and why" in LockFormat.md.
public struct KeyDerivationSettings: Equatable, Sendable {
    public var memoryKiB: UInt32
    public var passes: UInt32
    public var lanes: UInt32

    /// OWASP's recommended minimum, chosen for speed: 0.02 s per unlock on the Mac.
    public static let standard = KeyDerivationSettings(memoryKiB: 19_456, passes: 2, lanes: 1)

    public init(memoryKiB: UInt32, passes: UInt32, lanes: UInt32) {
        (self.memoryKiB, self.passes, self.lanes) = (memoryKiB, passes, lanes)
    }
}

/// Where random bytes come from. The system's, except when making the committed test files,
/// which use a fixed sequence so that making them again gives the same bytes.
public typealias RandomBytes = @Sendable (Int) -> Data

public let systemRandom: RandomBytes = { count in
    var generator = SystemRandomNumberGenerator()
    return Data((0..<count).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
}

// MARK: - The vault header

/// Not secret, but needed to unlock: the vault's ID, the Argon2id settings and salt, and a
/// check that only the right passphrase opens.
public struct VaultHeader: Equatable, Sendable {
    static let magic = Data("SNV1".utf8)
    static let version: UInt8 = 1
    static let size = 93
    static let checkPhrase = Data("SyncedNotes-OK-1".utf8)

    public let id: Data
    public let settings: KeyDerivationSettings
    public let salt: Data
    let checkNonce: Data
    let check: Data

    /// The bytes before `check`: the additional data that `check` is sealed with.
    var authenticatedPart: Data {
        var bytes = Self.magic
        bytes.append(Self.version)
        bytes.append(id)
        bytes.append(littleEndian: settings.memoryKiB)
        bytes.append(littleEndian: settings.passes)
        bytes.append(littleEndian: settings.lanes)
        bytes.append(salt)
        bytes.append(checkNonce)
        return bytes
    }

    public var encoded: Data { authenticatedPart + check }

    public init(decoding bytes: Data) throws {
        let bytes = Data(bytes)
        guard bytes.count == Self.size, bytes.prefix(4) == Self.magic else { throw LockError.notAVaultHeader }
        guard bytes[4] == Self.version else { throw LockError.unsupportedVersion(bytes[4]) }
        // Copies, not slices: a slice keeps the old file's positions, which trips up any
        // later code that indexes it from zero.
        id = Data(bytes[5..<21])
        settings = KeyDerivationSettings(
            memoryKiB: bytes.littleEndianUInt32(at: 21),
            passes: bytes.littleEndianUInt32(at: 25),
            lanes: bytes.littleEndianUInt32(at: 29)
        )
        salt = Data(bytes[33..<49])
        checkNonce = Data(bytes[49..<61])
        check = Data(bytes[61..<93])
    }

    init(id: Data, settings: KeyDerivationSettings, salt: Data, checkNonce: Data, check: Data) {
        (self.id, self.settings, self.salt, self.checkNonce, self.check) = (id, settings, salt, checkNonce, check)
    }
}

// MARK: - The vault, unlocked

/// An unlocked vault: its header and the keys made from the passphrase. Held only in memory,
/// for as long as the app is open.
public struct Vault: Sendable {
    public let header: VaultHeader
    let masterKey: SymmetricKey
    let notesKey: SymmetricKey
    let picturesKey: SymmetricKey
    let databaseKey: SymmetricKey
    let backupsKey: SymmetricKey

    /// Every key, for the test files only: written out beside them, so a reader of the format
    /// can tell exactly which step it gets wrong. Never used for real notes.
    @_spi(TestFiles) public var keysForTestFiles: [(name: String, bytes: Data)] {
        [("master", masterKey), ("notes", notesKey), ("pictures", picturesKey), ("database", databaseKey)]
            .map { ($0.0, $0.1.withUnsafeBytes { Data($0) }) }
    }

    /// The raw key for the database's whole-file lock (SQLCipher).
    public var databaseKeyBytes: Data { databaseKey.withUnsafeBytes { Data($0) } }

    /// A new vault for [passphrase], with a new ID and salt.
    public static func create(
        passphrase: String,
        settings: KeyDerivationSettings = .standard,
        random: RandomBytes = systemRandom
    ) throws -> Vault {
        let id = random(16)
        let salt = random(16)
        let checkNonce = random(12)
        let keys = try Keys(passphrase: passphrase, vaultID: id, settings: settings, salt: salt)
        let unsealed = VaultHeader(id: id, settings: settings, salt: salt, checkNonce: checkNonce, check: Data())
        let box = try AES.GCM.seal(
            VaultHeader.checkPhrase, using: keys.notes,
            nonce: AES.GCM.Nonce(data: checkNonce), authenticating: unsealed.authenticatedPart
        )
        let header = VaultHeader(id: id, settings: settings, salt: salt, checkNonce: checkNonce, check: box.ciphertext + box.tag)
        return Vault(header: header, keys: keys)
    }

    /// Unlocks [header] with [passphrase]. A wrong passphrase is caught here, by the check,
    /// before any note is touched.
    public static func unlock(_ header: VaultHeader, passphrase: String) throws -> Vault {
        let keys = try Keys(passphrase: passphrase, vaultID: header.id, settings: header.settings, salt: header.salt)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: header.checkNonce),
                ciphertext: header.check.prefix(16), tag: header.check.suffix(16)
            )
            let phrase = try AES.GCM.open(box, using: keys.notes, authenticating: header.authenticatedPart)
            guard phrase == VaultHeader.checkPhrase else { throw LockError.wrongPassphrase }
        } catch {
            throw LockError.wrongPassphrase
        }
        return Vault(header: header, keys: keys)
    }

    private init(header: VaultHeader, keys: Keys) {
        self.header = header
        (masterKey, notesKey, picturesKey, databaseKey, backupsKey) = (keys.master, keys.notes, keys.pictures, keys.database, keys.backups)
    }

    // MARK: Locked items

    /// Locks [content] as a note or picture with ID [id] (16 bytes).
    public func lock(_ content: Data, kind: ItemKind, id: Data, random: RandomBytes = systemRandom) throws -> Data {
        precondition(id.count == 16, "An item ID is 16 bytes.")
        var header = LockedItem.magic
        header.append(LockedItem.version)
        header.append(kind.rawValue)
        header.append(self.header.id)
        header.append(id)
        let nonce = random(12)
        header.append(nonce)
        let box = try AES.GCM.seal(content, using: key(for: kind), nonce: AES.GCM.Nonce(data: nonce), authenticating: header)
        return header + box.ciphertext + box.tag
    }

    /// Opens a locked item, refusing it whole if anything about it is wrong.
    public func open(_ item: Data, expecting expected: ItemKind? = nil) throws -> LockedItem {
        let item = Data(item)
        guard item.count >= LockedItem.headerSize + 16, item.prefix(4) == LockedItem.magic else {
            throw LockError.notALockedItem
        }
        guard item[4] == LockedItem.version else { throw LockError.unsupportedVersion(item[4]) }
        guard let kind = ItemKind(rawValue: item[5]) else { throw LockError.unknownKind(item[5]) }
        if let expected, expected != kind { throw LockError.wrongKind(expected: expected, found: kind) }
        guard item[6..<22] == header.id else { throw LockError.wrongVault }
        let id = item[22..<38]
        let aad = item.prefix(LockedItem.headerSize)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: item[38..<50]),
                ciphertext: item[LockedItem.headerSize..<(item.count - 16)], tag: item.suffix(16)
            )
            let content = try AES.GCM.open(box, using: key(for: kind), authenticating: aad)
            return LockedItem(kind: kind, id: Data(id), content: Data(content))
        } catch {
            throw LockError.damaged
        }
    }

    private func key(for kind: ItemKind) -> SymmetricKey {
        switch kind {
        case .note: notesKey
        case .picture: picturesKey
        case .backup: backupsKey
        }
    }
}

/// What an opened item holds.
public struct LockedItem: Equatable, Sendable {
    static let magic = Data("SNL1".utf8)
    static let version: UInt8 = 1
    static let headerSize = 50

    public let kind: ItemKind
    public let id: Data
    public let content: Data
}

// MARK: - Keys

/// The master key and the three subkeys made from it.
struct Keys {
    let master: SymmetricKey
    let notes: SymmetricKey
    let pictures: SymmetricKey
    let database: SymmetricKey
    let backups: SymmetricKey

    init(passphrase: String, vaultID: Data, settings: KeyDerivationSettings, salt: Data) throws {
        let master = try Self.masterKey(passphrase: passphrase, settings: settings, salt: salt)
        self.master = master
        func subkey(_ info: String) -> SymmetricKey {
            HKDF<SHA256>.deriveKey(inputKeyMaterial: master, salt: vaultID, info: Data(info.utf8), outputByteCount: 32)
        }
        notes = subkey("syncednotes notes v1")
        pictures = subkey("syncednotes pictures v1")
        database = subkey("syncednotes database v1")
        backups = subkey("syncednotes backups v1")
    }

    /// Argon2id over the passphrase in Unicode NFC. Keyboards produce the same letter in
    /// different ways; without normalising, the right passphrase typed on another keyboard could be
    /// different bytes from the same one typed on the Mac, and be refused.
    static func masterKey(passphrase: String, settings: KeyDerivationSettings, salt: Data) throws -> SymmetricKey {
        // libsodium's Argon2id always uses one lane.
        guard settings.lanes == 1 else { throw LockError.unsupportedSettings }
        let normalised = Array(passphrase.precomposedStringWithCanonicalMapping.utf8)
        guard let bytes = Sodium().pwHash.hash(
            outputLength: 32,
            passwd: normalised,
            salt: Array(salt),
            opsLimit: Int(settings.passes),
            memLimit: Int(settings.memoryKiB) * 1024,
            alg: .Argon2ID13
        ) else { throw LockError.keyDerivationFailed }
        return SymmetricKey(data: bytes)
    }
}

// MARK: - Little-endian numbers

extension Data {
    mutating func append(littleEndian value: UInt32) {
        Swift.withUnsafeBytes(of: value.littleEndian) { append(contentsOf: $0) }
    }

    func littleEndianUInt32(at offset: Int) -> UInt32 {
        let start = startIndex + offset
        return self[start..<(start + 4)].enumerated().reduce(0) { $0 | UInt32($1.element) << (8 * UInt32($1.offset)) }
    }
}
