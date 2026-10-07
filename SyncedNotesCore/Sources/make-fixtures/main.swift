import Foundation
@_spi(TestFiles) import SyncedNotesCore

// Writes the lock's test files (see TestFiles in SyncedNotesCore). Run from SyncedNotesCore:
//
//     swift run make-fixtures [directory]
//
// The directory defaults to SyncedNotesCore/Fixtures/lock-v1.

let here = URL(fileURLWithPath: #filePath)
// main.swift, make-fixtures, Sources: three steps up is the package.
let package = here.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
let directory = CommandLine.arguments.count > 1
    ? URL(fileURLWithPath: CommandLine.arguments[1])
    : package.appendingPathComponent("Fixtures/lock-v1")
let count = try TestFiles.write(to: directory)
print("Wrote \(count) items and the vault to \(directory.path)")
