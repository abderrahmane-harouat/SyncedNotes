# SyncedNotes

Encrypted notes for the Mac, native Swift. No server, no account, no cloud. See
[README.md](README.md) for the product and the folder.

## Stack

- **Language / Runtime**: Swift 6, macOS 26
- **Framework**: SwiftUI for the windows; AppKit's `NSTextView` on TextKit 2 for the editor
- **Key dependencies**: GRDB with SQLCipher (vendored in `Vendor/GRDB`), swift-sodium for
  Argon2id, swift-protobuf for the note format; CryptoKit for AES-GCM and HKDF
- **Build**: `SyncedNotes.xcodeproj` (the app) and the Swift package `SyncedNotesCore`

## Commands

```bash
xcodebuild -project SyncedNotes.xcodeproj -scheme SyncedNotes -configuration Debug -derivedDataPath build build
cd SyncedNotesCore && swift test             # the lock, database, backups, folders, passphrase change
cd SyncedNotesCore && swift run make-fixtures   # the lock's test files, only after a format change
swift Scripts/make-app-icon.swift               # the app icon, from Design/AppIcon-artwork.png
```

Installing: see the README. Keep exactly one copy of the app on the Mac,
`/Applications/SyncedNotes.app`; delete the build's copy after installing.

## Rules

- **Notes never leave the Mac, not even to Apple.** No iCloud, no Siri, Apple Intelligence or
  Writing Tools, no Handoff or Universal Clipboard, no Spotlight indexing, no crash reporting or
  analytics. A system feature stays off until it is confirmed to keep data on the device, and it
  is turned off in code rather than left to the defaults. Everything about it lives in
  [`Privacy.md`](Privacy.md); keep that file current whenever a change touches what the app
  shares with the system.
- **Apple is a vendor like any other, and is not trusted.** Use only what of macOS runs on the
  Mac alone. Every text field is the app's own (`PlainTextField`, `PlainSearchField`) or a
  secure field, never SwiftUI's `TextField` or `.searchable`; every right-click menu offers
  editing only; every copy is for this Mac only; no Apple framework that talks to a server.
- **The build never involves Apple's services**: signed ad-hoc on this Mac, no developer
  account, no Xcode Cloud, TestFlight, App Store or notarization.
- **The lock format is a contract.** [`Docs/LockFormat.md`](Docs/LockFormat.md) describes it byte
  for byte; the notes on disk and every backup depend on it. A change is a new format version,
  never a silent edit.
- **Never edit `SyncedNotesCore/Fixtures/` or `Generated/` by hand.** The lock's test files are
  written by `swift run make-fixtures`, always to the same bytes, and a test fails if the
  committed copies differ. The note format's code is generated from `Proto/note.proto`, whose
  fields are only ever added under new numbers.
- **A wrong key must fail loudly.** Never return partial plaintext, and never write over notes
  with something that did not authenticate.
- **Comments say why, not what.** This codebase explains the reasoning behind a decision,
  especially where the obvious choice was wrong. Match that density, and do not strip it out.
- **Test names are sentences** describing the behaviour: `aFolderNameIsNotReadableOnDisk`,
  `theWrongCurrentPassphraseChangesNothing`.
- **Commit messages are plain sentences** about what changed for the person using the app:
  "Keep notes in folders". Not Conventional Commits.
- **Nothing secret enters the repo.** `.snbackup` and `.snx` files and anything with a key are
  ignored by git. Test data is invented and guards nothing.
- **Tests never touch the real notes or the real clipboard.** Use a temporary folder and a
  private pasteboard; the editor's own Copy writes to the system clipboard.
