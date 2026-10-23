# Privacy

Everything about where the notes are allowed to go, what the app does to keep them there,
and what is still open. The encryption itself (keys, locked items, backups) is described in
[`Docs/LockFormat.md`](Docs/LockFormat.md).

## The rule

The notes hold live credentials. Note content stays on the user's own two devices, the Mac
and the paired phone, encrypted, and goes nowhere else, and that includes Apple and Google.
No cloud, no backup service, no assistant, no AI feature, no analytics. A backup goes only
where the user saves it, locked. Sync goes straight from one device to the other over the
local network, encrypted twice ([`Docs/Sync.md`](Docs/Sync.md)), and nowhere else.

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

Apple is kept out of the build as well. Since 2026-10-22 the Mac app is built without Xcode
or Swift (`mac/`, see the README):

- **Signed on this Mac only** (ad-hoc, `codesign --sign -`): no Apple developer account, team
  or provisioning profile.
- **No Xcode Cloud, TestFlight, App Store or notarization.** Notarizing would upload the app to
  Apple; it is not done.
- **No Apple frameworks that talk to Apple's servers.** The app reaches macOS only through
  the few calls in `KeepOnThisMac.kt` (the Objective-C runtime and Carbon, for the protections
  below) and Java's own AWT; no CloudKit, App Intents, Spotlight, StoreKit or analytics.
- **Dependencies are fetched from their own repositories**, each pinned to one version: Maven
  Central and Google's Maven for libraries, the Gradle plugin portal for build plugins, and
  Adoptium for the JDK the app is packaged with. None of them is Apple.

## The app

The note is drawn by the app's own engine (`shared/engine`, with its Rust half in
`shared/glyphs`), not by any text view of the system's: macOS gets keystrokes and shows
pixels, and there is no text view for Writing Tools, Look Up, Services, spelling or
autocorrect to attach to. The window around it is Compose for Desktop, on Java's AWT.

Not used, ever:

- iCloud of any kind: documents, CloudKit, keychain sync, Handoff, Universal Clipboard
- Siri, App Intents, Shortcuts
- Apple Intelligence and Writing Tools
- Dictation, Look Up, Translate, the Services menu, the Share menu, AutoFill
- Spotlight or Core Spotlight indexing, Quick Look previews, widgets, notifications that
  show note text
- Crash reporters, analytics, or telemetry of our own or a third party's

Done:

- **Notes at rest are locked twice**: an SQLCipher-format database (SQLite3 Multiple Ciphers
  through `io.github.willena:sqlite-jdbc`), locked whole, holding each note as its own locked
  item (AES-256-GCM, key from the passphrase by Argon2id); pictures are locked files with
  random names. See `Docs/LockFormat.md`. Checked on the raw bytes by the tests: no note text,
  title, search word, folder name or picture content reaches the disk. The folder is the
  app's own sandbox container, which iCloud does not sync.
- **The passphrase is never stored**, and the keys live only in memory until Lock Notes or
  quitting.
- **Deleting a note moves it to the Trash first**, where it stays, locked like any other note,
  for 30 days or until deleted permanently; then only an empty marker is left.
