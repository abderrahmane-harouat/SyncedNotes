package com.syncednotes.core.sync

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.MessageLite
import com.syncednotes.core.Id
import com.syncednotes.core.MAX_CLOCK_SKEW_SECONDS
import com.syncednotes.core.SyncIdentity
import com.syncednotes.core.SyncPeer
import com.syncednotes.core.VaultHeader
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlin.math.abs
import syncednotes.sync.v1.Hello
import syncednotes.sync.v1.Manifest
import syncednotes.sync.v1.Message
import syncednotes.sync.v1.Opening
import syncednotes.sync.v1.PairConfirm
import syncednotes.sync.v1.PairRequest
import syncednotes.sync.v1.Ping
import syncednotes.sync.v1.Records
import syncednotes.sync.v1.SignatureFrame
import syncednotes.sync.v1.SyncReply
import syncednotes.sync.v1.SyncRequest
import syncednotes.sync.v1.VaultChanged

// The wire half of Docs/Sync.md: frames, pairing, and opening an authenticated, encrypted
// session. Both sides are here, since the Mac and the phone run this same code; which side
// says what is fixed by [SyncRole], never by who opened the connection.

const val SYNC_PROTOCOL = 1
private const val MAX_HANDSHAKE_FRAME = 64 * 1024
private const val MAX_FRAME = 32 * 1024 * 1024

enum class SyncRole { PHONE, MAC }

sealed class SyncException(message: String) : Exception(message) {
    class NotPaired : SyncException("This device is not paired with the other one.")
    class AuthenticationFailed : SyncException("The other device could not prove it is the one you paired with.")
    class Cancelled : SyncException("Pairing was cancelled.")
    class ClockSkew(val seconds: Long) :
        SyncException("The clocks on the phone and the Mac differ by ${seconds / 60} minutes. Set both to the correct time to sync.")
    class Protocol(detail: String) : SyncException("Unexpected reply from the other device ($detail).")
    /** The Mac's notes are locked with another vault than the phone's: its passphrase was changed. */
    class VaultChanged(val header: VaultHeader) :
        SyncException("The passphrase was changed on the Mac. Enter the new one to keep syncing.")
}

/** Length-prefixed frames over a stream: 4 bytes of length, big-endian, then the bytes. */
class FrameStream(input: InputStream, private val output: OutputStream) {
    private val input = DataInputStream(input)

    /** Writes a frame and returns it as sent, length included, for handshake transcripts. */
    fun write(payload: ByteArray): ByteArray {
        val frame = frameOf(payload)
        synchronized(output) {
            output.write(frame)
            output.flush()
        }
        return frame
    }

    /** Reads one frame; returns the payload and the frame as received, length included. */
    fun read(maxBytes: Int): Pair<ByteArray, ByteArray> {
        val length = try { input.readInt() } catch (e: EOFException) { throw SyncException.Protocol("connection closed") }
        if (length < 0 || length > maxBytes) throw SyncException.Protocol("frame of $length bytes")
        val payload = ByteArray(length).also { input.readFully(it) }
        return payload to frameOf(payload)
    }
}

private fun frameOf(payload: ByteArray): ByteArray {
    val n = payload.size
    return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + payload
}

/** Encrypted, authenticated messages once a handshake is done; one key for each direction. */
class SecureChannel(private val frames: FrameStream, sendKey: ByteArray, receiveKey: ByteArray) {
    private val sender = FrameCipher(sendKey)
    private val receiver = FrameCipher(receiveKey)

    fun send(bytes: ByteArray) {
        // The counter and the write must happen in the same order.
        synchronized(sender) { frames.write(sender.seal(bytes)) }
    }

    /** Reads and opens the next frame. Only one thread may read. */
    fun receive(maxBytes: Int = MAX_FRAME): ByteArray {
        val (payload, _) = frames.read(maxBytes)
        return try {
            receiver.open(payload)
        } catch (e: Exception) {
            throw SyncException.AuthenticationFailed()
        }
    }

    fun send(message: Message) = send(message.toByteArray())
    fun sendManifest(manifest: Manifest) = send(Message.newBuilder().setManifest(manifest).build())
    fun sendRecords(records: Records) = send(Message.newBuilder().setRecords(records).build())
    fun sendPing() = send(Message.newBuilder().setPing(Ping.getDefaultInstance()).build())
    fun sendVaultChanged(header: VaultHeader) =
        send(Message.newBuilder().setVaultChanged(VaultChanged.newBuilder().setVaultHeader(header.encoded.bytes)).build())

