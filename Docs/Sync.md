# Sync between the Mac and the phone

The Mac and the phone keep the same notes over the local network only. There is no server and
no cloud, Apple's included: the two devices talk to each other directly, and only while both
are on the same network with SyncedNotes open and unlocked. The design is SyncedPass's, adapted
to notes; both apps run the same code for it (`shared/core`, package `sync`), so there is one
implementation of each side, not two.

The messages are Protobuf, defined in
[`sync.proto`](../SyncedNotesCore/Proto/sync.proto). A change to this file is a change to both
apps.

## One vault on both devices

Both devices hold **the same vault** ([`LockFormat.md`](LockFormat.md)): the same header, so
the same passphrase gives the same keys on both. That is what lets a note travel as the
locked item it already is, never opened on the way: the network carries ciphertext inside
ciphertext.

- **Pairing hands the phone the Mac's vault header.** A phone with no notes yet unlocks it
  with the Mac's passphrase and starts from there. A phone that already has notes of its own
  asks for the Mac's passphrase too, and locks its notes again under the Mac's vault, checked
  item by item as for a passphrase change, before anything is sent.
- **The Mac's vault is the one.** Changing the passphrase is done on the Mac. The next time the
  phone connects, the Mac sees the phone's vault ID is another and sends its new header
  (`VaultChanged`) instead of syncing; the phone asks for the new passphrase and locks its notes
  again under it. Until then the two do not sync, and nothing is lost on either side.
- **A device never takes in an item that does not open.** Every note and picture received is
  opened with the vault before anything is written; one that does not open refuses the whole
  message, and the session ends.

## Overview

- **Both sides listen, and both connect.** While its notes are unlocked and it is paired, each
  app announces itself on the local network: the Mac as `_syncednotes._tcp` with Bonjour, the
  phone as `_syncednotes-phone._tcp` with Network Service Discovery, both under the service name
  "SyncedNotes" rather than the device's own name, which anyone on the network can see. Each
  looks for the other and connects when it is not connected yet. Whichever connection gets
  through is used. This keeps sync working when one direction is blocked, for instance while
  the Mac's firewall is still asking about incoming connections.
- **Who speaks first does not depend on who connected.** In every handshake the phone sends the
  first frame and the Mac answers.
- **One connection per pair of devices.** When both connect at once, both keep the one the phone
  opened and close the other.
- **Pairing happens once.** The devices exchange long-term identity keys, and the user checks
  that both screens show the same 6-digit code. From then on they know each other.
- **Every connection is encrypted and mutually authenticated,** with fresh keys each time.
- **Merging is per note and per folder,** by when each last changed.
- **Only what changed travels.** Each side sends a short manifest of IDs and times; the other
  sends back only what wins over it.
- **Local network only.** Both apps refuse connections to or from addresses outside it.

## What is stored

In the locked database (migration `v5: sync`):

- `folder_deletions`: a deleted folder's ID and when it was deleted, as a deleted note keeps its
  row with `deleted` set, so the deletion spreads instead of the folder coming back.
- `sync_identity`: this device's ID, name, and P-256 identity key pair. The private key is only
  ever inside the locked database, so it is only at hand while the notes are unlocked.
- `sync_peers`: each paired device's ID, name, public identity key, and when it was paired.

When each device last synced is kept in memory only.

## Merging

Every note row has `changed`: when anything about it last changed on any device (an edit, a
move, the Trash, its deletion). Every folder has `changed`, or its deletion's time. For one ID,
the record with the later time wins, whole:

- a newer edit replaces an older version;
- a deletion newer than the last change deletes it;
- a change newer than a deletion brings it back;
- on a tie between a record and a deletion, the deletion wins, so both sides decide alike.

Each note and folder is decided on its own: editing one note on the Mac and another on the
phone, even while apart, keeps both edits. The same note edited on both: the later edit wins.

A received note's title and search words are worked out from the note itself once opened, as
when it is saved, so the two devices can never list or find it differently.

**Rows that no longer make sense together are kept as they are and shown sensibly**, so both
devices still agree on the rows:

- a note in a folder that is not on this device (deleted on the other) shows in no folder;
- folders moved into each other on the two devices before they synced, or a folder whose
  parent is gone, show at the top.

Restoring from a backup counts as a change at the moment of the import (LockFormat.md), so it
spreads instead of being undone by a deletion.

The decision relies on the clocks. Devices refuse to sync when theirs differ by more than 5
minutes, and say so. A device also ignores any record dated more than 5 minutes ahead of its own
clock: one device with a wrong clock would otherwise win over every real edit until that date.

## Transport

TCP. Every message is a frame: a 4-byte big-endian length, then the bytes. Handshake frames are
Protobuf in the clear (at most 64 KiB). After the handshake every frame is encrypted (at most
32 MiB):

- AES-256-GCM, a separate key for each direction;
- the nonce is 4 zero bytes then a 64-bit big-endian counter, from 0 for each direction, one up
  per frame, never sent; both sides count;
