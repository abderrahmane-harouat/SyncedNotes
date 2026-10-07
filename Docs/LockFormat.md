# The lock format

How notes and pictures are locked, byte for byte. Every note on disk and every backup is in
this format, and the test files in `SyncedNotesCore/Fixtures/` are written by the lock code to
pin it down: a reader written from this file alone must open them. Nothing here may change
silently: a change is a new format version.

It is written for more than one device holding the same vault, so that a second device of
the user's could one day read the notes; today only the Mac app reads and writes it.

## Overview

- One **vault** per person: a passphrase and a random salt. Both devices hold the same vault,
  so the same passphrase opens both.
- The passphrase and the salt make the **master key** (Argon2id). This is the only slow step,
  done once per unlock.
- The master key makes separate **subkeys** for notes, pictures, backups and the database
  (HKDF), so no key is ever used for two purposes.
- Each note and each picture is a **locked item**: sealed on its own with AES-256-GCM. A locked
  item is exactly what is stored on a device and exactly what travels to the other.

All numbers are little-endian. Sizes are in bytes.

## The vault header

Stored once per device, next to the database. Holds nothing secret, but is needed to unlock:
a second device would receive it the first time the two connect, and from then on use the
same salt, so the same passphrase gives the same keys on both.

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 4 | magic | ASCII `SNV1` |
| 4 | 1 | version | `1` |
| 5 | 16 | vault ID | random, names this vault |
| 21 | 4 | Argon2id memory | in KiB: `19456` (19 MiB) |
| 25 | 4 | Argon2id passes | `2` |
| 29 | 4 | Argon2id lanes | `1` |
| 33 | 16 | salt | random |
| 49 | 12 | check nonce | random |
| 61 | 32 | check | the 16 ASCII bytes `SyncedNotes-OK-1`, sealed with the notes key (16 bytes of ciphertext, then the 16-byte tag), with the header's first 61 bytes as additional data |

**Unlocking:** derive the master key from the passphrase with the header's salt and settings,
derive the notes key, and open `check`. If it does not open, the passphrase is wrong: stop,
and never try to open or write anything with that key. A wrong passphrase fails loudly
here, before any note is touched.

## Keys

**Master key:** Argon2id (RFC 9106, version 0x13) over the passphrase, with the header's
salt, memory, passes and lanes, giving 32 bytes. No secret key or extra data.

The passphrase is first normalised to Unicode **NFC**, then encoded as UTF-8; nothing is
trimmed. Keyboards differ in how they produce the same letter (é can arrive as one character
or as e plus a combining accent, and Arabic has similar cases), so without normalising, a
passphrase typed on one keyboard and the same one typed on another could be different bytes,
and the right passphrase would be refused. Swift (`precomposedStringWithCanonicalMapping`)
and Java (`java.text.Normalizer`, form NFC) give the same result.

**Subkeys:** HKDF-SHA256 (RFC 5869), with the master key as input key material, the vault ID
as salt, and one of these ASCII strings as info, giving 32 bytes each:

| Info | Used for |
|---|---|
| `syncednotes notes v1` | locked notes, and the header's `check` |
| `syncednotes pictures v1` | locked pictures |
| `syncednotes database v1` | the whole-file lock on each device's database (SQLCipher raw key) |
| `syncednotes backups v1` | a backup's single locked item |

## A locked item

A note or a picture, locked on its own.

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 4 | magic | ASCII `SNL1` |
| 4 | 1 | version | `1` |
| 5 | 1 | kind | `1` note, `2` picture, `3` backup |
| 6 | 16 | vault ID | the vault it belongs to |
| 22 | 16 | item ID | the note's or picture's own ID |
| 38 | 12 | nonce | random, new every time the item is locked |
| 50 | n | ciphertext | AES-256-GCM of the content, with the kind's subkey |
| 50 + n | 16 | tag | GCM authentication tag |

The first 50 bytes, the header, are the GCM **additional data**. They are not secret, but they
cannot be changed without the item failing to open. So a locked note cannot be passed off as a
different note, a picture as a note, or an item as belonging to another vault.

**Content:** for a note, the note in its Protobuf format (`Note` in [`note.proto`](../SyncedNotesCore/Proto/note.proto));
for a picture, the picture file's own bytes, unchanged; for a backup, a Protobuf `Backup`.

**Opening:** check the magic, the version, that the kind is known, and that the vault ID is
this vault's; then open it with the kind's subkey. If any check fails or the tag does not
verify, the item is refused whole: never partial content, and nothing written over a good
copy with it.

## A backup file

Every note that is not deleted, with the pictures they show, in one file (`.snbackup`).

| Offset | Size | Field | Value |
|---|---|---|---|
| 0 | 4 | magic | ASCII `SNB1` |
| 4 | 1 | version | `1` |
| 5 | 93 | vault header | the header of the vault it was made in, exactly as above |
| 98 | n | locked item | kind `3`, sealed with the backups subkey; its item ID is random |

The locked item holds a Protobuf `Backup` (see `note.proto`): every folder, each note with its ID and folder, when it
was created and last changed, and its content; and each picture's ID and bytes.

Sealed as a single item, so nothing inside can be read without the passphrase: not the notes,
not their dates, not how many there are. Changing any byte refuses the whole file.

**Opening:** read the vault header. If it is this device's own vault, its keys open the item;
otherwise the vault is unlocked with the passphrase it was made with, as above. **Restoring:**
each note is locked again under this device's own vault. Importing a backup is asking for its
notes back, so a note deleted here comes back. Every restored note keeps the backup's dates
(when it was created and last edited); only the device's own record of when the note last
changed, which sync compares, is set to the moment of the import, so the note counts as newer
than any record of its deletion, on either device. A note still here that was changed after the
backup was made keeps its newer version.

## Changing the passphrase

Every key comes from the passphrase, so a new passphrase is a **new vault**: a new salt and a
new vault ID, with every note and picture opened and locked again under it, and the database
locked again (SQLCipher's `sqlcipher_export` into a new file, as its makers recommend over
re-keying in place). A new ID, not the old one: a backup made before the change names the old
vault, so it is recognised as another vault and asks for the passphrase it was made with.

On the Mac nothing is changed in place. The new vault is written into a folder beside the
notes, opened there with the new passphrase and compared with the notes item by item; only
then does it take their place, and the old folder is deleted. Stopped part way, the next launch
finishes a change that was checked and drops one that was not. When sync comes, the other
device has to be given the new vault header, as on first pairing.

## Choices and why

- **AES-256-GCM, 12-byte random nonces.** Built into both platforms (CryptoKit, and Java's
  crypto on every other platform). A random 12-byte nonce is safe for billions of items under one key.
- **Argon2id at 19 MiB, 2 passes, 1 lane.** OWASP's recommended minimum; 0.02 s per unlock on
  the Mac. Chosen for speed over 64 MiB (0.13 s). Recorded in the header, so it can be raised
  for a vault without touching its items.
- **One salt per vault, not per item.** Argon2id is deliberately slow; a salt per item would
  mean running it once per note, turning a 0.02 s unlock into seconds.
- **Subkeys through HKDF.** The same key is never used for two jobs, and the database's key is
  not the one that locks notes that travel.
- **IDs in the clear, bound by the additional data.** A device must know which note an item is
  before it can store it; binding the header to the ciphertext stops anyone swapping them.
- **Little-endian, fixed offsets.** Nothing to parse loosely, so the two apps cannot read the
  same bytes differently.
