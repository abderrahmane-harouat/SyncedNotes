package com.syncednotes.core

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import syncednotes.note.v1.BackupNote
import syncednotes.note.v1.Note
import syncednotes.note.v1.Paragraph
import syncednotes.note.v1.Backup as StoredBackup

/**
 * The lock against the test files the Swift app's lock code wrote: if this lock opens every
 * one of them as the manifest says, it reads what the Mac has on disk and in every backup.
 */
class LockTest {
    private val dir = File(System.getProperty("syncednotes.fixtures"))
    private val manifest = Json.parseToJsonElement(File(dir, "manifest.json").readText()).jsonObject
    private val passphrase = manifest.string("passphrase")
    private val header by lazy { VaultHeader.decode(File(dir, "vault.bin").readBytes()) }
    private val vault by lazy { Vault.unlock(header, passphrase) }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private val items get() = manifest.getValue("items").jsonArray.map { it.jsonObject }

    @Test
    fun theVaultHeaderReadsAsTheManifestRecordsIt() {
        val recorded = manifest.getValue("vault").jsonObject
        assertEquals(recorded.string("id"), hex(header.id))
        assertEquals(recorded.string("salt"), hex(header.salt))
        assertEquals(
            KeyDerivationSettings(recorded.getValue("memoryKiB").jsonPrimitive.int, recorded.getValue("passes").jsonPrimitive.int, recorded.getValue("lanes").jsonPrimitive.int),
            header.settings,
        )
        assertContentEquals(File(dir, "vault.bin").readBytes(), header.encoded)
    }

    @Test
    fun theRightPassphraseMakesEveryKeyTheTestFilesRecord() {
        val recorded = manifest.getValue("vault").jsonObject.getValue("keys").jsonObject
        for ((name, bytes) in vault.keysForTests) assertEquals(recorded.string(name), hex(bytes), "the $name key")
    }

    @Test
    fun theSamePassphraseWithItsAccentDecomposedUnlocksToo() {
        val decomposed = manifest.string("passphraseDecomposed")
        assert(decomposed != passphrase) { "the manifest's two passphrases should differ in their bytes" }
        assertContentEquals(vault.databaseKey, Vault.unlock(header, decomposed).databaseKey)
    }

    @Test
    fun aWrongPassphraseIsRefusedBeforeAnythingIsOpened() {
        val e = assertFailsWith<LockException> { Vault.unlock(header, manifest.string("wrongPassphrase")) }
        assertEquals(LockException.Reason.WRONG_PASSPHRASE, e.reason)
    }

    @Test
    fun everyGoodTestFileOpensToExactlyItsRecordedContent() {
        val good = items.filter { it.getValue("opens").jsonPrimitive.boolean }
        assertEquals(5, good.size)
        for (item in good) {
            val opened = vault.open(File(dir, item.string("file")).readBytes())
            assertEquals(item.string("kind"), opened.kind.name.lowercase(), item.string("file"))
            assertEquals(item.string("id"), hex(opened.id), item.string("file"))
            assertEquals(item.getValue("contentLength").jsonPrimitive.int, opened.content.size, item.string("file"))
            assertEquals(item.string("contentSha256"), sha256(opened.content), item.string("file"))
            item["contentUtf8"]?.let { assertEquals(it.jsonPrimitive.content, opened.content.toString(Charsets.UTF_8)) }
        }
    }

    @Test
    fun everyRefuseFileIsRefusedForItsRecordedReason() {
        val refused = items.filter { !it.getValue("opens").jsonPrimitive.boolean }
        assertEquals(4, refused.size)
        for (item in refused) {
            val e = assertFailsWith<LockException>(item.string("file")) { vault.open(File(dir, item.string("file")).readBytes()) }
            val expected = when (item.string("refusedAs")) {
                "damaged" -> LockException.Reason.DAMAGED
                "wrongVault" -> LockException.Reason.WRONG_VAULT
                else -> error("A reason this test does not know: ${item.string("refusedAs")}")
            }
            assertEquals(expected, e.reason, item.string("file"))
        }
    }

    @Test
    fun aNoteCannotBeOpenedAsAPicture() {
        val e = assertFailsWith<LockException> { vault.open(File(dir, "note-plain.snl").readBytes(), expecting = ItemKind.PICTURE) }
        assertEquals(LockException.Reason.WRONG_KIND, e.reason)
    }

    @Test
    fun whatThisLockLocksTheTestVaultOpensAgain() {
        val id = ByteArray(16) { it.toByte() }
        val locked = vault.lock("made up".toByteArray(), ItemKind.NOTE, id)
        val opened = Vault.unlock(header, passphrase).open(locked, expecting = ItemKind.NOTE)
        assertContentEquals("made up".toByteArray(), opened.content)
        assertContentEquals(id, opened.id)
    }

    @Test
    fun aNewVaultUnlocksWithItsOwnPassphraseOnly() {
        val made = Vault.create("another made-up passphrase")
        val reread = VaultHeader.decode(made.header.encoded)
        assertContentEquals(made.databaseKey, Vault.unlock(reread, "another made-up passphrase").databaseKey)
        assertFailsWith<LockException> { Vault.unlock(reread, "another made-up passphras") }
    }

    @Test
    fun aBackupOpensInItsOwnVaultAndNoOther() {
        val backup = StoredBackup.newBuilder().setFormatVersion(1).addNotes(
            BackupNote.newBuilder().setId(com.google.protobuf.ByteString.copyFrom(ByteArray(16))).setCreated(1.0).setUpdated(2.0)
                .setNote(Note.newBuilder().setFormatVersion(1).addParagraphs(Paragraph.newBuilder().setText("made-up backup note"))),
        ).build()
        val file = Backup.make(backup, vault)
        assertEquals(header, Backup.vaultHeader(file))
        assertEquals(backup, Backup.open(file, vault))
        val e = assertFailsWith<LockException> { Backup.open(file, Vault.create("made-up other vault")) }
        assertEquals(LockException.Reason.WRONG_VAULT, e.reason)
        val damaged = file.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(LockException.Reason.DAMAGED, assertFailsWith<LockException> { Backup.open(damaged, vault) }.reason)
    }
}
