// swift-tools-version:6.1
// GRDB 7.11.1 (commit b83108d10f42680d78f23fe4d4d80fc88dab3212), changed only as GRDB's own
// Package.swift says to for SQLCipher: the "GRDB+SQLCipher" lines are applied, the system
// SQLite library is removed, and the tests are left out. See VENDORED.md.

import PackageDescription

let swiftSettings: [SwiftSetting] = [
    .define("SQLITE_ENABLE_FTS5"),
    .define("SQLITE_ENABLE_SNAPSHOT"),
    // GRDB+SQLCipher
    .define("SQLITE_HAS_CODEC"),
    .define("SQLCipher"),
]
let cSettings: [CSetting] = [
    // GRDB+SQLCipher
    .define("SQLITE_HAS_CODEC"),
]

let package = Package(
    name: "GRDB",
    platforms: [.iOS(.v13), .macOS(.v10_15), .tvOS(.v13), .watchOS(.v7)],
    products: [
        .library(name: "GRDB", targets: ["GRDB"]),
    ],
    dependencies: [
        // Pinned exactly, where GRDB's comment says "from": nothing updates by itself.
        .package(url: "https://github.com/sqlcipher/SQLCipher.swift.git", exact: "4.19.0"),
    ],
    targets: [
        .target(
            name: "GRDBSQLCipher",
            dependencies: [.product(name: "SQLCipher", package: "SQLCipher.swift")]
        ),
        .target(
            name: "GRDB",
            dependencies: [
                .product(name: "SQLCipher", package: "SQLCipher.swift"),
                .target(name: "GRDBSQLCipher"),
            ],
            path: "GRDB",
            resources: [.copy("PrivacyInfo.xcprivacy")],
            cSettings: cSettings,
            swiftSettings: swiftSettings + [.enableUpcomingFeature("MemberImportVisibility")]
        ),
    ],
    swiftLanguageModes: [.v6]
)
