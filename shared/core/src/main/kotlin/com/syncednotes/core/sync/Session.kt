package com.syncednotes.core.sync

import com.syncednotes.core.Id
import com.syncednotes.core.NoteStore
import com.syncednotes.core.VaultHeader
import com.syncednotes.core.apply
import com.syncednotes.core.hasRecordsNeededBy
import com.syncednotes.core.manifest
import com.syncednotes.core.recordsNeededBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import syncednotes.sync.v1.Manifest
import syncednotes.sync.v1.Message

/**
 * One live session with a paired device (Docs/Sync.md, "Sync sessions"): both sides send what
 * they have, then only what changed, until the connection drops or the notes lock. The same
 * on the phone and the Mac. [onSynced] is called with the notes that came in, after every
 * exchange, even an empty one, so the screen can say when the two last compared.
 */
@OptIn(FlowPreview::class)
suspend fun runSyncSession(channel: SecureChannel, store: NoteStore, onSynced: (Set<Id>) -> Unit) = coroutineScope {
    var lastSent: Manifest? = null
    val sendLock = Any()
    fun sendManifest(force: Boolean = false) = synchronized(sendLock) {
        val manifest = store.manifest()
        if (force || manifest != lastSent) {
            channel.sendManifest(manifest)
            lastSent = manifest
        }
    }
    sendManifest()

    // Every change here, made on this device or taken from another, is told at once.
    val watcher = launch(Dispatchers.IO) {
        store.changes.drop(1).debounce(300).collect { runCatching { sendManifest() } }
    }
    val keepAlive = launch(Dispatchers.IO) {
        var elapsed = 0
        while (isActive) {
            delay(5_000)
            elapsed += 5
            if (elapsed % 25 == 0) runCatching { channel.sendPing() }
            if (elapsed % 60 == 0) runCatching { sendManifest(force = true) }
        }
    }
    try {
        while (isActive) {
            val message = runInterruptible(Dispatchers.IO) { channel.receiveMessage() }
            when (message.kindCase) {
                Message.KindCase.MANIFEST -> {
                    val theirs = message.manifest
                    store.recordsNeededBy(theirs).forEach(channel::sendRecords)
                    // The other side has something newer: show it what is here, so it sends it.
                    if (store.hasRecordsNeededBy(store.manifest(), theirs)) sendManifest(force = true)
                    onSynced(emptySet())
                }
                Message.KindCase.RECORDS -> onSynced(store.apply(message.records))
                Message.KindCase.PING -> {}
                Message.KindCase.VAULT_CHANGED -> throw SyncException.VaultChanged(
                    runCatching { VaultHeader.decode(message.vaultChanged.vaultHeader.toByteArray()) }
                        .getOrElse { throw SyncException.Protocol("vault header") },
                )
                // A message kind from a newer app: nothing to do with it here.
                else -> {}
            }
        }
    } finally {
        watcher.cancel()
        keepAlive.cancel()
    }
}