    fun receiveMessage(): Message = parse(receive()) { Message.parseFrom(it) }
}

/** A pairing waiting for both people to compare the code. */
class Pairing internal constructor(
    val role: SyncRole,
    /** The 6-digit code both screens show. */
    val code: String,
    /** The other device as it described itself; saved as a peer once both sides confirm. */
    val other: Hello,
    private val channel: SecureChannel,
    private val transcript: ByteArray,
    private val identity: SyncIdentity,
) {
    private val mine = if (role == SyncRole.PHONE) "phone" else "mac"
    private val theirs = if (role == SyncRole.PHONE) "mac" else "phone"

    /**
     * Tells the other device whether this one's user confirmed that the codes match. The Mac
     * sends its [vaultHeader] with it: what the phone needs to open the same notes.
     */
    fun confirm(accepted: Boolean, vaultHeader: VaultHeader? = null) {
        val confirm = PairConfirm.newBuilder().setAccepted(accepted)
        if (accepted) {
            confirm.signature = SyncCrypto.sign(identity.privateKey, confirmation(mine, transcript)).bytes
            vaultHeader?.let { confirm.vaultHeader = it.encoded.bytes }
        }
        channel.send(confirm.build().toByteArray())
    }

    /**
     * Waits for the other device's user. Returns the new peer and, from the Mac, its vault
     * header; throws if they cancelled or the signature is wrong.
     */
    fun awaitOther(): Pair<SyncPeer, VaultHeader?> {
        val reply = parse(channel.receive(MAX_HANDSHAKE_FRAME)) { PairConfirm.parseFrom(it) }
        if (!reply.accepted) throw SyncException.Cancelled()
        if (!SyncCrypto.verify(other.identityKey.toByteArray(), confirmation(theirs, transcript), reply.signature.toByteArray())) {
            throw SyncException.AuthenticationFailed()
        }
        val header = if (role == SyncRole.PHONE) {
            try {
                VaultHeader.decode(reply.vaultHeader.toByteArray())
            } catch (e: Exception) {
                throw SyncException.Protocol("vault header")
            }
        } else null
        return SyncPeer(other.deviceId, other.deviceName, other.identityKey, Instant.now()) to header
    }
}

/** A session's encrypted channel, with whom, and the vault the other side's notes are in. */
class OpenSession(val channel: SecureChannel, val peer: SyncPeer, val theirVault: Id)

object SyncHandshake {
    /**
     * Pairing as the phone, steps 1–4 of Docs/Sync.md, "Pairing": on a connection the Mac
     * opened from its Pair a Phone window.
     */
    fun pairAsPhone(frames: FrameStream, identity: SyncIdentity): Pairing {
        val ephemeral = SyncCrypto.Ephemeral()
        val hello = hello(identity, ephemeral).toByteArray()
        // f3 is sent last but committed to first, so the Mac's keys cannot be chosen to match it.
        val f3 = frameOf(hello)
        val f1 = frames.write(
            Opening.newBuilder().setPair(PairRequest.newBuilder().setProtocol(SYNC_PROTOCOL).setCommitment(SyncCrypto.sha256(f3).bytes)).build().toByteArray(),
        )
        val (f2Payload, f2) = frames.read(MAX_HANDSHAKE_FRAME)
        val mac = parse(f2Payload) { Hello.parseFrom(it) }
        checkOther(mac, identity)
        frames.write(hello)
        checkClock(mac.time)
        return pairing(SyncRole.PHONE, ephemeral, mac, f1, f2, f3, frames, identity)
    }

    /** Pairing as the Mac, on a connection it opened to a phone that is ready to pair. */
    fun pairAsMac(frames: FrameStream, identity: SyncIdentity): Pairing {
        val (f1Payload, f1) = frames.read(MAX_HANDSHAKE_FRAME)
        val opening = parse(f1Payload) { Opening.parseFrom(it) }
        if (!opening.hasPair()) throw SyncException.Protocol("expected pairing")
        if (opening.pair.protocol != SYNC_PROTOCOL) throw SyncException.Protocol("protocol ${opening.pair.protocol}")
        val ephemeral = SyncCrypto.Ephemeral()
        val f2 = frames.write(hello(identity, ephemeral).toByteArray())
        val (f3Payload, f3) = frames.read(MAX_HANDSHAKE_FRAME)
        if (!SyncCrypto.sha256(f3).contentEquals(opening.pair.commitment.toByteArray())) throw SyncException.AuthenticationFailed()
        val phone = parse(f3Payload) { Hello.parseFrom(it) }
        checkOther(phone, identity)
        checkClock(phone.time)
        return pairing(SyncRole.MAC, ephemeral, phone, f1, f2, f3, frames, identity)
    }

