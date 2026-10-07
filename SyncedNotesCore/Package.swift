// swift-tools-version: 6.2
import PackageDescription

// The Mac app's logic that is not about the screen: the lock (Docs/LockFormat.md), the
// note format (Proto/note.proto) and the encrypted database. Kept apart from the app so it
// can be tested from the terminal, and so the lock's test files come from the very same code.
let package = Package(
    name: "SyncedNotesCore",
    platforms: [.macOS(.v26)],
    products: [
        .library(name: "SyncedNotesCore", targets: ["SyncedNotesCore"]),
        .executable(name: "make-fixtures", targets: ["make-fixtures"]),
    ],
    dependencies: [
        // Argon2id, from libsodium's own author. Pinned exactly: nothing updates by itself.
        .package(url: "https://github.com/jedisct1/swift-sodium.git", exact: "0.11.0"),
        // Apple's Protobuf, for the note format (Proto/note.proto).
        .package(url: "https://github.com/apple/swift-protobuf.git", exact: "1.38.1"),
        // SQLite with SQLCipher's whole-file lock: our own pinned copy, see its VENDORED.md.
        .package(path: "../Vendor/GRDB"),
    ],
    targets: [
        .target(
            name: "SyncedNotesCore",
            dependencies: [
                .product(name: "Sodium", package: "swift-sodium"),
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
                .product(name: "GRDB", package: "GRDB"),
            ]
        ),
        .executableTarget(name: "make-fixtures", dependencies: ["SyncedNotesCore"]),
        .testTarget(
            name: "SyncedNotesCoreTests",
            // GRDB directly too, to build a database as an older version left it.
            dependencies: ["SyncedNotesCore", .product(name: "GRDB", package: "GRDB")]
        ),
    ]
)