- **App Sandbox**, with access only to files the user picks in a Save or Open dialog, for
  backups, and to the network for sync (`mac/packaging/SyncedNotes.entitlements`). Before
  2026-10-23 the sandbox had no network at all; sync needs it. What keeps notes on the local
  network is now the app's own code (below), which a network monitor can check. Checked:
  the packaged app opens its notes inside the sandbox. The libraries the app loads (the Rust engine, SQLite's) are inside the
  app and covered by its signature: the sandbox refuses to load code the app writes itself,
  which is what SQLite's driver would otherwise do.
- **Backups are locked like everything else**: one sealed item, under the passphrase.
- **Changing the passphrase leaves nothing the old one opens** on the Mac: the notes are
  locked again into a new folder, checked, swapped in, and the old folder is deleted.
- **The small text fields** (search, folder names, passphrases) are Compose's own: they use no
  text service of the system's, and their right-click menu offers editing only.
- **Services get nothing.** The Services menu Java puts in the app menu is taken out, and
  AppKit is told there is none (`KeepOnThisMac.kt`). Checked: the app menu has About, Hide,
  Show All and Quit, nothing else. There is no Edit menu, so AppKit has nowhere to add
  Writing Tools, AutoFill or Start Dictation.
- **Copies stay on this Mac.** Everything copied, from a note or from a field, goes through
  `Pasteboard`, which marks it for this Mac only (`currentHostOnly`), keeping it out of
  Universal Clipboard; Compose's fields are given that pasteboard in place of Java's. A
  passphrase field refuses to copy at all.
- **Passphrase fields turn on secure input** while they have focus, shown or hidden, so other
  apps cannot watch the keystrokes; it is turned off again exactly once (`SecureInput`).
- **Windows are kept out of screenshots and screen recording** (`sharingType = .none`).
  Checked: the window server reports the window as not shareable. A run from the build on
  made-up notes may be captured, to check the app's look; the app never allows it with the
  real notes.

- **Sync stays on the local network** (`Docs/Sync.md`). The Mac finds the phone with Bonjour,
  macOS's multicast DNS, answered by the devices on the network and no server; it announces
  itself under the name "SyncedNotes", never the Mac's own, and only while unlocked and paired
  or pairing. It accepts connections only from, and connects only to, local addresses
  (private, link-local, loopback), and every connection must prove it is the paired phone
  before anything is sent. Notes travel as the locked items they are, inside a session
  encrypted with keys that die with it. macOS asks once before the app may use the local
  network (`NSLocalNetworkUsageDescription`), and lets it look only for the two services
  named in its Info.plist. The Mac's own name goes to the phone only while pairing, which the
  user starts and confirms with a code on both screens.

And:

- If the encryption key ever goes in the keychain, the entry is not synchronizable and is tied
  to this device.

## The phone app

The same rule, the same engine (no Android text view, so no spell checker, autofill or
keyboard learning sees a note), the same lock and store as the Mac, and the same sync.

- **Notes at rest** are in the app's own storage, which no other app can read, locked as on
  the Mac: SQLCipher for Android (`net.zetetic:sqlcipher-android`) with the vault's database
  key, each note its own locked item.
- **Never backed up off the phone**: `allowBackup` is off, and the data extraction rules keep
  everything out of Google's cloud backup and out of the transfer to a new phone.
- **The notes lock** after five minutes away from the app, and from the menu.
- **Screenshots and Recent apps show nothing** (`FLAG_SECURE`), as the Mac keeps its windows
  out of screenshots. A debug build allows screenshots only when a file only `adb` can write
  is there, to check the app's look on made-up notes.
- **The few text fields** (passphrase, search, folder names) tell the keyboard not to learn or
  suggest (`IME_FLAG_NO_PERSONALIZED_LEARNING`; Gboard and Samsung Keyboard then show their
  incognito mark), and every window is closed to autofill services and to content capture,
  which would hand what is on screen to another app or the system's intelligence.
- **The network**: Android's `INTERNET` permission, which it requires for any socket, local
  ones included. The phone finds the Mac with Network Service Discovery (multicast DNS on the
  local network) and refuses any address outside it, as the Mac does.

## Open issues


- **⌃⌘D (Look Up) and other system shortcuts** were not tried by hand in the new app.
- **The dictation key** still reaches macOS's own Dictation, which the app cannot refuse:
  turn Dictation off (below).
- **Windows opened after the first** (none yet) must call `keepWindowsOutOfScreenshots` too.

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

- Run the app under a network monitor (LuLu is free and open source). The only connections
  should be to the paired phone's local address, and Bonjour's multicast on the local network.
- Take a screenshot (⇧⌘4, then Space, then click the window): the app's window should come out
  blank.
- Right-click a note: only editing commands. The Edit menu: no Writing Tools, AutoFill or Start
  Dictation.