    private fun pairing(
        role: SyncRole, ephemeral: SyncCrypto.Ephemeral, other: Hello,
        f1: ByteArray, f2: ByteArray, f3: ByteArray, frames: FrameStream, identity: SyncIdentity,
    ): Pairing {
        val secret = ephemeral.agree(other.ephemeralKey.toByteArray())
        val salt = SyncCrypto.sha256("SyncedNotes pair v1".toByteArray(), f1, f2, f3)
        val codeBytes = SyncCrypto.hkdf(secret, salt, "SyncedNotes pair v1 code", 4)
        val code = (codeBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) } % 1_000_000).toString().padStart(6, '0')
        val phoneToMac = SyncCrypto.hkdf(secret, salt, "SyncedNotes pair v1 phone to mac", 32)
        val macToPhone = SyncCrypto.hkdf(secret, salt, "SyncedNotes pair v1 mac to phone", 32)
        val channel = channel(role, frames, phoneToMac, macToPhone)
        listOf(secret, phoneToMac, macToPhone).forEach { it.fill(0) }
        return Pairing(role, code, other, channel, salt, identity)
    }

    /** Opens a session as the phone (Docs/Sync.md, "Sync sessions"), whoever connected. */
    fun sessionAsPhone(frames: FrameStream, identity: SyncIdentity, vaultId: Id, peers: List<SyncPeer>): OpenSession {
        val ephemeral = SyncCrypto.Ephemeral()
        val f1 = frames.write(
            Opening.newBuilder().setSync(
                SyncRequest.newBuilder().setProtocol(SYNC_PROTOCOL).setDeviceId(identity.deviceId)
                    .setEphemeralKey(ephemeral.publicKey.bytes).setNonce(SyncCrypto.randomBytes(32).bytes)
                    .setTime(now()).setVaultId(vaultId),
            ).build().toByteArray(),
        )
        val (f2Payload, f2) = frames.read(MAX_HANDSHAKE_FRAME)
        val reply = parse(f2Payload) { SyncReply.parseFrom(it) }
        val peer = peers.firstOrNull { it.deviceId == reply.deviceId } ?: throw SyncException.NotPaired()
        val (f3Payload, _) = frames.read(MAX_HANDSHAKE_FRAME)
        val macSignature = parse(f3Payload) { SignatureFrame.parseFrom(it) }.signature.toByteArray()
        if (!SyncCrypto.verify(peer.publicKey.toByteArray(), SyncCrypto.sha256("SyncedNotes sync v1 mac".toByteArray(), f1, f2), macSignature)) {
            throw SyncException.AuthenticationFailed()
        }
        val signature = SyncCrypto.sign(identity.privateKey, SyncCrypto.sha256("SyncedNotes sync v1 phone".toByteArray(), f1, f2))
        frames.write(SignatureFrame.newBuilder().setSignature(signature.bytes).build().toByteArray())
        checkClock(reply.time)
        return OpenSession(sessionChannel(SyncRole.PHONE, ephemeral, reply.ephemeralKey, f1, f2, frames), peer, reply.vaultId)
    }

    /** Opens a session as the Mac, whoever connected; the phone has spoken first. */
    fun sessionAsMac(frames: FrameStream, identity: SyncIdentity, vaultId: Id, peers: List<SyncPeer>): OpenSession {
        val (f1Payload, f1) = frames.read(MAX_HANDSHAKE_FRAME)
        val opening = parse(f1Payload) { Opening.parseFrom(it) }
        // Pairing happens only on a connection the Mac opened for it, never on one it accepted.
        if (!opening.hasSync()) throw SyncException.Protocol("expected sync")
        val request = opening.sync
        if (request.protocol != SYNC_PROTOCOL) throw SyncException.Protocol("protocol ${request.protocol}")
        val peer = peers.firstOrNull { it.deviceId == request.deviceId } ?: throw SyncException.NotPaired()
        val ephemeral = SyncCrypto.Ephemeral()
        val f2 = frames.write(
            SyncReply.newBuilder().setDeviceId(identity.deviceId).setEphemeralKey(ephemeral.publicKey.bytes)
                .setNonce(SyncCrypto.randomBytes(32).bytes).setTime(now()).setVaultId(vaultId).build().toByteArray(),
        )
        val signature = SyncCrypto.sign(identity.privateKey, SyncCrypto.sha256("SyncedNotes sync v1 mac".toByteArray(), f1, f2))
        frames.write(SignatureFrame.newBuilder().setSignature(signature.bytes).build().toByteArray())
        val (f4Payload, _) = frames.read(MAX_HANDSHAKE_FRAME)
        val phoneSignature = parse(f4Payload) { SignatureFrame.parseFrom(it) }.signature.toByteArray()
        if (!SyncCrypto.verify(peer.publicKey.toByteArray(), SyncCrypto.sha256("SyncedNotes sync v1 phone".toByteArray(), f1, f2), phoneSignature)) {
            throw SyncException.AuthenticationFailed()
        }
        checkClock(request.time)
        return OpenSession(sessionChannel(SyncRole.MAC, ephemeral, request.ephemeralKey, f1, f2, frames), peer, request.vaultId)
    }

    private fun sessionChannel(
        role: SyncRole, ephemeral: SyncCrypto.Ephemeral, theirKey: ByteString, f1: ByteArray, f2: ByteArray, frames: FrameStream,
    ): SecureChannel {
        val secret = ephemeral.agree(theirKey.toByteArray())
        val salt = SyncCrypto.sha256(f1, f2)
        val phoneToMac = SyncCrypto.hkdf(secret, salt, "SyncedNotes sync v1 phone to mac", 32)
        val macToPhone = SyncCrypto.hkdf(secret, salt, "SyncedNotes sync v1 mac to phone", 32)
        return channel(role, frames, phoneToMac, macToPhone).also { listOf(secret, phoneToMac, macToPhone).forEach { it.fill(0) } }
    }

    private fun channel(role: SyncRole, frames: FrameStream, phoneToMac: ByteArray, macToPhone: ByteArray) =
        if (role == SyncRole.PHONE) SecureChannel(frames, phoneToMac, macToPhone) else SecureChannel(frames, macToPhone, phoneToMac)

    private fun hello(identity: SyncIdentity, ephemeral: SyncCrypto.Ephemeral): Hello =
        Hello.newBuilder().setDeviceId(identity.deviceId).setDeviceName(identity.name)
            .setIdentityKey(identity.publicKey.bytes).setEphemeralKey(ephemeral.publicKey.bytes)
            .setNonce(SyncCrypto.randomBytes(32).bytes).setTime(now()).build()

    /** A device refuses to pair with itself, or with a key that is not P-256. */
    private fun checkOther(other: Hello, identity: SyncIdentity) {
        try {
            SyncCrypto.publicKey(other.identityKey.toByteArray())
        } catch (e: Exception) {
            throw SyncException.Protocol("identity key")
        }
        if (other.deviceId.size() != 16) throw SyncException.Protocol("device ID")
        if (other.identityKey.toByteArray().contentEquals(identity.publicKey) || other.deviceId == identity.deviceId) {
            throw SyncException.Protocol("own identity")
        }
    }

    private fun checkClock(theirTime: Double) {
        val skew = abs(theirTime - now())
        if (skew > MAX_CLOCK_SKEW_SECONDS) throw SyncException.ClockSkew(skew.toLong())
    }

    private fun now(): Double = Instant.now().let { it.epochSecond + it.nano / 1e9 }
}

/** What each side signs to confirm pairing; the role keeps one side's confirmation from being passed off as the other's. */
private fun confirmation(role: String, transcript: ByteArray) =
    SyncCrypto.sha256("SyncedNotes pair v1 confirm $role".toByteArray(), transcript)

private val ByteArray.bytes: ByteString get() = ByteString.copyFrom(this)

private fun <T : MessageLite> parse(bytes: ByteArray, parser: (ByteArray) -> T): T = try {
    parser(bytes)
} catch (e: InvalidProtocolBufferException) {
    throw SyncException.Protocol("malformed message")
}
