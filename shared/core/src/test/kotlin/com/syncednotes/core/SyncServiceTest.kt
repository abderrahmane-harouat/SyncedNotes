package com.syncednotes.core

import com.syncednotes.core.sync.Discovery
import com.syncednotes.core.sync.FoundDevice
import com.syncednotes.core.sync.SyncRole
import com.syncednotes.core.sync.SyncService
import com.syncednotes.core.sync.SyncService.PairingState
import java.io.File
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A Mac and a phone on one made-up network, over loopback: pairing, then a live session. */
class SyncServiceTest {
    /** Stands in for Bonjour and Network Service Discovery: each side sees the other's announcement. */
    private class Network {
        val adverts = mutableMapOf<SyncRole, FoundDevice>()
        val browsers = mutableMapOf<SyncRole, (FoundDevice) -> Unit>()

        fun discovery(role: SyncRole) = object : Discovery {
            override fun advertise(port: Int, readyToPair: Boolean, name: String?) {
                val device = FoundDevice("$role:$port", InetAddress.getLoopbackAddress(), port, readyToPair, name)
                synchronized(this@Network) { adverts[role] = device }
                browsers[other(role)]?.invoke(device)
            }
            override fun stopAdvertising() { synchronized(this@Network) { adverts.remove(role) } }
            override fun browse(found: (FoundDevice) -> Unit, lost: (String) -> Unit) {
                browsers[role] = found
                adverts[other(role)]?.let(found)
            }
            override fun stopBrowsing() { browsers.remove(role) }
        }

        private fun other(role: SyncRole) = if (role == SyncRole.MAC) SyncRole.PHONE else SyncRole.MAC
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val network = Network()
    private val passphrase = "made-up passphrase"
    private val macFolder = temporaryFolder("service-mac")
    private val phoneFolder = temporaryFolder("service-phone")
    private val vault = createVault(macFolder, passphrase)
    private val mac = NoteStore(macFolder, vault)
    private val phone = NoteStore(phoneFolder.also { File(it, "vault.bin").writeBytes(vault.header.encoded) }, vault)
    private val arrivedOnPhone = mutableSetOf<Id>()
    private val macSync = SyncService(SyncRole.MAC, { "made-up Mac" }, network.discovery(SyncRole.MAC), scope)
    private val phoneSync = SyncService(SyncRole.PHONE, { "made-up phone" }, network.discovery(SyncRole.PHONE), scope, onRemoteChange = { synchronized(arrivedOnPhone) { arrivedOnPhone += it } })

    @AfterTest
    fun stop() {
        macSync.detach(); phoneSync.detach()
        scope.cancel()
        mac.close(); phone.close()
    }

    private fun waitFor(what: String, seconds: Int = 15, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > until) error("Gave up waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun pair() {
        macSync.attach(mac)
        phoneSync.attach(phone)
        phoneSync.openPairing()
        macSync.openPairing()
        waitFor("the phone to be listed") { macSync.phonesReadyToPair.value.isNotEmpty() }
        assertEquals("made-up phone", macSync.phonesReadyToPair.value.single().name)
        macSync.pairWith(macSync.phonesReadyToPair.value.single())
        waitFor("both codes") { macSync.pairing.value is PairingState.Confirming && phoneSync.pairing.value is PairingState.Confirming }
        val macCode = (macSync.pairing.value as PairingState.Confirming).code
        val phoneCode = (phoneSync.pairing.value as PairingState.Confirming).code
        assertEquals(macCode, phoneCode)
        assertTrue(macCode.matches(Regex("\\d{6}")))
        phoneSync.confirmPairing(true)
        macSync.confirmPairing(true)
        waitFor("both to be paired") { macSync.pairing.value is PairingState.Paired && phoneSync.pairing.value is PairingState.Paired }
        val paired = phoneSync.pairing.value as PairingState.Paired
        assertEquals(vault.header, paired.vaultHeader)
        assertEquals("made-up Mac", paired.peer.name)
        macSync.closePairing()
        phoneSync.closePairing()
    }

    @Test
    fun aPhoneAndAMacPairByComparingCodesAndThenSyncBothWays() {
        pair()
        assertEquals(listOf("made-up phone"), mac.peers().map { it.name })
        assertEquals(listOf("made-up Mac"), phone.peers().map { it.name })

        mac.save(note("Written on the Mac"), id(1))
        waitFor("the note on the phone") { runCatching { phone.load(id(1)) }.isSuccess }
        assertEquals(note("Written on the Mac"), phone.load(id(1)))
        waitFor("the window to be told") { synchronized(arrivedOnPhone) { id(1) in arrivedOnPhone } }

        phone.save(note("Written on the phone"), id(2))
        waitFor("the note on the Mac") { runCatching { mac.load(id(2)) }.isSuccess }
        phone.delete(id(1))
        waitFor("the deletion on the Mac") { mac.isDeleted(id(1)) }
    }

    @Test
    fun cancellingOnOneDeviceEndsThePairingOnTheOther() {
        macSync.attach(mac)
        phoneSync.attach(phone)
        phoneSync.openPairing()
        macSync.openPairing()
        waitFor("the phone to be listed") { macSync.phonesReadyToPair.value.isNotEmpty() }
        macSync.pairWith(macSync.phonesReadyToPair.value.single())
        waitFor("the codes") { phoneSync.pairing.value is PairingState.Confirming && macSync.pairing.value is PairingState.Confirming }
        phoneSync.confirmPairing(false)
        waitFor("the Mac to hear of it") { macSync.pairing.value is PairingState.Failed }
        assertTrue(mac.peers().isEmpty())
        assertTrue(phone.peers().isEmpty())
    }

    @Test
    fun aPhoneLeftOnTheOldVaultIsGivenTheMacsNewOne() {
        pair()
        // The Mac's passphrase changed: it now holds another vault than the phone's.
        val changedFolder = temporaryFolder("service-mac-changed")
        val changedVault = createVault(changedFolder, "made-up new passphrase")
        macSync.detach()
        NoteStore(changedFolder, changedVault).use { changed ->
            changed.saveSyncIdentity(mac.syncIdentity()!!)
            mac.peers().forEach(changed::savePeer)
            macSync.attach(changed)
            waitFor("the phone to be told") { phoneSync.vaultChanged.value != null }
            assertEquals(changedVault.header, assertNotNull(phoneSync.vaultChanged.value))
            macSync.detach()
        }
    }
}
