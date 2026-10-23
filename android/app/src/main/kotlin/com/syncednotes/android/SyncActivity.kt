package com.syncednotes.android

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.syncednotes.core.LockException
import com.syncednotes.core.SyncIdentity
import com.syncednotes.core.SyncPeer
import com.syncednotes.core.VaultHeader
import com.syncednotes.core.syncIdentity
import com.syncednotes.core.sync.SyncService.PairingState
import com.syncednotes.core.sync.SyncService.PeerState
import kotlin.concurrent.thread
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Pairing with the Mac, and how sync stands once paired (Docs/Sync.md). Pairing ends with the
 * Mac's passphrase whenever the phone's notes are not yet in the Mac's vault.
 */
class SyncActivity : ScreenActivity() {
    private val scope = MainScope()
    private lateinit var page: LinearLayout
    /** Set while the Mac's passphrase is asked for: the screen then stays put. */
    private var asking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = column(padding = dp(28)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val back = plainButton("Done") { finish() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        showScreen(
            column(
                row(back).apply { setPadding(dp(8), 0, dp(8), 0) },
                ScrollView(this).apply { addView(page) }.margins(height = 0, weight = 1f),
            ),
        )
        if (notes.sync.peers.value.isEmpty()) notes.sync.openPairing()
        scope.launch {
            combine(notes.sync.pairing, notes.sync.peers, notes.sync.peerStates, notes.sync.vaultChanged) { pairing, peers, states, changed ->
                Quad(pairing, peers, states, changed)
            }.collect { (pairing, peers, states, changed) -> if (!asking) render(pairing, peers, states, changed) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        notes.sync.closePairing()
        super.onDestroy()
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun render(pairing: PairingState?, peers: List<SyncPeer>, states: Map<com.syncednotes.core.Id, PeerState>, changed: VaultHeader?) {
        page.removeAllViews()
        if (changed != null && pairing == null) {
            askPassphrase(
                "The passphrase was changed on your Mac",
                "Enter the Mac's new passphrase. The notes on this phone are locked again with it, and sync carries on.",
                changed, null,
            )
            return
        }
        when (pairing) {
            null -> showPaired(peers, states)
            PairingState.Ready, PairingState.Connecting -> {
                title("Pair with your Mac")
                text("On your Mac, open SyncedNotes and choose Pair a Phone… from the File menu. Then pick this phone:")
                page.addView(label(notes.deviceName(), 20f, bold = true).apply { gravity = Gravity.CENTER }.margins(top = dp(20), bottom = dp(24)))
                page.addView(ProgressBar(this))
                text("Both need to be on the same Wi-Fi network.", Palette.SECONDARY).margins(top = dp(24))
            }
            is PairingState.Confirming -> {
                title("Compare the codes")
                text("Does your Mac, ${pairing.otherName}, show this same code?")
                code(pairing.code)
                page.addView(primaryButton("The Codes Match") { notes.sync.confirmPairing(true) }.margins(top = dp(8)))
                page.addView(plainButton("Cancel", Palette.DANGER) { notes.sync.confirmPairing(false) }.margins(top = dp(8)))
            }
            is PairingState.WaitingForOther -> {
                title("Compare the codes")
                code(pairing.code)
                text("Confirm on your Mac too.", Palette.SECONDARY)
                page.addView(ProgressBar(this).margins(top = dp(16), width = dp(48), height = dp(48)))
            }
            is PairingState.Paired -> {
                val store = notes.store
                val header = pairing.vaultHeader
                if (header != null && (store == null || !store.vault.header.id.contentEquals(header.id))) {
                    askPassphrase(
                        "Enter your Mac's passphrase",
                        if (store == null) "The notes on this phone are locked with the same passphrase as on your Mac."
                        else "The notes on this phone are locked again with your Mac's passphrase, so both open the same notes.",
                        header, pairing,
                    )
                } else {
                    title("Paired")
                    text("This phone and ${pairing.otherNameOrMac()} now keep the same notes whenever both are open on this network.")
                    page.addView(primaryButton("Done") { finish() }.margins(top = dp(24)))
                }
            }
            is PairingState.Failed -> {
                title("Pairing did not finish")
                text(pairing.message)
                page.addView(primaryButton("Try Again") { notes.sync.restartPairing() }.margins(top = dp(24)))
            }
        }
    }

    private fun PairingState.Paired.otherNameOrMac() = peer.name.ifBlank { "your Mac" }

    private fun showPaired(peers: List<SyncPeer>, states: Map<com.syncednotes.core.Id, PeerState>) {
        if (peers.isEmpty()) {
            title("Pair with your Mac")
            page.addView(primaryButton("Pair") { notes.sync.openPairing() }.margins(top = dp(16)))
            return
        }
        title("Sync")
        for (peer in peers) {
            val state = when (val s = states[peer.deviceId]) {
                is PeerState.Connected -> s.lastSynced?.let {
                    "Connected. In sync ${sinceInWords(it)}."
                } ?: "Connected."
                is PeerState.Failed -> s.message
                else -> "Looking for it on this network…"
            }
            val card = column(
                label(peer.name, 18f, bold = true),
                label(state, 15f, Palette.SECONDARY).margins(top = dp(4)),
                plainButton("Unpair", Palette.DANGER) {
                    AlertDialog.Builder(this).setTitle("Unpair ${peer.name}?")
                        .setMessage("The notes stay on both devices; they just stop syncing. Pairing again takes a moment.")
                        .setPositiveButton("Unpair") { _, _ -> notes.sync.unpair(peer.deviceId) }
                        .setNegativeButton("Cancel", null).show()
                }.margins(top = dp(8), width = LinearLayout.LayoutParams.WRAP_CONTENT),
                padding = dp(18),
            ).apply { background = rounded(Palette.CARD, dp(14).toFloat()) }
            page.addView(card.margins(top = dp(20)))
        }
        text("Sync runs while SyncedNotes is open and unlocked on both, on the same network. Nothing goes through the internet.", Palette.SECONDARY)
            .margins(top = dp(20))
    }

    /**
     * The Mac's passphrase, to open its vault and keep the phone's notes in it. [pairing] is
     * the pairing that brought the header, or null when the Mac's passphrase changed since.
     */
    private fun askPassphrase(heading: String, explanation: String, header: VaultHeader, pairing: PairingState.Paired?) {
        asking = true
        page.removeAllViews()
        title(heading)
        text(explanation)
        val field = privateField("Your Mac's passphrase", secret = true)
        page.addView(field.margins(top = dp(24), bottom = dp(16)))
        val problem = label("", 14f, Palette.DANGER).apply { visibility = View.GONE }
        lateinit var go: TextView
        go = primaryButton("Continue") {
            val passphrase = field.text.toString()
            if (passphrase.isEmpty()) return@primaryButton
            go.isEnabled = false
            go.text = "Locking the notes…"
            problem.visibility = View.GONE
            val identity: SyncIdentity = pairing?.identity ?: notes.store?.syncIdentity() ?: return@primaryButton
            thread {
                val failure = runCatching { notes.join(header, passphrase, identity, pairing?.peer) }.exceptionOrNull()
                runOnUiThread {
                    go.isEnabled = true
                    go.text = "Continue"
                    if (failure == null) {
                        asking = false
                        if (pairing == null) recreate() else finishPaired()
                    } else {
                        problem.text = if (failure is LockException) "That is not the Mac's passphrase." else "The notes could not be locked again: ${failure.message}"
                        problem.visibility = View.VISIBLE
                    }
                }
            }
        }
        page.addView(go)
        page.addView(problem.margins(top = dp(12)))
        field.requestFocus()
    }

    private fun finishPaired() {
        notes.sync.closePairing()
        // The notes start afresh, with nothing of the lock screen left under them.
        startActivity(android.content.Intent(this, NotesActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun title(text: String) {
        page.addView(label(text, 26f, bold = true).apply { gravity = Gravity.CENTER }.margins(top = dp(24), bottom = dp(12)))
    }

    private fun text(text: String, color: Int = Palette.TEXT): View =
        label(text, 16f, color).apply { gravity = Gravity.CENTER }.also { page.addView(it) }

    private fun code(code: String) {
        val spaced = code.substring(0, 3) + " " + code.substring(3)
        page.addView(
            label(spaced, 44f, bold = true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.08f
                typeface = android.graphics.Typeface.MONOSPACE
            }.margins(top = dp(24), bottom = dp(24)),
        )
    }
}
