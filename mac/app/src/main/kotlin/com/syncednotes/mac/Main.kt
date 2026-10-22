package com.syncednotes.mac

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SyncedNotes",
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
    ) {
        NoteCanvas(Modifier.fillMaxSize())
    }
}
