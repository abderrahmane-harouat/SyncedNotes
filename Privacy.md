# Privacy

Everything about where the notes are allowed to go, what the app does to keep them there,
and what is still open. The encryption itself (keys, locked items, backups) is described in
[`Docs/LockFormat.md`](Docs/LockFormat.md).

## The rule

The notes hold live credentials. Note content stays on the Mac, encrypted, and goes nowhere
else, and that includes Apple. No cloud, no backup service, no assistant, no AI feature, no
analytics. A backup goes only where the user saves it, locked. Should sync with a second
device come, it goes to that device over the local network, encrypted, and nowhere else.

"Note content" means the text, titles, folder names, pictures, the passphrase, what is
searched for, and anything derived from them, like previews, search indexes, screenshots, or
what a keyboard learns.

Three principles decide every case this file does not list:

- **Apple is a vendor like any other, and is not trusted.** The app uses what of macOS runs on
  the Mac alone (the text system, CryptoKit, the file system) and refuses every Apple service
  that can carry what is typed, shown or stored somewhere else. Being Apple's is no reason to
  allow a feature.
- **Unknown means off.** A system feature stays disabled until it is confirmed to keep data
  on the device, from the vendor's documentation or by watching the network. The
  confirmation, and where it came from, gets written down here.
- **Off in code, not by default.** Defaults change between OS versions, so every opt-out
  is explicit in the app.

## What we trust, and cannot control

While a note is open, the operating system sees it in plain text: it reads the keystrokes,
draws the pixels, and holds the memory. That is true for any editor on any platform, and no
app can stop it. The rule is about features that send data away, not about the operating
system the app runs on.

What only the system decides, the app cannot reach:

- **The dictation key.** The app removes Start Dictation from its menus, but dictation started
  from the keyboard is macOS's own. Turn Dictation off (below).
- **Apps given Accessibility or Screen Recording access** in System Settings can read what is
  on screen. Grant those only to apps you trust.
- **Keyboards and input methods** see every keystroke. The built-in ones are Apple's.

The settings that close these are in the checklist at the end.

## Building the app

Apple is kept out of the build as well:

- **Signed on this Mac only** (ad-hoc, `CODE_SIGN_IDENTITY = "-"`): no Apple developer account,
  team or provisioning profile, so Xcode contacts no Apple service to sign it.
- **No Xcode Cloud, TestFlight, App Store or notarization.** Notarizing would upload the app to
  Apple; it is not done.
- **No Apple frameworks that talk to Apple's servers**: no CloudKit, App Intents, Spotlight,
  StoreKit, MetricKit or analytics. The app declares no Siri or Shortcuts actions.
- **Dependencies are fetched from their own repositories** (GitHub), pinned to one version,
  and GRDB with SQLCipher is vendored in the repo.

## The app

A native AppKit app gets many system features for free, and some work by sending what is in
a text view to a server. That is where this could quietly change.

Not used, ever:

- iCloud of any kind: documents, CloudKit, keychain sync, Handoff, Universal Clipboard
- Siri, App Intents, Shortcuts
- Apple Intelligence and Writing Tools
- Dictation, Look Up, Translate, the Services menu, the Share menu, AutoFill
- Spotlight or Core Spotlight indexing, Quick Look previews, widgets, notifications that
  show note text
- Crash reporters, analytics, or telemetry of our own or a third party's

Done:

- **Notes at rest are locked twice**: an SQLCipher database, locked whole, holding each note as
  its own locked item (AES-256-GCM, key from the passphrase by Argon2id); pictures are locked
  files with random names. See `Docs/LockFormat.md`. Checked on the raw bytes: no note
  text, title, search word, folder name or picture content reaches the disk. Titles, search
  words and folder names are locked once, by the database, not twice. The folder is the app's
  own sandbox container, which iCloud does not sync.
- **The passphrase is never stored**, and the keys live only in memory until Lock Notes or
  quitting.
- **Deleting a note moves it to the Trash first**, where it stays, locked like any other note,
  for 30 days or until deleted permanently; then only an empty marker is left, so a second
  device would learn of the deletion.
