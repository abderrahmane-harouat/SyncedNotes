# TODO

What comes next, roughly in order of how much it matters. The rule in
[`Privacy.md`](Privacy.md) applies to every item: nothing here may send note content off the
Mac, or to Apple.

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
