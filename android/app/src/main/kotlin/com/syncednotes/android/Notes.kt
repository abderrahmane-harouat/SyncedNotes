package com.syncednotes.android

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.syncednotes.core.Id
import com.syncednotes.core.LockException
import com.syncednotes.core.NoteStore
import com.syncednotes.core.PassphraseChange
import com.syncednotes.core.PictureCache
import com.syncednotes.core.PictureHandles
import com.syncednotes.core.addPicture
import com.syncednotes.core.SqlOpener
import com.syncednotes.core.SyncIdentity
import com.syncednotes.core.SyncPeer
import com.syncednotes.core.Vault
import com.syncednotes.core.VaultHeader
import com.syncednotes.core.createVault
import com.syncednotes.core.savePeer
import com.syncednotes.core.saveSyncIdentity
import com.syncednotes.core.sync.SyncRole
import com.syncednotes.core.sync.SyncService
import com.syncednotes.core.unlockVault
import com.syncednotes.core.vaultHeaderFile
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The phone's notes, for every screen: locked or not, the store while unlocked, and sync. One
 * per process, made by [NotesApp]. The Mac's NotesModel holds the same, for its one window.
 */
class Notes(private val app: Application) {
    /** The notes' folder, inside the app's own storage, which no other app can read. */
    val home = File(app.filesDir, "SyncedNotes")
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var store: NoteStore? = null
        private set

    val sync = SyncService(
        role = SyncRole.PHONE,
        deviceName = ::deviceName,
        discovery = NsdDiscovery(app),
        scope = scope,
        onRemoteChange = { ids ->
            // A note's pictures may have come after it.
            pictures.retryMissing()
            main.post { remoteListeners.forEach { it(ids) } }
        },
    )

    /** The open note's pictures, decoded on the phone at the size they are shown. */
    val pictures = PictureCache({ store }, AndroidPictures) { main.post { pictureListeners.forEach { it() } } }
    private val pictureListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Called on the main thread when a picture finished decoding, to draw it. */
    fun addPictureListener(listener: () -> Unit) = pictureListeners.add(listener)
    fun removePictureListener(listener: () -> Unit) = pictureListeners.remove(listener)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val remoteListeners = CopyOnWriteArrayList<(Set<Id>) -> Unit>()
    private var watching: Job? = null

    init {
        SqlOpener.installed = AndroidSqlOpener
        // A passphrase change, or joining the Mac's vault, stopped part way: finish or drop it
        // before anything opens the notes.
        runCatching { PassphraseChange.finishInterruptedChange(home) }
    }

    val hasVault: Boolean get() = vaultHeaderFile(home).exists()
    val isUnlocked: Boolean get() = store != null

    /** Called on the main thread whenever the notes change, here or from the Mac, or lock. */
    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** Called on the main thread with the notes that came in from the Mac. */
    fun addRemoteListener(listener: (Set<Id>) -> Unit) = remoteListeners.add(listener)
    fun removeRemoteListener(listener: (Set<Id>) -> Unit) = remoteListeners.remove(listener)

    private fun changed() = main.post { listeners.forEach { it() } }

    // --- Locking ---------------------------------------------------------------------------

    /** Argon2id takes a moment: call off the main thread. Throws on a wrong passphrase. */
    fun unlock(passphrase: String) = open(unlockVault(home, passphrase))

    /** A vault of the phone's own, for using it without a Mac; pairing later joins the Mac's. */
    fun create(passphrase: String) = open(createVault(home, passphrase))

    private fun open(vault: Vault) {
        val opened = NoteStore(home, vault)
        store = opened
        watching?.cancel()
        watching = scope.launch { opened.changes.collect { changed() } }
        sync.attach(opened)
        changed()
    }

    fun lock() = close(tell = true)

    /**
     * Closes the notes. Untold, for closing them only to swap the folder under them and open
     * them again at once: the screens would otherwise go to the lock screen in between.
     */
    private fun close(tell: Boolean) {
        val opened = store ?: return
        sync.detach()
        watching?.cancel()
        store = null
        pictures.clear()
        runCatching { opened.close() }
        if (tell) changed()
    }

    // --- Joining the Mac's vault -------------------------------------------------------------

