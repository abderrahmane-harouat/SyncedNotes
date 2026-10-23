package com.syncednotes.core.sync

import com.syncednotes.core.Id
import com.syncednotes.core.NoteStore
import com.syncednotes.core.SyncIdentity
import com.syncednotes.core.SyncPeer
import com.syncednotes.core.VaultHeader
import com.syncednotes.core.newId
import com.syncednotes.core.peers
import com.syncednotes.core.removePeer
import com.syncednotes.core.savePeer
import com.syncednotes.core.saveSyncIdentity
import com.syncednotes.core.syncIdentity
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** A device of the other kind, found on the local network. */
data class FoundDevice(
    /** Tells one announcement from another; the same while it stays on the network. */
    val key: String,
    val address: InetAddress,
    val port: Int,
    /** A phone whose pairing screen is open. */
    val readyToPair: Boolean = false,
    /** The phone's own name, announced only while its pairing screen is open. */
    val name: String? = null,
)

/**
 * The platform's half of finding each other: Network Service Discovery on Android, Bonjour on
 * the Mac. Callbacks may come on any thread.
 */
interface Discovery {
    /** Announces this device on [port]; a phone ready to pair also gives its [name]. */
    fun advertise(port: Int, readyToPair: Boolean, name: String?)
    fun stopAdvertising()
    fun browse(found: (FoundDevice) -> Unit, lost: (String) -> Unit)
    fun stopBrowsing()
}

/**
 * Keeps this device's notes in sync with its paired devices on the local network (Docs/Sync.md).
 * While the notes are unlocked ([attach]), it listens and announces itself, and also looks for
 * the other devices and connects to them: whichever connection gets through is used, and each
 * stays open as a live two-way session, so a change on either side reaches the other within
 * moments. The same on the phone and the Mac; [role] says which side of the protocol it speaks.
 */
