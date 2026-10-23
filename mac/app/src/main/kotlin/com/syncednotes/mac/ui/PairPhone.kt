package com.syncednotes.mac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncednotes.core.sync.SyncService
import com.syncednotes.core.sync.SyncService.PairingState
import com.syncednotes.core.sync.SyncService.PeerState
import com.syncednotes.mac.NotesModel
import java.time.Duration
import java.time.Instant

/**
 * Pairing a phone, and the phones paired (Docs/Sync.md, "Pairing"): the phone's pairing screen
 * announces it, it is listed here, and both screens show a code to compare. Open for as long as
 * this sheet is; closing it ends any pairing under way.
 */
@Composable
fun PairPhoneSheet(model: NotesModel, sync: SyncService) {
    val c = colors
    val pairing by sync.pairing.collectAsState()
    val phones by sync.phonesReadyToPair.collectAsState()
    val peers by sync.peers.collectAsState()
    val states by sync.peerStates.collectAsState()
    DisposableEffect(sync) {
        sync.openPairing()
        onDispose { sync.closePairing() }
    }
    val close = { model.pairingPhone = false }

    Sheet(width = 440.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val p = pairing) {
                is PairingState.Confirming -> {
                    BasicText("Compare the codes", style = MacType.title.copy(color = c.text))
                    BasicText("Does ${p.otherName.ifBlank { "the phone" }} show this same code?", style = MacType.body.copy(color = c.secondaryText))
                    Code(p.code)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        MacButton("Cancel", { sync.confirmPairing(false) })
                        MacButton("The Codes Match", { sync.confirmPairing(true) }, primary = true)
                    }
                }
                is PairingState.WaitingForOther -> {
                    BasicText("Compare the codes", style = MacType.title.copy(color = c.text))
                    Code(p.code)
                    BasicText("Confirm on the phone too.", style = MacType.body.copy(color = c.secondaryText))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MacButton("Cancel", close) }
                }
                is PairingState.Paired -> {
                    BasicText("Paired", style = MacType.title.copy(color = c.text))
                    BasicText(
                        "${p.peer.name} and this Mac now keep the same notes whenever both are open on this network. " +
                            "The phone asks for this Mac's passphrase once, to open the same notes.",
                        style = MacType.body.copy(color = c.secondaryText),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MacButton("Done", close, primary = true) }
                }
                is PairingState.Failed -> {
                    BasicText("Pairing did not finish", style = MacType.title.copy(color = c.text))
                    BasicText(p.message, style = MacType.body.copy(color = c.secondaryText))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        MacButton("Close", close)
                        MacButton("Try Again", { sync.restartPairing() }, primary = true)
                    }
                }
                PairingState.Connecting -> {
                    BasicText("Pair a Phone", style = MacType.title.copy(color = c.text))
                    BasicText("Connecting to the phone…", style = MacType.body.copy(color = c.secondaryText))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MacButton("Cancel", close) }
                }
                else -> {
                    BasicText("Pair a Phone", style = MacType.title.copy(color = c.text))
                    BasicText(
                        "On the phone, open SyncedNotes and choose Pair with your Mac. It shows up here, on the same Wi-Fi network.",
                        style = MacType.body.copy(color = c.secondaryText),
                    )
                    Column(
                        Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(8.dp)).padding(vertical = 4.dp),
                    ) {
                        if (phones.isEmpty()) {
                            BasicText("Looking for phones…", Modifier.padding(12.dp), style = MacType.body.copy(color = c.tertiaryText))
                        }
                        for (phone in phones) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                BasicText(phone.name ?: "A phone", Modifier.weight(1f), style = MacType.body.copy(color = c.text))
                                MacButton("Pair", { sync.pairWith(phone) }, primary = true)
                            }
                        }
                    }
                    if (peers.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        BasicText("Paired", style = MacType.headline.copy(color = c.text))
                        for (peer in peers) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    BasicText(peer.name, style = MacType.body.copy(color = c.text))
                                    BasicText(describe(states[peer.deviceId]), style = MacType.small.copy(color = c.secondaryText))
                                }
                                MacButton("Unpair", { sync.unpair(peer.deviceId) }, destructive = true)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MacButton("Done", close, primary = true) }
                }
            }
        }
    }
}

@Composable
private fun Code(code: String) {
    val c = colors
    BasicText(
        code.substring(0, 3) + " " + code.substring(3),
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        style = MacType.title.copy(color = c.text, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
    )
}

private fun describe(state: PeerState?): String = when (state) {
    is PeerState.Connected -> state.lastSynced?.let { "In sync, ${ago(it)}" } ?: "Connected"
    is PeerState.Failed -> state.message
    else -> "Not on this network right now"
}

private fun ago(t: Instant): String {
    val seconds = Duration.between(t, Instant.now()).seconds
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        else -> "${seconds / 3600} h ago"
    }
}
