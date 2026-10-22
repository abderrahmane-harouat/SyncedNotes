package com.syncednotes.mac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syncednotes.mac.NotesModel
import kotlinx.coroutines.delay

/** Asks for the passphrase: to choose one the first time, and to unlock every time after. */
@Composable
fun LockScreen(model: NotesModel, firstRun: Boolean) {
    val c = colors
    val passphrase = rememberTextFieldState()
    val repeated = rememberTextFieldState()
    var message by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val second = remember { FocusRequester() }

    fun submit() {
        if (passphrase.text.isEmpty()) return
        if (firstRun) {
            try {
                model.create(passphrase.text.toString(), repeated.text.toString())
            } catch (e: NotesModel.Message) {
                message = e.message
                repeated.edit { replace(0, length, "") }
                if (passphrase.text.length < NotesModel.SHORTEST_PASSPHRASE) first.requestFocus() else second.requestFocus()
            }
        } else if (!model.unlock(passphrase.text.toString())) {
            message = model.problem ?: "That is not the passphrase."
            model.problem = null
            passphrase.edit { replace(0, length, "") }
            first.requestFocus()
        }
    }

    // Ready to type into straight away, without a click first, once the window is up.
    LaunchedEffect(Unit) {
        delay(150)
        first.requestFocus()
    }

    Box(Modifier.fillMaxSize().background(c.window), contentAlignment = Alignment.Center) {
        Column(Modifier.width(340.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.lockFilled, c.secondaryText, size = 38.dp)
            BasicText(if (firstRun) "Choose a passphrase" else "Enter your passphrase", style = MacType.title.copy(color = c.text))
            if (firstRun) {
                BasicText(
                    "It locks every note. There is no way to recover it, and no copy is kept anywhere, so choose one you will remember.",
                    style = MacType.body.copy(color = c.secondaryText, textAlign = TextAlign.Center),
                )
            }
            PassphraseField(passphrase, "Passphrase", focusRequester = first, onSubmit = {
                if (firstRun && repeated.text.isEmpty()) second.requestFocus() else submit()
            })
            if (firstRun) PassphraseField(repeated, "The same passphrase again", focusRequester = second, onSubmit = ::submit)
            message?.let {
                BasicText(it, style = MacType.body.copy(color = c.danger, textAlign = TextAlign.Center))
            }
            Spacer(Modifier.height(2.dp))
            MacButton(if (firstRun) "Create" else "Unlock", ::submit, primary = true, enabled = passphrase.text.isNotEmpty())
        }
    }
}