class SyncService(
    val role: SyncRole,
    private val deviceName: () -> String,
    private val discovery: Discovery,
    private val scope: CoroutineScope,
    /** Notes that came in from another device, for the window to show them. */
    private val onRemoteChange: (Set<Id>) -> Unit = {},
    /** A fixed port to listen on instead of any free one: for testing over `adb forward`. */
    private val fixedPort: Int? = null,
) {
    sealed interface PeerState {
        data object Waiting : PeerState
        data class Connected(val lastSynced: Instant?) : PeerState
        data class Failed(val message: String) : PeerState
    }

    sealed interface PairingState {
        /** The phone announces it is ready; the Mac lists the phones that are. */
        data object Ready : PairingState
        data object Connecting : PairingState
        data class Confirming(val code: String, val otherName: String) : PairingState
        data class WaitingForOther(val code: String, val otherName: String) : PairingState
        /**
         * Both confirmed. On the phone, [vaultHeader] is the Mac's, and [identity] the phone's,
         * to keep with the notes once they are locked with that vault.
         */
        data class Paired(val peer: SyncPeer, val vaultHeader: VaultHeader?, val identity: SyncIdentity) : PairingState
        data class Failed(val message: String) : PairingState
    }

    private val _peerStates = MutableStateFlow<Map<Id, PeerState>>(emptyMap())
    val peerStates: StateFlow<Map<Id, PeerState>> = _peerStates.asStateFlow()

    private val _pairing = MutableStateFlow<PairingState?>(null)
    /** The pairing under way, while a pairing screen is open. */
    val pairing: StateFlow<PairingState?> = _pairing.asStateFlow()

    private val _phonesReadyToPair = MutableStateFlow<List<FoundDevice>>(emptyList())
    /** On the Mac: the phones whose pairing screen is open, for its Pair a Phone window. */
    val phonesReadyToPair: StateFlow<List<FoundDevice>> = _phonesReadyToPair.asStateFlow()

    private val _vaultChanged = MutableStateFlow<VaultHeader?>(null)
    /** On the phone: the Mac's new vault header, once the Mac's passphrase has been changed. */
    val vaultChanged: StateFlow<VaultHeader?> = _vaultChanged.asStateFlow()

    private val _peers = MutableStateFlow<List<SyncPeer>>(emptyList())
    val peers: StateFlow<List<SyncPeer>> = _peers.asStateFlow()

    @Volatile private var store: NoteStore? = null
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var advertised: Pair<Int, Boolean>? = null
    /** Live connections, closed when sync stops. */
    private val sockets = mutableSetOf<Socket>()
    /** The open session with each device, and whether the phone opened it. */
    private val sessions = mutableMapOf<Id, Pair<Socket, Boolean>>()
    /** Connection loops to devices found on the network, by key. */
    private val connections = mutableMapOf<String, Job>()
    /** Devices that turned out not to be paired with this one, and when. */
    private val notOurs = mutableMapOf<String, Long>()
    private val found = mutableMapOf<String, FoundDevice>()
    private var pairingJob: Job? = null
    private var pairingSocket: Socket? = null
    private var userDecision: CompletableDeferred<Boolean>? = null
    /** The phone's identity while it pairs before it has any notes to keep it in. */
    private var pendingIdentity: SyncIdentity? = null

    // --- Starting and stopping -----------------------------------------------------------

    /** The notes are unlocked: sync them, if paired. */
    fun attach(store: NoteStore) {
        this.store = store
        _peers.value = store.peers()
        _vaultChanged.value = null
        refresh()
    }

    /** The notes are locked: stop, and drop every connection. */
    fun detach() {
        store = null
        _peers.value = emptyList()
        refresh()
    }

    /** Starts or stops to match the notes' state and whether a pairing screen is open. */
    @Synchronized
    fun refresh() {
        val wanted = (store != null && _peers.value.isNotEmpty()) || _pairing.value != null
        if (wanted && !running) start() else if (!wanted && running) stop()
        if (running) advertise()
    }

    private fun start() {
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(fixedPort ?: 0))
            }
        }.getOrNull() ?: return
        server = socket
        running = true
        scope.launch(Dispatchers.IO) { acceptConnections(socket) }
        advertise()
        discovery.browse(::onFound, ::onLost)
    }

    private fun stop() {
        running = false
        discovery.stopAdvertising()
        advertised = null
        discovery.stopBrowsing()
        synchronized(connections) {
            connections.values.forEach { it.cancel() }
            connections.clear()
        }
        synchronized(found) { found.clear() }
        _phonesReadyToPair.value = emptyList()
        runCatching { server?.close() }
        server = null
        synchronized(sockets) {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
        synchronized(sessions) { sessions.clear() }
        _peerStates.value = emptyMap()
        if (store == null) cancelPairing()
    }

    /** Announces this device, and on a phone whether its pairing screen is open. */
    @Synchronized
    private fun advertise() {
        val port = server?.localPort ?: return
        val ready = role == SyncRole.PHONE && _pairing.value == PairingState.Ready
        if (advertised == port to ready) return
        discovery.stopAdvertising()
        // Only while pairing, which the user starts, does a phone give its name: anyone on the
        // network can see an announcement, and device names often hold their owner's name.
        discovery.advertise(port, ready, if (ready) deviceName() else null)
        advertised = port to ready
    }

    // --- Finding the other devices and connecting to them --------------------------------

    private fun onFound(device: FoundDevice) {
        if (!isLocal(device.address)) return
        synchronized(found) { found[device.key] = device }
        publishPhonesReadyToPair()
        if (!device.readyToPair) connectWhileFound(device)
    }

    private fun onLost(key: String) {
        synchronized(found) { found.remove(key) }
        publishPhonesReadyToPair()
        synchronized(connections) { connections.remove(key)?.cancel() }
    }

    private fun publishPhonesReadyToPair() {
        if (role != SyncRole.MAC) return
        _phonesReadyToPair.value = synchronized(found) { found.values.filter { it.readyToPair }.sortedBy { it.name } }
    }

    /**
     * Keeps trying to reach a device while it is on the network, whenever this one is not
     * already connected to every paired device: the other may well have connected first.
     */
    private fun connectWhileFound(device: FoundDevice) {
        synchronized(connections) {
            if (connections[device.key]?.isActive == true) return
            connections[device.key] = scope.launch(Dispatchers.IO) {
                var backoff = 2_000L
                while (running && isActive) {
                    val refused = synchronized(notOurs) { notOurs[device.key] }
                    val peers = _peers.value
                    val allConnected = peers.isNotEmpty() && synchronized(sessions) { peers.all { it.deviceId in sessions } }
                    if (store != null && peers.isNotEmpty() && !allConnected && pairingSocket == null &&
                        (refused == null || System.currentTimeMillis() - refused > 60_000)
                    ) {
                        val socket = Socket()
                        synchronized(sockets) { sockets += socket }
                        try {
                            socket.connect(InetSocketAddress(device.address, device.port), 5_000)
                            if (session(socket, openedByPhone = role == SyncRole.PHONE)) backoff = 2_000L
                            else synchronized(notOurs) { notOurs[device.key] = System.currentTimeMillis() }
                        } catch (e: Exception) {
                            // Not reachable now, for instance while the Mac's firewall asks;
                            // the other side's own connection may get through.
                        } finally {
                            runCatching { socket.close() }
                            synchronized(sockets) { sockets -= socket }
                        }
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(10_000L)
                }
            }
        }
    }

    // --- Connections from the other devices ----------------------------------------------

    private fun acceptConnections(server: ServerSocket) {
        while (running && !server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            if (!isLocal(socket.inetAddress)) {
                runCatching { socket.close() }
                continue
            }
            synchronized(sockets) { sockets += socket }
            scope.launch(Dispatchers.IO) {
                try {
                    handle(socket)
                } finally {
                    runCatching { socket.close() }
                    synchronized(sockets) { sockets -= socket }
                }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        // While the phone's pairing screen is open, a Mac connects to pair. Only one attempt
        // per opening: any failure ends pairing on screen, so nobody can retry unseen until a
        // code happens to match.
        val pairingNow = role == SyncRole.PHONE && synchronized(this) {
            if (_pairing.value == PairingState.Ready && pairingSocket == null) {
                pairingSocket = socket
                true
            } else false
        }
        if (pairingNow) {
            pair(socket, null)
            return
        }
        if (synchronized(this) { pairingSocket != null }) return
        session(socket, openedByPhone = role == SyncRole.MAC)
    }

    /**
     * Runs a sync session over [socket], whichever side opened it; the phone always speaks
     * first. Returns false if the other side is not a device this one is paired with.
     */
    private suspend fun session(socket: Socket, openedByPhone: Boolean): Boolean {
        val store = store ?: return true
        val identity = store.syncIdentity() ?: return true
        val vaultId = com.google.protobuf.ByteString.copyFrom(store.vault.header.id)
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000  // the handshake must be prompt
        val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
        val open = try {
            if (role == SyncRole.PHONE) SyncHandshake.sessionAsPhone(frames, identity, vaultId, _peers.value)
            else SyncHandshake.sessionAsMac(frames, identity, vaultId, _peers.value)
        } catch (e: SyncException.NotPaired) {
            return false
        } catch (e: SyncException.ClockSkew) {
            markAll(PeerState.Failed(e.message.orEmpty()))
            return true
        } catch (e: Exception) {
            return true  // a broken handshake
        }
        val peer = open.peer
        if (open.theirVault != vaultId) {
            // The Mac's vault is the one both hold; a phone left on another is given the Mac's.
            if (role == SyncRole.MAC) {
                runCatching { open.channel.sendVaultChanged(store.vault.header) }
                setState(peer.deviceId, PeerState.Failed("The phone needs the new passphrase."))
                return true
            }
        }
        socket.soTimeout = 70_000  // the other side pings every 25 seconds
        val keep = synchronized(sessions) {
            val existing = sessions[peer.deviceId]
            // When both connected at once, both keep the connection the phone opened;
            // otherwise a newer connection replaces an older one.
            if (existing != null && existing.second && !openedByPhone) false
            else {
                existing?.first?.let { runCatching { it.close() } }
                sessions[peer.deviceId] = socket to openedByPhone
                true
            }
        }
        if (!keep) return true
        setState(peer.deviceId, PeerState.Connected(null))
        try {
            runSyncSession(open.channel, store) { changed ->
                setState(peer.deviceId, PeerState.Connected(Instant.now()))
                if (changed.isNotEmpty()) onRemoteChange(changed)
            }
        } catch (e: SyncException.VaultChanged) {
            _vaultChanged.value = e.header
            setState(peer.deviceId, PeerState.Failed(e.message.orEmpty()))
        } catch (e: Exception) {
            // Dropped, or the notes locked; either side connects again.
        } finally {
            val current = synchronized(sessions) {
                (sessions[peer.deviceId]?.first === socket).also { if (it) sessions.remove(peer.deviceId) }
            }
            if (current && _peerStates.value[peer.deviceId] !is PeerState.Failed) setState(peer.deviceId, PeerState.Waiting)
        }
        return true
    }

    private fun setState(id: Id, state: PeerState) = _peerStates.update { it + (id to state) }

    private fun markAll(state: PeerState) = _peerStates.update { _peers.value.associate { p -> p.deviceId to state } }

    fun unpair(id: Id) {
        store?.removePeer(id)
        _peers.value = store?.peers().orEmpty()
        _peerStates.update { states -> states.filterKeys { it != id } }
        synchronized(sessions) { sessions.remove(id)?.let { runCatching { it.first.close() } } }
        synchronized(notOurs) { notOurs.clear() }
        refresh()
    }

    // --- Pairing -------------------------------------------------------------------------

    /** Opens the pairing screen: a phone announces it is ready, a Mac lists such phones. */
    fun openPairing() {
        if (_pairing.value == null) _pairing.value = PairingState.Ready
        refresh()
    }

    fun closePairing() {
        cancelPairing()
        _pairing.value = null
        refresh()
    }

    /** Ready to pair again, after a failed attempt. */
    fun restartPairing() {
        cancelPairing()
        _pairing.value = PairingState.Ready
        refresh()
    }

    /** This device's user compared the codes. */
    fun confirmPairing(accepted: Boolean) {
        userDecision?.complete(accepted)
    }

    /** On the Mac: pairs with a phone from [phonesReadyToPair]. */
    fun pairWith(phone: FoundDevice) {
        if (role != SyncRole.MAC || _pairing.value != PairingState.Ready) return
        val socket = Socket()
        synchronized(this) { pairingSocket = socket }
        synchronized(sockets) { sockets += socket }
        _pairing.value = PairingState.Connecting
        scope.launch(Dispatchers.IO) {
            try {
                socket.connect(InetSocketAddress(phone.address, phone.port), 5_000)
                pair(socket, phone)
            } catch (e: Exception) {
                _pairing.value = PairingState.Failed("The phone could not be reached. Make sure it is on the same network, with its pairing screen open.")
                synchronized(this@SyncService) { if (pairingSocket === socket) pairingSocket = null }
            } finally {
                runCatching { socket.close() }
                synchronized(sockets) { sockets -= socket }
            }
        }
    }

    /** Pairing over [socket] (Docs/Sync.md, "Pairing"), as the phone or as the Mac. */
    private suspend fun pair(socket: Socket, phone: FoundDevice?) {
        val decision = CompletableDeferred<Boolean>()
        userDecision = decision
        val job = scope.launch(Dispatchers.IO) {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 15_000  // up to showing the code, the handshake must be prompt
                val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                val identity = ensureIdentity()
                val pairing = if (role == SyncRole.PHONE) SyncHandshake.pairAsPhone(frames, identity) else SyncHandshake.pairAsMac(frames, identity)
                socket.soTimeout = 0  // comparing codes may take a while
                val otherName = pairing.other.deviceName.ifBlank { phone?.name ?: "" }
                _pairing.value = PairingState.Confirming(pairing.code, otherName)

                // The other device's user may answer before or after this one's.
                val fromOther = async { runCatching { pairing.awaitOther() } }
                var otherResult: Result<Pair<SyncPeer, VaultHeader?>>? = null
                var accepted: Boolean? = null
                while (accepted == null) {
                    select {
                        decision.onAwait { accepted = it }
                        if (otherResult == null) fromOther.onAwait { result ->
                            otherResult = result
                            result.exceptionOrNull()?.let { throw it }
                        }
                    }
                }
                pairing.confirm(accepted == true, if (role == SyncRole.MAC) store?.vault?.header else null)
                if (accepted != true) throw SyncException.Cancelled()
                if (otherResult == null) _pairing.value = PairingState.WaitingForOther(pairing.code, otherName)
                val (peer, header) = (otherResult ?: fromOther.await()).getOrThrow()
                // The phone keeps the Mac as a peer once its notes are in the Mac's vault, which
                // may first need the Mac's passphrase; the Mac keeps the phone now.
                val store = store
                if (store != null && (role == SyncRole.MAC || header?.id?.contentEquals(store.vault.header.id) == true)) {
                    store.savePeer(peer)
                    _peers.value = store.peers()
                }
                _pairing.value = PairingState.Paired(peer, header, identity)
            } catch (e: SyncException.Cancelled) {
                _pairing.value = if (decision.isCompleted) PairingState.Ready else PairingState.Failed("Pairing was cancelled on the other device.")
            } catch (e: SyncException) {
                _pairing.value = PairingState.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                if (isActive) _pairing.value = PairingState.Failed("The connection was interrupted. Try again, with only your own devices pairing.")
            } finally {
                runCatching { socket.close() }
                synchronized(this@SyncService) { if (pairingSocket === socket) pairingSocket = null }
                refresh()
            }
        }
        pairingJob = job
        job.join()
    }

    private fun cancelPairing() {
        userDecision?.complete(false)
        userDecision = null
        synchronized(this) {
            runCatching { pairingSocket?.close() }
            pairingSocket = null
        }
        pairingJob?.cancel()
        pairingJob = null
    }

    /**
     * This device's identity, made the first time it pairs and kept in the locked database. A
     * phone with no notes yet keeps it in memory until it has the Mac's vault to keep it in.
     */
    private fun ensureIdentity(): SyncIdentity {
        val store = store
        val existing = store?.syncIdentity() ?: pendingIdentity
        if (existing != null) {
            if (existing.name == deviceName()) return existing
            return SyncIdentity(existing.deviceId, deviceName(), existing.privateKey, existing.publicKey).also { store?.saveSyncIdentity(it) }
        }
        val (privateKey, publicKey) = SyncCrypto.newIdentity()
        val identity = SyncIdentity(newId(), deviceName(), privateKey, publicKey)
        if (store != null) store.saveSyncIdentity(identity) else pendingIdentity = identity
        return identity
    }

    companion object {
        const val MAC_SERVICE_TYPE = "_syncednotes._tcp"
        const val PHONE_SERVICE_TYPE = "_syncednotes-phone._tcp"
        /** The name every device announces itself under, never the device's own. */
        const val SERVICE_NAME = "SyncedNotes"

        /** Loopback, private IPv4, link-local, or unique local IPv6 (Docs/Sync.md, "Local network only"). */
        fun isLocal(address: InetAddress): Boolean = when (address) {
            is Inet4Address -> address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
            is Inet6Address -> address.isLoopbackAddress || address.isLinkLocalAddress ||
                (address.address[0].toInt() and 0xFE) == 0xFC ||
                (address.isIPv4CompatibleAddress && isLocal(InetAddress.getByAddress(address.address.copyOfRange(12, 16))))
            else -> false
        }
    }
}