    /**
     * Makes the Mac's vault, from its [header], the phone's, unlocked with the Mac's
     * [passphrase], keeping [identity] and the Mac as [peer] with the notes (Docs/Sync.md, "One
     * vault on both devices"). A phone with no notes starts from the Mac's header; one with
     * notes locks them again under it, checked before they take the old ones' place. Slow:
     * call off the main thread. Throws on a wrong passphrase, changing nothing.
     */
    fun join(header: VaultHeader, passphrase: String, identity: SyncIdentity, peer: SyncPeer?) {
        val vault = Vault.unlock(header, passphrase)
        val current = store
        val headerHere = vaultHeaderFile(home).takeIf { it.exists() }?.let { runCatching { VaultHeader.decode(it.readBytes()) }.getOrNull() }
        if (current == null && (headerHere == null || headerHere.id.contentEquals(header.id))) {
            // A phone with no notes starts from the Mac's header; one that already holds it
            // (an earlier attempt stopped part way) simply opens with it.
            val wrote = headerHere == null
            home.mkdirs()
            if (wrote) NoteStore.writeAtomically(vaultHeaderFile(home), header.encoded)
            try {
                open(vault)
            } catch (e: Exception) {
                // Back to a phone with no notes, so pairing can be tried again from the start.
                if (wrote) home.deleteRecursively()
                throw e
            }
        } else {
            val opened = current ?: throw IllegalStateException("Unlock the notes first.")
            if (!opened.vault.header.id.contentEquals(header.id)) {
                opened.saveSyncIdentity(identity)
                peer?.let(opened::savePeer)
                PassphraseChange.prepareJoining(opened, home, vault, passphrase)
                close(tell = false)
                PassphraseChange.swap(home)
                open(vault)
                return
            }
        }
        val opened = store!!
        opened.saveSyncIdentity(identity)
        peer?.let(opened::savePeer)
        // Sync starts now that there is a peer to sync with.
        sync.attach(opened)
    }

    /**
     * Changes the passphrase of a phone that is not paired, as on the Mac: the notes are
     * locked again beside the old ones, checked, then swapped in. A paired phone holds the
     * Mac's vault, whose passphrase is changed on the Mac. Slow: off the main thread. Throws a
     * [LockException] for a wrong [current] passphrase, and changes nothing then.
     */
    fun changePassphrase(current: String, new: String) {
        val opened = store ?: throw IllegalStateException("Unlock the notes first.")
        check(sync.peers.value.isEmpty()) { "Change the passphrase on your Mac; this phone follows it." }
        val vault = PassphraseChange.prepare(opened, home, current, new)
        close(tell = false)
        try {
            PassphraseChange.swap(home)
        } catch (e: Exception) {
            // Settle on whichever notes are in place now: the change finished, or did not.
            runCatching { PassphraseChange.finishInterruptedChange(home) }
        }
        open(runCatching { unlockVault(home, new) }.getOrElse { unlockVault(home, current) })
        if (!vault.header.id.contentEquals(store!!.vault.header.id)) throw IllegalStateException("The passphrase was not changed; your notes are as they were.")
    }

    /**
     * Keeps a picture the user chose: written afresh (AndroidPictures.prepare), then locked in
     * the store like any picture, which sync carries to the Mac. Returns the engine's handle
     * for it, or null when it is not a picture. Slow: off the main thread.
     */
    fun addPicture(bytes: ByteArray): Int? {
        val opened = store ?: return null
        val prepared = AndroidPictures.prepare(bytes) ?: return null
        // Its size in points, as the Mac counts them: pixels of a sharp screen, halved.
        val picture = opened.addPicture(prepared.bytes, (prepared.width + 1) / 2, (prepared.height + 1) / 2)
        return PictureHandles.handle(picture)
    }

    fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
}

/** The process: makes [Notes], and locks them after a while away from the app. */
class NotesApp : Application() {
    lateinit var notes: Notes
        private set

    private val main = Handler(Looper.getMainLooper())
    private var started = 0
    private val lockAway = Runnable { notes.lock() }

    override fun onCreate() {
        super.onCreate()
        notes = Notes(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                main.removeCallbacks(lockAway)
            }
            override fun onActivityStopped(activity: Activity) {
                started--
                // Left for five minutes, the notes lock, as a phone left on a table should.
                if (started == 0) main.postDelayed(lockAway, LOCK_AFTER_MILLIS)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        const val LOCK_AFTER_MILLIS = 5 * 60 * 1000L
    }
}

val Activity.notes: Notes get() = (application as NotesApp).notes