- **App Sandbox with no network**, and access only to files the user picks in a Save or Open
  dialog (added for backups). macOS stops the app connecting anywhere, whatever the code does.
  The hardened runtime is on, with one exception: the app may load the libraries bundled
  inside it (SQLCipher's framework), since a locally signed app has no Team ID to match them
  against. Planned to go once SQLCipher is compiled in from source. Sync, if it comes, adds
  local-network access and nothing more.
- **Backups are locked like everything else**: one sealed item, under the passphrase, with
  nothing readable inside, not even the dates or the number of notes.
- **Changing the passphrase leaves nothing the old one opens** on the Mac: the notes are
  locked again into a new folder, checked, swapped in, and the old folder is deleted. Backups
  exported before the change still open with the old passphrase; the app says so and offers to
  export a new one.
- **Text services are off in every place text is typed**: the note, table cells, the search
  field, folder names, and passphrase fields, the secure ones included. Writing Tools,
  spelling, grammar, autocorrect, completion, inline predictions, math completion, text
  replacement, quote and dash substitution, data and link detection (`turnOffTextServices`,
  in `KeepOnThisMac.swift`). The note's own text view sets them as it is made; a field's
  editing component as editing starts, whoever made the field.
- **The search field and folder names are the app's own fields** (`HardenedTextField.swift`),
  not SwiftUI's, whose editing component cannot be changed (given another, SwiftUI crashes;
  tried). A shown passphrase uses one too.
- **No right-click menu offers to send text anywhere.** The note, table cells and the app's
  fields offer editing only: Cut, Copy, Paste, Select All (and the table's commands in a cell).
  Look Up, Translate, Search With Google, Share, Services and Writing Tools are not offered.
- **Services get nothing.** No text view hands its text to the Services menu
  (`validRequestor` returns nothing), and the Services menu is removed from the app menu.
- **No Look Up gesture.** A force click or three-finger tap on text does nothing, rather than
  looking the word up, Siri knowledge included (`quickLook(with:)`).
- **The Edit menu has no Writing Tools, AutoFill or Start Dictation.** AppKit adds them by
  itself; they are taken out each time it does (`KeepOnThisMac.strip`), and Dictation's menu
  item is also switched off through AppKit's documented setting. Emoji & Symbols stays: it is
  a local picker.
- **Copies stay on this Mac.** Everything copied, from a note or any of the app's fields, is
  marked for this Mac only (`currentHostOnly`), which keeps it out of Universal Clipboard. A
  passphrase's secure field refuses to copy at all (checked). Not yet tried with a second
  device.
- **A shown passphrase keeps a hidden one's protections** (`PassphraseField`): the eye button
  swaps the secure field for the app's own field, which turns secure input on itself while it
  has focus (so other apps still cannot watch the keystrokes), offers no text service, and is
  not marked as a password field, which would invite the Passwords app and its iCloud Keychain.
  Secure input is turned off again exactly once, when the field loses focus or goes.
- **Windows are kept out of screenshots and screen recording** (`sharingType = .none` on every
  window, sheets and Settings included). The cost: the app cannot be screenshotted, and shows
  blank in a screen share. Whether macOS honours this for every way of capturing the screen
  has not been tested.
- **Nothing readable is left beside the notes** (checked 2026-10-07): the app keeps no caches
  and no saved window state; its settings file holds only window sizes and the folder the last
  backup dialog showed.

And:

- If the encryption key ever goes in the keychain, the entry is not synchronizable and is tied
  to this device.

## Open issues

- **⌃⌘D (Look Up) and other system shortcuts** were not tried by hand; the menu items and the
  gesture are gone, but a shortcut macOS handles itself may still reach a selected word.
- **The hardened runtime's library exception** stays until SQLCipher is compiled from source.

## Fixed

Found in an audit on 2026-10-07:

- **The shown passphrase was marked as a password field**, which invites Password AutoFill.
  Removed.
- **The search field and folder names had every text service on**, AppKit's full right-click
  menu, and copies that went to Universal Clipboard. Now the app's own fields.
- **The note's right-click menu, the Edit menu and the Services menu** offered Look Up,
  Translate, Search With Google, Share, Services, Writing Tools, AutoFill and Dictation. Removed.
- **Windows could be captured** by screenshots and screen recording. Now refused.

## Settings to turn off yourself

What the app cannot switch off for you. Checked on this Mac on 2026-10-07:

| Setting | Where | This Mac |
|---|---|---|
| Share Mac Analytics, and share with app developers | System Settings > Privacy & Security > Analytics & Improvements | Off |
| Siri | System Settings > Apple Intelligence & Siri | Off |
| Handoff, which also carries Universal Clipboard | System Settings > General > AirDrop & Handoff | Off |
| Apple Intelligence | System Settings > Apple Intelligence & Siri | Not checked |
| Dictation | System Settings > Keyboard > Dictation | Not checked |

## How to check

- Run the app under a network monitor (LuLu is free and open source). There should be no
  connection at all.
- Take a screenshot (⇧⌘4, then Space, then click the window): the app's window should come out
  blank.
- Right-click a note: only editing commands. The Edit menu: no Writing Tools, AutoFill or Start
  Dictation.
