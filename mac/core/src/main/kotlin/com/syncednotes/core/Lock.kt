package com.syncednotes.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.HKDFParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

// The lock format, exactly as Docs/LockFormat.md describes it. The notes on disk, the
// backups and the test files all depend on it, so anything changed here must change there
// too, as a new format version. It is the Swift app's Lock.swift, byte for byte, and the test
// files that Swift code wrote are what proves it.

/**
 * Why something could not be unlocked or opened. Every failure refuses the whole thing: there
 * is never partial content.
 */
class LockException(val reason: Reason, detail: String? = null) : Exception(detail ?: reason.name) {
    enum class Reason {
        NOT_A_VAULT_HEADER,
        NOT_A_LOCKED_ITEM,
        UNSUPPORTED_VERSION,
        /** Settings this build cannot use, such as more than one Argon2id lane. */
        UNSUPPORTED_SETTINGS,
        WRONG_PASSPHRASE,
        WRONG_VAULT,
        WRONG_KIND,
        UNKNOWN_KIND,
        /** The tag did not verify: the item was altered, cut short, or damaged. */
        DAMAGED,
    }
}

enum class ItemKind(val code: Byte) {
    NOTE(1),
    PICTURE(2),
    /** A whole backup: every note and picture, sealed as one (see [Backup]). */
    BACKUP(3);

    companion object {
        fun of(code: Byte): ItemKind? = entries.firstOrNull { it.code == code }
    }
}

/** How hard the passphrase is to guess. See "Choices and why" in LockFormat.md. */
data class KeyDerivationSettings(val memoryKiB: Int, val passes: Int, val lanes: Int) {
    companion object {
        /** OWASP's recommended minimum, chosen for speed: 0.02 s per unlock on the Mac. */
        val STANDARD = KeyDerivationSettings(memoryKiB = 19_456, passes = 2, lanes = 1)
    }
}

/**
 * Where random bytes come from. The system's, except in tests that need the same bytes every
 * time.
 */
fun interface RandomBytes {
    fun next(count: Int): ByteArray

    companion object {
        private val secure = SecureRandom()
        val SYSTEM = RandomBytes { count -> ByteArray(count).also(secure::nextBytes) }
    }
}

// --- The vault header ---------------------------------------------------------------------

/**
 * Not secret, but needed to unlock: the vault's ID, the Argon2id settings and salt, and a
 * check that only the right passphrase opens.
 */
class VaultHeader internal constructor(
    val id: ByteArray,
    val settings: KeyDerivationSettings,
    val salt: ByteArray,
    internal val checkNonce: ByteArray,
    internal val check: ByteArray,
) {
    /** The bytes before `check`: the additional data that `check` is sealed with. */
    internal val authenticatedPart: ByteArray
        get() = ByteBuffer.allocate(61).order(ByteOrder.LITTLE_ENDIAN)
            .put(MAGIC).put(VERSION).put(id)
            .putInt(settings.memoryKiB).putInt(settings.passes).putInt(settings.lanes)
            .put(salt).put(checkNonce)
            .array()

    val encoded: ByteArray get() = authenticatedPart + check

    override fun equals(other: Any?): Boolean = other is VaultHeader && encoded.contentEquals(other.encoded)
    override fun hashCode(): Int = encoded.contentHashCode()

    companion object {
        internal val MAGIC = "SNV1".toByteArray(Charsets.US_ASCII)
        internal const val VERSION: Byte = 1
        const val SIZE = 93
        internal val CHECK_PHRASE = "SyncedNotes-OK-1".toByteArray(Charsets.US_ASCII)

        fun decode(bytes: ByteArray): VaultHeader {
            if (bytes.size != SIZE || !bytes.startsWith(MAGIC)) throw LockException(LockException.Reason.NOT_A_VAULT_HEADER)
            if (bytes[4] != VERSION) throw LockException(LockException.Reason.UNSUPPORTED_VERSION, "Vault header version ${bytes[4]}")
            val numbers = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return VaultHeader(
                id = bytes.copyOfRange(5, 21),
                settings = KeyDerivationSettings(numbers.getInt(21), numbers.getInt(25), numbers.getInt(29)),
                salt = bytes.copyOfRange(33, 49),
                checkNonce = bytes.copyOfRange(49, 61),
                check = bytes.copyOfRange(61, 93),
            )
        }
    }
}

// --- The vault, unlocked ------------------------------------------------------------------

/**
 * An unlocked vault: its header and the keys made from the passphrase. Held only in memory,
 * for as long as the app is open.
 */
class Vault private constructor(val header: VaultHeader, private val keys: Keys) {
    /** The raw key for the database's whole-file lock. */
    val databaseKey: ByteArray get() = keys.database.copyOf()

    /**
     * Every key, for the tests only: compared with the ones recorded beside the test files, so
     * a wrong step shows as the step it is.
     */
    internal val keysForTests: Map<String, ByteArray>
        get() = mapOf("master" to keys.master, "notes" to keys.notes, "pictures" to keys.pictures, "database" to keys.database)

    /** Locks [content] as an item of [kind] with ID [id] (16 bytes). */
    fun lock(content: ByteArray, kind: ItemKind, id: ByteArray, random: RandomBytes = RandomBytes.SYSTEM): ByteArray {
        require(id.size == 16) { "An item ID is 16 bytes." }
        val nonce = random.next(12)
        val itemHeader = LockedItem.MAGIC + byteArrayOf(LockedItem.VERSION, kind.code) + header.id + id + nonce
        return itemHeader + Gcm.seal(content, keyFor(kind), nonce, itemHeader)
    }

