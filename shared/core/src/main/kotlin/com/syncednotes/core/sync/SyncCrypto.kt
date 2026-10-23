package com.syncednotes.core.sync

import com.syncednotes.core.hkdf
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What sync is built from (Docs/Sync.md, "Cryptography"): P-256 keys sent as
 * SubjectPublicKeyInfo DER, ECDSA with SHA-256, ECDH, HKDF-SHA256 and AES-256-GCM. All of it is
 * in Java's own crypto on both the Mac and Android, so neither needs a library for it.
 */
object SyncCrypto {
    private val random = SecureRandom()

    /** P-256's parameters, taken from a generated key, which works on every Android version. */
    private val p256: ECParameterSpec by lazy { (generate().public as ECPublicKey).params }

    private fun generate() =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** A new long-term identity: the 32-byte private scalar and the SPKI public key. */
    fun newIdentity(): Pair<ByteArray, ByteArray> {
        val pair = generate()
        return scalar((pair.private as ECPrivateKey).s) to pair.public.encoded
    }

    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, privateKey), p256)))
        update(data)
        sign()
    }

    /** False for a bad signature, a malformed key or a key on another curve. */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(publicKey))
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    /** A one-time key pair for one handshake, so a session's keys die with it. */
    class Ephemeral {
        private val pair = generate()
        val publicKey: ByteArray = pair.public.encoded

        /** ECDH with the other side's ephemeral key: the 32-byte shared x coordinate. */
        fun agree(peerPublicKey: ByteArray): ByteArray = KeyAgreement.getInstance("ECDH").run {
            init(pair.private)
            doPhase(publicKey(peerPublicKey), true)
            generateSecret()
        }
    }

    fun hkdf(secret: ByteArray, salt: ByteArray, info: String, length: Int): ByteArray =
        hkdf(secret, salt, info.toByteArray(Charsets.US_ASCII), length)

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)

    /** Parses an SPKI public key, accepting only P-256. */
    fun publicKey(spki: ByteArray): PublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
        require(key.params.curve == p256.curve && key.params.order == p256.order && key.params.generator == p256.generator) { "Not a P-256 key" }
        return key
    }

    /** A private scalar as exactly 32 big-endian bytes. */
    private fun scalar(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)  // a leading sign byte
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }
}

/**
 * AES-256-GCM for one direction of a connection. The nonce is 4 zero bytes and a 64-bit
 * counter both sides keep, so it is never sent; a frame replayed, reordered or altered fails
 * to open.
 */
class FrameCipher(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private var counter = 0L

    fun seal(plaintext: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, plaintext)

    fun open(ciphertext: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, ciphertext)

    private fun run(mode: Int, data: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        counter++
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, nonce))
            doFinal(data)
        }
    }
}
