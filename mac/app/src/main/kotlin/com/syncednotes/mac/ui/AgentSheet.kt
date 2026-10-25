package com.syncednotes.mac.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncednotes.core.Id
import com.syncednotes.mac.NotesModel
import com.syncednotes.mac.Pasteboard
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sharing a folder with an AI agent (Docs/AIAgents.md): first what that means, said plainly,
 * since it is the one way a note leaves this Mac and the phone; then, once shared, how to
 * connect Claude Code, opencode or Claude Desktop, each ready to copy.
 */
@Composable
fun AgentSheet(model: NotesModel, folderId: Id) {
    val c = colors
    val folder = model.folders.firstOrNull { it.id == folderId }
    val close = { model.sharingWithAgent = null }
    if (folder == null) {
        // The folder was deleted while the sheet was open: there is nothing left to share.
        androidx.compose.runtime.LaunchedEffect(Unit) { close() }
        return
    }
    val shared = model.agentFolder == folderId
    Sheet(width = 560.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!shared) {
                BasicText("Share “${folder.name}” with an AI agent?", style = MacType.headline.copy(color = c.text))
                BasicText(
                    "An AI agent you connect, such as Claude Code, Claude Desktop or opencode, will be able to read, change " +
                        "and add notes in this folder and the folders inside it, while SyncedNotes is unlocked on this Mac. " +
                        "It sees nothing else: not your other notes, not even their titles.",
                    style = MacType.body.copy(color = c.secondaryText),
                )
                BasicText(
                    "What the agent reads goes to the company that runs its model, as everything you give that agent does. " +
                        "These notes then leave this Mac and your phone, which no other note ever does. " +
                        "Keep passwords and keys out of this folder.",
                    style = MacType.body.copy(color = c.text),
                )
                model.agentFolder?.let { other ->
                    model.folders.firstOrNull { it.id == other }?.let {
                        BasicText("Only one folder is shared at a time: this takes the place of “${it.name}”.", style = MacType.body.copy(color = c.secondaryText))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    MacButton("Cancel", close)
                    MacButton("Share Folder", { model.shareWithAgent(folderId) }, primary = true)
                }
            } else {
                BasicText("“${folder.name}” is shared with AI agents", style = MacType.headline.copy(color = c.text))
                val url = model.agentUrl
                BasicText(
                    if (url == null) "The server is not running; see the message in the window."
                    else "Agents connect to SyncedNotes on this Mac only, at $url, with the token below. They can work on the folder whenever the notes are unlocked.",
                    style = MacType.body.copy(color = c.secondaryText),
                )
                if (url != null) {
                    val token = model.agentToken
                    Setup("Claude Code", "Run this in Terminal once:", claudeCode(url, token))
                    Setup("opencode", "Put this in ~/.config/opencode/opencode.json:", opencode(url, token))
                    Setup("Claude Desktop", "In Settings > Developer > Edit Config, put this in claude_desktop_config.json, then restart Claude:", claudeDesktop(url, token))
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MacButton("Stop Sharing", { model.stopSharingWithAgent(); close() }, destructive = true)
                    Tooltip("Agents given the current token can no longer get in, until given the new one") {
                        MacButton("New Token", { model.newAgentToken() })
                    }
                    Spacer(Modifier.weight(1f))
                    MacButton("Done", close, primary = true)
                }
            }
        }
    }
}

/** One way to connect, with the text to paste and a button that copies it. */
@Composable
private fun Setup(app: String, how: String, text: String) {
    val c = colors
    var copied by remember(text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(app, Modifier.weight(1f), style = MacType.bodyStrong.copy(color = c.text))
            MacButton(if (copied) "Copied" else "Copy", {
                // For this Mac only, as everything the app copies (Pasteboard).
                Pasteboard.copy(text)
                copied = true
            })
        }
        BasicText(how, style = MacType.small.copy(color = c.secondaryText))
        Box(Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(6.dp)).padding(8.dp)) {
            BasicText(text, style = MacType.small.copy(color = c.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp))
        }
    }
}

private fun claudeCode(url: String, token: String) =
    "claude mcp add --transport http --scope user syncednotes $url --header \"Authorization: Bearer $token\""

private fun opencode(url: String, token: String) = """
    {
      "${'$'}schema": "https://opencode.ai/config.json",
      "mcp": {
        "syncednotes": {
          "type": "remote",
          "url": "$url",
          "enabled": true,
          "headers": { "Authorization": "Bearer $token" }
        }
      }
    }
""".trimIndent()

/**
 * Claude Desktop starts the servers it uses as programs of its own and speaks to them on their
 * standard input and output. This one is the Mac's own shell and curl, passing each message to
 * the app and printing its answer: nothing to install, and short enough to read through.
 */
private fun claudeDesktop(url: String, token: String): String {
    val bridge = "while IFS= read -r line; do r=\$(curl -s -H 'Authorization: Bearer $token' -H 'Content-Type: application/json' " +
        "-H 'Accept: application/json, text/event-stream' --data-binary \"\$line\" $url); [ -n \"\$r\" ] && printf '%s\\n' \"\$r\"; done"
    return """
        {
          "mcpServers": {
            "syncednotes": {
              "command": "/bin/sh",
              "args": ["-c", ${JsonPrimitive(bridge)}]
            }
          }
        }
    """.trimIndent()
}
