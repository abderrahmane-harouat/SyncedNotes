# SyncedNotes

Encrypted notes for the Mac, and a phone app beside it. No server, no account, no cloud. See
[README.md](README.md) for the product and the folder.

## Stack

Since 2026-10-22 the Mac app is built without Xcode or Swift. The Swift app (`SyncedNotes/`,
`SyncedNotesCore/`) stays in the repo as reference until the new one does everything it did.

- **Language / Runtime**: Kotlin 2.4 on the JVM (JDK 27 to build, a Temurin 25 runtime inside
  the app), Rust for the engine's fonts and drawing
- **Windows**: Compose for Desktop 1.12 around the note on the Mac; Android's own widgets,
  built in code, on the phone (minSdk 28). The note itself is the app's own engine
  (`shared/engine`, `shared/glyphs`), never a text view of the system's or Compose's
- **Key dependencies**: SQLite3 Multiple Ciphers (`io.github.willena:sqlite-jdbc`) on the Mac and
  SQLCipher for Android on the phone, both SQLCipher 4's format; Bouncy Castle for Argon2id;
  protobuf-javalite for the note format and sync; the platform's own AES-GCM, ECDH and ECDSA
- **Build**: Gradle in `mac/` (`:app`, with `:core` and `:engine` from `shared/`) and in
  `android/` (the phone app, on the same `shared/` modules)

## Commands

```bash
cd mac && ./gradlew test                    # the lock, store, backups, engine, app
cd mac && ./gradlew :app:run                # the app on try-out notes, SyncedNotes-Try
cd mac && ./gradlew :app:makeTryNotes       # made-up notes for that, then :app:run -PtryUnlock
cd mac && ./gradlew :app:signApp            # the signed, sandboxed SyncedNotes.app
cd android && ./gradlew :app:assembleDebug   # the phone app (ANDROID_HOME, NDK 28.2)
cd android && ./gradlew :app:assembleTryout  # "SyncedNotes Try": beside the real app, made-up notes only
cd shared/glyphs && cargo test --release     # the Rust half
cd SyncedNotesCore/Proto && protoc --java_out=lite:../../shared/core/Generated note.proto sync.proto
```

Installing: see the README. Keep exactly one copy of the app on the Mac,
`/Applications/SyncedNotes.app`; delete the build's copy after installing.

## Rules

- **Notes never leave the user's Mac and phone, not even to Apple or Google.** Sync goes
  straight between the two over the local network ([`Docs/Sync.md`](Docs/Sync.md)). No iCloud, no Siri, Apple Intelligence or
  Writing Tools, no Handoff or Universal Clipboard, no Spotlight indexing, no crash reporting or
  analytics. A system feature stays off until it is confirmed to keep data on the device, and it
  is turned off in code rather than left to the defaults. Everything about it lives in
  [`Privacy.md`](Privacy.md); keep that file current whenever a change touches what the app
  shares with the system.
- **Apple is a vendor like any other, and is not trusted.** Use only what of macOS runs on the
  Mac alone. The note is the app's own engine; the small fields (search, folder names,
  passphrases) are Compose's, which use no text service of the system's; every right-click
  menu offers editing only; every copy goes through `Pasteboard`, for this Mac only; no Apple
  framework that talks to a server. Native calls go in `KeepOnThisMac.kt` and say why.
- **The build never involves Apple's services**: signed ad-hoc on this Mac, no developer
  account, no Xcode Cloud, TestFlight, App Store or notarization.
- **The lock format is a contract.** [`Docs/LockFormat.md`](Docs/LockFormat.md) describes it byte
  for byte; the notes on disk and every backup depend on it. A change is a new format version,
  never a silent edit.
- **The sync protocol is a contract too.** [`Docs/Sync.md`](Docs/Sync.md) and
  `SyncedNotesCore/Proto/sync.proto` are what the phone and the Mac both speak; both apps run
  the one implementation in `shared/core` (package `sync`), so a change there is a change to both.
- **Never edit `SyncedNotesCore/Fixtures/` or `Generated/` by hand.** The lock's test files were
  written by the Swift app's lock code, and the Kotlin lock's tests open them as they are. The
  note format's code is generated from `Proto/note.proto`, whose fields are only ever added
  under new numbers.
- **A note is saved from the engine only if it reads in and writes back exactly**
  (`NoteFormat.canEdit`); anything the engine cannot show yet opens read-only, never saved over.
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
- **Tests never touch the real notes or the real clipboard.** Use a temporary folder and
  `Pasteboard.private()`; a run from the build uses `SyncedNotes-Try`, never `SyncedNotes`. On
  the phone, try changes in the Tryout build, never in the real app, and never open its photo
  picker or capture its screen.
