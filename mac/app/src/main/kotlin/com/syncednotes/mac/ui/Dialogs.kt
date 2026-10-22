package com.syncednotes.mac.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.syncednotes.mac.NotesModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A question with one way forward, which may not be undone: the Mac's confirmation dialog. */
@Composable
fun ConfirmSheet(title: String, message: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, destructive: Boolean = true) {
    val c = colors
    Sheet(width = 360.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(title, style = MacType.headline.copy(color = c.text))
            BasicText(message, style = MacType.body.copy(color = c.secondaryText))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                MacButton("Cancel", onCancel)
                MacButton(confirm, onConfirm, primary = !destructive, destructive = destructive)
            }
        }
    }
}

/** Something to hear about, with an OK. */
@Composable
fun AlertSheet(title: String, message: String, onDismiss: () -> Unit) {
    val c = colors
    Sheet(width = 380.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(title, style = MacType.headline.copy(color = c.text))
            BasicText(message, style = MacType.body.copy(color = c.secondaryText))
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MacButton("OK", onDismiss, primary = true) }
        }
    }
}

/**
 * Asks for the passphrase in use and a new one, changes it, and shows that it worked. While
 * the notes are locked again the sheet stays: closing it then would leave the change running
 * unseen.
 */
@Composable
fun ChangePassphraseSheet(model: NotesModel) {
    val c = colors
    val current = rememberTextFieldState()
    val new = rememberTextFieldState()
    val repeated = rememberTextFieldState()
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val second = remember { FocusRequester() }
    val third = remember { FocusRequester() }

    fun change() {
        if (working || current.text.isEmpty() || new.text.isEmpty()) return
        working = true
        message = null
        scope.launch {
            try {
                model.changePassphrase(current.text.toString(), new.text.toString(), repeated.text.toString())
                done = true
            } catch (e: NotesModel.Message) {
                message = e.message
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(100)
        first.requestFocus()
    }

    Sheet(width = 400.dp) {
        if (done) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText("Passphrase changed", style = MacType.title.copy(color = c.text))
                BasicText(
                    "Every note and picture is now locked with the new passphrase. Backups made before still open only with the old one, so export a new backup.",
                    style = MacType.body.copy(color = c.secondaryText),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MacButton("Done", { model.changingPassphrase = false }, primary = true)
                }
            }
            return@Sheet
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("Change Passphrase", style = MacType.title.copy(color = c.text))
            BasicText(
                "Every note and picture is locked again with the new passphrase. The old one will no longer open anything on this Mac.",
                style = MacType.body.copy(color = c.secondaryText),
            )
            PassphraseField(current, "Current passphrase", focusRequester = first, onSubmit = { second.requestFocus() })
            PassphraseField(new, "New passphrase", focusRequester = second, onSubmit = { third.requestFocus() })
            PassphraseField(repeated, "The new passphrase again", focusRequester = third, onSubmit = ::change)
            message?.let { BasicText(it, style = MacType.body.copy(color = c.danger)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (working) BasicText("Locking every note again…", style = MacType.body.copy(color = c.secondaryText))
                Spacer(Modifier.weight(1f))
                MacButton("Cancel", { model.changingPassphrase = false }, enabled = !working)
                MacButton("Change", ::change, primary = true, enabled = !working && current.text.isNotEmpty() && new.text.isNotEmpty())
            }
        }
    }
}

/**
 * Asks for the passphrase of the vault a backup came from, when it is not this one's: a backup
 * made before the passphrase was changed.
 */
@Composable
fun BackupPassphraseSheet(model: NotesModel) {
    val c = colors
    val passphrase = rememberTextFieldState()
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun submit() {
        if (passphrase.text.isEmpty()) return
        if (!model.importBackup(passphrase.text.toString())) {
            wrong = true
            passphrase.edit { replace(0, length, "") }
            focus.requestFocus()
        }
    }

    LaunchedEffect(Unit) {
        delay(100)
        focus.requestFocus()
    }

    Sheet(width = 380.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("This backup was locked with another passphrase", style = MacType.headline.copy(color = c.text))
            BasicText(
                "Enter the passphrase it was made with. Its notes are then locked again with your current one.",
                style = MacType.body.copy(color = c.secondaryText),
            )
            PassphraseField(passphrase, "The backup's passphrase", focusRequester = focus, onSubmit = ::submit)
            if (wrong) BasicText("That is not this backup's passphrase.", style = MacType.body.copy(color = c.danger))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                MacButton("Cancel", { model.importWaitingForPassphrase = null })
                MacButton("Import", ::submit, primary = true, enabled = passphrase.text.isNotEmpty())
            }
        }
    }
}
