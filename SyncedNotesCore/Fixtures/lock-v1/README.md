# Lock test files, format version 1

Made-up notes and pictures, locked under a made-up passphrase, exactly as
[`Docs/LockFormat.md`](../../../Docs/LockFormat.md) describes. They guard nothing.

Any app that reads this format must open every file here as `manifest.json` says: the good
items to exactly the content recorded (SHA-256, length, and the text where there is one), and
the `refuse-` items refused, for the reason given. `manifest.json` also holds the passphrase,
the same passphrase with its accent decomposed (which must unlock too), a wrong one (which
must not), and every intermediate key, to pin down which step a reader gets wrong.

**Never edit these by hand.** They are written by the app's own lock code:

```bash
cd SyncedNotesCore && swift run make-fixtures
```

That always writes the same bytes, and a test (`theCommittedTestFilesAreExactlyWhatTheGeneratorWrites`)
fails if what is committed here differs from it. Regenerate only after a deliberate change to
the format, which is a new version and a new folder.
