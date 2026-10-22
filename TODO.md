# TODO

What comes next, roughly in order of how much it matters. The rule in
[`Privacy.md`](Privacy.md) applies to every item: nothing here may send note content off the
Mac, or to Apple.

## Rebuilding the Mac app on the note engine

Until these are done, the Swift app stays in the repo and on the Mac.

- [ ] **Arabic and other right-to-left text**: Arabic joining and the lam-alef ligature in the
  Rust shaper (OpenType GSUB), marks and kerning (GPOS), and the bidirectional algorithm in the
  engine's layout, with the caret and selection following what is on screen.
- [ ] **Pictures and tables** in the engine, so the notes holding them can be edited; until
  then they open read-only.
- [ ] **Rich copy and paste** between notes, keeping formatting; plain text today.
- [ ] Lists that start as you type: `- `, `* ` or `1. ` at the start of a line.
- [ ] An Edit menu that acts on the note or the field that has focus.
- [ ] Dragging notes onto folders, and columns that can be resized.
- [ ] Input methods that compose (dead keys, Chinese and Japanese) through AWT's.
- [ ] The app's size: about 150 MB, mostly the Java runtime and Skia; SQLite's driver and
  Skia's jar each carry a library the app already has.
- [ ] Import the notes from the Swift app's backup, then retire the Swift app and its sources.

## Safety of the notes

- [ ] **Automatic encrypted backups**: a backup on a schedule to a folder the user picks once,
  keeping the last few. Today backups are made by hand, and a forgotten passphrase or a lost
  Mac loses everything not backed up.
- [ ] **Auto-lock**: lock after a chosen time idle, and when the Mac sleeps or the screen locks.
- [ ] **Unlock with Touch ID**, the key kept in the keychain on this Mac only (not
  synchronizable, tied to this device, behind biometrics), with the passphrase still asked for
  after a restart.
- [ ] **Compile SQLCipher from source** into the app, so the hardened runtime's exception for
  loading bundled libraries can go (`SyncedNotes.entitlements`).
- [ ] **Clean up pictures** of notes deleted for good: they stay on disk, locked, until then.
- [ ] **An independent security review** of the lock and the database.

## Privacy, checked by hand

- [ ] A screenshot and a screen recording of the real window come out blank.
- [ ] ⌃⌘D (Look Up) and the dictation key do nothing in a note or a field.
- [ ] A run under a network monitor (LuLu) shows no connection at all.
- [ ] A copy does not reach a second Apple device, even with Handoff on.

## Writing

- [ ] Find and replace within a note.
- [ ] Links between notes.
- [ ] Tags, alongside folders.
- [ ] Pinned notes, and a choice of sort order (edited, created, title).
- [ ] Insert a picture from a file dialog (needs read-only access to files the user picks).
- [ ] Files that are not pictures, kept locked like pictures.
- [ ] Tables: column widths, selecting and moving rows and columns.
- [ ] A heading made not bold keeps that when saved (today a heading's weight is part of the
  heading).
- [ ] List markers drawn by the app for right-to-left items too.

## Getting notes in and out

- [ ] Export a note, a folder or everything as Markdown and as PDF, and print.
- [ ] Import from Markdown files and from Apple Notes (by copy and paste today).
- [ ] Note history: earlier versions of a note, locked like the note.

## Quality

- [ ] A test target in the Xcode project for the editor and the windows: paste, copy, lists,
  checklists, folders, selection, the passphrase sheet, the privacy protections. These checks
  exist, but as a separate program that is not in the repo.
- [ ] Run the app from a fresh vault in a screenshot test, light and dark.

## Later

- [ ] An iPhone app, and sync between the user's own devices over the local network only,
  encrypted end to end. The lock format is already written for a second device
  ([`Docs/LockFormat.md`](Docs/LockFormat.md)).