    /** Opens a locked item, refusing it whole if anything about it is wrong. */
    fun open(item: ByteArray, expecting: ItemKind? = null): LockedItem {
        if (item.size < LockedItem.HEADER_SIZE + 16 || !item.startsWith(LockedItem.MAGIC)) {
            throw LockException(LockException.Reason.NOT_A_LOCKED_ITEM)
        }
        if (item[4] != LockedItem.VERSION) throw LockException(LockException.Reason.UNSUPPORTED_VERSION, "Locked item version ${item[4]}")
        val kind = ItemKind.of(item[5]) ?: throw LockException(LockException.Reason.UNKNOWN_KIND, "Item kind ${item[5]}")
        if (expecting != null && expecting != kind) throw LockException(LockException.Reason.WRONG_KIND, "Expected $expecting, found $kind")
        if (!item.copyOfRange(6, 22).contentEquals(header.id)) throw LockException(LockException.Reason.WRONG_VAULT)
        val aad = item.copyOfRange(0, LockedItem.HEADER_SIZE)
        val content = Gcm.open(item.copyOfRange(LockedItem.HEADER_SIZE, item.size), keyFor(kind), item.copyOfRange(38, 50), aad)
            ?: throw LockException(LockException.Reason.DAMAGED)
        return LockedItem(kind, item.copyOfRange(22, 38), content)
    }

    private fun keyFor(kind: ItemKind): ByteArray = when (kind) {
        ItemKind.NOTE -> keys.notes
        ItemKind.PICTURE -> keys.pictures
        ItemKind.BACKUP -> keys.backups
    }

    companion object {
        /** A new vault for [passphrase], with a new ID and salt. */
        fun create(
            passphrase: String,
            settings: KeyDerivationSettings = KeyDerivationSettings.STANDARD,
            random: RandomBytes = RandomBytes.SYSTEM,
        ): Vault {
            val id = random.next(16)
            val salt = random.next(16)
            val checkNonce = random.next(12)
            val keys = Keys(passphrase, id, settings, salt)
            val unsealed = VaultHeader(id, settings, salt, checkNonce, ByteArray(0))
            val check = Gcm.seal(VaultHeader.CHECK_PHRASE, keys.notes, checkNonce, unsealed.authenticatedPart)
            return Vault(VaultHeader(id, settings, salt, checkNonce, check), keys)
        }

        /**
         * Unlocks [header] with [passphrase]. A wrong passphrase is caught here, by the check,
         * before any note is touched.
         */
        fun unlock(header: VaultHeader, passphrase: String): Vault {
            val keys = Keys(passphrase, header.id, header.settings, header.salt)
            val phrase = Gcm.open(header.check, keys.notes, header.checkNonce, header.authenticatedPart)
            if (phrase == null || !phrase.contentEquals(VaultHeader.CHECK_PHRASE)) throw LockException(LockException.Reason.WRONG_PASSPHRASE)
            return Vault(header, keys)
        }
    }
}

/** What an opened item holds. */
class LockedItem(val kind: ItemKind, val id: ByteArray, val content: ByteArray) {
    companion object {
        internal val MAGIC = "SNL1".toByteArray(Charsets.US_ASCII)
        internal const val VERSION: Byte = 1
        internal const val HEADER_SIZE = 50
    }
}

// --- Keys ---------------------------------------------------------------------------------

/** The master key and the subkeys made from it. */
internal class Keys(passphrase: String, vaultId: ByteArray, settings: KeyDerivationSettings, salt: ByteArray) {
    val master = masterKey(passphrase, settings, salt)
    val notes = subkey("syncednotes notes v1", vaultId)
    val pictures = subkey("syncednotes pictures v1", vaultId)
    val database = subkey("syncednotes database v1", vaultId)
    val backups = subkey("syncednotes backups v1", vaultId)

    private fun subkey(info: String, vaultId: ByteArray): ByteArray {
        val spec = HKDFParameterSpec.ofExtract()
            .addIKM(SecretKeySpec(master, "HKDF"))
            .addSalt(vaultId)
            .thenExpand(info.toByteArray(Charsets.US_ASCII), 32)
        return KDF.getInstance("HKDF-SHA256").deriveData(spec)
    }

    companion object {
        /**
         * Argon2id over the passphrase in Unicode NFC. Keyboards produce the same letter in
         * different ways; without normalising, the right passphrase typed on another keyboard
         * could be different bytes from the same one typed on the Mac, and be refused.
         */
        fun masterKey(passphrase: String, settings: KeyDerivationSettings, salt: ByteArray): ByteArray {
            // The Swift app used libsodium, whose Argon2id always runs one lane; a vault made
            // with more could not be opened there, so it is not made or opened here either.
            if (settings.lanes != 1) throw LockException(LockException.Reason.UNSUPPORTED_SETTINGS, "${settings.lanes} lanes")
            val normalised = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(settings.memoryKiB)
                .withIterations(settings.passes)
                .withParallelism(settings.lanes)
                .withSalt(salt)
                .build()
            val out = ByteArray(32)
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(normalised, out)
            return out
        }
    }
}

/** AES-256-GCM with a 12-byte nonce and a 16-byte tag after the ciphertext. */
internal object Gcm {
    fun seal(plain: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    /** Null when the tag does not verify: never any of the content. */
    fun open(sealed: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(sealed)
    } catch (_: GeneralSecurityException) {
        null
    }
}

internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
