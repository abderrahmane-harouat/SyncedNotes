# GRDB, vendored

[GRDB.swift](https://github.com/groue/GRDB.swift) 7.11.1, commit
`b83108d10f42680d78f23fe4d4d80fc88dab3212`, MIT licence (`LICENSE`).

**Why a copy:** GRDB's SQLCipher support, which gives the database its whole-file lock, can
only be switched on with the Swift Package Manager by changing GRDB's own `Package.swift`
(GRDB's README, "Encryption"). So the project keeps this copy, pinned, and updates it by
hand. It also means the code shipped is exactly the code that was checked.

**What was changed:** only `Package.swift`, exactly as its "GRDB+SQLCipher" comments say:
SQLCipher is added as a dependency (pinned to 4.19.0 rather than "from 4.11.0"), the
`SQLITE_HAS_CODEC` and `SQLCipher` flags are set, the `GRDBSQLCipher` target is used, and the
system SQLite library is removed. The tests, documentation and Xcode projects are left out.
`GRDB/` and `Sources/GRDBSQLCipher/` are byte-for-byte GRDB's.

**To update:** clone the new tag, replace `GRDB/` and `Sources/GRDBSQLCipher/`, re-apply the
same `Package.swift` change against the new version's, and update the commit above.