- the frame is the ciphertext followed by the 16-byte tag, with no additional data.

A frame that fails to open ends the connection.

### Cryptography

- Keys: NIST P-256, sent as SubjectPublicKeyInfo DER. Signatures: ECDSA with SHA-256, DER.
- Key agreement: ECDH, the 32-byte x coordinate. Key derivation: HKDF-SHA256.

Below, `H` is SHA-256, `‖` is concatenation, and `f1`, `f2`, … are the handshake frames exactly
as sent, their 4-byte length included.

## Pairing

The devices agree on keys over the network, then the user checks that both screens show the
same code. A commitment stops someone in the middle from choosing keys that make the codes
match; the chance of an attack going unnoticed is one in a million per attempt.

The Mac always opens the pairing connection, to the phone whose pairing screen is open: such a
phone announces `pairing=1`, and `name=` with its own name, for as long as that screen is open,
and the Mac's Pair a Phone window lists phones by that name. One attempt per opening of either
screen: any failure ends pairing on screen, so nobody can retry unseen until a code matches.

1. Phone → Mac, `f1`: `Opening { pair { protocol: 1, commitment: H(f3) } }`
2. Mac → Phone, `f2`: `Hello { device_id, device_name, identity_key, ephemeral_key, nonce, time }`
3. Phone → Mac, `f3`: the phone's `Hello`. The Mac checks `H(f3) == commitment`.
4. Both compute `s = ECDH(ephemeral keys)` and `salt = H("SyncedNotes pair v1" ‖ f1 ‖ f2 ‖ f3)`:
   - `code = HKDF(s, salt, "SyncedNotes pair v1 code", 4)`, read as a big-endian unsigned
     integer modulo 1,000,000, shown as 6 digits;
   - `HKDF(s, salt, "SyncedNotes pair v1 phone to mac", 32)` and `… mac to phone`, for the
     encrypted frames from here on.
5. Both show the code. When the user confirms, the device sends the encrypted
   `PairConfirm { accepted: true, signature: Sign(identity, H("SyncedNotes pair v1 confirm phone" ‖ salt)) }`
   (`… confirm mac` from the Mac, which also sends its `vault_header`). Cancelling sends
   `accepted: false`. A device refuses a peer that presents its own identity key or device ID.
6. A device keeps the other as a peer only after its own user confirmed and the other's
   confirmation came with a valid signature from the identity key in `f2` or `f3`. The phone
   then joins the Mac's vault if it is not already in it (see above).

## Sync sessions

Either side connects; the phone speaks first:

1. Phone → Mac, `f1`: `Opening { sync { protocol, device_id, ephemeral_key, nonce, time, vault_id } }`
2. Mac → Phone, `f2`: `SyncReply { device_id, ephemeral_key, nonce, time, vault_id }`
3. Mac → Phone, `f3`: `SignatureFrame { Sign(Mac identity, H("SyncedNotes sync v1 mac" ‖ f1 ‖ f2)) }`
4. Phone → Mac, `f4`: `SignatureFrame { Sign(phone identity, H("SyncedNotes sync v1 phone" ‖ f1 ‖ f2)) }`

Each side checks that the other's device ID is a paired peer and that the signature verifies
with that peer's key; otherwise it closes. With `s = ECDH(ephemeral keys)` and
`salt = H(f1 ‖ f2)`, the phone sends with `HKDF(s, salt, "SyncedNotes sync v1 phone to mac", 32)`
and the Mac with `… mac to phone`. When the vault IDs differ, the Mac sends `VaultChanged` and
closes.

Then both sides send encrypted `Message`s:

- `manifest`: one `Stamp { id, changed, deleted }` per note and per folder, and the IDs of the
  pictures held. Sent when the session starts, after any change to the notes (here, or taken in
  from another device), and every minute.
- `records`: the reply to a manifest, with only the notes, folders and pictures that win over
  it or that it lacks, folders first, in messages of about 4 MiB at most. Not sent when there
  is nothing to send. A note travels as its locked item with its dates, Trash time and folder;
  a picture as its locked item.
- `ping`: every 25 seconds; a connection with nothing received for 70 seconds is closed.

When a device receives a manifest, it replies with what the sender needs; if the manifest shows
the sender has something it needs, it also replies with its own manifest, so the sender sends
it at once. Records are taken in as one transaction, and if anything changed the device's
manifest goes to every connected device, which spreads a change from one phone to another
through the Mac. This always ends: a device only sends what the other does not have yet.

## Local network only

- Both sides accept connections only from, and connect only to, local addresses: loopback,
  private IPv4 (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10) and unique local
  IPv6 (fc00::/7).
- Each side listens only while its notes are unlocked, and only if it is paired or its pairing
  screen is open.
- The phone app has Android's `INTERNET` permission, which Android requires for any socket,
  local ones included. It connects nowhere else. The Mac app has the sandbox's network client
  and server entitlements, for the same reason.
