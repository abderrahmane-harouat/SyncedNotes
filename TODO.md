# TODO

What comes next, roughly in order of how much it matters. The rule in
[`Privacy.md`](Privacy.md) applies to every item: nothing here may send note content anywhere
but the user's own Mac and phone, nor to Apple or Google.

## Rebuilding the Mac app on the note engine

Until these are done, the Swift app stays in the repo and on the Mac.

- [x] **Arabic and other right-to-left text**: Arabic joining and the lam-alef ligature in the
  Rust shaper (OpenType GSUB), marks and kerning (GPOS), and the bidirectional algorithm in the
  engine's layout, with the caret and selection following what is on screen.
- [ ] Right-to-left details: bracket pairs (UAX #9's N0), the invisible direction controls,
  and justified lines.
- [x] **Pictures** in the engine: inserted on both devices, shown at their size, kept whole
  when deleting or typing beside them, and synced.
- [ ] A picture amid text on a line (the Swift app could make one), and **tables**; notes
  holding either still open read-only.
- [ ] Copying and pasting a picture; resizing one.
- [x] **Rich paste** from web pages, Notion and Markdown (the Copy buttons of Claude and
  ChatGPT): headings, lists, checklists, quotes, code with its language, dividers, bold,
  italic, underline, strikethrough, inline code, tables as lines.
- [ ] **Rich copy** between notes, keeping formatting; plain text today, and none on the phone.
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
- [ ] A run under a network monitor (LuLu) shows no connection but to the paired phone.
- [ ] A copy does not reach a second Apple device, even with Handoff on.

## Writing

- [ ] Find and replace within a note.
- [ ] Links between notes.
- [ ] Tags, alongside folders.
- [ ] Pinned notes, and a choice of sort order (edited, created, title).
- [x] Insert a picture: a file on the Mac, the photo picker on the phone.
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

## The phone, and sync

Since 2026-10-23 the Android app keeps the same notes as the Mac over the local network
([`Docs/Sync.md`](Docs/Sync.md)): pairing by a code, then live sync of notes, folders, the
Trash and pictures. Tried between this Mac's try-out notes and a Galaxy M32.

- [ ] Try the packaged, sandboxed Mac app with the phone (the try-out run is unsandboxed).
- [x] The phone's dark theme, with the Mac's choice of System, Light or Dark.
- [x] The phone's own Change Passphrase, for a phone not paired.
- [ ] The Mac's passphrase change reaching the phone on screen (the phone is told today, and
  asks for the new one; the flow is untried by hand).
- [x] Moving folders on the phone.
- [x] `FLAG_SECURE` on the phone, so Recent apps shows no note.
- [x] Find in Note on both, every match marked.
- [x] The same tools on both: paragraph styles, the four lists, alignment with justify, the
  character styles, inline code, quote, code block, divider, line spacing, indent, the Mac's
  text colours and six highlights; Samsung's reading mode on the phone; undo and redo on the
  Mac's toolbar.
- [x] Code blocks in a language, their syntax coloured: C and C++ (Format ▸ Code Block on the
  Mac, the code block button on the phone).
- [ ] More languages for code blocks, and the block's language shown on the block itself.
- [ ] Sync in the background on the phone, while the app is closed: today it syncs while open
  and unlocked, as SyncedPass does.
- [ ] Deletion marks kept forever: prune those both devices have seen.
